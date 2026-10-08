package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.Playlists
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Moving an HLS stream to another variant without opening it again (#464).
 *
 * FFmpeg reads the playlist of a stream that has ended once and never again, so a playlist that
 * named another variant's segments later would not be seen. The master playlist FFmpeg is given
 * therefore names one variant at an address of the player's own, and the playlist served there
 * names every segment at such an address too, by its sequence number. Which variant's bytes a
 * segment address gives is decided when FFmpeg asks for it. RFC 8216, section 6.2.4, has the
 * variants of a stream hold the same content on the same timestamps, so the reader goes on as if
 * nothing changed, and an MPEG-TS decoder takes the new picture size from the parameter sets each
 * segment starts with.
 */

/** One segment of a media playlist: where its bytes are, when it plays, and what must match to stand in for another. */
internal class HlsSegment(
    val sequence: Long,
    val address: String,
    /** Where the segment starts in [address], which is not zero for a byte range. */
    val offset: Long,
    /** How many bytes the segment has, or null for all of [address]. */
    val length: Long?,
    val durationMicros: Long,
    /** The time from the playlist's first segment to this one. */
    val startMicros: Long,
    val dateMicros: Long?,
    val discontinuity: Boolean,
    /** The `EXT-X-KEY` attributes that decrypt the segment, its address resolved, or empty for none. */
    val key: String,
)

/** The initialization a playlist's `EXT-X-MAP` names. */
internal class HlsInit(val address: String, val offset: Long, val length: Long?)

/** A media playlist the variant switch understands, and the text FFmpeg reads in its place. */
internal class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val init: HlsInit?,
    val ended: Boolean,
    /** The playlist with every segment at an address of the switch, see [HlsVariantSwitch]. */
    val served: String,
) {
    val encrypted: Boolean get() = segments.any { it.key.isNotEmpty() }

    fun at(sequence: Long): HlsSegment? {
        val first = segments.firstOrNull()?.sequence ?: return null
        return segments.getOrNull((sequence - first).toInt())?.takeIf { it.sequence == sequence }
    }
}

/**
 * Reads the media playlist [text], whose addresses resolve against [base], or answers null when
 * it is no media playlist or uses something the switch does not serve: variables (`EXT-X-DEFINE`
 * or a `{$name}`), more than one `EXT-X-MAP`, a segment before the `EXT-X-MAP`, or a key method other than
 * AES-128. Such a playlist reaches FFmpeg as the server sent it, and a variant change opens the
 * stream again.
 */
