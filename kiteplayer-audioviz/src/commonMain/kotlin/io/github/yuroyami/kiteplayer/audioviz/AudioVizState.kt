package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSwitches
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderQuality
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderStats
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.asFuture
import kotlin.time.TimeSource

/**
 * What the visualiser shows and how: the drawing, the palette, whether the director picks drawings
 * with the music, and the finishing pass. Make one with [rememberAudioVizState] and show it with
 * [KiteAudioViz].
 */
@Stable
public class AudioVizState internal constructor(private val positionMicros: () -> Long) {

    /** Every drawing, in catalogue order. */
    public val catalogue: List<Visualization> = VizCatalog.create()

    /** Changes the drawing on the song's phrases while [directed] is on. */
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

    /** The drawing on screen now: the director's choice while [directed] is on, otherwise [drawing]. */
    public val showing: Visualization get() = if (directing) director.current else chosen

    /** Changes a couple of genes in the recipe of the drawing on screen, now rather than on the next phrase. */
    public fun mutate() {
        showing.genes?.mutateNow()
    }

    internal val tap = AudioVizFeed()

    /** The analyses already made and not yet heard, for drawings that move ahead of a beat. */
    internal val future: VizFuture = tap.timeline.asFuture { positionMicros() + DISPLAY_LEAD_MICROS }

    /** The analysis the picture draws this frame. */
    internal var frame: SpectrumFrame by mutableStateOf(SILENT)
        private set

    private var previousMicros = -1L

    /** Samples the analysis for the moment this frame reaches the eye. Called once a display frame. */
    internal fun nextFrame(): SpectrumFrame {
        val at = positionMicros() + DISPLAY_LEAD_MICROS
        val next = tap.timeline.sample(at, previousMicros) ?: SILENT
        previousMicros = at
        frame = next
        return next
    }
}

/**
 * An [AudioVizState] that listens to [player] for as long as it stays in the composition.
 *
 * Remember it next to the player rather than inside the audio-only branch, so the analysis is
 * already running when a song starts.
 */
@Composable
public fun rememberAudioVizState(player: KitePlayer): AudioVizState {
    val state = remember(player) {
        val started = TimeSource.Monotonic.markNow()
        val clock = SmoothClock(
            published = { player.position().inWholeMicroseconds },
            rate = { player.state.value.let { if (it.status == PlaybackStatus.Playing) it.speed else 0.0 } },
            nanos = { started.elapsedNow().inWholeNanoseconds },
        )
        AudioVizState(clock::micros)
    }
    DisposableEffect(player, state) {
        player.attachAudioTap(state.tap)
        onDispose { player.detachAudioTap(state.tap) }
    }
    return state
}

/** About one frame at sixty a second: a drawn frame reaches the screen that long after it is drawn. */
private const val DISPLAY_LEAD_MICROS = 16_000L

private val SILENT = SpectrumFrame.silent(BAND_COUNT, SCOPE_POINTS)
