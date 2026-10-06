package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A later Period's MP4 fragments written for the initialization the stream began with (#403). */
class Fmp4RewriteTest {

    private val sync = 0L
    private val nonSync = Fmp4.NON_SYNC

    private fun track(init: ByteArray) = Fmp4.tracks(init).single()

    private val avcA = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 1, 1), pps = byteArrayOf(0x68, 1))
    private val avcB = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 2, 2, 2), pps = byteArrayOf(0x68, 2))

    @Test
    fun aSegmentThatNeedsNothingIsLeftAlone() {
        val init = track(Mp4Bytes.init(1, 1000, "soun", "mp4a"))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(1))))
        assertSame(segment, Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, shiftMicros = 0, endMicros = null)))
    }

    @Test
    fun aSegmentThatNeedsNothingIsNotReadAtAll() {
        // A run that asks for four billion samples is refused only when the samples must be read (#474).
        val init = track(Mp4Bytes.init(1, 1000, "soun", "mp4a"))
        val segment = Mp4Bytes.defaultRuns(1, listOf(0xFFFF_FFFFL))
        assertSame(segment, Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, shiftMicros = 0, endMicros = null)))
        assertFailsWith<DashUnsupportedException> {
            Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, shiftMicros = 1_000, endMicros = null))
        }
    }

    @Test
    fun anH264PeriodCarriesItsParameterSetsEvenWhenTheyAreTheStreamsOwn() {
        // A decoder that played another Period holds that one's parameter sets, so a Period whose
        // configuration is the stream's own must restate it.
        val init = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(9), sync)))
        val samples = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, 0, null)), init)
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 0x67, 1, 1, 0, 0, 0, 2, 0x68, 1, 9), samples.single().data)
    }

    @Test
    fun aPeriodOfAnotherCodecIsRefused() {
        val avc = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val hevc = track(Mp4Bytes.init(1, 1000, "vide", "hvc1"))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(1), sync)))
        assertFailsWith<DashUnsupportedException> { Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(hevc, avc, 0, null)) }
        val avc3 = track(Mp4Bytes.init(1, 1000, "vide", "avc3", entry = avcA.copyOf().also { "avc3".encodeToByteArray().copyInto(it, 4) }))
        Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(avc3, avc, 0, null))
    }

    @Test
    fun timesMoveOntoThePresentationsTimeline() {
        val init = track(Mp4Bytes.init(1, 1000, "soun", "mp4a"))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(1, 2), sync), Sample(1000, byteArrayOf(3), nonSync)))
        val written = Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, shiftMicros = 20_000_000, endMicros = null))
        val samples = Fmp4.samples(written, init)
        assertEquals(listOf(20_000L, 21_000L), samples.map { it.decodeTime })
        assertEquals(listOf(1000L, 1000L), samples.map { it.duration })
        assertEquals(listOf(true, false), samples.map { it.isSync })
        assertContentEquals(byteArrayOf(1, 2), samples[0].data)
        assertContentEquals(byteArrayOf(3), samples[1].data)
        assertTrue(Fmp4.boxes(written, 0, written.size).first().type == "styp", "the styp stays first")
    }

    @Test
    fun anotherTrackIdAndTimescaleAreWrittenAsTheTargets() {
        val source = track(Mp4Bytes.init(2, 90_000, "vide", "avc1", entry = avcA))
        val target = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        // Three frames at 30 a second, ten seconds in, with a composition offset on the first.
        val segment = Mp4Bytes.segment(
            2,
            900_000,
            listOf(Sample(3000, byteArrayOf(1), sync, 6000), Sample(3000, byteArrayOf(2), nonSync, 0), Sample(3000, byteArrayOf(3), nonSync, 0)),
        )
        val written = Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(source, target, shiftMicros = 0, endMicros = null))
        assertEquals(emptyList(), Fmp4.samples(written, source), "the source's track id is gone")
        val samples = Fmp4.samples(written, target)
        assertEquals(listOf(10_000L, 10_033L, 10_067L), samples.map { it.decodeTime })
        assertEquals(10_100L, samples.last().decodeTime + samples.last().duration, "the segment ends where it did, rounding and all")
        assertEquals(67L, samples.first().compositionOffset)
    }

    @Test
    fun samplesFromThePeriodsEndOnAreDropped() {
        val init = track(Mp4Bytes.init(1, 1000, "soun", "mp4a"))
        val segment = Mp4Bytes.segment(1, 4000, List(4) { Sample(500, byteArrayOf(it.toByte())) })
        val written = Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, shiftMicros = 0, endMicros = 5_000_000))
        assertEquals(listOf(4000L, 4500L), Fmp4.samples(written, init).map { it.decodeTime })
    }

    @Test
    fun anotherH264ConfigurationTravelsInBandBeforeEachSyncSample() {
        val source = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcB))
        val target = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(9), sync), Sample(1000, byteArrayOf(8), nonSync)))
        val samples = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(source, target, 0, null)), target)
        val parameterSets = byteArrayOf(0, 0, 0, 4, 0x67, 2, 2, 2, 0, 0, 0, 2, 0x68, 2)
        assertContentEquals(parameterSets + byteArrayOf(9), samples[0].data, "the Period's SPS and PPS lead its sync sample")
        assertContentEquals(byteArrayOf(8), samples[1].data, "a sample that is not a sync sample is left as it was")
    }

    @Test
    fun parameterSetsAreNotCarriedForAnotherCodecOrPrefixLength() {
        val avc = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val twoBytePrefix = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = Mp4Bytes.avc1(byteArrayOf(0x67), byteArrayOf(0x68), lengthSize = 2)))
        val opus = track(Mp4Bytes.init(1, 48_000, "soun", "Opus"))
        assertEquals(null, Fmp4Rewrite.inBandParameterSets(twoBytePrefix, avc))
        assertEquals(null, Fmp4Rewrite.inBandParameterSets(opus, opus))
    }

    @Test
    fun aDolbyVisionPeriodCarriesItsParameterSetsAsHevcDoes() {
        // dvh1 and dvhe are HEVC with an RPU, so a Period of either restates its VPS, SPS and PPS,
        // and joins a stream that began as hvc1.
        val vps = byteArrayOf(0x40, 1)
        val sps = byteArrayOf(0x42, 1, 7)
        val pps = byteArrayOf(0x44, 1)
        val dvh1 = track(Mp4Bytes.init(1, 1000, "vide", "dvh1", entry = Mp4Bytes.hevcEntry("dvh1", vps, sps, pps)))
        val hvc1 = track(Mp4Bytes.init(1, 1000, "vide", "hvc1", entry = Mp4Bytes.hevcEntry("hvc1", byteArrayOf(0x40, 2), byteArrayOf(0x42, 2), byteArrayOf(0x44, 2))))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(1000, byteArrayOf(0x26, 1), sync)))
        val samples = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(dvh1, hvc1, 0, null)), hvc1)
        val parameterSets = byteArrayOf(0, 0, 0, 2, 0x40, 1, 0, 0, 0, 3, 0x42, 1, 7, 0, 0, 0, 2, 0x44, 1)
        assertContentEquals(parameterSets + byteArrayOf(0x26, 1), samples.single().data)
        val dvhe = track(Mp4Bytes.init(1, 1000, "vide", "dvhe", entry = Mp4Bytes.hevcEntry("dvhe", vps, sps, pps)))
        assertContentEquals(parameterSets, Fmp4Rewrite.inBandParameterSets(dvhe, dvh1))
    }

    @Test
    fun aDolbyVisionH264PeriodCarriesItsParameterSetsAsH264Does() {
        val dva1 = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 3), pps = byteArrayOf(0x68, 3)).also { "dva1".encodeToByteArray().copyInto(it, 4) }
        val source = track(Mp4Bytes.init(1, 1000, "vide", "dva1", entry = dva1))
        val target = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 0x67, 3, 0, 0, 0, 2, 0x68, 3), Fmp4Rewrite.inBandParameterSets(source, target))
    }
}
