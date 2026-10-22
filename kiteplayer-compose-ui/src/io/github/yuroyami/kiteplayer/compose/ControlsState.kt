package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Play and pause, and the moves through a queue, for a play button and its neighbours.
 *
 * Made by [rememberTransportState]. It reads the player's state and calls its own commands, so a
 * play button drawn from it shows what the player does, whichever control asked.
 */
@Stable
public class TransportState internal constructor(private val source: ControlsSource) {

    /**
     * True unless the player was asked to play, so the button offers play. A player that buffers
     * after a play shows pause, which answers the listener's own request, and an ended player
     * shows play, which starts the item again.
     */
    public val showsPlay: Boolean get() = !source.snapshot.playRequested

    /** True when the queue has an item before this one, counting a queue that repeats as round. */
    public val canGoPrevious: Boolean get() = queueStep(-1)

    /** True when the queue has an item after this one, counting a queue that repeats as round. */
    public val canGoNext: Boolean get() = queueStep(1)

    /** Plays when [showsPlay], and pauses otherwise. */
    public fun togglePlay() {
        if (showsPlay) source.send("play") { play() } else source.send("pause") { pause() }
    }

    /** Opens the previous item of the queue, keeping the play or pause intent. */
    public fun previous() {
        source.launch("previous") { previous() }
    }

    /** Opens the next item of the queue, keeping the play or pause intent. */
    public fun next() {
        source.launch("next") { next() }
    }

    private fun queueStep(direction: Int): Boolean {
        val snapshot = source.snapshot
        if (snapshot.queue.size < 2 || snapshot.queueIndex < 0) return false
        if (snapshot.loop == LoopMode.All) return true
        // Next and previous follow the play order, which a shuffle changes.
        val order = snapshot.queueOrder.ifEmpty { snapshot.queue.indices.toList() }
        val place = order.indexOf(snapshot.queueIndex)
        if (place < 0) return false
        return (place + direction) in order.indices
    }
}

/**
 * A seek bar: where the player is, how much of the item is buffered, and a scrub that moves it.
 *
 * Made by [rememberSeekBarState]. [fraction] and the buffered ranges are parts of the duration, from
 * 0 to 1. A scrub starts with [startScrub], moves with [scrubTo] and ends with [endScrub] or
 * [cancelScrub]. While it lasts, the bar shows the scrub target instead of the position, and each
 * step asks the player to seek there with [SeekMode.KeyframeThenRefine], which shows the nearest
 * picture at once and settles on the exact one a moment later. The player merges requests that come
 * faster than it can serve them, and refines the last one, so the end of a scrub asks for nothing
 * more. A cancelled scrub goes back to where it started.
 */
