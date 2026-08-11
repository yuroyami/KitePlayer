@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.CoreVideo.CVGetCurrentHostTime
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.abs
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
 * These tests make sound, briefly and quietly. That is the point. The reason the audio clock is anchored
 * to the device's own timestamps instead of to a guess is something only real hardware can confirm, and
 * one of the assertions here is the one the whole design rests on: the instant the device says a buffer
 * will be heard must land slightly ahead of now on the engine's own clock.
 *
 * ### What B1.8 changed about this file
 *
 * Every case that used to hand the sink an [AudioRenderCallback] over a small Kotlin ring of its own now
 * feeds the C ring the sink owns, through the same three C calls the engine's feeder uses (see
 * `CRingSupport.kt`). The sink has no Kotlin callback to hand anything to any more: its render callback
 * is a `static` C function it installs itself, which is register item B1-17. Keeping a Kotlin ring here
 * would have left these tests exercising a path no macOS user runs, which is exactly the substitution
 * plan section 2 forbids.
 *
 * Two cases moved out of Kotlin rather than being rewritten, because their subject moved: the
 * absent-callback fill and the clamp on a render longer than the device asked for are now properties of
 * `kprt_ring_render` and of the C callback, and they are asserted in
 * `kiteplayer-rt/native/tests/test_sink_callback.c`. They are named in
 * [CoreAudioSinkRealTimeTest] so the mapping is written down instead of implied.
 */
class CoreAudioSinkTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** Half a second of ring, which is what these cases feed and drain. */
    private fun halfSecond(negotiated: AudioFormat): Int = negotiated.sampleRate / 2

    @Test
    fun `the host clock and CoreAudio agree on a time base`() {
        val before = AppleHostClock.nanos()
        val hostNow = AppleHostClock.hostTimeToNanos(CVGetCurrentHostTime())
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
        val handoff = sink.openWithRing(format) { negotiated -> halfSecond(negotiated) }
        val ring = handoff.ring
        assertEquals(48_000, handoff.format.sampleRate)
        assertEquals(2, handoff.format.channels)
        assertEquals(SampleFormat.F32, handoff.format.sampleFormat)
        assertEquals(LatencyQuality.Estimated, sink.latencyQuality)

        try {
            // Prime before starting. Starting a device with nothing to play is an immediate underrun and
            // an audible click, which is why the engine fills before it starts too.
            var written = feedRing(ring, format.sampleRate / 4, 0, pts = 0)
            assertTrue(written > 0)

            sink.start()

            val startedAt = AppleHostClock.nanos()
            val runFor = 600.milliseconds.inWholeNanoseconds
            val wanted = format.sampleRate.toLong()
            while (AppleHostClock.nanos() - startedAt < runFor && written < wanted) {
                written += feedRing(ring, 4_096, written.toLong(), pts = null)
                delay(5)
            }

            val framesPlayed = ringConsumed(ring)
            val elapsedMs = (AppleHostClock.nanos() - startedAt) / 1_000_000.0
            val playedMs = framesPlayed * 1_000.0 / format.sampleRate

            assertTrue(framesPlayed > 0, "the device did not ask for any audio")
            assertTrue(
                abs(playedMs - elapsedMs) < 120.0,
                "the device must consume audio at real time: ${playedMs}ms played in ${elapsedMs}ms elapsed",
            )
            assertTrue(sink.callbacks > 0, "the device never entered the C callback")
            assertEquals(
                0,
                sink.zeroFilledCallbacks,
                "no callback should have found the ring missing while the sink was open",
            )
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `latency is reported as a plausible positive figure while playing`() = runBlocking {
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { negotiated -> negotiated.sampleRate / 4 }
        try {
            feedRing(handoff.ring, 4_800, 0, pts = 0)
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
        val handoff = sink.openWithRing(format) { 48_000 }
        val ring = handoff.ring
        try {
            fillRing(ring, 0)
            sink.start()
            delay(60)

            assertTrue(sink.setPaused(true), "CoreAudio can pause without discarding")
            val whilePaused = ringBuffered(ring)
            delay(100)
            assertEquals(
                whilePaused,
                ringBuffered(ring),
                "a paused device consumes nothing, so buffered audio survives the pause",
            )

            assertTrue(sink.setPaused(false))
            delay(60)
            assertTrue(ringBuffered(ring) < whilePaused, "resuming must start consuming again")
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `an unfed device is handed silence rather than stalling`() = runBlocking {
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 4_800 }
        try {
            sink.start()
            delay(120)
            assertTrue(
                ringUnderruns(handoff.ring) > 0,
                "a device with nothing to play must be handed silence, and the underrun counted",
            )
            // And the silence came from the ring, not from a second fill in the sink: register item
            // B1-19 collapsed those two into one, and a zero-filled callback would mean the ring was
            // gone rather than empty.
            assertEquals(0, sink.zeroFilledCallbacks, "an empty ring is not a missing ring")
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `the anchor the device publishes is in the near future on the engine clock`() = runBlocking {
        // This is the assertion the whole design rests on. If the engine's clock and CoreAudio's host
        // time used different bases, the offset measured here would be enormous instead of a few
        // milliseconds, and audio and video would sit at a fixed offset no correction could find.
        //
        // It is now measured from what the C ring published rather than from a Kotlin callback's own
        // argument, which is a stronger statement about the same number: it is the value the media clock
        // will actually be anchored to.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 48_000 }
        val ring = handoff.ring
        var worst = 0L
        var readings = 0
        try {
            fillRing(ring, 0)
            sink.start()
            repeat(25) {
                delay(10)
                val anchor = ringAnchor(ring)
                if (anchor.valid) {
                    val offset = anchor.audibleAtNanos - AppleHostClock.nanos()
                    if (abs(offset) > abs(worst)) worst = offset
                    readings++
                }
            }
        } finally {
            sink.stop()
            sink.close()
        }

        assertTrue(readings > 0, "the device never published an anchor")
        val worstMs = worst / 1_000_000.0
        assertTrue(
            worstMs > -5.0 && worstMs < 500.0,
            "the anchor must sit slightly ahead of now on the engine's clock; worst offset was $worstMs ms " +
                "over $readings readings",
        )
    }

    @Test
    fun `the callback body stays well inside the device period`() = runBlocking {
        // The worst callback body, measured in C from a mach_absolute_time pair around it. This is the
        // number the supervised device run of plan section 15.2 B1.8 assertion 3 judges, and asserting it
        // here as well means a regression shows up in the ordinary gate rather than only in a run
        // somebody has to remember to make.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 48_000 }
        try {
            fillRing(handoff.ring, 0)
            sink.start()
            delay(250)
            val worst = sink.worstCallbackNanos
            val period = 512L * 1_000_000_000L / format.sampleRate
            assertTrue(sink.callbacks > 1, "one callback proves nothing; the device made ${sink.callbacks}")
            assertTrue(worst > 0, "the callback span was never measured over ${sink.callbacks} callbacks")
            assertTrue(
                worst < period / 2,
                "the worst callback body was $worst ns, over half the ${period} ns period at 512 frames",
            )
        } finally {
            sink.stop()
            sink.close()
        }
    }

    @Test
    fun `a failed open hands back everything it created`() = runBlocking {
        // A negative sample rate is not a format any device takes, and the device refuses it after the
        // audio unit instance exists. That window between the instance and the end of open is exactly
        // where things used to be left behind, and it is now inside C: `kprt_sink_create` disposes what
        // it made and reports which step refused.
        val sink = CoreAudioSink()
        val impossible = AudioFormat(sampleRate = -1, channels = 2, sampleFormat = SampleFormat.F32)

        val failure = assertFailsWith<IllegalStateException> {
            sink.openWithRing(impossible) { 4_800 }
        }
        assertTrue(
            failure.message?.contains("stream format") == true,
            "the message must name the step that refused: ${failure.message}",
        )
        assertEquals(0, sink.retainedResources(), "a failed open must leave the sink owning nothing")

        // The sink still opens, which is the observable half of the same claim: nothing was left half
        // open behind the failure.
        sink.openWithRing(format) { 4_800 }
        assertEquals(
            3,
            sink.retainedResources(),
            "an open sink owns its C sink handle, the ring behind it and the negotiated format",
        )
        sink.close()
        assertEquals(0, sink.retainedResources(), "close must let go of all three")
    }

    @Test
    fun `opening through the Kotlin callback entry point is refused loudly`() = runBlocking {
        // A device whose C callback ignored the lambda it was handed would play correctly while the
        // caller believed its callback was being called. Silent disagreement is worse than a loud
        // failure, so the entry point that cannot be honoured says so.
        val sink = CoreAudioSink()
        val failure = assertFailsWith<UnsupportedOperationException> {
            sink.open(format, AudioRenderCallback { _, _, _ -> 0 })
        }
        val message = failure.message ?: ""
        assertTrue(message.contains("openWithRing"), "the message must name the entry point to use: $message")
        assertTrue(message.contains("C"), "the message must say why: $message")
        assertEquals(0, sink.retainedResources(), "a refused open must create nothing")
    }

    @OptIn(ExperimentalNativeApi::class, NativeRuntimeApi::class)
    @Test
    fun `a sink whose open failed is not pinned by a leaked reference`() {
        // This case used to guard a specific defect: the Kotlin callback needed a `StableRef` to reach
        // the sink from C, and a `StableRef` the failed open never disposed pinned the sink for the life
        // of the process. There is no `StableRef` anywhere in this sink now, because the callback is C
        // and its `ref` is a plain struct pointer, so the defect cannot recur in the form it had.
        //
        // The case is kept rather than deleted because it still asserts something true and checkable that
        // no field can show: after a failed open, nothing anywhere holds this object, so it is
        // collectable. A future registration, cache or global list that pinned it would fail here.
        val abandoned = openFailingSinkWeakly()

        GC.collect()

        assertNull(
            abandoned.value,
            "nothing may hold a sink whose open failed, and only a collection can prove it",
        )
    }

    /** Fails an open and keeps only a weak reference, so nothing this test holds can pin the sink. */
    @OptIn(ExperimentalNativeApi::class)
    private fun openFailingSinkWeakly(): WeakReference<CoreAudioSink> {
        val sink = CoreAudioSink()
        assertFailsWith<IllegalStateException> {
            runBlocking {
                sink.openWithRing(
                    AudioFormat(sampleRate = -1, channels = 2, sampleFormat = SampleFormat.F32),
                ) { 4_800 }
            }
        }
        return WeakReference(sink)
    }

    @Test
    fun `a clock on another time base is refused`() {
        // The sink converts CoreAudio host times through AppleHostClock. An engine measuring time from
        // anywhere else would disagree with the device by a constant nothing could correct, so the sink
        // refuses instead of half honouring the clock it was given.
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
