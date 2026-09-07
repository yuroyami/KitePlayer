package io.github.yuroyami.kiteplayer.session

/** What playback should do when the application leaves the screen. */
public enum class BackgroundPolicy {
    /**
     * Keep the sound, park video decoding while away, and resume it on return.
     *
     * Nobody is looking at the picture, so decoding it costs battery and heat for nothing. On
     * Android, carrying on in the background also needs the application's own foreground service.
     */
    ContinueAudio,

    /** Pause on leaving, and play again on return only when this policy was the one that paused. */
    PauseAll,

    /** Do nothing. */
    Ignore,
}

/** One answer for a foreground change. A null [videoEnabled] means leave video as it is. */
internal data class BackgroundDecision(
    val transport: SessionTransport,
    val videoEnabled: Boolean?,
)

/**
 * The pure part of background handling.
 *
 * Two memories, and both matter. Without [pausedByPolicy] a return to the screen would start
 * playing something the listener had paused themselves. Without [videoParkedByPolicy] it would
 * switch video back on for an application that had deliberately turned it off to play sound only.
 */
internal class BackgroundMachine(private val policy: BackgroundPolicy) {
    private var pausedByPolicy = false
    private var videoParkedByPolicy = false

    fun on(foreground: Boolean, playing: Boolean, videoEnabled: Boolean): BackgroundDecision =
        when (policy) {
            BackgroundPolicy.Ignore -> BackgroundDecision(SessionTransport.None, null)
            BackgroundPolicy.ContinueAudio ->
                if (!foreground) {
                    val park = videoEnabled
                    videoParkedByPolicy = park
                    BackgroundDecision(SessionTransport.None, if (park) false else null)
                } else {
                    val resumeVideo = videoParkedByPolicy
                    videoParkedByPolicy = false
                    BackgroundDecision(SessionTransport.None, if (resumeVideo) true else null)
                }
            BackgroundPolicy.PauseAll ->
                if (!foreground) {
                    pausedByPolicy = playing
                    BackgroundDecision(
                        if (playing) SessionTransport.Pause else SessionTransport.None,
                        null,
                    )
                } else {
                    val resume = pausedByPolicy
                    pausedByPolicy = false
                    BackgroundDecision(
                        if (resume) SessionTransport.Resume else SessionTransport.None,
                        null,
                    )
                }
        }
}

/** Turns background decisions into calls. */
internal class BackgroundApplier(
    private val target: SessionTarget,
    policy: BackgroundPolicy,
) {
    private val machine = BackgroundMachine(policy)

    fun handle(foreground: Boolean) {
        val decision = machine.on(foreground, target.playing, target.videoEnabled)
        decision.videoEnabled?.let(target::setVideoEnabled)
        when (decision.transport) {
            SessionTransport.Pause -> target.pause()
            SessionTransport.Resume -> target.play()
            SessionTransport.None -> Unit
        }
    }
}
