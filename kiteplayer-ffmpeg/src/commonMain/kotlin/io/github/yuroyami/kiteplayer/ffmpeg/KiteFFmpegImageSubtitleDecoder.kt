@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * Image subtitles over FFmpeg's own decoders: Blu-ray (PGS), DVB, DVD and XSUB. Each decoded
 * subtitle becomes one bitmap cue whose regions are the subtitle's images, already premultiplied,
 * placed on the canvas the stream was authored for, each carrying the forced mark FFmpeg read for
 * it (#513).
 */
internal class KiteFFmpegImageSubtitleDecoderFactory(
    private val source: KiteFFmpegSource,
) : SubtitleDecoderFactory {

    override val name: String = "kiteffmpeg-image"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? =
        if (stream.codec in IMAGE_CODECS) source.newImageSubtitleDecoder(stream) else null

    private companion object {
        val IMAGE_CODECS = setOf("hdmv_pgs_subtitle", "dvb_subtitle", "dvd_subtitle", "xsub")
    }
}

/**
 * One image subtitle stream. A subtitle that states no end, as a Blu-ray one does, becomes a cue
 * with [SubtitleCue.OPEN_END], and the engine closes it at the start of the next cue. A subtitle
 * with no image becomes a cue with no region, which draws nothing and closes the one before it:
 * that is how a Blu-ray stream takes a line off the screen.
 */
internal class KiteFFmpegImageSubtitleDecoder(
    private val decoder: io.github.yuroyami.kiteffmpeg.SubtitleDecoder,
    private val mapper: TimestampMapper,
    private val fallbackCanvas: VideoSize?,
) : SubtitleDecoder {

    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the subtitle decoder is closed" }
        // A damaged packet costs its own subtitle and nothing more: the next display set decodes.
        // The null packet at the end of the stream drains what the decoder still holds (#480).
        val subtitle = try {
            if (packet == null) decoder.drain() else decoder.decode((packet as KiteFFmpegPacket).native)
        } catch (damaged: io.github.yuroyami.kiteffmpeg.FFmpegException) {
            null
        } ?: return true
        val start = mapper.mapTimestamp(subtitle.startMicros)?.micros ?: packet?.pts?.micros ?: return true
        val end = mapper.mapTimestamp(subtitle.endMicros)?.micros?.takeIf { it > start } ?: SubtitleCue.OPEN_END
        val images = subtitle.images.filter { it.width > 0 && it.height > 0 }
        // A stream that states no canvas means the video's own picture; with no video either, the
        // images' own extent is the only frame of reference left.
        val canvasWidth = subtitle.canvasWidth.takeIf { it > 0 }
            ?: fallbackCanvas?.width?.takeIf { it > 0 }
            ?: images.maxOfOrNull { it.x + it.width }
            ?: 1
        val canvasHeight = subtitle.canvasHeight.takeIf { it > 0 }
            ?: fallbackCanvas?.height?.takeIf { it > 0 }
            ?: images.maxOfOrNull { it.y + it.height }
            ?: 1
        pending.addLast(
            SubtitleCue.Bitmap(
                startMicros = start,
                endMicros = end,
                regions = images.map { image ->
                    BitmapRegion(
                        x = image.x,
                        y = image.y,
                        width = image.width,
                        height = image.height,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        bitmap = RgbaBitmap(image.width, image.height, image.rgba),
                        forced = image.forced,
                    )
                },
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
        decoder.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        decoder.close()
    }
}
