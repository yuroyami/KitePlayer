package io.github.yuroyami.kiteplayer.ffmpeg

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

    /** The highest frame rate, or null when the variant does not state it. */
    val frameRate: Double? = attributes["FRAME-RATE"]?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

    private val codecs: List<String> = attributes["CODECS"]?.split(',')?.map { it.trim().lowercase() }.orEmpty()

    /** True when the variant names its codecs and none of them is a video codec. */
    val audioOnly: Boolean = height == null && codecs.isNotEmpty() && codecs.none { it.substringBefore('.') in VIDEO_CODECS }

    /** True for a PQ or HLG picture. */
    val hdr: Boolean = attributes["VIDEO-RANGE"].let { it == "PQ" || it == "HLG" }

    /**
     * True for Dolby Vision profile 5, whose picture has no HDR10 or SDR base layer. Without Dolby
     * Vision processing its colours come out wrong, so it plays only when nothing else is offered.
     */
    val dolbyVisionOnly: Boolean = codecs.any { it.startsWith("dvh1.05") || it.startsWith("dvhe.05") }

    private companion object {
        /** The RFC 6381 codec names of the video formats a variant may carry. */
        val VIDEO_CODECS = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "dva1", "dvav", "av01", "vp09", "vp08", "mp4v")
    }
}

/**
 * Chooses the variant to play. A variant with a picture wins over one with sound only, one without
 * Dolby Vision profile 5 over one with it, and SDR over HDR, because the renderers show HDR tone
 * mapped. Among the rest, the variant with the highest bitrate within [maxBitrate] and
 * [maxVideoHeight] plays, and the first one listed wins a tie. When none fits, the one with the
 * lowest bitrate plays.
 */
internal fun chooseHlsVariant(variants: List<HlsVariant>, maxBitrate: Long?, maxVideoHeight: Int?): HlsVariant {
    require(variants.isNotEmpty()) { "a master playlist has at least one variant" }
    var pool = variants
    fun prefer(keep: (HlsVariant) -> Boolean) {
        pool.filter(keep).takeIf { it.isNotEmpty() }?.let { pool = it }
    }
    prefer { !it.audioOnly }
    prefer { !it.dolbyVisionOnly }
    prefer { !it.hdr }
    val fitting = pool.filter { variant ->
        (maxBitrate == null || variant.bandwidth <= maxBitrate) &&
            (maxVideoHeight == null || variant.height == null || variant.height <= maxVideoHeight)
    }
    if (fitting.isEmpty()) return pool.minBy { it.bandwidth }
    return fitting.maxWith(compareBy<HlsVariant> { it.bandwidth }.thenBy { it.height ?: 0 })
}

/**
 * A master playlist with one variant kept: the playlist FFmpeg reads, every variant it offered in
 * playlist order, and the place of the kept one in that list.
 */
internal class HlsMaster(val playlist: String, val variants: List<HlsVariant>, val chosen: Int)

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
 * addresses stay as they are, so the playlist must be read against its own address.
 */
internal fun keepOneHlsVariant(text: String, maxBitrate: Long?, maxVideoHeight: Int?, wanted: Int? = null): HlsMaster? {
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
    val chosen = wanted?.let(variants::getOrNull) ?: chooseHlsVariant(variants, maxBitrate, maxVideoHeight)
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
    return HlsMaster(lines.filterIndexed { at, _ -> at !in dropped }.joinToString("\n"), variants, variants.indexOf(chosen))
}

/**
 * [text] with every relative address made absolute against [base]: the address lines, and the
 * `URI` attribute of every tag that has one. A playlist that was read after a redirect needs this,
 * because FFmpeg resolves its addresses against the address it asked for, not the one that
 * answered.
 */
internal fun absoluteHlsAddresses(text: String, base: String): String =
    text.split('\n').joinToString("\n") { raw ->
        val line = raw.removeSuffix("\r")
        val ending = raw.substring(line.length)
        val rewritten = when {
            line.isBlank() -> line
            !line.startsWith("#") -> resolveUriReference(base, line.trim())
            line.startsWith("#EXT") && "URI=\"" in line -> {
                val start = line.indexOf("URI=\"") + 5
                val end = line.indexOf('"', start)
                if (end < 0) line else line.substring(0, start) + resolveUriReference(base, line.substring(start, end)) + line.substring(end)
            }
            else -> line
        }
        rewritten + ending
    }

