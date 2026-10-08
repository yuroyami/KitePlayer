package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.mp4.Fmp4
import io.github.yuroyami.kiteplayer.mp4.Fmp4Rewrite
import io.github.yuroyami.kiteplayer.mp4.Fmp4UnsupportedException
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
        assertFailsWith<Fmp4UnsupportedException> {
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
        assertFailsWith<Fmp4UnsupportedException> { Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(hevc, avc, 0, null)) }
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

    @Test
    fun eachTrackOfASegmentIsWrittenForItsOwnTarget() {
        // Pictures and sound in one segment, as a muxed HLS variant has them (#464).
        val picture = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcB))
        val sound = track(Mp4Bytes.init(2, 48_000, "soun", "mp4a"))
        val firstPicture = track(Mp4Bytes.init(5, 1000, "vide", "avc1", entry = avcA))
        val firstSound = track(Mp4Bytes.init(6, 48_000, "soun", "mp4a"))
        val segment = Mp4Bytes.segment(1, 4000, listOf(Sample(40, byteArrayOf(9), sync), Sample(40, byteArrayOf(8), nonSync))) +
            Mp4Bytes.segment(2, 192_000, listOf(Sample(1024, byteArrayOf(7))))
        val written = Fmp4Rewrite.rewrite(
            segment,
            listOf(Fmp4Rewrite.Plan(picture, firstPicture, 0, null), Fmp4Rewrite.Plan(sound, firstSound, 0, null)),
        )
        val pictures = Fmp4.samples(written, firstPicture)
        assertEquals(listOf(4000L, 4040L), pictures.map { it.decodeTime })
        assertContentEquals(byteArrayOf(0, 0, 0, 4, 0x67, 2, 2, 2, 0, 0, 0, 2, 0x68, 2, 9), pictures[0].data)
        assertContentEquals(byteArrayOf(8), pictures[1].data)
        val sounds = Fmp4.samples(written, firstSound)
        assertEquals(listOf(192_000L), sounds.map { it.decodeTime })
        assertContentEquals(byteArrayOf(7), sounds.single().data)
        assertEquals(emptyList(), Fmp4.samples(written, picture) + Fmp4.samples(written, sound), "the segment's own track ids are gone")
    }

    @Test
    fun aTrackWithNoPlanIsLeftOutOfAWrittenSegment() {
        val picture = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val other = track(Mp4Bytes.init(3, 1000, "meta", "mett"))
        val segment = Mp4Bytes.segment(1, 0, listOf(Sample(40, byteArrayOf(9), sync))) + Mp4Bytes.segment(3, 0, listOf(Sample(40, byteArrayOf(5))))
        val written = Fmp4Rewrite.rewrite(segment, listOf(Fmp4Rewrite.Plan(picture, picture, 0, null)))
        assertEquals(1, Fmp4.samples(written, picture).size)
        assertEquals(emptyList(), Fmp4.samples(written, other))
    }

    @Test
    fun timesFollowTheDifferenceOfTheTwoEditLists() {
        // The reader takes the first initialization's edit offset from every time. The source's
        // edit starts 80 ms into its media and the target's at once, so each time moves back 80 ms.
        val source = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA, edits = listOf(0L to 80L), movieTimescale = 1000))
        val target = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        assertEquals(80L, source.timeOffset)
        val segment = Mp4Bytes.segment(
            1,
            4000,
            listOf(Sample(40, byteArrayOf(1), sync, 80), Sample(40, byteArrayOf(2), nonSync, 160), Sample(40, byteArrayOf(3), nonSync, 0)),
        )
        val samples = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(source, target, 0, null)), target)
        assertEquals(listOf(3920L, 3960L, 4000L), samples.map { it.decodeTime })
        assertEquals(listOf(4000L, 4120L, 4000L), samples.map { it.decodeTime + it.compositionOffset }, "each picture shows 80 ms earlier in the media")
        val back = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(target, source, 0, null)), source)
        assertEquals(listOf(4080L, 4120L, 4160L), back.map { it.decodeTime }, "and the other way round, later")
    }

    @Test
    fun anEditOffsetIsScaledWhenTheTimescalesDiffer() {
        val source = track(Mp4Bytes.init(1, 90_000, "vide", "avc1", entry = avcA, edits = listOf(0L to 7200L), movieTimescale = 1000))
        val target = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA, edits = listOf(0L to 30L), movieTimescale = 1000))
        val segment = Mp4Bytes.segment(1, 360_000, listOf(Sample(3600, byteArrayOf(1), sync, 7200)))
        val sample = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(source, target, 0, null)), target).single()
        // 4000 ms, less the source's 80 ms, plus the 30 ms the reader will take away.
        assertEquals(3950L, sample.decodeTime)
        assertEquals(80L, sample.compositionOffset)
    }

    @Test
    fun aJoinedSegmentDecodesNoEarlierThanItsFirstPictureShows() {
        // Pictures that decode two frames ahead of what they show: I, P, then the two B between them.
        val init = track(Mp4Bytes.init(1, 1000, "vide", "avc1", entry = avcA))
        val segment = Mp4Bytes.segment(
            1,
            3920,
            listOf(
                Sample(40, byteArrayOf(1), sync, 80), Sample(40, byteArrayOf(2), nonSync, 160),
                Sample(40, byteArrayOf(3), nonSync, 40), Sample(40, byteArrayOf(4), nonSync, 40), Sample(40, byteArrayOf(5), nonSync, 160),
            ),
        )
        val shown = listOf(4000L, 4120L, 4040L, 4080L, 4240L)
        val plain = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, 0, null)), init)
        assertEquals(listOf(3920L, 3960L, 4000L, 4040L, 4080L), plain.map { it.decodeTime }, "a segment that follows its own stream keeps its decode times")
        val joined = Fmp4.samples(Fmp4Rewrite.rewrite(segment, Fmp4Rewrite.Plan(init, init, 0, null, joined = true)), init)
        assertEquals(listOf(4000L, 4001L, 4002L, 4040L, 4080L), joined.map { it.decodeTime }, "each rises, from the time the first picture shows")
        assertEquals(shown, joined.map { it.decodeTime + it.compositionOffset }, "and every picture shows when it did")
        assertEquals(shown, plain.map { it.decodeTime + it.compositionOffset })
        assertEquals(4120L, joined.last().decodeTime + joined.last().duration, "the segment ends where it did")
    }

    @Test
    fun soundJoinsWhenOnlyItsBitRateDiffers() {
        fun sound(entry: ByteArray) = track(Mp4Bytes.init(2, 48_000, "soun", "mp4a", entry = entry))
        val first = sound(Mp4Bytes.mp4a(bitRate = 96_000))
        assertTrue(Fmp4Rewrite.joins(sound(Mp4Bytes.mp4a(bitRate = 64_000)), first), "the decoder is set up the same way")
        assertTrue(!Fmp4Rewrite.sameCodec(sound(Mp4Bytes.mp4a(bitRate = 64_000)), first), "though the two entries differ")
        assertTrue(!Fmp4Rewrite.joins(sound(Mp4Bytes.mp4a(channels = 6)), first), "another channel count")
        assertTrue(!Fmp4Rewrite.joins(sound(Mp4Bytes.mp4a(sampleRate = 44_100)), first), "another sample rate")
        assertTrue(!Fmp4Rewrite.joins(sound(Mp4Bytes.mp4a(setup = byteArrayOf(0x12, 0x10))), first), "another decoder setup")
        assertTrue(!Fmp4Rewrite.joins(track(Mp4Bytes.init(2, 48_000, "soun", "ac-3")), first), "another codec")
    }

    @Test
    fun picturesJoinWhenTheirParameterSetsCanTravelInBand() {
        fun picture(entry: ByteArray, type: String = "avc1") = track(Mp4Bytes.init(1, 1000, "vide", type, entry = entry))
        val first = picture(avcA)
        assertTrue(Fmp4Rewrite.joins(picture(avcB), first))
        val shortLengths = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 2), pps = byteArrayOf(0x68, 2), lengthSize = 2)
        assertTrue(!Fmp4Rewrite.joins(picture(shortLengths), first), "every NAL unit would need its length written again")
        val hevc = Mp4Bytes.hevcEntry("hvc1", byteArrayOf(0x40), byteArrayOf(0x42), byteArrayOf(0x44))
        assertTrue(!Fmp4Rewrite.joins(picture(hevc, "hvc1"), first), "another codec")
        val vp9 = track(Mp4Bytes.init(1, 1000, "vide", "vp09", entry = Mp4Bytes.box("vp09", ByteArray(78) + byteArrayOf(1))))
        val otherVp9 = track(Mp4Bytes.init(1, 1000, "vide", "vp09", entry = Mp4Bytes.box("vp09", ByteArray(78) + byteArrayOf(2))))
        assertTrue(Fmp4Rewrite.joins(vp9, vp9) && !Fmp4Rewrite.joins(otherVp9, vp9), "a codec with no parameter sets needs the same configuration")
    }
}