@Stable
public class SeekBarState internal constructor(
    private val source: ControlsSource,
    private val previews: CoroutineScope,
) {
    private var scrubStart: Duration? = null
    private var previewJob: Job? = null

    /** Where the scrub would land, or null when no scrub is running. */
    public var scrubTarget: Duration? by mutableStateOf(null)
        private set

    /**
     * The seek bar picture for [scrubTarget], or null when there is no scrub or the item has no
     * pictures. See [KitePlayer.thumbnailAt].
     */
    public var preview: StreamThumbnail? by mutableStateOf(null)
        private set

    /** Where the player is. */
    public val position: Duration get() = source.progress.position

    /** How long the item is, or null when it is live or not measured. */
    public val duration: Duration? get() = source.snapshot.duration?.takeIf { it > Duration.ZERO }

    /** True when the player can seek and the item has a duration to place a target against. */
    public val seekable: Boolean get() = source.snapshot.seekable && duration != null

    /** True while a scrub runs. */
    public val scrubbing: Boolean get() = scrubTarget != null

    /** Where the bar's thumb stands: the scrub target while a scrub runs, else the position. */
    public val fraction: Float get() = fractionOf(scrubTarget ?: position)

    /**
     * The buffered parts of the item, each as a range of [fraction]s. When the player reports no
     * ranges, as for HLS or with the byte cache off, this is one range from the position to as far
     * ahead as the demuxer has read.
     */
    public val buffered: List<ClosedFloatingPointRange<Float>>
        get() {
            val total = duration ?: return emptyList()
            val progress = source.progress
            val ranges = progress.bufferedRanges.ifEmpty {
                if (progress.bufferedAhead > Duration.ZERO) {
                    listOf(progress.position..progress.position + progress.bufferedAhead)
                } else {
                    emptyList()
                }
            }
            return ranges.map { fractionOf(it.start, total)..fractionOf(it.endInclusive, total) }
                .filter { it.endInclusive > it.start }
        }

    /** Starts a scrub at [fraction] of the duration. Ignored when the item cannot seek. */
    public fun startScrub(fraction: Float) {
        if (!seekable) return
        if (scrubStart == null) scrubStart = position
        moveTarget(fraction)
    }

    /** Moves a running scrub to [fraction] of the duration. Ignored when no scrub runs. */
    public fun scrubTo(fraction: Float) {
        if (scrubStart == null || !seekable) return
        moveTarget(fraction)
    }

    /** Ends a scrub where it stands. The player is already on its way there. */
    public fun endScrub() {
        finishScrub()
    }

    /** Ends a scrub and returns the player to where it was when the scrub started. */
    public fun cancelScrub() {
        val start = scrubStart ?: return
        val moved = scrubTarget != null
        finishScrub()
        if (moved) seek(start)
    }

    /** Jumps [delta] from the position, or from the scrub target while a scrub runs. */
    public fun stepBy(delta: Duration) {
        val total = duration
        if (!seekable || total == null) return
        val from = scrubTarget ?: source.target.position()
        val to = (from + delta).coerceIn(Duration.ZERO, total)
        if (scrubStart != null) {
            moveTarget(fractionOf(to, total))
        } else {
            seek(to)
        }
    }

    private fun moveTarget(fraction: Float) {
        val total = duration ?: return
        // Whole milliseconds: a Float fraction carries noise in its last digits, which no seek needs.
        val to = (total * fraction.coerceIn(0f, 1f).toDouble()).inWholeMilliseconds.milliseconds
        if (to == scrubTarget) return
        scrubTarget = to
        seek(to)
        fetchPreview(to)
    }

    private fun finishScrub() {
        scrubStart = null
        scrubTarget = null
        previewJob?.cancel()
        previewJob = null
        preview = null
    }

    private fun seek(to: Duration) {
        source.send("requestSeek") { requestSeek(to, SeekMode.KeyframeThenRefine) }
    }

    private fun fetchPreview(at: Duration) {
        val snapshot = source.snapshot
        // Asking costs nothing when the item has no pictures, but a coroutine per drag step is still waste.
        if (snapshot.tracks.thumbnails == null && snapshot.media?.thumbnails == null) return
        previewJob?.cancel()
        previewJob = previews.launch {
            val picture = try {
                source.target.thumbnailAt(at)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (scrubTarget == at) preview = picture
        }
    }

    private fun fractionOf(value: Duration): Float = duration?.let { fractionOf(value, it) } ?: 0f

    private fun fractionOf(value: Duration, total: Duration): Float = (value / total).toFloat().coerceIn(0f, 1f)
}

/** One entry of a [TrackMenuState]: what it is called, and whether it is the one in use. */
@Stable
public class TrackMenuOption internal constructor(
    /** The words for this option, from [KitePlayerControlsLabels]. */
    public val label: String,
    /** True for the option the player uses now. */
    public val selected: Boolean,
    internal val apply: (ControlsSource) -> Unit,
) {
    override fun toString(): String = "TrackMenuOption($label${if (selected) ", selected" else ""})"
}

/**
 * A menu of choices: the audio or subtitle tracks, the qualities, or the speeds.
 *
 * Made by [rememberTrackMenuState], [rememberQualityMenuState] and [rememberSpeedMenuState]. Every
 * choice is listed, each with its own name, so a listener picks one rather than cycling through
 * them one press at a time.
 */
@Stable
public class TrackMenuState internal constructor(
    private val source: ControlsSource,
    private val build: (ControlsSource) -> List<TrackMenuOption>,
) {
    /** The choices, in the order a menu shows them. */
    public val options: List<TrackMenuOption> get() = build(source)

    /** Asks the player for [option]. */
    public fun select(option: TrackMenuOption) {
        option.apply(source)
    }
}

/**
 * The volume, as a slider shows it.
 *
 * Made by [rememberVolumeState]. [level] follows hearing rather than amplitude, as mpv's volume
 * does: the player's volume is the level cubed, so half the slider is an eighth of the amplitude,
 * about 18 dB down, and the slider's middle sounds like the middle. A volume above 1, which only a
 * raised volume ceiling allows, shows as a full slider and is left alone until the slider moves.
 */
@Stable
public class VolumeState internal constructor(private val source: ControlsSource) {

    /** From 0, silence, to 1, the volume at unity. */
    public val level: Float get() = levelOf(source.snapshot.volume)

    /** True while the player is muted. */
    public val muted: Boolean get() = source.snapshot.muted

    /** Sets the volume to [level] cubed. Moving the slider above 0 also takes the mute off. */
    public fun setLevel(level: Float) {
        val wanted = level.coerceIn(0f, 1f)
        source.send("setVolume") { setVolume(wanted * wanted * wanted) }
        if (wanted > 0f && muted) source.send("setMuted") { setMuted(false) }
    }

    /** Mutes, or takes the mute off. The volume stays where it is. */
    public fun toggleMute() {
        val next = !muted
        source.send("setMuted") { setMuted(next) }
    }

    internal companion object {
        fun levelOf(volume: Float): Float = cbrt(volume.coerceIn(0f, 1f))
    }
}

/**
 * Whether the controls show, and when they hide by themselves.
 *
 * Made by [rememberControlsVisibility]. The controls hide after its timeout only while the player
 * plays. [show] starts the timeout again, so every press, key and scrub keeps them up, and a tap
 * on the picture and the timeout hide them the same way, through [hide].
 */
@Stable
public class ControlsVisibility internal constructor(visible: Boolean = true) {

    /** True while the controls show. */
    public var visible: Boolean by mutableStateOf(visible)
        private set

    /** Bumped by every [show], so the timeout starts again. */
    internal var interactions: Int by mutableIntStateOf(0)
        private set

    /** True while something holds the controls up, such as a scrub or an open menu. */
    internal var held: Boolean by mutableStateOf(false)

    /** Shows the controls and starts the timeout again. */
    public fun show() {
        visible = true
        interactions++
    }

    /** Hides the controls. */
    public fun hide() {
        visible = false
    }

    /** Hides the controls when they show, and shows them otherwise. */
    public fun toggle() {
        if (visible) hide() else show()
    }
}

/** The speeds [rememberSpeedMenuState] offers unless it is given others. */
internal val DefaultSpeeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** Play, pause, previous and next for [player]. */
@Composable
public fun rememberTransportState(player: KitePlayer): TransportState =
    rememberTransportState(rememberControlsSource(player))

@Composable
internal fun rememberTransportState(source: ControlsSource): TransportState = remember(source) { TransportState(source) }

/** The seek bar of [player]. */
@Composable
public fun rememberSeekBarState(player: KitePlayer): SeekBarState = rememberSeekBarState(rememberControlsSource(player))

@Composable
internal fun rememberSeekBarState(source: ControlsSource): SeekBarState {
    val previews = rememberCoroutineScope()
    return remember(source, previews) { SeekBarState(source, previews) }
}

/**
 * A menu of [player]'s tracks of [kind], audio or subtitles. Subtitles have an off option first.
 * Each track is named by [KitePlayerControlsLabels.trackName] from [labels].
 *
 * @throws IllegalArgumentException for [TrackKind.Video], which is not a menu the controls offer.
 */
@Composable
public fun rememberTrackMenuState(
    player: KitePlayer,
    kind: TrackKind,
    labels: KitePlayerControlsLabels = DefaultControlsLabels,
): TrackMenuState = rememberTrackMenuState(rememberControlsSource(player), kind, labels)

@Composable
internal fun rememberTrackMenuState(source: ControlsSource, kind: TrackKind, labels: KitePlayerControlsLabels): TrackMenuState {
    require(kind != TrackKind.Video) { "the controls offer menus for audio and subtitles, not video" }
    return remember(source, kind, labels) { TrackMenuState(source) { trackOptions(it, kind, labels) } }
}

/** A menu of [player]'s qualities: an automatic option first, then each variant of the stream. */
@Composable
public fun rememberQualityMenuState(
    player: KitePlayer,
    labels: KitePlayerControlsLabels = DefaultControlsLabels,
): TrackMenuState = rememberQualityMenuState(rememberControlsSource(player), labels)

@Composable
internal fun rememberQualityMenuState(source: ControlsSource, labels: KitePlayerControlsLabels): TrackMenuState =
    remember(source, labels) { TrackMenuState(source) { qualityOptions(it, labels) } }

/**
 * A menu of speeds for [player], from [speeds], each within the player's own speed range. The
 * default offers half speed to double speed.
 */
@Composable
public fun rememberSpeedMenuState(
    player: KitePlayer,
    speeds: List<Double> = DefaultSpeeds,
    labels: KitePlayerControlsLabels = DefaultControlsLabels,
): TrackMenuState = rememberSpeedMenuState(rememberControlsSource(player), speeds, labels)

@Composable
internal fun rememberSpeedMenuState(source: ControlsSource, speeds: List<Double>, labels: KitePlayerControlsLabels): TrackMenuState =
    remember(source, speeds, labels) { TrackMenuState(source) { speedOptions(it, speeds, labels) } }

/** The volume and the mute of [player]. */
@Composable
public fun rememberVolumeState(player: KitePlayer): VolumeState = rememberVolumeState(rememberControlsSource(player))

@Composable
internal fun rememberVolumeState(source: ControlsSource): VolumeState = remember(source) { VolumeState(source) }

/**
 * Whether the controls of [player] show. They hide [timeout] after the last interaction, and only
 * while the player plays; a paused player keeps them up.
 */
@Composable
public fun rememberControlsVisibility(player: KitePlayer?, timeout: Duration = 3.seconds): ControlsVisibility {
    val visibility = remember { ControlsVisibility() }
    val playing = player?.state?.collectAsState()?.value?.playRequested ?: false
    ControlsTimeout(visibility, playing, timeout)
    return visibility
}

@Composable
internal fun ControlsTimeout(visibility: ControlsVisibility, playing: Boolean, timeout: Duration) {
    LaunchedEffect(visibility, visibility.visible, visibility.interactions, visibility.held, playing, timeout) {
        if (visibility.visible && playing && !visibility.held) {
            delay(timeout)
            visibility.hide()
        }
    }
}

internal fun trackOptions(source: ControlsSource, kind: TrackKind, labels: KitePlayerControlsLabels): List<TrackMenuOption> {
    val tracks = source.snapshot.tracks
    val selected = tracks.selected(kind)
    val listed = tracks.all.filter { it.kind == kind }.map { track ->
        TrackMenuOption(labels.trackName(track), track.id == selected, selectTrack(kind, track.id))
    }
    if (kind != TrackKind.Subtitle || listed.isEmpty()) return listed
    return listOf(TrackMenuOption(labels.subtitlesOff, selected == null, selectTrack(kind, null))) + listed
}

private fun selectTrack(kind: TrackKind, id: TrackId?): (ControlsSource) -> Unit = { source ->
    source.launch("selectTrack") { selectTrack(kind, id) }
}

internal fun qualityOptions(source: ControlsSource, labels: KitePlayerControlsLabels): List<TrackMenuOption> {
    val snapshot = source.snapshot
    val variants = snapshot.tracks.variants
    if (variants.isEmpty()) return emptyList()
    // A chosen variant is kept on the item; without one the player picks and steps by itself.
    val chosen = snapshot.media?.demux?.variant?.takeIf { index -> variants.any { it.index == index } }
    val automatic = TrackMenuOption(labels.automaticQuality, chosen == null) { it.launch("selectVariant") { selectVariant(null) } }
    return listOf(automatic) + variants.map { variant ->
        TrackMenuOption(labels.variantName(variant), variant.index == chosen) {
            it.launch("selectVariant") { selectVariant(variant.index) }
        }
    }
}

internal fun speedOptions(source: ControlsSource, speeds: List<Double>, labels: KitePlayerControlsLabels): List<TrackMenuOption> {
    val current = source.snapshot.speed
    return speeds.filter { it >= KitePlayer.SPEED_MIN && it <= KitePlayer.SPEED_MAX }.map { speed ->
        TrackMenuOption(labels.speedName(speed), abs(speed - current) < SPEED_MATCH) {
            it.send("setSpeed") { setSpeed(speed) }
        }
    }
}

/** Close enough to call a speed the one that plays: a speed set from text can carry rounding. */
private const val SPEED_MATCH = 1e-6
