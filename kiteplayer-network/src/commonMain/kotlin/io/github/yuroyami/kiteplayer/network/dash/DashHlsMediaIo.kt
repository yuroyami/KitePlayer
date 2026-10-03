package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
import io.github.yuroyami.kiteplayer.network.shownUri
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
 * FFmpeg loads it, from the time of day [nowMicros] gives, and fetches the manifest again through
 * [refetch] once its minimum update period has passed. The media sequence stays continuous across
 * those loads even when a refreshed manifest counts its segments from a different number.
 *
 * A [DashSegmentBase] representation names its segments only in its file's segment index, which
 * this reads through [openUrl] the first time the representation's playlist is asked for: the
 * `sidx` of an MP4 file, or the `Cues` of a WebM one (#401).
 */
internal class DashHlsMediaIo(
    private val presentation: DashHlsPresentation,
    private var manifest: DashManifest,
    private val manifestUrl: String,
    private val policy: DashUrlPolicy,
    private val openUrl: suspend (String) -> MediaIo,
    private val refetch: (suspend () -> DashManifest)?,
    private val nowMicros: () -> Long,
    private val bitsPerSecond: () -> Long? = { null },
    /** Called once when this reader closes, for what its segment readers depend on, such as the client. */
    private val release: () -> Unit = {},
) : MediaIo {

    private val master = presentation.master.encodeToByteArray()
    private var position = 0
    private var closed = false
    private var warningSink: (PlaybackWarning) -> Unit = {}

    private val lock = Mutex()
    private val written = HashMap<String, String>()
    private val indexed = HashMap<String, DashTimedPlan>()
    private val sequences = HashMap<String, LiveSequence>()

    /** The subtitle segments served as WebVTT, by the address their playlist names them under, oldest first. */
    private val conversions = LinkedHashMap<String, Conversion>()

    /** The initialization segments of MP4 subtitle tracks, read once each. */
    private val subtitleInits = HashMap<String, ByteArray>()
    private var fetchedAtMicros = nowMicros()

    override val size: Long get() = master.size.toLong()
    override val seekable: Boolean get() = true
    override val location: String get() = presentation.masterAddress
    override val contentType: String get() = HLS_MEDIA_TYPE

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw KtorMediaIoException("read after close")
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

    override suspend fun openRelated(uri: String): MediaIo? {
        if (closed) throw KtorMediaIoException("openRelated after close")
        if (uri == presentation.masterAddress) return MemoryMediaIo(master, uri)
        presentation.track(uri)?.let { track -> return MemoryMediaIo(playlist(track).encodeToByteArray(), uri) }
        lock.withLock { conversions[uri] }?.let { conversion ->
            return MemoryMediaIo(convert(conversion).encodeToByteArray(), uri, WEBVTT_MEDIA_TYPE)
        }
        // Every other address came from a playlist written here, so from the manifest; it is
        // checked again all the same, because FFmpeg is free to ask for anything.
        if (uri.substringAfter("://").substringBefore('/').equals(DashHls.HOST, ignoreCase = true)) return null
        DashManifestParser.requireAllowed(manifestUrl, uri, policy)
        return openUrl(uri).also { it.setWarningSink(warningSink) }
    }

    override fun close() {
        if (closed) return
        closed = true
        release()
    }

    /** The media playlist of [track] as it stands now. */
    private suspend fun playlist(track: DashHlsTrack): String = lock.withLock {
        if (!manifest.isDynamic) {
            return@withLock written.getOrPut(track.address) {
                DashHls.mediaPlaylist(served(track, plan(track, manifest, null)), live = false)
            }
        }
        val now = nowMicros()
        refreshIfDue(now)
        val plan = plan(track, manifest, now)
        val sequence = sequences.getOrPut(track.address) { LiveSequence() }.first(plan)
        DashHls.mediaPlaylist(served(track, plan), live = true, sequence = sequence)
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
            conversions.remove(address)
            conversions[address] = Conversion(track, format, segment, plan.initializationUrl, plan.initializationRange)
            DashTimedSegment(address, null, segment.number, segment.startMicros, segment.durationMicros)
        }
        // A live track names new segments for ever; the oldest go once FFmpeg is long past them.
        while (conversions.size > MAX_CONVERSIONS) conversions.remove(conversions.keys.first())
        return DashTimedPlan(null, null, segments)
    }

    /** The WebVTT that [conversion]'s segment holds, on the picture's timeline. */
    private suspend fun convert(conversion: Conversion): String {
        val segment = conversion.segment
        val bytes = fetch(segment.url, segment.range)
        val cues = when (conversion.format) {
            DashSubtitleFormat.Mp4 -> {
                val initUrl = conversion.initializationUrl
                    ?: throw DashUnsupportedException("the MP4 subtitles of ${conversion.track.representation.id} have no initialization")
                val key = "$initUrl#${conversion.initializationRange}"
                val init = lock.withLock { subtitleInits[key] } ?: fetch(initUrl, conversion.initializationRange).also { read ->
                    lock.withLock { subtitleInits[key] = read }
                }
                DashSubtitles.mp4Cues(init, bytes)
            }
            else -> Ttml.cues(bytes.decodeToString())
        }
        return webVtt(DashSubtitles.shift(cues, timelineOffset(conversion.track)))
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
        return (main?.representation?.let(::offsetMicros) ?: 0L) - offsetMicros(track.representation)
    }

    private fun offsetMicros(representation: DashRepresentation): Long {
        val template = representation.segmentTemplate ?: return 0L
        val offset = template.presentationTimeOffset
        return offset / template.timescale * 1_000_000 + offset % template.timescale * 1_000_000 / template.timescale
    }

    /** The bytes of [url], or of its [range] of it, after [policy] accepts it. */
    private suspend fun fetch(url: String, range: LongRange?): ByteArray {
        if (range != null) return readRange(url, range)
        val io = openUrl(DashManifestParser.requireAllowed(manifestUrl, url, policy))
        try {
            return readAllBounded(io, MAX_SUBTITLE_BYTES, "the subtitles at ${shownUri(url)}")
        } finally {
            io.close()
        }
    }

    /** A subtitle segment that this reader serves as WebVTT. */
    private class Conversion(
        val track: DashHlsTrack,
        val format: DashSubtitleFormat,
        val segment: DashTimedSegment,
        val initializationUrl: String?,
        val initializationRange: LongRange?,
    )

    private suspend fun plan(track: DashHlsTrack, from: DashManifest, now: Long?): DashTimedPlan {
        val period = from.periods.single()
        val set = period.adaptationSets.getOrNull(track.setIndex) ?: track.set
        val representation = set.representations.getOrNull(track.representationIndex) ?: track.representation
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

    /** The manifest fetched again when it is live, its minimum update period has passed, and it still has one Period. */
    private suspend fun refreshIfDue(now: Long) {
        val fetch = refetch ?: return
        val period = manifest.minimumUpdatePeriodMicros ?: return
        if (now - fetchedAtMicros < period) return
        fetchedAtMicros = now
        val fresh = try {
            fetch()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // The old manifest still describes the segments it named, and the window it writes
            // keeps moving with the clock. The next load after another period tries again.
            KiteLog.log("KiteDash", "the live manifest could not be fetched again: ${failure.message}")
            return
        }
        if (fresh.isDynamic && fresh.periods.size == 1) manifest = fresh
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
        val io = openUrl(DashManifestParser.requireAllowed(manifestUrl, file, policy))
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
        val io = openUrl(DashManifestParser.requireAllowed(manifestUrl, file, policy))
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

    /** A playlist or a subtitle segment written in memory, read under its own address. */
    private class MemoryMediaIo(
        private val bytes: ByteArray,
        override val location: String,
        override val contentType: String = HLS_MEDIA_TYPE,
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

        /** How many converted subtitle segments a live reader remembers. */
        private const val MAX_CONVERSIONS = 4096

        /** How much of a WebM file is read to find its layout when the manifest gives no initialization range. */
        private const val WEBM_HEAD_BYTES = 64L * 1024

        /** The longest EBML element header: a four byte ID and an eight byte size. */
        private const val EBML_HEADER_BYTES = 12L
    }
}
