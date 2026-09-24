package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteffmpeg.MediaByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The blocking bridge, for every target that has a blocking primitive.
 *
 * A [MediaIo.read] that returns 0 (nothing yet, more may come) is retried here, because the
 * blocking side's contract is block-or-end.
 *
 * Every read and seek runs as a child of [lifetime], which is what lets [interrupt] end a read that
 * waits inside the reader: the reader's suspended call resumes with a cancellation, and FFmpeg
 * receives an I/O error. Nothing that closes the reader may run on the demux lane while a read is in
 * flight, because this bridge holds that lane's thread; the engine closes a source only after its
 * reads have ended.
 */
internal actual class BlockingMediaIo actual constructor(
    private val io: MediaIo,
) : MediaByteSource {

    /** The parent of every read and seek. Cancelled only by [interrupt]. */
    private val lifetime = Job()

    actual override val size: Long? get() = io.size
    actual override val seekable: Boolean get() = io.seekable

    actual override fun read(into: ByteArray, offset: Int, length: Int): Int = runBlocking(lifetime) {
        var r = io.read(into, offset, length)
        while (r == 0) {
            delay(1)
            r = io.read(into, offset, length)
        }
        r
    }

    actual override fun seek(position: Long) {
        runBlocking(lifetime) { io.seek(position) }
    }

    actual override fun close() = io.close()

    actual fun interrupt() {
        lifetime.cancel(CancellationException("the media source was interrupted"))
    }
}
