package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/** What to play. */
public data class MediaItem(
    /**
     * Where the media is. A file path, or a URL with any scheme the linked FFmpeg supports.
     * Ignored when [io] is set, except as a hint for format probing and as a label.
     */
    val uri: String,
    /**
     * Request headers, for the http and https protocols.
     *
     * Not passed to the demuxer by any source here, so they reach nothing.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Subtitle files to load alongside the media.
     *
     * Nothing loads them. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val externalSubtitles: List<SubtitleSource> = emptyList(),
    /**
     * Where to start. Null means the beginning, or the container's own start time.
     *
     * Nothing reads this: opening always starts where the container does.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val startPosition: Duration? = null,
    /**
     * Read the bytes through your own code instead of through FFmpeg's protocols.
     *
     * The FFmpeg source rejects a non-null value: KiteCodec has no custom I/O path.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val io: MediaIo? = null,
    /**
     * A hint for the demuxer, for example "mpegts", when the bytes have no recognisable header.
     * Almost never needed. Probing is reliable.
     *
     * Not passed to the demuxer by any source here.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val formatHint: String? = null,
    /**
     * Demuxer options applied between allocation and open, the only moment `probesize`, `fflags`,
     * format forcing and protocol options can act. Passed straight to the source's pre-open funnel;
     * a key the demuxer does not consume is reported rather than silently dropped.
     *
     * These belong to the ITEM and not to the backend because the useful ones differ per file. The
     * motivating case is Android: a picked `content://` file reaches FFmpeg as the `fd:` protocol
     * with `"fd"` set to a descriptor number, and that number is different for every file.
     */
    val openOptions: Map<String, String> = emptyMap(),
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
 *
 * No source calls any of this. It is interface surface a later backend will implement, and the one
 * backend that exists rejects a [MediaItem] that carries one.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
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

/**
 * An external subtitle file or stream, added alongside a media item.
 *
 * Nothing opens one, and no cue reaches a screen.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
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
     * Meant to show the keyframe at once and then refine to the exact frame in the background, which is
     * what a seek bar drag wants: the picture responds immediately and settles a moment later.
     *
     * The two stages do not exist yet. The engine treats this exactly as [Precise] does, so a request
     * lands on the right frame and the immediate keyframe is not shown first. It is a slower answer than
     * the name promises and never a wrong one, and it is asked for by name rather than silently mapped:
     * the coalescing rules already keep a drag responsive by showing a frame from each seek before the
     * next one runs. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    KeyframeThenRefine,
}
