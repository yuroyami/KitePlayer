@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioScanSink
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** What one scan decoded: the track, and whether it reached the end. */
internal class ScanOutcome(val track: TrackId, val reachedEnd: Boolean)

/** Where scan targets come from and how a scan reads them. The player is the production source. */
internal interface SongScanSource {
    val targets: Flow<ScanTarget?>
    suspend fun scan(target: ScanTarget, sink: AudioScanSink): ScanOutcome
}

internal class PlayerScanSource(private val player: KitePlayer) : SongScanSource {
    override val targets: Flow<ScanTarget?> = player.state.map { snapshot ->
        val media = snapshot.media ?: return@map null
        val track = snapshot.tracks.selectedAudio
        ScanTarget(media, track, track?.let(snapshot.tracks::find), snapshot.duration?.inWholeMicroseconds, snapshot.seekable)
    }

    override suspend fun scan(target: ScanTarget, sink: AudioScanSink): ScanOutcome =
        player.scanAudio(target.media, target.track, sink).let { ScanOutcome(it.track, it.reachedEnd) }
}

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

/** Holds a scan to [share] of one core: busy wall time over total wall time, with sleeps added. */
internal class ScanPacer(
    private val share: Double,
    private val clock: MonotonicClock,
    private val sleep: suspend (Long) -> Unit = { delay(it.nanoseconds) },
) {
    private val start = clock.nanos()
    private var slept = 0L

    suspend fun pace() {
        val now = clock.nanos()
        val elapsed = now - start
        val busy = elapsed - slept
        val needed = (busy / share - elapsed).toLong()
        if (needed < MIN_SLEEP_NANOS) return
        sleep(needed)
        slept += clock.nanos() - now
    }

    private companion object {
        const val MIN_SLEEP_NANOS = 2_000_000L
    }
}

/**
 * Follows one player's item and audio track, scans eligible items in the background and hands
 * finished maps to [destination]. A new item or track cancels the old scan and removes its map.
 */
internal class SongScanner(
    private val source: SongScanSource,
    private val destination: SongMapTarget,
    private val policy: () -> SongScanPolicy,
    private val scanDispatcher: CoroutineDispatcher,
    private val clock: MonotonicClock = MonotonicClock.System,
    private val settle: kotlin.time.Duration = 1.seconds,
    private val pacerFactory: () -> ScanPacer = { ScanPacer(CPU_SHARE, clock) },
    private val wait: suspend (kotlin.time.Duration) -> Unit = { delay(it) },
) {
    private var nextIdentity = 1L

    fun start(scope: CoroutineScope): Job = scope.launch {
        source.targets
            .distinctUntilChanged { old, new -> old?.identity == new?.identity }
            .collectLatest { target -> follow(target) }
    }

    private suspend fun follow(target: ScanTarget?) {
        // Whatever map was installed described the previous item or track.
        destination.install(null)
        if (target == null || target.durationMicros == null || !target.seekable) return
        if (!policy().allows(target.media)) return
        wait(settle)
        val key = SongMapCache.key(target)
        val cached = SongMapCache.get(key)
        if (cached != null) {
            destination.install(MapInstall(cached, key, nextIdentity++) { SongMapCache.remove(key) })
            return
        }
        val map = SongScanLimiter.mutex.withLock {
            withContext(scanDispatcher) {
                val builder = SongMapBuilder(target.track ?: TrackId(-1))
                val pacer = pacerFactory()
                val result = source.scan(target) { pts, interleaved, frames, format ->
                    builder.feed(pts, interleaved, frames, format)
                    pacer.pace()
                }
                builder.build(result.reachedEnd, result.track)
            }
        }
        if (map.complete) SongMapCache.put(key, map)
        destination.install(MapInstall(map, key, nextIdentity++) { SongMapCache.remove(key) })
    }

    private companion object {
        const val CPU_SHARE = 0.25
    }
}
