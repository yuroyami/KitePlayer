package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The parallel body, for every target with more than one thread to run it on. */
internal actual inline fun parallelRowSlices(
    width: Int,
    height: Int,
    crossinline body: (startRow: Int, endRowExclusive: Int) -> Unit,
) {
    val slices = parallelSliceCount(width, height)
    if (slices <= 1) {
        body(0, height)
        return
    }
    runRowSlices(height, slices) { from, to -> body(from, to) }
}

/**
 * Runs the slices on the calling thread and on helpers from `Dispatchers.Default`, each taking the
 * next slice that nobody took yet.
 *
 * The caller alone can finish every slice, and it waits only for slices a helper already started.
 * A caller that waited for helpers to start deadlocked when every `Default` thread was a caller
 * doing the same, as on the desktop, where the video scheduler converts on `Default` (#224).
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun runRowSlices(height: Int, slices: Int, body: (startRow: Int, endRowExclusive: Int) -> Unit) {
    // Even boundaries: a subsampled chroma row serves two luma rows.
    val rowsPerSlice = ((height + slices - 1) / slices + 1) and 1.inv()
    val total = (height + rowsPerSlice - 1) / rowsPerSlice
    val claimed = atomic(0)
    val finished = atomic(0)
    val failure = atomic<Throwable?>(null)
    val allDone = CompletableDeferred<Unit>()

    fun runClaimedSlices() {
        while (true) {
            val index = claimed.getAndIncrement()
            if (index >= total) return
            val from = index * rowsPerSlice
            try {
                body(from, minOf(from + rowsPerSlice, height))
            } catch (thrown: Throwable) {
                failure.compareAndSet(null, thrown)
            } finally {
                if (finished.incrementAndGet() == total) allDone.complete(Unit)
            }
        }
    }

    // Helpers that start after the caller took every slice find nothing and end.
    repeat(total - 1) { GlobalScope.launch(Dispatchers.Default) { runClaimedSlices() } }
    runClaimedSlices()
    // Only slices that a running helper holds are left, so this wait always ends.
    if (finished.value < total) runBlocking { allDone.await() }
    failure.value?.let { throw it }
}
