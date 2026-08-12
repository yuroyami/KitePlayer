package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fake side of the [AudioTrackDriver] seam. Every android.media behaviour the sink relies on
 * is scriptable here, and the two invariants the production ordering must never break are
 * RECORDED rather than asserted, so a test chooses loud failure: any driver entry after
 * [release] lands in [postReleaseCalls], and a blocking write is unblocked only by [pause] or
 * [stop], exactly like the platform.
 */
private class FakeAudioTrackDriver(
    override val bufferSizeInFrames: Int = 2048,
) : AudioTrackDriver {

    val calls = mutableListOf<String>()
    val postReleaseCalls = mutableListOf<String>()
    var released = false
        private set

    /** Floats actually handed over, in write order, for tail-silence assertions. */
    val writtenFloats = mutableListOf<Float>()

    /** Next write results; empty means "write everything you were given". */
    val writeResults = ArrayDeque<Int>()

    /** When set, write blocks until pause/stop; models WRITE_BLOCKING against a full buffer. */
    @Volatile var blockWrites = false
    private val writeGate = Object()
    @Volatile private var interrupted = false
    val writeEntered = CountDownLatch(1)

    var timestampAnswer: DriverTimestamp? = null
    var headAnswer = 0

    override fun onWriterThreadStart() { record("threadStart") }

    private fun record(name: String) {
        synchronized(calls) {
            if (released) postReleaseCalls += name
            calls += name
        }
    }

    override fun play() = record("play")
    override fun pause() {
        record("pause")
        synchronized(writeGate) { interrupted = true; writeGate.notifyAll() }
    }
    override fun stop() {
        record("stop")
        synchronized(writeGate) { interrupted = true; writeGate.notifyAll() }
    }
    override fun flush() = record("flush")
    override fun release() {
        record("release")
        released = true
    }

    override fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int {
        record("write")
        writeEntered.countDown()
        if (blockWrites) {
            synchronized(writeGate) {
                while (!interrupted) writeGate.wait()
            }
            return 0 /* the platform returns what it wrote before the interrupt; zero is legal */
        }
        val n = synchronized(writeResults) {
            if (writeResults.isEmpty()) sizeFloats else writeResults.removeFirst()
        }
        val take = n.coerceAtMost(sizeFloats)
        if (take > 0) {
            synchronized(writtenFloats) {
                for (i in 0 until take) writtenFloats += source[offsetFloats + i]
            }
        }
        return n
    }

    override fun timestamp(): DriverTimestamp? = timestampAnswer
    override fun playbackHeadPosition(): Int = headAnswer
}

private class FixedClock(var now: Long = 0L) : MonotonicClock {
    override fun nanos(): Long = now
}

private fun sink(driver: FakeAudioTrackDriver, clock: MonotonicClock = FixedClock()) =
    AudioTrackSink({ driver }, clock)

private val stereo48k = AudioFormat(48_000, 2, SampleFormat.F32)

/** A callback returning full blocks of a marker value, counting invocations. */
private class FullBlockCallback(private val marker: Float = 0.5f) : AudioRenderCallback {
    val invocations = AtomicInteger()
    val deadlines = mutableListOf<Long>()
    val buffers = mutableListOf<AudioSinkBuffer>()
    @Volatile var framesSeen = 0
    override fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
        invocations.incrementAndGet()
        synchronized(deadlines) { deadlines += deadlineNanos }
        synchronized(buffers) { buffers += destination }
        framesSeen = frames
        val block = FloatArray(frames * destination.format.channels) { marker }
        destination.writeInterleaved(block, 0, 0, frames)
        return frames
    }
}

class AudioTrackSinkTest {

