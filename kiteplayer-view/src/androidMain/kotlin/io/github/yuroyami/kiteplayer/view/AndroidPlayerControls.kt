package io.github.yuroyami.kiteplayer.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** What the controls do with a key before any button sees it. */
internal enum class ControlsKeyAction { TogglePlay, Wake, Pass }

/**
 * Space and the media play-pause key play or pause from anywhere. While the controls are [hidden],
 * the first arrow, D-pad centre or Enter only shows them, as on a television.
 */
internal fun controlsKeyAction(keyCode: Int, hidden: Boolean): ControlsKeyAction = when (keyCode) {
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ControlsKeyAction.TogglePlay
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    -> if (hidden) ControlsKeyAction.Wake else ControlsKeyAction.Pass
    else -> ControlsKeyAction.Pass
}

/** How far a key moves the focused seek bar, or null for a key the bar does not take. */
internal fun seekBarKeyStep(keyCode: Int): Duration? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> -PlayerControlsModel.SeekStep
    KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerControlsModel.SeekStep
    else -> null
}

/** The fraction of a bar [width] wide that [x] stands for, between ends inset by [inset]. */
internal fun barFractionAt(x: Float, width: Int, inset: Float): Float {
    val span = width - 2 * inset
    if (span <= 0f) return 0f
    return ((x - inset) / span).coerceIn(0f, 1f)
}

/**
 * The default controls of a [KitePlayerView]: a transparent layer over the picture with a bar at
 * its bottom, made of plain platform views that draw [model]. A tap on the picture shows or hides
 * the bar, and a finger dragged sideways over the picture scrubs.
 */
