package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The menus of the default controls. */
public enum class PlayerControlsMenu { Audio, Subtitles, Quality, Speed }

/** One entry of a menu: what it is called, and whether it is the one in use. */
public data class PlayerControlsOption(
    /** The words for this option, from [PlayerControlsStrings]. */
    val label: String,
    /** True for the option the player uses now. */
    val selected: Boolean,
)

/**
 * Everything the default controls show at one moment, with nothing about how to draw it. A native
 * view draws its controls from this and from nothing else.
 *
 * [fraction] and [buffered] are parts of the duration, from 0 to 1. [volumeLevel] is the slider's
 * value, which follows hearing: the player's volume is the level cubed.
 */
public data class PlayerControlsSnapshot(
    /** True while the controls show. */
    val visible: Boolean = true,
    /** True when the play button offers play, and false when it offers pause. */
    val showsPlay: Boolean = true,
    /** True when a queue with more than one item is open, so previous and next have a place. */
    val hasQueue: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
    val position: Duration = Duration.ZERO,
    /** How long the item is, or null when it is live or not measured. */
    val duration: Duration? = null,
    /** True when the player can seek and the item has a duration, so the seek bar has a place. */
    val seekable: Boolean = false,
    /** Where a running scrub would land, or null when no scrub runs. */
    val scrubTarget: Duration? = null,
    /** Where the seek bar's thumb stands: the scrub target while a scrub runs, else the position. */
    val fraction: Float = 0f,
    /** The buffered parts of the item, each as a range of fractions. */
    val buffered: List<ClosedFloatingPointRange<Float>> = emptyList(),
    /** [position] as text. */
    val positionText: String = "0:00",
    /** [duration] as text, or null without one. */
    val durationText: String? = null,
    /** [scrubTarget] as text, or null when no scrub runs. */
    val scrubText: String? = null,
    /** What a screen reader says the seek bar stands at, in whole seconds. */
    val seekBarValueText: String = "0:00",
    /** The volume slider's value, from 0, silence, to 1, the volume at unity. */
    val volumeLevel: Float = 1f,
    val muted: Boolean = false,
    /** What a screen reader says the volume stands at. */
    val volumeText: String = "",
    val audioTracks: List<PlayerControlsOption> = emptyList(),
    /** The subtitle tracks, after an off option. Empty when the item has none. */
    val subtitleTracks: List<PlayerControlsOption> = emptyList(),
    /** The qualities, after an automatic option. Empty when the stream has no variants. */
    val qualities: List<PlayerControlsOption> = emptyList(),
    val speeds: List<PlayerControlsOption> = emptyList(),
    /** True when the application gave a full screen action. */
    val canFullScreen: Boolean = false,
    /** True when the application gave a picture in picture action. */
    val canPictureInPicture: Boolean = false,
) {
    /** The choices of [menu], in the order a menu shows them. */
    public fun options(menu: PlayerControlsMenu): List<PlayerControlsOption> = when (menu) {
        PlayerControlsMenu.Audio -> audioTracks
        PlayerControlsMenu.Subtitles -> subtitleTracks
        PlayerControlsMenu.Quality -> qualities
        PlayerControlsMenu.Speed -> speeds
    }

    /** True when [menu] leaves something to choose, so its button has a place. */
    public fun offers(menu: PlayerControlsMenu): Boolean = when (menu) {
        PlayerControlsMenu.Audio -> audioTracks.size > 1
        PlayerControlsMenu.Subtitles -> subtitleTracks.isNotEmpty()
        // The automatic option and one variant leave nothing to choose.
        PlayerControlsMenu.Quality -> qualities.size > 2
        PlayerControlsMenu.Speed -> speeds.isNotEmpty()
    }
}

/** The player as the controls use it, so the model can be tested against a script. */
internal interface ControlsPlayer {
    val state: StateFlow<PlayerSnapshot>
    val progress: StateFlow<Progress>
    fun position(): Duration
    fun play()
    fun pause()
    fun requestSeek(to: Duration, mode: SeekMode)
    fun setSpeed(value: Double)
    fun setVolume(value: Float)
    fun setMuted(value: Boolean)
    fun setSubtitlePosition(value: Float)
    suspend fun next()
    suspend fun previous()
    suspend fun selectTrack(kind: TrackKind, track: TrackId?)
    suspend fun selectVariant(index: Int?)
}

