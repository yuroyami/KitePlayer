package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.Interleaver
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/** The default [AudioBuffer.copyInterleaved], which a backend that only has channels relies on. */
class AudioBufferInterleaveTest {

    /** Channel c of frame f holds c * 100 + f, so every value says where it came from. */
    private class ChannelsOnly(override val frameCount: Int, channels: Int) : AudioBuffer {
        override val pts: Pts = Pts(0)
        override val format: AudioFormat = AudioFormat(48_000, channels, SampleFormat.F32)
        override val generation: Generation = Generation.Initial

        override fun copyChannel(channel: Int, into: FloatArray, offset: Int) {
            for (frame in 0 until frameCount) into[offset + frame] = (channel * 100 + frame).toFloat()
        }

        override fun close() = Unit
    }

    @Test
    fun theDefaultInterleavesFrameByFrameFromTheOffset() {
        val into = FloatArray(2 + 3 * 4) { -1f }
        ChannelsOnly(frameCount = 4, channels = 3).copyInterleaved(into, offset = 2)
        assertEquals(
            listOf(-1f, -1f, 0f, 100f, 200f, 1f, 101f, 201f, 2f, 102f, 202f, 3f, 103f, 203f),
            into.toList(),
        )
    }

    @Test
    fun theEngineInterleaverReusesItsArrayAcrossBuffers() {
        val interleaver = Interleaver()
        val first = interleaver.interleave(ChannelsOnly(frameCount = 4, channels = 2))
        assertEquals(listOf(0f, 100f, 1f, 101f, 2f, 102f, 3f, 103f), first.toList().take(8))
        val second = interleaver.interleave(ChannelsOnly(frameCount = 2, channels = 2))
        assertEquals(true, second === first, "a smaller buffer reuses the array")
        assertEquals(listOf(0f, 100f, 1f, 101f), second.toList().take(4))
    }
}
