// The HLS path reads MediaItem.openOptions, FFmpeg's own option names, to leave a key the caller set alone.
@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteOpener
import io.github.yuroyami.kiteffmpeg.MediaByteSource
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * How an HLS item opens through its reader.
 *
 * The playlist is read here, whole, and a master playlist keeps one variant (see
 * [keepOneHlsVariant]). FFmpeg then reads the playlist from memory, with the reader's address as
 * the base of the playlist's relative addresses. When the reader can open related addresses, the
 * opener hands FFmpeg a new reader for each variant playlist, segment and key, on the same
 * lifetime as the playlist's bridge, so one interrupt reaches every read.
 */
internal class HlsOpen(
    /** The playlist as FFmpeg reads it. Closing it closes the item's reader. */
    val playlist: MediaIo,
    /** The address the playlist's relative addresses resolve against. */
    val url: String,
    /** Null when the reader opens no related address, and FFmpeg's own protocols read them. */
    val opener: MediaByteOpener?,
    val ledger: HlsLedger,
) {
    /**
     * The pre-open options this open adds. A segment address often has no file extension, so
     * FFmpeg's extension check must not refuse it; the opener decides which addresses open.
     */
    fun options(item: MediaItem): Map<String, String> =
        if (opener != null && "extension_picky" !in item.openOptions) mapOf("extension_picky" to "0") else emptyMap()
}

/** The most bytes a playlist may have. A day of two second segments is about 3 MB. */
internal const val MAX_PLAYLIST_BYTES: Int = 16 * 1024 * 1024

/**
 * Reads [io]'s playlist and prepares the open. [lifetime] is the lifetime of every bridge of the
 * source. The caller closes [io] when this throws.
 */
internal suspend fun openHls(item: MediaItem, io: MediaIo, lifetime: Job): HlsOpen {
    val base = io.location ?: item.uri
    val text = readPlaylist(io, item.uri).decodeToString()
    val playlist = keepOneHlsVariant(text, item.demux.maxBitrate, item.demux.maxVideoHeight) ?: text
    val ledger = HlsLedger(item.uri)
    val opener = if (io.location != null && nestedOpensSupported) {
        MediaByteOpener { address -> openRelatedBridge(io, address, lifetime, ledger) }
    } else {
        null
    }
    return HlsOpen(PlaylistMediaIo(playlist.encodeToByteArray(), owner = io), base, opener, ledger)
}

/**
 * Opens [address] through [io] for FFmpeg, on the demux thread. A playlist read after a redirect
 * gets its addresses made absolute, because FFmpeg would resolve them against [address].
 */
private fun openRelatedBridge(io: MediaIo, address: String, lifetime: Job, ledger: HlsLedger): MediaByteSource? =
    blockingIn(lifetime) {
        val related = try {
            io.openRelated(address)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            ledger.failed(address, failure.message ?: failure.toString())
            throw failure
        }
        if (related == null) {
            ledger.failed(address, "the reader refused the address")
            return@blockingIn null
        }
        val redirected = related.location?.takeIf { it != address }
        val readable = if (redirected != null && looksLikeHls(null, related.contentType, address)) {
            try {
                val text = readPlaylist(related, address).decodeToString()
                PlaylistMediaIo(absoluteHlsAddresses(text, redirected).encodeToByteArray(), owner = related)
            } catch (failure: Throwable) {
                related.close()
                throw failure
            }
        } else {
            related
        }
        BlockingMediaIo(LedgeredMediaIo(readable, address, ledger), lifetime)
    }

