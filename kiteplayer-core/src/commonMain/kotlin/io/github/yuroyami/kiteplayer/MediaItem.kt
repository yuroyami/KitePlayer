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
     * Respelled by the FFmpeg backend as the http protocol's own `headers` option, one
     * CRLF-joined block, through the same pre-open funnel [openOptions] uses; an explicit
     * `headers` key there wins over this field. On media no http protocol opens (a local
     * file), the unused-option warning reports them, typed.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Subtitle files to load alongside the media (S4.e). Local SubRip and WebVTT files become
     * selectable synthetic subtitle tracks; see [SubtitleSource] for the exact contract.
     */
    val externalSubtitles: List<SubtitleSource> = emptyList(),
    /**
     * A video filter chain attached at open (S4.e): KD-1's compiled description, or a raw
     * FFmpeg chain like `scale=1280:720,eq=brightness=0.1`. Every decoded frame runs through
     * it before presentation. Filters run on SOFTWARE frames: under HwdecPolicy.Auto or Prefer
     * the hardware route stands down with a warning, and under Require the video track is
     * refused, because both demands cannot hold at once. Timebase-preserving chains only
     * (scale, crop, eq, format and friends); runtime hot-swap is a documented non-goal.
     */
    val videoFilter: String? = null,
    /**
     * Where to start. Null means the beginning, or the container's own start time.
     *
     * Honoured in two halves: the source is moved to the keyframe at or before this position
     * BEFORE the first frame is decoded, so nothing from the beginning of the media is ever
     * shown or heard, and the exact landing then rides an ordinary precise seek. Needs a
     * seekable source; a position that cannot be honoured (unseekable media, or past the end)
     * starts at the beginning and warns `StartPositionIgnored`, typed.
     */
    val startPosition: Duration? = null,
    /**
     * Read the bytes through your own code instead of through FFmpeg's protocols.
     *
     * Wired through the custom AVIO bridge (KPKMP 17.12 M1): when set, [uri] is a label only
     * and every byte the demuxer touches comes from this reader. This is how an application
     * plays through its own HTTP client with its own TLS and auth, from an encrypted store,
     * a torrent, a cache, or bytes it already holds.
     */
    val io: MediaIo? = null,
    /**
     * A hint for the demuxer, for example "mpegts", when the bytes have no recognisable header.
     * Almost never needed. Probing is reliable.
     *
     * Respelled by the FFmpeg backend as a `format_whitelist` of exactly this name, which is
     * what forcing a demuxer means to libavformat: probing is confined to the named format and
     * the open FAILS rather than falling back when the bytes are not that format. An explicit
     * [openOptions] `format_whitelist` key wins over this field.
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
 * Implemented by the FFmpeg backend since the custom AVIO bridge (KPKMP 17.12 M1): a
 * [MediaItem.io] carries the media's bytes; a [SubtitleSource.io] is still unwired and warns
 * typed. The demux worker blocks on [read], so a source that never produces a byte and never
 * returns -1 stalls playback; that is the contract, not a defect.
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
 * Turns a URI into a [MediaIo] when it knows how, at open time (KPKMP 17.12 M1, the Ktor
 * half). Installed through [PlayerConfig.network]; the engine consults it exactly when a
 * [MediaItem] carries a URI and no [MediaItem.io] of its own. Returning null passes the URI
 * through to the backend untouched, which is what keeps local files on FFmpeg's own fast
 * path.
 *
 * This is how https plays on phones: the engine's FFmpeg profile deliberately vendors no TLS
 * backend (its protocol list is pinned to file/fd/pipe/data/http/tcp), so a resolver such as
 * kiteplayer-network's Ktor one carries the bytes with the OS supplying TLS.
 */
public fun interface MediaIoResolver {
    /** A new [MediaIo] for [uri], or null when this resolver does not handle it. */
    public suspend fun resolve(uri: String): MediaIo?
}

/**
 * An external subtitle file added alongside a media item (S4.e).
 *
 * LOCAL SubRip and WebVTT files load at open: each becomes a selectable synthetic subtitle
 * track (a negative [io.github.yuroyami.kiteplayer.TrackId], labelled by [title] or the file
 * name) whose cues run through the same engine timing path container cues use. A file that
 * cannot be read or parsed warns typed and is skipped rather than failing the open. Network
 * URLs stay parked with KPKMP 17.8, and [io] is not wired yet: both warn typed today.
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
     * Shows the keyframe at once and then refines to the exact frame, which is what a seek bar
     * drag wants: the picture responds immediately and settles a moment later.
     *
     * Real since the S4.g surge (SOL-API3): the seek machine lands and PRESENTS the keyframe at
     * or before the target first, then runs an ordinary precise landing on the exact frame. The
     * reported position and [io.github.yuroyami.kiteplayer.PlayerEvent.SeekCompleted] carry the
     * exact landing, never the intermediate keyframe, and a keyframe that already sits on the
     * target skips the second phase. The refine pays its decode-forward from the keyframe a
     * second time; that cost buys the immediate picture, mpv's own trade.
     */
    KeyframeThenRefine,
}
