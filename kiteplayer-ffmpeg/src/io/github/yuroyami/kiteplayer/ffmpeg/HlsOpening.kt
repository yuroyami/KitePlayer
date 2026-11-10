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
import io.github.yuroyami.kiteplayer.Playlists
import io.github.yuroyami.kiteplayer.StreamVariant
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
    /** Every variant of a master playlist, in playlist order, or empty for a media playlist. */
    val variants: List<StreamVariant> = emptyList(),
    /** The index of the variant that plays, or null for a media playlist. */
    val selectedVariant: Int? = null,
    /** The seek bar pictures of a master playlist that names an image stream, or null (#433). */
    val thumbnails: HlsThumbnails? = null,
    /** The time of day of the stream's positions, from its playlists' dates (#444). */
    val times: HlsTimeOfDay = HlsTimeOfDay(),
    /** Moves a master playlist's stream to another variant in place, or null when it cannot (#464). */
    val switch: HlsVariantSwitch? = null,
    /** Where the playlist asks to start, once FFmpeg has read the playlist that says (#627). */
    val start: HlsStart = HlsStart(null, null, null),
) {
    /** Where a finished media playlist asks to start, in microseconds (#627), or null. */
    val suggestedStartUs: Long? get() = start.startUs

    /**
     * The pre-open options this open adds. A segment address often has no file extension, so
     * FFmpeg's extension check must not refuse it; the opener decides which addresses open. A live
     * playlist that names a start point with `EXT-X-START` starts there, as RFC 8216 asks, rather
     * than three segments from its end, which FFmpeg does only with `prefer_x_start` on (#627). A
     * key the caller set stays as the caller set it.
     */
    fun options(item: MediaItem): Map<String, String> = buildMap {
        if (opener != null && "extension_picky" !in item.openOptions) put("extension_picky", "0")
        if ("prefer_x_start" !in item.openOptions) put("prefer_x_start", "1")
    }
}

/**
 * Where the finished media playlist [text] asks playback to start, in microseconds, from its
 * `EXT-X-START` (#627), or null. A negative `TIME-OFFSET` counts back from the end, and one past
 * either end stops there. A live playlist gives null: FFmpeg applies the tag to it with
 * `prefer_x_start`, and only to it, so a finished one started at zero.
 */
internal fun hlsSuggestedStartUs(text: String, master: String? = null): Long? {
    if (text.lineSequence().none { it.trim() == "#EXT-X-ENDLIST" }) return null
    // A master may name the start for every playlist it lists; a media playlist's own tag wins.
    fun tagOf(playlist: String) = playlist.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#EXT-X-START:") }
    val tag = tagOf(text) ?: master?.let(::tagOf) ?: return null
    val offset = tag.removePrefix("#EXT-X-START:").split(',')
        .map { it.trim() }
        .firstOrNull { it.startsWith("TIME-OFFSET=") }
        ?.removePrefix("TIME-OFFSET=")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() } ?: return null
    val totalSeconds = text.lineSequence().map { it.trim() }
        .filter { it.startsWith("#EXTINF:") }
        .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
    val seconds = (if (offset < 0) totalSeconds + offset else offset).coerceIn(0.0, totalSeconds)
    return (seconds * 1_000_000).toLong()
}

/** The most bytes a playlist may have. A day of two second segments is about 3 MB. */
internal const val MAX_PLAYLIST_BYTES: Int = 16 * 1024 * 1024

/**
 * Reads [io]'s playlist and prepares the open. [lifetime] is the lifetime of every bridge of the
 * source. The caller closes [io] when this throws.
 */
