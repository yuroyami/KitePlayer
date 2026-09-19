@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSwitches
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderQuality
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderStats
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * What the visualiser shows and how: the drawing, the palette, whether the director picks drawings
 * with the music, and the finishing pass. Make one with [rememberAudioVizState] and show it with
 * [KiteAudioViz].
 */
@Stable
public class AudioVizState internal constructor(feed: AudioVizFeed? = null, private val clock: () -> VizClockReading) {
    private var analysisFeed by mutableStateOf(feed)
    private var eventCursor = feed?.timeline?.eventCursor()

    /** Shared analysis diagnostics while this view is attached. Poll values for telemetry. */
    public val analysisStats: AudioAnalysisStats? get() = analysisFeed?.stats

    /** Shared event retention and detector completion, separate from this view's cursor. */
    public val eventStats: AudioEventHistoryStats? get() = analysisFeed?.timeline?.eventStats

    /** Events this view discarded as too late: over 30 ms for a transient, over 3 s for structure. */
    public val lateEventDiscards: Long get() = eventCursor?.lateDiscards ?: 0L

    /** Past events discarded on attachment, pause, discontinuity, long suspension or overflow. */
    public val catchUpEventDiscards: Long get() = eventCursor?.catchUpDiscards ?: 0L

    /** Live structural events this view dropped because a complete song map covered their time. */
    public val duplicateEventDiscards: Long get() = eventCursor?.duplicateDiscards ?: 0L

    internal fun bind(feed: AudioVizFeed?) {
        analysisFeed = feed
        eventCursor = feed?.timeline?.eventCursor()
        sampledAnalysisRevision = null
        sampledMicros = null
        frame = SILENT
    }

    /** Every drawing, in catalogue order. */
    public val catalogue: List<Visualization> = VizCatalog.create()

    /** Changes the drawing at supported musical boundaries while [directed] is on. */
    public val director: VizDirector = VizDirector(catalogue)

    private var chosen by mutableStateOf(catalogue.first())
    private var directing by mutableStateOf(false)

    /** The drawing to show. Setting it puts it on screen at once, and the director carries on from it. */
    public var drawing: Visualization
        get() = chosen
        set(value) {
            chosen = value
            director.show(value)
        }

    /** Whether the director changes the drawing with the music. Turning it off keeps what it was showing. */
    public var directed: Boolean
        get() = directing
        set(value) {
            if (directing && !value) chosen = director.current
            directing = value
        }

    /** The colours the drawings use. */
    public var palette: VizPalette by mutableStateOf(VizPalette.Prism)

    /** Parts of the finishing pass to leave out, for every drawing. */
    public var switches: PostSwitches by mutableStateOf(PostSwitches.All)

    /** How much of the canvas trailing drawings render at, and whether that may drop when frames run slow. */
    public val quality: RenderQuality = RenderQuality()

    /** How long each part of the last frame took. */
    public val stats: RenderStats = RenderStats()

    private var wantedDisplayDelay by mutableStateOf<Duration?>(null)

    /**
     * Estimated drawing-to-display delay. Null estimates one refresh period from frame callbacks,
     * initially 1/60 second. Set a measured route-specific estimate when one is available. Frame
     * callback timing is not presentation feedback; this value remains an estimate.
     */
    public var displayDelay: Duration?
        get() = wantedDisplayDelay
        set(value) {
            require(value == null || (value.isFinite() && value >= Duration.ZERO))
            wantedDisplayDelay = value
        }

    /** The estimate used by the latest sample, in presentation time. */
    public var estimatedDisplayDelay: Duration by mutableStateOf(INITIAL_REFRESH_NANOS.nanoseconds)
        private set

    /** The drawing on screen now: the director's choice while [directed] is on, otherwise [drawing]. */
    public val showing: Visualization get() = if (directing) director.current else chosen

    /** Changes a couple of genes in the recipe of the drawing on screen, now rather than at the next accepted boundary. */
    public fun mutate() {
        showing.genes?.mutateNow()
    }

    /** The analyses already made and not yet heard, for drawings that move ahead of a beat. */
    internal val future: VizFuture = object : VizFuture {
        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? {
            val at = sampledMicros ?: return null
            val feed = analysisFeed ?: return null
            if (sampledRate <= 0.0) return null
            val event = feed.timeline.nextEvent(at, kind) ?: return null
            if (event.generation != sampledGeneration || event.analysisRevision != sampledAnalysisRevision ||
                feed.timeline.generation != sampledGeneration || feed.timeline.revision != sampledAnalysisRevision) return null
            val seconds = ((event.detection.ptsMicros - at) / 1_000_000.0 / sampledRate).toFloat()
            return if (seconds in 0f..0.1f) UpcomingAudioEvent(event, seconds) else null
        }

        override fun at(secondsAhead: Float): SpectrumFrame? {
            val at = sampledMicros ?: return null
            val feed = analysisFeed ?: return null
            if (sampledRate <= 0.0 || secondsAhead !in 0f..0.1f) return null
            return feed.timeline.ahead(at, (secondsAhead * sampledRate).toFloat())
                ?.takeIf { it.generation == sampledGeneration && it.analysisRevision == sampledAnalysisRevision }
        }

        override val nextOnsetSeconds: Float
            get() {
                val at = sampledMicros ?: return -1f
                val feed = analysisFeed ?: return -1f
                if (sampledRate <= 0.0 || feed.timeline.generation != sampledGeneration || feed.timeline.revision != sampledAnalysisRevision) return -1f
                val mediaSeconds = feed.timeline.nextOnsetSeconds(at)
                val seconds = (mediaSeconds / sampledRate).toFloat()
                return if (seconds in 0f..0.1f) seconds else -1f
            }
    }