private class RealPlayer(private val player: KitePlayer) : ControlsPlayer {
    override val state: StateFlow<PlayerSnapshot> get() = player.state
    override val progress: StateFlow<Progress> get() = player.progress
    override fun position(): Duration = player.position()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun requestSeek(to: Duration, mode: SeekMode) = player.requestSeek(to, mode)
    override fun setSpeed(value: Double) = player.setSpeed(value)
    override fun setVolume(value: Float) = player.setVolume(value)
    override fun setMuted(value: Boolean) = player.setMuted(value)
    override fun setSubtitlePosition(value: Float) = player.setSubtitlePosition(value)
    override suspend fun next() = player.next()
    override suspend fun previous() = player.previous()
    override suspend fun selectTrack(kind: TrackKind, track: TrackId?) {
        player.selectTrack(kind, track)
    }
    override suspend fun selectVariant(index: Int?) = player.selectVariant(index)
}

/**
 * The default controls of a native view, without the drawing: what they show, as [state], and what
 * a press does, as the functions below. `KitePlayerView`, `KitePlayerUIView` and `KitePlayerAwtView`
 * each draw one of these with their own toolkit, and an application with a toolkit of its own can
 * do the same.
 *
 * It does what `KitePlayerControls` of `kiteplayer-compose-ui` does:
 * - The controls hide [hideAfter] after the last [poke], and only while the player plays. A scrub
 *   and a [hold] keep them up.
 * - A scrub starts with [beginScrub], moves with [moveScrub] and ends with [endScrub] or
 *   [cancelScrub]. Each step asks the player to seek with [SeekMode.KeyframeThenRefine], which
 *   shows the nearest picture at once and settles on the exact one a moment later.
 * - While the controls show, subtitles move up above the part of the view given as [barShare], and
 *   settle back when they hide, unless the application moved them itself in between.
 * - A command the player refuses is logged through `KiteLog` and otherwise ignored.
 *
 * Use every member from one thread, the one [scope] runs on. [close] stops it; the scope and the
 * player stay the caller's.
 */
