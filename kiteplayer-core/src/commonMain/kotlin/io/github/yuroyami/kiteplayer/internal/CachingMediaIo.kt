package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.IoCachePolicy
import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.atomicfu.atomic

/**
 * The M5 byte cache (KPKMP 17.12): one contiguous RAM window over the wrapped [MediaIo].
 *
 * What it buys, in order of value on a network stream:
 *  - Chunked upstream reads: the demuxer's small nibbles become [IoCachePolicy.readChunkBytes]
 *    pulls, so a remote source sees hundreds of times fewer round trips.
 *  - A free seek-back window: a seek landing inside the window is served from RAM with NO
 *    upstream seek, which on http means no new ranged request. This is the piece that makes
 *    scrubbing a network stream tolerable.
 *  - An honest [Progress.bufferedRanges]: the window's byte span, published through
 *    [windowStartByte]/[windowEndByte] and time-mapped by the engine where duration and size
 *    are both known.
 *
 * Threading: MediaIo's own contract (demux worker only, one call at a time) is inherited, so
 * the mutable state below needs no lock; the two window atomics exist ONLY so the progress
 * sampler on the actor thread can read a coherent span.
 *
 * Honest limits, recorded where they are true: the window is ONE contiguous span, not a set
 * (a far seek resets it, exactly like mpv's cache before ranges), and the forward half fills
 * as the demuxer reads rather than through its own prefetch worker; the packet queues above
 * already read ahead by [io.github.yuroyami.kiteplayer.BufferPolicy]'s budgets.
 */
internal class CachingMediaIo(
    private val upstream: MediaIo,
    private val policy: IoCachePolicy,
) : MediaIo {

    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable

    /** The window's bytes: dense, variable-length chunks; copyOut walks their actual sizes. */
    private val chunks = ArrayDeque<ByteArray>()
    private var windowStart = 0L
    private var windowEnd = 0L

    /** Where the NEXT read serves from. Distinct from the upstream cursor past the window end. */
    private var cursor = 0L

    /** Upstream's own cursor, tracked so a window reset knows whether a real seek is needed. */
    private var upstreamPos = 0L
    private var upstreamEof = false

    /** For the progress sampler only; same values as windowStart/windowEnd, cross-thread safe. */
    val windowStartByte = atomic(0L)
    val windowEndByte = atomic(0L)

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        // Serve from the window when the cursor is inside it.
        if (cursor in windowStart until windowEnd) {
            val available = (windowEnd - cursor).toInt().coerceAtMost(length)
            copyOut(cursor, into, offset, available)
            cursor += available
            return available
        }
        // Not inside the window, so by construction AT its end: seek() either lands the cursor
        // inside the window or resets the window onto the seek target, and reads only ever
        // advance the cursor to windowEnd.
        check(cursor == windowEnd) { "cache cursor $cursor escaped the window [$windowStart, $windowEnd]" }
        if (upstreamEof && cursor == upstreamPos) return -1
        val chunk = ByteArray(policy.readChunkBytes)
        val pulled = upstream.read(chunk, 0, chunk.size)
        if (pulled < 0) {
            upstreamEof = true
            return -1
        }
        if (pulled == 0) return 0
        upstreamPos += pulled
        append(chunk, pulled)
        val served = pulled.coerceAtMost(length)
        copyOut(cursor, into, offset, served)
        cursor += served
        return served
    }

    override suspend fun seek(position: Long) {
        if (position in windowStart..windowEnd) {
            // Inside the window (its end included: the next read extends forward from there).
            // NO upstream traffic: this is the free seek-back the cache exists for.
            cursor = position
            return
        }
        upstream.seek(position)
        upstreamPos = position
        upstreamEof = false
        resetWindow(position)
        cursor = position
    }

    override fun close() {
        chunks.clear()
        upstream.close()
    }

    private fun resetWindow(at: Long) {
        chunks.clear()
        windowStart = at
        windowEnd = at
        publish()
    }

    private fun append(chunk: ByteArray, filled: Int) {
        chunks.addLast(if (filled == chunk.size) chunk else chunk.copyOf(filled))
        windowEnd += filled
        // Evict from the front, keeping the back window behind the cursor and the total budget.
        while (chunks.size > 1) {
            val first = chunks.first()
            val afterDrop = windowStart + first.size
            val keepsBackWindow = cursor - afterDrop >= policy.backWindowBytes
            val overBudget = windowEnd - windowStart > policy.forwardWindowBytes
            if (overBudget && keepsBackWindow) {
                chunks.removeFirst()
                windowStart = afterDrop
            } else {
                break
            }
        }
        publish()
    }

    private fun copyOut(from: Long, into: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var at = from
        var written = offset
        var chunkStart = windowStart
        for (chunk in chunks) {
            val chunkEnd = chunkStart + chunk.size
            if (at < chunkEnd) {
                val inChunk = (at - chunkStart).toInt()
                val take = (chunk.size - inChunk).coerceAtMost(remaining)
                chunk.copyInto(into, written, inChunk, inChunk + take)
                written += take
                at += take
                remaining -= take
                if (remaining == 0) return
            }
            chunkStart = chunkEnd
        }
        check(remaining == 0) { "cache window failed to cover $length bytes at $from" }
    }

    private fun publish() {
        windowStartByte.value = windowStart
        windowEndByte.value = windowEnd
    }
}
