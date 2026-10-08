package io.github.yuroyami.kiteplayer.view

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.PopupMenu
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.accessibility.Accessible
import javax.accessibility.AccessibleAction
import javax.accessibility.AccessibleComponent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.accessibility.AccessibleStateSet
import javax.accessibility.AccessibleValue
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * Draws the controls of a [KitePlayerAwtView] over one frame. It runs on the thread that paints
 * the frame, so it reads only what is safe from any thread and never waits.
 */
public fun interface AwtControlsPainter {
    /** Draws into a canvas of [width] by [height], in the canvas's own coordinates. */
    public fun paint(graphics: Graphics2D, width: Int, height: Int)
}

/**
 * A renderer of [KitePlayerAwtView] that can draw the view's controls into each frame it paints.
 *
 * Nothing a toolkit draws shows above a native canvas, and the renderer repaints the whole canvas
 * for every picture, so controls over the picture have to be part of the frame. A renderer that is
 * not one of these still plays; the view then draws its controls only while no picture is painted.
 */
public interface AwtControlsCanvas {
    /** What to draw over every frame, after the picture and the subtitles, or null for nothing. */
    public fun setControlsPainter(painter: AwtControlsPainter?)

    /** Paints the frame on screen again, because the controls changed. Called on the event thread. */
    public fun repaintControls()
}

/** The parts of the desktop controls a pointer can press. */
internal enum class AwtControl { Previous, Play, Next, Mute, Volume, Seek, Audio, Subtitles, Quality, Speed, PictureInPicture, FullScreen }

/** Where each part of the desktop controls goes in a canvas, in the canvas's own coordinates. */
internal class AwtControlsLayout(
    val parts: Map<AwtControl, Rectangle>,
    /** The top of the darkened strip the controls stand on. */
    val barTop: Int,
    val height: Int,
    /** Where the times start, and the baseline they stand on. */
    val timeX: Int,
    val timeBaseline: Int,
) {
    fun hit(x: Int, y: Int): AwtControl? = parts.entries.firstOrNull { it.value.contains(x, y) }?.key

    /** The part of the height the controls cover, which subtitles stay above. */
    val barShare: Float get() = if (height <= 0) 0f else ((height - barTop).toFloat() / height).coerceIn(0f, 1f)

    /** The fraction of the bar [control] that [x] stands for, between its inset ends. */
    fun fractionAt(control: AwtControl, x: Int): Float {
        val bar = parts[control] ?: return 0f
        val span = bar.width - 2 * THUMB_RADIUS
        if (span <= 0) return 0f
        return ((x - bar.x - THUMB_RADIUS).toFloat() / span).coerceIn(0f, 1f)
    }

    internal companion object {
        const val MARGIN = 8
        const val BUTTON = 36
        const val ICON = 20
        const val SEEK_HEIGHT = 24
        const val THUMB_RADIUS = 6
        const val VOLUME_WIDTH = 96

        /** Below this width the volume shows as its button alone. */
        const val WIDE = 480

        /**
         * One row of buttons along the bottom, the seek bar above it. Play, previous and next, the
         * volume and the times stand on the left, and the menus, picture in picture and full
         * screen on the right. A part with nothing to do is left out.
         */
        fun of(width: Int, height: Int, shown: PlayerControlsSnapshot): AwtControlsLayout {
            val parts = LinkedHashMap<AwtControl, Rectangle>()
            val rowTop = height - MARGIN - BUTTON
            var left = MARGIN
            fun onLeft(control: AwtControl, partWidth: Int = BUTTON) {
                parts[control] = Rectangle(left, rowTop, partWidth, BUTTON)
                left += partWidth
            }
            if (shown.hasQueue) onLeft(AwtControl.Previous)
            onLeft(AwtControl.Play)
            if (shown.hasQueue) onLeft(AwtControl.Next)
            onLeft(AwtControl.Mute)
            if (width >= WIDE) onLeft(AwtControl.Volume, VOLUME_WIDTH)

            var right = width - MARGIN
            fun onRight(control: AwtControl) {
                right -= BUTTON
                // A canvas too narrow for every button keeps the ones on the left.
                if (right >= left) parts[control] = Rectangle(right, rowTop, BUTTON, BUTTON)
            }
            if (shown.canFullScreen) onRight(AwtControl.FullScreen)
            if (shown.canPictureInPicture) onRight(AwtControl.PictureInPicture)
            if (shown.offers(PlayerControlsMenu.Speed)) onRight(AwtControl.Speed)
            if (shown.offers(PlayerControlsMenu.Quality)) onRight(AwtControl.Quality)
            if (shown.offers(PlayerControlsMenu.Subtitles)) onRight(AwtControl.Subtitles)
            if (shown.offers(PlayerControlsMenu.Audio)) onRight(AwtControl.Audio)

            var top = rowTop
            if (shown.seekable) {
                top -= SEEK_HEIGHT
                parts[AwtControl.Seek] = Rectangle(MARGIN, top, (width - 2 * MARGIN).coerceAtLeast(0), SEEK_HEIGHT)
            }
            return AwtControlsLayout(parts, top - MARGIN, height, left + MARGIN, rowTop + BUTTON / 2 + 5)
        }
    }
}

