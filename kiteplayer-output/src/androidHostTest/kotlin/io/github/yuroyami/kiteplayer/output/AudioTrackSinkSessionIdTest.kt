package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Android audio session id, and why it is on the sink rather than invented by the app.
 *
 * Android's `LoudnessEnhancer`, `Equalizer`, `Visualizer` and every other `AudioEffect` attach to a
 * session id, and the only object that knows this player's is the `AudioTrack` the sink built. An
 * app cannot ask for one and get the right answer: allocating a fresh id through `AudioManager`
 * gives a session nothing is playing on, and effects attached there do nothing at all, silently.
 *
 * So the sink publishes what it has. Before open and after close it has nothing, and says so with
 * null rather than a stale number: an effect attached to a released session is the same silent
 * nothing, and a caller that sees null can wait instead.
 *
 * The real `AudioTrack.getAudioSessionId` is a stub on a host JVM, so the number here comes through
 * the driver seam like every other android.media value this suite drives.
 */
class AudioTrackSinkSessionIdTest {

    private class SessionDriver(
        private val id: Int,
        override val bufferSizeInFrames: Int = 2048,
    ) : AudioTrackDriver {
        var releaseCount = 0
            private set

        override val sessionId: Int get() = id
        override fun onWriterThreadStart() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() = Unit
        override fun release() { releaseCount++ }
        override fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int = sizeFloats
        override fun timestamp(): DriverTimestamp? = null
        override fun playbackHeadPosition(): Int = 0
    }

    private class FixedClock : MonotonicClock {
        override fun nanos(): Long = 0L
    }

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)


    @Test
    fun `a sink that has not opened a device has no session id`() {
        val sink = AudioTrackSink({ SessionDriver(42) }, FixedClock())
        assertNull(sink.platformSessionId, "a sink with no device must not name a session")
        sink.close()
    }

    @Test
    fun `an open sink publishes the device's session id`() = runBlocking {
        val sink = AudioTrackSink({ SessionDriver(42) }, FixedClock())
        try {
            sink.open(format) { _, _, _ -> 0 }
            assertEquals(42, sink.platformSessionId)
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a closed sink stops naming the session it used to have`() = runBlocking {
        // The number is the one thing about a released AudioTrack that still looks valid, and an
        // effect attached to it is silently inert. Null is the honest answer.
        val sink = AudioTrackSink({ SessionDriver(7) }, FixedClock())
        sink.open(format) { _, _, _ -> 0 }
        assertEquals(7, sink.platformSessionId)
        sink.close()
        assertNull(sink.platformSessionId, "a closed sink still named its old session")
    }

    @Test
    fun `the default SPI answer is null, so no other sink has to care`() {
        // Every sink that is not Android's inherits this: CoreAudio, the desktop line, the web
        // worklet and every test fake. The member exists for one platform and costs the rest nothing.
        val plain = object : io.github.yuroyami.kiteplayer.spi.AudioSink {
            override suspend fun open(
                request: AudioFormat,
                render: io.github.yuroyami.kiteplayer.spi.AudioRenderCallback,
            ): AudioFormat = request
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override suspend fun drain() = Unit
            override suspend fun setPaused(paused: Boolean): Boolean = true
            override val deviceBufferFrames: Int get() = 0
            override fun latencyNanos(): Long = 0
            override val latencyQuality: io.github.yuroyami.kiteplayer.LatencyQuality
                get() = io.github.yuroyami.kiteplayer.LatencyQuality.Unreliable
            override val events: kotlinx.coroutines.flow.Flow<io.github.yuroyami.kiteplayer.spi.AudioSinkEvent>
                get() = kotlinx.coroutines.flow.emptyFlow()
            override fun close() = Unit
        }
        assertNull(plain.platformSessionId)
    }
}
