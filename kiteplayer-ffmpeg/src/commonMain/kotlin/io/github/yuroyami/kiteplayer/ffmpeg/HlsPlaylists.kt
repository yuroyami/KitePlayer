package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.VariantFit
import io.github.yuroyami.kiteplayer.VideoSize

/**
 * The text work of the HLS path, with no FFmpeg and no network: recognising a playlist, keeping
 * one variant of a master playlist, and resolving the addresses a playlist names. RFC 8216 is the
 * HLS specification, and RFC 3986 defines how a relative address resolves.
 */

/** The media type that RFC 8216 gives an HLS playlist. */
internal const val HLS_MEDIA_TYPE: String = "application/vnd.apple.mpegurl"

/** RFC 8216, section 4, names the first two. FFmpeg accepts the other two, and servers send them. */
private val HLS_MEDIA_TYPES = setOf(HLS_MEDIA_TYPE, "audio/mpegurl", "application/x-mpegurl", "audio/x-mpegurl")

/**
 * True when the bytes are an HLS playlist by what is known before reading them: a [formatHint] of
 * `hls`, an HLS [contentType], or an [address] whose path ends in `.m3u8` or `.m3u`. Any other
 * format hint says the bytes are something else.
 */
internal fun looksLikeHls(formatHint: String?, contentType: String?, address: String?): Boolean {
    if (formatHint != null) return formatHint == "hls" || formatHint == "applehttp"
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type in HLS_MEDIA_TYPES) return true
    val path = address?.substringBefore('#')?.substringBefore('?')?.lowercase() ?: return false
    return path.endsWith(".m3u8") || path.endsWith(".m3u")
}

/** The most bytes read from the start of a reader to recognise a playlist by [startsLikeHls]. */
internal const val HLS_SNIFF_BYTES: Int = 64

/**
 * True when the first [length] bytes of [head] start an HLS playlist: the `#EXTM3U` tag, which RFC
 * 8216, section 4.3.1.1, puts on the first line, after an optional UTF-8 byte order mark and
 * whitespace. This is what recognises a playlist that nothing else marks, behind an address with
 * no extension that a server sends as plain text or as bytes.
 */
internal fun startsLikeHls(head: ByteArray, length: Int = head.size): Boolean {
    var at = 0
    if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) at = 3
    while (at < length && head[at].toInt().toChar() in " \t\r\n") at++
    if (length - at < HLS_TAG.size) return false
    for (i in HLS_TAG.indices) if (head[at + i] != HLS_TAG[i]) return false
    return true
}

private val HLS_TAG = "#EXTM3U".encodeToByteArray()

/**
 * True when nothing about [contentType] rules out a playlist, so the bytes are worth a look: no
 * type, or one that is not audio, video or an image. A server that sends an HLS playlist sends it
 * as one of the HLS types, as text, or as bytes.
 */
