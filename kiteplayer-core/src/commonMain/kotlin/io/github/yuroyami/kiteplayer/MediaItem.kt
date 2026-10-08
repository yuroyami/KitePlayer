package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SubtitleEncodings
import io.github.yuroyami.kiteplayer.internal.redactUri
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What to play. */
public data class MediaItem(
    /**
     * Where the media is: a file path, or an address.
     *
     * With no [io] and no resolver answer, the FFmpeg backend opens the address through FFmpeg's
     * own protocols. On Android, Apple platforms, the JVM and native desktop those are `file`,
     * `fd`, `pipe`, `data`, `http`, `tcp`, `udp`, `rtp`, `rtsp` and `rtmp`, and a path with no
     * scheme is a file. An `https` address plays through `kiteplayer-network`. Any other scheme,
     * among them `srt`, `rtmps` and `rtsps`, fails the open with [PlaybackError.SchemeUnsupported]
     * before anything goes over the network. A web page has no sockets, so there an address plays
     * only through [io] or `kiteplayer-network`, and the open of any other fails the same way.
     *
     * A live sender is read as it sends. RTSP tries UDP and falls back to TCP when nothing arrives;
     * `rtsp_transport` in [openOptions], set to `tcp` or `udp`, chooses one. A `udp` or `rtp`
     * address may name a multicast group, which plays where the host is allowed to join it, and a
     * file ending in `.sdp` plays the RTP session it describes. A sender that goes silent fails
     * the open after ten seconds, and the playback after ten over UDP and twenty over a TCP
     * connection, on which FFmpeg waits twice. The user name and password in an address never
     * reach a log or an error.
     *
     * The player stays about the buffer policy's ready duration behind such a sender. When the
     * open or a stall leaves it more than half a second further behind, it plays 1.1 times faster,
     * keeping the pitch, until it is back within a tenth of a second of that, so each second of
     * extra delay clears in ten. It leaves a speed the caller chose alone, and does not catch up
     * with the pitch correction off, which would raise every voice by a tenth. A raw `tcp` address
     * is not caught up, because a sender there may as well send a file as fast as it can.
     *
     * When [io] is set, the bytes come from that reader and this is a label. The FFmpeg backend
     * still reads its extension to recognise an HLS playlist, and resolves the playlist's relative
     * addresses against it when the reader reports no [MediaIo.location].
     */
    val uri: String,
    /**
     * Request headers, for the http and https protocols. Passed to a configured or automatic
     * [MediaIoResolver] when it supplies the transport. An external subtitle gets them only when it
     * has the same scheme, host and port as [uri]; see [SubtitleSource].
     *
     * Respelled by the FFmpeg backend as the http protocol's own `headers` option, one
     * CRLF-joined block, through the same pre-open funnel [openOptions] uses. A `headers` key in
     * [openOptions] as well refuses the open. On media no http protocol opens (a local file), the
     * unused-option warning reports them, typed.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Subtitle files to load alongside the media. Local SubRip and WebVTT files become
     * selectable synthetic subtitle tracks; see [SubtitleSource] for the exact contract.
     */
    val externalSubtitles: List<SubtitleSource> = emptyList(),
    /**
     * A video filter chain attached at open: a compiled filter description, or a raw
     * FFmpeg chain like `scale=1280:720,eq=brightness=0.1`. Every decoded frame runs through
     * it before presentation. Filters run on SOFTWARE frames: under HwdecPolicy.Auto or Prefer
     * the hardware route stands down with a warning, and under Require the video track is
     * refused, because both demands cannot hold at once. Timebase-preserving chains only
     * (scale, crop, eq, format and friends); runtime hot-swap is a documented non-goal.
     */
    @property:KitePlayerLowLevelApi
    val videoFilter: String? = null,
    /**
     * Where to start. Null means the beginning, or the container's own start time. With a [clip]
     * it counts from the clip's start, as every position of a clipped item does, and null means
     * the clip's start.
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
     * A FACTORY, not a reader. The engine calls it once per open and closes what it gets when that
     * session ends, so every call must return a FRESH reader. One media item is opened more than
     * once by perfectly ordinary playback: a track switch reopens the container, so does a hardware
     * decoder recovery, so does a loop, and so does a queue coming back round. Holding one live
     * reader here meant the second open was handed the one the first session had already closed,
     * and the media simply stopped.
     *
     * Wired through the custom AVIO bridge: when set, [uri] is a label only and
     * every byte the demuxer touches comes from the reader this makes. This is how an application
     * plays through its own HTTP client with its own TLS and auth, from an encrypted store, a
     * torrent, a cache, or bytes it already holds.
     */
    val io: MediaIoFactory? = null,
    /**
     * The demuxer to open the item with, by FFmpeg name, for example "mpegts" or "s16le". Almost
     * never needed: probing is reliable.
     *
     * The FFmpeg backend forces this demuxer, as the command line's `-f` does, so the open does
     * not probe. That opens input with nothing to probe, such as raw PCM, whose parameters go in
     * [openOptions] (`sample_rate`, `ch_layout`). Bytes that are not that format fail the open, and
     * a name the build does not carry fails it too.
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
     *
     * On Android, Apple platforms and Linux, the FFmpeg backend reads such a descriptor by position
     * when it holds a regular file, so no open moves the file offset of the caller. You still own
     * the descriptor and close it. A pipe is read as a stream.
     *
     * A key that a typed field also sets, such as `headers` next to [headers], refuses the open
     * with [PlaybackError.ConfigurationInvalid] naming both. Neither side wins quietly.
     */
    @property:KitePlayerLowLevelApi
    val openOptions: Map<String, String> = emptyMap(),
    /**
     * Typed settings for opening the container: how far to probe, what to do with damaged packets,
     * low latency, and bytes to skip at the start. The default changes nothing. See [DemuxPolicy].
     */
    val demux: DemuxPolicy = DemuxPolicy(),
    /**
     * The title the lock screen, the notification and the car show for this item. Null falls back
     * to the file's own title tag, then to [label]. Nothing else reads it.
     */
    val title: String? = null,
    /** The line under [title] on those screens. Null falls back to the file's artist tag. */
    val artist: String? = null,
    /** The album line, where a screen shows one. Null falls back to the file's album tag. */
    val album: String? = null,
    /**
     * An audio filter chain attached at open: a raw FFmpeg chain like `volume=0.5` or
     * `loudnorm`. Every decoded audio buffer runs through it before the engine's own stages, so
     * it works on untagged files, keeps pitch and speed apart, and can change the level or the
     * key. Keep the chain's timing consistent with the audio it takes in; the buffers it gives
     * back carry the timestamps FFmpeg puts on them. The web build has no filter graphs, so there
     * an item with one fails to open with [PlaybackError.ConfigurationInvalid] rather than playing
     * without it.
     */
    @property:KitePlayerLowLevelApi
    val audioFilter: String? = null,
    /**
     * What the item's sound is, for the platform's sound processing: see [AudioContent]. The
     * default declares a film when the item shows a picture, cover art aside, and music when it
     * shows none. Name [AudioContent.Speech] for a podcast or an audiobook.
     */
    val audioContent: AudioContent = AudioContent.Automatic,
    /**
     * The part of the file this item is, or null for the whole file: a track of an album ripped
     * to one file, a chapter played on its own, or any clip of a longer file (#456). The item is
     * then an item of the clip's length, and every position and length the player reports for it
     * counts from the clip's start. See [MediaClip].
     */
    val clip: MediaClip? = null,
    /**
     * Marks the file as still being written, as a recording in progress, a download that plays
     * while it arrives or a TV recorder's file is (#430), or null, the default, for a file that is
     * complete. See [FileGrowth].
     */
    val growth: FileGrowth? = null,
    /**
     * A WebVTT thumbnail file whose pictures a seek bar shows for this item, or null (#433). A
     * stream that carries thumbnails of its own needs none: [KitePlayer.thumbnailAt] answers from
     * this file when it is set, and from the stream otherwise. See [ThumbnailSource].
     */
    val thumbnails: ThumbnailSource? = null,
    /**
     * True when this item's sound runs into the next one's, as the tracks of an album can, so the
     * queue joins the two gapless whatever [QueueConfig.crossfade] says (#434). False, the default,
     * lets a crossfade overlap them.
     */
    val runsIntoNext: Boolean = false,
) {
    public companion object {}

    init {
        refuseSeekBreakingOptions()
    }

    @OptIn(KitePlayerLowLevelApi::class)
    private fun refuseSeekBreakingOptions() {
        // MP3 seeking is only correct when the table of contents is used AND fast seek is unset.
        // Either key on its own gives seeking that lands in the wrong place, looks like a player
        // bug, and is invisible until somebody compares against another player. The engine owns
        // that strategy, so these two are refused rather than accepted and quietly ignored.
        openOptions["fflags"]?.let { flags ->
            require(flags.split(',', '+').none { it.trim() == "fastseek" }) {
                "openOptions fflags=$flags: fastseek breaks exact seeking and is never applied"
            }
        }
        require("usetoc" !in openOptions) {
            "openOptions usetoc: the engine owns MP3 seek strategy and this key is never applied"
        }
    }

    /**
     * A short label for logs and for a UI that has nothing better to show: the file name alone.
     * The query and the fragment are dropped, because that is where a signed URL keeps its
     * signature, and the lock screen shows this label when the item has no title.
     */
    val label: String get() = redactUri(uri)

    /**
     * The item without its secrets: the URI cut to [label], and header and option names without
     * their values. An `Authorization` header or a signed URL in a printed item is a leaked
     * credential, and items get printed into logs.
     */
    @OptIn(KitePlayerLowLevelApi::class)
    override fun toString(): String = buildString {
        append("MediaItem(").append(label)
        if (headers.isNotEmpty()) append(", headers=").append(headers.keys)
        if (externalSubtitles.isNotEmpty()) append(", externalSubtitles=").append(externalSubtitles.size)
        if (videoFilter != null) append(", videoFilter=").append(videoFilter)
        if (audioFilter != null) append(", audioFilter=").append(audioFilter)
        if (clip != null) append(", clip=").append(clip)
        if (startPosition != null) append(", startPosition=").append(startPosition)
        if (io != null) append(", io")
        if (formatHint != null) append(", formatHint=").append(formatHint)
        if (openOptions.isNotEmpty()) append(", openOptions=").append(openOptions.keys)
        if (demux != DemuxPolicy()) append(", demux=").append(demux)
        if (title != null) append(", title=").append(title)
        if (artist != null) append(", artist=").append(artist)
        if (album != null) append(", album=").append(album)
        if (runsIntoNext) append(", runsIntoNext")
        append(")")
    }
}

