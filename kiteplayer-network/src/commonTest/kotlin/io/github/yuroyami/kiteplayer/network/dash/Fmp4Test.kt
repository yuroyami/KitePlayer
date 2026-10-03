package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

    @Test
    fun anotherTracksFragmentGivesNoSample() {
        val track = Fmp4.tracks(Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp")).single()
        assertEquals(emptyList(), Fmp4.samples(Mp4Bytes.segment(2, decodeTime = 0, listOf(Sample(1000, byteArrayOf(1)))), track))
    }
}
