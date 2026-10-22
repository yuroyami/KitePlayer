package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Development fixtures with known pitch content. They tune parameters; they are not held-out data. */
internal object TonalFixtures {
    private val MAJOR = listOf(intArrayOf(0, 4, 7), intArrayOf(5, 9, 12), intArrayOf(7, 11, 14), intArrayOf(0, 4, 7))
    private val MINOR = listOf(intArrayOf(0, 3, 7), intArrayOf(5, 8, 12), intArrayOf(7, 11, 14), intArrayOf(0, 3, 7))

    /**
     * A I-IV-V-I progression in the key on [tonic] (0 for C), one chord a second, with a bass note
     * and six harmonics per note at amplitude 1/h. [detuneCents] shifts every frequency.
     */
    fun progression(tonic: Int, minor: Boolean, seconds: Float, sampleRate: Int = 48_000,
        detuneCents: Float = 0f, amplitude: Float = 0.25f): FloatArray {
        val chords = if (minor) MINOR else MAJOR
        val out = FloatArray((seconds * sampleRate).toInt())
        val chordSamples = sampleRate
        for (index in out.indices) {
            val chord = chords[(index / chordSamples) % chords.size]
            val t = (index % chordSamples).toFloat() / sampleRate
            val envelope = minOf(1f, t / 0.02f) * (0.6f + 0.4f * exp(-t * 3f)) * minOf(1f, (1f - t) / 0.03f)
            var value = 0.0
            val notes = chord.map { 48 + tonic + it } + (36 + tonic + chord[0])
            for (midi in notes) {
                val base = 440.0 * 2.0.pow((midi - 69 + detuneCents / 100.0) / 12.0)
                for (h in 1..6) {
                    val f = base * h
                    if (f >= sampleRate / 2.0) break
                    value += sin(2 * PI * f * index / sampleRate) / h
                }
            }
            out[index] = (value * envelope * amplitude / notes.size).toFloat()
        }
        return out
    }

    /** Pure sine tones at the given MIDI notes, held steadily, with no harmonics to tip the balance. */
    fun pureCluster(notes: IntArray, seconds: Float, sampleRate: Int = 48_000): FloatArray {
        val frequencies = notes.map { 440.0 * 2.0.pow((it - 69) / 12.0) }
        return FloatArray((seconds * sampleRate).toInt()) { index ->
            (frequencies.sumOf { sin(2 * PI * it * index / sampleRate) } * 0.1).toFloat()
        }
    }

    /** Kick, snare and hats with no bass line or chord, unlike [SyntheticSong.drumLoop]. */
    fun drumsOnly(seconds: Float, beatsPerMinute: Float = 128f, sampleRate: Int = 48_000): FloatArray {
        val beat = 60f / beatsPerMinute
        var state = 4_242L
        return FloatArray((seconds * sampleRate).toInt()) { index ->
            val time = index.toFloat() / sampleRate
            val intoBeat = time % beat
            val intoHalf = time % (beat / 2f)
            val intoBar = time % (beat * 4f)
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = (state shr 40).toFloat() / 8_388_608f
            val kick = sin(2f * PI.toFloat() * (46f + 56f * exp(-intoBeat * 30f)) * intoBeat) * exp(-intoBeat * 11f) * 0.9f
            val sinceSnare = if (intoBar >= beat) (intoBar - beat) % (beat * 2f) else beat
            (kick + white * exp(-sinceSnare * 26f) * 0.55f + white * exp(-intoHalf * 90f) * 0.22f).coerceIn(-1f, 1f)
        }
    }

    fun noise(seconds: Float, sampleRate: Int = 48_000, amplitude: Float = 0.3f): FloatArray {
        var state = 12_345L
        return FloatArray((seconds * sampleRate).toInt()) {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            ((state ushr 40).toInt() / 8_388_608f - 1f) * amplitude
        }
    }
}

/** Runs [tracker] over mono [samples] at the analyser's 10 ms cadence, calling [each] after every analysis. */
internal fun KeyTracker.run(samples: FloatArray, sampleRate: Int = 48_000, startMicros: Long = 0L,
    each: (Long) -> Unit = {}) {
    val hop = sampleRate / 100
    var seen = 0L
    for (index in samples.indices) {
        push(samples[index], samples[index])
        seen++
        if (seen % hop == 0L) {
            val available = startMicros + seen * 1_000_000L / sampleRate
            analyse(available)
            each(available)
        }
    }
}