/**
 * The default controls of a [KitePlayerAwtView]: it draws [model] with Java2D and turns the mouse
 * and the keys of [view] into the model's actions. [repaint] asks for the frame to be painted
 * again. Every member except [paint] belongs to the event thread.
 */
internal class AwtPlayerControls(
    private val view: Component,
    val model: PlayerControlsModel,
    scope: CoroutineScope,
    private val repaint: () -> Unit,
) : AwtControlsPainter {

    /** What [paint] draws. Written on the event thread and read on the painting thread. */
    @Volatile
    private var shown: PlayerControlsSnapshot = model.state.value

    /** False while the picture shows somewhere else, such as a floating window, so nothing is drawn. */
    @Volatile
    var paintsHere: Boolean = true

    /** Opens a menu at a point of the view. A test replaces it, because a headless JVM has no menus. */
    var openMenu: (PlayerControlsMenu, x: Int, y: Int) -> Unit = ::showMenu

    private var pressed: AwtControl? = null
    private var pressedOnPicture = false
    private var popup: PopupMenu? = null

    private val mouse = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = press(event.x, event.y)
        override fun mouseDragged(event: MouseEvent) = drag(event.x)
        override fun mouseReleased(event: MouseEvent) = release(event.x, event.y)
        override fun mouseMoved(event: MouseEvent) {
            // A pointer on its way to a button keeps the controls it is aiming at.
            if (shown.visible) model.poke()
        }
    }

    private val keys = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            if (key(event.keyCode)) event.consume()
        }
    }

    private val watch: Job = scope.launch {
        model.state.collect { next ->
            val before = shown
            shown = next
            model.barShare = layout().barShare
            if (before.visible || next.visible) repaint()
        }
    }

    init {
        view.addMouseListener(mouse)
        view.addMouseMotionListener(mouse)
        view.addKeyListener(keys)
        view.isFocusable = true
    }

    /** Takes the listeners off the view and stops the model. */
    fun close() {
        watch.cancel()
        view.removeMouseListener(mouse)
        view.removeMouseMotionListener(mouse)
        view.removeKeyListener(keys)
        popup?.let(view::remove)
        popup = null
        model.close()
    }

    fun layout(): AwtControlsLayout = AwtControlsLayout.of(view.width, view.height, shown)

    /** What a screen reader calls [control]. */
    fun name(control: AwtControl): String {
        val words = model.strings
        return when (control) {
            AwtControl.Previous -> words.previous
            AwtControl.Play -> if (shown.showsPlay) words.play else words.pause
            AwtControl.Next -> words.next
            AwtControl.Mute -> if (shown.muted) words.unmute else words.mute
            AwtControl.Volume -> words.volume
            AwtControl.Seek -> words.seekBar
            AwtControl.Audio -> words.audio
            AwtControl.Subtitles -> words.subtitles
            AwtControl.Quality -> words.quality
            AwtControl.Speed -> words.speed
            AwtControl.PictureInPicture -> words.pictureInPicture
            AwtControl.FullScreen -> words.fullScreen
        }
    }

    /** What a screen reader says [control] stands at, for the two bars. */
    fun valueText(control: AwtControl): String? = when (control) {
        AwtControl.Seek -> shown.seekBarValueText
        AwtControl.Volume -> shown.volumeText
        else -> null
    }

    /** The bar's value from 0 to 1, for the two bars. */
    fun value(control: AwtControl): Float? = when (control) {
        AwtControl.Seek -> shown.fraction
        AwtControl.Volume -> shown.volumeLevel
        else -> null
    }

    fun enabled(control: AwtControl): Boolean = enabled(control, shown)

    /** The parts a screen reader can reach now, in reading order: none while the controls are hidden. */
    fun reachable(): List<AwtControl> {
        if (!shown.visible) return emptyList()
        return layout().parts.entries.sortedWith(compareBy({ it.value.y }, { it.value.x })).map { it.key }
    }

    /** Does what a press and a release on [control] do. Also what a screen reader's action does. */
    fun activate(control: AwtControl) {
        if (!enabled(control)) return
        val at = layout().parts[control]
        when (control) {
            AwtControl.Previous -> model.previous()
            AwtControl.Play -> model.togglePlay()
            AwtControl.Next -> model.next()
            AwtControl.Mute -> model.toggleMute()
            AwtControl.Audio -> open(PlayerControlsMenu.Audio, at)
            AwtControl.Subtitles -> open(PlayerControlsMenu.Subtitles, at)
            AwtControl.Quality -> open(PlayerControlsMenu.Quality, at)
            AwtControl.Speed -> open(PlayerControlsMenu.Speed, at)
            AwtControl.PictureInPicture -> model.enterPictureInPicture()
            AwtControl.FullScreen -> model.toggleFullScreen()
            AwtControl.Seek, AwtControl.Volume -> Unit
        }
    }

    /** Sets a bar to [fraction] of its length, as a screen reader's value change does. */
    fun setValue(control: AwtControl, fraction: Float) {
        when (control) {
            AwtControl.Seek -> {
                model.beginScrub(fraction)
                model.endScrub()
            }
            AwtControl.Volume -> model.setVolumeLevel(fraction)
            else -> Unit
        }
    }

    private fun open(menu: PlayerControlsMenu, at: Rectangle?) {
        model.poke()
        openMenu(menu, at?.x ?: 0, at?.y ?: 0)
    }

    private fun showMenu(menu: PlayerControlsMenu, x: Int, y: Int) {
        if (GraphicsEnvironment.isHeadless()) return
        popup?.let(view::remove)
        val choices = PopupMenu(name(AwtControl.valueOf(menu.name)))
        shown.options(menu).forEachIndexed { index, option ->
            choices.add(CheckboxMenuItem(option.label, option.selected).apply { addItemListener { model.select(menu, index) } })
        }
        popup = choices
        view.add(choices)
        choices.show(view, x, y)
    }

    fun press(x: Int, y: Int) {
        view.requestFocusInWindow()
        val layout = layout()
        val hit = if (shown.visible) layout.hit(x, y) else null
        pressed = hit
        pressedOnPicture = hit == null && (!shown.visible || y < layout.barTop)
        when (hit) {
            AwtControl.Seek -> model.beginScrub(layout.fractionAt(AwtControl.Seek, x))
            AwtControl.Volume -> model.setVolumeLevel(layout.fractionAt(AwtControl.Volume, x))
            null -> if (!pressedOnPicture) model.poke()
            else -> model.poke()
        }
    }

    fun drag(x: Int) {
        when (pressed) {
            AwtControl.Seek -> model.moveScrub(layout().fractionAt(AwtControl.Seek, x))
            AwtControl.Volume -> model.setVolumeLevel(layout().fractionAt(AwtControl.Volume, x))
            else -> Unit
        }
    }

    fun release(x: Int, y: Int) {
        val was = pressed
        pressed = null
        when {
            was == AwtControl.Seek -> model.endScrub()
            was == AwtControl.Volume -> model.poke()
            was != null -> if (layout().hit(x, y) == was) activate(was)
            // A click on the picture shows or hides the controls, as a tap does on a phone.
            pressedOnPicture -> model.toggleVisible()
        }
        pressedOnPicture = false
    }

    /** Handles one key press, and answers true when the key was used. */
    fun key(code: Int): Boolean {
        // The first arrow or Enter while the controls are hidden only shows them.
        if (code in WakeKeys && model.wake()) return true
        when (code) {
            KeyEvent.VK_SPACE -> model.togglePlay()
            KeyEvent.VK_LEFT -> model.stepBy(-PlayerControlsModel.SeekStep)
            KeyEvent.VK_RIGHT -> model.stepBy(PlayerControlsModel.SeekStep)
            KeyEvent.VK_UP -> model.setVolumeLevel(shown.volumeLevel + PlayerControlsModel.VOLUME_STEP)
            KeyEvent.VK_DOWN -> model.setVolumeLevel(shown.volumeLevel - PlayerControlsModel.VOLUME_STEP)
            KeyEvent.VK_M -> model.toggleMute()
            KeyEvent.VK_F -> if (shown.canFullScreen) model.toggleFullScreen() else return false
            else -> return false
        }
        return true
    }

    override fun paint(graphics: Graphics2D, width: Int, height: Int) {
        val now = shown
        if (!paintsHere || !now.visible || width <= 0 || height <= 0) return
        val layout = AwtControlsLayout.of(width, height, now)
        val g = graphics
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Scrim
        g.fillRect(0, layout.barTop, width, height - layout.barTop)

        for ((control, at) in layout.parts) {
            when (control) {
                AwtControl.Seek -> paintBar(g, at, now.fraction, now.buffered, Content, thumb = if (now.scrubTarget != null) 1f else 0.75f)
                AwtControl.Volume -> paintBar(g, at, now.volumeLevel, emptyList(), if (now.muted) Disabled else Content, thumb = 0.75f)
                else -> paintIcon(g, iconOf(control, now), at, if (enabled(control, now)) Content else Disabled)
            }
        }

        g.font = TextFont
        g.color = Content
        val times = now.durationText?.let { "${now.positionText} / $it" } ?: now.positionText
        val menusStart = layout.parts.filterKeys { it.ordinal >= AwtControl.Audio.ordinal }.values.minOfOrNull { it.x } ?: width
        if (layout.timeX + g.fontMetrics.stringWidth(times) <= menusStart) g.drawString(times, layout.timeX, layout.timeBaseline)

        val target = now.scrubText
        val bar = layout.parts[AwtControl.Seek]
        if (target != null && bar != null) paintScrubTarget(g, target, bar, now.fraction, width)
    }

    private fun enabled(control: AwtControl, now: PlayerControlsSnapshot): Boolean = when (control) {
        AwtControl.Previous -> now.canGoPrevious
        AwtControl.Next -> now.canGoNext
        else -> true
    }

    private fun paintBar(
        g: Graphics2D,
        at: Rectangle,
        fraction: Float,
        buffered: List<ClosedFloatingPointRange<Float>>,
        fill: Color,
        thumb: Float,
    ) {
        val radius = AwtControlsLayout.THUMB_RADIUS.toFloat()
        val y = at.y + at.height / 2f
        val start = at.x + radius
        val span = at.width - 2 * radius
        if (span <= 0f) return
        g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Track
        g.draw(Line2D.Float(start, y, start + span, y))
        g.color = Buffered
        buffered.forEach { g.draw(Line2D.Float(start + it.start * span, y, start + it.endInclusive * span, y)) }
        g.color = fill
        val x = start + fraction.coerceIn(0f, 1f) * span
        g.draw(Line2D.Float(start, y, x, y))
        val r = radius * thumb
        g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
    }

    private fun paintIcon(g: Graphics2D, icon: List<IconPath>, at: Rectangle, color: Color) {
        val size = AwtControlsLayout.ICON
        val drawn = g.create() as Graphics2D
        try {
            drawn.translate(at.x + (at.width - size) / 2.0, at.y + (at.height - size) / 2.0)
            drawn.scale(size / ControlIconShapes.GRID.toDouble(), size / ControlIconShapes.GRID.toDouble())
            drawn.color = color
            drawn.stroke = BasicStroke(ControlIconShapes.STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (path in icon) {
                val outline = path.toPath2D()
                if (path.stroked) drawn.draw(outline) else drawn.fill(outline)
            }
        } finally {
            drawn.dispose()
        }
    }

    /** The scrub's target time in a small box above the thumb, kept inside the canvas. */
    private fun paintScrubTarget(g: Graphics2D, text: String, bar: Rectangle, fraction: Float, width: Int) {
        val metrics = g.fontMetrics
        val boxWidth = metrics.stringWidth(text) + 16
        val boxHeight = metrics.height + 8
        val radius = AwtControlsLayout.THUMB_RADIUS
        val centre = bar.x + radius + fraction * (bar.width - 2 * radius)
        val x = (centre - boxWidth / 2f).toInt().coerceIn(0, (width - boxWidth).coerceAtLeast(0))
        val y = bar.y - boxHeight - 4
        g.color = MenuColor
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), boxWidth.toFloat(), boxHeight.toFloat(), 8f, 8f))
        g.color = Content
        g.drawString(text, x + 8, y + 4 + metrics.ascent)
    }

    private companion object {
        val Content: Color = Color.WHITE
        val Disabled = Color(255, 255, 255, 97)
        val Buffered = Color(255, 255, 255, 128)
        val Track = Color(255, 255, 255, 61)
        val Scrim = Color(0, 0, 0, 140)
        val MenuColor = Color(0x20, 0x21, 0x24, 0xF0)
        val TextFont = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        val WakeKeys = setOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER)

        fun iconOf(control: AwtControl, now: PlayerControlsSnapshot): List<IconPath> = when (control) {
            AwtControl.Previous -> ControlIconShapes.Previous
            AwtControl.Play -> if (now.showsPlay) ControlIconShapes.Play else ControlIconShapes.Pause
            AwtControl.Next -> ControlIconShapes.Next
            AwtControl.Mute -> if (now.muted) ControlIconShapes.Muted else ControlIconShapes.Volume
            AwtControl.Audio -> ControlIconShapes.Audio
            AwtControl.Subtitles -> ControlIconShapes.Subtitles
            AwtControl.Quality -> ControlIconShapes.Quality
            AwtControl.Speed -> ControlIconShapes.Speed
            AwtControl.PictureInPicture -> ControlIconShapes.PictureInPicture
            AwtControl.FullScreen -> ControlIconShapes.FullScreen
            AwtControl.Seek, AwtControl.Volume -> emptyList()
        }
    }
}

