package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/**
 * The pictures a stream carries for a seek bar's preview (#433): a DASH adaptation set of
 * thumbnail tiles, an HLS image playlist, or a WebVTT thumbnail file the item names in
 * [MediaItem.thumbnails]. Each image is a grid of tiles, one tile for each stretch of time.
 * [Tracks.thumbnails] lists the set, and [KitePlayer.thumbnailAt] gives the tile for a position.
 */
public data class ThumbnailSet(
    /** A tile's width in pixels, or null when the stream does not state it. */
    val width: Int? = null,
    /** A tile's height in pixels, or null when the stream does not state it. */
    val height: Int? = null,
    /** How much of the item one tile stands for, or null when the tiles are not evenly spaced. */
    val interval: Duration? = null,
)

/**
 * The seek bar picture for a stretch of the item (#433): the region of [image] at [x], [y], [width]
 * by [height] pixels, which stands for the item from [start] up to [end]. [image] is the whole grid
 * image as the stream serves it, a JPEG, PNG or WebP that [mimeType] names when the stream says,
 * so the application decodes it with its platform's image decoder and draws that region. A region
 * of 0, 0 and a zero size stands for the whole image, for a stream that does not cut its images
 * into tiles.
 */
public class StreamThumbnail(
    public val image: ByteArray,
    public val mimeType: String?,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val start: Duration,
    public val end: Duration,
) {
    /** True when the region is the whole image. */
    public val isWholeImage: Boolean get() = width == 0 && height == 0

    override fun toString(): String =
        "StreamThumbnail(${image.size} bytes of ${mimeType ?: "an image"}, ${width}x$height at $x,$y, $start..$end)"
}

/**
 * A WebVTT thumbnail file for an item (#433), as many web players publish: each cue's text names
 * an image, relative to the file, and a region of it, `sprite.jpg#xywh=0,0,160,90`, for the cue's
 * time. [uri] is where the file is, and [io], when set, reads it instead, as
 * [SubtitleSource.io] does. The images are read through the file's own reader, or else the
 * player's network, with the item's headers for an image on the item's own server.
 */
public data class ThumbnailSource(
    val uri: String,
    val io: MediaIoFactory? = null,
)
