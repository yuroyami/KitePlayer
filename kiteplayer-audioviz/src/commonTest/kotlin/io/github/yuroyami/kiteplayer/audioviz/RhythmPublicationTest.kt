package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RhythmPublicationTest {
    @Test
    fun theAnalyzerDoesNotBackdateUnknownBarAndPhrasePositionsIntoAFalseBoundary() {
        val rate = 8_000
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        var confident = 0
        analyzer.onAnalysis = { frame ->
            if (frame.beatConfidence > 0.6f) confident++
            assertEquals(0f, frame.barPhase)
            assertEquals(0f, frame.phrasePhase)
        }
        val song = SyntheticSong.drumLoop(10f, sampleRate = rate)
        analyzer.feed(song, song.size, 1, 0L)
        assertTrue(confident > 100, "the fixture must exercise an acquired pulse grid")
    }

    @Test
    fun thePublishedCountdownExpiresWithTheEvidenceEvenIfTheDiagnosticTempoRemains() {
        val rate = 8_000
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val song = SyntheticSong.drumLoop(10f, sampleRate = rate)
        analyzer.feed(song, song.size, 1, 0L)
        assertTrue(analyzer.latest.beatConfidence > 0.6f)
        analyzer.feed(FloatArray(rate * 2), rate * 2, 1, 10_000_000L)
        assertTrue(analyzer.latest.bpm > 0f, "candidate rate is diagnostic")
        assertEquals(0f, analyzer.latest.beatConfidence)
        assertEquals(-1f, analyzer.latest.beatInSeconds)
    }

    @Test
    fun interpolationKeepsEvidenceDiscreteAndProjectsOnlyTheAcceptedPulsePhase() {
        fun frame(at: Long, usable: Boolean, phase: Float, revision: Long) = SpectrumFrame(
            at, FloatArray(4), FloatArray(4), FloatArray(4), 0f, 0f, 0f, 0f, 0f, 0f,
            rhythm = RhythmEstimate(at, at + 20_000L, 200_000L, revision,
                120f, 0.9f, if (usable) 0.8f else 0.1f, usable, phase, 60f, 0.8f),
        )
        val before = frame(0L, true, 0.99f, 1L)
        val after = frame(20_000L, false, 0.7f, 2L)
        val between = before.blend(after, 0.5f)
        val estimate = assertNotNull(between.rhythm)
        assertEquals(1L, estimate.revision, "a new hypothesis cannot be interpolated backwards")
        assertEquals(0.8f, estimate.beatSupport)
        assertEquals(0.01f, estimate.beatPhase, 1e-5f)
        assertEquals(10_000L, estimate.ptsMicros)
        assertEquals(20_000L, estimate.availableMicros)
        assertTrue(estimate.usable)
        assertEquals(2L, assertNotNull(before.blend(after, 1f).rhythm).revision)
        val expired = estimate.at(201_000L)
        assertFalse(expired.usable)
        assertEquals(-1f, expired.beatInSeconds)
        assertEquals(0f, expired.beatSupport)
        assertEquals(estimate, between.withEvents().rhythm, "event delivery must retain rhythm evidence")
    }
}
