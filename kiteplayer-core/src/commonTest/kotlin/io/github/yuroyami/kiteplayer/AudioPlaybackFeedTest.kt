package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The public feed runs the whole audio path, so a setting of the class acts on what it is given. */
class AudioPlaybackFeedTest {

    /** A device pumped by hand that keeps every sample it heard. */
    private class PumpedSink : AudioSink {
        override val deviceBufferFrames: Int = 256
        private var render: AudioRenderCallback? = null
        private var buffer: Heard? = null
        val heard: MutableList<Float> = mutableListOf()

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            buffer = Heard(request, deviceBufferFrames)
            return request
        }

        fun pumpAll() {
            val callback = render ?: return
            val destination = buffer ?: return
            while (true) {
                val written = callback.onRender(destination, deviceBufferFrames, 0L)
                if (written <= 0) return
                for (i in 0 until written * destination.format.channels) heard += destination.values[i]
            }
        }

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun drain() = Unit
        override suspend fun setPaused(paused: Boolean): Boolean = true
        override fun latencyNanos(): Long = 0
        override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
        override val events: Flow<AudioSinkEvent> = emptyFlow()
        override fun close() = Unit
    }

    private class Heard(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val values = FloatArray(frames * format.channels)

        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(values, destinationFrameOffset * format.channels, sourceOffset, sourceOffset + frames * format.channels)
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            values.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    @Test
    fun `a hard left balance silences the right channel of audio fed through submitDecoded`() = runTest {
        val stereo = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)
        val sink = PumpedSink()
        val audio = AudioPlayback(sink, TestClock())
        audio.open(stereo)
        audio.play()
        audio.balance = -1f
        audio.submitDecoded(null, FloatArray(480 * 2) { 0.5f }, 480, stereo)
        sink.pumpAll()

        val right = sink.heard.filterIndexed { index, _ -> index % 2 == 1 }
        val left = sink.heard.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(480, right.size, "every frame fed reached the device")
        assertTrue(right.all { it == 0f }, "the right channel was not silenced: ${right.distinct()}")
        assertTrue(left.all { it == 0.5f }, "the left channel changed: ${left.distinct()}")
        audio.close()
    }
}