    /** The analysis the picture draws this frame. */
    internal var frame: SpectrumFrame by mutableStateOf(SILENT)
        private set

    private var sampledMicros: Long? = null
    private var sampledGeneration: Generation? = null
    private var sampledAnalysisRevision: Long? = null
    private var sampledRate = 0.0
    private var previousFrameNanos: Long? = null
    private var refreshNanos = INITIAL_REFRESH_NANOS
    private val refreshIntervals = LongArray(9)
    private val refreshScratch = LongArray(9)
    private var refreshCount = 0
    private var refreshIndex = 0

    /** Samples the analysis for the moment this frame reaches the eye. Called once a display frame. */
    internal fun nextFrame(frameTimeNanos: Long? = null): SpectrumFrame {
        if (frameTimeNanos != null) {
            val interval = previousFrameNanos?.let { frameTimeNanos - it }
            if (interval != null && interval > 250_000_000L) eventCursor?.reset()
            // A stall is not a new refresh rate. This estimates cadence only, never the media clock.
            if (interval != null && interval in 4_000_000L..50_000_000L) {
                refreshIntervals[refreshIndex] = interval
                refreshIndex = (refreshIndex + 1) % refreshIntervals.size
                refreshCount = minOf(refreshCount + 1, refreshIntervals.size)
                refreshIntervals.copyInto(refreshScratch, endIndex = refreshCount)
                refreshScratch.sort(0, refreshCount)
                // A missed callback is a multiple of the period, not evidence of a slower panel.
                refreshNanos = refreshScratch[(refreshCount - 1) / 2]
            }
            previousFrameNanos = frameTimeNanos
        }
        estimatedDisplayDelay = displayDelay ?: refreshNanos.nanoseconds
        val reading = clock()
        val feed = analysisFeed
        val revision = feed?.timeline?.revision
        val changed = sampledGeneration != reading.generation || sampledRate != reading.rate || sampledAnalysisRevision != revision
        sampledAnalysisRevision = revision
        sampledGeneration = reading.generation
        sampledRate = reading.rate
        val at = reading.positionMicros?.let {
            it + (estimatedDisplayDelay.inWholeNanoseconds / 1_000.0 * reading.rate).toLong()
        }
        sampledMicros = at
        if (at == null || feed == null || feed.timeline.generation != reading.generation) {
            eventCursor?.reset()
            frame = SILENT
            return frame
        }
        if (changed) eventCursor?.reset()
        val aligned = feed.timeline.interpolated(at)?.takeIf {
            it.generation == reading.generation && it.analysisRevision == revision
        }
        val held = reading.rate == 0.0
        val delivery = eventCursor?.sample(at, paused = held || aligned == null)
        val sampled = if (delivery != null) (aligned ?: SILENT).withDeliveredEvents(delivery) else aligned ?: SILENT
        // A paused picture keeps its levels but carries no beat progression.
        val next = if (held) sampled.withPulseHeld() else sampled
        frame = next
        return next
    }
}

/**
 * An [AudioVizState] that listens to [player] for as long as it stays in the composition.
 *
 * Remember it next to the player rather than inside the audio-only branch, so the analysis is
 * already running when a song starts. [songScan] decides which items a background scan may read
 * to build a song map; see docs/audioviz-song-scan-api.md.
 */
@Composable
public fun rememberAudioVizState(player: KitePlayer, songScan: SongScanPolicy = SongScanPolicy.Default): AudioVizState {
    val state = remember(player) {
        AudioVizState {
            val reading = player.audioClock()
            VizClockReading(reading.position?.micros, reading.rate, reading.generation)
        }
    }
    DisposableEffect(player, state) {
        val lease = playerAudioVizSessions.acquire(player)
        lease.feed.scanPolicy.store(songScan)
        state.bind(lease.feed)
        onDispose {
            state.bind(null)
            lease.close()
        }
    }
    return state
}

internal data class VizClockReading(
    val positionMicros: Long?,
    val rate: Double = 1.0,
    val generation: Generation = Generation.Initial,
)

private const val INITIAL_REFRESH_NANOS = 16_666_667L

private val SILENT = SpectrumFrame.silent(BAND_COUNT, SCOPE_POINTS)