public class PlayerControlsModel internal constructor(
    private val target: ControlsPlayer,
    private val scope: CoroutineScope,
    strings: PlayerControlsStrings,
    private val hideAfter: Duration,
    private val speeds: List<Double>,
    /**
     * Runs the commands that wait for the player, such as a move to the next item. It is not
     * [scope] on purpose: the player stops an open whose caller was cancelled.
     */
    private val commands: CoroutineScope,
) {
    /**
     * Controls for [player], collected on [scope], with the words of [strings]. They hide
     * [hideAfter] after the last interaction.
     */
    public constructor(
        player: KitePlayer,
        scope: CoroutineScope,
        strings: PlayerControlsStrings = PlayerControlsStrings.Default,
        hideAfter: Duration = 3.seconds,
    ) : this(RealPlayer(player), scope, strings, hideAfter, DefaultSpeeds, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private var visible = true
    private var held = false
    private var hideJob: Job? = null
    private var playRequested = target.state.value.playRequested
    private var scrubStart: Duration? = null
    private var scrubTarget: Duration? = null
    private val lift = SubtitleLift(target)
    private var menusOf: PlayerSnapshot? = null
    private var menus: Map<PlayerControlsMenu, Menu> = emptyMap()
    private var closed = false

    /** Every word the controls show or a screen reader says. Assigning rebuilds [state]. */
    public var strings: PlayerControlsStrings = strings
        set(value) {
            field = value
            menusOf = null
            publish()
        }

    /** What the full screen button does, or null for no such button. */
    public var onFullScreen: (() -> Unit)? = null
        set(value) {
            field = value
            publish()
        }

    /** What the picture in picture button does, or null for no such button. */
    public var onPictureInPicture: (() -> Unit)? = null
        set(value) {
            field = value
            publish()
        }

    /**
     * The part of the view's height that the controls cover at its bottom, from 0 to 1. While the
     * controls show, subtitles stand above it. 0, the default, moves nothing.
     */
    public var barShare: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            applyLift()
        }

    private val snapshots = MutableStateFlow(build())

    /** What the controls show now. */
    public val state: StateFlow<PlayerControlsSnapshot> = snapshots.asStateFlow()

    private val watch: Job = scope.launch {
        launch {
            target.state.collect { snapshot ->
                if (snapshot.playRequested != playRequested) {
                    playRequested = snapshot.playRequested
                    rearm()
                }
                publish()
            }
        }
        launch { target.progress.collect { publish() } }
    }

    init {
        rearm()
    }

    /** Plays when the button offers play, and pauses otherwise. */
    public fun togglePlay() {
        poke()
        if (!target.state.value.playRequested) send("play") { play() } else send("pause") { pause() }
    }

    /** Opens the previous item of the queue, keeping the play or pause intent. */
    public fun previous() {
        poke()
        launch("previous") { previous() }
    }

    /** Opens the next item of the queue, keeping the play or pause intent. */
    public fun next() {
        poke()
        launch("next") { next() }
    }

    /** Starts a scrub at [fraction] of the duration. Ignored when the item cannot seek. */
    public fun beginScrub(fraction: Float) {
        if (!seekable()) return
        if (scrubStart == null) scrubStart = target.progress.value.position
        moveTarget(fraction)
        poke()
    }

    /** Moves a running scrub to [fraction] of the duration. Ignored when no scrub runs. */
    public fun moveScrub(fraction: Float) {
        if (scrubStart == null || !seekable()) return
        moveTarget(fraction)
        publish()
    }

    /** Ends a scrub where it stands. The player is already on its way there. */
    public fun endScrub() {
        scrubStart = null
        scrubTarget = null
        poke()
    }

    /** Ends a scrub and returns the player to where it was when the scrub started. */
    public fun cancelScrub() {
        val start = scrubStart ?: return
        val moved = scrubTarget != null
        endScrub()
        if (moved) seek(start)
    }

    /** Jumps [delta] from the position, or from the scrub target while a scrub runs. */
    public fun stepBy(delta: Duration) {
        val total = duration()
        if (!seekable() || total == null) return
        val to = ((scrubTarget ?: target.position()) + delta).coerceIn(Duration.ZERO, total)
        if (scrubStart != null) moveTarget(fractionOf(to, total)) else seek(to)
        poke()
    }

    /**
     * Where a sideways drag over the picture puts a scrub that started at fraction [from], after the
     * finger moved [dragShare] of the picture's width. The width stands for the whole item, or for
     * ten minutes of a longer one.
     */
    public fun pictureScrubFraction(from: Float, dragShare: Float): Float {
        val total = duration() ?: return from
        return from + dragShare * (minOf(PictureScrubSpan, total) / total).toFloat()
    }

    /** Sets the volume to [level] cubed. Moving the slider above 0 also takes the mute off. */
    public fun setVolumeLevel(level: Float) {
        val wanted = level.coerceIn(0f, 1f)
        send("setVolume") { setVolume(volumeOf(wanted)) }
        if (wanted > 0f && target.state.value.muted) send("setMuted") { setMuted(false) }
        poke()
    }

    /** Mutes, or takes the mute off. The volume stays where it is. */
    public fun toggleMute() {
        val next = !target.state.value.muted
        send("setMuted") { setMuted(next) }
        poke()
    }

    /** Asks the player for option [index] of [menu], as [PlayerControlsSnapshot.options] lists them. */
    public fun select(menu: PlayerControlsMenu, index: Int) {
        poke()
        menus[menu]?.apply?.getOrNull(index)?.invoke()
    }

    /** Calls [onFullScreen]. */
    public fun toggleFullScreen() {
        poke()
        onFullScreen?.invoke()
    }

    /** Calls [onPictureInPicture]. */
    public fun enterPictureInPicture() {
        poke()
        onPictureInPicture?.invoke()
    }

    /** Shows the controls and starts the timeout again. Call it for every touch, key and pointer move. */
    public fun poke() {
        visible = true
        rearm()
        applyLift()
        publish()
    }

    /**
     * [poke], and true when the controls were hidden. The first key press while they are hidden
     * only shows them, as on a television, so a view that gets true does nothing more with the key.
     */
    public fun wake(): Boolean {
        val wasHidden = !visible
        poke()
        return wasHidden
    }

    /** Hides the controls. */
    public fun hide() {
        visible = false
        rearm()
        applyLift()
        publish()
    }

    /** Hides the controls when they show, and shows them otherwise, as a tap on the picture does. */
    public fun toggleVisible() {
        if (visible) hide() else poke()
    }

    /** True keeps the controls up, for as long as a menu is open. False starts the timeout again. */
    public fun hold(held: Boolean) {
        this.held = held
        rearm()
    }

    /** Stops following the player and puts subtitles back. Every later call does nothing useful. */
    public fun close() {
        if (closed) return
        closed = true
        watch.cancel()
        hideJob?.cancel()
        hideJob = null
        lift.settle()
    }

    private fun rearm() {
        hideJob?.cancel()
        hideJob = null
        if (closed || !visible || held || scrubStart != null || !playRequested) return
        hideJob = scope.launch {
            delay(hideAfter)
            hideJob = null
            hide()
        }
    }

    private fun applyLift() {
        if (closed) return
        if (visible && barShare > 0f) lift.raise((1f - barShare).coerceIn(SubtitleLift.LOWEST, 1f)) else lift.settle()
    }

    private fun publish() {
        if (!closed) snapshots.value = build()
    }

    private fun duration(): Duration? = target.state.value.duration?.takeIf { it > Duration.ZERO }

    private fun seekable(): Boolean = target.state.value.seekable && duration() != null

    private fun moveTarget(fraction: Float) {
        val total = duration() ?: return
        // Whole milliseconds: a Float fraction carries noise in its last digits, which no seek needs.
        val to = (total * fraction.coerceIn(0f, 1f).toDouble()).inWholeMilliseconds.milliseconds
        if (to == scrubTarget) return
        scrubTarget = to
        seek(to)
    }

    private fun seek(to: Duration) {
        send("requestSeek") { requestSeek(to, SeekMode.KeyframeThenRefine) }
    }

    private fun send(name: String, command: ControlsPlayer.() -> Unit) {
        try {
            target.command()
        } catch (refused: IllegalStateException) {
            refusedCommand(name, refused)
        } catch (refused: IllegalArgumentException) {
            refusedCommand(name, refused)
        }
    }

    private fun launch(name: String, command: suspend ControlsPlayer.() -> Unit) {
        commands.launch {
            try {
                target.command()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                refusedCommand(name, refused)
            }
        }
    }

    private fun refusedCommand(name: String, error: Exception) {
        KiteLog.log(LOG_TAG, "the player refused $name from the controls: ${error.message}")
    }

    private fun build(): PlayerControlsSnapshot {
        val player = target.state.value
        val progress = target.progress.value
        val words = strings
        val total = duration()
        val shown = scrubTarget ?: progress.position
        if (menusOf !== player) {
            menusOf = player
            menus = buildMenus(player, words)
        }
        val level = levelOf(player.volume)
        return PlayerControlsSnapshot(
            visible = visible,
            showsPlay = !player.playRequested,
            hasQueue = player.queue.size > 1,
            canGoPrevious = queueStep(player, -1),
            canGoNext = queueStep(player, 1),
            position = progress.position,
            duration = total,
            seekable = player.seekable && total != null,
            scrubTarget = scrubTarget,
            fraction = if (total == null) 0f else fractionOf(shown, total),
            buffered = bufferedFractions(progress, total),
            positionText = words.time(progress.position),
            durationText = total?.let(words.time),
            scrubText = scrubTarget?.let(words.time),
            // Whole seconds, so a screen reader is not handed a new value on every tick.
            seekBarValueText = words.positionOf(shown.inWholeSeconds.seconds, total),
            volumeLevel = level,
            muted = player.muted,
            volumeText = words.volumeLevel(level),
            audioTracks = menus.getValue(PlayerControlsMenu.Audio).options,
            subtitleTracks = menus.getValue(PlayerControlsMenu.Subtitles).options,
            qualities = menus.getValue(PlayerControlsMenu.Quality).options,
            speeds = menus.getValue(PlayerControlsMenu.Speed).options,
            canFullScreen = onFullScreen != null,
            canPictureInPicture = onPictureInPicture != null,
        )
    }

    private class Menu(val options: List<PlayerControlsOption>, val apply: List<() -> Unit>)

    private fun menu(entries: List<Pair<PlayerControlsOption, () -> Unit>>) = Menu(entries.map { it.first }, entries.map { it.second })

    private fun buildMenus(player: PlayerSnapshot, words: PlayerControlsStrings): Map<PlayerControlsMenu, Menu> = mapOf(
        PlayerControlsMenu.Audio to menu(trackEntries(player, TrackKind.Audio, words)),
        PlayerControlsMenu.Subtitles to menu(trackEntries(player, TrackKind.Subtitle, words)),
        PlayerControlsMenu.Quality to menu(qualityEntries(player, words)),
        PlayerControlsMenu.Speed to menu(speedEntries(player, words)),
    )

    private fun trackEntries(
        player: PlayerSnapshot,
        kind: TrackKind,
        words: PlayerControlsStrings,
    ): List<Pair<PlayerControlsOption, () -> Unit>> {
        val selected = player.tracks.selected(kind)
        val listed = player.tracks.all.filter { it.kind == kind }.map { track ->
            PlayerControlsOption(words.trackName(track), track.id == selected) to { launch("selectTrack") { selectTrack(kind, track.id) } }
        }
        if (kind != TrackKind.Subtitle || listed.isEmpty()) return listed
        val off = PlayerControlsOption(words.subtitlesOff, selected == null) to { launch("selectTrack") { selectTrack(kind, null) } }
        return listOf(off) + listed
    }

    private fun qualityEntries(player: PlayerSnapshot, words: PlayerControlsStrings): List<Pair<PlayerControlsOption, () -> Unit>> {
        val variants = player.tracks.variants
        if (variants.isEmpty()) return emptyList()
        // A chosen variant is kept on the item; without one the player picks and steps by itself.
        val chosen = player.media?.demux?.variant?.takeIf { index -> variants.any { it.index == index } }
        val automatic = PlayerControlsOption(words.automaticQuality, chosen == null) to { launch("selectVariant") { selectVariant(null) } }
        return listOf(automatic) + variants.map { variant ->
            PlayerControlsOption(words.variantName(variant), variant.index == chosen) to {
                launch("selectVariant") { selectVariant(variant.index) }
            }
        }
    }

    private fun speedEntries(player: PlayerSnapshot, words: PlayerControlsStrings): List<Pair<PlayerControlsOption, () -> Unit>> =
        speeds.filter { it >= KitePlayer.SPEED_MIN && it <= KitePlayer.SPEED_MAX }.map { speed ->
            PlayerControlsOption(words.speedName(speed), abs(speed - player.speed) < SPEED_MATCH) to { send("setSpeed") { setSpeed(speed) } }
        }

    public companion object {
        /** How far an arrow key moves the seek bar. */
        public val SeekStep: Duration = 10.seconds

        /** How far an arrow key moves the volume slider. */
        public const val VOLUME_STEP: Float = 0.1f

        /** The slider value for a player [volume]: its cube root, so the slider follows hearing. */
        internal fun levelOf(volume: Float): Float = cbrt(volume.coerceIn(0f, 1f))

        /** The player volume for a slider [level]: its cube. */
        internal fun volumeOf(level: Float): Float = level * level * level
    }
}

