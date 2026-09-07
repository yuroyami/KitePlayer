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
    val volume: Float
    val videoEnabled: Boolean
    fun play()
    fun pause()
    fun setVolume(value: Float)
    fun setVideoEnabled(enabled: Boolean)
}

internal class PlayerSessionTarget(private val player: KitePlayer) : SessionTarget {
    override val playing: Boolean get() = player.state.value.status == PlaybackStatus.Playing
    override val volume: Float get() = player.state.value.volume
    override val videoEnabled: Boolean get() = player.state.value.videoEnabled
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun setVolume(value: Float) = player.setVolume(value)
    override fun setVideoEnabled(enabled: Boolean) = player.setVideoEnabled(enabled)
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
    private var volumeBeforeDuck: Float? = null

    fun handle(event: InterruptionEvent) {
        val decision = machine.on(event, target.playing)
        // Volume first: a permanent loss arriving while ducked has to give the volume back BEFORE
        // it pauses, or the next press of play is quiet for no reason the listener can see.
        applyDuck(decision.ducked)
        when (decision.transport) {
            SessionTransport.Pause -> target.pause()
            SessionTransport.Resume -> target.play()
            SessionTransport.None -> Unit
        }
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