internal fun readSwitchablePlaylist(text: String, base: String): HlsMediaPlaylist? {
    // A variable may come from the master or the address, which only FFmpeg puts in.
    if ("{$" in text) return null
    var started = false
    var sequence = 0L
    var duration = 0L
    var date: Long? = null
    var carried: Long? = null
    var discontinuity = false
    var ended = false
    var key = ""
    var init: HlsInit? = null
    var range: Pair<Long, Long?>? = null
    var start = 0L
    // Where the last byte range of each address ended, for a range that states no offset.
    val rangeEnds = HashMap<String, Long>()
    val segments = ArrayList<HlsSegment>()
    val served = StringBuilder(text.length + 256)
    for (raw in text.removePrefix("﻿").lineSequence()) {
        val line = raw.trim()
        if (!started) {
            if (line.isEmpty()) continue
            if (!line.startsWith("#EXTM3U")) return null
            started = true
            served.append(line).append('\n')
            continue
        }
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-DEFINE") -> return null
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                if (segments.isNotEmpty()) return null
                sequence = line.substringAfter(':').trim().toLongOrNull() ?: return null
                served.append(line).append('\n')
            }
            line.startsWith("#EXTINF:") -> {
                val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                duration = seconds?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1_000_000).toLong() } ?: return null
                served.append(line).append('\n')
            }
            line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                date = parseProgramDateTimeMicros(line.substringAfter(':'))
                served.append(line).append('\n')
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> range = parseByteRange(line.substringAfter(':')) ?: return null
            line.startsWith("#EXT-X-KEY:") -> {
                val attributes = parseHlsAttributes(line.substringAfter(':'))
                val method = attributes["METHOD"]
                if (method != "NONE" && method != "AES-128") return null
                val uri = attributes["URI"]?.let { Playlists.resolve(base, it) }
                key = if (method == "NONE") "" else (attributes - "URI").entries.joinToString(",") { "${it.key}=${it.value}" } + ",URI=$uri"
                served.append(if (uri == null) line else withUri(line, uri)).append('\n')
            }
            line.startsWith("#EXT-X-MAP:") -> {
                if (init != null || segments.isNotEmpty()) return null
                val attributes = parseHlsAttributes(line.substringAfter(':'))
                val address = Playlists.resolve(base, attributes["URI"] ?: return null)
                val bytes = attributes["BYTERANGE"]?.let { parseByteRange(it) ?: return null }
                init = HlsInit(address, bytes?.second ?: 0L, bytes?.first)
                served.append("#EXT-X-MAP:URI=\"").append(HlsVariantSwitch.INIT).append(extensionOf(address)).append("\"\n")
            }
            line == "#EXT-X-DISCONTINUITY" -> {
                discontinuity = true
                served.append(line).append('\n')
            }
            line == "#EXT-X-ENDLIST" -> {
                ended = true
                served.append(line).append('\n')
            }
            line.startsWith("#") -> served.append(line).append('\n')
            else -> {
                val address = Playlists.resolve(base, line)
                val bytes = range
                val offset = if (bytes == null) 0L else bytes.second ?: rangeEnds[address] ?: 0L
                if (bytes != null) rangeEnds[address] = offset + bytes.first
                val ownDate = date ?: carried.takeIf { !discontinuity }
                segments += HlsSegment(sequence, address, offset, bytes?.first, duration, start, ownDate, discontinuity, key)
                served.append(HlsVariantSwitch.SEGMENT).append(sequence).append(extensionOf(address)).append('\n')
                carried = ownDate?.plus(duration)
                start += duration
                sequence++
                duration = 0L
                date = null
                discontinuity = false
                range = null
            }
        }
    }
    if (!started) return null
    return HlsMediaPlaylist(segments, init, ended, served.toString())
}

/** `n[@o]` of an `EXT-X-BYTERANGE`: the length, and the offset when it is stated. */
private fun parseByteRange(value: String): Pair<Long, Long?>? {
    val length = value.substringBefore('@').trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
    if ('@' !in value) return length to null
    val offset = value.substringAfter('@').trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return length to offset
}

/** [line], an `EXT-X-KEY` tag, with its address written as [uri]. */
private fun withUri(line: String, uri: String): String {
    val at = line.indexOf("URI=\"")
    if (at < 0) return line
    val close = line.indexOf('"', at + 5)
    if (close < 0) return line
    return line.substring(0, at + 5) + uri + line.substring(close)
}

/** The file extension of [address] with its dot, which FFmpeg's extension check may look at, or nothing. */
private fun extensionOf(address: String): String {
    val name = address.substringBefore('#').substringBefore('?').substringAfterLast('/')
    val extension = name.substringAfterLast('.', "")
    return if (extension.length in 1..5 && extension.all { it.isLetterOrDigit() }) ".$extension" else ""
}

/**
 * True when a reader that plays [from] can go on with the segments of [to]: the same segments by
 * number, each as long, cut and encrypted the same way. An ended playlist must match whole. A
 * live one must match where the two overlap, and by date too where both state one.
 */
internal fun sameSegments(from: HlsMediaPlaylist, to: HlsMediaPlaylist): Boolean {
    if (from.ended != to.ended || (from.init == null) != (to.init == null)) return false
    if (from.segments.isEmpty() || to.segments.isEmpty()) return false
    if (from.ended && (from.segments.size != to.segments.size || from.segments[0].sequence != to.segments[0].sequence)) return false
    var shared = 0
    for (segment in from.segments) {
        val other = to.at(segment.sequence) ?: continue
        shared++
        if (abs(segment.durationMicros - other.durationMicros) > SEGMENT_TOLERANCE_MICROS) return false
        if (from.ended && abs(segment.startMicros - other.startMicros) > SEGMENT_TOLERANCE_MICROS) return false
        if (segment.discontinuity != other.discontinuity || segment.key != other.key) return false
        if (segment.dateMicros != null && other.dateMicros != null &&
            abs(segment.dateMicros - other.dateMicros) > SEGMENT_TOLERANCE_MICROS
        ) {
            return false
        }
    }
    return shared > 0
}