/** The speeds the speed menu offers. */
internal val DefaultSpeeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** The most of an item a drag across the whole picture stands for. */
private val PictureScrubSpan: Duration = 10.minutes

/** Close enough to call a speed the one that plays: a speed set from text can carry rounding. */
private const val SPEED_MATCH = 1e-6

private const val LOG_TAG = "KitePlayerControls"

private fun fractionOf(value: Duration, total: Duration): Float = (value / total).toFloat().coerceIn(0f, 1f)

/** True when the queue has an item one step in [direction], counting a queue that repeats as round. */
private fun queueStep(player: PlayerSnapshot, direction: Int): Boolean {
    if (player.queue.size < 2 || player.queueIndex < 0) return false
    if (player.loop == LoopMode.All) return true
    // Next and previous follow the play order, which a shuffle changes.
    val order = player.queueOrder.ifEmpty { player.queue.indices.toList() }
    val place = order.indexOf(player.queueIndex)
    if (place < 0) return false
    return (place + direction) in order.indices
}

/**
 * The buffered ranges as fractions of [total]. When the player reports no ranges, as for HLS or
 * with the byte cache off, this is one range from the position to as far as the demuxer has read.
 */
private fun bufferedFractions(progress: Progress, total: Duration?): List<ClosedFloatingPointRange<Float>> {
    if (total == null) return emptyList()
    val ranges = progress.bufferedRanges.ifEmpty {
        if (progress.bufferedAhead > Duration.ZERO) listOf(progress.position..progress.position + progress.bufferedAhead) else emptyList()
    }
    return ranges.map { fractionOf(it.start, total)..fractionOf(it.endInclusive, total) }.filter { it.endInclusive > it.start }
}

