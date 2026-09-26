@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioScanRange
import io.github.yuroyami.kiteplayer.AudioScanSink
import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which items a background song scan may read. Local files scan by default; a network URI and an
 * item with its own reader factory need the application's explicit consent, because a scan reads
 * the whole track a second time. Live and read-once items never scan.
 */
@AudioVizAuthoringApi
public class SongScanPolicy(
    /** Files on this device, named by a `file:` URI or a plain path. */
    public val localFiles: Boolean = true,
    /** Any other URI, such as `https:`. The application owns the extra download. */
    public val network: Boolean = false,
    /** Items that bring their own reader factory, which must allow a second concurrent open. */
    public val customReaders: Boolean = false,
) {
    internal fun allows(media: MediaItem): Boolean = when {
        media.io != null -> customReaders
        uriScheme(media.uri).let { it == null || it == "file" } -> localFiles
        else -> network
    }

    public companion object {
        public val Default: SongScanPolicy = SongScanPolicy()
        public val Off: SongScanPolicy = SongScanPolicy(localFiles = false)
    }
}

/** The URI's scheme in lower case, or null for a plain path, including a Windows drive path. */
internal fun uriScheme(uri: String): String? {
    val colon = uri.indexOf(':')
    if (colon < 2) return null
    val scheme = uri.substring(0, colon)
    return if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } && scheme[0].isLetter()) scheme.lowercase() else null
}

/** What the player is playing, as far as a scan needs to know. */
internal class ScanTarget(
    val media: MediaItem,
    val track: TrackId?,
    val stream: TrackInfo?,
    val durationMicros: Long?,
    val seekable: Boolean,
) {
    /** A scan restarts only when the item, its track or its readiness changes. */
    val identity: Triple<MediaItem, TrackId?, Boolean> get() = Triple(media, track, durationMicros != null && seekable)
}

/** What one scan decoded: the track, whether it reached the end, and where its last block ended. */
internal class ScanOutcome(val track: TrackId, val reachedEnd: Boolean, val endMicros: Long = 0L)

/** Where scan targets come from and how a scan reads them. The player is the production source. */
internal interface SongScanSource {
    val targets: Flow<ScanTarget?>

    /** Decodes [range] of the target, or all of it when [range] is null. */
    suspend fun scan(target: ScanTarget, range: AudioScanRange?, sink: AudioScanSink): ScanOutcome
}

internal class PlayerScanSource(private val player: KitePlayer) : SongScanSource {
    override val targets: Flow<ScanTarget?> = player.state.map { snapshot ->
        val media = snapshot.media ?: return@map null
        val track = snapshot.tracks.selectedAudio
        ScanTarget(media, track, track?.let(snapshot.tracks::find), snapshot.duration?.inWholeMicroseconds, snapshot.seekable)
    }

    override suspend fun scan(target: ScanTarget, range: AudioScanRange?, sink: AudioScanSink): ScanOutcome =
        player.scanAudio(target.media, target.track, range, sink)
            .let { ScanOutcome(it.track, it.reachedEnd, it.endPts?.micros ?: 0L) }
}

/** How many ranges of one song may be scanned at once. Half the cores, so playback keeps its own. */
internal expect fun scanWorkerCount(): Int

/** A map handed to the analysis worker, with its cache key and what to do if live audio disagrees. */
internal class MapInstall(val map: SongMap, val key: String, val identity: Long, val onRejected: () -> Unit)

/** Where a finished map goes. The analysis feed is the production destination. */
internal fun interface SongMapTarget {
    fun install(install: MapInstall?)
}

/** At most one scan at a time in the process, whatever the number of players and views. */
internal object SongScanLimiter {
    val mutex: Mutex = Mutex()
}

/** A bounded session cache of the sixteen newest maps. Keys hold a hash of the URI, never the URI. */
internal object SongMapCache {
    private val maps = AtomicReference<List<Pair<String, SongMap>>>(emptyList())

    fun get(key: String): SongMap? = maps.load().lastOrNull { it.first == key }?.second

