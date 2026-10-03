@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.subtitle.AssParser
import io.github.yuroyami.kiteplayer.subtitle.AssTrackParser
import io.github.yuroyami.kiteplayer.subtitle.SubRipParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.WebVttParser

/**
 * Text subtitle decode over the packet path: a Matroska SubRip, WebVTT or ASS track's
 * packets carry the cue BODY as bytes and the timing as pts/duration, so decoding is UTF-8
 * plus the pure parsers in kiteplayer-subtitles. No C is involved, which is the whole point of
 * the text path. ASS decodes at the DIALOGUE tier: styles, colours, positioning and
 * the common override subset; typesetting-grade rendering is the optional libass module's.
 * Bitmap formats still need real engines.
 */
internal class KiteFFmpegSubtitleDecoderFactory : SubtitleDecoderFactory {

    override val name: String = "kiteffmpeg-text"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? = when (stream.codec) {
        // The cue, not just its text, so a `{\an8}` in the packet lifts the line as it does in a file.
        "subrip", "srt", "text" -> KiteFFmpegTextSubtitleDecoder { payload, start, end ->
            SubRipParser.parseCue(payload.decodeToString(), start, end)
        }
        // MP4 timed text is NOT raw UTF-8: a tx3g sample is a 2-byte big-endian text length, that
        // many bytes of text, then boxes, whose `styl` box carries the faces and colours (#512).
        "mov_text" -> timedTextDefaultStyle(stream.codecExtradata).let { default ->
            KiteFFmpegTextSubtitleDecoder { payload, start, end -> timedTextCue(payload, default, start, end) }
        }
        "webvtt" -> KiteFFmpegTextSubtitleDecoder { payload, start, end -> webVttCue(payload.decodeToString(), start, end) }
        // The Kotlin ASS dialogue tier. The track header, styles included, travels as
        // codec extradata; each packet is one FFmpeg-normalised event line.
        "ass", "ssa" -> KiteFFmpegAssSubtitleDecoder(
            AssParser.trackParser(stream.codecExtradata?.decodeToString() ?: ""),
        )
        else -> null
    }
}

/** ASS packets against the track header's styles: one event line per packet. */
internal class KiteFFmpegAssSubtitleDecoder(
    private val track: AssTrackParser,
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) return true
        val start = packet.pts?.micros ?: return true
        val line = (packet as KiteFFmpegPacket).native.copyBytes().decodeToString()
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

internal class KiteFFmpegTextSubtitleDecoder(
    /** A cue from a packet's bytes and timing, or null when the packet holds no text. */
    private val cueOf: (payload: ByteArray, startMicros: Long, endMicros: Long) -> SubtitleCue.Text?,
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        if (packet == null) return true
        val start = packet.pts?.micros ?: return true
        val payload = (packet as KiteFFmpegPacket).native.copyBytes()
        if (payload.isEmpty()) return true
        val durationUs = packet.duration?.micros?.takeIf { it > 0 } ?: DEFAULT_HOLD_MICROS
        cueOf(payload, start, start + durationUs)?.let(pending::addLast)
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

/** A WebVTT packet's cue. Matroska keeps the cue settings outside the body, so only the text is here. */
private fun webVttCue(body: String, startMicros: Long, endMicros: Long): SubtitleCue.Text? =
    WebVttParser.parseCueBody(body).takeIf { it.isNotEmpty() }?.let { SubtitleCue.Text(startMicros, endMicros, it) }
