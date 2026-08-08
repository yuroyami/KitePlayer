package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
