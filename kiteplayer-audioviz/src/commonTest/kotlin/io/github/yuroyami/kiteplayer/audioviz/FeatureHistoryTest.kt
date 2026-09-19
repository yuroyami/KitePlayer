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
        assertNull(timeline.at(7_950_000L))
        assertNotNull(timeline.at(8_000_000L))
        assertEquals(41, timeline.historyStats.retainedFrames)
        assertEquals(160L, timeline.historyStats.evictedFrames)
        assertEquals(2_000_000L, timeline.historyStats.retentionMicros)
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
