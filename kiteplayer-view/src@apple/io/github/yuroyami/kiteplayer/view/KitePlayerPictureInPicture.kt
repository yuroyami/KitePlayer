@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerContentSource
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.AVKit.AVPictureInPictureSampleBufferPlaybackDelegateProtocol
import platform.AVKit.create
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMVideoDimensions
import platform.CoreMedia.kCMTimeNegativeInfinity
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The timescale every Core Media time here is built with.
 *
 * 600 is the usual choice because it divides evenly by 24, 25, 30 and 60, so a whole number of
 * frames at any of those rates lands on a whole number of ticks.
 */
internal const val TIMESCALE: Int = 600

/**
 * Puts a [KitePlayer] in the small floating window, and lets that window drive it, on iOS and macOS.
 *
 * The system offers this only to a player that draws into a sample buffer display layer, which is
 * what `SampleBufferVideoRenderer` in the output module produces. Pair the view with that renderer
 * first, then build one of these over the same layer. On macOS, an `AppKitWindow` built with the
 * sample buffer surface hosts such a layer.
 *
 * What this keeps in step, so the window never shows a stale state:
 * - The play button and the range. The window reads them again whenever the player's status,
 *   duration or seekability changes. A source that cannot seek hides the skip buttons.
 * - The progress bar. The window reads the layer's clock, so this gives the layer a clock and keeps
 *   it at the player's position and speed through play, pause, seek and speed changes.
 * - [active], from the window's own reports that it opened and closed.
 *
 * Building it can fail for a reason that is not an error: a device that does not offer the
 * feature. [createOrNull] answers null rather than throwing at the moment a viewer is trying to
 * leave the app.
 *
 * On iOS the application still declares the background audio capability itself; without it the
 * window appears and freezes as soon as the app is no longer on screen. Pass this object to
 * `attachBackgroundHandling` in `kiteplayer`, so video keeps playing in the window while the app
 * is away.
 *
 * Use every member from the main thread.
 */
public class KitePlayerPictureInPicture internal constructor(
    private val player: PictureInPicturePlayer,
    private val control: PictureInPictureControl,
    private val clock: LayerClock?,
    private val scope: CoroutineScope,
    /** Held here because the system holds it only weakly. */
    @Suppress("unused") private val playback: PlaybackDelegate? = null,
) : AutoCloseable {

    private val activeFlow = MutableStateFlow(false)
    private var closed = false

    /** The window's own reports. Held here because the controller holds its delegate weakly. */
    internal val events: WindowEvents = WindowEvents(activeFlow) { onRestoreRequested }

    /** True when the system is willing right now. It can change with the device's state. */
    public val isPossible: Boolean get() = control.isPossible

    /** True while the small window is on screen, as the controller reports it now. */
    public val isActive: Boolean get() = control.isActive

    /**
     * True from the moment the window has opened until it has closed, or failed to open.
     *
     * The window's own start and stop reports drive it, so a window the system opened by itself
     * shows here too.
     */
    public val active: StateFlow<Boolean> = activeFlow.asStateFlow()

    /**
     * Whether the window opens by itself when the viewer leaves the app while playing.
     *
     * Off by default, because a player that jumps into a floating window unasked is a surprise.
     * iOS only: macOS has no such feature, so there this is always false and a write does nothing.
     */
    public var startsAutomatically: Boolean
        get() = control.startsAutomatically
        set(value) {
            control.startsAutomatically = value
        }

    /**
     * Called when the viewer asks to go back to the app from the window.
     *
     * Show the inline picture again, then call `done(true)`; call `done(false)` when it cannot be
     * shown. The window waits for the call before it animates back. Null restores at once.
     */
    public var onRestoreRequested: ((done: (Boolean) -> Unit) -> Unit)? = null

    init {
        control.listen(events)
        scope.launch { followWindowState() }
        if (clock != null) scope.launch { followClock(clock) }
    }

    /** Asks for the window. Does nothing when the system is not willing; check [isPossible]. */
    public fun start() {
        if (!closed) control.start()
    }

    /** Puts the picture back in the app. */
    public fun stop() {
        if (!closed) control.stop()
    }

    /** Closes the window if it is open and lets go of the layer. Safe to call twice. */
    override fun close() {
        if (closed) return
        closed = true
        if (control.isActive) control.stop()
        control.release()
        scope.cancel()
        clock?.release()
        // The controller no longer reports to this object, so it cannot say the window closed.
        activeFlow.value = false
    }

    /** The window reads its play button and range only when told they may have changed. */
    private suspend fun followWindowState() {
        player.state
            .map { WindowFacts(it.status, it.duration, it.seekable) }
            .distinctUntilChanged()
            .collect { facts ->
                control.requiresLinearPlayback = !facts.seekable
                control.invalidatePlaybackState()
            }
    }

    /** Moves the layer's clock with the player: its time on every change, its rate with play state. */
    private suspend fun followClock(clock: LayerClock) {
        player.state
            .map { ClockFacts(it.status == PlaybackStatus.Playing, it.speed, it.generation) }
            .distinctUntilChanged()
            .collect { facts ->
                clock.follow(player.position(), rate = if (facts.playing) facts.speed else 0.0)
            }
    }

    private data class WindowFacts(val status: PlaybackStatus, val duration: Duration?, val seekable: Boolean)

    /** A new generation means the position jumped: a seek, or a new stream. */
    private data class ClockFacts(val playing: Boolean, val speed: Double, val generation: Generation)

    public companion object {
        /**
         * Builds a controller over [layer], or null when this device cannot show the window.
         *
         * @param skipInterval how far the window's two skip buttons move when the system sends no
         *        interval of its own. Fifteen seconds is what the system player uses.
         */
        public fun createOrNull(
            player: KitePlayer,
            layer: AVSampleBufferDisplayLayer,
            skipInterval: Duration = 15.seconds,
        ): KitePlayerPictureInPicture? {
            if (!AVPictureInPictureController.isPictureInPictureSupported()) return null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val seam = KitePlayerSeam(player)
            val playback = PlaybackDelegate(seam, scope, skipInterval)
            val source = AVPictureInPictureControllerContentSource.create(
                sampleBufferDisplayLayer = layer,
                playbackDelegate = playback,
            )
            val controller = AVPictureInPictureController(contentSource = source)
            return KitePlayerPictureInPicture(
                player = seam,
                control = ControllerControl(controller),
                clock = LayerClock.attachTo(layer),
                scope = scope,
                playback = playback,
            )
        }
    }
}

