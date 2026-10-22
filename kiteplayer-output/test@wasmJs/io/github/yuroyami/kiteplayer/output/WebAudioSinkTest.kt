package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The feeder's policy, asserted as arithmetic.
 *
 * None of this needs a browser and none of it should: the sink's decisions are queue depth and
 * time, and a real `AudioContext` would add a user gesture and a stopwatch to every assertion for
 * no extra proof. [WebAudioDevice] exists to be faked here, exactly as [SilentPacedAudioSink]
 * splits out its scope and clock for the same reason.
 *
 * What is NOT covered here, stated so nobody reads this file as more than it is: that the worklet
 * source parses, that `addModule` resolves, and that a browser makes a sound. Those need a browser
 * and belong with the conformance matrix run in a real browser.
 */
class WebAudioSinkTest {

    private val blockFrames = 1024
    private val highWaterFrames = 4096

    private class VirtualClock(private val scope: TestScope) : MonotonicClock {
        override fun nanos(): Long = scope.testScheduler.currentTime * 1_000_000
    }

    /** Records every block handed over, so a test can read back what the device would have played. */
    private class FakeDevice(
        override val sampleRate: Int = 48_000,
        override val channels: Int = 2,
        private val latencySeconds: Double? = 0.01,
    ) : WebAudioDevice {
        val pushed = mutableListOf<FloatArray>()
        var queued = 0
        var silence = 0
        var flushes = 0
        var resumes = 0
        var suspends = 0
        var closed = false

        override fun queuedFrames(): Int = queued
        override fun underrunFrames(): Int = silence
        override fun outputLatencySeconds(): Double? = latencySeconds

        override fun enqueue(samples: FloatArray, frames: Int) {
            pushed += samples.copyOf(frames * channels)
            queued += frames
        }

        override fun flush() {
            flushes++
            queued = 0
        }

        override suspend fun resume() {
            resumes++
        }

        override suspend fun suspendPlayback() {
            suspends++
        }

        override fun close() {
            closed = true
        }

        /** Pretends the worklet played [frames], which is what makes room for a refill. */
        fun play(frames: Int) {
            queued = (queued - frames).coerceAtLeast(0)
        }
    }