    fun put(key: String, map: SongMap) {
        while (true) {
            val old = maps.load()
            val next = (old.filterNot { it.first == key } + (key to map)).takeLast(CAPACITY)
            if (maps.compareAndSet(old, next)) return
        }
    }

    fun remove(key: String) {
        while (true) {
            val old = maps.load()
            if (old.none { it.first == key }) return
            if (maps.compareAndSet(old, old.filterNot { it.first == key })) return
        }
    }

    internal fun clear() = maps.store(emptyList())

    internal val size: Int get() = maps.load().size

    const val CAPACITY = 16

    /** Hash of the URI, stream identity and analysis version. Headers never enter it. */
    fun key(target: ScanTarget): String {
        var hash = FNV_OFFSET
        for (char in target.media.uri) {
            hash = hash xor char.code.toLong()
            hash *= FNV_PRIME
        }
        val stream = target.stream
        return "${hash.toULong().toString(16)}/${target.track?.value}/${target.durationMicros}/" +
            "${stream?.sampleRate}/${stream?.channels}/${SongMap.VERSION}"
    }

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}

/**
 * Follows one player's item and audio track, scans eligible items in the background and hands
 * finished maps to [destination]. A new item or track cancels the old scan and removes its map.
 *
 * A map that [store] already holds is installed without decoding anything, which is the whole
 * point of keeping one. Otherwise the song is cut into ranges and scanned on several workers at
 * once, because a map that lands after the song has finished helps nobody.
 */
