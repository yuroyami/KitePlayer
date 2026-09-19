package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural decisions of the live analyser on synthetic development fixtures. These fixtures
 * tuned the detector; they are not held-out music. Times are media seconds.
 */
class SectionDetectorTest {
    private val ln100 = kotlin.math.ln(100f)

    private class Found(val kind: AudioEventKind, val at: Double, val confirmedAt: Double, val confidence: Float)

    /** Feeds [mono] through a real analyser and timeline, as the player does, and answers every published decision. */
    private fun decisions(mono: FloatArray, sampleRate: Int = 48_000): List<Found> {
        val analyzer = SpectrumAnalyzer(sampleRate = sampleRate)
        val timeline = SpectrumTimeline(256)
        analyzer.reset(timeline.generation, timeline.revision)
        val publisher = timeline.publisher()
        val found = ArrayList<Found>()
        analyzer.onAnalysis = { frame ->
            check(publisher.push(frame)) { "the timeline rejected a frame at ${frame.ptsMicros}" }
            frame.structure?.let { batch ->
                for (index in 0 until batch.size) {
                    val hit = batch[index]
                    found += Found(hit.kind, hit.ptsMicros / 1e6, hit.availableMicros / 1e6, hit.confidence)
                }
            }
        }
        val block = FloatArray(1024 * 2)
        var start = 0
        while (start < mono.size) {
            val frames = minOf(1024, mono.size - start)
            for (i in 0 until frames) { block[2 * i] = mono[start + i]; block[2 * i + 1] = mono[start + i] }
            analyzer.feed(block, frames, 2, start * 1_000_000L / sampleRate)
            start += frames
        }
        assertEquals(0L, timeline.structureEventStats.rejectedPublications)
        return found
    }

    /** A clear change is placed at the change and confirmed within the standard's two seconds. */
    private fun assertOneChange(found: List<Found>, kind: AudioEventKind, at: Double,
        placement: Double = 0.2, delay: Double = 2.0) {
        assertEquals(1, found.size, "decisions: ${found.map { "${it.kind}@${it.at}" }}")
        val change = found.single()
        assertEquals(kind, change.kind)
        assertTrue(change.at in at - 0.05..at + placement, "boundary placed at ${change.at}, expected $at")
        assertTrue(change.confirmedAt - at <= delay, "confirmed ${change.confirmedAt - at} s after the change")
        assertTrue(change.confidence >= 0.6f, "confidence ${change.confidence}")
    }

    @Test
    fun aQuietPadIntoLoudDrumsIsADrop() {
        assertOneChange(decisions(SyntheticSong.calmPad(20f) + SyntheticSong.drumLoop(20f)), AudioEventKind.Drop, 20.0)
    }

    @Test
    fun loudDrumsIntoAQuietPadIsABreakdown() {
        assertOneChange(decisions(SyntheticSong.drumLoop(20f) + SyntheticSong.calmPad(20f)), AudioEventKind.Breakdown, 20.0)
    }

    @Test
    fun aPadIntoPianoChordsAtTheSameLevelIsASectionBoundary() {
        val pad = SyntheticSong.calmPad(20f)
        val chords = TonalFixtures.progression(9, minor = true, seconds = 20f, amplitude = 0.06f)
        assertOneChange(decisions(pad + chords), AudioEventKind.SectionBoundary, 20.0)
    }

    @Test
    fun aKeyChangeOnTheSameInstrumentIsFoundInsideTheStructuralBudget() {
        // Harmony leads here and the new register shifts timbre a chord later, so this is not a
        // clear change: it is placed within 1.2 s and confirmed inside the 3 s lateness budget.
        val samples = TonalFixtures.progression(0, minor = false, seconds = 20f) +
            TonalFixtures.progression(6, minor = false, seconds = 20f)
        assertOneChange(decisions(samples), AudioEventKind.SectionBoundary, 20.0, placement = 1.2, delay = 3.0)
    }

    @Test
    fun aSwellOfOneSoundIsNotASection() {
        val pad = SyntheticSong.calmPad(40f)
        for (index in pad.indices) pad[index] *= 0.03f + 0.97f * index / pad.size
        assertEquals(emptyList(), decisions(pad).map { "${it.kind}@${it.at}" })
    }

    @Test
    fun aPureToneRisingFortyDecibelsIsNotASection() {
        val total = 40 * 48_000
        val tone = FloatArray(total) { index ->
            val gain = 0.01f * kotlin.math.exp(ln100 * index / total)
            (sin(2 * PI * 440.0 * index / 48_000) * gain).toFloat()
        }
        assertEquals(emptyList(), decisions(tone).map { "${it.kind}@${it.at}" })
    }

    @Test
    fun aSuddenLevelStepOfTheSameToneIsNotASection() {
        val tone = FloatArray(40 * 48_000) { index ->
            val gain = if (index < 20 * 48_000) 0.01f else 1f
            (sin(2 * PI * 440.0 * index / 48_000) * gain * 0.5).toFloat()
        }
        assertEquals(emptyList(), decisions(tone).map { "${it.kind}@${it.at}" })
    }

    @Test
    fun aSteadyLoopHasNoSections() {
        assertEquals(emptyList(), decisions(SyntheticSong.drumLoop(60f)).map { "${it.kind}@${it.at}" })
    }

    @Test
    fun oneLoudHitIsNotASection() {
        val pad = SyntheticSong.calmPad(40f)
        val hit = 20 * 48_000
        for (offset in 0 until 960) pad[hit + offset] += (sin(2 * PI * 180.0 * offset / 48_000) * exp(-offset / 200.0) * 0.9).toFloat()
        assertEquals(emptyList(), decisions(pad).map { "${it.kind}@${it.at}" })
    }

    @Test
    fun musicStartingAfterSilenceIsNotASection() {
        assertEquals(emptyList(), decisions(SyntheticSong.silence(10f) + SyntheticSong.drumLoop(20f)).map { "${it.kind}@${it.at}" })
    }
}
