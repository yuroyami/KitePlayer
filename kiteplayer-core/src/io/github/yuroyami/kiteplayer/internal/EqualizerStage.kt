package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.EqualizerSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ten peaking filters per channel, in series.
 *
 * Each band is one biquad from the standard cookbook: a peaking equaliser whose magnitude response
 * at its own centre frequency is exactly the gain asked for, and which tends to unity a couple of
 * octaves either side. Ten of them in series is what a graphic equaliser IS, and the reason to
 * write it here rather than reach for a filter chain is that this runs on every buffer of every
 * platform, wasm included, and must cost nothing when it is switched off.
 *
 * [isFlat] is what makes that true: with every gain at zero the stage is skipped entirely rather
 * than running twenty multiply-accumulates per sample against coefficients that happen to be the
 * identity, so an ordinary file pays nothing at all.
 *
 * State is per band per channel, which is what stops the left channel's history leaking into the
 * right one. Direct form 1, because its two output-history taps are the ones a `Float` can hold
 * without the coefficient quantisation of the transposed form biting at 31 Hz, where the poles sit
 * very close to the unit circle at 48 kHz.
 *
 * Not thread safe. The feeder owns it, and a settings change is applied by that same worker between
 * buffers, which is also why a change is a coefficient swap rather than an interpolation: the
 * transient of a swap is one buffer and inaudible, and interpolating twenty coefficients per sample
 * to avoid it would cost more than the filter.
 */
internal class EqualizerStage(private val channels: Int, private val sampleRate: Int) {

    init {
        require(channels >= 1) { "an equaliser needs at least one channel, was $channels" }
        require(sampleRate > 0) { "an equaliser needs a real sample rate, was $sampleRate" }
    }

    private val bandCount = EqualizerSettings.Bands.size

    /** Per band: b0, b1, b2, a1, a2, already normalised by a0. */
    private val coefficients = FloatArray(bandCount * 5)

    /** Per band per channel: x1, x2, y1, y2. */
    private val history = FloatArray(bandCount * channels * 4)

    private var preamp = 1f

    /** True while the stage would do nothing, in which case [apply] does nothing. */
    var isFlat: Boolean = true
        private set

    /**
     * A band whose centre is at or above the Nyquist frequency is skipped.
     *
     * At 32 kHz the 16 kHz band sits exactly on it, where the cookbook's `cos(w0)` is -1 and the
     * filter degenerates. Leaving it out is honest: the material cannot carry that band.
     */
    private val bandActive = BooleanArray(bandCount)

    fun set(settings: EqualizerSettings) {
        preamp = 10f.pow(settings.preampDb / 20f)
        isFlat = settings.isFlat
        if (isFlat) {
            reset()
            return
        }
        for (band in 0 until bandCount) {
            val centre = EqualizerSettings.Bands[band]
            val active = centre * 2f < sampleRate
            bandActive[band] = active
            if (!active) continue
            writeCoefficients(band, centre, settings.gainsDb[band])
        }
    }

    /** The cookbook peaking filter. At [centre] its magnitude is exactly [gainDb]. */
    private fun writeCoefficients(band: Int, centre: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centre / sampleRate
        val alpha = sin(w0) / (2.0 * Q)
        val a0 = 1.0 + alpha / a
        val at = band * 5
        coefficients[at] = ((1.0 + alpha * a) / a0).toFloat()
        coefficients[at + 1] = ((-2.0 * cos(w0)) / a0).toFloat()
        coefficients[at + 2] = ((1.0 - alpha * a) / a0).toFloat()
        coefficients[at + 3] = ((-2.0 * cos(w0)) / a0).toFloat()
        coefficients[at + 4] = ((1.0 - alpha / a) / a0).toFloat()
    }

    /** Forgets every filter's history. Called on a flush, so a seek carries no tail across. */
    fun reset() {
        history.fill(0f)
    }

    /**
     * Filters the first [frames] sample frames of interleaved [samples] in place.
     *
     * Only those frames: the pipeline reuses one buffer, and its tail holds audio from an earlier
     * and longer conversion that has already been handed on.
     */
    fun apply(samples: FloatArray, frames: Int) {
        if (isFlat || frames <= 0) return
        var base = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                var value = samples[base + channel] * preamp
                for (band in 0 until bandCount) {
                    if (!bandActive[band]) continue
                    val c = band * 5
                    val h = (band * channels + channel) * 4
                    val x1 = history[h]
                    val x2 = history[h + 1]
                    val y1 = history[h + 2]
                    val y2 = history[h + 3]
                    val out = coefficients[c] * value +
                        coefficients[c + 1] * x1 +
                        coefficients[c + 2] * x2 -
                        coefficients[c + 3] * y1 -
                        coefficients[c + 4] * y2
                    history[h + 1] = x1
                    history[h] = value
                    history[h + 3] = y1
                    history[h + 2] = out
                    value = out
                }
                samples[base + channel] = value
            }
            base += channels
        }
    }

    private companion object {
        /**
         * The bandwidth of each filter.
         *
         * 1.41 puts the half-power points about two thirds of an octave either side, so ten octave
         * bands overlap enough to be continuous and little enough that moving one is audible as
         * that band rather than as its neighbours.
         */
        const val Q = 1.41
    }
}
