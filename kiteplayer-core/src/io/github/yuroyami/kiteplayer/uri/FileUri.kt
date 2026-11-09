package io.github.yuroyami.kiteplayer.uri

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi

/**
 * The local file an address names, as one rule for every reader that opens one (#655).
 *
 * A `file:` URI writes its path percent-encoded, as RFC 8089 and RFC 3986 define, so a name with a
 * space or a non-ASCII letter arrives as `%20` or `%C3%A9`, which is how Android's `Uri.fromFile`,
 * Apple's `NSURL` and Java's `File.toURI` all write it. FFmpeg's file reader strips the `file:` and
 * opens the rest as it stands, so each reader decodes the address here first, as mpv does.
 */
@KitePlayerInternalApi
public object FileUri {

    /**
     * The path [uri] names: a `file:` address decoded, `file:///C:/x` and `file:/C:/x` as `C:/x`, a
     * bare or Windows path as it is, or null for an address of any other scheme and for an empty
     * one. A single letter before the colon is a Windows drive, not a scheme. A `file:///path` or
     * `file://localhost/path` names `/path`. Any other host is a network share, which is how
     * Windows writes `\\nas\films\a.mkv` as a URI, so `file://nas/films/a.mkv` names the share
     * path `//nas/films/a.mkv`, which Windows file calls and FFmpeg's file reader open (#858).
     */
    public fun localPath(uri: String): String? {
        if (uri.startsWith("file:", ignoreCase = true)) return decodedPath(uri).ifEmpty { null }
        val colon = uri.indexOf(':')
        if (colon < 2) return uri.ifEmpty { null }
        val scheme = uri.substring(0, colon)
        val looksLikeScheme = scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        return if (looksLikeScheme) null else uri
    }

    private fun decodedPath(uri: String): String {
        var rest = uri.substring("file:".length)
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            if (!rest.startsWith("/")) {
                val host = rest.substringBefore('/')
                val path = rest.substringAfter('/', "")
                rest = if (host.equals("localhost", ignoreCase = true)) "/$path" else "//$host/$path"
            }
        }
        val decoded = percentDecoded(rest.substringBefore('?').substringBefore('#'))
        val drive = decoded.length >= 3 && decoded[0] == '/' && decoded[1].isLetter() && decoded[2] == ':'
        return if (drive) decoded.substring(1) else decoded
    }

    /**
     * [text] with its `%XX` escapes turned back into the UTF-8 bytes they stand for. The text
     * between escapes is encoded whole, so a character outside the Basic Multilingual Plane, which
     * is two UTF-16 units, survives beside an escape.
     */
    private fun percentDecoded(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var runStart = 0
        var at = 0
        fun flushRun(end: Int) {
            if (end > runStart) text.substring(runStart, end).encodeToByteArray().forEach { bytes += it }
        }
        while (at < text.length) {
            // `%` and exactly two hexadecimal digits (RFC 3986, 2.1). toIntOrNull also took a sign,
            // so a literal `%-1` decoded to the byte 0xFF (#875).
            val high = if (text[at] == '%' && at + 2 <= text.lastIndex) text[at + 1].digitToIntOrNull(16) else null
            val low = if (high != null) text[at + 2].digitToIntOrNull(16) else null
            val value = if (high != null && low != null) {
                high * 16 + low
            } else {
                null
            }
            if (value != null) {
                flushRun(at)
                bytes += value.toByte()
                at += 3
                runStart = at
            } else {
                at++
            }
        }
        flushRun(text.length)
        return bytes.toByteArray().decodeToString()
    }
}