internal suspend fun openHls(item: MediaItem, io: MediaIo, lifetime: Job, read: String? = null): HlsOpen {
    val base = io.location ?: item.uri
    val text = read ?: readPlaylist(io, item.uri).decodeToString()
    // A master that names a protected session key is refused before any variant is read (#624).
    hlsDrmRefusal(text, item.uri)?.let { throw it }
    val master = keepOneHlsVariant(text, item.demux.maxBitrate, item.demux.maxVideoHeight, item.demux.variant, item.demux.fit, ::hlsCodecDecodable)
    val ledger = HlsLedger(item.uri)
    // The kept variant's backups stand in for it when an address fails (#440).
    val failover = HlsFailover(master?.backups.orEmpty(), base)
    val times = HlsTimeOfDay()
    // FFmpeg reloads a media playlist from its own address, which is the base it reads it under.
    if (master == null) times.read(base, text)
    val nested = io.location != null && nestedOpensSupported
    // With two variants or more, the kept one is served by a switch that can move to another (#464).
    val switch = if (master != null && nested && master.variants.size > 1) {
        HlsVariantSwitch(
            addresses = master.addresses.map { Playlists.resolve(base, it) },
            traits = master.variants,
            initial = master.chosen,
            choose = {
                master.variants.indexOf(
                    chooseHlsVariant(master.variants, item.demux.maxBitrate, item.demux.maxVideoHeight, item.demux.fit, ::hlsCodecDecodable),
                )
            },
            openAddress = { address -> if (failover.isEmpty) io.openRelated(address) else failover.open(address) { io.openRelated(it) } },
            openAddressAt = { address, offset, length ->
                if (failover.isEmpty) {
                    io.openRelated(address, offset, length)
                } else {
                    failover.open(address) { io.openRelated(it, offset, length) }
                }
            },
        )
    } else {
        null
    }
    val playlist = when {
        master == null -> text
        switch != null -> master.servedFrom(HlsVariantSwitch.PLAYLIST)
        else -> master.playlist
    }.let { if (master == null) it else withoutStartTag(it) }
    // A master names its start in the kept variant's playlist, or in itself, and FFmpeg reads that
    // playlist through the opener during its own open, which is where the start is read (#627).
    val start = when {
        master == null -> HlsStart(null, null, hlsSuggestedStartUs(text))
        switch != null -> HlsStart(HlsVariantSwitch.PLAYLIST, text, null)
        nested -> HlsStart(keptVariantAddress(master.playlist)?.let { Playlists.resolve(base, it) }, text, null)
        else -> HlsStart(null, null, null)
    }
    val opener = if (nested) {
        MediaByteOpener { address -> openRelatedBridge(io, address, lifetime, ledger, failover, times, switch, start) }
    } else {
        // No segment open will say where FFmpeg starts, which for a playlist that has ended is its first.
        times.anchorAtStart(base)
        null
    }
    val variants = master?.variants.orEmpty().mapIndexed { index, variant ->
        StreamVariant(
            index = index,
            bitrate = variant.bandwidth,
            width = variant.width,
            height = variant.height,
            frameRate = variant.frameRate,
            codecs = variant.attributes["CODECS"],
            hdr = variant.hdr,
        )
    }
    // The pictures are read through the item's own reader, as its segments are (#433).
    val thumbnails = HlsThumbnails.choose(hlsImageStreams(text))?.let { HlsThumbnails(io, base, it) }
    return HlsOpen(
        PlaylistMediaIo(playlist.encodeToByteArray(), owner = io), base, opener, ledger, variants, master?.chosen, thumbnails, times, switch,
        start = start,
    )
}

/**
 * [master] without its `EXT-X-START`. FFmpeg takes that tag for a media playlist's, and then refuses
 * the master's first variant as "Master Playlist tag found in a Media Playlist", so a master that
 * named its start never opened. The start is read here instead (#627).
 */
private fun withoutStartTag(master: String): String =
    if ("#EXT-X-START:" !in master) master else master.split('\n').filterNot { it.trim().startsWith("#EXT-X-START:") }.joinToString("\n")