/**
 * The part of a file a [MediaItem] plays, from [start] to [end] (#456). Both are positions of the
 * whole file, as the item would report them with no clip.
 *
 * A clipped item is an item of the clip's length. Every position and length the player reports for
 * it counts from [start]: [KitePlayer.position], [KitePlayer.progress], [PlayerSnapshot.duration],
 * [PlayerSnapshot.chapters], [PlayerSnapshot.abLoopA] and [PlayerSnapshot.abLoopB], the markers,
 * [SleepTimer.At], [PlayerEvent.SeekCompleted], [PlayerMemento.position] and [MediaItem.startPosition],
 * and every position the caller hands in is read the same way. A seek stays inside the clip. The
 * item ends at [end] as an item ends at the end of its file, and the queue moves on. Nothing from
 * before [start] or from [end] on is heard or shown, and a subtitle on screen at [end] leaves there.
 * A clip whose [end] lies past the end of the media ends where the media does, and one that starts
 * at or past the stated end of the media fails the open with [PlaybackError.ConfigurationInvalid].
 *
 * The timestamps of the media itself stay the file's: every [Pts], such as the audio clock, a
 * presented or captured frame, the audio tap and a scan, and the times of a subtitle cue. Add
 * [start] to a position to get the timestamp that plays there, and take it away to go back.
 *
 * Two items in a queue that are the same file, every field equal but the clip, the start position
 * and the titles, where the second clip starts exactly where the first one ends, play as one
 * stream: the player reads on through the boundary without opening the file again, so an album in
 * one file plays its tracks with no gap and no seam, even in a lossy format. See
 * `docs/gapless-queue.md`.
 *
 * @throws IllegalArgumentException when [start] is negative or not finite, or when [end] is not
 *         finite or not after [start].
 */
