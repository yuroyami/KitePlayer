package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives the real audio device.
 *
 * These tests make sound, briefly and quietly. That is the point. The reason the audio clock is
 * anchored to the device's own timestamps instead of to a guess is something only real hardware can
 * confirm, and the last test here is the single assertion that proves the whole design: the instant
 * the device says a buffer will be heard must land slightly ahead of now on the engine's own clock.
 *
 * The ring used here is a deliberately naive one, local to the test. The engine's real ring is tested
 * in `kiteplayer-core`, and keeping it out of this file means these tests fail only when the sink is
 * wrong.
 */
class CoreAudioSinkTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** Just enough ring to feed a device and count what happened. Not the engine's implementation. */
    private class TestRing(val format: AudioFormat, capacityFrames: Int) {
        private val data = FloatArray(capacityFrames * format.channels)
        private val capacity = capacityFrames
        private val written = atomic(0L)
        private val consumed = atomic(0L)
        val underruns = atomic(0L)
        val lastDeadline = atomic(0L)
        val lastFrameIndex = atomic(-1L)

        val buffered: Int get() = (written.value - consumed.value).toInt()

        fun write(source: FloatArray, sourceFrameOffset: Int, frames: Int): Int {
            val room = capacity - buffered
            val toWrite = min(frames, room)
            var start = (written.value % capacity).toInt()
            var done = 0
            while (done < toWrite) {
                val run = min(toWrite - done, capacity - start)
                source.copyInto(
                    destination = data,
                    destinationOffset = start * format.channels,
                    startIndex = (sourceFrameOffset + done) * format.channels,
                    endIndex = (sourceFrameOffset + done + run) * format.channels,
                )
                start = (start + run) % capacity
                done += run
            }
            written.value += toWrite
            return toWrite
        }

        fun render(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
            val available = buffered
            val toRead = min(frames, available)
            var start = (consumed.value % capacity).toInt()
            var done = 0
            while (done < toRead) {
                val run = min(toRead - done, capacity - start)
                destination.writeInterleaved(data, start * format.channels, done, run)
                start = (start + run) % capacity
                done += run
            }
            consumed.value += toRead
            if (toRead < frames) {
                destination.writeSilence(toRead, frames - toRead)
                underruns.incrementAndGet()
            }
            if (toRead > 0) {
                lastFrameIndex.value = consumed.value - 1
                lastDeadline.value = deadlineNanos - ((frames - toRead).toLong() * 1_000_000_000L / format.sampleRate)
            }
            return toRead
        }
    }

    private fun tone(frames: Int, hz: Double = 440.0, amplitude: Double = 0.08): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * hz * i / format.sampleRate) * amplitude).toFloat()
            out[i * 2] = sample
            out[i * 2 + 1] = sample
        }
        return out
    }

    @Test
    fun `the host clock and CoreAudio agree on a time base`() {
        val before = AppleHostClock.nanos()
        val hostNow = AppleHostClock.hostTimeToNanos(platform.CoreAudio.AudioGetCurrentHostTime())
        val after = AppleHostClock.nanos()

        assertTrue(
            hostNow in before..after,
            "a host time converted now must fall between two readings of the same clock: " +
                "$before .. $hostNow .. $after",
        )
    }

    @Test
    fun `the host clock never goes backwards`() {
        var previous = AppleHostClock.nanos()
        repeat(20_000) {
            val now = AppleHostClock.nanos()
            assertTrue(now >= previous, "the clock went backwards: $previous then $now")
            previous = now
        }
    }

    @Test
    fun `a tone plays and the device consumes it at real time speed`() = runBlocking {
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = format.sampleRate / 2)
        val toneFrames = format.sampleRate
        val samples = tone(toneFrames)

        val negotiated = sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            ring.render(destination, frames, deadlineNanos)
        })
        assertEquals(48_000, negotiated.sampleRate)
        assertEquals(2, negotiated.channels)
        assertEquals(SampleFormat.F32, negotiated.sampleFormat)
        assertEquals(LatencyQuality.Estimated, sink.latencyQuality)

        try {
            // Prime before starting. Starting a device with nothing to play is an immediate underrun
            // and an audible click, which is why the engine fills before it starts too.
            var written = ring.write(samples, 0, toneFrames / 4)
            assertTrue(written > 0)

            sink.start()

            val startedAt = AppleHostClock.nanos()
            val runFor = 600.milliseconds.inWholeNanoseconds
            while (AppleHostClock.nanos() - startedAt < runFor && written < toneFrames) {
                written += ring.write(samples, written, toneFrames - written)
                delay(5)
            }

            val framesPlayed = ring.lastFrameIndex.value + 1
            val elapsedMs = (AppleHostClock.nanos() - startedAt) / 1_000_000.0
            val playedMs = framesPlayed * 1_000.0 / format.sampleRate

            assertTrue(framesPlayed > 0, "the device did not ask for any audio")
            assertTrue(
                abs(playedMs - elapsedMs) < 120.0,
                "the device must consume audio at real time: ${playedMs}ms played in ${elapsedMs}ms elapsed",
            )
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `latency is reported as a plausible positive figure while playing`() = runBlocking {
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = format.sampleRate / 4)

        sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            ring.render(destination, frames, deadlineNanos)
        })
        try {
            ring.write(FloatArray(4_800 * 2), 0, 4_800)
            sink.start()
            delay(120)

            val latency = sink.latencyNanos()
            assertTrue(
                latency in 0..200_000_000L,
                "a device buffer is a few milliseconds, so the reported latency should be small, was $latency ns",
            )
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `pause keeps buffered audio and resume consumes it again`() = runBlocking {
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = 48_000)

        sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            ring.render(destination, frames, deadlineNanos)
        })
        try {
            ring.write(FloatArray(48_000 * 2), 0, 48_000)
            sink.start()
            delay(60)

            assertTrue(sink.setPaused(true), "CoreAudio can pause without discarding")
            val whilePaused = ring.buffered
            delay(100)
            assertEquals(
                whilePaused,
                ring.buffered,
                "a paused device consumes nothing, so buffered audio survives the pause",
            )

            assertTrue(sink.setPaused(false))
            delay(60)
            assertTrue(ring.buffered < whilePaused, "resuming must start consuming again")
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `an unfed device is handed silence rather than stalling`() = runBlocking {
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = 4_800)

        sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            ring.render(destination, frames, deadlineNanos)
        })
        try {
            sink.start()
            delay(120)
            assertTrue(
                ring.underruns.value > 0,
                "a device with nothing to play must be handed silence, and the underrun counted",
            )
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `the deadline the device reports is in the near future on the engine clock`() = runBlocking {
        // This is the assertion the whole design rests on. If the engine's clock and CoreAudio's host
        // time used different bases, the offset measured here would be enormous instead of a few
        // milliseconds, and audio and video would sit at a fixed offset no correction could find.
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = 48_000)
        val worst = atomic(0L)
        val samples = atomic(0)

        sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            val offset = deadlineNanos - AppleHostClock.nanos()
            if (abs(offset) > abs(worst.value)) worst.value = offset
            samples.incrementAndGet()
            ring.render(destination, frames, deadlineNanos)
        })
        try {
            ring.write(FloatArray(48_000 * 2), 0, 48_000)
            sink.start()
            delay(250)
        } finally {
            sink.stop()
            sink.close()
        }

        assertTrue(samples.value > 0, "the device never called the render callback")
        val worstMs = worst.value / 1_000_000.0
        assertTrue(
            worstMs > -5.0 && worstMs < 500.0,
            "the deadline must sit slightly ahead of now on the engine's clock; worst offset was $worstMs ms " +
                "over ${samples.value} callbacks",
        )
    }

    @Test
    fun `every callback is handed the same buffer wrapper`() = runBlocking {
        // The real-time thread may not allocate, and one wrapper built during open is how this sink
        // keeps that promise. A second instance showing up means something is being constructed per
        // callback again, which is the defect this guards.
        val sink = CoreAudioSink()
        val ring = TestRing(format, capacityFrames = 48_000)
        val firstSeen = atomic<AudioSinkBuffer?>(null)
        val callbacks = atomic(0)
        val otherInstances = atomic(0)

        sink.open(format, AudioRenderCallback { destination, frames, deadlineNanos ->
            val seen = firstSeen.value
            if (seen == null) {
                firstSeen.value = destination
            } else if (seen !== destination) {
                otherInstances.incrementAndGet()
            }
            callbacks.incrementAndGet()
            ring.render(destination, frames, deadlineNanos)
        })
        try {
            ring.write(FloatArray(48_000 * 2), 0, 48_000)
            sink.start()
            delay(250)
        } finally {
            sink.stop()
            sink.close()
        }

        assertTrue(callbacks.value > 1, "one callback proves nothing; the device made ${callbacks.value}")
        assertEquals(
            0,
            otherInstances.value,
            "every one of ${callbacks.value} callbacks must be handed the same wrapper instance",
        )
    }

    @Test
    fun `a failed open hands back everything it created`() = runBlocking {
        // A negative sample rate is not a format any device takes, and the device refuses it after the
        // audio unit instance, the pinned self reference and the buffer wrapper all exist. That window
        // between the instance and the end of open is exactly where things used to be left behind.
        val sink = CoreAudioSink()
        val impossible = AudioFormat(sampleRate = -1, channels = 2, sampleFormat = SampleFormat.F32)

        assertFailsWith<IllegalStateException> {
            sink.open(impossible, AudioRenderCallback { _, _, _ -> 0 })
        }
        assertEquals(0, sink.retainedResources(), "a failed open must leave the sink owning nothing")

        // The sink still opens, which is the observable half of the same claim: nothing was left
        // half open behind the failure.
        sink.open(format, AudioRenderCallback { destination, frames, _ ->
            destination.writeSilence(0, frames)
            frames
        })
        assertEquals(5, sink.retainedResources(), "an open sink owns its unit, reference, format, callback and wrapper")
        sink.close()
        assertEquals(0, sink.retainedResources(), "close must let go of all five")
    }

    @OptIn(ExperimentalNativeApi::class, NativeRuntimeApi::class)
    @Test
    fun `a sink whose open failed is not pinned by a leaked reference`() {
        // The other half of the same defect, and the half a field cannot show: the callback needs a
        // StableRef to reach this object from C, and a StableRef that is never disposed pins the sink
        // for the life of the process. Collecting a sink that only a leak could hold is the proof.
        val abandoned = openFailingSinkWeakly()

        GC.collect()

        assertNull(
            abandoned.value,
            "a StableRef the failed open never disposed would keep this sink alive forever",
        )
    }

    /** Fails an open and keeps only a weak reference, so nothing this test holds can pin the sink. */
    @OptIn(ExperimentalNativeApi::class)
    private fun openFailingSinkWeakly(): WeakReference<CoreAudioSink> {
        val sink = CoreAudioSink()
        assertFailsWith<IllegalStateException> {
            runBlocking {
                sink.open(
                    AudioFormat(sampleRate = -1, channels = 2, sampleFormat = SampleFormat.F32),
                    AudioRenderCallback { _, _, _ -> 0 },
                )
            }
        }
        return WeakReference(sink)
    }

    @Test
    fun `a clock on another time base is refused`() {
        // The sink converts CoreAudio host times through AppleHostClock. An engine measuring time from
        // anywhere else would disagree with the device by a constant nothing could correct, so the
        // sink refuses instead of half honouring the clock it was given.
        val foreign = object : MonotonicClock {
            override fun nanos(): Long = 0
        }

        val direct = assertFailsWith<IllegalArgumentException> { CoreAudioSink(foreign) }
        val throughFactory = assertFailsWith<IllegalArgumentException> {
            runBlocking { CoreAudioSinkFactory(foreign).create() }
        }

        for (failure in listOf(direct, throughFactory)) {
            val message = failure.message ?: ""
            assertTrue(message.contains("AppleHostClock"), "the message must name the clock to use: $message")
            assertTrue(message.contains("host time"), "the message must explain the shared time base: $message")
        }
    }
}