    @Test
    fun `open negotiates mono or stereo and fails invalid requests before device creation`() = runBlocking {
        var factoryCalls = 0
        val s = AudioTrackSink({ factoryCalls++; FakeAudioTrackDriver() }, FixedClock())
        assertFailsWith<IllegalArgumentException> {
            s.open(AudioFormat(0, 2, SampleFormat.F32)) { _, _, _ -> 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            s.open(AudioFormat(48_000, 0, SampleFormat.F32)) { _, _, _ -> 0 }
        }
        assertEquals(0, factoryCalls, "an invalid request must fail BEFORE device creation")
        val mono = s.open(AudioFormat(48_000, 1, SampleFormat.F32)) { _, _, _ -> 0 }
        assertEquals(1, mono.channels)
        assertEquals(48_000, mono.sampleRate)
        assertEquals(SampleFormat.F32, mono.sampleFormat)
        s.close()
        val s2 = sink(FakeAudioTrackDriver())
        val surround = s2.open(AudioFormat(44_100, 6, SampleFormat.F32)) { _, _, _ -> 0 }
        assertEquals(2, surround.channels, "anything beyond mono is negotiated to stereo; downmix is the engine's")
        s2.close()
    }

    @Test
    fun `failed open releases the partially created driver and leaves no writer`() = runBlocking {
        val driver = FakeAudioTrackDriver(bufferSizeInFrames = 0)
        val s = sink(driver)
        assertFailsWith<IllegalStateException> { s.open(stereo48k) { _, _, _ -> 0 } }
        assertTrue(driver.released, "the partial driver must be released")
        s.close()
    }

    @Test
    fun `callback buffer and block size are preallocated and identical across ten thousand loops`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val s = sink(driver)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        while (callback.invocations.get() < 10_000) Thread.sleep(1)
        s.stop()
        assertEquals(minOf(2048, 512), callback.framesSeen, "block is exactly min(deviceBufferFrames, 512)")
        val distinct = synchronized(callback.buffers) { callback.buffers.toSet() }
        assertEquals(1, distinct.size, "one preallocated buffer adapter, never a per-loop allocation")
        s.close()
    }

