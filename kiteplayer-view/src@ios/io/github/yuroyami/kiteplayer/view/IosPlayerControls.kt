@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.view

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGLineJoin
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAction
import platform.UIKit.UIBezierPath
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchCancel
import platform.UIKit.UIControlEventTouchDown
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlEventTouchUpOutside
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateCancelled
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImage
import platform.UIKit.UIImageRenderingMode
import platform.UIKit.UILabel
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementState
import platform.UIKit.UIPanGestureRecognizer
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISlider
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.setAccessibilityHint
import platform.UIKit.setAccessibilityLabel
import platform.UIKit.setAccessibilityTraits
import platform.UIKit.setAccessibilityValue
import platform.UIKit.setIsAccessibilityElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The default controls of a [KitePlayerUIView]: a layer over the picture with a bar at its bottom,
 * made of UIKit buttons, a slider and menus that show [model]. A tap on the picture shows or hides
 * the bar, and a finger dragged sideways over the picture scrubs.
 */
internal class ControlsOverlayView(
    val model: PlayerControlsModel,
    scope: CoroutineScope,
) : UIView(frame = CGRectZero.readValue()) {

    /** The part over the picture. It takes the taps and the drags, and VoiceOver meets it as the video. */
    val picture = UIView(frame = CGRectZero.readValue())
    val bar = UIView(frame = CGRectZero.readValue())
    val previousButton = iconButton(ControlIconShapes.Previous) { model.previous() }
    val playButton = iconButton(ControlIconShapes.Play) { model.togglePlay() }
    val nextButton = iconButton(ControlIconShapes.Next) { model.next() }
    val muteButton = iconButton(ControlIconShapes.Volume) { model.toggleMute() }
    val pictureInPictureButton = iconButton(ControlIconShapes.PictureInPicture) { model.enterPictureInPicture() }
    val fullScreenButton = iconButton(ControlIconShapes.FullScreen) { model.toggleFullScreen() }
    val menuButtons: Map<PlayerControlsMenu, UIButton> = PlayerControlsMenu.entries.associateWith { menu ->
        val shape = when (menu) {
            PlayerControlsMenu.Audio -> ControlIconShapes.Audio
            PlayerControlsMenu.Subtitles -> ControlIconShapes.Subtitles
            PlayerControlsMenu.Quality -> ControlIconShapes.Quality
            PlayerControlsMenu.Speed -> ControlIconShapes.Speed
        }
        // The press only starts the timeout again: UIKit does not say when its menu opens or closes.
        iconButton(shape) { model.poke() }.apply { showsMenuAsPrimaryAction = true }
    }

    /**
     * The seek bar, made when this view first enters a window. A slider made in a process with no
     * application object stops that process at its next screen update, and a unit test is one.
     */
    var seekBar: UISlider? = null
        private set
    val timeLabel = whiteLabel()
    val scrubLabel = whiteLabel()

    private val trackLayer = CALayer()
    private val bufferedLayers = mutableListOf<CALayer>()
    private var shown: PlayerControlsSnapshot = model.state.value
    private var menusShown: Map<PlayerControlsMenu, List<PlayerControlsOption>> = emptyMap()
    private var playShowsPlay: Boolean? = null
    private var muteShowsMuted: Boolean? = null
    private var panFrom = 0f
    private var panScrubs = false
    private var seekLeft = 0.0
    private var seekWidth = 0.0
    private val watch: Job

    init {
        addSubview(picture)
        addSubview(bar)
        addSubview(scrubLabel)
        bar.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.55)
        bar.layer.addSublayer(trackLayer)
        trackLayer.backgroundColor = UIColor.whiteColor.colorWithAlphaComponent(0.24).CGColor
        trackLayer.cornerRadius = TRACK_HEIGHT / 2
        listOf(previousButton, playButton, nextButton, muteButton, pictureInPictureButton, fullScreenButton).forEach(bar::addSubview)
        menuButtons.values.forEach(bar::addSubview)
        bar.addSubview(timeLabel)

        scrubLabel.backgroundColor = UIColor.colorWithRed(0.125, green = 0.129, blue = 0.141, alpha = 0.94)
        scrubLabel.textAlignment = NSTextAlignmentCenter
        scrubLabel.layer.cornerRadius = 8.0
        scrubLabel.clipsToBounds = true
        scrubLabel.hidden = true
        scrubLabel.setIsAccessibilityElement(false)

        picture.setIsAccessibilityElement(true)
        picture.setAccessibilityTraits(UIAccessibilityTraitButton)
        picture.addGestureRecognizer(UITapGestureRecognizer(target = this, action = NSSelectorFromString("pictureTapped")))
        picture.addGestureRecognizer(UIPanGestureRecognizer(target = this, action = NSSelectorFromString("picturePanned:")))

        render(shown)
        watch = scope.launch { model.state.collect(::render) }
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window != null && seekBar == null) installSeekBar()
    }

    private fun installSeekBar() {
        val slider = UISlider(frame = CGRectZero.readValue())
        slider.minimumTrackTintColor = UIColor.whiteColor
        // The track and the buffered ranges are drawn behind the slider, so its own track is clear.
        slider.maximumTrackTintColor = UIColor.clearColor
        // The bar stands for time, which runs left to right in every language.
        slider.semanticContentAttribute = UISemanticContentAttributeForceLeftToRight
        slider.addAction(UIAction.actionWithHandler { _ -> seekTouched(slider.value) }, forControlEvents = UIControlEventTouchDown)
        slider.addAction(UIAction.actionWithHandler { _ -> seekMoved(slider.value, slider.tracking) }, forControlEvents = UIControlEventValueChanged)
        slider.addAction(
            UIAction.actionWithHandler { _ -> seekReleased() },
            forControlEvents = UIControlEventTouchUpInside or UIControlEventTouchUpOutside,
        )
        slider.addAction(UIAction.actionWithHandler { _ -> model.cancelScrub() }, forControlEvents = UIControlEventTouchCancel)
        bar.addSubview(slider)
        seekBar = slider
        render(shown)
        setNeedsLayout()
    }

    /** Stops following the model, which the caller closes. */
    fun close() {
        watch.cancel()
    }

    /** Draws the model again, for new words that leave what the model shows as it was. */
    fun refresh() {
        render(model.state.value)
    }

    private fun iconButton(shape: List<IconPath>, onPress: () -> Unit): UIButton {
        val button = UIButton(frame = CGRectZero.readValue())
        button.setImage(iconImage(shape), forState = UIControlStateNormal)
        button.tintColor = UIColor.whiteColor
        button.addAction(UIAction.actionWithHandler { _ -> onPress() }, forControlEvents = UIControlEventTouchUpInside)
        return button
    }

    private fun whiteLabel(): UILabel = UILabel(frame = CGRectZero.readValue()).apply {
        textColor = UIColor.whiteColor
        font = UIFont.monospacedDigitSystemFontOfSize(14.0, weight = 0.0)
    }

    /** A finger came down on the slider at [value]. */
    internal fun seekTouched(value: Float) = model.beginScrub(value)

    /** A slider value that changed under a finger moves the scrub; one VoiceOver changed is a whole seek. */
    internal fun seekMoved(value: Float, tracking: Boolean) {
        if (tracking) {
            model.moveScrub(value)
        } else {
            model.beginScrub(value)
            model.endScrub()
        }
    }

    /** The finger left the slider. */
    internal fun seekReleased() = model.endScrub()

    /** Whether the bar has a seek bar now. A live item has none. */
    internal val showsSeekBar: Boolean get() = !trackLayer.hidden

    @ObjCAction
    fun pictureTapped() {
        model.toggleVisible()
    }

    @ObjCAction
    fun picturePanned(recognizer: UIPanGestureRecognizer) {
        val width = picture.bounds.useContents { size.width }
        val moved = recognizer.translationInView(picture)
        val dx = moved.useContents { x }
        val dy = moved.useContents { y }
        when (recognizer.state) {
            UIGestureRecognizerStateBegan -> {
                // Only a sideways drag scrubs; an upward one is left to the application.
                panScrubs = shown.seekable && width > 0.0 && abs(dx) >= abs(dy)
                panFrom = shown.fraction
                if (panScrubs) model.beginScrub(model.pictureScrubFraction(panFrom, (dx / width).toFloat()))
            }
            UIGestureRecognizerStateChanged -> if (panScrubs) model.moveScrub(model.pictureScrubFraction(panFrom, (dx / width).toFloat()))
            UIGestureRecognizerStateEnded -> {
                if (panScrubs) model.endScrub()
                panScrubs = false
            }
            UIGestureRecognizerStateCancelled -> {
                if (panScrubs) model.cancelScrub()
                panScrubs = false
            }
            else -> Unit
        }
    }

    private fun render(now: PlayerControlsSnapshot) {
        val words = model.strings
        val layoutChanged = now.visible != shown.visible || now.seekable != shown.seekable || now.hasQueue != shown.hasQueue ||
            now.canFullScreen != shown.canFullScreen || now.canPictureInPicture != shown.canPictureInPicture ||
            PlayerControlsMenu.entries.any { now.offers(it) != shown.offers(it) }
        shown = now
        bar.hidden = !now.visible
        picture.setAccessibilityHint(if (now.visible) words.hideControls else words.showControls)

        if (playShowsPlay != now.showsPlay) {
            playShowsPlay = now.showsPlay
            playButton.setImage(iconImage(if (now.showsPlay) ControlIconShapes.Play else ControlIconShapes.Pause), forState = UIControlStateNormal)
        }
        playButton.setAccessibilityLabel(if (now.showsPlay) words.play else words.pause)
        if (muteShowsMuted != now.muted) {
            muteShowsMuted = now.muted
            muteButton.setImage(iconImage(if (now.muted) ControlIconShapes.Muted else ControlIconShapes.Volume), forState = UIControlStateNormal)
        }
        muteButton.setAccessibilityLabel(if (now.muted) words.unmute else words.mute)
        previousButton.hidden = !now.hasQueue
        previousButton.enabled = now.canGoPrevious
        previousButton.setAccessibilityLabel(words.previous)
        nextButton.hidden = !now.hasQueue
        nextButton.enabled = now.canGoNext
        nextButton.setAccessibilityLabel(words.next)
        pictureInPictureButton.hidden = !now.canPictureInPicture
        pictureInPictureButton.setAccessibilityLabel(words.pictureInPicture)
        fullScreenButton.hidden = !now.canFullScreen
        fullScreenButton.setAccessibilityLabel(words.fullScreen)

        menuButtons.forEach { (menu, button) ->
            val title = when (menu) {
                PlayerControlsMenu.Audio -> words.audio
                PlayerControlsMenu.Subtitles -> words.subtitles
                PlayerControlsMenu.Quality -> words.quality
                PlayerControlsMenu.Speed -> words.speed
            }
            button.hidden = !now.offers(menu)
            button.setAccessibilityLabel(title)
            val options = now.options(menu)
            // A menu is rebuilt only when its choices change, so an open one is not replaced on every tick.
            if (menusShown[menu] != options) button.menu = menuOf(menu, title, options)
        }
        menusShown = PlayerControlsMenu.entries.associateWith { now.options(it) }

        trackLayer.hidden = !now.seekable
        seekBar?.let { slider ->
            slider.hidden = !now.seekable
            if (!slider.tracking) slider.setValue(now.fraction, animated = false)
            slider.setAccessibilityLabel(words.seekBar)
            slider.setAccessibilityValue(now.seekBarValueText)
        }
        timeLabel.text = now.durationText?.let { "${now.positionText} / $it" } ?: now.positionText
        scrubLabel.hidden = now.scrubText == null
        now.scrubText?.let { scrubLabel.text = it }
        layoutBuffered()
        if (layoutChanged) setNeedsLayout()
    }

    private fun menuOf(menu: PlayerControlsMenu, title: String, options: List<PlayerControlsOption>): UIMenu {
        val actions = options.mapIndexed { index, option ->
            UIAction.actionWithTitle(option.label, image = null, identifier = null) { _ -> model.select(menu, index) }.apply {
                state = if (option.selected) UIMenuElementState.UIMenuElementStateOn else UIMenuElementState.UIMenuElementStateOff
            }
        }
        return UIMenu.menuWithTitle(title, children = actions)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        val safeBottom = safeAreaInsets.useContents { bottom }
        val safeLeft = safeAreaInsets.useContents { left }
        val safeRight = safeAreaInsets.useContents { right }
        val seekHeight = if (shown.seekable) SEEK_HEIGHT else 0.0
        val barHeight = MARGIN + seekHeight + BUTTON + safeBottom
        val barTop = (height - barHeight).coerceAtLeast(0.0)
        picture.setFrame(CGRectMake(0.0, 0.0, width, if (shown.visible) barTop else height))
        bar.setFrame(CGRectMake(0.0, barTop, width, barHeight))
        seekLeft = safeLeft + MARGIN
        seekWidth = (width - safeLeft - safeRight - 2 * MARGIN).coerceAtLeast(0.0)
        seekBar?.setFrame(CGRectMake(seekLeft, MARGIN / 2, seekWidth, SEEK_HEIGHT))

        val rowTop = MARGIN / 2 + seekHeight
        // Play, previous and next keep this order in a right-to-left layout too: they stand for time.
        var left = safeLeft + MARGIN
        listOf(previousButton, playButton, nextButton).filterNot { it.hidden }.forEach { button ->
            button.setFrame(CGRectMake(left, rowTop, BUTTON, BUTTON))
            left += BUTTON
        }
        var right = width - safeRight - MARGIN
        val trailing = listOf(fullScreenButton, pictureInPictureButton) +
            listOf(PlayerControlsMenu.Speed, PlayerControlsMenu.Quality, PlayerControlsMenu.Subtitles, PlayerControlsMenu.Audio).map(menuButtons::getValue) +
            muteButton
        trailing.filterNot { it.hidden }.forEach { button ->
            right -= BUTTON
            button.setFrame(CGRectMake(right, rowTop, BUTTON, BUTTON))
        }
        timeLabel.setFrame(CGRectMake(left + MARGIN, rowTop, (right - left - 2 * MARGIN).coerceAtLeast(0.0), BUTTON))
        scrubLabel.setFrame(CGRectMake((width - SCRUB_LABEL_WIDTH) / 2, (barTop - SCRUB_LABEL_HEIGHT) / 2, SCRUB_LABEL_WIDTH, SCRUB_LABEL_HEIGHT))
        layoutBuffered()
        // Subtitles stand above the bar while it shows.
        if (height > 0.0) model.barShare = (barHeight / height).toFloat().coerceIn(0f, 1f)
    }

    /** Places the track and one layer for each buffered range behind the slider's own track. */
    private fun layoutBuffered() {
        // The slider says where its own track runs. Before there is one the track fills the seek bar.
        val track = seekBar?.let { it.trackRectForBounds(it.bounds) }
        val left = seekLeft + (track?.useContents { origin.x } ?: 0.0)
        val span = track?.useContents { size.width } ?: seekWidth
        val y = MARGIN / 2 + (track?.useContents { origin.y + size.height / 2 } ?: (SEEK_HEIGHT / 2)) - TRACK_HEIGHT / 2
        val ranges = if (shown.seekable) shown.buffered else emptyList()
        // Without this every tick would slide the ranges into place.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        trackLayer.frame = CGRectMake(left, y, span, TRACK_HEIGHT)
        while (bufferedLayers.size < ranges.size) {
            val layer = CALayer()
            layer.backgroundColor = UIColor.whiteColor.colorWithAlphaComponent(0.5).CGColor
            layer.cornerRadius = TRACK_HEIGHT / 2
            bar.layer.insertSublayer(layer, above = trackLayer)
            bufferedLayers += layer
        }
        bufferedLayers.forEachIndexed { index, layer ->
            val range = ranges.getOrNull(index)
            layer.hidden = range == null
            if (range != null) layer.frame = CGRectMake(left + range.start * span, y, (range.endInclusive - range.start) * span, TRACK_HEIGHT)
        }
        CATransaction.commit()
    }

    /** Where each buffered range is drawn, for a test. */
    internal fun bufferedFrames(): List<CValue<CGRect>> = bufferedLayers.filterNot { it.hidden }.map { it.frame }
}

