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

    private companion object {
        const val PATH = "/recordings/slow.mkv"
    }
}
