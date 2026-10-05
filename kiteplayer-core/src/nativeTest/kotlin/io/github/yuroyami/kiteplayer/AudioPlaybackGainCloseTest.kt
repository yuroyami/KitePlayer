package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioAnchor
import io.github.yuroyami.kiteplayer.internal.AudioRingHandle
import io.github.yuroyami.kiteplayer.internal.OpenedAudioPath
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A volume change from another thread against [AudioPlayback.close].
 *
 * On Apple the sink's close frees the C ring that a gain change writes into, so a close must not
 * finish while a change is inside the ring. The ring here is a fake whose gain call can be held,
 * which parks a change at exactly that point.
 */
class AudioPlaybackGainCloseTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun aCloseWaitsForAGainChangeInsideTheRing() = runBlocking {
        val ring = HeldGainRing(format)
        val audio = AudioPlayback(IdleSink())
        audio.openPath = { _, request, _ -> OpenedAudioPath(request, ring) }
        audio.open(format)
        ring.holdNextGain = true
        val state = GainState()
        val setter = launch(Dispatchers.IO) {
            audio.volume = 0.5f
            state.setterReturned = true
        }
        val closer = try {
            while (!ring.insideGain) usleep(100u)
            val closer = launch(Dispatchers.IO) {
                audio.close()
                state.closeReturned = true
            }
            // A close that does not wait finishes in well under a millisecond.
            usleep(50_000u)
            state.closedWhileHeld = state.closeReturned
            closer
        } finally {
            ring.releaseGain = true
        }
        setter.join()
        closer.join()
        assertFalse(state.closedWhileHeld, "the close finished while a gain change was inside the ring")
        assertTrue(state.setterReturned && state.closeReturned, "both calls return once the change goes on")
        val callsAtClose = ring.gainCalls
        audio.muted = true
        assertEquals(callsAtClose, ring.gainCalls, "a change after the close finds no ring")
    }
}

/** A ring that takes everything and can hold one [setGain] call until released. */
private class HeldGainRing(override val format: AudioFormat) : AudioRingHandle {
    @Volatile var holdNextGain = false
    @Volatile var insideGain = false
    @Volatile var releaseGain = false
    @Volatile var gainCalls = 0

    override val underruns: Long get() = 0
    override val bufferedFrames: Int get() = 0
    override val bufferedUs: Long get() = 0

    override fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int = frames

    override fun anchor(): AudioAnchor? = null

    override fun setGain(target: Float) {
        gainCalls++
        if (holdNextGain) {
            holdNextGain = false
            insideGain = true
            while (!releaseGain) usleep(100u)
        }
    }

    override fun hold(held: Boolean) = Unit

    override val silent: Boolean get() = false

    override fun markEnding() = Unit

    override fun flush() = Unit
}

/** A device that does nothing; the ring above stands in for the one a real sink would own. */
private class IdleSink : AudioSink {
    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat = request
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun drain() = Unit
    override suspend fun setPaused(paused: Boolean): Boolean = true
    override val deviceBufferFrames: Int = 512
    override fun latencyNanos(): Long = 0
    override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
    override val events: Flow<AudioSinkEvent> = emptyFlow()
    override fun close() = Unit
}

/** Plain volatile flags; the test needs visibility across threads, not atomicity. */
private class GainState {
    @Volatile var setterReturned = false
    @Volatile var closeReturned = false
    @Volatile var closedWhileHeld = false
}
