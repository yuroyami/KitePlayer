package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The stall timeout: a source that stops answering ends the session with a typed error at
 * `BufferPolicy.stallTimeout`, instead of leaving the player in Buffering until someone gives up.
 * The scripted wedge is an uncancellable native read that only `interrupt()` ends.
 */
class StallTimeoutTest {

    @Test
    fun aSourceThatStopsAnsweringEndsTheSessionAtTheStallTimeout() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            faults = FaultPlan().apply { readWedgesAfter = WEDGE_AFTER_READS },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 5.seconds)),
        )
        harness.openWithRenderer()
        harness.core.play()
        val wedgedAt = awaitWedge(harness)

        runUntil(harness, wedgedAt + 4_900.milliseconds)
        assertNotEquals(
            PlaybackStatus.Failed,
            harness.core.snapshots.value.status,
            "the session ended before the stall timeout: ${harness.core.snapshots.value.error}",
        )

        runUntil(harness, wedgedAt + 5_200.milliseconds)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        val error = assertIs<PlaybackError.SourceStalled>(harness.core.snapshots.value.error)
        assertEquals("scripted://media", error.uri)
        assertTrue(error.stalledFor >= 5.seconds, "the error must say how long the read waited, said ${error.stalledFor}")
        assertTrue(harness.source.interruptCalls > 0, "the stall must interrupt the read it gives up on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun aSlowReaderThatStillDeliversBytesIsNotAStall() = runTest(timeout = 20.seconds) {
        // Every packet costs eight reads of one second each, so a packet takes eight seconds, four
        // times the stall timeout. Bytes arrive every second, and that is progress.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 20_000_000),
            faults = FaultPlan().apply { ioReadsPerPacket = 8 },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 2.seconds)),
        )
        harness.openThroughIo { SlowMediaIo(perRead = 1.seconds) }
        harness.core.play()
        val readsBefore = harness.source.reads
        harness.run(30.seconds)

        assertNotEquals(
            PlaybackStatus.Failed,
            harness.core.snapshots.value.status,
            "a slow source that still delivers was ended: ${harness.core.snapshots.value.error}",
        )
        assertTrue(harness.source.reads >= readsBefore + 3, "the source must have been read while the test watched")
        harness.close()
    }

    @Test
    fun anInfiniteStallTimeoutKeepsTheOldWait() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            faults = FaultPlan().apply { readWedgesAfter = WEDGE_AFTER_READS },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = Duration.INFINITE)),
        )
        harness.openWithRenderer()
        harness.core.play()
        awaitWedge(harness)
        harness.run(2.minutes)

        assertNotEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertEquals(0, harness.source.interruptCalls, "nothing may interrupt a read that the policy lets wait")
        // The teardown interrupts the wedge, so the close still completes.
        harness.close()
    }

    @Test
    fun aSourceThatCannotInterruptKeepsWaitingPastTheStallTimeout() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 60_000_000),
            faults = FaultPlan().apply {
                readWedgesAfter = WEDGE_AFTER_READS
                interruptSupported = false
            },
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 5.seconds)),
        )
        harness.openWithRenderer()
        harness.core.play()
        try {
            awaitWedge(harness)
            harness.run(20.seconds)

            assertNotEquals(
                PlaybackStatus.Failed,
                harness.core.snapshots.value.status,
                "a teardown around a read that cannot end would hang, so the session must keep waiting",
            )
            assertEquals(1, harness.source.interruptCalls, "the engine asks once and then leaves the source alone")
        } finally {
            // Released on every path: a wedge nothing can interrupt would otherwise hold the test's
            // own cleanup for ever. The read returns on its own, as a slow scan eventually does.
            harness.source.releaseWedge()
        }
        harness.run(1.seconds)
        harness.close()
    }

    @Test
    fun anExternalSubtitleWhoseReaderStallsIsSkippedAtTheStallTimeout() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(this, config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 3.seconds)))
        harness.attachRenderer()
        val item = MediaItem(
            "scripted://media",
            externalSubtitles = listOf(SubtitleSource("https://example.test/stalled.srt", io = { SilentMediaIo() })),
        )
        val startedAt = testScheduler.currentTime

        // Virtual time: without the bound this open waits for the subtitle file for ever.
        withTimeout(1.minutes) { harness.core.open(item) }

        assertTrue(testScheduler.currentTime - startedAt >= 3_000, "the file was given up before the stall timeout")
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the media itself must still open")
        val skipped = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.SubtitleSourceUnreadable>().single()
        assertTrue("no bytes arrived" in skipped.reason, "the warning must say why: ${skipped.reason}")
        harness.close()
    }

    @Test
    fun aStallTimeoutMustBePositive() {
        assertFailsWith<IllegalArgumentException> { BufferPolicy(stallTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BufferPolicy(stallTimeout = (-1).seconds) }
    }

    /** Waits until the scripted source wedges, and answers when that began by the harness clock. */
    private suspend fun awaitWedge(harness: CoreHarness): Duration {
        repeat(3_000) {
            harness.source.wedgedAtNanos?.let { return it.nanoseconds }
            harness.run(10.milliseconds)
        }
        error("the scripted source never wedged")
    }

    /** Lets virtual time pass until the harness clock reads [at]. */
    private suspend fun runUntil(harness: CoreHarness, at: Duration) {
        val left = at - harness.clock.nanos().nanoseconds
        if (left > Duration.ZERO) harness.run(left)
    }

    /** A reader that answers every read, one second late: a slow link that still delivers. */
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

    /** A reader that accepted the request and never sends a byte. Its read ends only by cancellation. */
    private class SilentMediaIo : MediaIo {
        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = awaitCancellation()

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() = Unit
    }

    private companion object {
        /** About four seconds of scripted media, so the wedge begins once playback is ready. */
        const val WEDGE_AFTER_READS = 300
    }
}
