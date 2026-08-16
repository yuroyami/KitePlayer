package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kitecodec.MediaByteSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Adapts the engine's suspending [MediaIo] onto KiteCodec's blocking [MediaByteSource] (M1).
 *
 * The block is real and deliberate: FFmpeg's demuxer pulls bytes synchronously on the demux
 * worker, which is exactly the one thread [MediaIo]'s contract already confines it to, so
 * bridging with [runBlocking] parks that worker and nothing else. A [MediaIo.read] that
 * returns 0 (nothing yet, more may come) is retried here, because the blocking side's
 * contract is block-or-end.
 */
internal class BlockingMediaIo(private val io: MediaIo) : MediaByteSource {

    override val size: Long? get() = io.size
    override val seekable: Boolean get() = io.seekable

    override fun read(into: ByteArray, offset: Int, length: Int): Int = runBlocking {
        var r = io.read(into, offset, length)
        while (r == 0) {
            delay(1)
            r = io.read(into, offset, length)
        }
        r
    }

    override fun seek(position: Long) {
        runBlocking { io.seek(position) }
    }

    override fun close() = io.close()
}
