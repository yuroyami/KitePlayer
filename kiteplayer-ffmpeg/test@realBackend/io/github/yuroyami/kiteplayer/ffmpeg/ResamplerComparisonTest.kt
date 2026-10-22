package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The engine's own sinc and libswresample, measured on the same signals through the same
 * pipeline, with the numbers printed side by side.
 *
 * The test asserts only that both resamplers filter at all. Which one should be the default is a
 * decision for whoever reads the numbers, so the test prints them rather than choosing.
 */
class ResamplerComparisonTest {

    private val candidates: List<Pair<String, AudioResamplerFactory?>> =
        listOf("engine sinc" to null, "libswresample" to KiteFFmpegResampler())

    private fun convert(input: FloatArray, fromRate: Int, toRate: Int, resampler: AudioResamplerFactory?): FloatArray =
        runBlocking {
            val converted = convertThroughPlayback(input, 1, fromRate, toRate, resampler)
            assertTrue(converted.warnings.none { it is PlaybackWarning.ResamplerUnavailable }, "${converted.warnings}")
            converted.samples
        }

    /** A sine sweep from [low] to [high] Hz, exponential, over [seconds] at [rate], at half scale. */
    private fun sweep(rate: Int, seconds: Double, low: Double, high: Double): FloatArray {
        val frames = (rate * seconds).toInt()
        val growth = ln(high / low)
        return FloatArray(frames) { i ->
            val t = i.toDouble() / rate
            val phase = 2.0 * PI * low * seconds / growth * (exp(growth * t / seconds) - 1.0)
            (0.5 * sin(phase)).toFloat()
        }
    }

    /** The moment the sweep passes [hz], in seconds. */
    private fun sweepTimeAt(hz: Double, seconds: Double, low: Double, high: Double): Double =
        seconds * ln(hz / low) / ln(high / low)

    /** A power ratio, such as two mean squares, in dB. */
    private fun decibels(ratio: Double): Double = 10.0 * log10(maxOf(ratio, 1e-30))

    /** An amplitude ratio in dB. */
    private fun amplitudeDecibels(ratio: Double): Double = 2.0 * decibels(ratio)

    /**
     * The amplitude at [hz], measured through a Hann window. Without the window, leakage from a
     * tone 3.9 kHz away sets a floor near -80 dB, which would hide a smaller image.
     */
    private fun windowedAmplitudeAt(samples: FloatArray, rate: Int, hz: Double, from: Int, until: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        var weights = 0.0
        val length = until - from
        for (i in 0 until length) {
            val weight = 0.5 - 0.5 * cos(2.0 * PI * i / (length - 1))
            val angle = 2.0 * PI * hz * i / rate
            real += weight * samples[from + i] * cos(angle)
            imaginary += weight * samples[from + i] * sin(angle)
            weights += weight
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / weights
    }

    private fun Double.shown(): String = ((this * 10).toLong() / 10.0).toString() + " dB"

    @Test
    fun theSweepThroughBothResamplers() {
        val seconds = 4.0
        val low = 20.0
        val high = 20_000.0
        val report = StringBuilder("Resampler comparison, through AudioPlayback\n")

        // Down from 48 kHz to 32 kHz. The new Nyquist is 16 kHz, so the sweep's last part must go.
        // While the sweep is above 16.8 kHz, anything left in the output is energy folded back
        // below the new Nyquist. From 100 Hz to 14.4 kHz the level must hold.
        val input = sweep(48_000, seconds, low, high)
        val aliasFrom = sweepTimeAt(16_800.0, seconds, low, high)
        val passFrom = sweepTimeAt(100.0, seconds, low, high)
        val passUntil = sweepTimeAt(14_400.0, seconds, low, high)
        val folded = mutableListOf<String>()
        val flat = mutableListOf<String>()
        for ((name, resampler) in candidates) {
            val output = convert(input, 48_000, 32_000, resampler)
            val aliasIn = meanSquare(input, (aliasFrom * 48_000).toInt(), input.size - 128)
            val aliasOut = meanSquare(output, (aliasFrom * 32_000).toInt() + 64, output.size - 128)
            val passIn = meanSquare(input, (passFrom * 48_000).toInt(), (passUntil * 48_000).toInt())
            val passOut = meanSquare(output, (passFrom * 32_000).toInt(), (passUntil * 32_000).toInt())
            val aliasDb = decibels(aliasOut / aliasIn)
            folded += "$name ${aliasDb.shown()}"
            flat += "$name ${decibels(passOut / passIn).shown()}"
            assertTrue(aliasDb < -20.0, "$name left ${aliasDb.shown()} above the new Nyquist, so it does not filter")
        }
        report.append("48000 to 32000 Hz, sweep 20 Hz to 20 kHz, energy above the new Nyquist: ")
        report.append("${folded.joinToString()}\n")
        report.append("48000 to 32000 Hz, level from 100 Hz to 14.4 kHz: ${flat.joinToString()}\n")

        // Up from 44.1 kHz to 48 kHz, the common case. Nothing folds on the way up, but a tone
        // leaves an image at 44.1 kHz minus its frequency, which the 48 kHz output folds back to
        // 3.9 kHz above the tone. An image above 20 kHz is out of hearing, so each tone is shown.
        // And the top of the band must keep its level.
        for (hz in listOf(10_000.0, 15_000.0, 18_000.0, 20_000.0)) {
            val levels = mutableListOf<String>()
            val images = mutableListOf<String>()
            for ((name, resampler) in candidates) {
                val output = convert(tone(44_100, 44_100, hz, amplitude = 0.5), 44_100, 48_000, resampler)
                val kept = windowedAmplitudeAt(output, 48_000, hz, from = 256, until = output.size - 256)
                val image = windowedAmplitudeAt(output, 48_000, hz + 3_900.0, from = 256, until = output.size - 256)
                levels += "$name ${amplitudeDecibels(kept / 0.5).shown()}"
                images += "$name ${amplitudeDecibels(image / kept).shown()}"
            }
            val khz = (hz / 1_000).toInt()
            val imageKhz = (hz + 3_900.0) / 1_000
            report.append("44100 to 48000 Hz, a $khz kHz tone: level ${levels.joinToString()}; ")
            report.append("image at $imageKhz kHz ${images.joinToString()}\n")
        }
        println(report)
    }
}
