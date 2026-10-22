package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Key estimates on synthetic development fixtures, with unknown where no key is supported. */
class KeyTrackerTest {
    private fun keyAfter(samples: FloatArray, sampleRate: Int = 48_000): KeyTracker =
        KeyTracker(sampleRate).also { it.run(samples, sampleRate) }

    @Test
    fun aMajorProgressionReadsAsItsKey() {
        val tracker = keyAfter(TonalFixtures.progression(0, minor = false, seconds = 12f))
        val key = assertNotNull(tracker.key, "C major chords should give a key")
        assertEquals(0, key.tonic)
        assertEquals(KeyMode.Major, key.mode)
        assertTrue(key.confidence >= 0.5f, "confidence ${key.confidence}")
    }

    @Test
    fun aMinorProgressionReadsAsItsMinorKey() {
        val key = assertNotNull(keyAfter(TonalFixtures.progression(9, minor = true, seconds = 12f)).key)
        assertEquals(9, key.tonic)
        assertEquals(KeyMode.Minor, key.mode)
    }

    @Test
    fun detunedMusicReadsItsKeyAndItsTuning() {
        val tracker = keyAfter(TonalFixtures.progression(7, minor = false, seconds = 14f, detuneCents = 45f))
        val key = assertNotNull(tracker.key)
        assertEquals(7, key.tonic)
        assertEquals(KeyMode.Major, key.mode)
        assertTrue(abs(tracker.tuningCents - 45f) <= 10f, "tuning ${tracker.tuningCents}")
        // Corrected tuning keeps the profile on the scale instead of leaking into the next semitone.
        val scale = setOf(7, 9, 11, 0, 2, 4, 6)
        val onScale = tracker.chroma.indices.filter { it in scale }.sumOf { tracker.chroma[it].toDouble() }
        assertTrue(onScale / tracker.chroma.sum() >= 0.9, "on-scale share ${onScale / tracker.chroma.sum()}")
    }

    @Test
    fun alternatingNeighbourKeysDoNotFlicker() {
        val tracker = KeyTracker(48_000)
        var samples = TonalFixtures.progression(0, minor = false, seconds = 10f)
        repeat(8) { samples += TonalFixtures.progression(if (it % 2 == 0) 7 else 0, minor = false, seconds = 2f) }
        var changes = 0
        var last: Int? = null
        tracker.run(samples) {
            val tonic = tracker.key?.tonic
            if (tonic != null) {
                if (last != null && tonic != last) changes++
                last = tonic
            }
        }
        assertTrue(changes <= 1, "the key flickered $changes times between neighbours two seconds apart")
    }

    @Test
    fun drumsNoiseAndSilenceHaveNoKey() {
        assertNull(keyAfter(TonalFixtures.drumsOnly(12f)).key, "drums")
        assertNull(keyAfter(TonalFixtures.noise(12f)).key, "noise")
        assertNull(keyAfter(SyntheticSong.silence(12f)).key, "silence")
    }

    @Test
    fun aChordThatFitsTwoKeysEquallyLeavesTheKeyUnknown() {
        // C6 is also A minor seventh: the two key profiles correlate almost equally with it.
        assertNull(keyAfter(TonalFixtures.pureCluster(intArrayOf(60, 64, 67, 69), seconds = 14f)).key)
    }

    @Test
    fun aKeyNeedsThreeSecondsOfPitchedEvidence() {
        val tracker = KeyTracker(48_000)
        var firstKnown: Long? = null
        tracker.run(TonalFixtures.progression(0, minor = false, seconds = 10f)) { available ->
            if (firstKnown == null && tracker.key != null) firstKnown = available
        }
        val known = assertNotNull(firstKnown)
        assertTrue(known >= 3_000_000L, "a key appeared after only $known us")
    }

    @Test
    fun aKeyChangeIsFollowedWithinTheAveragingTime() {
        val tracker = KeyTracker(48_000)
        val samples = TonalFixtures.progression(0, minor = false, seconds = 15f) +
            TonalFixtures.progression(6, minor = false, seconds = 20f)
        var switched: Long? = null
        tracker.run(samples) { available ->
            if (switched == null && available > 15_000_000L && tracker.key?.tonic == 6) switched = available
        }
        val at = assertNotNull(switched, "the key never followed the change to F sharp")
        assertTrue(at - 15_000_000L in 3_000_000L..15_000_000L, "followed after ${at - 15_000_000L} us")
    }

    @Test
    fun keyEvidenceExpiresDuringUnpitchedMusic() {
        val tracker = keyAfter(TonalFixtures.progression(0, minor = false, seconds = 12f) + TonalFixtures.drumsOnly(12f))
        assertNull(tracker.key, "twelve seconds of drums leave no supported key")
        assertTrue(tracker.chroma.max() < 0.1f, "the short-term profile decays: ${tracker.chroma.toList()}")
    }

    @Test
    fun theEstimateCarriesItsOwnLongWindow() {
        val tracker = keyAfter(TonalFixtures.progression(0, minor = false, seconds = 12f))
        val window = assertNotNull(tracker.key).window
        assertEquals(tracker.windowSize, window.sampleCount)
        val end = assertNotNull(window.endMicros)
        assertEquals(end - tracker.windowSize * 500_000L / 48_000, assertNotNull(window.referenceMicros), "centre of the window")
    }

    @Test
    fun theShortTermProfilePeaksOnTheChordTones() {
        val tracker = keyAfter(TonalFixtures.progression(0, minor = false, seconds = 4f))
        val strongest = tracker.chroma.indices.maxBy { tracker.chroma[it] }
        assertTrue(strongest in setOf(0, 4, 7, 5, 9, 11, 2), "strongest pitch class $strongest")
        assertTrue(tracker.chroma.max() in 0.5f..1f, "largest pitch class ${tracker.chroma.max()}")
    }
}
