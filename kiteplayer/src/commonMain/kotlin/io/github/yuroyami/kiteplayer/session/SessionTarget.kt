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
    val volume: Float
    val videoEnabled: Boolean
    fun play()
    fun pause()
    fun setVolume(value: Float)
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
    override val volume: Float get() = player.state.value.volume
    override val videoEnabled: Boolean get() = player.state.value.videoEnabled
    override fun play() = player.playFromRemote()
    override fun pause() = player.pauseFromRemote()
    override fun setVolume(value: Float) = player.setVolume(value)
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
 * Turns interruption decisions into calls, and remembers the volume it ducked from.
 *
 * The remembered volume is what makes ducking reversible. Reading it back from the player at the
 * moment of the duck, rather than assuming unity, is what keeps a listener's own quiet setting.
 */
internal class InterruptionApplier(
    private val target: SessionTarget,
    private val policy: InterruptionPolicy,
) {
    private val machine = InterruptionMachine(policy)
    private val claim = PauseClaim(target)
    private var volumeBeforeDuck: Float? = null

    fun handle(event: InterruptionEvent) {
        val decision = machine.on(event, target.playing)
        // Volume first: a permanent loss arriving while ducked has to give the volume back BEFORE
        // it pauses, or the next press of play is quiet for no reason the listener can see.
        applyDuck(decision.ducked)
        claim.apply(decision.transport)
    }

    private fun applyDuck(ducked: Boolean) {
        if (ducked) {
            if (volumeBeforeDuck == null) {
                volumeBeforeDuck = target.volume
                target.setVolume(policy.duckVolume)
            }
        } else {
            volumeBeforeDuck?.let {
                volumeBeforeDuck = null
                target.setVolume(it)
            }
        }
    }

    /** Gives the volume back on close, so an application that stops listening is not left quiet. */
    fun release() = applyDuck(false)
}

/**
 * When to ask the platform for the right to make sound and when to give it back.
 *
 * Held from the first request to play until the player goes idle, across a pause. Giving it back
 * at a pause would look tidier and would break resuming after a phone call: the platform only
 * tells a holder that the sound is theirs again.
 */
internal class SessionFocusLifecycle {
    private var held = false

    /** True to request, false to give back, null when nothing needs to change. */
    fun on(status: PlaybackStatus): Boolean? = when (status) {
        PlaybackStatus.Playing, PlaybackStatus.Buffering -> if (held) null else true.also { held = true }
        PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed ->
            if (held) false.also { held = false } else null
        PlaybackStatus.Paused, PlaybackStatus.Opening -> null
    }

    /** Gives it back on close, when it was held. */
    fun release(): Boolean = held.also { held = false }
}
