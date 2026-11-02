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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A pause freezes the position the listener last heard, on a device that dates each block a whole
 * buffer ahead, as an AudioTrack does and does not fade at pause (#646). The device's last anchor,
 * applied to the frozen clock as it was, moved the paused position 130 ms ahead of the sound.
 */
class AudioPausePositionTest {

    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    private class Capture(override val format: AudioFormat) : AudioSinkBuffer {
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) = Unit
        override fun writeSilence(frameOffset: Int, frames: Int) = Unit
    }

    /** A device the test pulls by hand. */
    private class HandSink : AudioSink {
        var render: AudioRenderCallback? = null
        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            return request
        }
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun drain() = Unit
        override suspend fun setPaused(paused: Boolean): Boolean = true
        override val deviceBufferFrames: Int = BLOCK
        override fun latencyNanos(): Long = 0
        override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
        override val events: Flow<AudioSinkEvent> = emptyFlow()
        override fun close() = Unit
    }

    @Test
    fun aPausedPositionIsTheOneLastHeardNotTheDevicesLastAnchor() = runTest {
        val clock = TestClock(1_000_000_000L)
        val sink = HandSink()
        val audio = AudioPlayback(sink, clock)
        val accepted = audio.open(format)
        audio.submitDecoded(Pts(0), FloatArray(7_200 * 2) { 0.1f }, 7_200, accepted)
        audio.play()
        val buffer = Capture(accepted)
        repeat(14) {
            // Each block is heard a whole AudioTrack buffer after it is taken.
            sink.render!!.onRender(buffer, BLOCK, clock.nanos() + AHEAD_NANOS)
            clock.advanceNanos(BLOCK * 1_000_000_000L / 48_000)
        }
        val heard = audio.position()!!.micros
        audio.pause()
        val paused = audio.position()!!.micros
        assertTrue(abs(paused - heard) <= BLOCK_MICROS, "paused at $paused after $heard was heard")
        clock.advance(500.milliseconds)
        assertEquals(paused, audio.position()!!.micros, "a paused position moved")
        audio.close()
    }

    private companion object {
        const val BLOCK = 512
        const val BLOCK_MICROS = 512L * 1_000_000 / 48_000
        const val AHEAD_NANOS = 130_000_000L
    }
}
