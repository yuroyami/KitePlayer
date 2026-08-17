@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real-time path, as far as Kotlin can still see it.
 *
 * ### What this file used to do, and why it cannot any more
 *
 * Before B1.8 the sink's real-time body was a Kotlin method, `fillDeviceBuffer(callback, timeStamp,
 * frames, bufferList)`, and this file called it directly over memory the test allocated. That made three
 * awkward cases into plain assertions: a callback that filled less than it was handed, no callback at
 * all, and a timestamp whose host time CoreAudio marked meaningless.
 *
 * That method is gone, and its absence is the point of the sub-phase. The body is now `kprt_render_into`
 * in `kiteplayer-rt/native/src/kite_rt_render.c`, reached from a `static` C function that
 * `include/kite_rt.h` does not name, so the cinterop bindings do not contain it and Kotlin has no way to
 * call it. Register item B1-17: a callback Kotlin can reach is a callback the garbage collector has to
 * stop.
 *
 * ### Where the three cases went, named rather than implied
 *
 * All three moved into C, into `kiteplayer-rt/native/tests/test_sink_callback.c`, which drives the same
 * body five million times with no device:
 *
 *  - "a device buffer with no callback to fill it comes back silent" is now "a callback that finds no
 *    ring zeroes the whole buffer and counts it". The state is reachable in production only during
 *    teardown, and it is the only silence case left outside `kprt_ring_render` (register item B1-19).
 *  - "a short render has its remainder zero filled" is now "a short read is exact zeroes after the real
 *    frames and counts one underrun", asserted on the exact bytes.
 *  - "an invalid host time falls back to the engine clock" is now "a host time the device did not flag
 *    valid is counted as estimated". The fallback clock read is in the C callback, and
 *    `test_sink_timebase.c` proves that clock is the same one `AudioGetCurrentHostTime` reads.
 *
 * The fourth case, "a render longer than the device asked for cannot leave the buffer", has no
 * equivalent because it has no subject: it guarded a Kotlin wrapper that clamped a caller's frame count,
 * and `kprt_ring_render` writes exactly the frame count it was given. `test_ring_basic.c` covers the
 * boundary.
 *
 * ### What is left here
 *
 * The observations that need a running native output unit and can only be made from outside it: that the
 * unit really enters the C callback, that the sink's counters describe what happened, and that teardown
 * while it is running is safe in the order the C code claims. macosArm64 supplies the hardware result;
 * iosSimulatorArm64 supplies a separate RemoteIO simulator result, never a physical-iPhone claim.
 */
class CoreAudioSinkRealTimeTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun `an idle open sink has made no callback and holds a ring`() = runBlocking {
        // Opened but never started, so nothing has pulled. The ring exists from the moment open returns,
        // which is what makes it safe for the engine to start feeding before it starts the device.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 4_800 }
        try {
            assertEquals(0, sink.callbacks, "an unstarted device must not have called anything")
            assertEquals(0, sink.zeroFilledCallbacks)
            assertEquals(4_800, ringBuffered(handoff.ringPointer()) + ringFree(handoff.ringPointer()), "the ring holds what was asked for")
            assertEquals(48_000, ringSampleRate(handoff.ringPointer()))
            assertEquals(0L, ringConsumed(handoff.ringPointer()))
            assertEquals(0L, ringUnderruns(handoff.ringPointer()))
            assertEquals(3, sink.retainedResources())
        } finally {
            sink.close()
        }
    }

    @Test
    fun `the device enters the C callback and dates what it played`() = runBlocking {
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 24_000 }
        val ring = handoff.ringPointer()
        try {
            fillRing(ring, 0)
            sink.start()
            delay(200)
            sink.stop()

            val consumed = ringConsumed(ring)
            val anchor = ringAnchor(ring)
            assertTrue(sink.callbacks > 0, "the device never entered the callback")
            assertTrue(consumed > 0, "the device consumed nothing")
            assertTrue(anchor.valid, "the callback published no anchor")
            assertTrue(!anchor.fromCache, "the anchor reader should not have needed its cache: it is unloaded here")

            // The published media time is the boundary after the last frame handed over, so it must equal
            // the duration of everything consumed, to the microsecond, through the same exact rescale the
            // ring uses. This is the property register item B1-24's one-machine caveat cannot weaken: it
            // is arithmetic, not timing.
            assertEquals(
                framesToMicros(consumed, 48_000),
                anchor.ptsUs,
                "the anchor must date exactly the frames the device took: consumed=$consumed",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `every callback on this machine carried a valid host time`() = runBlocking {
        // Not a promise about all hardware, and not asserted as one: CoreAudio is allowed to hand over a
        // timestamp with no valid host time, which is why the C callback has a fallback and counts it.
        // What this records is that on this device, over a few hundred callbacks, it never did. The
        // counter itself is asserted in test_sink_callback.c, where the flag can be forced.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 24_000 }
        try {
            fillRing(handoff.ringPointer(), 0)
            sink.start()
            delay(200)
            sink.stop()
            assertTrue(sink.callbacks > 0)
            assertEquals(
                0,
                sink.estimatedAnchors,
                "this device flagged an invalid host time on ${sink.estimatedAnchors} of ${sink.callbacks} " +
                    "callbacks, so the fallback path is live here and the anchor is an estimate that often",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `closing while the device is running is safe and stops the callbacks`() = runBlocking {
        // The ordering claim of `kprt_sink_destroy`: stop, uninitialise, dispose, and only then let go of
        // the ring. If it were the other way round the callback could read a freed ring, which is the
        // classic use-after-free in an audio teardown. This case cannot prove the order by inspection; it
        // does what a caller would do at the worst possible moment and asserts the process survives it and
        // the sink ends up owning nothing.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 24_000 }
        fillRing(handoff.ringPointer(), 0)
        sink.start()
        delay(60)
        val duringPlayback = sink.callbacks
        assertTrue(duringPlayback > 0, "the device was not running yet, so this closes nothing interesting")

        sink.close()

        assertEquals(0, sink.retainedResources(), "close must let go of both handles and the format")
        // Reading the stats of a closed sink answers zeroes rather than throwing or crashing, because the
        // C reader tolerates a NULL sink. A diagnostic call after close must not be a fault.
        assertEquals(0, sink.callbacks)
        assertEquals(0, sink.worstCallbackNanos)
        // And it is idempotent, which a session owner that closes twice depends on.
        sink.close()
        assertEquals(0, sink.retainedResources())
    }

    @Test
    fun `a sink can be opened again after it is closed`() = runBlocking {
        val sink = CoreAudioSink()
        sink.openWithRing(format) { 4_800 }
        sink.close()
        val second = sink.openWithRing(format) { 4_800 }
        try {
            assertEquals(48_000, second.format.sampleRate)
            assertEquals(3, sink.retainedResources())
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a second open on a live sink is refused`() = runBlocking {
        val sink = CoreAudioSink()
        sink.openWithRing(format) { 4_800 }
        try {
            val failure = assertFailsWith<IllegalStateException> { sink.openWithRing(format) { 4_800 } }
            assertTrue(
                failure.message?.contains("already open") == true,
                "the message must say what is wrong: ${failure.message}",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `the device period the sink reports is what the ring is sized against`() = runBlocking {
        val sink = CoreAudioSink()
        var askedWith = 0
        val handoff = sink.openWithRing(format) { negotiated ->
            askedWith = negotiated.sampleRate
            sink.deviceBufferFrames * 8
        }
        try {
            assertEquals(48_000, askedWith, "the capacity function must be given the format the device took")
            assertEquals(512, sink.deviceBufferFrames)
            // The ring really was created at that capacity, which is the only way to know the number the
            // engine computed was the number C used.
            assertEquals(512 * 8, ringBuffered(handoff.ringPointer()) + ringFree(handoff.ringPointer()))
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a mono request is accepted and a nine channel request is clamped`() = runBlocking {
        // The C negotiation clamps to what the target's Apple output unit takes here, and reports what it
        // settled on rather than what it was asked for. A sink that silently kept the request would have
        // the engine resample into a layout the device never agreed to.
        val mono = CoreAudioSink()
        val monoOpened = mono.openWithRing(
            AudioFormat(sampleRate = 44_100, channels = 1, sampleFormat = SampleFormat.F32),
        ) { 4_410 }
        try {
            assertEquals(1, monoOpened.format.channels)
            assertEquals(44_100, monoOpened.format.sampleRate)
        } finally {
            mono.close()
        }

        val many = CoreAudioSink()
        val manyOpened = many.openWithRing(
            AudioFormat(sampleRate = 48_000, channels = 9, sampleFormat = SampleFormat.F32),
        ) { 4_800 }
        try {
            assertEquals(2, manyOpened.format.channels, "nine channels must come back clamped, not accepted")
        } finally {
            many.close()
        }
    }

    @Test
    fun `the anchor advances with the device rather than with the clock`() = runBlocking {
        // Two readings a fixed wall time apart. The media time between them must grow by about that wall
        // time, because the device consumes at real speed; a clock derived from submissions instead would
        // jump ahead as fast as the feeder could write.
        val sink = CoreAudioSink()
        val handoff = sink.openWithRing(format) { 48_000 }
        val ring = handoff.ringPointer()
        try {
            fillRing(ring, 0)
            sink.start()
            delay(80)
            val first = ringAnchor(ring)
            delay(200)
            val second = ringAnchor(ring)
            sink.stop()

            assertTrue(first.valid && second.valid, "both readings must be real anchors")
            val advancedUs = second.ptsUs - first.ptsUs
            assertTrue(
                abs(advancedUs - 200_000) < 60_000,
                "200 ms of wall time must advance the media clock by about 200 ms, was $advancedUs us",
            )
        } finally {
            sink.close()
        }
    }
}
