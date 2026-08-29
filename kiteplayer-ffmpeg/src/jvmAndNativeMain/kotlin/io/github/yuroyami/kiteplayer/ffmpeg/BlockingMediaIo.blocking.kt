package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteffmpeg.MediaByteSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The blocking bridge, for every target that has a blocking primitive.
 *
 * A [MediaIo.read] that returns 0 (nothing yet, more may come) is retried here, because the
 * blocking side's contract is block-or-end.
 */
internal actual class BlockingMediaIo actual constructor(
    private val io: MediaIo,
) : MediaByteSource {

    actual override val size: Long? get() = io.size
    actual override val seekable: Boolean get() = io.seekable

    actual override fun read(into: ByteArray, offset: Int, length: Int): Int = runBlocking {
        var r = io.read(into, offset, length)
        while (r == 0) {
            delay(1)
            r = io.read(into, offset, length)
        }
        r
    }

    actual override fun seek(position: Long) {
        runBlocking { io.seek(position) }
    }

    actual override fun close() = io.close()
}
