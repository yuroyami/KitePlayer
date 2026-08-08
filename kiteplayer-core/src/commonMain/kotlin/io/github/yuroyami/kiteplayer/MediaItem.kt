package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/** What to play. */
public data class MediaItem(
    /**
     * Where the media is. A file path, or a URL with any scheme the linked FFmpeg supports.
     * Ignored when [io] is set, except as a hint for format probing and as a label.
     */
    val uri: String,
    /** Request headers, for the http and https protocols. */
    val headers: Map<String, String> = emptyMap(),
    val externalSubtitles: List<SubtitleSource> = emptyList(),
    /** Where to start. Null means the beginning, or the container's own start time. */
    val startPosition: Duration? = null,
    /** Read the bytes through your own code instead of through FFmpeg's protocols. */
    val io: MediaIo? = null,
    /**
     * A hint for the demuxer, for example "mpegts", when the bytes have no recognisable header.
     * Almost never needed. Probing is reliable.
     */
    val formatHint: String? = null,
) {
    /** A short label for logs and for a UI that has nothing better to show. */
    val label: String get() = uri.substringAfterLast('/').ifEmpty { uri }
}

/**
 * Reads media bytes from anywhere Kotlin can reach.
 *
 * This is how an application plays from its own HTTP client with its own authentication, from an
 * Android `content://` URI, from KiteTorrent, from an encrypted store, or from a byte array it
 * already holds.
 *
 * Threading: called from the demux worker only, one call at a time, never concurrently.
 * Implementations do not need to be thread safe. They may suspend.
 */
public interface MediaIo : AutoCloseable {
    /** Total size in bytes, or null when unknown, for example a live stream. */
    public val size: Long?

    /** False disables seeking in the player for this item. */
    public val seekable: Boolean

    /**
     * Reads at most [length] bytes into [into] starting at [offset].
     *
     * @return the number of bytes read, 0 if none are available yet but more may come, or -1 at
     *         the end of the stream. Returning 0 forever stalls playback, so a source with nothing
     *         more to give must return -1.
     */
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Moves the read cursor. Only called when [seekable] is true. */
    public suspend fun seek(position: Long)
}

/** An external subtitle file or stream, added alongside a media item. */
public data class SubtitleSource(
    val uri: String,
    /** Shown in a track menu. Defaults to the file name. */
    val title: String? = null,
    val language: String? = null,
    /** Read the subtitle bytes through your own code. */
    val io: MediaIo? = null,
    /** Selected as soon as it is loaded. */
    val selectImmediately: Boolean = false,
)

/** How exact a seek needs to be, traded against how long it takes. */
public enum class SeekMode {
    /**
     * Land on the nearest keyframe at or before the target. One decode, always fast, and up to a
     * whole group of pictures away from where you asked.
     */
    Keyframe,

    /**
     * Decode forward from the keyframe and land exactly. Costs up to one group of pictures of
     * throwaway decoding, which on a long-GOP 4K file is noticeable.
     */
    Precise,

    /**
     * Show the keyframe at once, then refine to the exact frame in the background.
     *
     * This is what a seek bar drag should use. The picture responds immediately and settles on the
     * right frame a moment later. Neither mpv nor ExoPlayer exposes this, and it is the single most
     * visible difference in a scrubbing interface.
     */
    KeyframeThenRefine,
}
