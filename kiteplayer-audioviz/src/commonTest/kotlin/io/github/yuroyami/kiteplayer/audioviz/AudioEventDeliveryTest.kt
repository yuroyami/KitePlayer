package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioEventDeliveryTest {
    private fun detection(time: Long, kind: AudioEventKind = AudioEventKind.LowTransient, strength: Float = 0.4f,
        available: Long = time + 20_000L) = AudioDetection(kind, time, available, strength, 0.8f, 0.3f)

    private fun publish(history: AudioEventHistory, vararg detections: AudioDetection, complete: Long = detections.last().ptsMicros,
        available: Long = maxOf(complete, detections.maxOfOrNull { it.availableMicros } ?: complete)): Boolean =
        history.publish(AudioDetections(available, complete, arrayOf(*detections)))

    @Test
    fun twoHitsAndCoincidentKindsReachEachViewOnceInSequenceOrder() {
        val history = AudioEventHistory(Generation.Initial, 0L)
        val first = AudioEventCursor { history }
        val second = AudioEventCursor { history }
        first.sample(0L)
        second.sample(0L)
        assertTrue(publish(history, detection(5_000L), detection(10_000L, strength = 0.2f),
            detection(10_000L, AudioEventKind.HighTransient)))
        val received = first.sample(16_000L)
        assertEquals(3, received.size)
        assertEquals(listOf(0L, 1L, 2L), (0 until received.size).map { received[it].event.sequence })
        assertEquals(listOf(5_000L, 10_000L, 10_000L), (0 until received.size).map { received[it].event.detection.ptsMicros })
        assertEquals(0.2f, received[1].event.detection.strength)
        assertEquals(0.8f, received[1].event.detection.confidence)
        assertEquals(0.3f, received[1].event.detection.surprise)
        assertTrue((0 until received.size).all { received[it].lateByMicros == 0L })
        assertEquals(0, first.sample(16_000L).size)
        assertEquals(3, second.sample(16_000L).size)
        assertEquals(0, second.sample(16_000L).size)
    }

    @Test
    fun aFutureEventWaitsForItsOriginalTime() {
        val history = AudioEventHistory(Generation.Initial, 0L)
        val cursor = AudioEventCursor { history }
        cursor.sample(0L)
        publish(history, detection(10_000L))
        assertEquals(0, cursor.sample(9_999L).size)
        assertEquals(10_000L, cursor.sample(10_000L)[0].event.detection.ptsMicros)
        assertEquals(0, cursor.sample(10_001L).size)
    }

    @Test
    fun completionWatermarkAndLateArrivalAreIndependentOfTheDisplayCursor() {
        val history = AudioEventHistory(Generation.Initial, 0L)
        val cursor = AudioEventCursor { history }
        assertNull(cursor.sample(0L).completeThroughMicros)
        publish(history, complete = 5_000L, available = 30_000L)
        assertEquals(5_000L, cursor.sample(10_000L).completeThroughMicros)
        publish(history, detection(8_000L, available = 35_000L))
        val late = cursor.sample(20_000L)
        assertEquals(1, late.size)
        assertEquals(12_000L, late[0].lateByMicros)
        assertEquals(8_000L, late[0].event.detection.ptsMicros)
        assertEquals(35_000L, late[0].event.detection.availableMicros)
        assertEquals(8_000L, late.completeThroughMicros)
        assertEquals(0, cursor.sample(20_000L).size)
        publish(history, detection(9_000L, available = 50_000L))
        val tooLate = cursor.sample(50_000L)
        assertEquals(0, tooLate.size)
        assertEquals(1L, tooLate.lateDiscards)
        assertEquals(1L, cursor.lateDiscards)
        assertEquals(0L, cursor.sample(51_000L).lateDiscards)
    }

    @Test
    fun overflowResetsAtNowAndKeepsFutureEventsWithoutReplayingPastBursts() {
        val history = AudioEventHistory(Generation.Initial, 0L, capacity = 4)
        val cursor = AudioEventCursor { history }
        cursor.sample(0L)
        publish(history, *listOf(1L, 2L, 3L, 4L, 5L, 6L, 8L).map { detection(it * 1_000) }.toTypedArray())
        assertEquals(3L, history.snapshot.evictedEvents)
        assertEquals(4 * 64L, history.snapshot.retainedPayloadBytes)
        val dropped = cursor.sample(5_000L)
        assertEquals(0, dropped.size)
        assertEquals(5L, dropped.catchUpDiscards)
        assertTrue(dropped.reset)
        val future = cursor.sample(9_000L)
        assertEquals(listOf(6_000L, 8_000L), (0 until future.size).map { future[it].event.detection.ptsMicros })
        assertEquals(5L, cursor.catchUpDiscards)
    }

    @Test
    fun mediaTimeRetentionExpiresEvenWhenNoNewEventsArrive() {
        val history = AudioEventHistory(Generation.Initial, 0L, retentionMicros = 100_000L)
        publish(history, detection(0L), complete = 0L, available = 20_000L)
        assertEquals(64L, history.snapshot.retainedPayloadBytes)
        publish(history, complete = 120_000L, available = 140_000L)
        assertEquals(0L, history.snapshot.retainedPayloadBytes)
        assertEquals(1L, history.snapshot.evictedEvents)
    }

    @Test
    fun longStallsAndPausesDiscardPastEventsIncludingDelayedConfirmations() {
        val history = AudioEventHistory(Generation.Initial, 0L)
        val cursor = AudioEventCursor { history }
        cursor.sample(0L)
        publish(history, detection(100_000L), detection(200_000L), detection(400_000L))
        val stalled = cursor.sample(300_001L)
        assertEquals(0, stalled.size)
        assertTrue(stalled.reset)
        assertEquals(2L, stalled.catchUpDiscards)
        assertEquals(1, cursor.sample(410_000L).size)
        publish(history, detection(420_000L))
        assertEquals(0, cursor.sample(430_000L, paused = true).size)
        publish(history, detection(425_000L), detection(440_000L))
        val resumed = cursor.sample(450_000L)
        assertEquals(1, resumed.size)
        assertEquals(440_000L, resumed[0].event.detection.ptsMicros)
        assertEquals(1L, resumed.catchUpDiscards)
    }

    @Test
    fun historyReplacementAndBackwardClockNeverReviveOldEvents() {
        var history = AudioEventHistory(Generation.Initial, 0L)
        val cursor = AudioEventCursor { history }
        cursor.sample(0L)
        publish(history, detection(10_000L))
        assertEquals(1, cursor.sample(20_000L).size)
        assertEquals(0, cursor.sample(0L).size)
        assertEquals(0, cursor.sample(20_000L).size)
        val retired = history
        history = AudioEventHistory(Generation(1), 1L)
        publish(retired, detection(30_000L))
        publish(history, detection(10_000L), detection(40_000L))
        val reset = cursor.sample(20_000L)
        assertEquals(Generation(1), reset.generation)
        assertEquals(1L, reset.analysisRevision)
        assertEquals(0, reset.size)
        assertEquals(1L, reset.catchUpDiscards)
        val current = cursor.sample(40_000L)
        assertEquals(1, current.size)
        assertEquals(Generation(1), current[0].event.generation)
        assertEquals(1L, current[0].event.analysisRevision)
    }

    @Test
    fun malformedOrRegressingPublicationsAreRejectedWithoutAdvancingTheWatermark() {
        val history = AudioEventHistory(Generation.Initial, 0L)
        assertTrue(publish(history, detection(10_000L), available = 30_000L))
        assertFalse(publish(history, detection(10_000L), available = 40_000L))
        assertFalse(publish(history, detection(20_000L), detection(15_000L), complete = 20_000L, available = 40_000L))
        assertFalse(publish(history, complete = 20_000L, available = 25_000L))
        assertFalse(publish(history, detection(50_000L), complete = 40_000L, available = 70_000L))
        assertEquals(10_000L, history.snapshot.completeThroughMicros)
        assertEquals(1L, history.snapshot.nextSequence)
        assertEquals(4L, history.rejectedPublications)
    }
}
