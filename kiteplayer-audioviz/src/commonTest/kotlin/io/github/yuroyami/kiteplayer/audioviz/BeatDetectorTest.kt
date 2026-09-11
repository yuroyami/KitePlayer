package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class BeatDetectorTest {

    private val rate = 44_100

    @Test
    fun findsTheBeatsInAClickTrack() {
        val beatsPerMinute = 120
        val seconds = 8.0
        val expected = (beatsPerMinute * seconds / 60).toInt()

        val found = beatsIn(clickTrack(beatsPerMinute, seconds))

        // Onset detection is allowed to miss the first hit while its history fills.
        assertTrue(
            abs(found - expected) <= 2,
            "a $beatsPerMinute BPM track over ${seconds}s should give about $expected beats, found $found",
        )
    }

    @Test
    fun findsMoreBeatsInAFasterTrack() {
        val slow = beatsIn(clickTrack(90, 8.0))
        val fast = beatsIn(clickTrack(180, 8.0))

        assertTrue(fast > slow + 4, "180 BPM gave $fast beats and 90 BPM gave $slow: tempo is not tracked")
    }

    @Test
    fun findsNothingInSilence() {
        assertTrue(beatsIn(FloatArray(rate * 4)) == 0, "silence has no beats")
    }

    @Test
    fun findsNothingInASteadyTone() {
        // The point of measuring GROWTH rather than loudness: a held note is loud and empty.
        val tone = FloatArray(rate * 4) { 0.5f * sin(2.0 * PI * 440.0 * it / rate).toFloat() }

        val found = beatsIn(tone)

        assertTrue(found <= 1, "a held tone should not read as a rhythm, found $found beats")
    }

    /** How many onsets the analyser reports over a mono buffer. */
    private fun beatsIn(mono: FloatArray): Int {
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        var beats = 0
        analyzer.onAnalysis = { if (it.beat > 0f) beats++ }
        analyzer.feed(mono, mono.size, channels = 1)
        return beats
    }

    /** Short bursts of decaying noise at a steady tempo, which is what a drum machine sounds like. */
    private fun clickTrack(beatsPerMinute: Int, seconds: Double): FloatArray {
        val total = (rate * seconds).toInt()
        val samplesPerBeat = rate * 60 / beatsPerMinute
        val burst = rate / 40
        val out = FloatArray(total)
        var noise = 12_345L
        for (index in 0 until total) {
            val intoBeat = index % samplesPerBeat
            if (intoBeat >= burst) continue
            noise = noise * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = ((noise shr 40).toFloat() / 8_388_608f).coerceIn(-1f, 1f)
            out[index] = white * exp(-6.0 * intoBeat / burst).toFloat()
        }
        return out
    }
}