/** The address the kept [master] playlist gives its one variant, as written, or null. */
private fun keptVariantAddress(master: String): String? {
    val lines = master.split('\n').map { it.trim() }
    val tag = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
    if (tag < 0) return null
    return (tag + 1 until lines.size).map { lines[it] }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
}

/**
 * Where an HLS item asks to start (#627). A media playlist says so in itself, which the open reads
 * at once. A master's kept variant says so in its own playlist, which FFmpeg reads through the
 * opener during its open, so [read] takes the first text read from [variantAddress], with the
 * master's own tag standing in when that playlist names none. A rendition's playlist, which FFmpeg
 * may read first, does not count.
 */
internal class HlsStart(private val variantAddress: String?, private val master: String?, initial: Long?) {
    private val found = atomic<Long?>(initial)
    private val read = atomic(variantAddress == null)

    /** The playlist at [address] was read whole as [text]. */
    fun read(address: String, text: String) {
        if (address != variantAddress || !read.compareAndSet(false, true)) return
        found.value = hlsSuggestedStartUs(text, master)
    }

    /** The start in microseconds, or null when nothing names one or the playlist was not read yet. */
    val startUs: Long? get() = found.value
}

/**
 * Opens [address] through [io] for FFmpeg, on the demux thread. A reader that was redirected
 * hands FFmpeg its new address as the bridge's location, and FFmpeg resolves a playlist's
 * addresses against it after it has put in the playlist's variables, exactly as it does after a
 * redirect its own `http` follows. The playlist reaches FFmpeg as the server sent it.
 */