public data class MediaClip(
    /** Where the item starts in the file. */
    val start: Duration = Duration.ZERO,
    /** Where the item ends in the file, or null when it runs to the end of the file. */
    val end: Duration? = null,
) {
    init {
        require(start.isFinite() && start >= Duration.ZERO) { "a clip must start at a finite position from zero, was $start" }
        require(end == null || (end.isFinite() && end > start)) { "a clip must end after it starts, was $start to $end" }
    }

    /** [start] in milliseconds. For Java, which cannot read a [Duration] (#394). */
    public val startMillis: Long get() = start.inWholeMilliseconds

    /** [end] in milliseconds, or null when the clip runs to the end of the file. For Java (#394). */
    public val endMillis: Long? get() = end?.inWholeMilliseconds

    /** How long the clip is, or null when it runs to the end of the file, whose length decides. */
    public val length: Duration? get() = end?.minus(start)

    public companion object {
        /**
         * A clip from [startMillis] to [endMillis], or to the end of the file when [endMillis] is
         * null. For Java, which cannot make a [Duration] (#394).
         */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        public fun ofMillis(startMillis: Long, endMillis: Long? = null): MediaClip =
            MediaClip(startMillis.milliseconds, endMillis?.milliseconds)
    }
}

/**
 * How a [MediaItem] whose file is still being written is played (#430).
 *
 * The item plays to the file's current end and on as it grows. At what looks like the end, the
 * player waits for more and reads again, and the item ends once the file has not grown for
 * [endsAfter]. Its length follows the file: [PlayerSnapshot.duration] is an estimate that grows
 * with it, as [PlayerSnapshot.durationIsEstimate] says, and a seek reaches any part already
 * written, not only the part that existed at the open. While playback waits at the end it buffers,
 * as it does for a slow network.
 *
 * The file is read through Kotlin rather than by FFmpeg's own file reader, which reports the end at
 * the first read that finds no more bytes. The item's [MediaItem.io] does that reading when it has
 * one. A local path with none needs a provider that serves local files, which `kiteplayer-io`
 * installs on the JVM, Android, Apple and Linux; without one, the item plays as a complete file
 * and the player says so with [PlaybackWarning.GrowthUnavailable].
 *
 * mpv's `appending://` protocol plays such files the same way, and waits about two seconds, the
 * default here, before it calls the end.
 *
 * @throws IllegalArgumentException when [endsAfter] is not positive and finite.
 */
