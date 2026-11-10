package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.scanMediaAudio
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cancelled audio scan never closes its source while the interrupt that its cancellation started
 * is still running on the cancelling thread (#714).
 *
 * Completing the link that carries the cancellation does not wait for a handler already running
 * elsewhere, so the scan's close used to run under an interrupt in flight, which on a native
 * target can reach a freed format context.
 */
class ScanInterruptCloseTest {

    @Test
    fun aScanClosesOnlyAfterTheInterruptInFlightHasReturned() = runTest {
        val scanLane = Executors.newSingleThreadExecutor { Thread(it, "kiteplayer-test-scan").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000, hasVideo = false))
        val inside = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        try {
            var first = true
            val scan = launch(scanLane, start = CoroutineStart.DEFAULT) {
                runCatching {
                    scanMediaAudio(
                        harness.backend,
                        MediaItem("scripted://scan"),
                        track = null,
                        preferredLanguages = emptyList(),
                        range = null,
                        sink = { _, _, _, _ ->
                            if (first) {
                                first = false
                                harness.session.scriptedSource.interrupting = {
                                    inside.countDown()
                                    // Bounded, so a close that never waits fails rather than hangs.
                                    letGo.await(5, TimeUnit.SECONDS)
                                }
                                reading.countDown()
                                cancelled.await(5, TimeUnit.SECONDS)
                            }
                        },
                    )
                }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS), "the scan never read")
            val session = harness.session
            // The cancellation's handler runs on this thread, and the interrupt holds it there.
            val canceller = Thread { scan.cancel() }.apply { start() }
            assertTrue(inside.await(5, TimeUnit.SECONDS), "the cancellation never reached the source")
            cancelled.countDown()
            // The scan now ends and reaches its close, which must wait for the interrupt.
            Thread.sleep(300)
            assertEquals(0, session.closeCount, "the scan closed its source while the interrupt was still running")
            letGo.countDown()
            canceller.join(5_000)
            runBlocking { scan.join() }
            assertEquals(1, session.closeCount, "the scan never closed its source")
        } finally {
            letGo.countDown()
            cancelled.countDown()
            scanLane.close()
        }
        harness.close()
    }
}
