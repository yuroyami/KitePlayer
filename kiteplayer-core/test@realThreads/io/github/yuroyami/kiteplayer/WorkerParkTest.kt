package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.Worker
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The park handshake of one [Worker] on real threads (#721): the actor asks, waits and releases
 * again and again while the worker loops through its checkpoint on another thread, and the worker
 * never does work while the actor holds what it owns.
 */
class WorkerParkTest {

    @Test
    fun aParkedWorkerNeverRunsWhileTheActorHoldsItsState() = runBlocking {
        val worker = Worker("park-test")
        val actorHolds = atomic(false)
        val ranWhileHeld = atomic(0)
        val steps = atomic(0L)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val loop = scope.launch {
            while (true) {
                worker.checkpoint()
                // Past the checkpoint the worker owns its state, so the actor must not.
                if (actorHolds.value) ranWhileHeld.incrementAndGet()
                steps.incrementAndGet()
                yield()
            }
        }
        try {
            var epoch = 0L
            repeat(ROUNDS) { round ->
                worker.requestQuiesce()
                assertTrue(worker.awaitQuiesced(2.seconds), "round $round: the worker did not park")
                actorHolds.value = true
                // While parked, the worker makes no step.
                val before = steps.value
                delay(50.microseconds)
                assertEquals(before, steps.value, "round $round: the worker stepped while parked")
                actorHolds.value = false
                worker.release(Generation(++epoch))
                // The next park waits until the worker ran again, so every round parks a running worker.
                val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 2.seconds
                while (steps.value == before && deadline.hasNotPassedNow()) yield()
                assertTrue(steps.value > before, "round $round: the released worker did not run again")
            }
            // The worker ran between the parks and kept every release.
            assertEquals(ROUNDS, worker.releases)
            assertTrue(steps.value >= ROUNDS, "the worker ran between the parks: ${steps.value} steps")
            assertEquals(0, ranWhileHeld.value, "the worker ran past a park while the actor held its state")
        } finally {
            loop.cancel()
            scope.cancel()
        }
    }

    private companion object {
        const val ROUNDS = 2_000
    }
}
