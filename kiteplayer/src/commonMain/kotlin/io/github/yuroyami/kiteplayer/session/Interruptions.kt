package io.github.yuroyami.kiteplayer.session

/**
 * What to do when something takes the right to make sound away.
 *
 * A call arrives, another app starts playing, the headphones come out. Each platform reports these
 * differently and each one expects a player to react. This is the one set of choices behind all of
 * them; the platform guard maps its own signals onto [InterruptionEvent] and follows what is here.
 */
public data class InterruptionPolicy(
    /** Pause when the sound is gone for good, for example another app took over playback. */
    val pauseOnLoss: Boolean = true,
    /** On a short loss that allows quiet playback, drop to [duckVolume] instead of pausing. */
    val duckOnTransient: Boolean = true,
    /** The volume to play at while ducked. */
    val duckVolume: Float = 0.2f,
    /** Play again after a short loss ends, but only when this policy was the one that paused. */
    val resumeAfterTransient: Boolean = true,
    /** Pause when the route stops being audible: wired headphones out, Bluetooth gone. */
    val pauseWhenBecomingNoisy: Boolean = true,
) {
    init {
        require(duckVolume.isFinite() && duckVolume in 0f..1f) {
            "duckVolume must be between 0 and 1, was $duckVolume"
        }
    }
}

/** What the platform said happened to the right to make sound. */
public enum class InterruptionEvent {
    /** Gone for good. Nothing resumes by itself after this. */
    Lost,

    /** Gone for a moment, and quiet playback is not allowed through it. */
    LostTransient,

    /** Gone for a moment, and quiet playback is allowed through it. */
    LostTransientCanDuck,

    /** It is ours again. */
    Gained,

    /** The route stopped being audible: headphones out, Bluetooth gone. */
    BecameNoisy,
}

/** What a guard should do to the transport. */
internal enum class SessionTransport { None, Pause, Resume }

/**
 * One answer, with the two halves apart on purpose.
 *
 * A single action cannot say "pause and give the volume back", which is exactly what a permanent
 * loss arriving while ducked has to do. Leaving the duck in force there would hand the listener a
 * quiet player the next time they pressed play, with nothing on screen to explain it.
 */
internal data class InterruptionDecision(
    val transport: SessionTransport,
    /** Whether the guard's volume duck should be in force once this event is handled. */
    val ducked: Boolean,
)

/**
 * The pure part of interruption handling: an event in, a decision out.
 *
 * It remembers whether it was the one that paused or ducked, so a regained focus never resumes
 * something the listener paused themselves. No platform types, so every branch is testable on any
 * target.
 */
internal class InterruptionMachine(private val policy: InterruptionPolicy) {
    private var pausedByPolicy = false
    private var ducked = false

    fun on(event: InterruptionEvent, playing: Boolean): InterruptionDecision = when (event) {
        // A permanent loss never resumes: the platform gave the sound to somebody else for good.
        InterruptionEvent.Lost -> decide(
            pause = policy.pauseOnLoss && playing,
            resumable = false,
            ducked = false,
        )
        InterruptionEvent.LostTransient -> decide(
            pause = playing,
            resumable = policy.resumeAfterTransient,
            ducked = false,
        )
        InterruptionEvent.LostTransientCanDuck ->
            if (policy.duckOnTransient) {
                ducked = playing
                InterruptionDecision(SessionTransport.None, ducked)
            } else {
                decide(pause = playing, resumable = policy.resumeAfterTransient, ducked = false)
            }
        InterruptionEvent.Gained -> {
            val resume = pausedByPolicy
            pausedByPolicy = false
            ducked = false
            InterruptionDecision(if (resume) SessionTransport.Resume else SessionTransport.None, false)
        }
        // Pulling the plug is a deliberate act, so plugging back in does not start the sound again.
        InterruptionEvent.BecameNoisy -> decide(
            pause = policy.pauseWhenBecomingNoisy && playing,
            resumable = false,
            ducked = false,
        )
    }

    private fun decide(pause: Boolean, resumable: Boolean, ducked: Boolean): InterruptionDecision {
        this.ducked = ducked
        pausedByPolicy = pause && resumable
        return InterruptionDecision(if (pause) SessionTransport.Pause else SessionTransport.None, ducked)
    }
}
