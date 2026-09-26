package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a stream. A stream reads forward only, so the player cannot seek in it or switch its video
 * track. Each open reads from the start, so [open] must return a new stream on every call. The
 * reader closes the stream it got.
 *
 * A stream read blocks its thread, so stop and close cannot wait for one. When the player gives up
 * on a read, for example at stop, the reader closes the stream, which ends the read for streams
 * that stop reading at close, as sockets and Android pipes do.
 */
public fun MediaIo.Companion.ofStream(open: () -> InputStream): MediaIoFactory =
    MediaIoFactory { InputStreamMediaIo(open()) }

internal class InputStreamMediaIo(private val stream: InputStream) : MediaIo {
    override val size: Long? get() = null
    override val seekable: Boolean get() = false

    private var ended = false
    private val closed = AtomicBoolean(false)

    // The blocking reads run here, so a cancelled caller does not have to wait for one.
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Where a read lands before it is copied out. Nothing reads again after a cancelled read closes.
    private var scratch = ByteArray(0)

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed.get()) { "MediaIo is closed" }
        requireReadSlice(into, offset, length)
        if (length == 0) return 0
        if (ended) return -1
        if (scratch.size < length) scratch = ByteArray(length)
        val buffer = scratch
        val pending = reads.async { stream.read(buffer, 0, length) }
        val count = try {
            pending.await()
        } catch (cancellation: CancellationException) {
            // Only a close ends a read that blocks its thread. Without it, stop and close waited
            // for ever on a stream that sent nothing more (#276).
            close()
            throw cancellation
        }
        if (count > 0) buffer.copyInto(into, offset, 0, count)
        if (count < 0) ended = true
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed.get()) { "MediaIo is closed" }
        throw UnsupportedOperationException("A stream cannot seek")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) stream.close()
    }
}