/**
 * The window's own controls, mapped onto the player.
 *
 * The system asks this object for the state rather than being told, so every answer reads the
 * player as it is now. A live stream answers with an unbounded range, which is how the window is
 * told to draw no scrub bar at all.
 *
 * Each system call forwards to a function that takes no controller, because a test process cannot
 * always build one: the iOS simulator refuses it.
 */
internal class PlaybackDelegate(
    private val player: PictureInPicturePlayer,
    private val scope: CoroutineScope,
    private val skipInterval: Duration,
) : NSObject(), AVPictureInPictureSampleBufferPlaybackDelegateProtocol {

    /** The window's play button. */
    fun play(playing: Boolean) {
        if (playing) player.play() else player.pause()
    }

    /** What the window's play button shows. */
    fun paused(): Boolean = player.state.value.status != PlaybackStatus.Playing

    /** The range the window's progress bar covers. */
    fun range(): CValue<CMTimeRange> {
        val duration = player.state.value.duration
            ?: return CMTimeRangeMake(kCMTimeNegativeInfinity.readValue(), kCMTimePositiveInfinity.readValue())
        return CMTimeRangeMake(
            start = CMTimeMakeWithSeconds(0.0, TIMESCALE),
            duration = CMTimeMakeWithSeconds(duration.toDouble(DurationUnit.SECONDS), TIMESCALE),
        )
    }

    /** The window's skip buttons. [done] is called whatever happened. */
    fun skip(interval: CValue<CMTime>, done: () -> Unit) {
        val seconds = CMTimeGetSeconds(interval)
        // The system sends its own interval, but a zero or unreadable one still has to move.
        val by = if (seconds.isFinite() && seconds != 0.0) seconds.seconds else skipInterval
        scope.launch {
            val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
            runCatching { player.seek(target) }
            // The window waits for this before it draws again.
            done()
        }
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        setPlaying: Boolean,
    ) = play(setPlaying)

    override fun pictureInPictureControllerIsPlaybackPaused(
        pictureInPictureController: AVPictureInPictureController,
    ): Boolean = paused()

    override fun pictureInPictureControllerTimeRangeForPlayback(
        pictureInPictureController: AVPictureInPictureController,
    ): CValue<CMTimeRange> = range()

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize: CValue<CMVideoDimensions>,
    ) {
        // The layer resizes itself. Nothing here needs the new size.
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        skipByInterval: CValue<CMTime>,
        completionHandler: () -> Unit,
    ) = skip(skipByInterval, completionHandler)
}

/**
 * The window's own reports: it opened, it closed, it could not open, and the viewer asked to go
 * back to the app.
 *
 * A failure to open only sets [active] false. The library prints nothing on its own, so the reason
 * is not kept. Each system call forwards to a function that takes no controller, as above.
 */
internal class WindowEvents(
    private val active: MutableStateFlow<Boolean>,
    private val restoreHandler: () -> ((done: (Boolean) -> Unit) -> Unit)?,
) : NSObject(), AVPictureInPictureControllerDelegateProtocol {

    fun started() {
        active.value = true
    }

    fun stopped() {
        active.value = false
    }

    fun failed() {
        active.value = false
    }

    /** Asks the application to show the picture again, or restores at once when nobody listens. */
    fun restore(done: (Boolean) -> Unit) {
        val handler = restoreHandler()
        if (handler == null) done(true) else handler(done)
    }

    override fun pictureInPictureControllerDidStartPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) = started()

    override fun pictureInPictureControllerDidStopPictureInPicture(
        pictureInPictureController: AVPictureInPictureController,
    ) = stopped()

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) = failed()

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) = restore(restoreUserInterfaceForPictureInPictureStopWithCompletionHandler)
}
