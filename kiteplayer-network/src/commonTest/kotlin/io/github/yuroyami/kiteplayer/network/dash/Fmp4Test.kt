package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The fragments of an MP4 subtitle track, read from bytes written here (#402). */
class Fmp4Test {

    @Test
    fun anInitializationSegmentGivesItsTrack() {
        val track = Fmp4.tracks(Mp4Bytes.init(trackId = 3, timescale = 1000, handler = "subt", sampleEntry = "stpp", defaultDuration = 1500)).single()
        assertEquals(3L, track.id)
        assertEquals(1000L, track.timescale)
        assertEquals("subt", track.handler)
        assertEquals("stpp", track.sampleEntry)
        assertEquals(1500L, track.defaultDuration)
    }

    @Test
    fun aSegmentGivesItsSamplesWithTheirTimes() {
        val track = Fmp4.tracks(Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "text", sampleEntry = "wvtt")).single()
        val segment = Mp4Bytes.segment(1, decodeTime = 90_000, listOf(Sample(2000, byteArrayOf(1, 2, 3)), Sample(3000, byteArrayOf(4, 5))))
        val samples = Fmp4.samples(segment, track)
        assertEquals(listOf(90_000L, 92_000L), samples.map { it.decodeTime })
        assertEquals(listOf(2000L, 3000L), samples.map { it.duration })
        assertContentEquals(byteArrayOf(1, 2, 3), samples[0].data)
        assertContentEquals(byteArrayOf(4, 5), samples[1].data)
    }

    @Test
    fun aSampleWithNoDurationLeansOnTheTrackDefault() {
        val track = Fmp4.tracks(Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp", defaultDuration = 1500)).single()
        val samples = Fmp4.samples(Mp4Bytes.segment(1, decodeTime = 0, listOf(Sample(null, byteArrayOf(9)), Sample(null, byteArrayOf(8)))), track)
        assertEquals(listOf(0L, 1500L), samples.map { it.decodeTime })
    }

    /** A subtitle track whose samples last one tick and are [defaultSize] bytes unless a fragment says otherwise. */
    private fun defaultsTrack(defaultSize: Long = 0) =
        Fmp4.tracks(Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp", defaultDuration = 1, defaultSize = defaultSize)).single()

    @Test
    fun zeroSizeSamplesPastTheBudgetAreRefusedBeforeAnyIsMade() {
        // A few dozen bytes that ask for four billion samples of no bytes each (#474).
        val segment = Mp4Bytes.defaultRuns(1, listOf(0xFFFF_FFFFL))
        assertTrue(segment.size < 100, "the segment is ${segment.size} bytes")
        assertFailsWith<DashUnsupportedException> { Fmp4.fragments(segment, defaultsTrack()) }
    }

    @Test
    fun theBudgetCountsEveryRunOfTheSegment() {
        val track = defaultsTrack()
        assertEquals(10, Fmp4.samples(Mp4Bytes.defaultRuns(1, listOf(4, 6)), track, maxSamples = 10).size)
        assertFailsWith<DashUnsupportedException> { Fmp4.samples(Mp4Bytes.defaultRuns(1, listOf(5, 6)), track, maxSamples = 10) }
    }

    @Test
    fun zeroSizeSamplesWithinTheBudgetRead() {
        val samples = Fmp4.samples(Mp4Bytes.defaultRuns(1, listOf(3)), defaultsTrack())
        assertEquals(listOf(0L, 1L, 2L), samples.map { it.decodeTime })
        assertTrue(samples.all { it.data.isEmpty() })
    }

    @Test
    fun aRunWhoseSampleFieldsRunPastItsBoxIsRefused() {
        // A run that says it lists the sizes of 1000 samples and lists two.
        val trun = Mp4Bytes.fullBox("trun", 0, 0x1 or 0x200, Mp4Bytes.u32(1000) + Mp4Bytes.u32(0) + Mp4Bytes.u32(1) + Mp4Bytes.u32(1))
        val tfhd = Mp4Bytes.fullBox("tfhd", 0, 0x020000, Mp4Bytes.u32(1))
        val segment = Mp4Bytes.box("moof", Mp4Bytes.fullBox("mfhd", 0, 0, Mp4Bytes.u32(1)) + Mp4Bytes.box("traf", tfhd + trun)) +
            Mp4Bytes.box("mdat", ByteArray(2))
        val refused = assertFailsWith<IllegalArgumentException> { Fmp4.fragments(segment, defaultsTrack()) }
        assertEquals("a run's sample fields run past its box", refused.message)
    }

    @Test
    fun defaultSizedSamplesThatOverrunTheSegmentAreRefused() {
        val segment = Mp4Bytes.defaultRuns(1, listOf(1000), mdat = ByteArray(10), tfhdSize = 100)
        val refused = assertFailsWith<IllegalArgumentException> { Fmp4.fragments(segment, defaultsTrack()) }
        assertEquals("a sample lies outside its segment", refused.message)
    }

    @Test
    fun theFragmentsDefaultSizeIsReadForEachSample() {
        val segment = Mp4Bytes.defaultRuns(1, listOf(3), mdat = byteArrayOf(1, 2, 3, 4, 5, 6), tfhdSize = 2)
        val samples = Fmp4.samples(segment, defaultsTrack(defaultSize = 9))
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6)), samples.map { it.data.toList() })
    }

    @Test
    fun aBaseOffsetPastTheSegmentIsRefusedRatherThanCutToItsLowBits() {
        // The segment's last byte is the one sample's data, and 2^32 above it cut to 32 bits lands on it.
        val last = (Mp4Bytes.defaultRuns(1, listOf(1), mdat = byteArrayOf(7), tfhdSize = 1, baseOffset = 0).size - 1).toLong()
        assertEquals(listOf<Byte>(7), Fmp4.samples(Mp4Bytes.defaultRuns(1, listOf(1), mdat = byteArrayOf(7), tfhdSize = 1, baseOffset = last), defaultsTrack()).single().data.toList())
        val beyond = Mp4Bytes.defaultRuns(1, listOf(1), mdat = byteArrayOf(7), tfhdSize = 1, baseOffset = (1L shl 32) + last)
        assertFailsWith<IllegalArgumentException> { Fmp4.samples(beyond, defaultsTrack()) }
    }

    @Test
    fun anotherTracksFragmentGivesNoSample() {
        val track = Fmp4.tracks(Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp")).single()
        assertEquals(emptyList(), Fmp4.samples(Mp4Bytes.segment(2, decodeTime = 0, listOf(Sample(1000, byteArrayOf(1)))), track))
    }
}