internal class SongScanner(
    private val source: SongScanSource,
    private val destination: SongMapTarget,
    private val policy: () -> SongScanPolicy,
    private val scanDispatcher: CoroutineDispatcher,
    private val settle: kotlin.time.Duration = 1.seconds,
    private val store: () -> SongMapStore = { SongMapStore.None },
    private val workers: () -> Int = ::scanWorkerCount,
    private val wait: suspend (kotlin.time.Duration) -> Unit = { delay(it) },
    /** Emits when [policy] may have changed, so an unchanged item is looked at again (#289). */
    private val policyChanges: Flow<Any?> = flowOf(Unit),
) {
    private var nextIdentity = 1L
    private var scope: CoroutineScope? = null

    /** The item whose map is installed, so a policy change for it needs no second read. */
    private var mappedIdentity: Any? = null

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            // Paired with whether the policy allows the item now. A policy change that leaves the
            // current item's answer alone does not restart its scan.
            combine(source.targets, policyChanges) { target, _ ->
                target to (target != null && policy().allows(target.media))
            }
                .distinctUntilChanged { old, new -> old.first?.identity == new.first?.identity && old.second == new.second }
                .collectLatest { (target, _) -> follow(target) }
        }
    }

    private suspend fun follow(target: ScanTarget?) {
        // The item already has its map: a change of policy only governs new reads.
        if (target != null && target.identity == mappedIdentity) return
        // Whatever map was installed described the previous item or track.
        destination.install(null)
        mappedIdentity = null
        if (target == null || target.durationMicros == null || !target.seekable) return
        if (!policy().allows(target.media)) return
        wait(settle)
        // Asked again after every wait: the policy may have been withdrawn meanwhile (#289).
        if (!policy().allows(target.media)) return
        val key = SongMapCache.key(target)
        SongMapCache.get(key)?.let { return install(it, key, target) }
        kept(key)?.let {
            SongMapCache.put(key, it)
            return install(it, key, target)
        }
        val map = try {
            SongScanLimiter.mutex.withLock { if (policy().allows(target.media)) scan(target) else null } ?: return
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // One item that cannot be scanned must not end the scans of every later item.
            KiteLog.log("SongScanner", "the song scan failed, so this item plays without a song map: $failure")
            return
        }
        if (map.complete) {
            SongMapCache.put(key, map)
            offWorker { store().write(key, encodeSongMap(map)) }
        }
        install(map, key, target)
    }

    /** The stored map for [key], when there is one this release can read. */
    private suspend fun kept(key: String): SongMap? = offWorker { store().read(key)?.let(::decodeSongMap) }

    /**
     * Runs store work off the analysis worker.
     *
     * A store that fails is a cache miss and nothing more, but a cancelled scan must stay
     * cancelled, so that one failure is passed on rather than swallowed with the rest.
     */
    private suspend fun <T> offWorker(work: suspend () -> T): T? = withContext(scanDispatcher) {
        try {
            work()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
    }

    private fun install(map: SongMap, key: String, target: ScanTarget) {
        mappedIdentity = target.identity
        val holder = scope
        destination.install(MapInstall(map, key, nextIdentity++) {
            SongMapCache.remove(key)
            // Live audio disagreed with it, so the stored copy is wrong too and must not come back.
            holder?.launch { offWorker { store().remove(key) } }
        })
    }

    /**
     * Scans [target] and builds its map, on several workers when the song is long enough to gain.
     *
     * A worker analyses [SongMapBuilder.WARM_UP_MICROS] before the stretch it owns and a little
     * past its end, so every detector is warm at a seam and the section detector can still look
     * ahead. Seeking is the risk: a container that seeks by estimate can land late enough to leave
     * a hole, so the parts are checked for continuity and a short one falls back to one pass.
     */
    private suspend fun scan(target: ScanTarget): SongMap {
        val duration = target.durationMicros ?: 0L
        val count = chunkCount(duration)
        if (count > 1) {
            val ranges = (0 until count).map { index ->
                (index * duration / count) to if (index == count - 1) duration else (index + 1) * duration / count
            }
            val scanned = try {
                coroutineScope {
                    ranges.mapIndexed { index, (from, until) ->
                        async(scanDispatcher) { scanRange(target, from, until, last = index == count - 1) }
                    }.awaitAll()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A backend that cannot open the item twice, or a seek it refuses: one pass still works.
                null
            }
            // Only the seams have to be proven. A declared duration is often a little longer than
            // the audio really is, so the end is proven by reaching it, not by counting up to it.
            val whole = scanned?.takeIf { results ->
                results.dropLast(1).all { it.part.coveredThroughMicros >= it.ownedUntil } &&
                    results.last().outcome.reachedEnd
            }
            if (whole != null) {
                return mergeSongMapParts(whole.map { it.part }, whole.first().outcome.track, complete = true)
            }
        }
        return sequential(target)
    }

    /** One pass over the whole song, which is what a short song and a failed seam both get. */
    private suspend fun sequential(target: ScanTarget): SongMap = withContext(scanDispatcher) {
        val builder = SongMapBuilder(target.track ?: TrackId(-1))
        val outcome = source.scan(target, null) { pts, interleaved, frames, format ->
            builder.feed(pts, interleaved, frames, format)
        }
        builder.build(outcome.reachedEnd, outcome.track)
    }

    private class RangeResult(val part: SongMapPart, val ownedUntil: Long, val outcome: ScanOutcome)

    private suspend fun scanRange(target: ScanTarget, from: Long, until: Long, last: Boolean): RangeResult {
        val builder = SongMapBuilder(
            target.track ?: TrackId(-1),
            ownedFromMicros = if (from == 0L) Long.MIN_VALUE else from,
            ownedUntilMicros = if (last) Long.MAX_VALUE else until,
        )
        val range = AudioScanRange(
            from = Pts((from - SongMapBuilder.WARM_UP_MICROS).coerceAtLeast(0L)),
            until = if (last) null else Pts(until + SongMapBuilder.LOOK_AHEAD_MICROS),
        )
        val outcome = source.scan(target, range) { pts, interleaved, frames, format ->
            builder.feed(pts, interleaved, frames, format)
        }
        return RangeResult(builder.part(), until, outcome)
    }

    /** Ranges are worth their warm-up only when each one is long, so a short song stays one pass. */
    private fun chunkCount(durationMicros: Long): Int {
        if (durationMicros <= 0L) return 1
        val byLength = (durationMicros / MINIMUM_CHUNK_MICROS).toInt()
        return minOf(workers().coerceAtLeast(1), byLength).coerceIn(1, MAXIMUM_WORKERS)
    }

    private companion object {
        /** A range shorter than this spends more time warming up than it saves. */
        const val MINIMUM_CHUNK_MICROS = 60_000_000L
        const val MAXIMUM_WORKERS = 4
    }
}
