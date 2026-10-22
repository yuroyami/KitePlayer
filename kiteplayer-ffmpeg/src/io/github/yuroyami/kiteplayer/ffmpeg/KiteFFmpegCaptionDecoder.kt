@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.CLOSED_CAPTIONS_CODEC
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
 * (#492). Captions carried inside the video stream decode through [InVideoCaptionDecoder] (#236).
 */
internal class KiteFFmpegCaptionDecoderFactory(
    private val source: KiteFFmpegSource,
) : SubtitleDecoderFactory {

    override val name: String = "kiteffmpeg-caption"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? = when {
        // The captions inside a video stream have no stream of the container to decode (#236).
        stream.codec == CLOSED_CAPTIONS_CODEC -> openInVideoCaptions()
        stream.codec in CAPTION_CODECS -> source.newCaptionDecoder(stream)
        else -> null
    }

    /** Null for a KiteFFmpeg build without FFmpeg's caption decoder, which leaves the track to another factory. */
    private fun openInVideoCaptions(): SubtitleDecoder? = try {
        InVideoCaptionDecoder(io.github.yuroyami.kiteffmpeg.ClosedCaptionDecoder.open(realTime = true))
    } catch (missing: io.github.yuroyami.kiteffmpeg.FFmpegException) {
        null
    }

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
 * embedded ASS track.
 *
 * A CEA-608 track, [realTime], answers as the captions inside a video do (#542): each answer is the
 * screen from the packet that changed it until the next, as mpv shows every caption stream, because
 * buffered the decoder gives a caption only once it leaves the screen, and the reads run too little
 * ahead for it to be seen. The other formats give each event with its start and its duration, and a
 * buffered decoder's last one comes out of the drain at the end of the stream (#480).
 */
internal class KiteFFmpegCaptionDecoder(
    private val decoder: io.github.yuroyami.kiteffmpeg.SubtitleDecoder,
    private val mapper: TimestampMapper,
    private val realTime: Boolean = false,
) : SubtitleDecoder {

    /** The caption decoder writes its own ASS header, so an empty one gives its default style. */
    private val track: AssTrackParser = AssParser.trackParser("")
    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the caption decoder is closed" }
        // In real time the screen holds until its next answer, so the end has nothing to give.
        if (realTime && packet == null) return true
        // A damaged packet costs its own caption and nothing more. The null packet at the end of
        // the stream drains the caption still on screen, which no packet completes (#480).
        val subtitle = try {
            if (packet == null) decoder.drain() else decoder.decode((packet as KiteFFmpegPacket).native)
        } catch (damaged: io.github.yuroyami.kiteffmpeg.FFmpegException) {
            null
        } ?: return true
        val start = mapper.mapTimestamp(subtitle.startMicros)?.micros ?: packet?.pts?.micros ?: return true
        if (realTime) {
            pending.addLast(track.captionScreen(subtitle.texts, start))
            return true
        }
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
