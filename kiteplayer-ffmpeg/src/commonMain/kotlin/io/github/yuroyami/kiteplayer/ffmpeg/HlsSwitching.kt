package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.Playlists
import io.github.yuroyami.kiteplayer.mp4.Fmp4
import io.github.yuroyami.kiteplayer.mp4.Fmp4Rewrite
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 *
 * FFmpeg's MP4 reader keeps the first initialization it is given, so fragments of another variant
 * do not fit it. From the first move on, every MP4 segment is therefore written again for that
 * first initialization, with its own variant's parameter sets in band, as [Fmp4Rewrite] says. The
 * first variant's segments are written that way too, because a decoder that has played another
 * variant keeps that one's parameter sets until it is given others.
 *
 * A segment can only be written again from its plain bytes. So the switch decrypts the AES-128
 * segments of an MP4 stream itself, from the first one, and shows FFmpeg a playlist with no key.
 * FFmpeg still decrypts MPEG-TS segments, which are never written again.
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
    /** Where the key of [key] is, and its IV when the tag states one. */
    val keyAddress: String? = null,
    val keyIv: ByteArray? = null,
)

/**
 * The initialization a playlist's `EXT-X-MAP` names. [key], [keyAddress] and [keyIv] are those of
 * the `EXT-X-KEY` before the tag, as in [HlsSegment], and [sequence] is the number of the segment
 * after it, which is the IV of a key that states none.
 */
internal class HlsInit(
    val address: String,
    val offset: Long,
    val length: Long?,
    val key: String = "",
    val keyAddress: String? = null,
    val keyIv: ByteArray? = null,
    val sequence: Long = 0,
)

