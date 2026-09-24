package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.io.File

/**
 * Serves [bytes] forward only, and holds the read at [gateAt] until it is cancelled: a source that
 * accepted the request and then stopped sending. [waiting] completes when a read is held, and
 * [cancelled] when the held read was cancelled.
 */
internal class GatedReader(private val bytes: ByteArray, gateAt: Int = Int.MAX_VALUE) : MediaIo {
    @Volatile
    var gateAt: Int = gateAt

    @Volatile
    var served: Int = 0
        private set

    val waiting = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()

    override val size: Long? = null
    override val seekable: Boolean = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (served >= bytes.size) return -1
        if (served >= gateAt) {
            waiting.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val count = minOf(length, bytes.size - served, gateAt - served)
        bytes.copyInto(into, offset, served, served + count)
        served += count
        return count
    }

    override suspend fun seek(position: Long) = error("this reader cannot seek")

    override fun close() = Unit
}

/** The Matroska house clip from the testmedia tree, whole. */
internal fun baselineMkvBytes(): ByteArray {
    val file = File(formatMatrixMediaDir() ?: "testmedia", "baseline.mkv")
    check(file.isFile) { "$file is missing; run ./scripts/testmedia.sh first" }
    return file.readBytes()
}
