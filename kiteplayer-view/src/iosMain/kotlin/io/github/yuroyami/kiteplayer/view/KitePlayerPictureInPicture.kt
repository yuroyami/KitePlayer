@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerContentSource
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
import platform.darwin.NSObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The timescale every Core Media time here is built with.
 *
 * 600 is the usual choice because it divides evenly by 24, 25, 30 and 60, so a whole number of
 * frames at any of those rates lands on a whole number of ticks.
 */
private const val TIMESCALE = 600

/**
 * Puts [player] in the small floating window, and lets that window drive it.
 *
 * iOS only offers this to a player that draws into a sample buffer display layer, which is what
 * `SampleBufferVideoRenderer` in the output module produces. Pair the view with that renderer
 * first, then build one of these over the same layer.
 *
 * Building it can fail for reasons that are not errors: an iPhone that does not offer the feature,
 * or a version of iOS older than the sample buffer content source. [createOrNull] answers null for
 * both rather than throwing at the moment a viewer is trying to leave the app.
 *
 * The application still declares the background audio capability itself; without it the window
 * appears and freezes as soon as the app is no longer on screen.
 */
public class KitePlayerPictureInPicture private constructor(
    private val controller: AVPictureInPictureController,
    private val scope: CoroutineScope,
    private val delegate: PlaybackDelegate,
) : AutoCloseable {

    /** True when the system is willing right now. It can change with the device's state. */
    public val isPossible: Boolean get() = controller.isPictureInPicturePossible()

    /** True while the small window is on screen. */
    public val isActive: Boolean get() = controller.isPictureInPictureActive()

    /**
     * Whether the window opens by itself when the viewer leaves the app while playing.
     *
     * Off by default, because a player that jumps into a floating window unasked is a surprise.
     */
    public var startsAutomatically: Boolean
        get() = controller.canStartPictureInPictureAutomaticallyFromInline
        set(value) {
            controller.canStartPictureInPictureAutomaticallyFromInline = value
        }

    /** Asks for the window. Does nothing when the system is not willing; check [isPossible]. */
    public fun start() {
        controller.startPictureInPicture()
    }

    /** Puts the picture back in the app. */
    public fun stop() {
        controller.stopPictureInPicture()
    }

    override fun close() {
        if (controller.isPictureInPictureActive()) controller.stopPictureInPicture()
        controller.contentSource = null
        scope.cancel()
    }

    public companion object {
        /**
         * Builds a controller over [layer], or null when this device or this iOS version cannot.
         *
         * @param skipInterval how far the window's two skip buttons move. Fifteen seconds is what
         *        the system player uses.
         */
        public fun createOrNull(
            player: KitePlayer,
            layer: AVSampleBufferDisplayLayer,
            skipInterval: Duration = 15.seconds,
        ): KitePlayerPictureInPicture? {
            if (!AVPictureInPictureController.isPictureInPictureSupported()) return null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val delegate = PlaybackDelegate(player, scope, skipInterval)
            val source = AVPictureInPictureControllerContentSource.create(
                sampleBufferDisplayLayer = layer,
                playbackDelegate = delegate,
            )
            val controller = AVPictureInPictureController(contentSource = source)
            return KitePlayerPictureInPicture(controller, scope, delegate)
        }
    }

    /**
     * The window's own controls, mapped onto the player.
     *
     * The system asks this object for the state rather than being told, so every answer reads the
     * player as it is now. A live stream answers with an unbounded range, which is how the window
     * is told to draw no scrub bar at all.
     */
    internal class PlaybackDelegate(
        private val player: KitePlayer,
        private val scope: CoroutineScope,
        private val skipInterval: Duration,
    ) : NSObject(), AVPictureInPictureSampleBufferPlaybackDelegateProtocol {

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            setPlaying: Boolean,
        ) {
            if (setPlaying) player.play() else player.pause()
        }

        override fun pictureInPictureControllerIsPlaybackPaused(
            pictureInPictureController: AVPictureInPictureController,
        ): Boolean = player.state.value.status != PlaybackStatus.Playing

        override fun pictureInPictureControllerTimeRangeForPlayback(
            pictureInPictureController: AVPictureInPictureController,
        ): CValue<CMTimeRange> {
            val duration = player.state.value.duration
                ?: return CMTimeRangeMake(kCMTimeNegativeInfinity.readValue(), kCMTimePositiveInfinity.readValue())
            return CMTimeRangeMake(
                start = CMTimeMakeWithSeconds(0.0, TIMESCALE),
                duration = CMTimeMakeWithSeconds(duration.toDouble(kotlin.time.DurationUnit.SECONDS), TIMESCALE),
            )
        }

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
        ) {
            val seconds = skipByInterval.useContents { CMTimeGetSeconds(this.readValue()) }
            // The system sends its own interval, but a zero or unreadable one still has to move.
            val by = if (seconds.isFinite() && seconds != 0.0) seconds.seconds else skipInterval
            scope.launch {
                val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
                runCatching { player.seek(target) }
                // Called whatever happened: the window waits for it before it draws again.
                completionHandler()
            }
        }
    }
}