private fun openRelatedBridge(
    io: MediaIo,
    address: String,
    lifetime: Job,
    ledger: HlsLedger,
    failover: HlsFailover,
    times: HlsTimeOfDay,
    switch: HlsVariantSwitch?,
    start: HlsStart,
): MediaByteSource? =
    blockingIn(lifetime) {
        // The kept variant's playlist and segments are at addresses the switch gave them (#464).
        val owner = switch?.takeIf { it.owns(address) }
        // The address a warning names is the stream's, never one the switch made up.
        suspend fun named(): String = owner?.nameOf(address) ?: address
        val related = try {
            when {
                owner != null -> owner.open(address)
                failover.isEmpty -> io.openRelated(address)
                else -> failover.open(address) { target -> io.openRelated(target) }
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            ledger.failed(named(), failure.message ?: failure.toString())
            throw failure
        }
        if (related == null) {
            ledger.failed(named(), "the reader refused the address")
            return@blockingIn null
        }
        times.opened(address)
        // A playlist among the addresses, a variant's or a live reload, gives its dates (#444).
        val shown = named()
        val dated = PlaylistDates(LedgeredMediaIo(related, shown, ledger), address, related.location ?: address, times) { text ->
            // A media playlist whose keys are protected by digital rights management fails its
            // read, so FFmpeg decrypts nothing with the wrong key, and the open says why (#624).
            hlsDrmRefusal(text, shown)?.let { throw ledger.refuse(it) }
            start.read(address, text)
        }
        BlockingMediaIo(dated, lifetime)
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
    override suspend fun openRelated(uri: String, offset: Long, length: Long?): MediaIo? = owner.openRelated(uri, offset, length)

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
 * A related reader that hands [times] the text it delivers when that text is an HLS playlist
 * (#444): a copy is kept from the first bytes, if they start one, until the end of the reader, and
 * read there. Anything else passes through with nothing kept. [base] is where the playlist's
 * addresses resolve, the address FFmpeg asked for after any redirect.
 */
internal class PlaylistDates(
    private val upstream: MediaIo,
    private val address: String,
    private val base: String,
    private val times: HlsTimeOfDay,
    /** Reads the whole text of a playlist as it ends, and may refuse it by throwing. */
    private val check: (String) -> Unit = {},
) : MediaIo {
    /** The text so far while it may be a playlist, and null once it is not or once it was read. */
    private var copy: ByteArray? = ByteArray(0)
    private var copied = 0

    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable
    override val location: String? get() = upstream.location
    override val contentType: String? get() = upstream.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(into, offset, length)
        val kept = copy ?: return count
        when {
            count < 0 -> {
                copy = null
                val text = kept.decodeToString(0, copied)
                check(text)
                times.read(address, text, base)
            }
            count > 0 -> keep(kept, into, offset, count)
        }
        return count
    }

    private fun keep(kept: ByteArray, from: ByteArray, offset: Int, count: Int) {
        if (copied < HLS_SNIFF_BYTES) {
            // Decided on the first bytes, so a segment costs a copy of those alone.
            val head = ByteArray(minOf(HLS_SNIFF_BYTES, copied + count))
            kept.copyInto(head, 0, 0, copied)
            from.copyInto(head, copied, offset, offset + head.size - copied)
            if (head.size == HLS_SNIFF_BYTES && !startsLikeHls(head)) {
                copy = null
                return
            }
        }
        if (copied + count > MAX_PLAYLIST_BYTES) {
            copy = null
            return
        }
        val target = if (copied + count > kept.size) kept.copyOf(maxOf(copied + count, kept.size * 2, 4_096)) else kept
        from.copyInto(target, copied, offset, offset + count)
        copied += count
        copy = target
    }

    override suspend fun seek(position: Long) {
        // FFmpeg reads a playlist from its start to its end; a reader it moves is not one.
        if (position != copied.toLong()) copy = null
        upstream.seek(position)
    }

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

    /** A playlist the stream named that is protected by digital rights management, once one was read (#624). */
    private val protectedBy = atomic<PlaybackException?>(null)

    /** The refusal of a protected playlist, which the open and the reads throw in place of what FFmpeg made of it. */
    val refusal: PlaybackException? get() = protectedBy.value

    /** Records [refusal] for the stream, and answers it for the read to throw. */
    fun refuse(refusal: PlaybackException): PlaybackException {
        protectedBy.compareAndSet(null, refusal)
        return protectedBy.value ?: refusal
    }
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
        refusal?.let { return it }
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

/**
 * The refusal of an HLS playlist protected by digital rights management, or null for one this
 * player plays (#624). A key line refuses when its method is `SAMPLE-AES-CTR`, which FFmpeg reads
 * as no encryption at all, when its `KEYFORMAT` is not `identity`, as Widevine, PlayReady and
 * FairPlay write theirs, or when its key is at an address of another kind than http, https or
 * data, as FairPlay's `skd:` is. FFmpeg keeps the last key line of a segment whatever its format, so
 * one protected line is enough to refuse, even beside one it could read.
 */
internal fun hlsDrmRefusal(text: String, label: String): PlaybackException? {
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) continue
        val attributes = parseHlsAttributes(line.substringAfter(':'))
        val method = attributes["METHOD"]?.uppercase() ?: continue
        if (method == "NONE") continue
        val format = attributes["KEYFORMAT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "identity"
        val uri = attributes["URI"].orEmpty()
        val scheme = uri.substringBefore(':', "").lowercase().takeIf { ':' in uri && it.all { c -> c.isLetterOrDigit() || c in "+-." } }
        val system = when {
            !format.equals("identity", ignoreCase = true) -> drmSystemOf(format)
            scheme != null && scheme !in setOf("http", "https", "data") -> if (scheme == "skd") "FairPlay" else "a key at a $scheme: address"
            method == "SAMPLE-AES-CTR" -> "SAMPLE-AES-CTR"
            else -> null
        } ?: continue
        return PlaybackException(PlaybackError.DrmUnsupported(label, system))
    }
    return null
}

/** The protection a `KEYFORMAT` names, by its common name where it has one. */
private fun drmSystemOf(format: String): String = when (format.lowercase()) {
    "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" -> "Widevine"
    "com.apple.streamingkeydelivery" -> "FairPlay"
    "com.microsoft.playready", "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" -> "PlayReady"
    else -> format
}