/** Reads [io] to its end, refusing a playlist larger than [MAX_PLAYLIST_BYTES]. */
internal suspend fun readPlaylist(io: MediaIo, label: String): ByteArray {
    // One byte more than a declared size, so the end shows without a copy.
    val declared = io.size
    var buffer = ByteArray(if (declared != null && declared in 1..MAX_PLAYLIST_BYTES) declared.toInt() + 1 else 65_536)
    var filled = 0
    while (true) {
        if (filled == buffer.size) {
            if (filled > MAX_PLAYLIST_BYTES) {
                throw PlaybackException(
                    PlaybackError.NotMedia(label, "the playlist is larger than $MAX_PLAYLIST_BYTES bytes"),
                )
            }
            buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_PLAYLIST_BYTES + 1))
        }
        val count = io.read(buffer, filled, buffer.size - filled)
        when {
            count < 0 -> break
            // Nothing yet, and more may come.
            count == 0 -> delay(1)
            else -> filled += count
        }
    }
    if (filled > MAX_PLAYLIST_BYTES) {
        throw PlaybackException(PlaybackError.NotMedia(label, "the playlist is larger than $MAX_PLAYLIST_BYTES bytes"))
    }
    return buffer.copyOf(filled)
}

/** A playlist already read into memory, standing in for [owner], the reader that read it. Closing it closes [owner]. */
internal class PlaylistMediaIo(private val bytes: ByteArray, private val owner: MediaIo) : MediaIo {
    private var position = 0
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true
    override val location: String? get() = owner.location
    override val contentType: String? get() = owner.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        require(position in 0L..size) { "seek to $position outside 0..$size" }
        this.position = position.toInt()
    }

    override suspend fun openRelated(uri: String): MediaIo? = owner.openRelated(uri)

    override fun close() = owner.close()
}

/** A related reader whose failures and deliveries [ledger] records under [address]. */
private class LedgeredMediaIo(
    private val upstream: MediaIo,
    private val address: String,
    private val ledger: HlsLedger,
) : MediaIo {
    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable
    override val location: String? get() = upstream.location
    override val contentType: String? get() = upstream.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        val count = try {
            upstream.read(into, offset, length)
        } catch (failure: Throwable) {
            if (failure !is CancellationException) ledger.failed(address, failure.message ?: failure.toString())
            throw failure
        }
        if (count > 0) ledger.delivered()
        return count
    }

    override suspend fun seek(position: Long) = upstream.seek(position)

    override fun close() = upstream.close()
}

/**
 * What happened to the addresses an HLS stream named.
 *
 * FFmpeg's HLS demuxer skips a segment it cannot read and carries on, so a stream whose server
 * stopped answering would end as if it were complete. The ledger warns for each failure, counts
 * the failures since the last byte arrived, and [failureAtEnd] turns an end reached while that
 * count is not zero into an error.
 */
internal class HlsLedger(private val label: String) {
    private val failuresInARow = atomic(0)
    private val lastFailure = atomic<String?>(null)
    private val lock = SynchronizedObject()

    /** Warnings from before a sink was attached, which happens after the open. */
    private val early = ArrayList<PlaybackWarning>()
    private var sink: ((PlaybackWarning) -> Unit)? = null

    fun failed(address: String, detail: String) {
        failuresInARow.incrementAndGet()
        lastFailure.value = detail
        val warning = PlaybackWarning.SegmentSkipped(address, detail)
        val target = synchronized(lock) {
            sink ?: run {
                if (early.size < MAX_EARLY_WARNINGS) early += warning
                null
            }
        }
        target?.invoke(warning)
    }

    fun delivered() {
        failuresInARow.value = 0
    }

    /** Sends the warnings so far to [target], and every later one. */
    fun attach(target: (PlaybackWarning) -> Unit) {
        val pending = synchronized(lock) {
            sink = target
            early.toList().also { early.clear() }
        }
        pending.forEach(target)
    }

    /** The error for a stream that ended while its last addresses failed, or null when it ended whole. */
    fun failureAtEnd(): PlaybackException? {
        val failures = failuresInARow.value
        if (failures == 0) return null
        return PlaybackException(
            PlaybackError.SourceUnavailable(
                label,
                null,
                "the stream ended after $failures of its addresses failed in a row; the last failure: ${lastFailure.value}",
            ),
        )
    }

    private companion object {
        const val MAX_EARLY_WARNINGS = 16
    }
}
