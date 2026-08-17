package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
    val rowsPerSlice = ((height + slices - 1) / slices + 1) and 1.inv()
    runBlocking {
        coroutineScope {
            var start = 0
            while (start < height) {
                val from = start
                val to = minOf(start + rowsPerSlice, height)
                launch(Dispatchers.Default) { body(from, to) }
                start = to
            }
        }
    }
}