/**
 * Moves subtitles above the controls while they show, and back when they hide, unless the
 * application moved them itself in between.
 */
private class SubtitleLift(private val target: ControlsPlayer) {
    private var before: Float? = null
    private var lifted: Float? = null

    /** Lifts subtitles to [wanted], or keeps them where they are when they already stand higher. */
    fun raise(wanted: Float) {
        val current = target.state.value.subtitlePosition
        val base = before?.takeIf { mine(current) } ?: current
        val next = minOf(base, wanted)
        if (next >= base) {
            settle()
            return
        }
        if (next == lifted && base == before) return
        before = base
        lifted = next
        move(next)
    }

    /** Puts subtitles back where they were before the lift, unless the application moved them. */
    fun settle() {
        val restore = before ?: return
        val untouched = mine(target.state.value.subtitlePosition)
        before = null
        lifted = null
        // Sent even when the player still shows the old position: the lift may be on its way.
        if (untouched) move(restore)
    }

    /** True when [current] is the lift, or the position before it, which the player can still show. */
    private fun mine(current: Float): Boolean = current == lifted || current == before

    private fun move(to: Float) {
        try {
            target.setSubtitlePosition(to)
        } catch (_: IllegalStateException) {
            // A closed player has no subtitles to move.
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {
        /** The highest the player places subtitles, a tenth of the way down. */
        const val LOWEST = 0.1f
    }
}
