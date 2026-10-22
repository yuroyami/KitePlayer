package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.Playlists

/**
 * The text work of a list of stream addresses, with no FFmpeg and no network (#450): the Shoutcast
 * PLS file a radio directory hands out, and a plain M3U list of streams, as opposed to an HLS
 * playlist. FFmpeg has no reader for either, and its probe takes a PLS file for LRC lyrics.
 */

/** One stream a list names, and its title when the list gives one. */
internal class StreamEntry(val address: String, val title: String?)

/** The media types servers send a PLS file as. */
private val PLS_MEDIA_TYPES = setOf("audio/x-scpls", "audio/scpls", "application/pls", "application/pls+xml")

/**
 * True when the bytes are a PLS file by what is known before reading them: a [formatHint] of `pls`,
 * a PLS [contentType], or an [address] whose path ends in `.pls`.
 */
internal fun looksLikePls(formatHint: String?, contentType: String?, address: String?): Boolean {
    if (formatHint != null) return formatHint == "pls"
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type in PLS_MEDIA_TYPES) return true
    val path = address?.substringBefore('#')?.substringBefore('?')?.lowercase() ?: return false
    return path.endsWith(".pls")
}

/** True when the first [length] bytes of [head] start a PLS file: `[playlist]` on its first line. */
internal fun startsLikePls(head: ByteArray, length: Int = head.size): Boolean {
    var at = 0
    if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) at = 3
    while (at < length && head[at].toInt().toChar() in " \t\r\n") at++
    if (length - at < PLS_TAG.length) return false
    return head.decodeToString(at, at + PLS_TAG.length).equals(PLS_TAG, ignoreCase = true)
}

private const val PLS_TAG = "[playlist]"

/**
 * The entries of a PLS file, in the order its `FileN` keys number them, with their `TitleN`, each
 * address resolved against [base], as [Playlists] reads them. A server that sends the keys without
 * the `[playlist]` line still means a PLS file.
 */
internal fun parsePls(text: String, base: String): List<StreamEntry> {
    val body = text.removePrefix("\uFEFF")
    val pls = if (body.trimStart().startsWith(PLS_TAG, ignoreCase = true)) body else "$PLS_TAG\n$body"
    return Playlists.parse(pls, base).orEmpty().map { StreamEntry(it.uri, it.title) }
}

/**
 * The entries of [text] when it is a plain M3U list of streams, or null when it is not: an HLS
 * playlist, or no list at all, such as a page of markup, as [Playlists] reads them (#490).
 */
internal fun plainStreamList(text: String, base: String): List<StreamEntry>? =
    Playlists.parse(text, base)?.takeIf { it.isNotEmpty() }?.map { StreamEntry(it.uri, it.title) }

/**
 * [stream], which comes to own [list] as well: the reader of the list the stream was named in. The
 * stream's reader is that list reader's related reader and may share its client, so once the stream
 * has opened the list reader stays open until the stream's closes. Before then, a close is the
 * stream's alone, so a failed entry leaves the list for the next one.
 */
internal class ListedStreamIo(private val stream: MediaIo) : MediaIo {
    var list: MediaIo? = null
    private var closed = false

    override val size: Long? get() = stream.size
    override val seekable: Boolean get() = stream.seekable
    override val location: String? get() = stream.location
    override val contentType: String? get() = stream.contentType
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = stream.read(into, offset, length)
    override suspend fun seek(position: Long) = stream.seek(position)
    override suspend fun openRelated(uri: String): MediaIo? = stream.openRelated(uri)
    override fun setWarningSink(sink: (io.github.yuroyami.kiteplayer.PlaybackWarning) -> Unit) = stream.setWarningSink(sink)
    override fun networkBitsPerSecond(): Long? = stream.networkBitsPerSecond()
    override fun takeTags(): Map<String, String>? = stream.takeTags()
    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.close()
        } finally {
            list?.close()
        }
    }
}
