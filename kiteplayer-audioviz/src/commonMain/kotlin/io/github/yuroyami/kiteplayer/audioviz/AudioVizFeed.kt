@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** One shared analysis worker. The playback tap only validates, copies and signals bounded work. */
internal class AudioVizFeed(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    pcmQueue: AudioPcmQueue = AudioPcmQueue(),
    private val clock: MonotonicClock = MonotonicClock.System,
) : AudioTap, AutoCloseable, SongMapTarget {
    private class Epoch(val generation: Generation, val revision: Long)

    val timeline = SpectrumTimeline(TIMELINE_CAPACITY)
    private val epoch = AtomicReference(Epoch(Generation.Initial, 0L))
    private val closed = AtomicBoolean(false)
    private val queue = AtomicReference<AudioPcmQueue?>(pcmQueue)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("audioviz-analysis"))
    val stats = AudioAnalysisStats(pcmQueue.reservedBytes) { queue.load()?.pendingNanos ?: 0L }

    // One writer, the audio feed. A run of rejected blocks publishes one reset, not one per retry.
    private var gapOpen = false

    // Only the analysis coroutine owns these fields and every analyser buffer.
    private var handled: Epoch? = null
    private var analyzer: SpectrumAnalyzer? = null
    private var format: AudioFormat? = null
    private var expectedMicros: Long? = null

    // A song map handed over by the scanner, applied and verified on the analysis worker only.
    private val pendingMap = AtomicReference<MapInstall?>(null)
    private var appliedMap: MapInstall? = null
    private var verification: MapVerification? = null

    /** Which items a background scan may read. The newest view to configure it sets it. */
    val scanPolicy = AtomicReference(SongScanPolicy.Default)

    /** Where finished maps are kept between runs. The newest view to configure it sets it. */
    val mapStore = AtomicReference(SongMapStore.None)
    private val scanStarted = AtomicBoolean(false)

    /** Moves whenever [scanPolicy] changes what it allows, so the scanner looks again (#289). */
    private val policyVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * Sets the scan policy and the map store. A policy that allows the same kinds of item as the
     * current one changes nothing, so a view that builds a new policy on every composition does
     * not restart a scan. A new store takes the next write.
     */
    fun configureScan(policy: SongScanPolicy, store: SongMapStore) {
        val old = scanPolicy.exchange(policy)
        mapStore.store(store)
        val same = old.localFiles == policy.localFiles && old.network == policy.network &&
            old.customReaders == policy.customReaders
        if (!same) policyVersion.value++
    }

    /** Hands a finished map to the analysis worker, or removes the current one with null. Any thread. */
    override fun install(install: MapInstall?) {
        pendingMap.store(install)
        wake.trySend(Unit)
    }

    /** Starts following [source] for song scans, once, for as long as this feed lives. */
    fun startSongScan(source: SongScanSource, scanDispatcher: CoroutineDispatcher = Dispatchers.IO) {
        if (!scanStarted.compareAndSet(false, true) || closed.load()) return
        SongScanner(
            source, this, { scanPolicy.load() }, scanDispatcher,
            store = { mapStore.load() },
            policyChanges = policyVersion,
        ).start(scope)
    }

    private val worker = scope.launch {
        try {
            for (signal in wake) {
                val pending = queue.load() ?: break
                var batch = 0
                while (!closed.load()) {
                    val slot = pending.peek() ?: break
                    try {
                        val current = epoch.load()
                        if (slot.generation == current.generation && slot.revision == current.revision) {
                            process(slot, current)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        stats.failures.fetchAndAdd(1L)
                        handled = null
                        analyzer = null
                        expectedMicros = null
                        timeline.reset(epoch.load().generation)
                        stats.resets.fetchAndAdd(1L)
                    } finally {
                        pending.release(slot)
                    }
                    if (++batch == 4) {
                        batch = 0
                        yield()
                    }
                }
            }
        } finally {
            analyzer = null
            format = null
            handled = null
            expectedMicros = null
        }
    }

    val isClosed: Boolean get() = closed.load()
    internal suspend fun awaitClosed() { worker.join() }

    override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
        onAudio(epoch.load().generation, pts, interleaved, frames, format)
    }

    override fun onAudio(generation: Generation, pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
        if (frames == 0 || closed.load()) return
        var current = epoch.load()
        if (generation < current.generation) return
        if (generation > current.generation) current = resetEpoch(generation)
        val pending = queue.load() ?: return
        val started = clock.nanos()
        val result = if (format.channels > 64 || format.sampleRate !in 1_000..768_000) AudioPcmQueue.Offer.Invalid else
            pending.offer(current.generation, current.revision, pts.micros, interleaved, frames, format)
        if (result == AudioPcmQueue.Offer.Accepted) {
            stats.copies.fetchAndAdd(1L)
            gapOpen = false
        } else {
            stats.drops.fetchAndAdd(1L)
            stats.droppedFrames.fetchAndAdd(frames.coerceAtLeast(0).toLong())
            if (result == AudioPcmQueue.Offer.Invalid) stats.invalid.fetchAndAdd(1L)
            if (!gapOpen) {
                resetEpoch(generation)
                gapOpen = true
            }
        }
        // Wake even after a drop so the worker releases queued data from the retired local epoch.
        wake.trySend(Unit)
        val elapsed = (clock.nanos() - started).coerceAtLeast(0L)
        stats.copyTime.fetchAndAdd(elapsed)
        stats.copyMax.store(maxOf(stats.copyMax.load(), elapsed))
    }

    override fun onDiscontinuity() {
        onDiscontinuity(epoch.load().generation.next())
    }

    override fun onDiscontinuity(generation: Generation) {
        if (closed.load()) return
        resetEpoch(generation)
        wake.trySend(Unit)
    }

    private fun resetEpoch(generation: Generation): Epoch {
        while (true) {
            val old = epoch.load()
            if (generation < old.generation) return old
            val next = Epoch(generation, old.revision + 1L)
            if (epoch.compareAndSet(old, next)) {
                timeline.reset(generation)
                stats.resets.fetchAndAdd(1L)
                return next
            }
        }
    }

    private fun process(slot: AudioPcmQueue.Slot, current: Epoch) {
        val started = clock.nanos()
        val nextFormat = checkNotNull(slot.format)
        val jumped = expectedMicros?.let { abs(slot.ptsMicros - it) > JUMP_MICROS } ?: false
        var active = analyzer
        if (active == null || handled !== current || nextFormat != format || jumped) {
            timeline.reset(current.generation)
            stats.resets.fetchAndAdd(1L)
            active = if (active != null && nextFormat == format) active.apply { reset() }
                else newAnalyzer(nextFormat.sampleRate)
            val publication = timeline.publisher()
            active.generation = current.generation
            active.analysisRevision = publication.revision
            val owner = active
            active.onAnalysis = { frame ->
                if (!closed.load() && epoch.load() === current) {
                    verify(frame, owner)
                    if (publication.push(frame)) stats.analyses.fetchAndAdd(1L)
                }
            }
            // A new analyser starts from the causal reference unless a map is applied.
            active.setSongReferencePower(appliedMap?.map?.referencePower)
            analyzer = active
            format = nextFormat
            handled = current
        }
        var sanitised = 0L
        for (index in 0 until slot.frames * nextFormat.channels) {
            val sample = slot.samples[index]
            val safe = if (sample.isFinite()) sample.coerceIn(-16f, 16f) else 0f
            if (safe != sample) {
                slot.samples[index] = safe
                sanitised++
            }
        }
        if (sanitised > 0) stats.sanitized.fetchAndAdd(sanitised)
        applyMap(active)
        active.feed(slot.samples, slot.frames, nextFormat, slot.ptsMicros)
        expectedMicros = slot.ptsMicros + slot.frames * 1_000_000L / nextFormat.sampleRate
        stats.analysisTime.fetchAndAdd((clock.nanos() - started).coerceAtLeast(0L))
    }

    /** Worker only: brings the applied map up to the newest one the scanner handed over. */
    private fun applyMap(analyzer: SpectrumAnalyzer) {
        val pending = pendingMap.load()
        if (pending === appliedMap) return
        appliedMap = pending
        verification = pending?.let { MapVerification(it.map) }
        analyzer.setSongReferencePower(pending?.map?.referencePower)
        timeline.installSongMap(pending?.let {
            SongMapEvents(it.identity, it.map.curveStartMicros, it.map.coveredThroughMicros, it.map.structureList())
        })
    }

    /** Worker only: compares live programme levels with the applied map and withdraws a map that disagrees. */
    private fun verify(frame: SpectrumFrame, analyzer: SpectrumAnalyzer) {
        val check = verification ?: return
        val install = appliedMap ?: return
        if (check.observe(frame) != MapVerification.Verdict.Rejected) return
        verification = null
        appliedMap = null
        pendingMap.compareAndSet(install, null)
        analyzer.setSongReferencePower(null)
        timeline.installSongMap(null)
        stats.rejectedMaps.fetchAndAdd(1L)
        install.onRejected()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resetEpoch(epoch.load().generation)
        queue.store(null)
        wake.close()
        scope.cancel()
    }

    private fun newAnalyzer(rate: Int) = SpectrumAnalyzer(
        bandCount = BAND_COUNT,
        scopePoints = SCOPE_POINTS,
        sampleRate = rate,
    )
}

internal const val BAND_COUNT = 40
internal const val SCOPE_POINTS = 256
private const val TIMELINE_CAPACITY = 256
// Some container timestamps are quantised to milliseconds. Known dropped blocks reset explicitly.
private const val JUMP_MICROS = 2_000L
