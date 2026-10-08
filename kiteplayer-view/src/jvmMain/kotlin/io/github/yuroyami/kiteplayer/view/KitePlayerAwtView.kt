package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.util.EnumMap
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import kotlin.time.Duration

/**
 * A desktop player view: an ordinary AWT canvas that a renderer paints video into.
 *
 * It exists for the reason the Android and iOS views exist. A platform component painted by its
 * own thread keeps its frame rate when the surrounding UI is busy, where video drawn as part of
 * the UI's own scene stops when that scene stops. On desktop that is the difference between a
 * heavy Compose window stuttering the picture and not.
 *
 * A view with no [rendererFactory] of its own uses [PlayerViewDefaults.rendererFactory], which the
 * `kiteplayer` module sets when it builds a player, so `view.player = player` is the whole setup.
 * The renderer is attached as soon as the player and a factory exist, before the canvas has a peer,
 * so a renderer-coupled decoder can take part in decoder selection; the canvas is then handed over
 * and taken back as AWT creates and destroys the peer. Unlike the Android view there is no separate subtitle component: a desktop
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
            (rendererFactory ?: PlayerViewDefaults.rendererFactory)?.let { factory ->
                val renderer = factory.create(
                    onVideoGeometry = { size, rotationDegrees ->
                        ledger.geometry(size.displayAspect, rotationDegrees)
                    },
                )
                try {
                    renderer.setCanvas(rendererCanvas())
                    (renderer as? AwtControlsCanvas)?.setControlsPainter(awtControls)
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
     * Creates the renderer attached to [player]. Null uses [PlayerViewDefaults.rendererFactory], and
     * the view stays headless when that is null too.
     *
     * Replacing the factory closes and detaches the current renderer before creating its
     * replacement.
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
            updateAccessibilityState()
            watchPlayer()
            rebuildControls()
        }

    /**
     * Draws default controls over the picture: play and pause, previous and next for a queue, the
     * seek bar with its buffered ranges, the times, the volume, menus for the audio and subtitle
     * tracks, the quality and the speed, and picture in picture and full screen buttons when
     * [onPictureInPicture] and [onFullScreen] are set. False by default.
     *
     * They are painted into the canvas with Java2D, so they need a renderer that is an
     * [AwtControlsCanvas], as the default one is. A click on the picture shows or hides them, and
     * they hide by themselves while the player plays. Space plays or pauses, the left and right
     * arrows move ten seconds, the up and down arrows change the volume, M mutes and F asks for
     * full screen. While they show, subtitles stand above them. They do what
     * `KitePlayerControls` of `kiteplayer-compose-ui` does, from the same [PlayerControlsModel].
     */
    public var showsControls: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildControls()
        }

    /** Every word the controls show or a screen reader says. English by default; pass translated ones. */
    public var controlsStrings: PlayerControlsStrings = PlayerControlsStrings.Default
        set(value) {
            field = value
            awtControls?.model?.strings = value
        }

    /** What the full screen button and the F key do, or null for no such button. The window is the application's. */
    public var onFullScreen: (() -> Unit)? = null
        set(value) {
            field = value
            awtControls?.model?.onFullScreen = value
        }

    /** What the picture in picture button does, or null for no such button. See [KitePlayerPictureInPicture]. */
    public var onPictureInPicture: (() -> Unit)? = null
        set(value) {
            field = value
            awtControls?.model?.onPictureInPicture = value
        }

    /** The model behind the controls, to show or hide them from code, or null while [showsControls] is false or no player is set. */
    public val controls: PlayerControlsModel? get() = awtControls?.model

    internal var awtControls: AwtPlayerControls? = null
        private set
    private var controlsScope: CoroutineScope? = null
    private val accessibleControls = EnumMap<AwtControl, AccessibleControl>(AwtControl::class.java)

    /** Builds the controls for the player and the switch as they stand now, and drops the old ones. */
    private fun rebuildControls() {
        awtControls?.close()
        controlsScope?.cancel()
        awtControls = null
        controlsScope = null
        accessibleControls.clear()
        val paired = player
        if (showsControls && paired != null) {
            val scope = CoroutineScope(SupervisorJob() + AwtEventThread)
            val model = PlayerControlsModel(paired, scope, controlsStrings)
            model.onFullScreen = onFullScreen
            model.onPictureInPicture = onPictureInPicture
            controlsScope = scope
            awtControls = AwtPlayerControls(this, model, scope, ::repaintControls).also { it.paintsHere = floatingCanvas == null }
        }
        (binding.activeRenderer as? AwtControlsCanvas)?.setControlsPainter(awtControls)
        repaintControls()
    }

    private fun repaintControls() {
        val canvas = binding.activeRenderer as? AwtControlsCanvas
        if (canvas != null) canvas.repaintControls() else repaint()
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        // A renderer that draws the controls into its frames leaves nothing to draw here.
        if (binding.activeRenderer is AwtControlsCanvas) return
        (g as? Graphics2D)?.let { awtControls?.paint(it, width, height) }
    }

    /** What a screen reader calls this view, as its accessible name. English by default; pass a translated one. */
    public var accessibilityVideoLabel: String = DEFAULT_VIDEO_ACCESSIBILITY_LABEL
        set(value) {
            val old = field
            field = value
            getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, old, value)
        }

    /**
     * Builds what a screen reader says about the state from the status, the position and the
     * duration. The default, [accessibilityStateText], is English; pass a translated one.
     */
    public var accessibilityStateFormat: (PlaybackStatus, Duration, Duration?) -> String = ::accessibilityStateText
        set(value) {
            field = value
            updateAccessibilityState()
        }

    /** The state a screen reader reads as this view's accessible description. */
    private var accessibilityState: String = accessibilityStateText(PlaybackStatus.Idle, Duration.ZERO, null)

    /**
     * Re-reads what a screen reader should say about the player and tells the platform.
     *
     * This view calls it when the player is assigned, and while it has its peer, each time the
     * player's status or duration changes. It does not follow the position, because a value that
     * changed on every tick would make a screen reader speak continuously. So the position read
     * out is the one from the last change. An application that wants a fresh position, for example
     * after a seek, calls this beside its own controls.
     */
    public fun updateAccessibilityState() {
        val snapshot = player?.state?.value
        val text = if (snapshot == null) {
            accessibilityStateFormat(PlaybackStatus.Idle, Duration.ZERO, null)
        } else {
            accessibilityStateFormat(snapshot.status, player?.progress?.value?.position ?: Duration.ZERO, snapshot.duration)
        }
        val old = accessibilityState
        accessibilityState = text
        if (old != text) {
            getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY, old, text)
        }
    }

    private var videoAccessibleContext: AccessibleContext? = null

    override fun getAccessibleContext(): AccessibleContext =
        videoAccessibleContext ?: VideoAccessibleContext().also { videoAccessibleContext = it }

    /** The canvas's own context, with the label as its name and the state as its description. */
    private inner class VideoAccessibleContext : AccessibleAWTCanvas() {
        override fun getAccessibleName(): String = accessibilityVideoLabel

        override fun getAccessibleDescription(): String = accessibilityState

        // The controls are painted, so each part is listed here for a screen reader to reach.
        override fun getAccessibleChildrenCount(): Int = awtControls?.reachable()?.size ?: 0

        override fun getAccessibleChild(i: Int): Accessible? {
            val painted = awtControls ?: return null
            val control = painted.reachable().getOrNull(i) ?: return null
            return accessibleControls.getOrPut(control) {
                AccessibleControl(painted, this@KitePlayerAwtView, control).also { it.setAccessibleParent(this@KitePlayerAwtView) }
            }
        }
    }

    /** Follows the player's state for what a screen reader hears, only while this view has its peer and a player. */
    private var stateWatch: Job? = null

    private fun watchPlayer() {
        stateWatch?.cancel()
        stateWatch = null
        val watched = player ?: return
        if (!hasPeer) return
        stateWatch = CoroutineScope(Dispatchers.Default).launch {
            var announced: Pair<PlaybackStatus, Duration?>? = null
            watched.state.collect { snapshot ->
                // The status and the duration, never the position; see updateAccessibilityState.
                val heard = snapshot.status to snapshot.duration
                if (heard != announced) {
                    announced = heard
                    // Every member of this view belongs to the event dispatch thread.
                    EventQueue.invokeLater { if (player === watched) updateAccessibilityState() }
                }
            }
        }
    }

    /**
     * Accepted for symmetry with the Android and iOS views, and does nothing: the desktop JVM has
     * no call that keeps the display awake. A desktop application that needs it asks its own
     * platform, for example with a power assertion on macOS.
     */
    public var keepDisplayAwake: Boolean = true

    /** Permanently releases this view's player pairing and active renderer. */
    public fun release() {
        player = null
    }

    /** Whether AWT has given this canvas its peer, as [canvasAvailable] and [canvasLost] report it. */
    private var hasPeer = false

    /**
     * The canvas of an open [KitePlayerPictureInPicture] window, or null when none is open.
     *
     * While it is set the renderer paints there, and this view's own peer events leave the renderer
     * where it is. Setting it moves the live renderer at once; clearing it hands the renderer back.
     */
    internal var floatingCanvas: Canvas? = null
        set(value) {
            if (field === value) return
            field = value
            // The floating window takes its own clicks, so the controls are not drawn into it.
            awtControls?.paintsHere = value == null
            binding.activeRenderer?.setCanvas(rendererCanvas())
        }

    /**
     * The canvas the renderer should paint into: the floating window's while one is open, else this
     * view while it has a peer, else none. Renderer creation and both peer events ask this.
     */
    internal fun rendererCanvas(): Canvas? = floatingCanvas ?: this.takeIf { hasPeer }

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
        hasPeer = true
        binding.activeRenderer?.setCanvas(rendererCanvas())
        binding.surfaceReady()
        watchPlayer()
    }

    /**
     * The peer is going away. Fences the renderer off this canvas before returning; a renderer
     * painting into an open floating window stays there.
     */
    internal fun canvasLost() {
        hasPeer = false
        binding.activeRenderer?.setCanvas(rendererCanvas())
        binding.surfaceGone()
        watchPlayer()
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