@SuppressLint("ViewConstructor")
internal class ControlsOverlay(
    context: Context,
    val model: PlayerControlsModel,
    scope: CoroutineScope,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    private val previous = ControlIconButton(context).apply { setOnClickListener { model.previous() } }
    private val play = ControlIconButton(context).apply { setOnClickListener { model.togglePlay() } }
    private val next = ControlIconButton(context).apply { setOnClickListener { model.next() } }
    private val mute = ControlIconButton(context).apply { setOnClickListener { model.toggleMute() } }
    private val menuButtons = PlayerControlsMenu.entries.associateWith { menu ->
        ControlIconButton(context).apply {
            icon = when (menu) {
                PlayerControlsMenu.Audio -> ControlIconShapes.Audio
                PlayerControlsMenu.Subtitles -> ControlIconShapes.Subtitles
                PlayerControlsMenu.Quality -> ControlIconShapes.Quality
                PlayerControlsMenu.Speed -> ControlIconShapes.Speed
            }
            setOnClickListener { openMenu(menu, this) }
        }
    }
    private val pictureInPicture = ControlIconButton(context).apply {
        icon = ControlIconShapes.PictureInPicture
        setOnClickListener { model.enterPictureInPicture() }
    }
    private val fullScreen = ControlIconButton(context).apply {
        icon = ControlIconShapes.FullScreen
        setOnClickListener { model.toggleFullScreen() }
    }
    private val seekBar = ControlsSeekBar(context, model)
    private val time = whiteText()
    private val scrubTarget = whiteText().apply {
        setBackgroundColor(MENU_COLOR)
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(SCRIM_COLOR)
        setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        // The bar takes its own touches, so a tap between two buttons does not hide it.
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var shown: PlayerControlsSnapshot = model.state.value
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var scrubFrom = 0f
    private var scrubbing = false

    private val watch: Job

    init {
        // Play, previous and next stand for time, which does not turn round in a right-to-left layout.
        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            addView(previous, square())
            addView(play, square())
            addView(next, square())
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(transport, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                time,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) },
            )
            addView(mute, square())
            PlayerControlsMenu.entries.forEach { addView(menuButtons.getValue(it), square()) }
            addView(pictureInPicture, square())
            addView(fullScreen, square())
        }
        bar.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32f)))
        bar.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        addView(scrubTarget, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // The layer itself is the picture's button: it holds the focus while the bar is hidden.
        isClickable = true
        isFocusable = true
        setOnClickListener { model.toggleVisible() }
        render(shown)
        watch = scope.launch { model.state.collect(::render) }
    }

    /** Stops following the model, which the caller closes. */
    fun close() {
        watch.cancel()
    }

    /** Draws the model again, for new words that leave what the model shows as it was. */
    fun refresh() {
        render(model.state.value)
    }

    private fun whiteText(): TextView = TextView(context).apply {
        setTextColor(CONTENT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        // Times read left to right in every language.
        textDirection = TEXT_DIRECTION_LTR
    }

    private fun square(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))

    private fun render(now: PlayerControlsSnapshot) {
        val words = model.strings
        val wasVisible = shown.visible
        shown = now
        contentDescription = if (now.visible) words.hideControls else words.showControls
        if (!now.visible && wasVisible && bar.hasFocus()) requestFocus()
        bar.visibility = if (now.visible) VISIBLE else GONE

        previous.show(now.hasQueue, ControlIconShapes.Previous, words.previous, now.canGoPrevious)
        play.show(true, if (now.showsPlay) ControlIconShapes.Play else ControlIconShapes.Pause, if (now.showsPlay) words.play else words.pause)
        next.show(now.hasQueue, ControlIconShapes.Next, words.next, now.canGoNext)
        mute.show(true, if (now.muted) ControlIconShapes.Muted else ControlIconShapes.Volume, if (now.muted) words.unmute else words.mute)
        menuButtons.forEach { (menu, button) ->
            val label = when (menu) {
                PlayerControlsMenu.Audio -> words.audio
                PlayerControlsMenu.Subtitles -> words.subtitles
                PlayerControlsMenu.Quality -> words.quality
                PlayerControlsMenu.Speed -> words.speed
            }
            button.show(now.offers(menu), button.icon, label)
        }
        pictureInPicture.show(now.canPictureInPicture, pictureInPicture.icon, words.pictureInPicture)
        fullScreen.show(now.canFullScreen, fullScreen.icon, words.fullScreen)

        seekBar.visibility = if (now.seekable) VISIBLE else GONE
        seekBar.show(now, words.seekBar)
        val times = now.durationText?.let { "${now.positionText} / $it" } ?: now.positionText
        if (time.text.toString() != times) time.text = times
        val target = now.scrubText
        scrubTarget.visibility = if (target != null) VISIBLE else GONE
        if (target != null && scrubTarget.text.toString() != target) scrubTarget.text = target
    }

    private fun openMenu(menu: PlayerControlsMenu, anchor: View) {
        val popup = PopupMenu(context, anchor)
        shown.options(menu).forEachIndexed { index, option ->
            popup.menu.add(MENU_GROUP, index, index, option.label).apply {
                isCheckable = true
                isChecked = option.selected
            }
        }
        popup.menu.setGroupCheckable(MENU_GROUP, true, true)
        popup.setOnMenuItemClickListener { item ->
            model.select(menu, item.itemId)
            true
        }
        // An open menu keeps the controls up; the timeout starts again when it closes.
        popup.setOnDismissListener { model.hold(false) }
        model.hold(true)
        popup.show()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Subtitles stand above the bar while it shows.
        if (bar.visibility == VISIBLE && height > 0) model.barShare = bar.height.toFloat() / height
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && shown.visible) model.poke()
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                scrubFrom = shown.fraction
                scrubbing = false
            }
            MotionEvent.ACTION_MOVE -> {
                // A finger scrubs; a mouse drag does not, and a tap still shows or hides.
                val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                if (!scrubbing && finger && shown.seekable && width > 0 && abs(event.x - downX) > touchSlop) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    model.beginScrub(pictureFraction(event.x))
                } else if (scrubbing) {
                    model.moveScrub(pictureFraction(event.x))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (scrubbing) model.endScrub() else performClick()
                scrubbing = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) model.cancelScrub()
                scrubbing = false
            }
        }
        return true
    }

    private fun pictureFraction(x: Float): Float = model.pictureScrubFraction(scrubFrom, (x - downX) / width)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With the focus on the layer itself the bar is as far away as when it is hidden.
        when (controlsKeyAction(event.keyCode, hidden = !shown.visible || isFocused)) {
            ControlsKeyAction.TogglePlay -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) model.togglePlay()
                return true
            }
            ControlsKeyAction.Wake -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    model.wake()
                    render(model.state.value)
                    play.requestFocus()
                }
                return true
            }
            ControlsKeyAction.Pass -> if (event.action == KeyEvent.ACTION_DOWN) model.poke()
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val CONTENT_COLOR = 0xFFFFFFFF.toInt()
        const val SCRIM_COLOR = 0x8C000000.toInt()
        const val MENU_COLOR = 0xF0202124.toInt()
        const val MENU_GROUP = 1
    }
}

/**
 * One button of the controls: an icon drawn from its outline, never mirrored, with a ring while it
 * has the keyboard or D-pad focus. A screen reader meets it as a button by its content description.
 */
internal class ControlIconButton(context: Context) : View(context) {

    var icon: List<IconPath> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            paths = value.map { it.toAndroidPath() }
            invalidate()
        }

    private var paths: List<Path> = emptyList()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
    }

    /** Shows or leaves out the button, with [shape] as its icon and [label] as its name. */
    fun show(visible: Boolean, shape: List<IconPath>, label: String, enabled: Boolean = true) {
        visibility = if (visible) VISIBLE else GONE
        icon = shape
        if (contentDescription != label) contentDescription = label
        if (isEnabled != enabled) {
            isEnabled = enabled
            invalidate()
        }
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val side = ICON_DP * density
        val scale = side / ControlIconShapes.GRID
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            paint.color = FOCUS_COLOR
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f - 2 * density, paint)
        }
        paint.color = if (!isEnabled) DISABLED_COLOR else if (isPressed) PRESSED_COLOR else CONTENT_COLOR
        val saved = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(scale, scale)
        icon.forEachIndexed { index, outline ->
            paint.style = if (outline.stroked) Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth = ControlIconShapes.STROKE
            canvas.drawPath(paths[index], paint)
        }
        canvas.restoreToCount(saved)
    }

    private companion object {
        const val ICON_DP = 24f
        const val CONTENT_COLOR = 0xFFFFFFFF.toInt()
        const val PRESSED_COLOR = 0xB3FFFFFF.toInt()
        const val DISABLED_COLOR = 0x61FFFFFF
        const val FOCUS_COLOR = 0xFF8AB4F8.toInt()
    }
}

