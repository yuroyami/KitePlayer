package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.io.InputStream

/**
 * Plays a stream. A stream reads forward only, so the player cannot seek in it or switch its video
 * track. Each open reads from the start, so [open] must return a new stream on every call. The
 * reader closes the stream it got.
 */
public fun MediaIo.Companion.ofStream(open: () -> InputStream): MediaIoFactory =
    MediaIoFactory { InputStreamMediaIo(open()) }

internal class InputStreamMediaIo(private val stream: InputStream) : MediaIo {
    override val size: Long? get() = null
    override val seekable: Boolean get() = false

    private var ended = false

    @Volatile
    private var closed = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "MediaIo is closed" }
        requireReadSlice(into, offset, length)
        if (length == 0) return 0
        if (ended) return -1
        val count = stream.read(into, offset, length)
        if (count < 0) ended = true
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        throw UnsupportedOperationException("A stream cannot seek")
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }
}