public data class FileGrowth(
    /** How long the file must go without growing before the item ends. */
    val endsAfter: Duration = 2.seconds,
) {
    init {
        require(endsAfter.isFinite() && endsAfter > Duration.ZERO) { "a growing file must end after a positive wait, was $endsAfter" }
    }

    /** [endsAfter] in milliseconds. For Java, which cannot read a [Duration]. */
    public val endsAfterMillis: Long get() = endsAfter.inWholeMilliseconds

    public companion object {
        /** Growth that ends after [endsAfterMillis] without new bytes. For Java, which cannot make a [Duration]. */
        @kotlin.jvm.JvmStatic
        public fun ofMillis(endsAfterMillis: Long): FileGrowth = FileGrowth(endsAfterMillis.milliseconds)
    }
}

/**
 * Makes a fresh [MediaIo] for one playback session.
 *
 * Called once per open, and opens happen more than once: a track switch, a loop, a recovery and a
 * queue wrap all reopen. Handing back the same reader every time means the second open is given
 * the one the first session already closed, and the media simply stops.
 *
 * A named interface rather than a bare lambda so a door can return one and this contract has
 * somewhere to be written down. Kotlin converts a lambda at the call site, so `io = { reader }`
 * keeps compiling exactly as it did.
 */
public fun interface MediaIoFactory {
    public suspend fun open(): MediaIo
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
 * Implemented by the FFmpeg backend since the custom AVIO bridge, and accepted at both
 * [MediaItem.io] and [SubtitleSource.io]. The demux worker waits on [read]. When no byte arrives
 * for `BufferPolicy.stallTimeout`, the engine interrupts the source and the session ends with
 * [PlaybackError.SourceStalled]. A backend interrupts a read by cancelling its coroutine, so
 * [read] and [seek] must suspend in a way that cancellation can end. A read that blocks its
 * thread instead cannot be stopped.
 */
public interface MediaIo : AutoCloseable {
    public companion object {}

    /** Total size in bytes, or null when unknown, for example a live stream. */
    public val size: Long?

    /**
     * Releases whatever this reader holds. Called exactly once by the engine per reader it made,
     * and it must tolerate being called twice: an open that fails part way is unwound from both
     * sides, and a reader that throws on a second close turns a handled failure into a new one.
     */
    override fun close()

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

    /**
     * Where this reader reports a problem that it recovered from, such as a dropped connection
     * that it opened again. The engine installs its warning reporter here before the first read,
     * and the warnings reach [KitePlayer.events]. The sink may be called from any thread and must
     * stay cheap. The default ignores it, for a reader with nothing to report.
     */
    public fun setWarningSink(sink: (PlaybackWarning) -> Unit) {}

    /**
     * The address these bytes came from, after any redirect, or null when the reader has none.
     * The backend resolves the relative addresses inside the media against it, such as the
     * segments of an HLS playlist. A reader that sets it should implement [openRelated] too.
     */
    public val location: String? get() = null

    /**
     * The media type the bytes arrived with, such as the `Content-Type` of an HTTP response, or
     * null when the reader does not know it. The backend uses it to recognise an HLS playlist
     * whose address does not end in `.m3u8`.
     */
    public val contentType: String? get() = null

    /**
     * A new reader for [uri], an absolute address that this reader's media names, or null to
     * refuse it. An HLS playlist names its variant playlists, segments and keys this way, and the
     * backend opens each one through here. The addresses come from the media, which is untrusted
     * input, so open only the schemes and hosts you expect. The caller closes the reader. The
     * default refuses every address.
     */
    public suspend fun openRelated(uri: String): MediaIo? = null

    /**
     * How fast the network delivers this reader's bytes, in bits per second, or null when the
     * reader does not measure it or has not measured enough yet. The default answers null.
     *
     * Count the bytes of the readers that [openRelated] made too, and count only the time that a
     * download waits for the network. The time it waits for the player to read is not the
     * network's. The player steps an HLS stream up to a higher variant only on this figure, so
     * an HLS stream on a reader that answers null never steps up by itself.
     *
     * The player calls this from its own thread, unlike the other members, so it must be safe to
     * call while a read runs.
     */
    public fun networkBitsPerSecond(): Long? = null

    /**
     * The tags the bytes of the last [read] brought, or null, the default, when it brought none
     * (#423): above all the song an internet radio station names in a title block between its audio
     * bytes. The backend asks after every read that returned bytes, on the thread that read, and
     * the tags belong at the first byte of that read, so a reader that stops each read where its
     * next tags belong places them exactly.
     *
     * Report each change once. A station's fields keep the names FFmpeg's own `http` gives them,
     * `StreamTitle` and `StreamUrl`. Finding the titles is the reader's work: it sends
     * `Icy-MetaData: 1`, reads the block interval from `icy-metaint`, and takes every block out of
     * the bytes before [read] hands them over, so the demuxer never sees one.
     */
    public fun takeTags(): Map<String, String>? = null

    /**
     * A server's refusal of an address this reader, or one it opened, had been reading, once, or
     * null, the default (#453). A signed address that expired gets one: the server answers 401 or
     * 403 to the next segment, the next playlist reload or the next range of the file, after the
     * item had opened. The engine asks on its own passes and opens the item again through its
     * resolver or its `io` factory, which hand out a fresh address, at the position it reached.
     *
     * Report a refusal of the item's first open as a failure of that open instead, as always.
     */
    public fun takeRefusal(): SourceRefusal? = null
}

/**
 * A server's answer [status], 401 or 403, to a request for [uri] that an open item's reader made
 * (#453). See [MediaIo.takeRefusal].
 */
public data class SourceRefusal(val uri: String, val status: Int)

/**
 * Turns a URI into a [MediaIo] when it knows how, at open time (the Ktor
 * half). Explicitly configured through [PlayerConfig.network] or supplied by an installed
 * optional provider. The engine consults it when a [MediaItem] has no [MediaItem.io] of its own.
 * Returning null from an explicit resolver passes the URI
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

    /**
     * Resolves [uri] with this item's HTTP request [headers]. Existing resolvers keep their
     * one-argument behavior unless they override this overload. Returning null deliberately
     * selects backend URI handling when this resolver was explicitly configured.
     */
    public suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? = resolve(uri)
}

