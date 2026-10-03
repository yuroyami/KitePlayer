package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
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
 * this reads through [openUrl] the first time the representation's playlist is asked for.
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
            return@withLock written.getOrPut(track.address) { DashHls.mediaPlaylist(plan(track, manifest, null), live = false) }
        }
        val now = nowMicros()
        refreshIfDue(now)
        val plan = plan(track, manifest, now)
        val sequence = sequences.getOrPut(track.address) { LiveSequence() }.first(plan)
        DashHls.mediaPlaylist(plan, live = true, sequence = sequence)
    }

    private suspend fun plan(track: DashHlsTrack, from: DashManifest, now: Long?): DashTimedPlan {
        val period = from.periods.single()
        val representation = period.adaptationSets.getOrNull(track.setIndex)?.representations?.getOrNull(track.representationIndex)
            ?: track.representation
        DashManifestParser.timedPlan(from, period, representation, policy, now)?.let { return it }
        val base = checkNotNull(representation.segmentBase) { "a representation without segments" }
        return indexed.getOrPut(track.address) { indexedPlan(representation, base) }
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

    /** A playlist written in memory, read under its own address. */
    private class MemoryMediaIo(private val bytes: ByteArray, override val location: String) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override val contentType: String get() = HLS_MEDIA_TYPE

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
    }
}