/** One outline of an icon as an Android path, on the icon's own 24 unit grid. */
internal fun IconPath.toAndroidPath(): Path {
    val path = Path()
    path.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
    for (step in steps) {
        when (step) {
            is IconStep.Move -> path.moveTo(step.x, step.y)
            is IconStep.Line -> path.lineTo(step.x, step.y)
            IconStep.Close -> path.close()
            is IconStep.Arc -> {
                val box = RectF(step.cx - step.radius, step.cy - step.radius, step.cx + step.radius, step.cy + step.radius)
                // A sweep of 360 draws nothing as an arc, so a whole circle is added as an oval.
                if (step.sweep >= 360f) path.addOval(box, Path.Direction.CW) else path.addArc(box, step.start, step.sweep)
            }
        }
    }
    return path
}

/**
 * The seek bar of the controls: the played part, the buffered ranges and a thumb, drawn left to
 * right in every layout direction. A press seeks and a drag scrubs. With the focus, the left and
 * right keys move ten seconds. A screen reader meets it as a seek bar with a range in seconds.
 */
@SuppressLint("ViewConstructor")
internal class ControlsSeekBar(context: Context, private val model: PlayerControlsModel) : View(context) {

    private var shown: PlayerControlsSnapshot = model.state.value
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumbRadius = 7 * density
    private var pressedDown = false

    init {
        isFocusable = true
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    /** Draws [now], and tells a screen reader its name and where it stands. */
    fun show(now: PlayerControlsSnapshot, label: String) {
        val before = shown
        shown = now
        // stateDescription is API 30. Below it the name carries the value too.
        if (Build.VERSION.SDK_INT >= 30) {
            if (contentDescription != label) contentDescription = label
            if (stateDescription != now.seekBarValueText) stateDescription = now.seekBarValueText
        } else {
            val spoken = "$label, ${now.seekBarValueText}"
            if (contentDescription != spoken) contentDescription = spoken
        }
        if (before.fraction != now.fraction || before.buffered != now.buffered || before.scrubTarget != now.scrubTarget) invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val total = shown.duration?.toDouble(DurationUnit.SECONDS)?.toFloat() ?: return
        val at = (shown.scrubTarget ?: shown.position).inWholeSeconds.toFloat().coerceIn(0f, total)
        @Suppress("DEPRECATION")
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, total, at)
        if (!shown.seekable) return
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> model.stepBy(PlayerControlsModel.SeekStep)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> model.stepBy(-PlayerControlsModel.SeekStep)
            android.R.id.accessibilityActionSetProgress -> {
                val total = shown.duration?.toDouble(DurationUnit.SECONDS)?.toFloat()
                val wanted = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN) ?: Float.NaN
                if (total == null || total <= 0f || wanted.isNaN() || !shown.seekable) return false
                model.beginScrub(wanted / total)
                model.endScrub()
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = seekBarKeyStep(keyCode) ?: return super.onKeyDown(keyCode, event)
        model.stepBy(step)
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!shown.seekable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedDown = true
                parent?.requestDisallowInterceptTouchEvent(true)
                model.beginScrub(barFractionAt(event.x, width, thumbRadius))
            }
            MotionEvent.ACTION_MOVE -> if (pressedDown) model.moveScrub(barFractionAt(event.x, width, thumbRadius))
            MotionEvent.ACTION_UP -> {
                if (pressedDown) model.endScrub()
                pressedDown = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressedDown) model.cancelScrub()
                pressedDown = false
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val start = thumbRadius
        val span = width - 2 * thumbRadius
        if (span <= 0f) return
        fun at(part: Float) = start + part.coerceIn(0f, 1f) * span
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            paint.color = FOCUS_COLOR
            val ring = thumbRadius + 3 * density
            canvas.drawRoundRect(density, y - ring, width - density, y + ring, ring, ring, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4 * density
        paint.color = TRACK_COLOR
        canvas.drawLine(at(0f), y, at(1f), y, paint)
        paint.color = BUFFERED_COLOR
        shown.buffered.forEach { canvas.drawLine(at(it.start), y, at(it.endInclusive), y, paint) }
        paint.color = CONTENT_COLOR
        canvas.drawLine(at(0f), y, at(shown.fraction), y, paint)
        paint.style = Paint.Style.FILL
        val grown = shown.scrubTarget != null || isFocused
        canvas.drawCircle(at(shown.fraction), y, if (grown) thumbRadius else thumbRadius * 0.75f, paint)
    }

    private companion object {
        const val CONTENT_COLOR = 0xFFFFFFFF.toInt()
        const val BUFFERED_COLOR = 0x80FFFFFF.toInt()
        const val TRACK_COLOR = 0x3DFFFFFF
        const val FOCUS_COLOR = 0xFF8AB4F8.toInt()
    }
}
