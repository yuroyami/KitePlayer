package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus

/**
 * The small part of a player a session guard actually touches.
 *
 * It exists so the deciding and the doing can be tested without a real player, a real device or a
 * real platform. [PlayerSessionTarget] is the only implementation that ships.
 */
internal interface SessionTarget {
    val playing: Boolean

    /** A count that moves with every play, pause, stop, open and queue move a caller makes. */
    val transportMark: Long
    val videoEnabled: Boolean
    fun play()
    fun pause()
    /** The duck multiplier on top of the volume. 1 is no duck. */
    fun setDuckLevel(level: Float)
    fun setVideoEnabled(enabled: Boolean)
}

/**
 * Play and pause for a press from outside the application: the lock screen, a headset, a car, the
 * system. Such a press can arrive after the player closed, when both calls throw, so it is dropped.
 */
internal fun KitePlayer.playFromRemote() {
    runCatching { play() }
}

internal fun KitePlayer.pauseFromRemote() {
    runCatching { pause() }
}

internal class PlayerSessionTarget(private val player: KitePlayer) : SessionTarget {
    // Buffering and a queue's next open count: the engine starts the sound by itself once data
    // arrives, so a guard that waited for Playing let it start over a call (#226).
    override val playing: Boolean get() = player.state.value.playRequested
    override val transportMark: Long get() = player.transportMark
    override val videoEnabled: Boolean get() = player.state.value.videoEnabled
    override fun play() = player.playFromRemote()
    override fun pause() = player.pauseFromRemote()
    override fun setDuckLevel(level: Float) = player.setDuckLevel(level)
    override fun setVideoEnabled(enabled: Boolean) = player.setVideoEnabled(enabled)
}

/**
 * Makes a policy's pause and its later resume, and resumes only a pause nobody overrode.
 *
 * The count of transport commands is read right after the policy's own pause. A play, a pause, an
 * open or a queue move after that, by the listener or the application, moves the count, and the
 * resume that would have undone the policy's pause does nothing instead (#278).
 */
internal class PauseClaim(private val target: SessionTarget) {
    private var markAtPause: Long? = null

    fun apply(transport: SessionTransport) {
        when (transport) {
            SessionTransport.Pause -> {
                target.pause()
                markAtPause = target.transportMark
            }
            SessionTransport.Resume -> {
                val mark = markAtPause
                markAtPause = null
                if (mark != null && mark == target.transportMark) target.play()
            }
            SessionTransport.None -> Unit
        }
    }
}

/**
 * Turns interruption decisions into calls.
 *
 * A duck is a multiplier on top of the volume, never a write to it, so it only ever lowers the
 * sound and a volume the listener changes while ducked is still theirs when it ends (#280).
 */
internal class InterruptionApplier(
    private val target: SessionTarget,
    private val policy: InterruptionPolicy,
) {
    private val machine = InterruptionMachine(policy)
    private val claim = PauseClaim(target)
    private var ducked = false

    fun handle(event: InterruptionEvent) = apply(machine.on(event, target.playing))

    /** The platform will hand the sound over later (#451): pause now, and its gain plays. */
    fun focusDelayed() = apply(machine.onDelayed(target.playing))

    private fun apply(decision: InterruptionDecision) {
        // Volume first: a permanent loss arriving while ducked has to give the volume back BEFORE
        // it pauses, or the next press of play is quiet for no reason the listener can see.
        applyDuck(decision.ducked)
        claim.apply(decision.transport)
    }

    private fun applyDuck(ducked: Boolean) {
        if (ducked == this.ducked) return
        this.ducked = ducked
        target.setDuckLevel(if (ducked) policy.duckVolume else 1f)
    }

    /** Ends a duck on close, so an application that stops listening is not left quiet. */
    fun release() = applyDuck(false)
}

/**
 * When to ask the platform for the right to make sound and when to give it back.
 *
 * Held from a granted request until the player goes idle, across a pause, and only for an item
 * with sound. Giving it back at a
 * pause would look tidier and would break resuming after a phone call: the platform only tells a
 * holder that the sound is theirs again.
 *
 * Only a grant holds it. A denied request, or a permanent loss, leaves nothing held, so the next
 * play asks again (#282). A request the platform answers "later" leaves it waiting: nothing is held
 * yet, the gain that arrives later holds it, and going idle gives the waiting request back as it
 * would a held one (#451). A play while waiting asks again. A request still on its way to the platform when [release] closes the
 * lifecycle answers [FocusAnswer.AfterClose], so a grant nobody would otherwise give back is given
 * back at once (#415). Not thread safe: a platform guard calls it from one thread at a time.
 */
internal class SessionFocusLifecycle {
    private var held = false
    private var waiting = false
    private var closed = false

    /**
     * True to request, false to give back, null when nothing needs to change. Always null once closed.
     *
     * [hasSound] is false for an item with no audio track selected, such as a muted preview in a
     * feed. Such an item asks for nothing, so another app's music plays on, and gives back what an
     * item before it held, playing or paused (#436). An item that is still opening keeps what is
     * held, because its tracks are not known yet.
     */
    fun on(status: PlaybackStatus, hasSound: Boolean = true): Boolean? = if (closed) null else when (status) {
        PlaybackStatus.Playing, PlaybackStatus.Buffering ->
            if (hasSound) (if (held) null else true) else giveBack()
        PlaybackStatus.Paused -> if (hasSound) null else giveBack()
        PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed -> giveBack()
        PlaybackStatus.Opening -> null
    }

    private fun giveBack(): Boolean? = if (held || waiting) {
        held = false
        waiting = false
        false
    } else {
        null
    }

    /** The platform's answer to a request [on] asked for, and what the caller does with it. */
    fun answered(result: FocusResult): FocusAnswer = when {
        closed -> FocusAnswer.AfterClose
        result == FocusResult.Granted -> FocusAnswer.Held.also { held = true; waiting = false }
        result == FocusResult.Delayed -> FocusAnswer.Waiting.also { held = false; waiting = true }
        else -> FocusAnswer.Denied.also { held = false; waiting = false }
    }

    /** The sound arrived: a request that waited is now held (#451). */
    fun gained() {
        if (waiting && !closed) {
            waiting = false
            held = true
        }
    }

    /** The platform took the sound away for good, so the next play has to ask again. */
    fun lost() {
        held = false
        waiting = false
    }

    /** Closes the lifecycle, and says whether something was held or waited for, to give back. */
    fun release(): Boolean {
        closed = true
        return (held || waiting).also {
            held = false
            waiting = false
        }
    }
}

/** What a caller does with the platform's answer to a focus request. */
internal enum class FocusResult {
    /** The sound is ours now. */
    Granted,

    /** The sound will be ours later, when the platform says so (#451). */
    Delayed,

    /** Refused. */
    Failed,
}

internal enum class FocusAnswer {
    /** Granted, and now held until the player goes idle or the lifecycle closes. */
    Held,

    /** Answered "later": the player waits, paused, for the gain that will start it (#451). */
    Waiting,

    /** Refused. Nothing is held, and there is no sound to make. */
    Denied,

    /** It came back after close, which gave back only what was held then: give a grant back now. */
    AfterClose,
}
