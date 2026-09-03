package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteplayer.MediaItem
import kotlin.time.Duration

/**
 * One preview image: a complete file in [format], [width] by [height] pixels, taken at
 * [position]. Two thumbnails are equal only when they are the same object, since [bytes] is an
 * array.
 */
public data class Thumbnail(
    val position: Duration,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val format: SnapshotFormat,
)

/** Seek previews and library grids: images at positions, scaled and encoded in one call. */
public object Thumbnails {

    /**
     * One image per position, scaled to at most [maxWidth] wide with the aspect kept, encoded in
     * [format]. Opens the item once and closes it before returning. Positions are keyframe-snapped
     * the way frame extraction works: the frame at or first after the position, fast rather than
     * exact. A hardware-decoded frame is downloaded first; the scale runs once per call on a graph
     * built from the first frame's geometry.
     *
     * @throws IllegalArgumentException for an item with no video stream, or a [maxWidth] below one
     * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the open, a seek or a decode fails
     */
    public suspend fun at(
        item: MediaItem,
        positions: List<Duration>,
        maxWidth: Int = 320,
        format: SnapshotFormat = SnapshotFormat.Jpeg,
    ): List<Thumbnail> {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        val codec = if (format == SnapshotFormat.Png) CodecId.Png else CodecId.Mjpeg
        openSource(item).use { source ->
            val stream = source.primaryVideo
                ?: throw IllegalArgumentException("${item.label} has no video stream to take a thumbnail from")
            val frameRate = stream.video?.frameRate ?: Rational(25, 1)
            var scaler: FilterGraph? = null
            try {
                return positions.map { position ->
                    val decoded = source.extractFrame(position.inWholeMicroseconds, stream)
                    val software = if (decoded.info.isHardware) decoded.use { it.downloadFromHardware() } else decoded
                    val info = software.info
                    if (info.width <= maxWidth) {
                        software.use { Thumbnail(position, info.width, info.height, it.encodeImage(codec), format) }
                    } else {
                        val graph = scaler ?: FilterGraph.buildVideo(
                            description = "scale=$maxWidth:-2",
                            width = info.width,
                            height = info.height,
                            pixelFormat = info.pixelFormat,
                            timeBase = info.timeBase,
                            frameRate = frameRate,
                            sampleAspectRatio = info.sampleAspectRatio,
                        ).also { scaler = it }
                        var scaled: Thumbnail? = null
                        val take: (Frame) -> Unit = { out ->
                            if (scaled == null) {
                                scaled = Thumbnail(position, out.info.width, out.info.height, out.encodeImage(codec), format)
                            }
                        }
                        // The graph closes the frame it is fed. A scale answers at once; should a
                        // build ever hold the frame back, the flush below gets it out and the graph
                        // is rebuilt for the next position, since a flushed input takes no more.
                        graph.feedInput(0, software, take)
                        if (scaled == null) {
                            graph.flushInput(0, take)
                            graph.close()
                            scaler = null
                        }
                        scaled ?: error("the scale filter produced no frame for ${item.label} at $position")
                    }
                }
            } finally {
                scaler?.close()
            }
        }
    }
}
