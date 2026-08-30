package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.VideoSize
import java.awt.Canvas

/**
 * A desktop player view: an ordinary AWT canvas that a renderer paints video into.
 *
 * It exists for the reason the Android and iOS views exist. A platform component painted by its
 * own thread keeps its frame rate when the surrounding UI is busy, where video drawn as part of
 * the UI's own scene stops when that scene stops. On desktop that is the difference between a
 * heavy Compose window stuttering the picture and not.
 *
 * Install a [rendererFactory] from a rendering adapter before assigning [player]. The renderer is
 * attached as soon as both exist, before the canvas has a peer, so a renderer-coupled decoder can
 * take part in decoder selection; the canvas is then handed over and taken back as AWT creates and
 * destroys the peer. Unlike the Android view there is no separate subtitle component: a desktop
 * renderer composites overlays into the same canvas, which is what the engine's overlay contract
 * already expects of it.
 *
 * **Compose content drawn over this view cannot receive mouse input, and that is a platform rule
 * rather than a bug here.** macOS delivers a click to the topmost NATIVE view under the pointer,
 * and this canvas is one; anything Compose paints above it afterwards is invisible to that
 * decision. Controls that must be clickable ON TOP of video belong in a borderless window owned by
 * this view's window, which is measurably the only arrangement that works. Controls that do not
 * overlap the video need nothing special. Measured 2026-08-30 across seven arrangements, in
 * `kiteplayer-sample-desktop/INTEROP-SPIKE.md`.
 *
 * Call [release] when the owner is permanently destroyed. Removing the canvas from its container
 * is not proof it will not be reused, so that alone does not release it.
 *
 * All members must be used from the AWT event dispatch thread, which is where the lifecycle
 * callbacks that drive this arrive.
 */
public open class KitePlayerAwtView : Canvas() {

    private val ledger = AwtViewLedger()

    internal val binding = PlayerViewBinding<KitePlayer, AwtPlayerViewRenderer>(
        createRenderer = {
            rendererFactory?.let { factory ->
                val renderer = factory.create(
                    onVideoGeometry = { size, rotationDegrees ->
                        ledger.geometry(size.displayAspect, rotationDegrees)
                    },
                )
                try {
                    renderer.setCanvas(this.takeIf { it.isDisplayable })
                    renderer
                } catch (configurationFailure: Throwable) {
                    // Not yet known to PlayerViewBinding, so that binding cannot roll it back.
                    // Close it here before propagating the failed construction, exactly as the
                    // Android view does for the same reason.
                    try {
                        renderer.close()
                    } catch (closeFailure: Throwable) {
                        if (closeFailure !== configurationFailure) {
                            configurationFailure.addSuppressed(closeFailure)
                        }
                    }
                    throw configurationFailure
                }
            }
        },
        attach = { player, renderer -> player.attachRenderer(renderer) },
        detach = { player, renderer ->
            try {
                player.detachRenderer(expected = renderer)
            } catch (_: IllegalStateException) {
                // The ordinary teardown order closes the player before clearing the view, and a
                // closed player refuses every command including this one. Closing already
                // detached everything, so there is nothing left to undo.
            }
        },
        close = { renderer ->
            try {
                renderer.close()
            } finally {
                ledger.absorb(renderer)
            }
        },
        // Headless-capable, like the Android view: the renderer exists as soon as the player does
        // so its decoder factory can be consulted, and losing the canvas does not end it.
        rendererNeedsSurface = false,
    )

    /**
     * Creates the renderer attached to [player]. Null keeps this view deliberately headless.
     *
     * Replacing the factory closes and detaches the current renderer before creating its
     * replacement. Install it before opening media when the renderer contributes a hardware
     * decoder factory; adding one to an already-open session does not reselect the decoder.
     */
    public var rendererFactory: AwtPlayerViewRendererFactory? = null
        set(value) {
            if (field === value) return
            field = value
            binding.rendererConfigurationChanged()
        }

