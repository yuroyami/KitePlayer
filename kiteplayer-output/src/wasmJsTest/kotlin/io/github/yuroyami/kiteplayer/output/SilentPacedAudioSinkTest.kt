package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import io.github.yuroyami.kiteplayer.MonotonicClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression this file exists for (S6-D7 finding 1).
 *
 * The first version of this sink stored the render callback and never called it. It compiled, it
 * satisfied the interface, and `KitePlayerPlatform.createOrNull()` returned a player, so every
 * proof run that day passed. Playback would have hung at position zero, because the engine's clock
 * anchors only when the callback consumes the audio ring. These tests assert the calling, which is
 * the only part that was ever missing.
 */
class SilentPacedAudioSinkTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** 1024 frames at 48 kHz. Every expectation below is arithmetic from this, not a stopwatch. */
    private val blockNanos = 1024L * 1_000_000_000L / 48_000L
    private val blockMillis = blockNanos / 1_000_000

    /** Reads the test scope's virtual time, so the pump and the assertions share one clock. */
    private class VirtualClock(private val scope: TestScope) : MonotonicClock {
        override fun nanos(): Long = scope.testScheduler.currentTime * 1_000_000
    }

    private fun TestScope.newSink() = SilentPacedAudioSink(backgroundScope, VirtualClock(this))

    private class CountingCallback : AudioRenderCallback {
        var calls = 0
        var lastFrames = 0
        var deadlines = mutableListOf<Long>()
        override fun onRender(destination: io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
            calls++
            lastFrames = frames
            deadlines += deadlineNanos
            return frames
        }
    }

    @Test
    fun startDrivesTheRenderCallback() = runTest {
        val sink = newSink()
        val callback = CountingCallback()
        sink.open(format, callback)
        assertEquals(0, callback.calls, "opening must not render")
        sink.start()
        advanceTimeBy(blockMillis * 5)
        sink.stop()
        assertTrue(callback.calls > 0, "the pump never called onRender, so the engine clock never moves")
        assertEquals(sink.deviceBufferFrames, callback.lastFrames, "blocks must be deviceBufferFrames wide")
        sink.close()
    }

    /** A sink that consumed instantly would drain a file in one turn and make sync meaningless. */
    @Test
    fun consumptionIsPacedRatherThanInstant() = runTest {
        val sink = newSink()
        val callback = CountingCallback()
        sink.open(format, callback)
        sink.start()
        advanceTimeBy(blockMillis * 5)
        sink.stop()
        // Exact, because the clock is virtual: five block-durations is five or six blocks, never
        // hundreds. A sink that consumed instantly would spin here and the range would catch it.
        assertTrue(
            callback.calls in 4..7,
            "expected about five blocks in five block-durations, got ${callback.calls}",
        )
    }

    /** The deadline is what the engine anchors against, so it must advance monotonically. */
    @Test
    fun deadlinesAdvanceByOneBlockEachTime() = runTest {
        val sink = newSink()
        val callback = CountingCallback()
        sink.open(format, callback)
        sink.start()
        advanceTimeBy(blockMillis * 6)
        sink.stop()
        val deadlines = callback.deadlines
        assertTrue(deadlines.size >= 2, "need at least two blocks to compare deadlines")
        for (i in 1 until deadlines.size) {
            assertEquals(
                blockNanos,
                deadlines[i] - deadlines[i - 1],
                "deadline $i did not advance by exactly one block",
            )
        }
    }

    @Test
    fun stopHaltsThePumpAndCloseIsIdempotent() = runTest {
        val sink = newSink()
        val callback = CountingCallback()
        sink.open(format, callback)
        sink.start()
        advanceTimeBy(blockMillis * 3)
        sink.stop()
        val afterStop = callback.calls
        advanceTimeBy(blockMillis * 5)
        assertEquals(afterStop, callback.calls, "the pump kept rendering after stop")
        sink.close()
        sink.close()
    }
}
