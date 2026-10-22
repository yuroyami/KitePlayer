package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pauses the player when its sound moves from headphones to speakers, as [KitePlayer.attachMediaSession]
 * owns it.
 *
 * macOS has no audio session, so it sends no notice for this. The audio output watches the route
 * instead and the player reports it as [PlayerEvent.AudioOutputBecameNoisy]. macOS also has no
 * phone call that takes the sound away, so that event is the only interruption a Mac has.
 */
internal fun interruptionHandling(player: KitePlayer, policy: InterruptionPolicy): AutoCloseable =
    MacInterruptionHandle(PlayerSessionTarget(player), policy).also { it.follow(player) }

internal class MacInterruptionHandle(private val target: SessionTarget, policy: InterruptionPolicy) : AutoCloseable {

    private val applier = InterruptionApplier(target, policy)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Starts undispatched, so the queue behind the lossless events exists before this returns and
     * no event from then on is missed.
     */
    fun follow(player: KitePlayer) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.losslessEvents.collect(::on) }
    }

    /**
     * One player event. The collector calls this on the main thread; a test calls it directly.
     *
     * The event waited in a queue, so the mark is compared again here: a play or a pause the
     * listener made since the event was sent wins over it.
     */
    fun on(event: PlayerEvent) {
        if (event !is PlayerEvent.AudioOutputBecameNoisy) return
        if (event.transportMark != target.transportMark) return
        applier.handle(InterruptionEvent.BecameNoisy)
    }

    override fun close() {
        scope.cancel()
        applier.release()
    }
}
