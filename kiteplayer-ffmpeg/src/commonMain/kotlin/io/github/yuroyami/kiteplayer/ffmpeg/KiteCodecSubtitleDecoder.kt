@file:OptIn(io.github.yuroyami.kitecodec.KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubRipParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.WebVttParser

/**
 * Text subtitle decode over the packet path (S4.c): a Matroska SubRip or WebVTT track's packets
 * carry the cue BODY as bytes and the timing as pts/duration, so decoding is UTF-8 plus the
 * pure parsers in kiteplayer-subtitles. No C is involved, which is the whole point of the text
 * path; ASS and bitmap formats need real engines and belong to S4.f.
 */
internal class KiteCodecSubtitleDecoderFactory : SubtitleDecoderFactory {

    override val name: String = "kitecodec-text"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? = when (stream.codec) {
        "subrip", "srt", "text", "mov_text" -> KiteCodecTextSubtitleDecoder(SubRipParser::parseCueBody)
        "webvtt" -> KiteCodecTextSubtitleDecoder(WebVttParser::parseCueBody)
        else -> null
    }
}

internal class KiteCodecTextSubtitleDecoder(
    private val parseBody: (String) -> List<StyledSpan>,
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) return true
        val start = packet.pts?.micros ?: return true
        val body = (packet as KiteCodecPacket).native.copyBytes().decodeToString()
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
