package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo

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
 * address resolved against [base]. Keys are read without regard to case, as players do.
 */
internal fun parsePls(text: String, base: String): List<StreamEntry> {
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
    return files.keys.sorted().map { n -> StreamEntry(resolveUriReference(base, files.getValue(n)), titles[n]) }
}

/**
 * The entries of [text] when it is a plain M3U list of streams, or null when it is not: an HLS
 * playlist, or no list at all, such as a page of markup. An HLS playlist always carries `#EXT-X-`
 * tags, RFC 8216 asks every media playlist for `#EXT-X-TARGETDURATION`, and a plain list carries
 * none. An `#EXTINF` line names the title of the address after it, after the first comma that no
 * quoted attribute holds, as IPTV lists write `#EXTINF:-1 tvg-name="A, B",Title`.
 */
internal fun plainStreamList(text: String, base: String): List<StreamEntry>? {
    val entries = mutableListOf<StreamEntry>()
    var title: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim().removePrefix("\uFEFF")
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXT-X-", ignoreCase = true) -> return null
            line.startsWith("#EXTINF:", ignoreCase = true) -> title = extinfTitle(line)
            line.startsWith("#") -> Unit
            line.startsWith("<") -> return null
            else -> {
                entries += StreamEntry(resolveUriReference(base, line), title)
                title = null
            }
        }
    }
    return entries.takeIf { it.isNotEmpty() }
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
