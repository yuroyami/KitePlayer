@file:OptIn(io.github.yuroyami.kitecodec.KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.subtitle.AssParser
import io.github.yuroyami.kiteplayer.subtitle.AssTrackParser
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubRipParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.WebVttParser

/**
 * Text subtitle decode over the packet path (S4.c): a Matroska SubRip, WebVTT or ASS track's
 * packets carry the cue BODY as bytes and the timing as pts/duration, so decoding is UTF-8
 * plus the pure parsers in kiteplayer-subtitles. No C is involved, which is the whole point of
 * the text path. ASS decodes at the DIALOGUE tier (17.12 M2): styles, colours, positioning and
 * the common override subset; typesetting-grade rendering is the optional libass module's.
 * Bitmap formats still need real engines.
 */
internal class KiteCodecSubtitleDecoderFactory : SubtitleDecoderFactory {

    override val name: String = "kitecodec-text"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? = when (stream.codec) {
        "subrip", "srt", "text" -> KiteCodecTextSubtitleDecoder(SubRipParser::parseCueBody)
        // MP4 timed text is NOT raw UTF-8: a tx3g sample is a 2-byte big-endian text length,
        // that many bytes of UTF-8, then optional style boxes. Decoding the whole payload put
        // the binary length prefix and box bytes into the cue (audit P1-15). The styles are
        // dropped for now; the text is exact.
        "mov_text" -> KiteCodecTextSubtitleDecoder(SubRipParser::parseCueBody, extractBody = ::tx3gText)
        "webvtt" -> KiteCodecTextSubtitleDecoder(WebVttParser::parseCueBody)
        // The Kotlin ASS dialogue tier (M2). The track header, styles included, travels as
        // codec extradata; each packet is one FFmpeg-normalised event line.
        "ass", "ssa" -> KiteCodecAssSubtitleDecoder(
            AssParser.trackParser(stream.codecExtradata?.decodeToString() ?: ""),
        )
        else -> null
    }
}

/** ASS packets against the track header's styles: one event line per packet. */
internal class KiteCodecAssSubtitleDecoder(
    private val track: AssTrackParser,
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) return true
        val start = packet.pts?.micros ?: return true
        val line = (packet as KiteCodecPacket).native.copyBytes().decodeToString()
        if (line.isEmpty()) return true
        val durationUs = packet.duration?.micros?.takeIf { it > 0 } ?: DEFAULT_HOLD_MICROS
        track.parseEvent(line, start, start + durationUs)?.let(pending::addLast)
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
    }

    override fun close() {
        closed = true
    }

    private companion object {
        /** An ASS event with no container duration holds five seconds, libass' own habit. */
        private const val DEFAULT_HOLD_MICROS: Long = 5_000_000L
    }
}

/** The UTF-8 text of one tx3g sample, per its 2-byte big-endian length header. */
private fun tx3gText(payload: ByteArray): String {
    if (payload.size < 2) return ""
    val length = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    val end = (2 + length).coerceAtMost(payload.size)
    if (end <= 2) return ""
    return payload.decodeToString(2, end)
}

internal class KiteCodecTextSubtitleDecoder(
    private val parseBody: (String) -> List<StyledSpan>,
    private val extractBody: (ByteArray) -> String = { it.decodeToString() },
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) return true
        val start = packet.pts?.micros ?: return true
        val body = extractBody((packet as KiteCodecPacket).native.copyBytes())
        if (body.isEmpty()) return true
        val spans = parseBody(body)
        if (spans.isEmpty()) return true
        val durationUs = packet.duration?.micros?.takeIf { it > 0 } ?: DEFAULT_HOLD_MICROS
        pending.addLast(
            SubtitleCue.Text(
                startMicros = start,
                endMicros = start + durationUs,
                spans = spans,
            ),
        )
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
    }

    override fun close() {
        closed = true
    }

    private companion object {
        /** A cue whose container declares no duration holds this long. Ten seconds, the SRT norm. */
        private const val DEFAULT_HOLD_MICROS: Long = 10_000_000L
    }
}
