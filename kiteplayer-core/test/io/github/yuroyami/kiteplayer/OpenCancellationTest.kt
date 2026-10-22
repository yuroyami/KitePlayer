package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An open whose reader hangs can be stopped: the engine runs the backend open as a job it can
 * cancel, and reads its mailbox while it waits. The scripted backend reads the item's reader during
 * its open, the way FFmpeg does while it discovers the streams.
 */
class OpenCancellationTest {

    @Test
    fun aStopEndsAnOpenWhoseReaderHangs() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(this)
        harness.backend.readsDuringOpen = 1
        val reader = SilentMediaIo()
        harness.attachRenderer()
        val opening = async { runCatching { harness.core.open(MediaItem("scripted://hanging", io = { reader })) } }
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Opening, harness.core.snapshots.value.status)
        val stoppedAt = testScheduler.currentTime

        // Virtual time: without the fix this stop waits for the open for ever.
        withTimeout(10.seconds) { harness.core.stop() }

        assertTrue(
            testScheduler.currentTime - stoppedAt <= 100,
            "the stop took ${testScheduler.currentTime - stoppedAt} ms; one poll of the mailbox is the promise",
        )
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        val refusal = assertIs<IllegalStateException>(opening.await().exceptionOrNull())
        assertTrue("preempted" in refusal.message.orEmpty(), "the open must say a stop preempted it: ${refusal.message}")
        assertTrue(reader.cancelled, "the cancellation must reach the reader's read")
        assertTrue(reader.closed, "the reader must be closed once the open has ended")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun cancellingAnOpenWhoseReaderHangsLeavesThePlayerIdle() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(this)
        harness.backend.readsDuringOpen = 1
        val reader = SilentMediaIo()
        harness.attachRenderer()
        val caller = launch { harness.core.open(MediaItem("scripted://hanging", io = { reader })) }
        harness.run(1.seconds)

        caller.cancel()
        harness.run(200.milliseconds)

        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status, "a cancelled open leaves Idle")
        assertTrue(reader.cancelled && reader.closed, "the reader's read was not ended and closed")
        // Nothing is left holding the demux lane: the next open plays.
        harness.backend.readsDuringOpen = 0
        harness.core.open(MediaItem("scripted://next"))
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun anOpenWhoseReaderStallsFailsAtTheStallTimeout() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(this, config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 5.seconds)))
        harness.backend.readsDuringOpen = 1
        val reader = SilentMediaIo()
        harness.attachRenderer()
        val startedAt = testScheduler.currentTime

        val failure = assertFailsWith<PlaybackException> {
            withTimeout(1.minutes) { harness.core.open(MediaItem("scripted://stalled", io = { reader })) }
        }

        val took = testScheduler.currentTime - startedAt
        assertTrue(took in 5_000..5_200, "the open failed after $took ms, not at the stall timeout")
        val error = assertIs<PlaybackError.SourceStalled>(failure.error)
        assertEquals("scripted://stalled", error.uri)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertTrue(reader.cancelled && reader.closed, "the stalled read was not ended and closed")
        harness.close()
    }

    @Test
    fun anOpenThatStillReadsIsNotAStall() = runTest(timeout = 20.seconds) {
        // Ten reads of one second each: the open takes five times the stall timeout, and bytes
        // arrive every second.
        val harness = CoreHarness(this, config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 2.seconds)))
        harness.backend.readsDuringOpen = 10
        harness.openThroughIo { SlowMediaIo(perRead = 1.seconds) }
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun anOpenThroughTheBackendsOwnProtocolsIsNotTimedByTheStallTimeout() = runTest(timeout = 20.seconds) {
        // No reader, so the engine cannot see progress, and the backend's own timeouts apply.
        val harness = CoreHarness(this, config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 2.seconds)))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        harness.backend.openGate = gate
        launch {
            delay(10.seconds)
            gate.complete(Unit)
        }
        harness.openWithRenderer()
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.close()
    }

    /** A reader that accepted the request and never sends a byte. Its read ends only by cancellation. */
    private class SilentMediaIo : MediaIo {
        var cancelled = false
            private set
        var closed = false
            private set

        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int =
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() {
            closed = true
        }
    }

    /** A reader that answers every read, late: a slow link that still delivers. */
    private class SlowMediaIo(private val perRead: Duration) : MediaIo {
        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            delay(perRead)
            return minOf(length, 4096)
        }

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() = Unit
    }
}