/**
 * An external subtitle file added alongside a media item.
 *
 * SubRip, WebVTT and ASS files load at open: each becomes a selectable synthetic subtitle track
 * (a negative [io.github.yuroyami.kiteplayer.TrackId], labelled by [title] or the file name)
 * whose cues run through the same engine timing path container cues use. A file that cannot be
 * read or parsed warns typed and is skipped rather than failing the open.
 *
 * Where the bytes come from, in order: [io] when set; then, for an http or https [uri], the
 * network resolver; then [uri] as a local path.
 *
 * The network resolver gets the parent item's [MediaItem.headers] only when [uri] has the same
 * scheme, host and port as the item's own URI, so a subtitle beside a signed URL works. A subtitle
 * on another server gets no item header. To send headers to it, give it its own [io].
 *
 * The text's encoding is decided from the bytes unless [encoding] names it: a byte-order mark, then
 * UTF-8, then [SubtitleConfig.fallbackEncoding] when one is set, and otherwise a guess, which
 * [PlaybackWarning.SubtitleCharsetGuessed] reports. A track whose guess was wrong can be read again in
 * another encoding with [KitePlayer.reloadExternalSubtitle].
 *
 * @throws IllegalArgumentException when [encoding] is not one of [ENCODINGS] or a label for one.
 */
