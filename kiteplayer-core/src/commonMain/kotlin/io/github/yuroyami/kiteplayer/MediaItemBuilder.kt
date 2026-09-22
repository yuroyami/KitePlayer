package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/**
 * Builds a [MediaItem] one setting at a time. Each call has the name of the field it sets, and
 * every call is optional.
 */
public class MediaItemBuilder internal constructor(private val uri: String) {
    private val headers = LinkedHashMap<String, String>()
    private val externalSubtitles = ArrayList<SubtitleSource>()
    private var videoFilter: String? = null
    private var startPosition: Duration? = null
    private var io: MediaIoFactory? = null
    private var formatHint: String? = null
    private val openOptions = LinkedHashMap<String, String>()
    private var demux = DemuxPolicy()

    /** Adds one request header. See [MediaItem.headers]. */
    public fun header(name: String, value: String) {
        headers[name] = value
    }

    /** Adds request headers. See [MediaItem.headers]. */
    public fun headers(vararg pairs: Pair<String, String>) {
        headers.putAll(pairs)
    }

    /** Adds a subtitle file. See [SubtitleSource]. */
    public fun externalSubtitle(source: SubtitleSource) {
        externalSubtitles += source
    }

    /** See [MediaItem.videoFilter]. */
    @KitePlayerLowLevelApi
    public fun videoFilter(chain: String) {
        videoFilter = chain
    }

    /** See [MediaItem.startPosition]. */
    public fun startPosition(position: Duration) {
        startPosition = position
    }

    /** See [MediaItem.io]. */
    public fun io(factory: MediaIoFactory) {
        io = factory
    }

    /** See [MediaItem.formatHint]. */
    public fun formatHint(name: String) {
        formatHint = name
    }

    /** Adds one raw demuxer option. See [MediaItem.openOptions]. */
    @KitePlayerLowLevelApi
    public fun openOption(key: String, value: String) {
        openOptions[key] = value
    }

    /** See [DemuxPolicy.probe]. */
    public fun probe(depth: ProbeDepth) {
        demux = demux.copy(probe = depth)
    }

    /** See [DemuxPolicy.corruptPackets]. */
    public fun corruptPackets(policy: CorruptPackets) {
        demux = demux.copy(corruptPackets = policy)
    }

    /** See [DemuxPolicy.generateTimestamps]. */
    public fun generateTimestamps() {
        demux = demux.copy(generateTimestamps = true)
    }

    /** See [DemuxPolicy.lowLatency]. */
    public fun lowLatency() {
        demux = demux.copy(lowLatency = true)
    }

    /** See [DemuxPolicy.skipInitialBytes]. */
    public fun skipInitialBytes(count: Long) {
        demux = demux.copy(skipInitialBytes = count)
    }

    @OptIn(KitePlayerLowLevelApi::class)
    internal fun build(): MediaItem = MediaItem(
        uri = uri,
        headers = headers.toMap(),
        externalSubtitles = externalSubtitles.toList(),
        videoFilter = videoFilter,
        startPosition = startPosition,
        io = io,
        formatHint = formatHint,
        openOptions = openOptions.toMap(),
        demux = demux,
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
