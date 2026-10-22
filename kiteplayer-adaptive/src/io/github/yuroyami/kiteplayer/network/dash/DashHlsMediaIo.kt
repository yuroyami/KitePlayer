package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.mp4.Fmp4
import io.github.yuroyami.kiteplayer.mp4.Fmp4Rewrite
import io.github.yuroyami.kiteplayer.webm.Webm
import io.github.yuroyami.kiteplayer.webm.WebmUnsupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The reader of a DASH item played as HLS (#295). It reads as the master playlist of
 * [presentation], answers the address of each media playlist with a playlist written from the
 * manifest, and opens every other address, a segment or an initialization the manifest named,
 * through [openUrl] once [policy] accepts it.
 *
 * A static presentation writes each media playlist once. A live one writes it again whenever
 * FFmpeg loads it, and fetches the manifest again through [refetch] once its minimum update period
 * has passed, from the address its `Location` names when it names one (#404). Its window follows
 * the time of day that its `UTCTiming` names, read through [DashLiveClock], or else the device's,
 * which [nowMicros] gives. The media sequence stays continuous across those loads even when a
 * refreshed manifest counts its segments from a different number.
 *
 * A [DashSegmentBase] representation names its segments only in its file's segment index, which
 * this reads through [openUrl] the first time the representation's playlist is asked for: the
 * `sidx` of an MP4 file, or the `Cues` of a WebM one (#401).
 *
 * A manifest of several Periods plays as one presentation (#403). Each track's playlist joins the
 * matching representation of every Period, and names its segments and initializations under this
 * reader's own host, where each segment is served moved onto the presentation's timeline: the
 * timeline of the first Period's picture, which a single-Period manifest keeps untouched. An MP4
 * segment is written against the initialization of the first Period listed for its track, the only
 * one its playlist names, because FFmpeg's MP4 reader keeps the first it reads for the whole
 * stream. The segment loses the samples from its Period's end on. A WebM track's playlist names
 * one header too, which FFmpeg's Matroska reader keeps, and a later Period's clusters take that
 * header's track numbers. A WebM or MPEG-TS segment keeps every sample, so media that runs past
 * its Period's end overlaps the next Period; packagers end a Period's last segment at its end, as
 * the fixtures do.
 *
 * Every address of this reader's own host that a playlist hands out stays resolvable for as long as
 * the playlist is valid (#405): for the reader's life in a static presentation, and in a live one
 * for as long as the track's current playlist or the one before lists it. An initialization read
 * for a joined Period or an MP4 subtitle segment is kept while something still listed needs it,
 * within [initBudgetBytes] for all of them together, and read again when it is asked for after it
 * went (#407).
 */
internal class DashHlsMediaIo(
    private val presentation: DashHlsPresentation,
    private var manifest: DashManifest,
    private val manifestUrl: String,
    private val policy: DashUrlPolicy,
    private val openUrl: suspend (String) -> MediaIo,
    private val refetch: (suspend (url: String) -> DashManifest)?,
    private val nowMicros: () -> Long,
    private val bitsPerSecond: () -> Long? = { null },
    /** Called once when this reader closes, for what its segment readers depend on, such as the client. */
    private val release: () -> Unit = {},
    /** The `Date` header of a URL's response, for a live clock whose `UTCTiming` reads one. */
    private val fetchDate: suspend (url: String) -> String? = { null },
    /** How many bytes of initializations this reader keeps at most, together. */
    private val initBudgetBytes: Long = MAX_INIT_BYTES,
    /** Told of each live manifest fetched again, so the locations its segments come from follow it (#440). */
    private val onManifest: suspend (DashManifest) -> Unit = {},
    /** The newest refusal of a request a reader of this item made, once (#453). */
    private val refusal: () -> io.github.yuroyami.kiteplayer.SourceRefusal? = { null },
    /**
     * A reader of a media or initialization segment that the transport may keep in a segment store
     * (#547), or null for a transport with no store. Only a presentation that has ended uses it.
     */
    private val openSegment: (suspend (String) -> MediaIo)? = null,
) : MediaIo {

    private val master = presentation.master.encodeToByteArray()
    private var position = 0
    private var closed = false
    private var warningSink: (PlaybackWarning) -> Unit = {}

    private val lock = Mutex()
    private val written = HashMap<String, String>()

    /** The addresses the playlists of a presentation that has ended named as segments or initializations (#547). */
    private val named = HashSet<String>()
    private val indexed = HashMap<String, DashTimedPlan>()
    private val sequences = HashMap<String, LiveSequence>()

    /** The subtitle segments served as WebVTT, by the address their playlist names them under. */
    private val conversions = HashMap<String, Conversion>()

    /** The initialization segments read, by address and range, the least recently used first. */
    private val inits = LinkedHashMap<String, ByteArray>()

    /** The bytes of every initialization in [inits] together. */
    private var initBytes = 0L

    /** The segments and initializations of joined Periods, by the address their playlist names them under. */
    private val pieces = HashMap<String, Piece>()

    /**
     * For each track of a live presentation, the addresses of this reader's own host that its
     * current playlist and the one before it list. Nothing else is kept (#405).
     */
    private val listed = HashMap<String, Listed>()

    /** The tracks whose set or representation a live refresh dropped, each reported once (#406). */
    private val goneReported = HashSet<String>()

    /**
     * The initialization each MP4 or WebM track of joined Periods is written against, by the HLS
     * track's address: that of the first Period listed for it. It is kept for the reader's life,
     * because a live server may drop a Period's files once the Period leaves the window.
     */
    private val baseInits = HashMap<String, ByteArray>()

    /** The MP4 track of each of [baseInits]. */
    private val baseTracks = HashMap<String, Fmp4.Track>()

    /** The Period the presentation's tracks were taken from, the first of the manifest at the open. */
    private val reference: DashPeriod = manifest.periods.first()
    private val referenceStartMicros: Long = manifest.periodTimings().first().startMicros

    /** The presentation time offset of the reference Period's picture, or else its sound, whose timeline the presentation keeps. */
    private val referenceOffsetMicros: Long =
        (presentation.tracks.firstOrNull { it.role == DashHlsRole.Video } ?: presentation.tracks.firstOrNull { it.role == DashHlsRole.Audio })
            ?.representation?.offsetMicros() ?: 0L
    private var fetchedAtMicros = nowMicros()

    /** Where a refresh fetches the manifest: the address it came from, or the last `Location` it named. */
    private var manifestAddress: String = allowedLocation(manifest) ?: manifestUrl

    // A clock's address may be relative, to the manifest's own, and the policy judges it as any other.
    private val clock = DashLiveClock(
        manifest.utcTimings,
        nowMicros,
        // The clock is not media, so it never meets a segment store.
        fetchText = { url ->
            fetch(DashManifestParser.resolveUrl(manifestUrl, url, policy), null, MAX_TIME_BYTES, segment = false).decodeToString()
        },
        fetchDate = { url -> fetchDate(DashManifestParser.resolveUrl(manifestUrl, url, policy)) },
    )

    override val size: Long get() = master.size.toLong()
    override val seekable: Boolean get() = true
    override val location: String get() = presentation.masterAddress
    override val contentType: String get() = HLS_MEDIA_TYPE

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IllegalStateException("read after close")
        if (length == 0) return 0
        if (position >= master.size) return -1
        val count = minOf(length, master.size - position)
        master.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        require(position in 0L..size) { "seek to $position outside 0..$size" }
        this.position = position.toInt()
    }

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
        warningSink = sink
    }

    override fun networkBitsPerSecond(): Long? = bitsPerSecond()

    override fun takeRefusal(): io.github.yuroyami.kiteplayer.SourceRefusal? = refusal()

    override suspend fun openRelated(uri: String): MediaIo? {
        if (closed) throw IllegalStateException("openRelated after close")
        if (uri == presentation.masterAddress) return MemoryMediaIo(master, uri)
        presentation.track(uri)?.let { track -> return MemoryMediaIo(playlist(track).encodeToByteArray(), uri) }
        lock.withLock { conversions[uri] }?.let { conversion ->
            return MemoryMediaIo(convert(conversion).encodeToByteArray(), uri, WEBVTT_MEDIA_TYPE)
        }
        lock.withLock { pieces[uri] }?.let { piece ->
            val bytes = try {
                serve(piece)
            } catch (refused: DashUnsupportedException) {
                // FFmpeg skips a segment it cannot open, so a Period it cannot read plays as a gap.
                KiteLog.log("KiteDash", "a segment of a joined Period is skipped: ${refused.message}")
                throw refused
            }
            return MemoryMediaIo(bytes, uri, null)
        }
        // Every other address came from a playlist written here, so from the manifest; it is
        // checked again all the same, because FFmpeg is free to ask for anything.
        if (uri.substringAfter("://").substringBefore('/').equals(DashHls.HOST, ignoreCase = true)) return null
        DashManifestParser.requireAllowed(manifestUrl, uri, policy)
        val segment = openSegment != null && lock.withLock { uri in named }
        return openOriginal(uri, segment).also { it.setWarningSink(warningSink) }
    }

    /**
     * A reader of [url] at its own server. [segment] says that the manifest named it as a media or
     * initialization segment, which a segment store may keep once the presentation has ended. A
     * live presentation's window is not played again, so it keeps none.
     */
    private suspend fun openOriginal(url: String, segment: Boolean = true): MediaIo =
        (openSegment?.takeIf { segment && !manifest.isDynamic } ?: openUrl)(url)

    override fun close() {
        if (closed) return
        closed = true
        // The initializations go with the reader (#407). A read still in flight finds the reader
        // closed and keeps nothing it reads after this.
        if (lock.tryLock()) {
            try {
                inits.clear()
                initBytes = 0
                baseInits.clear()
            } finally {
                lock.unlock()
            }
        }
        release()
    }

    /** How many initializations this reader keeps now, for a test. */
    internal val retainedInitializations: Int get() = inits.size

    /** How many bytes of initializations this reader keeps now, for a test. */
    internal val retainedInitializationBytes: Long get() = initBytes

    /** The media playlist of [track] as it stands now. */
    private suspend fun playlist(track: DashHlsTrack): String = lock.withLock {
        if (!manifest.isDynamic) {
            return@withLock written.getOrPut(track.address) {
                val plan = servedPlan(track, manifest, null)
                if (openSegment != null) {
                    plan.initializationUrl?.let(named::add)
                    for (segment in plan.segments) {
                        named += segment.url
                        segment.initializationUrl?.let(named::add)
                    }
                }
                DashHls.mediaPlaylist(
                    plan,
                    live = false,
                    images = track.representation.takeIf { track.role == DashHlsRole.Images },
                    dateOriginMicros = dateOrigin(manifest),
                )
            }
        }
        // The refresh follows the device's own clock, which only measures how long has passed.
        refreshIfDue(nowMicros())
        val now = clock.nowMicros()
        val plan = servedPlan(track, manifest, now)
        retainListed(track, plan)
        val sequence = sequences.getOrPut(track.address) { LiveSequence() }.first(plan)
        DashHls.mediaPlaylist(plan, live = true, sequence = sequence, dateOriginMicros = dateOrigin(manifest))
    }

    /**
     * The time of day of the served plan's time zero, or null when [from] states no
     * `availabilityStartTime` (#444). One Period's plan counts in its Period's time, which starts
     * that far after the availability start; a joined plan counts in the presentation's own time.
     */
    private fun dateOrigin(from: DashManifest): Long? {
        val start = from.availabilityStartTimeMicros ?: return null
        val single = from.periods.size == 1 && from.periodTimings().first().startMicros == referenceStartMicros
        return start + if (single) referenceStartMicros else 0L
    }

    /** [track]'s plan as FFmpeg is to read it: one Period's, or every Period's joined when [from] has more or another. */
    private suspend fun servedPlan(track: DashHlsTrack, from: DashManifest, now: Long?): DashTimedPlan {
        val single = from.periods.size == 1 && from.periodTimings().first().startMicros == referenceStartMicros
        return if (single) served(track, plan(track, from, now)) else joinedPlan(track, from, now)
    }

    /**
     * [track]'s segments in every Period of [from], in order, on the presentation's timeline
     * (#403). Each Period gives the representation that [DashPeriods.match] finds; a Period with
     * none gives nothing. A segment that begins after its Period ends is left out, and one that runs
     * past it is listed only until it, because the next Period's time begins there. Every segment
     * and initialization is named under this reader's own host, with what serving it takes.
     */
    private suspend fun joinedPlan(track: DashHlsTrack, from: DashManifest, now: Long?): DashTimedPlan {
        val timings = from.periodTimings()
        val segments = ArrayList<DashTimedSegment>()
        val root = "https://${DashHls.HOST}/${track.setIndex}-${track.representationIndex}"
        for ((index, timing) in timings.withIndex()) {
            // The Period the track came from, fetched again, keeps the track's own set (#406); another
            // Period gives its like.
            val refreshed = timing.startMicros == referenceStartMicros && timing.period.id == reference.id
            val bound = if (refreshed) {
                DashPeriods.bind(track, timing.period).also { if (it == null) reportGone(track) }
            } else {
                DashPeriods.match(track, reference, timing.period)
            }
            val (setIndex, representationIndex) = bound ?: continue
            val set = timing.period.adaptationSets[setIndex]
            val representation = set.representations[representationIndex]
            val periodPlan = DashManifestParser.timedPlan(
                from, timing.period, representation, policy, now,
                periodStartMicros = timing.startMicros, periodDurationMicros = timing.durationMicros,
            ) ?: run {
                val base = checkNotNull(representation.segmentBase) { "a representation without segments" }
                indexed.getOrPut("${representation.baseUrl}#${base.indexRange}") {
                    if (DashHls.isWebm(set, representation)) {
                        webmPlan(representation, base, timing.durationMicros)
                    } else {
                        indexedPlan(representation, base)
                    }
                }
            }
            // The Period's media time, less its offset, is Period time; the presentation keeps the
            // reference Period's picture where its own media time put it.
            val shift = (timing.startMicros - referenceStartMicros) + referenceOffsetMicros - representation.offsetMicros()
            val end = timing.endMicros?.let { it - referenceStartMicros + referenceOffsetMicros }
            val container = DashPeriods.containerOf(set, representation)
            val format = track.subtitleFormat
            // Every MP4 or WebM segment is written against one initialization, so the playlist names
            // only that one: FFmpeg's readers would skip another, and a variant change must know
            // which one a segment was written for (#566).
            val initAddress = periodPlan.initializationUrl?.takeIf { format == null }?.let { url ->
                if (container == DashContainer.Mp4 || container == DashContainer.Webm) {
                    "$root/init".also { pieces.getOrPut(it) { Piece.Init(track, url, periodPlan.initializationRange, container) } }
                } else {
                    "$root/${timing.key}/init".also { pieces[it] = Piece.Init(track, url, periodPlan.initializationRange, container) }
                }
            }
            var first = index > 0
            for (segment in periodPlan.segments) {
                val start = timing.startMicros + segment.startMicros
                val periodEnd = timing.endMicros
                if (periodEnd != null && start >= periodEnd) break
                val duration = if (periodEnd != null) minOf(segment.durationMicros, periodEnd - start) else segment.durationMicros
                val address: String
                if (format != null) {
                    address = "$root/${timing.key}/${segment.number}.vtt"
                    conversions[address] = Conversion(
                        track, format, segment,
                        segment.initializationUrl ?: periodPlan.initializationUrl,
                        segment.initializationRange ?: periodPlan.initializationRange,
                        offsetMicros = shift,
                    )
                } else {
                    address = "$root/${timing.key}/${segment.number}"
                    pieces[address] = Piece.Media(
                        track, segment.url, segment.range, container, shift,
                        endMicros = end.takeIf { container == DashContainer.Mp4 },
                        initializationUrl = periodPlan.initializationUrl,
                        initializationRange = periodPlan.initializationRange,
                    )
                }
                segments += DashTimedSegment(
                    address, null, segment.number, start, duration.coerceAtLeast(1),
                    initializationUrl = initAddress, discontinuity = first,
                )
                first = false
            }
        }
        return DashTimedPlan(null, null, segments)
    }

    /**
     * The bytes of [piece] as FFmpeg is to read them. An initialization is served as it is, and
     * an MP4 or WebM track's is kept as the one its stream began with. A segment is moved onto the
     * presentation's timeline: an MP4 one written against that initialization, a WebM one by
     * its clusters' timestamps, with that header's track numbers, an MPEG-TS one by its PTS, DTS
     * and PCR.
     */
    private suspend fun serve(piece: Piece): ByteArray = when (piece) {
        is Piece.Init -> when (piece.container) {
            DashContainer.Mp4, DashContainer.Webm -> baseInit(piece)
            else -> cachedInit(piece.url, piece.range)
        }
        is Piece.Media -> {
            val bytes = fetch(piece.url, piece.range, MAX_SEGMENT_BYTES)
            val init = piece.initializationUrl?.let { cachedInit(it, piece.initializationRange) }
            when (piece.container) {
                DashContainer.Mp4 -> {
                    val source = init?.let { Fmp4.tracks(it).firstOrNull() }
                    if (source == null) {
                        bytes
                    } else {
                        val target = baseTrack(piece.track) ?: source
                        dashFmp4 { Fmp4Rewrite.rewrite(bytes, Fmp4Rewrite.Plan(source, target, piece.shiftMicros, piece.endMicros)) }
                    }
                }
                DashContainer.Webm -> {
                    val own = init?.let { dashWebm { Webm.header(it) } }
                    val moved = WebmRewrite.shiftClusters(bytes, piece.shiftMicros * 1000 / (own?.timestampScaleNanos ?: 1_000_000L))
                    val base = baseInitOf(piece.track)?.let { dashWebm { Webm.header(it) } }
                    if (own == null || base == null) {
                        moved
                    } else {
                        val numbers = Webm.numbersFor(own, base)
                            ?: throw DashUnsupportedException("the WebM of ${shownUri(piece.url)} does not fit the header its stream began with")
                        Webm.retrack(moved, numbers)
                    }
                }
                DashContainer.Ts -> TsRewrite.shift(bytes, rounded(piece.shiftMicros * 9, 100))
                DashContainer.Other -> bytes
            }
        }
    }

    /** The initialization [piece] names, read once and kept as the one its track's segments are written against. */
    private suspend fun baseInit(piece: Piece.Init): ByteArray {
        val address = piece.track.address
        lock.withLock { baseInits[address] }?.let { return it }
        val bytes = fetch(piece.url, piece.range, MAX_SUBTITLE_BYTES)
        return lock.withLock {
            baseInits.getOrPut(address) {
                if (piece.container == DashContainer.Mp4) Fmp4.tracks(bytes).firstOrNull()?.let { baseTracks[address] = it }
                bytes
            }
        }
    }

    /** The initialization that [track]'s segments are written against, or null when its playlist names none. */
    private suspend fun baseInitOf(track: DashHlsTrack): ByteArray? {
        lock.withLock { baseInits[track.address] }?.let { return it }
        val piece = lock.withLock { pieces["https://${DashHls.HOST}/${track.setIndex}-${track.representationIndex}/init"] } as? Piece.Init ?: return null
        return baseInit(piece)
    }

    /** The MP4 track that [track]'s segments are written against, or null when its playlist names no initialization. */
    private suspend fun baseTrack(track: DashHlsTrack): Fmp4.Track? {
        baseInitOf(track) ?: return null
        return lock.withLock { baseTracks[track.address] }
    }

    /** [block], with a WebM header that cannot be read told as a presentation the reader cannot play. */
    private inline fun <T> dashWebm(block: () -> T): T = try {
        block()
    } catch (refused: WebmUnsupportedException) {
        throw DashUnsupportedException(refused.message ?: "the WebM header cannot be read")
    }

    /** [value] divided by [divisor], to the nearest whole number, halves away from zero. */
    private fun rounded(value: Long, divisor: Long): Long = if (value >= 0) (value + divisor / 2) / divisor else (value - divisor / 2) / divisor

    /**
     * The initialization at [url], or its [range] of it, read once and kept within the budget. The
     * least recently used go first when the budget is passed, never the one just read, and one that
     * went is read again here when a listed address asks for it (#407).
     */
    private suspend fun cachedInit(url: String, range: LongRange?): ByteArray {
        val key = initKey(url, range)
        lock.withLock {
            inits.remove(key)?.let { kept ->
                inits[key] = kept
                return kept
            }
        }
        val bytes = fetch(url, range, MAX_SUBTITLE_BYTES)
        lock.withLock {
            if (closed) return bytes
            inits.remove(key)?.let { initBytes -= it.size }
            inits[key] = bytes
            initBytes += bytes.size
            while (initBytes > initBudgetBytes && inits.size > 1) {
                initBytes -= inits.remove(inits.keys.first())!!.size
            }
        }
        return bytes
    }

    /** What [inits] is keyed by. */
    private fun initKey(url: String, range: LongRange?): String = "$url#$range"

    /**
     * Records the addresses of this reader's own host that [track]'s live [plan] lists, and lets go
     * of every one that neither the current nor the previous playlist of any track lists, with the
     * initializations nothing kept refers to (#405, #407). The previous playlist counts too,
     * because FFmpeg may still be asking for a segment it named.
     */
    private fun retainListed(track: DashHlsTrack, plan: DashTimedPlan) {
        val current = HashSet<String>()
        for (segment in plan.segments) {
            current += segment.url
            segment.initializationUrl?.let(current::add)
        }
        listed[track.address] = Listed(current, listed[track.address]?.current.orEmpty())
        val keep = HashSet<String>()
        for (addresses in listed.values) {
            keep += addresses.current
            keep += addresses.previous
        }
        conversions.keys.retainAll(keep)
        pieces.keys.retainAll(keep)
        val needed = HashSet<String>()
        for (piece in pieces.values) {
            when (piece) {
                is Piece.Init -> needed += initKey(piece.url, piece.range)
                is Piece.Media -> piece.initializationUrl?.let { needed += initKey(it, piece.initializationRange) }
            }
        }
        for (conversion in conversions.values) {
            conversion.initializationUrl?.let { needed += initKey(it, conversion.initializationRange) }
        }
        val unneeded = inits.keys.filter { it !in needed }
        for (key in unneeded) initBytes -= inits.remove(key)!!.size
    }

    /** The addresses one live track's [current] playlist and the one before it, [previous], list. */
    private class Listed(val current: Set<String>, val previous: Set<String>)

    /** A segment or initialization of a joined Period, served from this reader's own address. */
    private sealed interface Piece {
        class Init(val track: DashHlsTrack, val url: String, val range: LongRange?, val container: DashContainer) : Piece

        class Media(
            val track: DashHlsTrack,
            val url: String,
            val range: LongRange?,
            val container: DashContainer,
            val shiftMicros: Long,
            val endMicros: Long?,
            val initializationUrl: String?,
            val initializationRange: LongRange?,
        ) : Piece
    }

    /**
     * [plan] as FFmpeg is to read it. A TTML or MP4 subtitle track's segments are named under this
     * reader's own host, where each is served as WebVTT (#402); every other plan is left as it is.
     */
    private fun served(track: DashHlsTrack, plan: DashTimedPlan): DashTimedPlan {
        val format = track.subtitleFormat
        if (format == null || format == DashSubtitleFormat.WebVtt) return plan
        val segments = plan.segments.map { segment ->
            val address = "https://${DashHls.HOST}/${track.setIndex}-${track.representationIndex}/${segment.number}.vtt"
            conversions[address] = Conversion(track, format, segment, plan.initializationUrl, plan.initializationRange, timelineOffset(track))
            DashTimedSegment(address, null, segment.number, segment.startMicros, segment.durationMicros)
        }
        return DashTimedPlan(null, null, segments)
    }

    /** The WebVTT that [conversion]'s segment holds, on the picture's timeline. */
    private suspend fun convert(conversion: Conversion): String {
        val segment = conversion.segment
        val bytes = fetch(segment.url, segment.range, MAX_SUBTITLE_BYTES)
        val cues = when (conversion.format) {
            DashSubtitleFormat.Mp4 -> {
                val initUrl = conversion.initializationUrl
                    ?: throw DashUnsupportedException("the MP4 subtitles of ${conversion.track.representation.id} have no initialization")
                DashSubtitles.mp4Cues(cachedInit(initUrl, conversion.initializationRange), bytes)
            }
            DashSubtitleFormat.Ttml -> Ttml.cues(bytes.decodeToString())
            // WebVTT is converted only to move it, when Periods are joined.
            DashSubtitleFormat.WebVtt -> return DashSubtitles.shiftWebVtt(bytes.decodeToString(), conversion.offsetMicros)
        }
        return webVtt(DashSubtitles.shift(cues, conversion.offsetMicros))
    }

    /**
     * How far a subtitle track's own time sits from the time of the picture FFmpeg reads. Each
     * representation's time less its presentation time offset is the presentation's time, and
     * FFmpeg reads the picture's samples at their own time, so a cue moves by the picture's offset
     * less its own.
     */
    private fun timelineOffset(track: DashHlsTrack): Long {
        val main = presentation.tracks.firstOrNull { it.role == DashHlsRole.Video }
            ?: presentation.tracks.firstOrNull { it.role == DashHlsRole.Audio }
        return (main?.representation?.offsetMicros() ?: 0L) - track.representation.offsetMicros()
    }

    /** The bytes of [url], or of its [range] of it, after [policy] accepts it, at most [limit] of them. */
    private suspend fun fetch(url: String, range: LongRange?, limit: Long, segment: Boolean = true): ByteArray {
        if (range != null) {
            require(range.last - range.first < limit) { "${shownUri(url)} asks for more than $limit bytes" }
            return readRange(url, range)
        }
        val io = openOriginal(DashManifestParser.requireAllowed(manifestUrl, url, policy), segment)
        try {
            return readAllBounded(io, limit, "the segment at ${shownUri(url)}")
        } finally {
            io.close()
        }
    }

    /** A subtitle segment that this reader serves as WebVTT, its cues moved by [offsetMicros]. */
    private class Conversion(
        val track: DashHlsTrack,
        val format: DashSubtitleFormat,
        val segment: DashTimedSegment,
        val initializationUrl: String?,
        val initializationRange: LongRange?,
        val offsetMicros: Long,
    )

    private suspend fun plan(track: DashHlsTrack, from: DashManifest, now: Long?): DashTimedPlan {
        val period = from.periods.single()
        // A refresh may have moved the track's set or representation, or dropped it (#406).
        val (setIndex, representationIndex) = DashPeriods.bind(track, period) ?: throw gone(track)
        val set = period.adaptationSets[setIndex]
        val representation = set.representations[representationIndex]
        DashManifestParser.timedPlan(from, period, representation, policy, now)?.let { return it }
        val base = checkNotNull(representation.segmentBase) { "a representation without segments" }
        return indexed.getOrPut(track.address) {
            if (DashHls.isWebm(set, representation)) {
                webmPlan(representation, base, period.durationMicros ?: from.durationMicros)
            } else {
                indexedPlan(representation, base)
            }
        }
    }

    /**
     * The failure that a track's playlist meets when the live manifest no longer has its set or its
     * representation (#406). Serving the set now at its old place would give it another set's
     * segments, so the playlist is refused instead, and [reportGone] says so once.
     */
    private fun gone(track: DashHlsTrack): DashTrackGoneException {
        reportGone(track)
        return DashTrackGoneException(goneDetail(track))
    }

    /** Says once, through the warning sink, that [track] is gone from the live manifest. */
    private fun reportGone(track: DashHlsTrack) {
        if (!goneReported.add(track.address)) return
        val detail = goneDetail(track)
        KiteLog.log("KiteDash", detail)
        warningSink(PlaybackWarning.SegmentSkipped(track.address, detail))
    }

    private fun goneDetail(track: DashHlsTrack): String =
        "the live manifest no longer has the adaptation set ${track.set.id ?: "at ${track.setIndex}"} " +
            "with the representation ${track.representation.id ?: "at ${track.representationIndex}"}"

    /** The manifest fetched again when it is live and its minimum update period has passed. */
    private suspend fun refreshIfDue(now: Long) {
        val fetch = refetch ?: return
        val period = manifest.minimumUpdatePeriodMicros ?: return
        if (now - fetchedAtMicros < period) return
        fetchedAtMicros = now
        val fresh = try {
            fetch(manifestAddress)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // The old manifest still describes the segments it named, and the window it writes
            // keeps moving with the clock. The next load after another period tries again.
            KiteLog.log("KiteDash", "the live manifest could not be fetched again: ${failure.message}")
            return
        }
        if (fresh.isDynamic && fresh.periods.isNotEmpty()) {
            manifest = fresh
            allowedLocation(fresh)?.let { manifestAddress = it }
            onManifest(fresh)
        }
    }

    /** [from]'s `Location`, or null when it names none, or one that [policy] refuses beside the first address. */
    private fun allowedLocation(from: DashManifest): String? {
        val location = from.location ?: return null
        return try {
            DashManifestParser.requireAllowed(manifestUrl, location, policy)
        } catch (refused: Exception) {
            KiteLog.log("KiteDash", "the live manifest's Location is not followed: ${refused.message}")
            null
        }
    }

    /** The segments that [base]'s segment index names, read from its file. */
    private suspend fun indexedPlan(representation: DashRepresentation, base: DashSegmentBase): DashTimedPlan {
        val file = representation.baseUrl
        val indexRange = base.indexRange ?: findIndex(file)
        require(indexRange.last - indexRange.first < MAX_INDEX_BYTES) {
            "the segment index of ${representation.id} is larger than $MAX_INDEX_BYTES bytes"
        }
        val references = SegmentIndex.parse(readRange(file, indexRange), indexRange.first)
        if (references.isEmpty()) throw DashUnsupportedException("the segment index of ${representation.id} names no segments")
        val firstStart = references.first().startMicros
        return DashTimedPlan(
            initializationUrl = base.initializationUrl ?: file,
            initializationRange = base.initializationRange ?: (0L until indexRange.first).takeIf { indexRange.first > 0 },
            segments = references.mapIndexed { index, reference ->
                DashTimedSegment(file, reference.range, index + 1L, reference.startMicros - firstStart, reference.durationMicros)
            },
        )
    }

    /**
     * The segments that the `Cues` of [base]'s WebM file name, each the run of clusters from one
     * cue point's cluster to the next (#401). The start of the file says where the `Segment`'s
     * data begins, which the cluster positions count from, the timestamp scale, and, when the
     * manifest gives no index range, where the `Cues` are. The last run ends where the `Cues`
     * begin when they follow the clusters, or else at the end of the `Segment` or the file, and
     * lasts until [totalMicros], or as long as the run before it when nothing gives the length.
     */
    private suspend fun webmPlan(representation: DashRepresentation, base: DashSegmentBase, totalMicros: Long?): DashTimedPlan {
        val file = representation.baseUrl
        val headEnd = base.initializationRange?.let { it.last + 1 } ?: WEBM_HEAD_BYTES
        val layout = WebmIndex.layout(readRange(file, 0L until headEnd, allowShort = true))
        val cuesRange = base.indexRange ?: run {
            val start = layout.cuesStart ?: throw DashUnsupportedException("$file names no Cues, so its clusters cannot be found")
            val size = WebmIndex.elementSize(readRange(file, start until start + EBML_HEADER_BYTES, allowShort = true))
                ?: throw DashUnsupportedException("the Cues of $file have no size")
            start until start + size
        }
        require(cuesRange.last - cuesRange.first < MAX_INDEX_BYTES) {
            "the Cues of ${representation.id} are larger than $MAX_INDEX_BYTES bytes"
        }
        val points = WebmIndex.cuePoints(readRange(file, cuesRange))
        if (points.isEmpty()) throw DashUnsupportedException("the Cues of ${representation.id} name no clusters")
        val starts = points.map { layout.segmentDataStart + it.clusterPosition }.toMutableList()
        val ticks = points.map { it.time }.toMutableList()
        // Clusters before the first cue point play from the start.
        layout.firstCluster?.let { first ->
            if (first < starts.first()) {
                starts.add(0, first)
                ticks.add(0, 0L)
            }
        }
        val end = if (cuesRange.first > starts.last()) cuesRange.first else layout.segmentEnd ?: sizeOf(file)
        fun micros(tick: Long): Long = tick * layout.timestampScaleNanos / 1000
        val total = totalMicros ?: layout.durationNanos?.let { (it / 1000).toLong() }
        val segments = ArrayList<DashTimedSegment>(starts.size)
        for (i in starts.indices) {
            val startMicros = micros(ticks[i] - ticks[0])
            val durationMicros = when {
                i + 1 < starts.size -> micros(ticks[i + 1] - ticks[i])
                total != null && total > startMicros -> total - startMicros
                else -> segments.lastOrNull()?.durationMicros ?: 1_000_000L
            }
            val until = if (i + 1 < starts.size) starts[i + 1] else end
            segments += DashTimedSegment(file, starts[i] until until, i + 1L, startMicros, durationMicros.coerceAtLeast(1))
        }
        return DashTimedPlan(
            initializationUrl = base.initializationUrl ?: file,
            initializationRange = base.initializationRange ?: (0L until starts.first()),
            segments = segments,
        )
    }

    /** The size of [file], which its first response states. */
    private suspend fun sizeOf(file: String): Long {
        val io = openOriginal(DashManifestParser.requireAllowed(manifestUrl, file, policy))
        try {
            return io.size ?: throw DashUnsupportedException("$file states no size, so where its last clusters end is unknown")
        } finally {
            io.close()
        }
    }

    /** Where the segment index of [file] is, read from the top-level boxes at its start. */
    private suspend fun findIndex(file: String): LongRange {
        var offset = 0L
        repeat(MAX_BOXES_BEFORE_INDEX) {
            val header = readRange(file, offset until offset + BOX_HEADER_BYTES, allowShort = true)
            val box = SegmentIndex.first(header) ?: throw DashUnsupportedException("$file has no segment index")
            if (box.type == "sidx") return offset until offset + box.size
            offset += box.size
        }
        throw DashUnsupportedException("$file has no segment index among its first $MAX_BOXES_BEFORE_INDEX boxes")
    }

    /** The bytes of [range] of [file]; fewer only at the end of the file, when [allowShort]. */
    private suspend fun readRange(file: String, range: LongRange, allowShort: Boolean = false): ByteArray {
        val wanted = (range.last - range.first + 1).toInt()
        val io = openOriginal(DashManifestParser.requireAllowed(manifestUrl, file, policy))
        try {
            if (range.first > 0) io.seek(range.first)
            val out = ByteArray(wanted)
            var filled = 0
            while (filled < wanted) {
                val count = io.read(out, filled, wanted - filled)
                if (count < 0) break
                filled += count
            }
            if (filled < wanted && !allowShort) {
                throw DashUnsupportedException("$file ended after $filled of the $wanted bytes of ${range.first}-${range.last}")
            }
            return if (filled == wanted) out else out.copyOf(filled)
        } finally {
            io.close()
        }
    }

    /**
     * The media sequence of a live playlist, kept continuous across loads. The first load takes
     * the DASH numbers. Later loads follow the last segment given, by its start time, so a manifest
     * that counts from a different number after a refresh moves no segment.
     */
    private class LiveSequence {
        private var lastStartMicros: Long? = null
        private var lastSequence = 0L

        fun first(plan: DashTimedPlan): Long {
            val segments = plan.segments
            if (segments.isEmpty()) return lastSequence + 1
            val previous = lastStartMicros
            val first = when {
                previous == null -> segments.first().number
                else -> {
                    val at = segments.indexOfFirst { it.startMicros == previous }
                    when {
                        at >= 0 -> lastSequence - at
                        segments.first().startMicros > previous -> lastSequence + 1
                        else -> segments.first().number
                    }
                }
            }
            lastStartMicros = segments.last().startMicros
            lastSequence = first + segments.size - 1
            return first
        }
    }

    /** A playlist, a subtitle segment or a moved segment written in memory, read under its own address. */
    private class MemoryMediaIo(
        private val bytes: ByteArray,
        override val location: String,
        override val contentType: String? = HLS_MEDIA_TYPE,
    ) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            require(position in 0L..size) { "seek to $position outside 0..$size" }
            this.position = position.toInt()
        }

        override fun close() {}
    }

    internal companion object {
        const val HLS_MEDIA_TYPE: String = "application/vnd.apple.mpegurl"

        /** The largest segment index read. A day of one second fragments needs about 1 MB. */
        const val MAX_INDEX_BYTES: Long = 16L shl 20

        private const val MAX_BOXES_BEFORE_INDEX = 16
        private const val BOX_HEADER_BYTES = 16L

        const val WEBVTT_MEDIA_TYPE: String = "text/vtt"

        /** The largest subtitle segment or file read. A whole film of TTML is a few megabytes. */
        const val MAX_SUBTITLE_BYTES: Long = 16L shl 20

        /** The largest answer read from a live clock's address. A time of day is a few dozen bytes. */
        private const val MAX_TIME_BYTES: Long = 4096

        /**
         * How many bytes of initializations a reader keeps together. Each is usually a small `moov`
         * box; one may be as large as [MAX_SUBTITLE_BYTES], and the one just read is always kept.
         */
        const val MAX_INIT_BYTES: Long = 32L shl 20

        /** How much of a WebM file is read to find its layout when the manifest gives no initialization range. */
        private const val WEBM_HEAD_BYTES = 64L * 1024

        /** The longest EBML element header: a four byte ID and an eight byte size. */
        private const val EBML_HEADER_BYTES = 12L
    }
}

/** A live track's playlist was asked for after a refresh of the manifest dropped its set or its representation (#406). */
internal class DashTrackGoneException(message: String) : IllegalStateException(message)