public data class SubtitleSource(
    val uri: String,
    /** Shown in a track menu. Defaults to the file name. */
    val title: String? = null,
    val language: String? = null,
    /** Selected as soon as it is loaded. */
    val selectImmediately: Boolean = false,
    /**
     * Read the subtitle bytes through your own code.
     *
     * When null, [uri] is read through the network resolver for http and https, and as a local
     * path otherwise. Subtitle files are small and are read whole, so this reader is asked for
     * everything at once and closed.
     */
    val io: MediaIoFactory? = null,
    /**
     * The encoding the file is in, which is then used as it is, with no guess (#515). Null decides
     * from the bytes.
     *
     * One of [ENCODINGS], or any label the WHATWG Encoding Standard gives one of them, such as
     * `cp1250`, `latin2` or `sjis`, in any letter case. A file that is not in the encoding it is
     * given still loads, with its bytes read as told, so the viewer sees the result of the choice.
     * The five East Asian encodings are read by the backend's subtitle parser, which the FFmpeg
     * backend supplies; with a backend that has no table for the one named, the file does not load.
     */
    val encoding: String? = null,
) {
    init {
        require(encoding == null || SubtitleEncodings.canonical(encoding) != null) {
            "$encoding is not an encoding a subtitle file can be read in; the names are ${ENCODINGS.joinToString()}"
        }
    }

    public companion object {
        /**
         * The encodings [encoding] accepts, by the names the WHATWG Encoding Standard gives them, in
         * the order a "Text encoding" menu would list them: Unicode, then the single-byte tables by
         * script, then the East Asian ones. They are also every name
         * [PlaybackWarning.SubtitleCharsetGuessed] can report.
         *
         * `UTF-8`, `UTF-16LE`, `UTF-16BE`; `windows-1252` (Western European), `windows-1250` and
         * `ISO-8859-2` (Central European), `windows-1257` (Baltic), `windows-1254` and `ISO-8859-9`
         * (Turkish), `windows-1258` (Vietnamese), `windows-1251` and `KOI8-R` (Cyrillic),
         * `windows-1253` (Greek), `windows-1255` (Hebrew), `windows-1256` (Arabic), `windows-874`
         * (Thai); `Shift_JIS` and `EUC-JP` (Japanese), `GBK` (Simplified Chinese), `Big5`
         * (Traditional Chinese) and `EUC-KR` (Korean).
         */
        public val ENCODINGS: List<String> = SubtitleEncodings.names
    }
}

