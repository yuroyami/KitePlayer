package io.github.yuroyami.kiteplayer

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel

/**
 * A bounded pipe for bytes that arrive by push: a socket you read yourself, a decryptor, or a
 * download in flight. Your code calls [write], then [finish] or [fail]. The engine reads.
 *
 * It reads like a live stream. Its size is unknown and it cannot seek, so the player cannot seek
 * in it or switch its video track. It holds [capacityChunks] chunks of up to 256 KiB, which is
 * 4 MiB by default, and [write] waits while it is full.
 *
 * A pipe is read once. A track switch, a loop or a recovery opens the item again, and that open
 * needs a new pipe with its own producer. So a factory that can reopen makes a new pipe per call:
 *
 * ```kotlin
 * val item = MediaItem.from(MediaIoFactory { PipedMediaIo().also { scope.launch { produce(it) } } }, "live")
 * ```
 */
public class PipedMediaIo(capacityChunks: Int = 16) : MediaIo {
    init {
        require(capacityChunks >= 1) { "capacityChunks must be at least 1, was $capacityChunks" }
    }

    private val chunks = Channel<ByteArray>(capacityChunks)
    private val ended = atomic(false)
    private val closed = atomic(false)
    private val failure = atomic<Throwable?>(null)

    // Reader-side state. MediaIo reads come one at a time from one worker.
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    override val size: Long? get() = null
    override val seekable: Boolean get() = false

    /**
     * Adds a copy of [length] bytes of [bytes] from [offset], so [bytes] is free again when this
     * returns. Waits while the pipe is full. Call it from one producer at a time.
     *
     * @throws IllegalStateException after [finish] or [fail].
     * @throws kotlinx.coroutines.CancellationException after the engine closed the reader, so a
     *         producer coroutine ends quietly.
     */
    public suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset in 0..bytes.size && length >= 0 && length <= bytes.size - offset) {
            "Write slice is outside the source array"
        }
        check(!ended.value) { "The pipe was already finished or failed" }
        var start = offset
        val end = offset + length
        while (start < end) {
            val count = minOf(CHUNK_BYTES, end - start)
            chunks.send(bytes.copyOfRange(start, start + count))
            start += count
        }
    }

    /** Ends the stream. The engine reads what is still in the pipe and then sees the end. */
    public fun finish() {
        if (ended.compareAndSet(false, true)) chunks.close()
    }

    /** Fails the stream. The next read throws [cause], and the open or the playback fails with it. */
    public fun fail(cause: Throwable) {
        if (ended.compareAndSet(false, true)) {
            failure.value = cause
            chunks.close(cause)
        }
    }

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed.value) { "MediaIo is closed" }
        require(offset in 0..into.size && length >= 0 && length <= into.size - offset) {
            "Read slice is outside the destination array"
        }
        failure.value?.let { throw it }
        if (length == 0) return 0
        val chunk = pending ?: run {
            val result = chunks.receiveCatching()
            if (result.isClosed) {
                check(!closed.value) { "MediaIo is closed" }
                result.exceptionOrNull()?.let { throw it }
                return -1
            }
            pendingOffset = 0
            result.getOrThrow().also { pending = it }
        }
        val count = minOf(length, chunk.size - pendingOffset)
        chunk.copyInto(into, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        if (pendingOffset == chunk.size) pending = null
        return count
    }

    override suspend fun seek(position: Long) {
        check(!closed.value) { "MediaIo is closed" }
        throw UnsupportedOperationException("A PipedMediaIo cannot seek")
    }

    /** Closes the reader side. A write that waits for room, and every later write, is cancelled. */
    override fun close() {
        if (closed.compareAndSet(false, true)) chunks.cancel()
    }
}

private const val CHUNK_BYTES = 256 * 1024
