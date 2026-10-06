@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.subtitle.AssParser
import io.github.yuroyami.kiteplayer.subtitle.AssTrackParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * The text formats FFmpeg decodes into ASS events: closed captions stored as a track of their own,
 * such as a MOV `c608` track, whose CEA-608 byte pairs FFmpeg's caption decoder turns into timed
 * text, and SAMI, MicroDVD, SubViewer, MPL2, JACOsub, VPlayer, PJS, RealText and Spruce STL tracks
 * (#492). Captions carried inside the video stream are a different path.
 */
internal class KiteFFmpegCaptionDecoderFactory(
    private val source: KiteFFmpegSource,
) : SubtitleDecoderFactory {

    override val name: String = "kiteffmpeg-caption"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? =
        if (stream.codec in CAPTION_CODECS) source.newCaptionDecoder(stream) else null

    private companion object {
        val CAPTION_CODECS = setOf(
            "eia_608", "sami", "microdvd", "subviewer", "subviewer1", "mpl2", "jacosub", "vplayer", "pjs",
            "realtext", "stl",
        )
    }
}

/**
 * One caption track. FFmpeg's decoder answers with ASS events, `{\an7}HELLO` and the like, each
 * with a start and a duration, so every event goes through the same ASS event parser as an
 * embedded ASS track. The caption decoder emits a caption when the screen next changes, which is
 * when its end is known, so the last one comes out of the drain at the end of the stream.
 */
internal class KiteFFmpegCaptionDecoder(
    private val decoder: io.github.yuroyami.kiteffmpeg.SubtitleDecoder,
    private val mapper: TimestampMapper,
) : SubtitleDecoder {

    /** The caption decoder writes its own ASS header, so an empty one gives its default style. */
    private val track: AssTrackParser = AssParser.trackParser("")
    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the caption decoder is closed" }
        // A damaged packet costs its own caption and nothing more. The null packet at the end of
        // the stream drains the caption still on screen, which no packet completes (#480).
        val subtitle = try {
            if (packet == null) decoder.drain() else decoder.decode((packet as KiteFFmpegPacket).native)
        } catch (damaged: io.github.yuroyami.kiteffmpeg.FFmpegException) {
            null
        } ?: return true
        val start = mapper.mapTimestamp(subtitle.startMicros)?.micros ?: packet?.pts?.micros ?: return true
        val end = mapper.mapTimestamp(subtitle.endMicros)?.micros?.takeIf { it > start } ?: SubtitleCue.OPEN_END
        subtitle.texts.forEach { event -> track.parseEvent(event, start, end)?.let(pending::addLast) }
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
        decoder.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        decoder.close()
    }
}
