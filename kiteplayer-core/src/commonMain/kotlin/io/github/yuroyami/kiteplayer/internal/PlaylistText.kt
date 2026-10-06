package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaItem

/** The text work of [io.github.yuroyami.kiteplayer.Playlists] (#490), with no I/O. */
internal object PlaylistText {

    fun parse(text: String, base: String): List<MediaItem>? {
        val body = text.removePrefix("\uFEFF")
        val start = body.trimStart()
        return when {
            start.startsWith("[playlist]", ignoreCase = true) -> pls(body, base)
            start.startsWith("<?xml") || start.startsWith("<playlist") -> xspf(body, base)
            else -> m3u(body, base)
        }
    }

    /**
     * An M3U list, or null for an HLS playlist, which always carries `#EXT-X-` tags, and for text
     * that is no list, such as a page of markup.
     */
    private fun m3u(text: String, base: String): List<MediaItem>? {
        val items = mutableListOf<MediaItem>()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        val headers = mutableMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-", ignoreCase = true) -> return null
                line.startsWith("#EXTINF:", ignoreCase = true) -> title = extinfTitle(line)
                line.startsWith("#EXTART:", ignoreCase = true) -> artist = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTALB:", ignoreCase = true) -> album = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOption(line.substringAfter(':'))?.let { (key, value) -> headers[key] = value }
                line.startsWith("#") -> Unit
                line.startsWith("<") -> return null
                else -> {
                    items += MediaItem(
                        uri = playlistAddress(base, line),
                        title = title,
                        artist = artist,
                        album = album,
                        headers = headers.toMap(),
                    )
                    title = null
                    artist = null
                    album = null
                    headers.clear()
                }
            }
        }
        return items.takeIf { it.isNotEmpty() }
    }

    /** The title of an `#EXTINF` line: what follows its first comma outside quotes, or null. */
    private fun extinfTitle(line: String): String? {
        var quoted = false
        for (at in line.indices) {
            when (line[at]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return line.substring(at + 1).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** The header an `#EXTVLCOPT` option names, for the two that IPTV lists carry, or null. */
    private fun vlcOption(option: String): Pair<String, String>? {
        val key = option.substringBefore('=').trim().lowercase()
        val value = option.substringAfter('=', "").trim().removeSurrounding("\"").ifEmpty { return null }
        return when (key) {
            "http-referrer", "http-referer" -> "Referer" to value
            "http-user-agent" -> "User-Agent" to value
            else -> null
        }
    }

    /** The `FileN` entries of a PLS list in their number order, with their `TitleN`, keys read without regard to case. */
    private fun pls(text: String, base: String): List<MediaItem> {
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim().lowercase()
            val value = line.substring(equals + 1).trim()
            if (value.isEmpty()) continue
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { files[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        return files.keys.sorted().map { n -> MediaItem(uri = playlistAddress(base, files.getValue(n)), title = titles[n]) }
    }

    /**
     * Each `<track>` of an XSPF list with a `<location>`, read as the well-formed XML the format
     * requires: the first location, and the title, creator and album beside it.
     */
    private fun xspf(text: String, base: String): List<MediaItem>? {
        if (!text.contains("<playlist")) return null
        val items = mutableListOf<MediaItem>()
        var at = 0
        while (true) {
            val open = TRACK_OPEN.find(text, at) ?: break
            val close = text.indexOf("</track>", open.range.last + 1)
            if (close < 0) break
            val track = text.substring(open.range.last + 1, close)
            at = close + "</track>".length
            val location = element(track, "location") ?: continue
            items += MediaItem(
                uri = playlistAddress(base, location),
                title = element(track, "title"),
                artist = element(track, "creator"),
                album = element(track, "album"),
            )
        }
        return items
    }

    /** The decoded text of the first [name] element in [xml], or null when it has none or it is empty. */
    private fun element(xml: String, name: String): String? {
        val open = Regex("<$name(\\s[^>]*)?>").find(xml) ?: return null
        val close = xml.indexOf("</$name>", open.range.last + 1).takeIf { it >= 0 } ?: return null
        return xmlText(xml.substring(open.range.last + 1, close)).trim().ifEmpty { null }
    }

    /** XML character data with its CDATA sections unwrapped and its entity and character references decoded. */
    private fun xmlText(raw: String): String {
        if ('&' !in raw && "<![CDATA[" !in raw) return raw
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            if (raw.startsWith("<![CDATA[", at)) {
                val end = raw.indexOf("]]>", at).takeIf { it >= 0 } ?: raw.length
                out.append(raw, at + 9, end)
                at = end + 3
                continue
            }
            val char = raw[at]
            if (char == '&') {
                val semicolon = raw.indexOf(';', at)
                val entity = if (semicolon > at) raw.substring(at + 1, semicolon) else ""
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    at = semicolon + 1
                    continue
                }
            }
            out.append(char)
            at++
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = when {
        value in 0..0xFFFF -> value.toChar().toString()
        value in 0x10000..0x10FFFF -> {
            val offset = value - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
        else -> null
    }

    private val TRACK_OPEN = Regex("<track(\\s[^>]*)?>")
}

/**
 * [entry] as an address to open, resolved against [base], the address of the list naming it (#490).
 *
 * An address with a scheme of its own stands, except a `file://` one, which becomes the path it
 * names. Beside a list on a server, a relative entry resolves as RFC 3986 says. Beside a list on
 * disk it is a path in the list's folder, and a list written on Windows separates with backslashes,
 * which on any other system are read as separators too. A path that is already whole stands.
 */
internal fun playlistAddress(base: String, entry: String): String {
    val reference = entry.trim()
    if (reference.startsWith("file:", ignoreCase = true)) return filePath(reference)
    if (hasScheme(reference)) return reference
    if (hasScheme(base) && !base.startsWith("file:", ignoreCase = true)) return resolveUriReference(base, reference)
    val baseFile = if (base.startsWith("file:", ignoreCase = true)) filePath(base) else base
    val windows = isWindowsPath(baseFile)
    if (windows) {
        if (isWindowsPath(reference) || reference.startsWith("\\\\")) return reference
        if (reference.startsWith("\\") || reference.startsWith("/")) return baseFile.take(2) + reference.replace('/', '\\')
        return joinPath(baseFile.replace('\\', '/').substringBeforeLast('/', ""), reference.replace('\\', '/')).replace('/', '\\')
    }
    // A Windows path in a list read elsewhere names nothing here, and is kept as it was written.
    if (isWindowsPath(reference)) return reference
    val path = reference.replace('\\', '/')
    if (path.startsWith("/")) return path
    return joinPath(baseFile.substringBeforeLast('/', ""), path)
}

/** [folder] and the relative [path] joined with `/`, its `.` and `..` segments taken out. */
private fun joinPath(folder: String, path: String): String {
    val absolute = folder.startsWith("/")
    val segments = ArrayList<String>()
    for (segment in (if (folder.isEmpty()) path else "$folder/$path").split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                val last = segments.lastOrNull()
                val isDrive = last != null && last.length == 2 && last[1] == ':'
                when {
                    last != null && last != ".." && !isDrive -> segments.removeAt(segments.lastIndex)
                    last == null && !absolute || last == ".." -> segments += segment
                }
            }
            else -> segments += segment
        }
    }
    val drive = segments.firstOrNull()?.takeIf { it.length == 2 && it[1] == ':' }
    val joined = segments.joinToString("/")
    return if (absolute && drive == null) "/$joined" else joined
}

/** The path a `file:` address names, its percent escapes decoded, `file:///C:/x` as `C:/x`. */
private fun filePath(uri: String): String {
    var rest = uri.substring("file:".length)
    if (rest.startsWith("//")) {
        rest = rest.substring(2)
        // file://host/path: the local host is the only one a player can read.
        if (!rest.startsWith("/")) rest = rest.substringAfter('/', "").let { "/$it" }
    }
    val decoded = percentDecoded(rest.substringBefore('?').substringBefore('#'))
    return if (decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':' && decoded[1].isLetter()) decoded.substring(1) else decoded
}

private fun percentDecoded(text: String): String {
    if ('%' !in text) return text
    val bytes = ArrayList<Byte>(text.length)
    var at = 0
    while (at < text.length) {
        val char = text[at]
        if (char == '%' && at + 2 < text.length) {
            val value = text.substring(at + 1, at + 3).toIntOrNull(16)
            if (value != null) {
                bytes += value.toByte()
                at += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { bytes += it }
        at++
    }
    return bytes.toByteArray().decodeToString()
}

private fun hasScheme(text: String): Boolean {
    val colon = text.indexOf(':')
    if (colon < 2) return false
    return text[0].isLetter() && text.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
}

private fun isWindowsPath(text: String): Boolean =
    text.length >= 3 && text[0].isLetter() && text[1] == ':' && (text[2] == '\\' || text[2] == '/')

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