/** A media playlist the variant switch understands, and the text FFmpeg reads in its place. */
internal class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val init: HlsInit?,
    val ended: Boolean,
    /** The playlist with every segment at an address of the switch, see [HlsVariantSwitch]. */
    val served: String,
) {
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
 *
 * The served text of a playlist with an `EXT-X-MAP` names no key: the switch decrypts those
 * segments itself, because it must hold their plain bytes to write them again (#565).
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
    var keyAddress: String? = null
    var keyIv: ByteArray? = null
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
                keyAddress = uri.takeIf { method != "NONE" }
                keyIv = attributes["IV"].takeIf { method != "NONE" }?.let { parseIv(it) ?: return null }
                // Marked, so that the line can be left out once the playlist shows an `EXT-X-MAP`.
                served.append(KEY_MARK).append(if (uri == null) line else withUri(line, uri)).append('\n')
            }
            line.startsWith("#EXT-X-MAP:") -> {
                if (init != null || segments.isNotEmpty()) return null
                val attributes = parseHlsAttributes(line.substringAfter(':'))
                val address = Playlists.resolve(base, attributes["URI"] ?: return null)
                val bytes = attributes["BYTERANGE"]?.let { parseByteRange(it) ?: return null }
                init = HlsInit(address, bytes?.second ?: 0L, bytes?.first, key, keyAddress, keyIv, sequence)
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
                segments += HlsSegment(sequence, address, offset, bytes?.first, duration, start, ownDate, discontinuity, key, keyAddress, keyIv)
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
    val text = served.toString()
    val shown = if (init == null) {
        text.replace(KEY_MARK.toString(), "")
    } else {
        text.lineSequence().filter { !it.startsWith(KEY_MARK) }.joinToString("\n")
    }
    return HlsMediaPlaylist(segments, init, ended, shown)
}

/** Starts an `EXT-X-KEY` line while a playlist is read. No playlist line holds it. */
private const val KEY_MARK = '\u0000'

/** The sixteen bytes of an `IV` attribute, `0x` and up to 32 hexadecimal digits of one number, or null for anything else. */
private fun parseIv(value: String): ByteArray? {
    if (!value.startsWith("0x", ignoreCase = true) || value.length !in 3..34) return null
    val digits = value.substring(2).padStart(32, '0')
    if (digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return ByteArray(16) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
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
 * number, each as long and cut the same way, and encrypted the same way where FFmpeg decrypts
 * them, which is in a playlist with no `EXT-X-MAP`. An ended playlist must match whole. A live one
 * must match where the two overlap, and by date too where both state one.
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
        if (segment.discontinuity != other.discontinuity) return false
        if (from.init == null && segment.key != other.key) return false
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

    /** The tracks of [init], read at the first move of an MP4 stream. */
    private var baseTracks: List<Fmp4.Track>? = null

    /** How each variant's MP4 segments are written for [init], for the variants a move has checked. */
    private val plans = arrayOfNulls<List<Fmp4Rewrite.Plan>>(addresses.size)

    /** True once an MP4 stream has moved, from when every segment is written again. */
    private var rewrites = false

    /** The segment served last and its variant, or null when it was served as it is. */
    private var written: Pair<Long, Int>? = null

    /** The keys read so far, by address, under a lock of their own: a move reads one while it holds [lock]. */
    private val keys = HashMap<String, ByteArray>()
    private val keyLock = Mutex()

    private class Loaded(val variant: Int, val playlist: HlsMediaPlaylist)

    /** The variant whose segments FFmpeg is given from the next one it asks for. */
    val selected: Int get() = target.value

    /** True for an address this switch serves. */
    fun owns(address: String): Boolean = address.startsWith(ROOT)

    /** Opens [address], one of this switch's own, or answers null when it names nothing. */
    suspend fun open(address: String): MediaIo? = when {
        address == PLAYLIST -> playlist()
        address.startsWith(INIT) -> lock.withLock { init }?.let { opened(it) }
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
            address.startsWith(SEGMENT) -> sequenceOf(address)?.let(::find)?.second?.address
            else -> null
        } ?: address
    }

    /**
     * Moves the stream to the variant at [index], or to the one the player would choose for null.
     * True when the next segment FFmpeg asks for is that variant's. False when the stream must
     * open again for it: the variants do not share their segments, their codecs or their sound
     * differ, the playlist is one this switch does not serve, or the segments are MP4 in another
     * codec than H.264, HEVC, AV1 and VP9, or laid out in other tracks.
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
        if (!sameSegments(current.playlist, next.playlist)) return false
        if (current.playlist.init != null) {
            val fits = try {
                planned(current, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                false
            }
            if (!fits) return false
            rewrites = true
        }
        loaded[wanted] = next
        target.value = wanted
        true
    }

    /**
     * Makes the plans that write the MP4 segments of [next], and of [current] when it has none
     * yet, for the first initialization. False when either cannot be written for it.
     */
    private suspend fun planned(current: Loaded, next: Loaded): Boolean {
        val base = baseTracks ?: tracksOf(init ?: return false).also { baseTracks = it }
        for (loaded in listOf(current, next)) {
            if (plans[loaded.variant] != null) continue
            val own = loaded.playlist.init ?: return false
            val tracks = if (own.address == init?.address && own.offset == init?.offset && own.length == init?.length) base else tracksOf(own)
            plans[loaded.variant] = plansFor(tracks, base) ?: return false
        }
        return true
    }

    /** The tracks of the initialization [of], read whole. */
    private suspend fun tracksOf(of: HlsInit): List<Fmp4.Track> {
        val reader = opened(of) ?: return emptyList()
        return try {
            Fmp4.tracks(readWhole(reader, MAX_INIT_BYTES, of.address))
        } finally {
            reader.close()
        }
    }

    /** A reader of the initialization [of], in plain bytes. */
    private suspend fun opened(of: HlsInit): MediaIo? {
        val reader = slice(openAddress(of.address), of.offset, of.length) ?: return null
        if (of.key.isEmpty()) return reader
        val bytes = try {
            readWhole(reader, MAX_INIT_BYTES, of.address)
        } finally {
            reader.close()
        }
        return BytesMediaIo(decrypted(bytes, of.keyAddress, of.keyIv, of.sequence, of.address) ?: return null, of.address)
    }

    /**
     * [bytes] of [name] decrypted with the key at [address] and [iv], or with the IV that
     * [sequence] is when the tag stated none (RFC 8216, section 5.2). Null when the key cannot be read.
     */
    private suspend fun decrypted(bytes: ByteArray, address: String?, iv: ByteArray?, sequence: Long, name: String): ByteArray? {
        val key = keyAt(address ?: return null) ?: return null
        val vector = iv ?: ByteArray(16) { if (it < 8) 0 else (sequence ushr (8 * (15 - it))).toByte() }
        return try {
            Aes128Cbc.decrypt(bytes, key, vector)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("$name cannot be decrypted: ${failure.message}", failure)
        }
    }

    /** The sixteen bytes of the key at [address], read once. */
    private suspend fun keyAt(address: String): ByteArray? {
        keyLock.withLock { keys[address] }?.let { return it }
        val reader = openAddress(address) ?: return null
        val key = try {
            readWhole(reader, MAX_KEY_BYTES, address)
        } finally {
            reader.close()
        }
        check(key.size == 16) { "the key at $address has ${key.size} bytes, and an AES-128 key has 16" }
        keyLock.withLock { keys[address] = key }
        return key
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
        var plan: List<Fmp4Rewrite.Plan>? = null
        var encrypted = false
        val segment = lock.withLock {
            val variant = target.value
            val found = loaded[variant]?.playlist?.at(sequence)?.let { variant to it }
                // A live variant that had not listed the segment yet when the stream moved to it.
                ?: reloaded(variant)?.playlist?.at(sequence)?.let { variant to it }
                ?: find(sequence)
                ?: return null
            if (rewrites) {
                // Anything but the segment after the last one written, of the same variant, is a join.
                val joined = written != (sequence - 1 to found.first)
                plan = (plans[found.first] ?: return null).map { Fmp4Rewrite.Plan(it.source, it.target, it.shiftMicros, it.endMicros, joined) }
                written = sequence to found.first
            }
            // FFmpeg decrypts a segment of a playlist with no initialization, whose key it was shown.
            encrypted = init != null && found.second.key.isNotEmpty()
            found.second
        }
        val reader = slice(openAddress(segment.address), segment.offset, segment.length) ?: return null
        val plans = plan
        if (plans == null && !encrypted) return reader
        val bytes = try {
            readWhole(reader, MAX_SEGMENT_BYTES, segment.address)
        } finally {
            reader.close()
        }
        val plain = if (encrypted) {
            decrypted(bytes, segment.keyAddress, segment.keyIv, segment.sequence, segment.address) ?: return null
        } else {
            bytes
        }
        return BytesMediaIo(if (plans == null) plain else Fmp4Rewrite.rewrite(plain, plans), segment.address)
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

    /** The segment [sequence] of the variant that plays, else of the playlist FFmpeg read it in, with its variant. */
    private fun find(sequence: Long): Pair<Int, HlsSegment>? =
        (sequenceOf(loaded[target.value]) + sequenceOf(listed) + loaded.asSequence())
            .firstNotNullOfOrNull { read -> read?.playlist?.at(sequence)?.let { read.variant to it } }

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

        /**
         * How segments with the tracks [own] are written for a reader that holds [base]: each
         * picture and sound track for the one of its kind at the same place. Null when the two
         * hold other kinds of track, or a different number of one kind, or a pair that a decoder
         * cannot take in band.
         */
        fun plansFor(own: List<Fmp4.Track>, base: List<Fmp4.Track>): List<Fmp4Rewrite.Plan>? {
            if (base.isEmpty() || own.size != base.size) return null
            if ((own + base).any { it.handler != "vide" && it.handler != "soun" }) return null
            val out = ArrayList<Fmp4Rewrite.Plan>()
            for (kind in listOf("vide", "soun")) {
                val from = own.filter { it.handler == kind }
                val to = base.filter { it.handler == kind }
                if (from.size != to.size) return null
                for (i in from.indices) {
                    if (!Fmp4Rewrite.joins(from[i], to[i])) return null
                    // A decoder takes another picture size from parameter sets written in band, which
                    // H.264 and HEVC have, or from a key frame that states it, as in AV1 and VP9.
                    if (kind == "vide" && Fmp4Rewrite.inBandParameterSets(from[i], to[i]) == null &&
                        !Fmp4Rewrite.describedByKeyFrames(from[i], to[i])
                    ) return null
                    out += Fmp4Rewrite.Plan(from[i], to[i], shiftMicros = 0, endMicros = null)
                }
            }
            // The order of the first initialization's tracks, which is the order its fragments had.
            return out.sortedBy { plan -> base.indexOf(plan.target) }
        }

        /** The most an initialization may hold. One is a few kilobytes. */
        private const val MAX_INIT_BYTES: Long = 4L shl 20

        /** A key is sixteen bytes. */
        private const val MAX_KEY_BYTES: Long = 16

        /** The most one segment may hold to be written again, as much as the DASH path reads. */
        private const val MAX_SEGMENT_BYTES: Long = 64L shl 20
    }
}

/** All of [reader], or a failure when it holds more than [limit] bytes. [name] is for the message. */
private suspend fun readWhole(reader: MediaIo, limit: Long, name: String): ByteArray {
    val declared = reader.size
    require(declared == null || declared <= limit) { "$name holds $declared bytes, and at most $limit are read whole" }
    // One byte more than a declared size, so the end shows without a copy.
    var bytes = ByteArray(if (declared != null) declared.toInt() + 1 else 64 shl 10)
    var filled = 0
    while (true) {
        if (filled == bytes.size) {
            require(filled <= limit) { "$name holds more than $limit bytes, and at most that many are read whole" }
            bytes = bytes.copyOf(minOf(bytes.size.toLong() * 2, limit + 1).toInt())
        }
        val count = reader.read(bytes, filled, bytes.size - filled)
        when {
            count < 0 -> break
            // Nothing yet, and more may come.
            count == 0 -> delay(1)
            else -> filled += count
        }
    }
    require(filled <= limit) { "$name holds more than $limit bytes, and at most that many are read whole" }
    return bytes.copyOf(filled)
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
