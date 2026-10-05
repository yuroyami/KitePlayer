package io.github.yuroyami.kiteplayer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Builds a [MediaItem] one setting at a time. Each call has the name of the field it sets, every
 * call is optional, and each returns this builder.
 *
 * Kotlin builds an item in the block of [mediaItem]. Java cannot call the [MediaItem] constructor,
 * because a parameter of the type [Duration] hides it, so it makes a builder, chains its calls and
 * ends with [build] (#394):
 *
 * ```java
 * MediaItem item = new MediaItemBuilder("https://example.com/movie.mp4")
 *         .title("Movie")
 *         .startPositionMillis(90_000)
 *         .build();
 * ```
 */
public class MediaItemBuilder(private val uri: String) {
    private val headers = LinkedHashMap<String, String>()
    private val externalSubtitles = ArrayList<SubtitleSource>()
    private var videoFilter: String? = null
    private var audioFilter: String? = null
    private var startPosition: Duration? = null
    private var io: MediaIoFactory? = null
    private var formatHint: String? = null
    private val openOptions = LinkedHashMap<String, String>()
    private var demux = DemuxPolicy()
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var audioContent = AudioContent.Automatic
    private var clip: MediaClip? = null

    /** Adds one request header. See [MediaItem.headers]. */
    public fun header(name: String, value: String): MediaItemBuilder = apply {
        headers[name] = value
    }

    /** Adds request headers. See [MediaItem.headers]. */
    public fun headers(vararg pairs: Pair<String, String>): MediaItemBuilder = apply {
        headers.putAll(pairs)
    }

    /** Adds request headers. See [MediaItem.headers]. */
    public fun headers(headers: Map<String, String>): MediaItemBuilder = apply {
        this.headers.putAll(headers)
    }

    /** Adds a subtitle file. See [SubtitleSource]. */
    public fun externalSubtitle(source: SubtitleSource): MediaItemBuilder = apply {
        externalSubtitles += source
    }

    /** Adds subtitle files. See [SubtitleSource]. */
    public fun externalSubtitles(sources: List<SubtitleSource>): MediaItemBuilder = apply {
        externalSubtitles += sources
    }

    /** See [MediaItem.videoFilter]. */
    @KitePlayerLowLevelApi
    public fun videoFilter(chain: String): MediaItemBuilder = apply {
        videoFilter = chain
    }

    /** See [MediaItem.audioFilter]. */
    @KitePlayerLowLevelApi
    public fun audioFilter(chain: String): MediaItemBuilder = apply {
        audioFilter = chain
    }

    /** See [MediaItem.startPosition]. */
    public fun startPosition(position: Duration): MediaItemBuilder = apply {
        startPosition = position
    }

    /** [MediaItem.startPosition] in milliseconds, for Java, which cannot make a [Duration]. */
    public fun startPositionMillis(millis: Long): MediaItemBuilder = apply {
        startPosition = millis.milliseconds
    }

    /** See [MediaItem.clip]. A null [end] runs to the end of the file. */
    public fun clip(start: Duration, end: Duration? = null): MediaItemBuilder = apply {
        clip = MediaClip(start, end)
    }

    /**
     * [MediaItem.clip] in milliseconds, for Java, which cannot make a [Duration]. A null
     * [endMillis] runs to the end of the file.
     */
    @kotlin.jvm.JvmOverloads
    public fun clipMillis(startMillis: Long, endMillis: Long? = null): MediaItemBuilder = apply {
        clip = MediaClip.ofMillis(startMillis, endMillis)
    }

    /** See [MediaItem.io]. */
    public fun io(factory: MediaIoFactory): MediaItemBuilder = apply {
        io = factory
    }

    /** See [MediaItem.formatHint]. Null leaves the format to the probe. */
    public fun formatHint(name: String?): MediaItemBuilder = apply {
        formatHint = name
    }

    /** Adds one raw demuxer option. See [MediaItem.openOptions]. */
    @KitePlayerLowLevelApi
    public fun openOption(key: String, value: String): MediaItemBuilder = apply {
        openOptions[key] = value
    }

    /**
     * Replaces the whole of [MediaItem.demux]. [probe], [corruptPackets], [generateTimestamps],
     * [lowLatency] and [skipInitialBytes] change one field of it each.
     */
    public fun demux(policy: DemuxPolicy): MediaItemBuilder = apply {
        demux = policy
    }

    /** See [DemuxPolicy.probe]. */
    public fun probe(depth: ProbeDepth): MediaItemBuilder = apply {
        demux = demux.copy(probe = depth)
    }

    /** See [DemuxPolicy.corruptPackets]. */
    public fun corruptPackets(policy: CorruptPackets): MediaItemBuilder = apply {
        demux = demux.copy(corruptPackets = policy)
    }

    /** See [DemuxPolicy.generateTimestamps]. */
    public fun generateTimestamps(): MediaItemBuilder = apply {
        demux = demux.copy(generateTimestamps = true)
    }

    /** See [DemuxPolicy.lowLatency]. */
    public fun lowLatency(): MediaItemBuilder = apply {
        demux = demux.copy(lowLatency = true)
    }

    /** See [DemuxPolicy.skipInitialBytes]. */
    public fun skipInitialBytes(count: Long): MediaItemBuilder = apply {
        demux = demux.copy(skipInitialBytes = count)
    }

    /** See [MediaItem.title]. */
    public fun title(text: String?): MediaItemBuilder = apply {
        title = text
    }

    /** See [MediaItem.artist]. */
    public fun artist(text: String?): MediaItemBuilder = apply {
        artist = text
    }

    /** See [MediaItem.album]. */
    public fun album(text: String?): MediaItemBuilder = apply {
        album = text
    }

    /** See [MediaItem.audioContent]. */
    public fun audioContent(content: AudioContent): MediaItemBuilder = apply {
        audioContent = content
    }

    /** The item these settings describe, checked as its constructor checks it. */
    @OptIn(KitePlayerLowLevelApi::class)
    public fun build(): MediaItem = MediaItem(
        uri = uri,
        headers = headers.toMap(),
        externalSubtitles = externalSubtitles.toList(),
        videoFilter = videoFilter,
        startPosition = startPosition,
        io = io,
        formatHint = formatHint,
        openOptions = openOptions.toMap(),
        demux = demux,
        title = title,
        artist = artist,
        album = album,
        audioFilter = audioFilter,
        audioContent = audioContent,
        clip = clip,
    )
}

/**
 * Builds a [MediaItem] for [uri]. An empty block builds exactly `MediaItem(uri)`.
 *
 * ```kotlin
 * val item = mediaItem("https://cdn.example/movie.mkv") {
 *     header("Authorization", "Bearer $token")
 *     startPosition(90.seconds)
 *     probe(ProbeDepth.Thorough)
 * }
 * ```
 */
public fun mediaItem(uri: String, block: MediaItemBuilder.() -> Unit = {}): MediaItem =
    MediaItemBuilder(uri).apply(block).build()