    /**
     * The player whose picture this view shows. Assigning replaces the previous pairing; null
     * detaches. Playback never depends on this being set.
     */
    public var player: KitePlayer? = null
        set(value) {
            field = value
            binding.setPlayer(value)
        }

    /** Permanently releases this view's player pairing and active renderer. */
    public fun release() {
        player = null
    }

    /** The video's display aspect as the renderer last reported it, or 0 when there is none. */
    public val videoDisplayAspect: Float get() = ledger.displayAspect

    /** The video's rotation in degrees as the renderer last reported it. */
    public val videoRotation: Int get() = ledger.rotationDegrees

    /** Frames painted onto this canvas, across every renderer this view has built. */
    public val presentedFrames: Long get() = ledger.presented(binding.activeRenderer)

    /** Frames replaced by a newer one before they could be drawn. */
    public val supersededFrames: Long get() = ledger.superseded(binding.activeRenderer)

    /** Frames that reached no canvas for a reason other than being superseded. */
    public val failedFrames: Long get() = ledger.failed(binding.activeRenderer)

    override fun addNotify() {
        super.addNotify()
        canvasAvailable()
    }

    override fun removeNotify() {
        // Hand the canvas back BEFORE the peer goes away, for the reason the binding's rule 1
        // gives: a renderer must not be painting into a component that is losing its peer.
        canvasLost()
        super.removeNotify()
    }

    /**
     * The peer exists, so the renderer may paint. Separate from [addNotify] so a test can drive
     * the lifecycle without a display, which is the only way this is testable on a build machine.
     */
    internal fun canvasAvailable() {
        binding.activeRenderer?.setCanvas(this)
        binding.surfaceReady()
    }

    /** The peer is going away. Fences the renderer off the canvas before returning. */
    internal fun canvasLost() {
        binding.activeRenderer?.setCanvas(null)
        binding.surfaceGone()
    }
}

/**
 * Renderer adapter for [KitePlayerAwtView].
 *
 * The view owns the canvas and the renderer must not dispose it. Passing null fences all use of
 * the previous canvas before returning, which is what makes it safe for the view to let AWT
 * destroy the peer afterwards.
 */
public interface AwtPlayerViewRenderer : PlayerViewRenderer {
    public fun setCanvas(canvas: Canvas?)
}

/** Creates the desktop renderer adapter used by [KitePlayerAwtView]. */
public fun interface AwtPlayerViewRendererFactory {
    /**
     * Creates one renderer generation. The callback may arrive off the event dispatch thread; the
     * view only stores what it is given.
     */
    public fun create(
        onVideoGeometry: (VideoSize, rotationDegrees: Int) -> Unit,
    ): AwtPlayerViewRenderer
}

/**
 * The view's own bookkeeping across renderer generations, kept apart from the view so it can be
 * tested without an AWT peer or a running player.
 *
 * A renderer generation ends whenever the factory changes or the pairing is dropped, and its
 * counters die with it. What the diagnostics want is the total for the VIEW, so each ending
 * generation's counts are absorbed here first. Geometry goes the other way and is deliberately
 * reset, because a view with no renderer has no video and reporting the last one's shape would be
 * a stale answer rather than a missing one.
 */
internal class AwtViewLedger {
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    var displayAspect: Float = 0f
        private set
    var rotationDegrees: Int = 0
        private set

    fun geometry(aspect: Float, rotation: Int) {
        displayAspect = aspect
        rotationDegrees = rotation
    }

    /** Takes over a dying generation's counts and forgets its geometry. */
    fun absorb(renderer: PlayerViewRenderer) {
        presentedBefore += renderer.presentedFrames
        supersededBefore += renderer.supersededFrames
        failedBefore += renderer.failedFrames
        displayAspect = 0f
        rotationDegrees = 0
    }

    fun presented(live: PlayerViewRenderer?): Long = presentedBefore + (live?.presentedFrames ?: 0L)
    fun superseded(live: PlayerViewRenderer?): Long = supersededBefore + (live?.supersededFrames ?: 0L)
    fun failed(live: PlayerViewRenderer?): Long = failedBefore + (live?.failedFrames ?: 0L)
}
