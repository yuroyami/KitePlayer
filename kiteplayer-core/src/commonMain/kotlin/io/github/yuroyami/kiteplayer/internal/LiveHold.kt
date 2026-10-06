package io.github.yuroyami.kiteplayer.internal

import kotlinx.atomicfu.atomic

/**
 * What a paused player told the sender of a real-time stream (#441). The actor asks for the hold
 * while the player is paused and lifts it on play, and the demux lane carries it out between two
 * reads, so each flag is written on one side and read on the other.
 *
 * An RTSP camera ends a session it hears nothing from within its timeout, and a paused player reads
 * nothing, so the lane tells the source once and then repeats the call as the keepalive.
 */
internal class LiveHold {
    /** The player is paused and wants the sender held. Written by the actor. */
    val wanted = atomic(false)

    /** The sender took the pause, so the reads stopped. Written by the demux lane. */
    val told = atomic(false)

    /** The source has no notion of a pause, so the reads go on as before. Written by the demux lane. */
    val refused = atomic(false)

    /** What the sender answered a keepalive with when the session was already gone. Demux lane. */
    val lost = atomic<Throwable?>(null)

    /** When the demux lane last spoke to the sender, on the engine's clock. Demux lane only. */
    var lastCallNanos: Long = 0L

    /** True when the sender was told or lost, so play has to act before the reads go on. */
    val held: Boolean get() = told.value || lost.value != null

    /** Back to no hold, once play has resumed the sender. The refusal stands for the session. */
    fun lift() {
        wanted.value = false
        told.value = false
        lost.value = null
    }
}