    /**
     * Fills every frame it claims to write with [fill], and writes only what [framesFor] allows.
     *
     * Writing a real pattern is the point: a callback that returned a count without touching the
     * buffer could not catch a stale-tail bug, because every sample would already be zero.
     */
    private class PatternCallback(
        private val fill: Float = 1f,
        private val framesFor: (call: Int) -> Int = { it -> -1 },
    ) : AudioRenderCallback {
        var calls = 0
        val deadlines = mutableListOf<Long>()

        override fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
            val requested = framesFor(calls)
            val write = if (requested < 0) frames else requested.coerceIn(0, frames)
            calls++
            deadlines += deadlineNanos
            if (write > 0) {
                val source = FloatArray(write * destination.format.channels) { fill }
                destination.writeInterleaved(source, 0, 0, write)
            }
            return write
        }
    }

    private fun TestScope.newSink(device: WebAudioDevice) =
        WebAudioSink(device, backgroundScope, VirtualClock(this))

    private val request = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** The device dictates and the engine adapts: a sink never converts, so it never pretends. */
    @Test
    fun openReturnsWhatTheDeviceImposesRatherThanWhatWasAsked() = runTest {
        val device = FakeDevice(sampleRate = 44_100, channels = 1)
        val sink = newSink(device)
        val accepted = sink.open(request, PatternCallback())
        assertEquals(44_100, accepted.sampleRate, "the hardware rate must come back, not the request")
        assertEquals(1, accepted.channels, "the destination's channel count must come back")
        assertEquals(SampleFormat.F32, accepted.sampleFormat, "Web Audio is float and nothing else")
        sink.close()
    }

    /** Prebuffer to the high-water mark and then WAIT. A feeder with no ceiling is a memory leak. */
    @Test
    fun startFillsToHighWaterAndThenStops() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        val callback = PatternCallback()
        sink.open(request, callback)
        sink.start()
        runCurrent()
        assertEquals(
            highWaterFrames / blockFrames,
            device.pushed.size,
            "the feeder must fill to exactly the high-water mark",
        )
        advanceTimeBy(500)
        assertEquals(
            highWaterFrames / blockFrames,
            device.pushed.size,
            "the queue was still full, so nothing more should have been rendered",
        )
        sink.stop()
        sink.close()
    }

    @Test
    fun theFeederRefillsOnlyAsTheDeviceDrains() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        val afterPrebuffer = device.pushed.size
        device.play(2 * blockFrames)
        advanceTimeBy(50)
        assertEquals(
            afterPrebuffer + 2,
            device.pushed.size,
            "two blocks were played, so exactly two should have been refilled",
        )
        sink.stop()
        sink.close()
    }

    /**
     * The deadline is what the engine's clock anchors on, so it must count what is ALREADY queued.
     *
     * A deadline that ignored the queue would claim every block is audible one block from now, and
     * the four prebuffered blocks would all carry the same time. Video would then be scheduled
     * against audio that is up to 85 ms from being heard.
     */
    @Test
    fun deadlinesCountEverythingAlreadyQueuedAheadOfTheBlock() = runTest {
        val device = FakeDevice(latencySeconds = 0.01)
        val sink = newSink(device)
        val callback = PatternCallback()
        sink.open(request, callback)
        sink.start()
        runCurrent()
        val latencyNanos = 10_000_000L
        assertTrue(callback.deadlines.size >= 4, "expected the four prebuffered blocks")
        // Frames first, then ONE division, which is how the sink computes it. Multiplying a
        // truncated per-block figure instead would drift by a nanosecond per block, and the drift
        // would grow without bound over a long file rather than staying a rounding error.
        callback.deadlines.take(4).forEachIndexed { index, deadline ->
            val aheadFrames = (index + 1).toLong() * blockFrames
            assertEquals(
                aheadFrames * 1_000_000_000L / 48_000L + latencyNanos,
                deadline,
                "block $index must be audible after everything queued ahead of it, plus device latency",
            )
        }
        sink.stop()
        sink.close()
    }

    /**
     * The staging block is REUSED, so a short render that left its tail alone would replay the
     * previous block's audio. This is the one bug in this class that would be audible, not silent.
     */
    @Test
    fun aShortRenderIsPaddedWithSilenceRatherThanLastBlocksAudio() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        // First block full of 1.0, second block writes only its first half.
        val half = blockFrames / 2
        sink.open(request, PatternCallback(fill = 1f, framesFor = { call -> if (call == 1) half else -1 }))
        sink.start()
        runCurrent()
        assertTrue(device.pushed.size >= 2, "need the first two blocks to compare them")
        val second = device.pushed[1]
        val channels = 2
        assertNotEquals(0f, second[0], "the written half must still carry its samples")
        for (i in half * channels until second.size) {
            assertEquals(0f, second[i], "frame ${i / channels} of a short block was not silenced")
        }
        sink.stop()
        sink.close()
    }

    /** A seek must throw away what belongs to the position being left. */
    @Test
    fun stopDropsEverythingUnplayed() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        assertTrue(device.queued > 0, "the prebuffer should have filled the queue")
        sink.stop()
        assertEquals(1, device.flushes, "stop must flush")
        assertEquals(0, device.queued, "a flushed device holds nothing")
        val afterStop = device.pushed.size
        advanceTimeBy(200)
        assertEquals(afterStop, device.pushed.size, "the feeder kept rendering after stop")
        sink.close()
    }

    @Test
    fun pauseStopsTheFeederAndKeepsWhatIsQueued() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        val queuedBeforePause = device.queued
        sink.setPaused(true)
        assertEquals(1, device.suspends, "pause must suspend the context")
        assertEquals(0, device.flushes, "pause must NOT discard, that is what stop is for")
        assertEquals(queuedBeforePause, device.queued, "a pause that dropped audio would click on resume")
        device.play(2 * blockFrames)
        val afterPause = device.pushed.size
        advanceTimeBy(200)
        assertEquals(afterPause, device.pushed.size, "the feeder must not render while paused")
        sink.setPaused(false)
        advanceTimeBy(50)
        assertTrue(device.pushed.size > afterPause, "resume must restart the feeder")
        sink.stop()
        sink.close()
    }

    /** The device counts the silence it invented; the sink turns growth in that count into events. */
    @Test
    fun underrunsAreReportedAsTheyGrow() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        val seen = mutableListOf<String>()
        val collector = backgroundScope.launch { sink.events.collect { seen += it.toString() } }
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        device.silence = 256
        device.play(blockFrames)
        advanceTimeBy(50)
        assertTrue(seen.isNotEmpty(), "a dry device must be reported")
        val afterFirst = seen.size
        advanceTimeBy(200)
        assertEquals(afterFirst, seen.size, "an unchanged underrun count must not re-report")
        collector.cancel()
        sink.stop()
        sink.close()
    }

    /** No platform figure means nothing to filter, and saying Estimated would invent confidence. */
    @Test
    fun latencyQualityIsHonestAboutWhatThePlatformOffers() = runTest {
        val withFigure = newSink(FakeDevice(latencySeconds = 0.02))
        assertEquals(LatencyQuality.Estimated, withFigure.latencyQuality)
        val without = newSink(FakeDevice(latencySeconds = null))
        assertEquals(LatencyQuality.Unreliable, without.latencyQuality)
    }

    @Test
    fun latencyCountsTheQueueThisSinkItselfCreated() = runTest {
        val device = FakeDevice(latencySeconds = 0.01)
        val sink = newSink(device)
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        val queuedNanos = highWaterFrames.toLong() * 1_000_000_000L / 48_000L
        assertEquals(
            queuedNanos + 10_000_000L,
            sink.latencyNanos(),
            "latency must include the worklet queue, not just the platform's own figure",
        )
        sink.stop()
        sink.close()
    }

    @Test
    fun closeReleasesTheDevice() = runTest {
        val device = FakeDevice()
        val sink = newSink(device)
        sink.open(request, PatternCallback())
        sink.start()
        runCurrent()
        sink.close()
        assertTrue(device.closed, "close must release the AudioContext")
        sink.close()
    }
}
