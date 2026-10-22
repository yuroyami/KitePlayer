package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.TeletextPageReader

/** FFmpeg's name for a DVB teletext stream. */
internal const val TELETEXT: String = "dvb_teletext"

/** The page types of a teletext descriptor (ETSI EN 300 468 §6.2.43) that carry subtitles. */
private const val SUBTITLE_PAGE = 2
private const val HEARING_IMPAIRED_PAGE = 5

/** Above every stream index a container gives, so a further page's id never meets one (#510). */
private const val PAGE_ID_BASE = 0x1000000

/**
 * One track for each subtitle page of the teletext stream [stream] (#510), as VLC's transport stream
 * reader offers them, because a channel sends each language, and its hearing impaired subtitles, on
 * a page of its own in the one stream.
 *
 * FFmpeg keeps the descriptor's entries as two bytes each in the codec extradata, the page type and
 * magazine and then the page number, and their languages as a comma list in the same order (its
 * `mpegts.c`). Each page track keeps its own two bytes, so its decoder knows the page, its own
 * language, and the hearing impaired mark when its type says so. The first page keeps the stream's
 * index, and its default mark, and each further page takes an id of its own above every stream
 * index. A stream with no descriptor is one track that shows the first subtitle page it meets, and
 * a stream whose descriptor lists only pages that are not subtitles, such as the index page, is no
 * track at all, because nothing on it is a subtitle.
 */
internal fun teletextPages(stream: PlayerStreamInfo): List<PlayerStreamInfo> {
    val descriptor = stream.codecExtradata
    if (descriptor == null || descriptor.size < 2) return listOf(stream)
    val languages = stream.language?.split(',')
    val pages = (0 until descriptor.size / 2)
        .mapNotNull { entry ->
            val type = (descriptor[entry * 2].toInt() and 0xFF) shr 3
            if (type != SUBTITLE_PAGE && type != HEARING_IMPAIRED_PAGE) return@mapNotNull null
            TeletextPage(entry, type, descriptor.copyOfRange(entry * 2, entry * 2 + 2))
        }
        .distinctBy { it.number }
    return pages.mapIndexed { order, page ->
        stream.copy(
            index = if (order == 0) stream.index else PAGE_ID_BASE or (stream.index shl 12) or page.number,
            language = languages?.getOrNull(page.entry)?.trim()?.takeIf { it.isNotEmpty() },
            isDefault = stream.isDefault && order == 0,
            isAccessibility = stream.isAccessibility || page.type == HEARING_IMPAIRED_PAGE,
            codecExtradata = page.bytes,
        )
    }
}

/** One subtitle page of a teletext descriptor: its place in the list, its type, and its two bytes. */
private class TeletextPage(val entry: Int, val type: Int, val bytes: ByteArray) {
    /** The page as teletext numbers it, such as 0x888, with magazine 0 sent as 8. */
    val number: Int = ((bytes[0].toInt() and 7).let { if (it == 0) 8 else it } shl 8) or (bytes[1].toInt() and 0xFF)
}

/** The page a page track's two descriptor bytes name, or null for a track that takes the first it meets. */
internal fun teletextPageOf(stream: PlayerStreamInfo): Int? =
    stream.codecExtradata?.takeIf { it.size == 2 }?.let { TeletextPage(0, 0, it).number }

/**
 * A teletext page's cues, read in Kotlin by [TeletextPageReader] from the PES payloads FFmpeg hands
 * over (#510). FFmpeg's own teletext decoder needs libzvbi, which no build here carries, and it
 * holds each page until the next page header, which VLC does not.
 */
internal class KiteFFmpegTeletextDecoder(private val reader: TeletextPageReader) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) {
            pending.addAll(reader.end())
            return true
        }
        val pts = packet.pts?.micros ?: return true
        pending.addAll(reader.read(packet.copyBytes(), pts))
        return true
    }

    override suspend fun receive(): List<SubtitleCue> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        return out
    }

    override suspend fun flush(newGeneration: Generation) {
        pending.clear()
        reader.reset()
    }

    override fun close() {
        closed = true
    }
}
