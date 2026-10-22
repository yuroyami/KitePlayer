package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Development fixtures on an injected activation curve, independent of the onset detector. */
class TempoContractTest {
    private val rate = 100f

    private fun clicks(tracker: TempoTracker, bpm: Float, seconds: Int, start: Int = 0, accent: Int = 1) {
        val period = rate * 60f / bpm
        repeat(seconds * rate.toInt()) { index ->
            val at = start + index
            val nearest = round(at / period)
            val distance = at - nearest * period
            val weight = if (nearest.toInt() % accent == 0) 1f else 0.6f
            val value = exp(-0.5f * distance * distance) * weight
            tracker.feed(value, if (abs(distance) <= 0.5f) weight else 0f, 1f / rate)
        }
    }

    @Test
    fun oldRhythmExpiresWhenNoNewAttacksArrive() {
        val tracker = TempoTracker(rate)
        clicks(tracker, 120f, 12)
        assertTrue(tracker.confidence > 0.6f, "the fixture must first acquire a rhythm")
        repeat(150) { tracker.feed(0f, 0f, 0.01f) }
        assertEquals(-1f, tracker.beatInSeconds, "old history must not predict hits through silence")
        assertFalse(tracker.beatTicked)
    }

    @Test
    fun aPeriodicAccentDoesNotEstablishMeterOrPhrases() {
        val tracker = TempoTracker(rate)
        clicks(tracker, 120f, 15, accent = 3)
        assertTrue(tracker.confidence > 0.4f)
        assertEquals(0f, tracker.alignedBarPhase(), "no downbeat evidence was provided")
        assertEquals(0f, tracker.alignedPhrasePhase(), "sixteen beats are not a measured phrase")
        assertFalse(tracker.barTicked)
    }

    @Test
    fun cleanSlowAndFastPulseEvidenceIsNotFoldedIntoTheOldTempoRange() {
        for (wanted in listOf(44f, 52f, 80f, 128f, 176f, 224f)) {
            val tracker = TempoTracker(rate)
            clicks(tracker, wanted, 18)
            println("pulse rate $wanted -> ${tracker.bpm} with support ${tracker.confidence}")
            assertTrue(abs(tracker.bpm - wanted) < 2f, "$wanted was folded to ${tracker.bpm}")
        }
    }

    @Test
    fun octaveSupportIsVisibleAndResetClearsEveryPrediction() {
        val tracker = TempoTracker(rate)
        clicks(tracker, 120f, 12)
        assertTrue(tracker.usable)
        assertEquals(60f, tracker.alternativeBpm, 1f)
        assertTrue(tracker.alternativeConfidence > 0.6f)
        tracker.reset()
        assertFalse(tracker.usable)
        assertEquals(0f, tracker.bpm)
        assertEquals(0f, tracker.confidence)
        assertEquals(0f, tracker.tempoConfidence)
        assertEquals(0f, tracker.alternativeConfidence)
        assertEquals(-1f, tracker.beatInSeconds)
        assertEquals(0f, tracker.beatPhase)
        assertFalse(tracker.beatTicked)
    }

    @Test
    fun aMajorTempoChangeDropsTheOldLockBeforeAcquiringTheNewRate() {
        val tracker = TempoTracker(rate)
        clicks(tracker, 120f, 12)
        assertTrue(tracker.usable)
        var lost = false
        repeat(12) { second ->
            clicks(tracker, 88f, 1, start = second * 100)
            if (!tracker.usable) lost = true
        }
        assertTrue(lost, "a conflicting grid must not inherit the old lock")
        assertTrue(tracker.usable, "clear new evidence must reacquire")
        assertEquals(88f, tracker.bpm, 1f)
    }
}