internal fun mayBeAPlaylist(contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
    return !(type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/"))
}

/**
 * The attributes of an HLS tag, as RFC 8216, section 4.2, defines an attribute list: `NAME=value`
 * pairs separated by commas, where a quoted value may hold commas. Quotes are removed.
 */
internal fun parseHlsAttributes(list: String): Map<String, String> {
    val attributes = LinkedHashMap<String, String>()
    var at = 0
    while (at < list.length) {
        val equals = list.indexOf('=', at)
        if (equals < 0) break
        val name = list.substring(at, equals).trim()
        val valueStart = equals + 1
        val value: String
        val next: Int
        if (valueStart < list.length && list[valueStart] == '"') {
            val close = list.indexOf('"', valueStart + 1).let { if (it < 0) list.length else it }
            value = list.substring(valueStart + 1, close)
            next = list.indexOf(',', close).let { if (it < 0) list.length else it + 1 }
        } else {
            val comma = list.indexOf(',', valueStart).let { if (it < 0) list.length else it }
            value = list.substring(valueStart, comma).trim()
            next = comma + 1
        }
        if (name.isNotEmpty()) attributes[name] = value
        at = next
    }
    return attributes
}

/** One variant of a master playlist: its `EXT-X-STREAM-INF` attributes and the lines it uses. */
internal class HlsVariant(val tagLine: Int, val uriLine: Int, val attributes: Map<String, String>) {
    /** The peak bitrate in bits per second, which RFC 8216 requires every variant to state. */
    val bandwidth: Long = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L

    /** The picture height in pixels, or null when the variant does not state its size. */
    val height: Int? = attributes["RESOLUTION"]?.substringAfter('x', "")?.trim()?.toIntOrNull()

    /** The picture width in pixels, or null when the variant does not state its size. */
    val width: Int? = attributes["RESOLUTION"]?.substringBefore('x', "")?.trim()?.toIntOrNull()

    /** The picture size, or null when the variant does not state both sides. */
    val size: VideoSize? = if (width != null && height != null && width > 0 && height > 0) VideoSize(width, height) else null

    /** The highest frame rate, or null when the variant does not state it. */
    val frameRate: Double? = attributes["FRAME-RATE"]?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

    private val codecs: List<String> = attributes["CODECS"]?.split(',')?.map { it.trim().lowercase() }.orEmpty()

    /** True when the variant names its codecs and none of them is a video codec. */
    val audioOnly: Boolean = height == null && codecs.isNotEmpty() && codecs.none { it.substringBefore('.') in VIDEO_CODECS }

    /** True for a PQ or HLG picture. */
    val hdr: Boolean = attributes["VIDEO-RANGE"].let { it == "PQ" || it == "HLG" }

    /**
     * True for Dolby Vision profile 5, whose picture has no HDR10 or SDR base layer. The engine
     * composes each of its frames into HDR10 on the processor, which costs tens of milliseconds a
     * frame at 1080p and more than a phone has at 4K, so it plays only when nothing else is offered.
     */
    val dolbyVisionOnly: Boolean = codecs.any { it.startsWith("dvh1.05") || it.startsWith("dvhe.05") }

    private companion object {
        /** The RFC 6381 codec names of the video formats a variant may carry. */
        val VIDEO_CODECS = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "dva1", "dvav", "av01", "vp09", "vp08", "mp4v")
    }
}

/**
 * Chooses the variant to play. A variant with a picture wins over one with sound only, one without
 * Dolby Vision profile 5 over one with it, because profile 5 is composed on the processor, and HDR
 * over SDR when [fit] says the output shows HDR, SDR over HDR otherwise (#447). Among the rest, the
 * variant with the highest bitrate within [maxBitrate], [maxVideoHeight] and [fit]'s pixel cap
 * plays, and the first one listed wins a tie.
 * When none fits, the one with the lowest bitrate plays.
 */
internal fun chooseHlsVariant(
    variants: List<HlsVariant>,
    maxBitrate: Long?,
    maxVideoHeight: Int?,
    fit: VariantFit? = null,
): HlsVariant {
    require(variants.isNotEmpty()) { "a master playlist has at least one variant" }
    var pool = variants
    fun prefer(keep: (HlsVariant) -> Boolean) {
        pool.filter(keep).takeIf { it.isNotEmpty() }?.let { pool = it }
    }
    prefer { !it.audioOnly }
    prefer { !it.dolbyVisionOnly }
    val showsHdr = fit?.showsHdr == true
    prefer { it.hdr == showsHdr }
    val pixelCap = fit?.pixelCap(pool.mapNotNull { it.size })
    val fitting = pool.filter { variant ->
        (maxBitrate == null || variant.bandwidth <= maxBitrate) &&
            (maxVideoHeight == null || variant.height == null || variant.height <= maxVideoHeight) &&
            (pixelCap == null || variant.size == null || variant.size.width.toLong() * variant.size.height <= pixelCap)
    }
    if (fitting.isEmpty()) return pool.minBy { it.bandwidth }
    return fitting.maxWith(compareBy<HlsVariant> { it.bandwidth }.thenBy { it.height ?: 0 })
}

/**
 * A master playlist with one variant kept: the playlist FFmpeg reads, every variant it offered in
 * playlist order, the place of the kept one in that list, and the [backups] of what it kept.
 */
internal class HlsMaster(
    val playlist: String,
    val variants: List<HlsVariant>,
    val chosen: Int,
    val backups: List<HlsBackup> = emptyList(),
)

/**
 * A playlist the kept master names, by its address as written, and the playlists of its backup
 * variants that stand in for it, in the master's order (#440).
 */
internal class HlsBackup(val primary: String, val alternatives: List<String>)

/**
 * The backups of [chosen] among [variants] (#440): the variants that RFC 8216 calls redundant,
 * every attribute the same but the address. The rendition groups they name may
 * differ, as a backup on another network names its own, and each rendition of [chosen]'s groups
 * then has for backup the rendition of the same type, language and name in theirs. Content
 * steering's `PATHWAY-ID` names the network, so it differs between backups too.
 */
