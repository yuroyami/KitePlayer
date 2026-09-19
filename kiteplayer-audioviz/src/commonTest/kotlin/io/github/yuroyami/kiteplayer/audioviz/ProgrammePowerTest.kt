package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgrammePowerTest {
    @Test
    fun momentaryPowerMatchesIndependentFfmpegTonesAtSevenSampleRates() {
        // FFmpeg 8.0 ebur128=metadata=1 on two-second full-scale float32 mono sines.
        // Last 400 ms momentary readings, no integrated gating and no dual-mono option.
        val oracles = listOf(
            Triple(8_000, 50, -7.420), Triple(8_000, 997, -2.995), Triple(8_000, 2666, 0.453),
            Triple(16_000, 50, -7.549), Triple(16_000, 997, -2.970), Triple(16_000, 4000, 0.392),
            Triple(22_050, 50, -7.585), Triple(22_050, 997, -2.981), Triple(22_050, 4000, 0.337),
            Triple(44_100, 50, -7.632), Triple(44_100, 997, -3.008), Triple(44_100, 4000, 0.272),
            Triple(48_000, 50, -7.635), Triple(48_000, 997, -3.011), Triple(48_000, 4000, 0.267),
            Triple(96_000, 50, -7.657), Triple(96_000, 997, -3.028), Triple(96_000, 4000, 0.241),
            Triple(192_000, 50, -7.668), Triple(192_000, 997, -3.038), Triple(192_000, 4000, 0.229),
        )
        for ((rate, frequency, expected) in oracles) {
            val meter = ProgrammePower(format(rate, 1))
            tone(meter, rate, frequency, floatArrayOf(1f))
            assertTrue(meter.ready)
            val actual = -0.691 + 10 * log10(checkNotNull(meter.meanSquare))
            assertEquals(expected, actual, 0.003, "$rate Hz / $frequency Hz")
        }
    }

    @Test
    fun programmeSumsChannelsWithoutCancellationAndExcludesLfe() {
        val mono = measured(format(48_000, 1), floatArrayOf(1f))
        assertEquals(mono * 2, measured(format(48_000, 2), floatArrayOf(1f, -1f)), 1e-10)
        assertEquals(mono, measured(format(48_000, 2), floatArrayOf(1f, 0f)), 1e-10)
        val surround = format(48_000, 6).copy(channelLayoutMask = 0x60fL)
        for (channel in 0..5) {
            val gain = FloatArray(6) { if (it == channel) 1f else 0f }
            val weight = when (channel) { 3 -> 0.0; 4, 5 -> 1.41; else -> 1.0 }
            assertEquals(mono * weight, measured(surround, gain), 1e-9, "5.1 channel $channel")
        }
        // In 7.1 the rear pair is at +/-135 degrees; the side pair is at +/-90 degrees.
        val seven = format(48_000, 8).copy(channelLayoutMask = 0x63fL)
        assertEquals(mono, measured(seven, FloatArray(8) { if (it == 4) 1f else 0f }), 1e-9)
        assertEquals(mono * 1.41, measured(seven, FloatArray(8) { if (it == 6) 1f else 0f }), 1e-9)
    }

    @Test
    fun completeWindowAndDigitalSilenceAreDistinctFromMissingLayout() {
        val meter = ProgrammePower(format(8_000, 1))
        repeat(3199) { meter.addChannel(0, 0f); meter.endFrame() }
        assertFalse(meter.ready)
        assertNull(meter.meanSquare)
        meter.addChannel(0, 0f); meter.endFrame()
        assertTrue(meter.ready)
        assertEquals(0.0, meter.meanSquare)
        assertTrue(meter.digitalSilence)
        meter.addChannel(0, 1f); meter.endFrame()
        assertFalse(meter.digitalSilence)
        repeat(3200) { meter.addChannel(0, 0f); meter.endFrame() }
        assertTrue(meter.digitalSilence)
        assertTrue(checkNotNull(meter.meanSquare) >= 0.0)
        val unknown = ProgrammePower(format(8_000, 6).copy(channelLayout = ChannelLayout.Unknown))
        assertFalse(unknown.supported)
        val invalidMask = ProgrammePower(format(8_000, 2).copy(channelLayoutMask = 0x4L))
        assertFalse(invalidMask.supported)
        val lowRate = ProgrammePower(format(1_000, 1))
        assertFalse(lowRate.supported)
    }

    @Test
    fun powerRetainsAnAmplitudeStepOfTwelveDecibels() {
        val loud = measured(format(48_000, 1), floatArrayOf(1f))
        val quiet = measured(format(48_000, 1), floatArrayOf(10.0.pow(-12.0 / 20).toFloat()))
        assertEquals(-12.0, 10 * log10(quiet / loud), 1e-5)
    }

    private fun format(rate: Int, channels: Int) = AudioFormat(rate, channels, SampleFormat.F32)

    private fun measured(format: AudioFormat, gains: FloatArray): Double {
        val meter = ProgrammePower(format)
        tone(meter, format.sampleRate, 997, gains)
        return checkNotNull(meter.meanSquare)
    }

    private fun tone(meter: ProgrammePower, rate: Int, frequency: Int, gains: FloatArray) {
        repeat(rate * 2) { index ->
            val sample = sin(2 * PI * frequency * index / rate).toFloat()
            for (channel in gains.indices) meter.addChannel(channel, sample * gains[channel])
            meter.endFrame()
        }
    }
}