    @Test
    fun `a short callback return gets its tail silenced by the sink`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val s = sink(driver)
        val stopAfterFirst = CountDownLatch(1)
        s.open(stereo48k) { destination, frames, _ ->
            val half = frames / 2
            val block = FloatArray(half * 2) { 1f }
            destination.writeInterleaved(block, 0, 0, half)
            stopAfterFirst.countDown()
            half
        }
        s.start()
        assertTrue(stopAfterFirst.await(5, TimeUnit.SECONDS))
        s.stop()
        val floats = synchronized(driver.writtenFloats) { driver.writtenFloats.toList() }
        assertTrue(floats.size >= 512 * 2, "the FULL block reaches the device, tail included")
        val firstBlock = floats.take(512 * 2)
        assertTrue(firstBlock.take(256 * 2).all { it == 1f }, "the written half is the callback's")
        assertTrue(firstBlock.drop(256 * 2).all { it == 0f }, "the tail is silence, written by the sink")
        s.close()
    }

    @Test
    fun `partial writes loop until the block is fully submitted and count it once`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val totalFloats = 512 * 2
        driver.writeResults.addAll(listOf(100, 300, totalFloats - 400))
        val s = sink(driver)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        while (callback.invocations.get() < 2) Thread.sleep(1)
        s.stop()
        val floats = synchronized(driver.writtenFloats) { driver.writtenFloats.size }
        assertTrue(floats >= totalFloats, "three partial writes must still deliver the whole block")
        s.close()
    }

    @Test
    fun `a zero write with the writer live is a device failure, not a busy loop`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        driver.writeResults.add(0)
        val s = sink(driver)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        val event = AtomicReference<AudioSinkEvent?>()
        /* Subscribe BEFORE starting: the flow has no replay, and the failure fires on the
         * writer's first block. */
        val collector = async { s.events.first() }
        yield() /* single-threaded runBlocking: let the collector actually subscribe first */
        s.start()
        withTimeout(5_000) {
            event.set(collector.await())
        }
        assertTrue(event.get() is AudioSinkEvent.DeviceLost, "zero write must surface as DeviceLost")
        val writesAfterFailure = synchronized(driver.calls) { driver.calls.count { it == "write" } }
        assertTrue(writesAfterFailure <= 2, "the writer must exit, never spin on a dead device")
        s.close()
    }

    @Test
    fun `the deadline prefers a valid timestamp and uses the exact formula`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        driver.timestampAnswer = DriverTimestamp(framePosition = 0, nanoTime = 1_000_000L)
        val s = sink(driver)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        while (callback.invocations.get() < 1) Thread.sleep(1)
        s.stop()
        val first = synchronized(callback.deadlines) { callback.deadlines.first() }
        val expected = AudioTrackSink.timestampDeadline(
            timestampNanos = 1_000_000L,
            timestampFrames = 0,
            submittedFrames = 0,
            requestedFrames = 512,
            sampleRate = 48_000,
        )
        assertEquals(expected, first)
        assertEquals("timestamp", s.observedDeadlineSource)
        s.close()
    }

    @Test
    fun `a timestamp ahead of submitted data is rejected and the head fallback answers`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        driver.timestampAnswer = DriverTimestamp(framePosition = 999_999, nanoTime = 5L)
        driver.headAnswer = 0
        val clock = FixedClock(7_000_000L)
        val s = sink(driver, clock)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        while (callback.invocations.get() < 1) Thread.sleep(1)
        s.stop()
        val first = synchronized(callback.deadlines) { callback.deadlines.first() }
        val expected = 7_000_000L + AudioTrackSink.framesToNanos(512, 48_000)
        assertEquals(expected, first, "the fallback is queued frames on the injected clock")
        assertEquals("head", s.observedDeadlineSource)
        s.close()
    }

    @Test
    fun `latency clamps at zero when the reported position passes submitted data`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val s = sink(driver)
        s.open(stereo48k, FullBlockCallback())
        driver.headAnswer = 10_000 /* nothing submitted yet, played is ahead: clamp */
        assertEquals(0, s.latencyNanos())
        s.close()
    }

    @Test
    fun `pause joins without flushing and resume starts one new writer`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val s = sink(driver)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        while (callback.invocations.get() < 1) Thread.sleep(1)
        assertTrue(s.setPaused(true))
        val flushesAtPause = synchronized(driver.calls) { driver.calls.count { it == "flush" } }
        assertEquals(0, flushesAtPause, "pause discards nothing")
        val before = callback.invocations.get()
        assertTrue(s.setPaused(false))
        while (callback.invocations.get() <= before) Thread.sleep(1)
        s.stop()
        s.close()
    }

    @Test
    fun `stop signals, unblocks, joins, then flushes and resets the submitted count`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        driver.blockWrites = true
        val s = sink(driver)
        s.open(stereo48k, FullBlockCallback())
        s.start()
        assertTrue(driver.writeEntered.await(5, TimeUnit.SECONDS), "the writer must be inside a blocking write")
        s.stop()
        synchronized(driver.calls) {
            val pause = driver.calls.indexOf("pause")
            val flush = driver.calls.indexOf("flush")
            assertTrue(pause in 0 until flush, "flush must come after the unblock+join, never before")
        }
        assertEquals(0, s.latencyNanos(), "stop resets the submitted count")
        s.close()
    }

    @Test
    fun `drain exits on the first short return and never flushes`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        val s = sink(driver)
        val served = AtomicLong()
        s.open(stereo48k) { destination, frames, _ ->
            val n = served.getAndIncrement()
            if (n < 2) {
                destination.writeSilence(0, frames); frames
            } else {
                destination.writeSilence(0, frames); 0 /* dry: end of media */
            }
        }
        s.start()
        while (served.get() < 3) Thread.sleep(1)
        driver.headAnswer = Int.MAX_VALUE /* everything submitted has played, whatever the count */
        s.drain()
        val flushes = synchronized(driver.calls) { driver.calls.count { it == "flush" } }
        assertEquals(0, flushes, "drain plays out; it never discards")
        assertTrue(synchronized(driver.calls) { driver.calls.count { it == "stop" } } >= 1)
        s.close()
    }

    @Test
    fun `close performs the stop ordering, releases after the join, and is idempotent`() = runBlocking {
        val driver = FakeAudioTrackDriver()
        driver.blockWrites = true
        val s = sink(driver)
        s.open(stereo48k, FullBlockCallback())
        s.start()
        assertTrue(driver.writeEntered.await(5, TimeUnit.SECONDS))
        s.close()
        s.close() /* idempotent */
        assertTrue(driver.released)
        assertEquals(
            emptyList(),
            synchronized(driver.calls) { driver.postReleaseCalls.toList() },
            "no driver call may land after release; the join precedes it",
        )
        assertEquals(1, synchronized(driver.calls) { driver.calls.count { it == "release" } })
    }

    @Test
    fun `negative control - releasing before the join is caught by the fake as a post-release write`() {
        val driver = FakeAudioTrackDriver()
        val writing = CountDownLatch(1)
        val t = Thread {
            writing.countDown()
            driver.write(FloatArray(64), 0, 64)
        }
        driver.blockWrites = false
        driver.writeResults.add(64)
        /* Deliberately violate the ordering the sink guarantees: release first, write after. */
        driver.release()
        t.start()
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        t.join(5_000)
        if (driver.postReleaseCalls.isEmpty()) {
            fail("the fake failed to record a post-release write; the ordering tests prove nothing")
        }
    }
}
