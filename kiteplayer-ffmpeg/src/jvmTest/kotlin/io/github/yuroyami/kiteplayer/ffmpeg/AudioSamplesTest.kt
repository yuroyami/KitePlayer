package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.SampleFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decoded audio arrives in whatever sample format the codec likes. One helper turns any of them
 * into interleaved floats in -1..1, which is what the meter and the waveform read.
 */
class AudioSamplesTest {

    private fun le16(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { i, v ->
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    private fun leFloats(vararg values: Float): ByteArray = ByteArray(values.size * 4).also { bytes ->
        values.forEachIndexed { i, v ->
            val bits = v.toRawBits()
            for (b in 0 until 4) bytes[i * 4 + b] = ((bits shr (8 * b)) and 0xFF).toByte()
        }
    }

    @Test
    fun `packed 16 bit stereo becomes interleaved floats`() {
        // Two frames of stereo: L0 R0 L1 R1.
        val frame = Frame.ofAudio(le16(16384, -16384, 32767, 0), sampleCount = 2, sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.S16)
        try {
            val floats = AudioSamples.toFloatInterleaved(frame)
            assertEquals(4, floats.size)
            assertEquals(0.5f, floats[0], 1e-6f)
            assertEquals(-0.5f, floats[1], 1e-6f)
            assertTrue(floats[2] > 0.999f)
            assertEquals(0f, floats[3])
        } finally {
            frame.close()
        }
    }

    @Test
    fun `planar float stereo is re-interleaved`() {
        // Planar: all of channel 0, then all of channel 1.
        val frame = Frame.ofAudio(
            leFloats(0.25f, 0.5f, -0.25f, -0.5f),
            sampleCount = 2, sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.FltP,
        )
        try {
            assertContentEquals(floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f), AudioSamples.toFloatInterleaved(frame))
        } finally {
            frame.close()
        }
    }

    @Test
    fun `unsigned 8 bit is centred on zero`() {
        val frame = Frame.ofAudio(byteArrayOf(128.toByte(), 255.toByte(), 0), sampleCount = 3, sampleRate = 8_000, channels = 1, sampleFormat = SampleFormat.U8)
        try {
            val floats = AudioSamples.toFloatInterleaved(frame)
            assertEquals(0f, floats[0])
            assertTrue(floats[1] > 0.99f)
            assertEquals(-1f, floats[2])
        } finally {
            frame.close()
        }
    }

    @Test
    fun `a format nobody decodes to is refused by name`() {
        val frame = Frame.ofAudio(ByteArray(16), sampleCount = 2, sampleRate = 48_000, channels = 1, sampleFormat = SampleFormat.S64)
        try {
            val failure = assertFailsWith<IllegalArgumentException> { AudioSamples.toFloatInterleaved(frame) }
            assertTrue("s64" in failure.message.orEmpty(), failure.message)
        } finally {
            frame.close()
        }
    }
}