/**
 * Resolves [reference] against [base] as RFC 3986, section 5.2, defines it. A reference with a
 * scheme of its own, such as `https:` or `data:`, comes back with its dot segments removed.
 */
internal fun resolveUriReference(base: String, reference: String): String {
    if (SCHEME.matchesAt(reference, 0)) {
        val scheme = reference.substringBefore(':')
        if (!reference.startsWith("$scheme://")) return reference
        val parts = splitUri(reference)
        return compose(parts.scheme, parts.authority, removeDotSegments(parts.path), parts.query, parts.fragment)
    }
    val baseParts = splitUri(base)
    val ref = splitUri(reference, hasScheme = false)
    return when {
        ref.authority != null ->
            compose(baseParts.scheme, ref.authority, removeDotSegments(ref.path), ref.query, ref.fragment)
        ref.path.isEmpty() ->
            compose(baseParts.scheme, baseParts.authority, baseParts.path, ref.query ?: baseParts.query, ref.fragment)
        ref.path.startsWith("/") ->
            compose(baseParts.scheme, baseParts.authority, removeDotSegments(ref.path), ref.query, ref.fragment)
        else -> {
            // RFC 3986, section 5.2.3.
            val slash = baseParts.path.lastIndexOf('/')
            val merged = when {
                baseParts.authority != null && baseParts.path.isEmpty() -> "/" + ref.path
                slash < 0 -> ref.path
                else -> baseParts.path.substring(0, slash + 1) + ref.path
            }
            compose(baseParts.scheme, baseParts.authority, removeDotSegments(merged), ref.query, ref.fragment)
        }
    }
}

private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")

private class UriParts(val scheme: String?, val authority: String?, val path: String, val query: String?, val fragment: String?)

private fun splitUri(uri: String, hasScheme: Boolean = true): UriParts {
    var rest = uri
    val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }
    rest = rest.substringBefore('#')
    val query = rest.substringAfter('?', "").takeIf { '?' in rest }
    rest = rest.substringBefore('?')
    val scheme = if (hasScheme && SCHEME.matchesAt(rest, 0)) rest.substringBefore(':') else null
    if (scheme != null) rest = rest.substringAfter(':')
    val authority = if (rest.startsWith("//")) rest.substring(2).substringBefore('/') else null
    if (authority != null) rest = rest.substring(2 + authority.length)
    return UriParts(scheme, authority, rest, query, fragment)
}

private fun compose(scheme: String?, authority: String?, path: String, query: String?, fragment: String?): String = buildString {
    if (scheme != null) append(scheme).append(':')
    if (authority != null) append("//").append(authority)
    append(path)
    if (query != null) append('?').append(query)
    if (fragment != null) append('#').append(fragment)
}

/** RFC 3986, section 5.2.4, step by step. */
private fun removeDotSegments(path: String): String {
    var input = path
    val output = StringBuilder()
    fun dropLastSegment() = output.setLength(output.lastIndexOf('/').coerceAtLeast(0))
    while (input.isNotEmpty()) {
        when {
            input.startsWith("../") -> input = input.substring(3)
            input.startsWith("./") -> input = input.substring(2)
            input.startsWith("/./") -> input = input.substring(2)
            input == "/." -> input = "/"
            input.startsWith("/../") -> {
                input = input.substring(3)
                dropLastSegment()
            }
            input == "/.." -> {
                input = "/"
                dropLastSegment()
            }
            input == "." || input == ".." -> input = ""
            else -> {
                val next = input.indexOf('/', startIndex = if (input.startsWith("/")) 1 else 0).let { if (it < 0) input.length else it }
                output.append(input, 0, next)
                input = input.substring(next)
            }
        }
    }
    return output.toString()
}
