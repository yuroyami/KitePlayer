package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformResamplerTest {
    @Test
    fun downsamplingPreservesDcAndRejectsFrequenciesAboveTheNewNyquist() {
        val resampler = WaveformResampler()
        val out = FloatArray(128)
        resampler.resample(FloatArray(512) { 0.25f }, out)
        assertTrue(out.all { abs(it - 0.25f) < 1e-6f })
        resampler.resample(FloatArray(512) { if (it % 2 == 0) 1f else -1f }, out)
        assertTrue(out.all { abs(it) < 0.002f }, "Nyquist alternation must not alias into a flat trace")
        resampler.resample(FloatArray(512) { sin(2 * PI * 16_000 * it / 48_000).toFloat() }, out)
        assertTrue(out.slice(10 until 118).all { abs(it) < 0.003f })
    }

    @Test
    fun passbandAndPairedPolarityArePreservedWithoutKeepingOldSignalData() {
        val resampler = WaveformResampler()
        val pcm = FloatArray(512) { sin(2 * PI * 1000 * it / 48_000).toFloat() }
        val left = FloatArray(128)
        val right = FloatArray(128)
        resampler.resample(pcm, left)
        resampler.resample(FloatArray(pcm.size) { -pcm[it] }, right)
        for (index in 10 until 118) {
            val expected = sin(2 * PI * 1000 * index * 511 / 127 / 48_000)
            assertEquals(expected, left[index].toDouble(), 0.003)
            assertEquals(left[index], -right[index], 1e-6f)
        }
        resampler.resample(FloatArray(512), right)
        assertTrue(right.all { it == 0f })
    }

    @Test
    fun emptyEqualAndUpsampledTracesHaveDefinedEndpoints() {
        val resampler = WaveformResampler()
        val out = FloatArray(5) { 9f }
        resampler.resample(FloatArray(0), out)
        assertTrue(out.all { it == 0f })
        resampler.resample(floatArrayOf(-1f, 1f), out)
        assertEquals(listOf(-1f, -0.5f, 0f, 0.5f, 1f), out.toList())
        resampler.resample(FloatArray(5) { it.toFloat() }, out)
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), out.toList())
        resampler.resample(floatArrayOf(0.25f), out)
        assertTrue(out.all { it == 0.25f })
    }
}