/**
 * One part of the controls as a screen reader meets it: a button it can press, or a bar whose value
 * it can read and set. The parts are painted, not components, so the view lists these as its
 * accessible children.
 */
internal class AccessibleControl(
    private val controls: AwtPlayerControls,
    private val view: Component,
    val control: AwtControl,
) : AccessibleContext(), Accessible, AccessibleAction, AccessibleValue, AccessibleComponent {

    private val isBar: Boolean get() = control == AwtControl.Seek || control == AwtControl.Volume
    private fun place(): Rectangle = controls.layout().parts[control] ?: Rectangle()

    override fun getAccessibleContext(): AccessibleContext = this
    override fun getAccessibleName(): String = controls.name(control)
    override fun getAccessibleDescription(): String? = controls.valueText(control)
    override fun getAccessibleRole(): AccessibleRole = if (isBar) AccessibleRole.SLIDER else AccessibleRole.PUSH_BUTTON
    override fun getAccessibleIndexInParent(): Int = controls.reachable().indexOf(control)
    override fun getAccessibleChildrenCount(): Int = 0
    override fun getAccessibleChild(i: Int): Accessible? = null
    override fun getLocale(): Locale = view.locale
    override fun getAccessibleAction(): AccessibleAction? = if (isBar) null else this
    override fun getAccessibleValue(): AccessibleValue? = if (isBar) this else null
    override fun getAccessibleComponent(): AccessibleComponent = this

    override fun getAccessibleStateSet(): AccessibleStateSet = AccessibleStateSet().apply {
        if (controls.enabled(control)) add(AccessibleState.ENABLED)
        if (control in controls.reachable()) {
            add(AccessibleState.VISIBLE)
            if (view.isShowing) add(AccessibleState.SHOWING)
        }
    }

    override fun getAccessibleActionCount(): Int = 1
    override fun getAccessibleActionDescription(i: Int): String? = if (i == 0) AccessibleAction.CLICK else null
    override fun doAccessibleAction(i: Int): Boolean {
        if (i != 0 || !controls.enabled(control)) return false
        controls.activate(control)
        return true
    }

    // A bar reads and takes whole percent, because a screen reader steps in whole numbers.
    override fun getCurrentAccessibleValue(): Number = ((controls.value(control) ?: 0f) * 100).roundToInt()
    override fun getMinimumAccessibleValue(): Number = 0
    override fun getMaximumAccessibleValue(): Number = 100
    override fun setCurrentAccessibleValue(n: Number?): Boolean {
        if (n == null || !isBar) return false
        controls.setValue(control, (n.toFloat() / 100f).coerceIn(0f, 1f))
        return true
    }

    override fun getBounds(): Rectangle = place()
    override fun getLocation(): Point = place().location
    override fun getSize(): Dimension = place().size
    override fun contains(p: Point): Boolean = Rectangle(place().size).contains(p)
    override fun getLocationOnScreen(): Point? {
        if (!view.isShowing) return null
        val origin = view.locationOnScreen
        val at = place()
        return Point(origin.x + at.x, origin.y + at.y)
    }
    override fun isEnabled(): Boolean = controls.enabled(control)
    override fun isVisible(): Boolean = control in controls.reachable()
    override fun isShowing(): Boolean = isVisible && view.isShowing
    override fun isFocusTraversable(): Boolean = false
    override fun getAccessibleAt(p: Point): Accessible? = null
    override fun getBackground(): Color? = view.background
    override fun getForeground(): Color? = view.foreground
    override fun getCursor(): Cursor? = view.cursor
    override fun getFont(): Font? = view.font
    override fun getFontMetrics(f: Font): FontMetrics? = view.getFontMetrics(f)

    // The parts are laid out and painted by the controls, so nothing here can be set from outside.
    override fun setBackground(c: Color?) = Unit
    override fun setForeground(c: Color?) = Unit
    override fun setCursor(cursor: Cursor?) = Unit
    override fun setFont(f: Font?) = Unit
    override fun setEnabled(b: Boolean) = Unit
    override fun setVisible(b: Boolean) = Unit
    override fun setLocation(p: Point?) = Unit
    override fun setBounds(r: Rectangle?) = Unit
    override fun setSize(d: Dimension?) = Unit
    override fun requestFocus() = Unit
    override fun addFocusListener(l: FocusListener?) = Unit
    override fun removeFocusListener(l: FocusListener?) = Unit
}

/** One outline of an icon as a Java2D path, on the icon's own 24 unit grid. */
internal fun IconPath.toPath2D(): Path2D.Float {
    val path = Path2D.Float(if (evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
    for (step in steps) {
        when (step) {
            is IconStep.Move -> path.moveTo(step.x, step.y)
            is IconStep.Line -> path.lineTo(step.x, step.y)
            IconStep.Close -> path.closePath()
            // Java2D turns anticlockwise for a positive angle, the icons clockwise.
            is IconStep.Arc -> path.append(
                Arc2D.Float(step.cx - step.radius, step.cy - step.radius, 2 * step.radius, 2 * step.radius, -step.start, -step.sweep, Arc2D.OPEN),
                false,
            )
        }
    }
    return path
}

/** The AWT event thread as a coroutine dispatcher, for the controls' watch of the player. */
internal object AwtEventThread : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        EventQueue.invokeLater(block)
    }
}
