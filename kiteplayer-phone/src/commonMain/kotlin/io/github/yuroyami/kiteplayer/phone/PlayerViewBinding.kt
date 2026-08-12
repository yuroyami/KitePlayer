package io.github.yuroyami.kiteplayer.phone

/**
 * The one attach state machine both platform views drive.
 *
 * The law: a renderer exists exactly while a player is set AND the platform surface is ready.
 * Both views feed the same two facts in through [setPlayer], [surfaceReady] and [surfaceGone],
 * and this class owns every ordering rule that follows from them, so the rules are written once
 * and tested once instead of twice with a platform in the way.
 *
 * The ordering rules, and why each holds:
 *
 * 1. **Close before detach.** When the surface goes away, the renderer is closed BEFORE the
 *    platform callback returns, because on Android a Surface touched after `surfaceDestroyed`
 *    returns is a native abort, not an exception. The output renderers' close blocks until their
 *    drawing worker has finished, which is exactly what makes closing here sufficient. Only then
 *    is the player told to detach; detach is asynchronous, and a present that races the swap
 *    lands on a closed renderer, which refuses it safely.
 * 2. **A player swap tears down first.** The old renderer is closed and the OLD player detached
 *    before anything is built for the new player, so no renderer is ever shared between two
 *    players.
 * 3. **Every transition is idempotent.** Platform lifecycles deliver duplicate callbacks; a
 *    second `surfaceGone` or an assignment of the same player must do nothing.
 *
 * Effects arrive as functions so this class stays pure Kotlin and the views stay thin: the
 * Android view supplies a Surface-backed renderer factory, the iOS view a layer-backed one, and
 * the tests supply recorders.
 *
 * Not thread-safe. Every call must come from the platform's main thread, which is where both
 * platforms deliver the lifecycle callbacks this is driven by.
 */
internal class PlayerViewBinding<P : Any, R : Any>(
    private val createRenderer: () -> R,
    private val attach: (P, R) -> Unit,
    private val detach: (P) -> Unit,
    private val close: (R) -> Unit,
) {
    private var player: P? = null
    private var surfaceIsReady = false
    private var renderer: R? = null

    /** The renderer currently alive, for diagnostic passthroughs. Null between generations. */
    val activeRenderer: R? get() = renderer

    /** Replaces the bound player. Rule 2: the old pairing is fully torn down first. */
    fun setPlayer(next: P?) {
        if (next === player) return
        dropRenderer()
        player = next
        buildIfReady()
    }

    /** The platform surface became usable. Idempotent. */
    fun surfaceReady() {
        if (surfaceIsReady) return
        surfaceIsReady = true
        buildIfReady()
    }

    /**
     * The platform surface is going away. Rule 1: when this returns, nothing will touch the
     * surface again. Idempotent.
     */
    fun surfaceGone() {
        if (!surfaceIsReady) return
        surfaceIsReady = false
        dropRenderer()
    }

    private fun buildIfReady() {
        if (renderer != null) return
        val boundPlayer = player ?: return
        if (!surfaceIsReady) return
        val built = createRenderer()
        renderer = built
        attach(boundPlayer, built)
    }

    private fun dropRenderer() {
        val dropped = renderer ?: return
        renderer = null
        // Close first: after this returns the surface is safe to release. Detach second: the
        // engine keeps playing without a picture, and any present already in flight lands on a
        // closed renderer, which refuses and closes the frame.
        close(dropped)
        player?.let(detach)
    }
}
