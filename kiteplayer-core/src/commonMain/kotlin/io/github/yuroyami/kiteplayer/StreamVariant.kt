package io.github.yuroyami.kiteplayer

/**
 * One version of the media at another quality, such as one variant of an HLS master playlist.
 * [Tracks.variants] lists them, and [KitePlayer.selectVariant] plays one of them.
 */
public data class StreamVariant(
    /** The variant's place in its master playlist, counting from 0. [KitePlayer.selectVariant] takes it. */
    val index: Int,
    /** The peak bitrate, in bits per second. */
    val bitrate: Long,
    /** The picture width in pixels, or null when the variant does not state its size. */
    val width: Int? = null,
    /** The picture height in pixels, or null when the variant does not state its size. */
    val height: Int? = null,
    /** The highest frame rate, or null when the variant does not state it. */
    val frameRate: Double? = null,
    /** The codecs in RFC 6381 form, such as `avc1.64001f,mp4a.40.2`, or null when not stated. */
    val codecs: String? = null,
)