/** How far two variants' segment boundaries may lie apart: under two frames at 30 frames a second. */
private const val SEGMENT_TOLERANCE_MICROS = 50_000L

/**
 * Serves one variant of a master playlist to FFmpeg at addresses of its own, and moves the stream
 * to another variant between two segments. See the note at the top of this file.
 *
 * [addresses] are the variants' playlists, resolved, in the master's order, and [traits] their
 * `EXT-X-STREAM-INF` tags. [choose] answers the variant the player would open by itself.
 * [openAddress] opens an address the stream names.
 */
internal class HlsVariantSwitch(
    private val addresses: List<String>,
    private val traits: List<HlsVariant>,
    initial: Int,
    private val choose: () -> Int,
    private val openAddress: suspend (String) -> MediaIo?,
) {
    private val lock = Mutex()
    private val target = atomic(initial)

    /** True when the first playlist was not understood, so FFmpeg read it as the server sent it. */
    private var passedThrough = false

    /** The newest playlist read of each variant. */
    private val loaded = arrayOfNulls<Loaded>(addresses.size)

    /** The playlist FFmpeg read last. */
    private var listed: Loaded? = null

    /** The initialization FFmpeg read first, which its MP4 reader keeps for the whole stream. */
    private var init: HlsInit? = null

    private class Loaded(val variant: Int, val playlist: HlsMediaPlaylist)

    /** The variant whose segments FFmpeg is given from the next one it asks for. */
    val selected: Int get() = target.value

    /** True for an address this switch serves. */
    fun owns(address: String): Boolean = address.startsWith(ROOT)

    /** Opens [address], one of this switch's own, or answers null when it names nothing. */
    suspend fun open(address: String): MediaIo? = when {
        address == PLAYLIST -> playlist()
        address.startsWith(INIT) -> lock.withLock { init }?.let { slice(openAddress(it.address), it.offset, it.length) }
        address.startsWith(SEGMENT) -> segment(address)
        else -> null
    }

    /**
     * The address of the stream that [address], one of this switch's own, stands for now, for a
     * message a person reads. [address] itself when it stands for nothing.
     */
    suspend fun nameOf(address: String): String = lock.withLock {
        when {
            address == PLAYLIST -> addresses[target.value]
            address.startsWith(INIT) -> init?.address
            address.startsWith(SEGMENT) -> sequenceOf(address)?.let(::find)?.address
            else -> null
        } ?: address
    }

    /**
     * Moves the stream to the variant at [index], or to the one the player would choose for null.
     * True when the next segment FFmpeg asks for is that variant's. False when the stream must
     * open again for it: the variants do not share their segments, their codecs or their sound
     * differ, or the playlist is one this switch does not serve.
     */
    suspend fun request(index: Int?): Boolean = lock.withLock {
        val current = listed ?: return false
        if (passedThrough) return false
        val wanted = index ?: choose()
        if (wanted !in addresses.indices) return false
        if (wanted == target.value) return true
        if (!sameKind(traits[target.value], traits[wanted])) return false
        val next = try {
            load(wanted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        } ?: return false
        // FFmpeg's MP4 reader keeps the first initialization, which another variant's fragments do not fit.
        if (current.playlist.init != null) return false
        if (!sameSegments(current.playlist, next.playlist)) return false
        loaded[wanted] = next
        target.value = wanted
        true
    }

    private suspend fun playlist(): MediaIo? = lock.withLock {
        val variant = target.value
        val address = addresses[variant]
        val reader = openAddress(address) ?: return null
        if (passedThrough) return reader
        val location: String
        val bytes = try {
            location = reader.location ?: address
            readPlaylist(reader, address)
        } finally {
            reader.close()
        }
        val playlist = readSwitchablePlaylist(bytes.decodeToString(), location)
        if (playlist == null) {
            // FFmpeg holds addresses of this switch once it read one playlist from it.
            check(listed == null) { "the playlist at $address changed into one the variant switch cannot serve" }
            passedThrough = true
            return BytesMediaIo(bytes, location)
        }
        val read = Loaded(variant, playlist)
        loaded[variant] = read
        listed = read
        if (init == null) init = playlist.init
        BytesMediaIo(playlist.served.encodeToByteArray(), PLAYLIST)
    }

    /** Reads the playlist of [variant], or null when it is not one this switch serves. */
    private suspend fun load(variant: Int): Loaded? {
        val address = addresses[variant]
        val reader = openAddress(address) ?: return null
        val playlist = try {
            readSwitchablePlaylist(readPlaylist(reader, address).decodeToString(), reader.location ?: address)
        } finally {
            reader.close()
        }
        return playlist?.let { Loaded(variant, it) }
    }

    private suspend fun segment(address: String): MediaIo? {
        val sequence = sequenceOf(address) ?: return null
        val segment = lock.withLock {
            val variant = target.value
            loaded[variant]?.playlist?.at(sequence)
                // A live variant that had not listed the segment yet when the stream moved to it.
                ?: reloaded(variant)?.playlist?.at(sequence)
                ?: find(sequence)
        } ?: return null
        return slice(openAddress(segment.address), segment.offset, segment.length)
    }

    /** The playlist of [variant] read again, kept as its newest, or null when it cannot be read now. */
    private suspend fun reloaded(variant: Int): Loaded? {
        // An ended playlist never lists more than it did.
        if (loaded[variant]?.playlist?.ended == true) return null
        val read = try {
            load(variant)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            null
        }
        return read?.also { loaded[variant] = it }
    }

    private fun sequenceOf(address: String): Long? = address.substring(SEGMENT.length).substringBefore('.').toLongOrNull()

    /** The segment [sequence] of the variant that plays, else of the playlist FFmpeg read it in. */
    private fun find(sequence: Long): HlsSegment? =
        loaded[target.value]?.playlist?.at(sequence)
            ?: listed?.playlist?.at(sequence)
            ?: loaded.firstNotNullOfOrNull { it?.playlist?.at(sequence) }

    companion object {
        /** A host no server has: RFC 2606 keeps `.invalid` out of the DNS. */
        const val ROOT: String = "https://kite-hls.invalid/"
        const val PLAYLIST: String = "${ROOT}variant.m3u8"
        const val SEGMENT: String = "${ROOT}segment/"
        const val INIT: String = "${ROOT}init"

        /**
         * True when a decoder that plays [from] can take [to] in band: both have a picture or
         * neither, in the same range and codec, and both take their sound from the same place.
         */
        fun sameKind(from: HlsVariant, to: HlsVariant): Boolean =
            from.audioOnly == to.audioOnly && from.hdr == to.hdr && from.dolbyVisionOnly == to.dolbyVisionOnly &&
                from.videoCodec == to.videoCodec && (from.attributes["AUDIO"] == null) == (to.attributes["AUDIO"] == null)
    }
}

/** [upstream] from [offset] on for [length] bytes, or all of it. Null stays null. */
private suspend fun slice(upstream: MediaIo?, offset: Long, length: Long?): MediaIo? {
    if (upstream == null || (offset == 0L && length == null)) return upstream
    try {
        if (offset != 0L) upstream.seek(offset)
    } catch (failure: Throwable) {
        upstream.close()
        throw failure
    }
    return SliceMediaIo(upstream, offset, length)
}

/** A window of [upstream], already at [offset]. Closing it closes [upstream]. */
private class SliceMediaIo(private val upstream: MediaIo, private val offset: Long, private val length: Long?) : MediaIo {
    private var position = 0L
    override val size: Long? get() = length ?: upstream.size?.let { (it - offset).coerceAtLeast(0) }
    override val seekable: Boolean get() = upstream.seekable
    override val location: String? get() = upstream.location
    override val contentType: String? get() = upstream.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        val left = this.length?.let { it - position }
        if (left != null && left <= 0) return -1
        val count = upstream.read(into, offset, if (left == null) length else minOf(length.toLong(), left).toInt())
        if (count > 0) position += count
        return count
    }

    override suspend fun seek(position: Long) {
        upstream.seek(offset + position)
        this.position = position
    }

    override fun networkBitsPerSecond(): Long? = upstream.networkBitsPerSecond()

    override fun close() = upstream.close()
}

/** Bytes already in memory, which say they came from [location]. */
internal class BytesMediaIo(private val bytes: ByteArray, override val location: String?) : MediaIo {
    private var position = 0
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true

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

    override fun close() = Unit
}
