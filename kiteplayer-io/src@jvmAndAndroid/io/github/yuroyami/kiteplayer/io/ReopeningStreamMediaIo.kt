package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * A stream of a known [size] that seeks by opening it again (#457): a bundled resource that is
 * stored compressed, an entry of the app's jar or a compressed Android asset, which has no
 * descriptor to read by position. A seek forward skips, and a seek back opens the stream again
 * and skips from its start, which inflates the bytes before the target once more. For a clip an
 * app bundles that costs little, and it is what lets a file whose index sits at its end play at
 * all. [open] must return a new stream from the start on every call.
 */
internal class ReopeningStreamMediaIo(private val open: () -> InputStream, override val size: Long) : MediaIo {
    override val seekable: Boolean get() = true

    private var stream: InputStream? = null
    private var position = 0L
    private var at = 0L
    private var closed = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "MediaIo is closed" }
        requireReadSlice(into, offset, length)
        if (length == 0) return 0
        if (position >= size) return -1
        return withContext(Dispatchers.IO) {
            val current = positioned()
            val count = current.read(into, offset, minOf(length.toLong(), size - position).toInt())
            if (count > 0) {
                position += count
                at += count
            }
            count
        }
    }

    override suspend fun seek(position: Long) {
        check(!closed) { "MediaIo is closed" }
        require(position in 0..size) { "seek to $position outside 0..$size" }
        this.position = position
    }

    /** The stream, at [position]: skipped forward, or opened again when it stands past it. */
    private fun positioned(): InputStream {
        var current = stream
        if (current == null || at > position) {
            current?.close()
            current = open()
            stream = current
            at = 0
        }
        while (at < position) {
            val skipped = current.skip(position - at)
            if (skipped <= 0) {
                // skip may stop short without reaching the end; a read says which it was.
                if (current.read() < 0) throw MediaIoException("the stream ended at byte $at, before byte $position")
                at++
            } else {
                at += skipped
            }
        }
        return current
    }

    override fun close() {
        if (closed) return
        closed = true
        stream?.close()
        stream = null
    }
}
