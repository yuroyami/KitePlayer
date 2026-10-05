package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pause, a resume and a seek on a device that cuts the sound where it stops (#486).
 *
 * The device is pulled once every 10 ms of virtual time and keeps everything it is handed while it
 * runs, so the samples a listener would hear across a pause or a seek are one stream the test can
 * walk. The left channel carries a full-scale 50 Hz sine, whose steepest step between two samples
 * is 2π·50/48000, about 0.0065; the right channel carries a constant 0.5, which is never zero
 * while sound plays. A cut is a step of up to the whole wave; a fade adds at most one ramp step,
 * 1/240, to the wave's own.
 */
class AudioFadeTest {

    private val format = AudioFormat(
        sampleRate = RATE,
        channels = 2,
        sampleFormat = SampleFormat.F32,
        channelLayoutMask = null,
    )

    /** Engine time is the test's virtual time, so the deadlines a fade waits on are the ones it sleeps through. */
    private class VirtualClock(private val scope: TestScope) : MonotonicClock {
        override fun nanos(): Long = scope.testScheduler.currentTime * 1_000_000L
    }

    private class Capture(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        val scratch = FloatArray(frames * format.channels)

        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(
                destination = scratch,
                destinationOffset = destinationFrameOffset * format.channels,
                startIndex = sourceOffset,
                endIndex = sourceOffset + frames * format.channels,
            )
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            scratch.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
        }
    }

    /** A device pulled on a 10 ms period while it runs, recording every frame it is handed. */
    private class PulledSink(
        private val clock: MonotonicClock,
        override val cutsSoundOnStop: Boolean = true,
    ) : AudioSink {
        override val deviceBufferFrames: Int = PERIOD_FRAMES
        private var render: AudioRenderCallback? = null
        private var capture: Capture? = null
        var running = false
            private set
        val calls = mutableListOf<String>()
        val left = mutableListOf<Float>()
        val right = mutableListOf<Float>()

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            capture = Capture(request, PERIOD_FRAMES)
            return request
        }

        fun pull() {
            if (!running) return
            val callback = render ?: return
            val destination = capture ?: return
            callback.onRender(destination, PERIOD_FRAMES, clock.nanos() + PERIOD_NANOS)
            for (frame in 0 until PERIOD_FRAMES) {
                left += destination.scratch[frame * 2]
                right += destination.scratch[frame * 2 + 1]
            }
        }

        override suspend fun start() { calls += "start"; running = true }
        override suspend fun stop() { calls += "stop"; running = false }
        override suspend fun drain() { calls += "drain"; running = false }
        override suspend fun setPaused(paused: Boolean): Boolean {
            calls += if (paused) "pause" else "resume"
            running = !paused
            return true
        }
        override fun latencyNanos(): Long = 0
        override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
        override val events: Flow<AudioSinkEvent> = emptyFlow()
        override fun close() = Unit
    }

    private fun CoroutineScope.pullEvery10Ms(sink: PulledSink): Job = launch {
        while (isActive) {
            sink.pull()
            delay(10)
        }
    }

    /** [frames] frames of the test signal starting at sample [from] of the sine. */
    private fun signal(from: Int, frames: Int): FloatArray = FloatArray(frames * 2) { index ->
        val frame = from + index / 2
        if (index % 2 == 0) sin(2.0 * PI * TONE_HZ * frame / RATE).toFloat() else 0.5f
    }

    private fun largestStep(samples: List<Float>): Float {
        var largest = 0f
        for (i in 1 until samples.size) largest = maxOf(largest, abs(samples[i] - samples[i - 1]))
        return largest
    }

    @Test
    fun aPauseAndAResumeFadeRatherThanCut() = runTest {
        val clock = VirtualClock(this)
        val sink = PulledSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(format)
        audio.submitDecoded(Pts(0), signal(0, 8_000), 8_000, format)
        val puller = pullEvery10Ms(sink)

        audio.play()
        delay(35)
        audio.pause()
        val heard = sink.right.size
        val paused = audio.position()
        delay(500)
        audio.play()
        delay(40)
        puller.cancel()

        val limit = SINE_STEP + RAMP_STEP
        assertTrue(largestStep(sink.left) <= limit, "the sine stepped by ${largestStep(sink.left)}, over $limit")
        assertTrue(largestStep(sink.right) <= RAMP_STEP + 1e-6f, "the level stepped by ${largestStep(sink.right)}")
        assertEquals(listOf("resume", "start", "stop", "pause", "resume", "start"), sink.calls)

        // The paused position is the frame after the last one faded: the right channel is nonzero
        // on every frame the fade played but its last, which the walk reaches at gain zero.
        val lastSounding = sink.right.subList(0, heard).indexOfLast { it != 0f }
        val expected = (lastSounding + 2).toLong() * 1_000_000L / RATE
        assertEquals(expected, paused?.micros, "the pause holds the position heard last")

        // The resume carries on with the frame after the fade, so the sine continues in phase.
        val resumedAt = (heard until sink.left.size).first { sink.right[it] != 0f }
        val nextSample = lastSounding + 2
        val gain = sink.right[resumedAt + 300] / 0.5f
        val wanted = sin(2.0 * PI * TONE_HZ * (nextSample + 300) / RATE).toFloat() * gain
        assertTrue(abs(sink.left[resumedAt + 300] - wanted) < 1e-5f, "the resume skipped or repeated audio")
        audio.close()
    }

    @Test
    fun aSeekWhilePlayingFadesOutAndTheNewPositionFadesIn() = runTest {
        val clock = VirtualClock(this)
        val sink = PulledSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(format)
        audio.submitDecoded(Pts(0), signal(0, 6_000), 6_000, format)
        val puller = pullEvery10Ms(sink)

        audio.play()
        delay(35)
        audio.flush(Generation.Initial.next())
        // A quarter of a cycle on, where the wave stands at its crest: a cut start would step by 1.
        audio.submitDecoded(Pts(2_000_000), signal(240, 6_000), 6_000, format)
        audio.play()
        delay(40)
        puller.cancel()

        val limit = SINE_STEP + RAMP_STEP
        assertTrue(largestStep(sink.left) <= limit, "the sine stepped by ${largestStep(sink.left)}, over $limit")
        assertTrue(largestStep(sink.right) <= RAMP_STEP + 1e-6f, "the level stepped by ${largestStep(sink.right)}")
        audio.close()
    }

    @Test
    fun aVolumeSetWhilePausedIsInPlaceFromTheFirstResumedFrame() = runTest {
        val clock = VirtualClock(this)
        val sink = PulledSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(format)
        audio.submitDecoded(Pts(0), signal(0, 8_000), 8_000, format)
        val puller = pullEvery10Ms(sink)

        audio.play()
        delay(35)
        audio.pause()
        val heard = sink.right.size
        audio.volume = 0.2f
        audio.play()
        delay(40)
        puller.cancel()

        val resumed = sink.right.subList(heard, sink.right.size)
        assertTrue(resumed.any { it != 0f }, "nothing was heard after the resume")
        assertTrue(resumed.all { it <= 0.5f * 0.2f + 1e-6f }, "the old volume leaked past the resume: ${resumed.max()}")
        audio.close()
    }

    @Test
    fun aDeviceThatFadesItselfIsStoppedAsBefore() = runTest {
        val clock = VirtualClock(this)
        val sink = PulledSink(clock, cutsSoundOnStop = false)
        val audio = AudioPlayback(sink, clock)
        audio.open(format)
        audio.submitDecoded(Pts(0), signal(0, 8_000), 8_000, format)
        val puller = pullEvery10Ms(sink)

        audio.play()
        delay(35)
        val before = sink.right.size
        audio.pause()
        puller.cancel()

        assertEquals(before, sink.right.size, "a pause on a device that fades itself waits for nothing")
        assertEquals(listOf("resume", "start", "pause"), sink.calls)
        audio.close()
    }

    @Test
    fun aDeviceThatStopsPullingDoesNotHoldThePauseForEver() = runTest {
        val clock = VirtualClock(this)
        val sink = PulledSink(clock)
        val audio = AudioPlayback(sink, clock)
        audio.open(format)
        audio.submitDecoded(Pts(0), signal(0, 8_000), 8_000, format)

        audio.play()
        sink.pull()
        val began = testScheduler.currentTime
        audio.pause()
        assertTrue(testScheduler.currentTime - began <= 250, "the pause waited ${testScheduler.currentTime - began} ms")
        assertEquals(listOf("resume", "start", "pause"), sink.calls)
        audio.close()
    }

    private companion object {
        const val RATE = 48_000
        const val PERIOD_FRAMES = 480
        const val PERIOD_NANOS = 10_000_000L
        const val TONE_HZ = 50.0

        /** The sine's own steepest step between two samples. */
        val SINE_STEP = (2.0 * PI * TONE_HZ / RATE).toFloat()

        /** One step of the 5 ms ramp at 48 kHz: the whole range over 240 frames. */
        const val RAMP_STEP = 1f / 240f
    }
}