private fun backupsOf(chosen: HlsVariant, variants: List<HlsVariant>, lines: List<String>): List<HlsBackup> {
    val groupTypes = listOf("AUDIO", "VIDEO", "SUBTITLES")
    val ignored = groupTypes.toSet() + "CLOSED-CAPTIONS" + "PATHWAY-ID"
    fun identity(variant: HlsVariant) = variant.attributes.filterKeys { it !in ignored }
    val backups = variants.filter { it !== chosen && identity(it) == identity(chosen) && lines[it.uriLine].trim() != lines[chosen.uriLine].trim() }
    if (backups.isEmpty()) return emptyList()
    val out = mutableListOf(HlsBackup(lines[chosen.uriLine].trim(), backups.map { lines[it.uriLine].trim() }))
    val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map { parseHlsAttributes(it.substringAfter(':')) }
    for (type in groupTypes) {
        val group = chosen.attributes[type] ?: continue
        for (rendition in renditions.filter { it["TYPE"] == type && it["GROUP-ID"] == group }) {
            val uri = rendition["URI"] ?: continue
            val alternatives = backups.mapNotNull { backup ->
                val theirs = backup.attributes[type]?.takeIf { it != group } ?: return@mapNotNull null
                renditions.firstOrNull {
                    it["TYPE"] == type && it["GROUP-ID"] == theirs && it["LANGUAGE"] == rendition["LANGUAGE"] && it["NAME"] == rendition["NAME"]
                }?.get("URI")
            }.distinct()
            if (alternatives.isNotEmpty()) out += HlsBackup(uri, alternatives)
        }
    }
    return out
}

/**
 * [text] with only the chosen variant left, or null when [text] is not a master playlist. The
 * variant at [wanted] in playlist order is kept when there is one; otherwise [chooseHlsVariant]
 * chooses by [maxBitrate] and [maxVideoHeight].
 *
 * FFmpeg's HLS demuxer opens every variant of a master playlist and keeps reading each one while
 * any of its streams is in use, so a master playlist handed over whole downloads several copies of
 * the media. This keeps the header tags, the chosen `EXT-X-STREAM-INF` tag and its address, and
 * the `EXT-X-MEDIA` renditions of the groups that the chosen variant names. It drops the other
 * variants, every `EXT-X-I-FRAME-STREAM-INF` tag, and the renditions of other groups. Relative
 * addresses stay as they are, so the playlist must be read against its own address. The chosen
 * variant's backups are kept apart, in [HlsMaster.backups], for the opener to fail over to (#440).
 */
internal fun keepOneHlsVariant(
    text: String,
    maxBitrate: Long?,
    maxVideoHeight: Int?,
    wanted: Int? = null,
    fit: VariantFit? = null,
): HlsMaster? {
    val lines = text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }
    val variants = mutableListOf<HlsVariant>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            // The address is the next line that is not blank and not a comment.
            var uri = index + 1
            while (uri < lines.size && (lines[uri].isBlank() || (lines[uri].startsWith("#") && !lines[uri].startsWith("#EXT")))) uri++
            if (uri < lines.size && !lines[uri].startsWith("#")) {
                variants += HlsVariant(index, uri, parseHlsAttributes(line.substringAfter(':')))
                index = uri + 1
                continue
            }
        }
        index++
    }
    if (variants.isEmpty()) return null
    val chosen = wanted?.let(variants::getOrNull) ?: chooseHlsVariant(variants, maxBitrate, maxVideoHeight, fit)
    // The rendition group of each type that the chosen variant names, if any.
    val groups = listOf("AUDIO", "VIDEO", "SUBTITLES", "CLOSED-CAPTIONS").associateWith { chosen.attributes[it] }
    val dropped = HashSet<Int>()
    for (variant in variants) {
        if (variant !== chosen) {
            dropped += variant.tagLine
            dropped += variant.uriLine
        }
    }
    lines.forEachIndexed { at, line ->
        when {
            line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> dropped += at
            line.startsWith("#EXT-X-MEDIA:") -> {
                val rendition = parseHlsAttributes(line.substringAfter(':'))
                val wanted = groups[rendition["TYPE"]]
                if (wanted == null || wanted != rendition["GROUP-ID"]) dropped += at
            }
        }
    }
    return HlsMaster(
        lines.filterIndexed { at, _ -> at !in dropped }.joinToString("\n"),
        variants,
        variants.indexOf(chosen),
        backupsOf(chosen, variants, lines),
    )
}
