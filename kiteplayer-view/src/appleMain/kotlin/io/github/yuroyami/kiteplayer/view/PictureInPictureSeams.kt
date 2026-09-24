@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.AVKit.invalidatePlaybackState
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMClockGetHostTimeClock
import platform.CoreMedia.CMClockGetTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimebaseCreateWithSourceClock
import platform.CoreMedia.CMTimebaseGetRate
import platform.CoreMedia.CMTimebaseGetTime
import platform.CoreMedia.CMTimebaseRef
import platform.CoreMedia.CMTimebaseRefVar
import platform.CoreMedia.CMTimebaseSetRateAndAnchorTime
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** The player as the window uses it, so a test can script its state. */
internal interface PictureInPicturePlayer {
    val state: StateFlow<PlayerSnapshot>
    fun position(): Duration
    fun play()
    fun pause()
    suspend fun seek(to: Duration)
}

internal class KitePlayerSeam(private val player: KitePlayer) : PictureInPicturePlayer {
    override val state: StateFlow<PlayerSnapshot> get() = player.state
    override fun position(): Duration = player.position()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override suspend fun seek(to: Duration) = player.seek(to)
}

/** The system controller as the window class uses it, so a test can stand in for it. */
internal interface PictureInPictureControl {
    val isPossible: Boolean
    val isActive: Boolean
    var requiresLinearPlayback: Boolean
    var startsAutomatically: Boolean
    fun start()
    fun stop()

    /** Tells the window to read the play button and the range again. */
    fun invalidatePlaybackState()

    /** Sends the window's reports to [listener]. The controller holds it weakly. */
    fun listen(listener: AVPictureInPictureControllerDelegateProtocol)

    /** Stops the reports and lets go of the layer and the player. */
    fun release()
}

internal class ControllerControl(private val controller: AVPictureInPictureController) : PictureInPictureControl {
    override val isPossible: Boolean get() = controller.isPictureInPicturePossible()
    override val isActive: Boolean get() = controller.isPictureInPictureActive()

    override var requiresLinearPlayback: Boolean
        get() = controller.requiresLinearPlayback
        set(value) {
            controller.requiresLinearPlayback = value
        }

    override var startsAutomatically: Boolean
        get() = controller.startsAutomaticallyFromInline
        set(value) {
            controller.startsAutomaticallyFromInline = value
        }

    override fun start() = controller.startPictureInPicture()
    override fun stop() = controller.stopPictureInPicture()
    override fun invalidatePlaybackState() = controller.invalidatePlaybackState()

    override fun listen(listener: AVPictureInPictureControllerDelegateProtocol) {
        controller.delegate = listener
    }

    override fun release() {
        controller.delegate = null
        controller.contentSource = null
    }
}

/**
 * The layer's control timebase: the clock the window's progress bar reads.
 *
 * It runs from the host clock, and [follow] sets its time and its rate. Frames do not wait for it,
 * because the sample buffer renderer marks every frame to show at once, so the engine still
 * decides when a frame appears.
 */
internal class LayerClock private constructor(
    private val layer: AVSampleBufferDisplayLayer,
    private val timebase: CMTimebaseRef,
) {
    /** The clock's reading now, in seconds of media. */
    val seconds: Double get() = CMTimeGetSeconds(CMTimebaseGetTime(timebase))

    /** How fast the clock runs: the player's speed while playing, zero otherwise. */
    val rate: Double get() = CMTimebaseGetRate(timebase)

    /** Reads [position] from this instant on, moving at [rate]. */
    fun follow(position: Duration, rate: Double) {
        CMTimebaseSetRateAndAnchorTime(
            timebase = timebase,
            rate = rate,
            timebaseTime = CMTimeMakeWithSeconds(position.toDouble(DurationUnit.SECONDS), TIMESCALE),
            immediateSourceTime = CMClockGetTime(CMClockGetHostTimeClock()),
        )
    }

    /** Takes the clock off the layer, when it is still the layer's, and frees it. */
    fun release() {
        if (layer.controlTimebase == timebase) layer.controlTimebase = null
        CFRelease(timebase)
    }

    companion object {
        /** A stopped clock at zero, set on [layer]. Null when Core Media cannot make one. */
        fun attachTo(layer: AVSampleBufferDisplayLayer): LayerClock? {
            val timebase = memScoped {
                val out = alloc<CMTimebaseRefVar>()
                if (CMTimebaseCreateWithSourceClock(null, CMClockGetHostTimeClock(), out.ptr) != 0) null else out.value
            } ?: return null
            layer.controlTimebase = timebase
            return LayerClock(layer, timebase)
        }
    }
}
