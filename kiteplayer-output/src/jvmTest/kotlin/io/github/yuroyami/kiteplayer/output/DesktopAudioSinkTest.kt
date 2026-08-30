package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fake side of the [SourceDataLineDriver] seam. Every `javax.sound.sampled` behaviour the
 * sink relies on is scriptable here, and the two invariants the production ordering must never
 * break are RECORDED rather than asserted, so a test chooses loud failure: any driver entry after
 * [close] lands in [postCloseCalls], and a blocking write is unblocked only by [stop], [flush] or
 * [close], exactly like the line.
 */
private class FakeSourceDataLine(accepted: AudioFormat) : SourceDataLineDriver {

    private val frameBytes = accepted.channels * WIRE_BYTES_PER_SAMPLE

    val calls = mutableListOf<String>()
    val postCloseCalls = mutableListOf<String>()
    var lineClosed = false
        private set

    /** Bytes actually handed over, in write order, for tail-silence and ordering assertions. */
    val writtenBytes = mutableListOf<Byte>()

    /** Next write results in BYTES; empty means "write everything you were given". */
    val writeResults = ArrayDeque<Int>()

    /** When set, write blocks until stop/flush/close; models the blocking write on a full line. */
    @Volatile var blockWrites = false

    /** When >= 0, writes from that call index on block like [blockWrites]. */
    @Volatile var blockFromWriteCall = -1

    /** When > 0, an interrupted blocked write returns this short POSITIVE count instead of 0. */
    @Volatile var interruptWriteResult = 0

    /** When set, [open] throws the way a line another application owns does. */
    @Volatile var openThrows = false

    /** What [open] should report, so a refused open can be driven. */
    var bufferSizeAnswer = 2048 * 4
    var framePositionAnswer = 0L
    var availableAnswer = 0

    private var writeCalls = 0
    private val writeGate = Object()
    @Volatile private var interrupted = false
    val writeEntered = CountDownLatch(1)

    override fun open() {
        record("open")
        if (openThrows) throw IllegalStateException("line unavailable")
    }

    override val bufferSizeBytes: Int get() = bufferSizeAnswer

    override fun onWriterThreadStart() = record("threadStart")

    private fun record(name: String) {
        synchronized(calls) {
            if (lineClosed) postCloseCalls += name
            calls += name
        }
    }

    override fun start() = record("start")

    override fun stop() {
        record("stop")
        synchronized(writeGate) { interrupted = true; writeGate.notifyAll() }
    }

    override fun drain() = record("drain")

    override fun flush() {
        record("flush")
        synchronized(writeGate) { interrupted = true; writeGate.notifyAll() }
    }

    override fun close() {
        record("close")
        lineClosed = true
        synchronized(writeGate) { interrupted = true; writeGate.notifyAll() }
    }

    override fun write(source: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
        record("write")
        if (sizeBytes % frameBytes != 0) {
            fail("the line was handed $sizeBytes bytes, not a whole number of $frameBytes-byte frames")
        }
        writeEntered.countDown()
        val call = writeCalls++
        if (blockWrites || (blockFromWriteCall in 0..call)) {
            synchronized(writeGate) {
                while (!interrupted) writeGate.wait()
            }
            /* The line returns what it wrote before the interrupt: zero is legal, and so is a
             * short POSITIVE count, which is the audit F-AUD1 shape. */
            if (interruptWriteResult > 0) {
                val take = interruptWriteResult.coerceAtMost(sizeBytes)
                take(source, offsetBytes, take)
                return take
            }
            return 0
        }
        val n = synchronized(writeResults) {
            if (writeResults.isEmpty()) sizeBytes else writeResults.removeFirst()
        }
        val took = n.coerceAtMost(sizeBytes)
        if (took > 0) take(source, offsetBytes, took)
        return n
    }

    private fun take(source: ByteArray, offsetBytes: Int, count: Int) {
        synchronized(writtenBytes) {
            for (i in 0 until count) writtenBytes += source[offsetBytes + i]
        }
    }

    override fun longFramePosition(): Long = framePositionAnswer

    override fun available(): Int = availableAnswer

    /** The k-th signed 16-bit little-endian sample the line was handed. */
    fun shortAt(index: Int): Int = synchronized(writtenBytes) {
        val low = writtenBytes[index * 2].toInt() and 0xFF
        val high = writtenBytes[index * 2 + 1].toInt()
        (high shl 8) or low
    }

