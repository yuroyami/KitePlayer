package io.github.yuroyami.kiteplayer.session

/**
 * What playback should do when the application leaves the screen.
 *
 * A picture in picture window that the background handler was given counts as the screen while it
 * shows, because the viewer is still watching the picture there.
 */
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
 *
 * The policy acts when the picture stops being seen anywhere, and again when it is seen once more.
 * A picture in picture window that shows counts as being seen. So the application leaving with the
 * window open does nothing, the window closing while the application is away acts as leaving, and
 * the window opening while the application is away acts as coming back. That last one covers an
 * automatic start, where the system can open the window after the application has already left.
 */
internal class BackgroundMachine(private val policy: BackgroundPolicy) {
    private var pausedByPolicy = false
    private var videoParkedByPolicy = false
    private var foreground = true
    private var windowShowing = false

    /** Whether anyone can see the picture: on the screen, or in the small window. */
    private val seen: Boolean get() = foreground || windowShowing

    /** The application came to the screen, or left it. */
    fun on(foreground: Boolean, playing: Boolean, videoEnabled: Boolean): BackgroundDecision {
        val seenBefore = seen
        this.foreground = foreground
        return decide(seenBefore, playing, videoEnabled)
    }

    /** A picture in picture window opened, or closed. */
    fun onWindow(showing: Boolean, playing: Boolean, videoEnabled: Boolean): BackgroundDecision {
        val seenBefore = seen
        windowShowing = showing
        return decide(seenBefore, playing, videoEnabled)
    }

    private fun decide(seenBefore: Boolean, playing: Boolean, videoEnabled: Boolean): BackgroundDecision =
        when {
            seen == seenBefore -> NOTHING
            seen -> seenAgain()
            else -> goneFromView(playing, videoEnabled)
        }

    private fun goneFromView(playing: Boolean, videoEnabled: Boolean): BackgroundDecision =
        when (policy) {
            BackgroundPolicy.Ignore -> NOTHING
            BackgroundPolicy.ContinueAudio -> {
                videoParkedByPolicy = videoEnabled
                BackgroundDecision(SessionTransport.None, if (videoEnabled) false else null)
            }
            BackgroundPolicy.PauseAll -> {
                pausedByPolicy = playing
                BackgroundDecision(if (playing) SessionTransport.Pause else SessionTransport.None, null)
            }
        }

    private fun seenAgain(): BackgroundDecision =
        when (policy) {
            BackgroundPolicy.Ignore -> NOTHING
            BackgroundPolicy.ContinueAudio -> {
                val resumeVideo = videoParkedByPolicy
                videoParkedByPolicy = false
                BackgroundDecision(SessionTransport.None, if (resumeVideo) true else null)
            }
            BackgroundPolicy.PauseAll -> {
                val resume = pausedByPolicy
                pausedByPolicy = false
                BackgroundDecision(if (resume) SessionTransport.Resume else SessionTransport.None, null)
            }
        }

    private companion object {
        val NOTHING = BackgroundDecision(SessionTransport.None, null)
    }
}

/**
 * Whether any activity is on screen, from start and stop callbacks alone.
 *
 * A handle attached after an activity started never saw that start, so counting from zero
 * missed every transition after it (#281). This keeps the activities it saw start, by identity,
 * plus [startedBeforeAttach] for the ones it did not: a stop of an activity it never saw start
 * is one of those. A stop for a configuration change, such as a rotation, is followed by the new
 * activity's start and is not a trip to the background.
 *
 * The answer starts as "on screen", which is what [BackgroundMachine] assumes too.
 */
internal class StartedActivities(private var startedBeforeAttach: Boolean) {
    private val seen = HashSet<Any>()
    private var announced = true

    private val onScreen: Boolean get() = seen.isNotEmpty() || startedBeforeAttach

    /** An activity started. Returns the new answer when it changed, else null. */
    fun onStarted(activity: Any): Boolean? {
        seen += activity
        return change()
    }

    /** An activity stopped. Returns the new answer when it changed, else null. */
    fun onStopped(activity: Any, changingConfiguration: Boolean): Boolean? {
        if (!seen.remove(activity)) startedBeforeAttach = false
        return if (changingConfiguration) null else change()
    }

    private fun change(): Boolean? {
        val now = onScreen
        if (now == announced) return null
        announced = now
        return now
    }
}

/** Turns background decisions into calls. */
internal class BackgroundApplier(
    private val target: SessionTarget,
    policy: BackgroundPolicy,
) {
    private val machine = BackgroundMachine(policy)
    private val claim = PauseClaim(target)

    fun handle(foreground: Boolean) {
        apply(machine.on(foreground, target.playing, target.videoEnabled))
    }

    /** A picture in picture window opened, or closed. */
    fun handleWindow(showing: Boolean) {
        apply(machine.onWindow(showing, target.playing, target.videoEnabled))
    }

    private fun apply(decision: BackgroundDecision) {
        decision.videoEnabled?.let(target::setVideoEnabled)
        claim.apply(decision.transport)
    }
}
