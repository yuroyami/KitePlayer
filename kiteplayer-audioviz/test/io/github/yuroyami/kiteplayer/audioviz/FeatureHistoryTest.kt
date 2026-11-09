package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureHistoryTest {
    private fun frame(at: Long, scope: FloatArray = FloatArray(4), availability: AnalysisAvailability = AnalysisAvailability.Ready) =
        SpectrumFrame(at, FloatArray(4), FloatArray(4), scope, 0.5f, 0f, 0f, 0f, 0f, 0f, availability = availability)

    @Test
    fun featureRetentionHasATimeBoundEvenWhenTheSlotCountIsLarge() {
        val timeline = SpectrumTimeline(1024)
        for (index in 0..200) timeline.push(frame(index * 50_000L))
        assertNull(timeline.at(4_950_000L))
        assertNotNull(timeline.at(5_000_000L))
        assertEquals(101, timeline.historyStats.retainedFrames)
        assertEquals(100L, timeline.historyStats.evictedFrames)
        assertEquals(5_000_000L, timeline.historyStats.retentionMicros)
    }

    /** A heard position a little more than two seconds behind the analysis still has a frame (#963). */
    @Test
    fun aHeardPositionWellBehindTheAnalysisStillFindsAFrame() {
        val timeline = SpectrumTimeline(512)
        for (index in 0..300) timeline.push(frame(index * 10_000L))
        assertNotNull(timeline.at(3_000_000L - 2_100_000L), "an output 2.1 s deep blanked the picture")
    }

    @Test
    fun payloadBoundsEvictOldFramesAndAnOversizedFrameCannotReplaceValidHistory() {
        val timeline = SpectrumTimeline(16)
        for (index in 0..2) timeline.push(frame(index * 10_000L, FloatArray(1_000_000)))
        assertNull(timeline.at(0L))
        assertEquals(2, timeline.historyStats.retainedFrames)
        assertEquals(1L, timeline.historyStats.evictedFrames)
        assertTrue(timeline.historyStats.retainedPayloadBytes <= 8 * 1024 * 1024L)
        timeline.push(frame(30_000L, FloatArray(2_200_000)))
        assertEquals(20_000L, timeline.newest()?.ptsMicros)
        assertEquals(1L, timeline.historyStats.rejectedOversizedFrames)
        assertEquals(2, timeline.historyStats.retainedFrames)
    }

    @Test
    fun lookaheadHorizonStopsAtMissingDataAndCannotStartInWarmup() {
        val timeline = SpectrumTimeline(16)
        timeline.push(frame(0L, availability = AnalysisAvailability.WarmingUp))
        timeline.push(frame(10_000L))
        timeline.push(frame(20_000L))
        timeline.push(frame(30_000L, availability = AnalysisAvailability.Unavailable))
        timeline.push(frame(40_000L))
        assertEquals(0f, timeline.availableAheadSeconds(5_000L))
        assertEquals(0.01f, timeline.availableAheadSeconds(10_000L))
        assertEquals(0f, timeline.availableAheadSeconds(30_000L))
    }

    @Test
    fun aSlotCapacityCannotAllocateAnUnboundedAtomicArray() {
        assertFailsWith<IllegalArgumentException> { SpectrumTimeline(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { SpectrumTimeline(1025) }
    }
}
