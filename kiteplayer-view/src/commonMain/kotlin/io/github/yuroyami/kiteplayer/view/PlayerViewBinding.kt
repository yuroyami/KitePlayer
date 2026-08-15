package io.github.yuroyami.kiteplayer.view

/**
 * The one attach state machine both platform views drive.
 *
 * By default a renderer exists exactly while a player is set AND the platform surface is ready.
 * A renderer with its own headless fallback can opt into the earlier player-only lifetime described
 * below. Both views feed their state through [setPlayer], [surfaceReady] and [surfaceGone], and this
 * class owns every ordering rule that follows, so the rules are written once and tested once instead
 * of twice with a platform in the way.
 *
 * The ordering rules, and why each holds:
 *
 * 1. **Close before detach.** When a surface-bound renderer's surface goes away, it is closed
 *    BEFORE the platform callback returns, because on Android a Surface touched after
 *    `surfaceDestroyed` returns is a native abort, not an exception. The output renderers' close
 *    blocks until their drawing worker has finished, which is exactly what makes closing here
 *    sufficient. Only then is the player told to detach; detach is asynchronous, and a present
 *    that races the swap lands on a closed renderer, which refuses it safely.
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
 * [rendererNeedsSurface] is false for a renderer that owns a headless platform fallback. That
 * renderer is attached as soon as the player is assigned, so its renderer-coupled decoder factory
 * can participate in decoder selection before the real display surface arrives. Surface lifecycle
 * callbacks must still be forwarded to the platform renderer by the view; this binding only
 * decides whether losing the surface also ends the renderer generation.
 *
 * Not thread-safe. Every call must come from the platform's main thread, which is where both
 * platforms deliver the lifecycle callbacks this is driven by.
 */
internal class PlayerViewBinding<P : Any, R : Any>(
    private val createRenderer: () -> R?,
    private val attach: (P, R) -> Unit,
    private val detach: (P) -> Unit,
    private val close: (R) -> Unit,
    private val rendererNeedsSurface: Boolean = true,
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

    /** Marks the platform surface usable. Idempotent. */
    fun surfaceReady() {
        if (surfaceIsReady) return
        surfaceIsReady = true
        buildIfReady()
    }

    /**
     * Marks the platform surface unavailable. Rule 1 closes a surface-bound generation before
     * returning. A headless-capable generation remains attached and must receive the actual surface
     * change directly from its platform view. Idempotent.
     */
    fun surfaceGone() {
        if (!surfaceIsReady) return
        surfaceIsReady = false
        if (rendererNeedsSurface) dropRenderer()
    }

    /**
     * Re-evaluates the renderer factory after configuration that affects renderer construction
     * changes. An existing generation is torn down before its replacement is requested. When the
     * factory still cannot provide a renderer, the binding remains detached until this hook or
     * another ordinary lifecycle transition gives it another opportunity.
     */
    fun rendererConfigurationChanged() {
        dropRenderer()
        buildIfReady()
    }

    private fun buildIfReady() {
        if (renderer != null) return
        val boundPlayer = player ?: return
        if (rendererNeedsSurface && !surfaceIsReady) return
        val built = createRenderer() ?: return
        renderer = built
        try {
            attach(boundPlayer, built)
        } catch (attachFailure: Throwable) {
            renderer = null
            try {
                close(built)
            } catch (closeFailure: Throwable) {
                if (closeFailure !== attachFailure) attachFailure.addSuppressed(closeFailure)
            }
            throw attachFailure
        }
    }

    private fun dropRenderer() {
        val dropped = renderer ?: return
        renderer = null
        // Close first: after this returns the surface is safe to release. Detach second: the
        // engine keeps playing without a picture, and any present already in flight lands on a
        // closed renderer, which refuses and closes the frame.
        var failure: Throwable? = null
        try {
            close(dropped)
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            player?.let(detach)
        } catch (detachFailure: Throwable) {
            val closeFailure = failure
            if (closeFailure == null) {
                failure = detachFailure
            } else {
                if (detachFailure !== closeFailure) closeFailure.addSuppressed(detachFailure)
            }
        }
        failure?.let { throw it }
    }
}
