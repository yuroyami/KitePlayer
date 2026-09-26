package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * As many converting video schedulers as `Dispatchers.Default` has threads must all finish (#224).
 * The desktop scheduler is a one-thread view of `Default`, and it converts there.
 */
class ParallelSlicesProgressTest {

    @Test
    fun asManyConcurrentConversionsAsDefaultHasThreadsAllFinish() = runBlocking {
        val callers = maxOf(2, Runtime.getRuntime().availableProcessors())
        val finished = AtomicInteger()
        val wrongRows = AtomicInteger()
        // Not children of this test: a deadlocked caller would otherwise hold the test open for ever.
        val callerScope = CoroutineScope(Job())
        val jobs = List(callers) {
            callerScope.launch(Dispatchers.Default.limitedParallelism(1)) {
                val out = IntArray(WIDTH * HEIGHT)
                repeat(10) { round ->
                    parallelRowSlices(WIDTH, HEIGHT) { from, to ->
                        for (row in from until to) {
                            for (column in 0 until WIDTH) out[row * WIDTH + column] = row + round
                        }
                    }
                    for (row in 0 until HEIGHT) if (out[row * WIDTH] != row + round) wrongRows.incrementAndGet()
                }
                finished.incrementAndGet()
            }
        }
        assertNotNull(
            withTimeoutOrNull(20.seconds) { jobs.joinAll() },
            "${finished.get()} of $callers conversions finished within 20 s",
        )
        assertEquals(0, wrongRows.get(), "every row is converted exactly as asked")
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
