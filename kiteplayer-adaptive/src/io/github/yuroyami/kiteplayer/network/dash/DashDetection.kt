package io.github.yuroyami.kiteplayer.network.dash

/**
 * How the automatic transport tells a DASH manifest from media (#400): by the content type the
 * server sent, by a path that ends in `.mpd`, or, when the type leaves room for a manifest, by the
 * root element of the document. Tokenised CDN addresses often have no extension, and servers send
 * manifests as `text/plain`, `application/xml` or bytes as often as with their own type.
 */
internal object DashDetection {

    /** The media type ISO/IEC 23009-1, annex C, gives an MPD. */
    const val MEDIA_TYPE: String = "application/dash+xml"

    /**
     * How many bytes are read from the start of a response to find its root element: an XML
     * declaration and a packager's comment fit many times over.
     */
    const val SNIFF_BYTES: Int = 4096

    /**
     * True when the response says it is a manifest: [contentType] is [MEDIA_TYPE], or the path of
     * [address] ends in `.mpd` and the type does not call the bytes media.
     */
    fun declared(contentType: String?, address: String?): Boolean {
        if (typeOf(contentType) == MEDIA_TYPE) return true
        val path = address?.substringBefore('#')?.substringBefore('?')?.lowercase() ?: return false
        return path.endsWith(".mpd") && worthSniffing(contentType)
    }

    /**
     * True when nothing about [contentType] rules out a manifest: no type, or one that is not
     * audio, video, an image or an HLS playlist, which the backend recognises itself.
     */
    fun worthSniffing(contentType: String?): Boolean {
        val type = typeOf(contentType) ?: return true
        if (type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/")) return false
        return type !in HLS_TYPES
    }

    /**
     * True when the first [length] bytes of [head] start a document whose root element is `MPD`,
     * in any namespace prefix. A byte order mark, whitespace, the XML declaration, processing
     * instructions, comments and a document type declaration may come first. A comment or tag that
     * those bytes do not finish says nothing yet, so the answer is false.
     */
    fun startsLikeMpd(head: ByteArray, length: Int): Boolean {
        val text = head.decodeToString(0, length.coerceIn(0, head.size))
        var at = if (text.startsWith('﻿')) 1 else 0
        while (true) {
            while (at < text.length && text[at].isWhitespace()) at++
            if (at >= text.length || text[at] != '<') return false
            at = when {
                text.startsWith("<?", at) -> text.indexOf("?>", at + 2).takeIf { it >= 0 }?.plus(2) ?: return false
                text.startsWith("<!--", at) -> text.indexOf("-->", at + 4).takeIf { it >= 0 }?.plus(3) ?: return false
                text.startsWith("<!", at) -> text.indexOf('>', at + 2).takeIf { it >= 0 }?.plus(1) ?: return false
                else -> {
                    var end = at + 1
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '>' && text[end] != '/') end++
                    // A name that runs to the end of the bytes may go on, as <MPDX> would.
                    if (end >= text.length) return false
                    return text.substring(at + 1, end).substringAfter(':') == "MPD"
                }
            }
        }
    }

    private fun typeOf(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "audio/mpegurl", "application/x-mpegurl", "audio/x-mpegurl")
}