/** How exact a seek needs to be, traded against how long it takes. */
public enum class SeekMode {
    /**
     * Land on a keyframe near the target without decoding forward to it: by default the last one
     * at or before the target, and [KeyframeChoice] picks another. One decode, always fast, and up
     * to a whole group of pictures away from where you asked.
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
     * The seek machine lands and PRESENTS the keyframe at
     * or before the target first, then runs an ordinary precise landing on the exact frame. The
     * reported position and [io.github.yuroyami.kiteplayer.PlayerEvent.SeekCompleted] carry the
     * exact landing, never the intermediate keyframe, and a keyframe that already sits on the
     * target skips the second phase. The refine pays its decode-forward from the keyframe a
     * second time; that cost buys the immediate picture, mpv's own trade.
     */
    KeyframeThenRefine,
}

/**
 * Which keyframe a [SeekMode.Keyframe] seek lands on, since it does not decode forward to the
 * exact target.
 *
 * In a file whose keyframes are far apart, such as a screen recording, a long-GOP encode or the
 * recording of a live stream, the choice decides whether a short jump forward goes anywhere: with
 * ten seconds between keyframes, a five second skip forward under [Before] lands where it started or even
 * earlier.
 * Set it with [PlayerConfig.keyframeChoice] or live with [KitePlayer.setKeyframeChoice]. The precise
 * modes are not affected: [SeekMode.KeyframeThenRefine] always shows the keyframe before the target
 * first, because that is where its decode forward starts.
 */
public enum class KeyframeChoice {
    /** The last keyframe at or before the target. Never lands past where you asked; the default. */
    Before,

    /**
     * The first keyframe at or after the target, or the last one before it when none follows,
     * so a seek near the end still lands rather than failing.
     */
    After,

    /**
     * Whichever of the keyframes either side of the target is nearer to it, the one before on a
     * tie or when none follows.
     */
    Closest,

    /**
     * [After] for a seek forward from the current position and [Before] for a seek backward, so a
     * skip button always moves the way it points. mpv applies the same rule to a relative keyframe seek.
     */
    InSeekDirection,
}
