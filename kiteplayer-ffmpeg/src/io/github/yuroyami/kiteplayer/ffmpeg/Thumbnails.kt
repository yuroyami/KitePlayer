package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PictureCrop
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * One preview image: a complete file in [format], [width] by [height] pixels, taken at
 * [position]. Two thumbnails are equal when every field is, the contents of [bytes] included.
 */
public data class Thumbnail(
    val position: Duration,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val format: SnapshotFormat,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Thumbnail && position == other.position && width == other.width &&
            height == other.height && format == other.format && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = position.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        return 31 * result + format.hashCode()
    }
}

/** Seek previews and library grids: images at positions, scaled and encoded in one call. */
public object Thumbnails {

    /**
     * One image per position, shown as a renderer shows the picture and scaled to at most
     * [maxWidth] wide with the aspect kept, encoded in [format]. The container's crop, the sample
     * aspect and the display matrix's mirror and turn all apply first (#608), so a portrait phone
     * recording gives a portrait image and an anamorphic picture keeps its proportions; each
     * [Thumbnail] reports the size its image has. Opens the item once and closes it before
     * returning. Each image is the frame shown at its position, decoded forward from the keyframe
     * before it, as frame extraction does. A hardware-decoded frame is downloaded first; the
     * filters run on a graph built for the first frame's geometry and built again when a frame's
     * geometry differs. The work runs off the caller's thread, and a cancel takes effect between
     * two positions.
     *
     * @throws IllegalArgumentException for an item with no video stream, or a [maxWidth] below one
     * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the open, a seek or a decode fails
     */
    @Throws(Exception::class)
    public suspend fun at(
        item: MediaItem,
        positions: List<Duration>,
        maxWidth: Int = 320,
        format: SnapshotFormat = SnapshotFormat.Jpeg,
    ): List<Thumbnail> = withContext(blockingWork) {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        val codec = if (format == SnapshotFormat.Png) CodecId.Png else CodecId.Mjpeg
        openSource(item).use { source ->
            val stream = source.primaryVideo
                ?: throw IllegalArgumentException("${item.label} has no video stream to take a thumbnail from")
            val frameRate = stream.video?.frameRate ?: Rational(25, 1)
            val crop = stream.video?.crop?.let { PictureCrop(top = it.top, bottom = it.bottom, left = it.left, right = it.right) }
            var graph: FilterGraph? = null
            var graphFor: Any? = null
            try {
                return@withContext positions.map { position ->
                    ensureActive()
                    val decoded = source.extractFrame(position.inWholeMicroseconds, stream)
                    val software = if (decoded.info.isHardware) decoded.use { it.downloadFromHardware() } else decoded
                    val info = software.info
                    val shown = shownGeometry(
                        info.width, info.height, info.sampleAspectRatio.num, info.sampleAspectRatio.den,
                        crop, stream.rotationDegrees, stream.mirrored, maxWidth,
                    )
                    val filter = shown.filter
                    if (filter == null) {
                        return@map software.use { Thumbnail(position, info.width, info.height, it.encodeImage(codec), format) }
                    }
                    val key = listOf(filter, info.width, info.height, info.pixelFormat)
                    if (graph == null || graphFor != key) {
                        graph?.close()
                        graph = null
                        graph = FilterGraph.buildVideo(
                            description = filter,
                            width = info.width,
                            height = info.height,
                            pixelFormat = info.pixelFormat,
                            timeBase = info.timeBase,
                            frameRate = frameRate,
                            sampleAspectRatio = info.sampleAspectRatio,
                        )
                        graphFor = key
                    }
                    val current = checkNotNull(graph)
                    var image: Thumbnail? = null
                    val take: (Frame) -> Unit = { out ->
                        if (image == null) {
                            image = Thumbnail(position, out.info.width, out.info.height, out.encodeImage(codec), format)
                        }
                    }
                    // The graph closes the frame it is fed. These filters answer at once; should a
                    // build ever hold the frame back, the flush below gets it out and the graph is
                    // built again for the next position, since a flushed input takes no more.
                    current.feedInput(0, software, take)
                    if (image == null) {
                        current.flushInput(0, take)
                        current.close()
                        graph = null
                        graphFor = null
                    }
                    image ?: error("the filters produced no frame for ${item.label} at $position")
                }
            } finally {
                graph?.close()
            }
        }
    }
}