private const val MARGIN = 8.0
private const val BUTTON = 44.0
private const val SEEK_HEIGHT = 32.0
private const val TRACK_HEIGHT = 4.0
private const val SCRUB_LABEL_WIDTH = 96.0
private const val SCRUB_LABEL_HEIGHT = 36.0

/** An icon as a template image, which a button tints. It is never flipped for a right-to-left layout. */
internal fun iconImage(icon: List<IconPath>, side: Double = 24.0): UIImage {
    val scale = side / ControlIconShapes.GRID
    val image = UIGraphicsImageRenderer(size = CGSizeMake(side, side)).imageWithActions { _ ->
        UIColor.whiteColor.set()
        icon.forEach { outline ->
            val path = outline.toBezierPath(scale)
            if (outline.stroked) {
                path.lineWidth = ControlIconShapes.STROKE * scale
                path.lineCapStyle = CGLineCap.kCGLineCapRound
                path.lineJoinStyle = CGLineJoin.kCGLineJoinRound
                path.stroke()
            } else {
                path.usesEvenOddFillRule = outline.evenOdd
                path.fill()
            }
        }
    }
    return image.imageWithRenderingMode(UIImageRenderingMode.UIImageRenderingModeAlwaysTemplate)
}

/** One outline of an icon as a UIKit path, with each grid unit [scale] points long. */
internal fun IconPath.toBezierPath(scale: Double = 1.0): UIBezierPath {
    val path = UIBezierPath()
    for (step in steps) {
        when (step) {
            is IconStep.Move -> path.moveToPoint(CGPointMake(step.x * scale, step.y * scale))
            is IconStep.Line -> path.addLineToPoint(CGPointMake(step.x * scale, step.y * scale))
            IconStep.Close -> path.closePath()
            is IconStep.Arc -> {
                val from = step.start * PI / 180
                val to = (step.start + step.sweep) * PI / 180
                // An arc starts a new piece of outline, so the path first moves to where it begins.
                path.moveToPoint(CGPointMake((step.cx + step.radius * cos(from)) * scale, (step.cy + step.radius * sin(from)) * scale))
                path.addArcWithCenter(
                    CGPointMake(step.cx * scale, step.cy * scale),
                    radius = step.radius * scale,
                    startAngle = from,
                    endAngle = to,
                    clockwise = true,
                )
            }
        }
    }
    return path
}
