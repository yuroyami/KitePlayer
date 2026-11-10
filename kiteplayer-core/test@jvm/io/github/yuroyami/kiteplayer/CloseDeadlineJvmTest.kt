package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A close answers within its deadline whatever the release is waiting for (#473).
 *
 * The release runs on a real thread here, as a platform gives it a lane of its own, so it can block
 * the way a disk write does while the actor's deadline runs on the test's virtual clock.
 */
class CloseDeadlineJvmTest {

    @Test
    fun aRecordingWhoseFileWillNotFinishCannotHoldACloseOverItsDeadline() = runTest {
        val releaseLane = Executors.newSingleThreadExecutor { Thread(it, "kiteplayer-test-release").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val trailer = CountDownLatch(1)
        try {
            val harness = CoreHarness(
                this,
                script = MediaScript(recordable = true),
                releaseDispatcher = releaseLane,
                closeDeadline = 1.seconds,
            )
            harness.openWithRenderer()
            harness.core.startRecording(PATH)
            val session = harness.session
            val recording = checkNotNull(session.recordingSource)
            // A trailer far slower than the deadline. Bounded, so a close that waits for it fails
            // after five seconds rather than hanging the suite.
            recording.finishing = { trailer.await(5, TimeUnit.SECONDS) }

            val failure = assertFailsWith<PlaybackException> { harness.core.closeAndAwait() }
            assertIs<PlaybackError.RuntimeCompromised>(failure.error, "a close past its deadline must say so")
            assertEquals(listOf("start $PATH"), synchronized(recording.calls) { recording.calls.toList() }, "the close waited for the file")

            // The release goes on once the write returns: it finishes the file and closes the rest.
            trailer.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.closeCount == 0 && System.nanoTime() < until) withContext(Dispatchers.Default) { delay(10) }
            assertEquals(listOf("start $PATH", "stop"), recording.calls)
            assertEquals(1, session.closeCount, "the release never finished after the file did")
            harness.stopDevice()
        } finally {
            trailer.countDown()
            releaseLane.close()
        }
    }

    @Test
    fun aPreloadWhoseSourceWillNotCloseCannotHoldACloseOverItsDeadline() = runTest {
        val releaseLane = Executors.newSingleThreadExecutor { Thread(it, "kiteplayer-test-release").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val stuck = CountDownLatch(1)
        try {
            val harness = CoreHarness(
                this,
                script = MediaScript(durationUs = 3_000_000, hasVideo = false),
                releaseDispatcher = releaseLane,
                closeDeadline = 1.seconds,
            )
            harness.core.openQueue(listOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
            harness.core.play()
            var waited = 0
            while (harness.core.snapshots.value.preloadedIndex != 1 && waited < 600) {
                harness.run(5.milliseconds)
                waited++
            }
            assertEquals(1, harness.core.snapshots.value.preloadedIndex, "the second item preloads")
            val preload = harness.backend.sessions.last()
            // Bounded, so a close that waits for it fails after five seconds rather than hanging.
            preload.closing = { stuck.await(5, TimeUnit.SECONDS) }

            // Before #707 the close dropped the preload on the actor first, outside any deadline,
            // and so waited the whole five seconds for this source before it answered.
            val started = System.nanoTime()
            val failure = assertFailsWith<PlaybackException> { harness.core.closeAndAwait() }
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertIs<PlaybackError.RuntimeCompromised>(failure.error, "a close past its deadline must say so")
            assertTrue(tookMs < 4_000, "the close waited for the preload's source: $tookMs ms")
            assertEquals(0, preload.closeCount, "the preload's source was still closing when close answered")

            stuck.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (preload.closeCount == 0 && System.nanoTime() < until) withContext(Dispatchers.Default) { delay(10) }
            assertEquals(1, preload.closeCount, "the preload was never released once its source returned")
            harness.stopDevice()
        } finally {
            stuck.countDown()
            releaseLane.close()
        }
    }

    private companion object {
        const val PATH = "/recordings/slow.mkv"
    }
}
