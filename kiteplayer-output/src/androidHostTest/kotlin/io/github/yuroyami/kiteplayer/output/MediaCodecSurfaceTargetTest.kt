package io.github.yuroyami.kiteplayer.output

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCodecSurfaceTargetTest {
    @Test
    fun surfaceUpdateFencesPresentationCompletionAccounting() {
        val target = MediaCodecSurfaceTarget()
        val completionEntered = CountDownLatch(1)
        val allowCompletion = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val updateReturned = CountDownLatch(1)
        val presented = AtomicLong()
        val observedAfterUpdate = AtomicLong(-1L)

        val releaseThread = thread(name = "codec-release") {
            target.withSnapshotCompletion(
                completion = { rendered ->
                    completionEntered.countDown()
                    assertTrue(allowCompletion.await(5, TimeUnit.SECONDS))
                    if (rendered) presented.incrementAndGet()
                },
                action = { true },
            )
        }
        assertTrue(completionEntered.await(5, TimeUnit.SECONDS))

        val updateThread = thread(name = "surface-update") {
            updateStarted.countDown()
            target.update(null)
            observedAfterUpdate.set(presented.get())
            updateReturned.countDown()
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))
        assertFalse(updateReturned.await(100, TimeUnit.MILLISECONDS))

        allowCompletion.countDown()
        releaseThread.join(5_000)
        updateThread.join(5_000)
        assertFalse(releaseThread.isAlive)
        assertFalse(updateThread.isAlive)
        assertEquals(1L, observedAfterUpdate.get())
    }
}
