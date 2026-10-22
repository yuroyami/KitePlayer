package io.github.yuroyami.kiteplayer

/**
 * An item's own cover picture, as its file carries it (#425): the album art of an MP3, an M4A or a
 * FLAC, still encoded, so the one picture serves a lock screen, a notification and an application's
 * own view at whatever size each draws it.
 */
public class CoverArt(
    /** The encoded picture, a JPEG or a PNG as a rule. */
    public val bytes: ByteArray,
    /** The picture's media type, such as `image/jpeg`, or null when the file does not say. */
    public val mimeType: String?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CoverArt && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (mimeType?.hashCode() ?: 0)

    override fun toString(): String = "CoverArt(${bytes.size} bytes, $mimeType)"
}