    fun countOf(name: String): Int = synchronized(calls) { calls.count { it == name } }
}

private class FixedClock(var now: Long = 0L) : MonotonicClock {
    override fun nanos(): Long = now
}

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

/**
 * Every wait in this suite is BOUNDED, and that is not tidiness. A neutered fix usually shows up
 * as "the thing never happens", and an unbounded wait turns that into a hang the suite reports as
 * nothing at all. The rule is the backstop for a hang inside the sink itself.
 */
private fun awaitUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("timed out after ${timeoutMs}ms waiting for $what")
        Thread.sleep(1)
    }
}

class DesktopAudioSinkTest {

    @get:Rule
    val hangGuard: Timeout = Timeout.seconds(60)

    private val drivers = mutableListOf<FakeSourceDataLine>()
    private var opens = 0

    private fun sink(clock: MonotonicClock = FixedClock()) = DesktopAudioSink(
        { accepted -> opens++; FakeSourceDataLine(accepted).also { synchronized(drivers) { drivers += it } } },
        clock,
    )

    private val driver: FakeSourceDataLine get() = synchronized(drivers) { drivers.first() }

    // ── open ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `open negotiates mono or stereo and fails invalid requests before line creation`() = runBlocking {
        val s = sink()
        assertFailsWith<IllegalArgumentException> {
            s.open(AudioFormat(0, 2, SampleFormat.F32)) { _, _, _ -> 0 }
        }
        assertFailsWith<IllegalArgumentException> {
            s.open(AudioFormat(48_000, 0, SampleFormat.F32)) { _, _, _ -> 0 }
        }
        assertEquals(0, opens, "an invalid request must fail BEFORE the line is created")
        val mono = s.open(AudioFormat(48_000, 1, SampleFormat.F32)) { _, _, _ -> 0 }
        assertEquals(1, mono.channels)
        assertEquals(48_000, mono.sampleRate)
        assertEquals(SampleFormat.F32, mono.sampleFormat, "the engine always writes floats; S16 is the wire only")
        s.close()
    }

    @Test
    fun `channel counts the mixers have no line for fall to stereo`() = runBlocking {
        for ((requested, expected) in listOf(1 to 1, 2 to 2, 3 to 2, 6 to 2, 8 to 2)) {
            val s = sink()
            val accepted = s.open(AudioFormat(48_000, requested, SampleFormat.F32), FullBlockCallback())
            assertEquals(expected, accepted.channels, "requested $requested channels")
            s.close()
        }
    }

    @Test
    fun `a refused open closes the partially created line and leaves no writer`() = runBlocking {
        val s = DesktopAudioSink(
            { accepted -> FakeSourceDataLine(accepted).also { it.bufferSizeAnswer = 0; drivers += it } },
            FixedClock(),
        )
        assertFailsWith<IllegalStateException> { s.open(stereo48k) { _, _, _ -> 0 } }
        assertTrue(driver.lineClosed, "the partial line must be closed")
        assertEquals(0, driver.countOf("threadStart"), "a refused open must leave no writer")
        s.close()
    }

    // The other failure shape of an open: javax.sound.sampled throws LineUnavailableException when
    // another application owns the device, and the half-built line must not be left behind.
    @Test
    fun `an open that throws still closes the line it created`() = runBlocking {
        val s = DesktopAudioSink(
            { accepted -> FakeSourceDataLine(accepted).also { it.openThrows = true; drivers += it } },
            FixedClock(),
        )
        assertFailsWith<IllegalStateException> { s.open(stereo48k) { _, _, _ -> 0 } }
        assertTrue(driver.lineClosed, "an open that throws must still close the line it created")
        s.close()
    }

    @Test
    fun `deviceBufferFrames is the line's own byte buffer in frames`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        assertEquals(2048, s.deviceBufferFrames, "2048 stereo S16 frames is 8192 bytes")
        s.close()
    }

    // ── the writer machine ──────────────────────────────────────────────────────────────────

    @Test
    fun `callback buffer and block size are preallocated and identical across ten thousand loops`() = runBlocking {
        val s = sink()
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        awaitUntil("ten thousand render pulls", 60_000) { callback.invocations.get() >= 10_000 }
        s.stop()
        assertEquals(512, callback.framesSeen, "block is exactly min(deviceBufferFrames, 512)")
        val distinct = synchronized(callback.buffers) { callback.buffers.toSet() }
        assertEquals(1, distinct.size, "one preallocated buffer adapter, never a per-loop allocation")
        s.close()
    }

    // The block buffer is preallocated and REUSED, so a short return leaves the previous block's
    // samples in the tail. The first block here is deliberately full and loud, so the tail the
    // second block does not write is dirty; nothing above the sink zeroes it.
    @Test
    fun `a short callback return gets its tail silenced over the previous block`() = runBlocking {
        val s = sink()
        val calls = AtomicInteger()
        s.open(stereo48k) { destination, frames, _ ->
            if (calls.getAndIncrement() == 0) {
                destination.writeInterleaved(FloatArray(frames * 2) { 1f }, 0, 0, frames)
                frames
            } else {
                val half = frames / 2
                destination.writeInterleaved(FloatArray(half * 2) { 0.75f }, 0, 0, half)
                half
            }
        }
        s.start()
        awaitUntil("both blocks on the wire") {
            synchronized(driver.writtenBytes) { driver.writtenBytes.size } >= 2 * 512 * 4
        }
        s.stop()
        for (i in 0 until 512 * 2) {
            assertTrue(driver.shortAt(i) > 30_000, "sample $i: block one is the full loud block")
        }
        for (i in 1024 until 1024 + 256 * 2) {
            assertTrue(driver.shortAt(i) in 20_000..28_000, "sample $i: block two's written half")
        }
        for (i in 1024 + 256 * 2 until 2048) {
            assertEquals(0, driver.shortAt(i), "sample $i: the tail is silence the sink wrote over block one")
        }
        s.close()
    }

    @Test
    fun `partial writes loop until the block is fully submitted`() = runBlocking {
        val s = sink()
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        val total = 512 * 4
        driver.writeResults.addAll(listOf(100, 300, total - 400))
        s.start()
        awaitUntil("two render pulls") { callback.invocations.get() >= 2 }
        s.stop()
        assertTrue(
            synchronized(driver.writtenBytes) { driver.writtenBytes.size } >= total,
            "three partial writes must still deliver the whole block",
        )
        s.close()
    }

    // SOL-A1: the submitted count is what the line ACTUALLY took, never what was offered.
    @Test
    fun `an interrupted block counts only the frames the line actually took`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.writeResults.add(100) /* 100 bytes is 25 stereo S16 frames */
        driver.blockFromWriteCall = 1
        s.start()
        assertTrue(driver.writeEntered.await(10, TimeUnit.SECONDS), "the writer never reached a write")
        awaitUntil("the writer to reach its second write") { driver.countOf("write") >= 2 }
        s.setPaused(true)
        driver.framePositionAnswer = 0
        val expected = DesktopAudioSink.framesToNanos(25, 48_000)
        val wholeBlock = DesktopAudioSink.framesToNanos(512, 48_000)
        assertEquals(
            expected,
            s.latencyNanos(),
            "an interrupted block must count its written part, not the offered $wholeBlock",
        )
        s.close()
    }

    // SOL-A2: one writer, ever.
    @Test
    fun `a duplicate resume never starts a second writer thread`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        s.start()
        assertTrue(driver.writeEntered.await(10, TimeUnit.SECONDS), "the writer never reached a write")
        s.setPaused(false)
        s.setPaused(false)
        Thread.sleep(20)
        assertEquals(1, driver.countOf("threadStart"), "a duplicate resume must not create a second writer")
        s.close()
    }

    // SOL-A2: a dead line marks the machine FAILED, publishes the state BEFORE the event, and
    // the writer exits instead of spinning.
    @Test
    fun `a zero write with the writer live is a device failure, not a busy loop`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.writeResults.add(0)
        /* Subscribe BEFORE starting: the flow has no replay and the failure fires on block one. */
        val lost = async { s.events.first() }
        yield()
        s.start()
        val event = withTimeout(5_000) { lost.await() }
        assertTrue(event is AudioSinkEvent.DeviceLost, "a zero write must surface as DeviceLost")
        assertTrue(driver.countOf("write") <= 2, "the writer must exit, never spin on a dead line")
        s.close()
    }

    // SOL-A2's recovery arm.
    @Test
    fun `after a device failure the next start opens a fresh line and plays again`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.writeResults.add(0)
        val lost = async { s.events.first { it is AudioSinkEvent.DeviceLost } }
        yield()
        s.start()
        withTimeout(5_000) { lost.await() }

        s.start() /* the recovery arm */
        awaitUntil("the recovered line to be written to") {
            synchronized(drivers) { drivers.size } >= 2 && drivers[1].countOf("write") > 0
        }
        assertEquals(2, opens, "recovery must open a fresh line")
        assertTrue(drivers[0].countOf("close") >= 1, "the dead line must be closed")
        assertTrue(drivers[1].countOf("open") == 1, "the fresh line must actually be opened")
        s.close()
    }

    /**
     * The recovery arm's own failure. A device that went away can still be unavailable when the
     * sink reaches for a replacement, and that reopen used to run unguarded: the fresh line leaked
     * and `driver` was left pointing at the dead one this arm had just closed.
     */
    @Test
    fun `a recovery whose reopen is refused leaks nothing and leaves no closed line behind`() = runBlocking {
        var failNextOpen = false
        val s = DesktopAudioSink(
            { accepted ->
                opens++
                FakeSourceDataLine(accepted).also {
                    it.openThrows = failNextOpen
                    synchronized(drivers) { drivers += it }
                }
            },
            FixedClock(),
        )
        s.open(stereo48k, FullBlockCallback())
        driver.writeResults.add(0)
        val lost = async { s.events.first { it is AudioSinkEvent.DeviceLost } }
        yield()
        s.start()
        withTimeout(5_000) { lost.await() }

        failNextOpen = true
        assertFailsWith<IllegalStateException> { s.start() }

        val refused = synchronized(drivers) { drivers[1] }
        assertTrue(
            refused.countOf("close") >= 1,
            "the line whose open was refused must be closed, or it leaks a device handle",
        )
        // And the sink did not keep the corpse: recovery is still owed, so a start with a working
        // device gets a THIRD line rather than reusing the one it closed before the failed reopen.
        failNextOpen = false
        s.start()
        awaitUntil("the second recovery to write") {
            synchronized(drivers) { drivers.size } >= 3 && drivers[2].countOf("write") > 0
        }
        assertTrue(drivers[0].lineClosed, "the original dead line stays closed")
        s.close()
    }

    // Audit F-AUD1: a short POSITIVE return is also how the line hands a write back at an
    // interrupt, and the loop must not re-enter the blocking write past the signal.
    @Test
    fun `a short positive return at the pause signal stops the loop instead of re-entering write`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.blockWrites = true
        driver.interruptWriteResult = 100
        s.start()
        assertTrue(driver.writeEntered.await(10, TimeUnit.SECONDS), "the writer never reached a write")
        s.setPaused(true)
        val writes = driver.countOf("write")
        assertTrue(writes <= 2, "the signalled writer must stop at the interrupted write, not busy the line: $writes")
        driver.framePositionAnswer = 0
        assertEquals(DesktopAudioSink.framesToNanos(25, 48_000), s.latencyNanos(), "and the partial count is honest")
        s.close()
    }

    // Audit F-AUD2: the interrupted block's unwritten tail was already pulled from the ring, so
    // dropping it on resume loses up to a block of decoded audio at every pause.
    @Test
    fun `resume submits the interrupted block's remainder before pulling a new one`() = runBlocking {
        val s = sink()
        /* Block zero is positive, block one is negative, so the byte stream names its own block. */
        val stamped = object : AudioRenderCallback {
            var block = 0
            override fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int {
                val value = if (block++ == 0) 0.5f else -0.5f
                destination.writeInterleaved(FloatArray(frames * 2) { value }, 0, 0, frames)
                return frames
            }
        }
        s.open(stereo48k, stamped)
        driver.writeResults.add(100)
        driver.blockFromWriteCall = 1
        s.start()
        assertTrue(driver.writeEntered.await(10, TimeUnit.SECONDS), "the writer never reached a write")
        awaitUntil("the writer to reach its second write") { driver.countOf("write") >= 2 }
        s.setPaused(true)

        driver.blockFromWriteCall = -1
        s.setPaused(false)
        awaitUntil("block zero's remainder and part of block one") {
            synchronized(driver.writtenBytes) { driver.writtenBytes.size } >= 512 * 4 + 200
        }
        s.setPaused(true)
        assertTrue(driver.shortAt(50) > 0, "sample 50 is block zero")
        assertTrue(driver.shortAt(1023) > 0, "sample 1023 still completes block zero, it is not dropped")
        assertTrue(driver.shortAt(1024) < 0, "block one follows the COMPLETED block zero")
        s.close()
    }

    // ── deadline and position arithmetic ────────────────────────────────────────────────────

    @Test
    fun `the deadline is now plus everything queued plus this block`() = runBlocking {
        val clock = FixedClock(7_000_000L)
        val s = sink(clock)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        driver.framePositionAnswer = 0 /* nothing played yet */
        s.start()
        awaitUntil("two render pulls") { callback.invocations.get() >= 2 }
        s.stop()
        val deadlines = synchronized(callback.deadlines) { callback.deadlines.toList() }
        assertEquals(
            7_000_000L + DesktopAudioSink.framesToNanos(512, 48_000),
            deadlines[0],
            "block one: nothing queued, so its last frame is one block away",
        )
        assertEquals(
            7_000_000L + DesktopAudioSink.framesToNanos(1024, 48_000),
            deadlines[1],
            "block two: block one is still queued, so its last frame is two blocks away",
        )
        s.close()
    }

    @Test
    fun `a played position past the submitted count clamps the queue at zero`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.framePositionAnswer = 10_000 /* nothing submitted yet, the line claims to be ahead */
        assertEquals(0, s.latencyNanos())
        s.close()
    }

    // SOL-A3: getLongFramePosition is already 64 bit, so a position past 2^32 is a REAL position
    // and no wrap extension may touch it. A 32-bit fold would read this line as barely started
    // and report a queue that is not there.
    @Test
    fun `a frame position past thirty two bits is read as is, with no wrap fold`() = runBlocking {
        val s = sink()
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        awaitUntil("the first render pull") { callback.invocations.get() >= 1 }
        s.setPaused(true) /* pause keeps the submitted count, unlike stop */
        driver.framePositionAnswer = 0x2_0000_0000L /* eight billion frames really did play */
        assertEquals(
            0,
            s.latencyNanos(),
            "a 64-bit position must be read as is; folding it at 2^32 invents a queue",
        )
        s.close()
    }

    // SOL-A3's desktop half: the JDK counts the position from open(), so a run that stops and
    // starts again zeroes the submitted count while the line's counter keeps going. The two are
    // re-zeroed together through ONE base, or every deadline after a seek under-reports the queue
    // by the whole device buffer.
    @Test
    fun `after a stop the played count restarts with the submitted count`() = runBlocking {
        val clock = FixedClock(1_000L)
        val s = sink(clock)
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        awaitUntil("the first render pull") { callback.invocations.get() >= 1 }
        driver.framePositionAnswer = 5_000L /* the line played 5000 frames of the old run */
        s.stop()
        val before = synchronized(callback.deadlines) { callback.deadlines.size }

        s.start()
        awaitUntil("two render pulls in the new run") { callback.invocations.get() >= before + 2 }
        s.stop()
        val deadlines = synchronized(callback.deadlines) { callback.deadlines.toList() }
        assertEquals(
            1_000L + DesktopAudioSink.framesToNanos(512, 48_000),
            deadlines[before],
            "the new run's first block has nothing queued ahead of it",
        )
        assertEquals(
            1_000L + DesktopAudioSink.framesToNanos(1024, 48_000),
            deadlines[before + 1],
            "the new run's second block queues behind the first; a stale base hides that queue",
        )
        s.close()
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `pause stops without flushing and resume starts one new writer`() = runBlocking {
        val s = sink()
        val callback = FullBlockCallback()
        s.open(stereo48k, callback)
        s.start()
        awaitUntil("the first render pull") { callback.invocations.get() >= 1 }
        assertTrue(s.setPaused(true))
        assertEquals(0, driver.countOf("flush"), "pause discards nothing")
        val before = callback.invocations.get()
        assertTrue(s.setPaused(false))
        awaitUntil("a render pull after the resume") { callback.invocations.get() > before }
        s.stop()
        s.close()
    }

    @Test
    fun `stop signals, unblocks, joins, then flushes and resets the submitted count`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.blockWrites = true
        s.start()
        assertTrue(driver.writeEntered.await(5, TimeUnit.SECONDS), "the writer must be inside a blocking write")
        s.stop()
        synchronized(driver.calls) {
            val stop = driver.calls.indexOf("stop")
            val flush = driver.calls.indexOf("flush")
            assertTrue(stop in 0 until flush, "flush must come after the unblock and the join, never before")
        }
        assertEquals(0, s.latencyNanos(), "stop resets the submitted count")
        s.close()
    }

    // Audit F-AUD3: drain used to leave the submitted counter stale, so a later latency read
    // invented minutes of pending audio.
    @Test
    fun `drain exits on the first short return, plays the line out and never flushes`() = runBlocking {
        val s = sink()
        val served = AtomicLong()
        s.open(stereo48k) { destination, frames, _ ->
            val n = served.getAndIncrement()
            destination.writeSilence(0, frames)
            if (n < 2) frames else 0
        }
        s.start()
        awaitUntil("the callback to run dry") { served.get() >= 3 }
        s.drain()
        assertEquals(0, driver.countOf("flush"), "drain plays out; it never discards")
        assertEquals(1, driver.countOf("drain"), "the line's own drain is what plays the queue out")
        assertTrue(driver.countOf("stop") >= 1, "drain stops the line once it is empty")
        driver.framePositionAnswer = 0
        assertEquals(0, s.latencyNanos(), "drain resets the submitted count")
        s.close()
    }

    @Test
    fun `close performs the stop ordering, closes after the join, and is idempotent`() = runBlocking {
        val s = sink()
        s.open(stereo48k, FullBlockCallback())
        driver.blockWrites = true
        s.start()
        assertTrue(driver.writeEntered.await(5, TimeUnit.SECONDS))
        s.close()
        s.close()
        assertTrue(driver.lineClosed)
        assertEquals(
            emptyList(),
            synchronized(driver.calls) { driver.postCloseCalls.toList() },
            "no line call may land after close; the join precedes it",
        )
        assertEquals(1, driver.countOf("close"))
    }

    // ── the underrun report, and the one call available() serves ────────────────────────────

    @Test
    fun `a render callback that runs dry outside a drain is reported as an underrun`() = runBlocking {
        val s = sink()
        s.open(stereo48k) { destination, frames, _ -> destination.writeSilence(0, frames); 0 }
        driver.availableAnswer = 1234 /* the line's own free space rides along in the detail */
        /* Subscribe BEFORE starting: the flow has no replay and the dry edge is the first block. */
        val under = async { s.events.first { it is AudioSinkEvent.Underrun } }
        yield()
        s.start()
        val event = withTimeout(5_000) { under.await() } as AudioSinkEvent.Underrun
        assertTrue(event.detail.contains("1234"), "the detail must carry available(): ${event.detail}")
        s.stop()
        s.close()
    }

    @Test
    fun `a drain running dry is the end of media, not an underrun`() = runBlocking {
        val s = sink()
        val served = AtomicInteger()
        val letDry = CountDownLatch(1)
        s.open(stereo48k) { destination, frames, _ ->
            served.incrementAndGet()
            destination.writeSilence(0, frames)
            if (letDry.count == 0L) 0 else frames
        }
        /* On another thread on purpose: this suite's waits block runBlocking's single thread, so
         * a collector on that thread would never run and the assertion would prove nothing. */
        val underruns = AtomicInteger()
        val collector = launch(Dispatchers.Default) {
            s.events.collect { if (it is AudioSinkEvent.Underrun) underruns.incrementAndGet() }
        }
        Thread.sleep(50) /* let the collector subscribe; the flow has no replay */
        s.start()
        awaitUntil("two full blocks before the drain") { served.get() >= 2 }
        /* Released only after drain() has already raised its flag (it does so before it blocks),
         * so the short pull that ends the drain cannot race the flag. */
        val releaser = Thread { Thread.sleep(100); letDry.countDown() }
        releaser.start()
        s.drain()
        releaser.join()
        /* A negative assertion needs a settle window: the emit and the collector sit on different
         * threads, so asserting the instant drain returns passes even when the event is in
         * flight. Proved long enough by the falsification arm, which goes red inside it. */
        Thread.sleep(500)
        assertEquals(0, underruns.get(), "the end of media must not be reported as a device underrun")
        collector.cancel()
        s.close()
    }

    // ── negative control ────────────────────────────────────────────────────────────────────

    @Test
    fun `negative control - closing before the join is caught by the fake as a post-close write`() {
        val fake = FakeSourceDataLine(stereo48k)
        fake.writeResults.add(64)
        val writing = CountDownLatch(1)
        val t = Thread {
            writing.countDown()
            fake.write(ByteArray(64), 0, 64)
        }
        /* Deliberately violate the ordering the sink guarantees: close first, write after. */
        fake.close()
        t.start()
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        t.join(5_000)
        if (fake.postCloseCalls.isEmpty()) {
            fail("the fake failed to record a post-close write; the ordering tests prove nothing")
        }
    }
}
