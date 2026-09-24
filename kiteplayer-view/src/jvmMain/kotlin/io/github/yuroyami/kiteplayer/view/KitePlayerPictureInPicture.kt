package io.github.yuroyami.kiteplayer.view

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.Point
import java.awt.PopupMenu
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Shows the picture of a [KitePlayerAwtView] in a small window that stays above every other window.
 *
 * The JVM has no system picture in picture, so this opens a borderless always-on-top window and
 * moves the view's renderer into it. The renderer moves, not the view: no decoder restarts, a
 * paused picture is painted again as soon as the window has its size, and subtitles come along
 * because the renderer draws them. [stop] hands the renderer back to the view.
 *
 * In the window, a click plays or pauses, a double click returns to the app, a drag moves the
 * window, and a drag from its lower right corner resizes it and keeps its aspect. A right click
 * opens a menu with play or pause, "Back to app" and "Close". The window has no drawn buttons,
 * because nothing Swing draws can appear above a native canvas.
 *
 * All members must be used from the AWT event dispatch thread, like the view itself.
 */
public class KitePlayerPictureInPicture private constructor(
    private val view: KitePlayerAwtView,
    private val options: FloatingWindowOptions,
) : AutoCloseable {

    private val activeState = MutableStateFlow(false)
    private var window: JWindow? = null
    private var controls: FloatingControls? = null
    private var closed = false

    /** True until [close]. [createOrNull] already checked that this JVM can open the window. */
    public val isPossible: Boolean get() = !closed

    /** True while the floating window is on screen. */
    public val isActive: Boolean get() = activeState.value

    /** [isActive], as a flow that changes when the window opens and when it closes. */
    public val active: StateFlow<Boolean> = activeState.asStateFlow()

    /**
     * Called for a double click or "Back to app" in the window, in place of the default. The
     * default, used while this is null, calls [stop] and brings the view's own window to the front.
     */
    public var onRestoreRequested: (() -> Unit)? = null

    /**
     * Opens the window on the screen that shows the view, in the corner [FloatingWindowOptions]
     * names, and moves the picture into it. Does nothing while the window is open, while another
     * floating window shows the same view, or after [close].
     */
    public fun start() {
        if (closed || window != null || view.floatingCanvas != null) return
        val configuration = view.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        val aspect = floatingAspect(view.videoDisplayAspect, view.videoRotation)
        val canvas = Canvas().apply { background = Color.BLACK }
        val floating = JWindow().apply {
            isAlwaysOnTop = true
            contentPane.background = Color.BLACK
            contentPane.add(canvas, BorderLayout.CENTER)
            bounds = floatingWindowBounds(configuration.bounds, insets, aspect, options)
        }
        val mouse = FloatingControls(floating, canvas).also { it.install() }
        window = floating
        controls = mouse
        try {
            // Moved before the window shows, so the renderer's resize listener sees the canvas get
            // its size and paints the retained picture: a paused player shows at once.
            view.floatingCanvas = canvas
            floating.isVisible = true
        } catch (failure: Throwable) {
            window = null
            controls = null
            mouse.dispose()
            view.floatingCanvas = null
            floating.dispose()
            throw failure
        }
        activeState.value = true
    }

    /** Closes the window and hands the picture back to the view. Does nothing when it is not open. */
    public fun stop() {
        val floating = window ?: return
        window = null
        controls?.dispose()
        controls = null
        try {
            // Handed back first: a renderer must not paint into a canvas whose peer is going away.
            view.floatingCanvas = null
        } finally {
            floating.dispose()
            activeState.value = false
        }
    }

    /** Calls [stop], then refuses every later [start]. */
    override fun close() {
        if (closed) return
        try {
            stop()
        } finally {
            closed = true
            onRestoreRequested = null
        }
    }

    private fun togglePlayback() {
        val player = view.player ?: return
        if (player.state.value.status.isActive) player.pause() else player.play()
    }

    private fun restore() {
        val requested = onRestoreRequested
        if (requested != null) {
            requested()
            return
        }
        stop()
        val owner = SwingUtilities.getWindowAncestor(view) ?: return
        if (owner is Frame && owner.extendedState and Frame.ICONIFIED != 0) {
            owner.extendedState = owner.extendedState and Frame.ICONIFIED.inv()
        }
        owner.toFront()
        owner.requestFocus()
    }

    public companion object {
        /**
         * Builds the floating window for [view], or answers null when this JVM cannot show one:
         * a headless JVM has no screen, and some window systems cannot keep a window on top.
         *
         * Nothing opens until [start].
         */
        public fun createOrNull(
            view: KitePlayerAwtView,
            options: FloatingWindowOptions = FloatingWindowOptions(),
        ): KitePlayerPictureInPicture? {
            if (!floatingWindowsSupported()) return null
            return KitePlayerPictureInPicture(view, options)
        }
    }

    /**
     * The window's mouse handling. The canvas fills the whole window and is a native component, so
     * every event over the window arrives here.
     */
    private inner class FloatingControls(
        private val floating: JWindow,
        private val canvas: Canvas,
    ) : MouseAdapter() {
        private var pressedOnScreen: Point? = null
        private var startBounds: Rectangle? = null
        private var resizing = false
        private var dragged = false
        private val menu = PopupMenu()
        private val playOrPause = MenuItem()

        // A double click starts with a single one, so a single click waits for the double click
        // interval before it plays or pauses, and a double click cancels it.
        private val singleClick = Timer(doubleClickInterval()) { togglePlayback() }.apply { isRepeats = false }

        fun install() {
            playOrPause.addActionListener { togglePlayback() }
            menu.add(playOrPause)
            menu.add(MenuItem("Back to app").apply { addActionListener { restore() } })
            menu.add(MenuItem("Close").apply { addActionListener { stop() } })
            canvas.add(menu)
            canvas.addMouseListener(this)
            canvas.addMouseMotionListener(this)
        }

        fun dispose() {
            singleClick.stop()
        }

        override fun mousePressed(event: MouseEvent) {
            // Platforms differ on whether the menu gesture is the press or the release.
            if (event.isPopupTrigger) {
                showMenu(event)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(event)) return
            pressedOnScreen = event.locationOnScreen
            startBounds = floating.bounds
            resizing = isOnResizeGrip(event.x, event.y, canvas.width, canvas.height)
            dragged = false
        }

        override fun mouseDragged(event: MouseEvent) {
            val from = pressedOnScreen ?: return
            val start = startBounds ?: return
            val dx = event.xOnScreen - from.x
            val dy = event.yOnScreen - from.y
            dragged = true
            if (!resizing) {
                floating.setLocation(start.x + dx, start.y + dy)
                return
            }
            val configuration = floating.graphicsConfiguration
            val usable = usableArea(configuration.bounds, Toolkit.getDefaultToolkit().getScreenInsets(configuration))
            floating.size = resizedKeepingAspect(
                start = Dimension(start.width, start.height),
                dx = dx,
                dy = dy,
                aspect = start.width.toFloat() / start.height,
                // Room to the right and below, but never less than the window already has.
                maxWidth = maxOf(usable.x + usable.width - start.x, start.width),
                maxHeight = maxOf(usable.y + usable.height - start.y, start.height),
            )
        }

        override fun mouseReleased(event: MouseEvent) {
            pressedOnScreen = null
            startBounds = null
            if (event.isPopupTrigger) showMenu(event)
        }

        override fun mouseClicked(event: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(event) || dragged) return
            if (event.clickCount >= 2) {
                singleClick.stop()
                restore()
            } else {
                singleClick.restart()
            }
        }

        private fun showMenu(event: MouseEvent) {
            playOrPause.label = if (view.player?.state?.value?.status?.isActive == true) "Pause" else "Play"
            menu.show(canvas, event.x, event.y)
        }
    }
}

/**
 * Whether this JVM can show an always-on-top window. Headless is asked first: a headless JVM has
 * no screen, and its toolkit is not the one that would draw the window. `KitePlayerPlatform` in the
 * `kiteplayer` module asks the same two questions for `supportsPictureInPicture`.
 */
internal fun floatingWindowsSupported(): Boolean =
    !GraphicsEnvironment.isHeadless() &&
        runCatching { Toolkit.getDefaultToolkit().isAlwaysOnTopSupported }.getOrDefault(false)

/** The system's double click interval in milliseconds, or half a second where it is not published. */
private fun doubleClickInterval(): Int =
    runCatching { Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int }
        .getOrNull() ?: 500
