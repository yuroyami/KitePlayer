package io.github.yuroyami.kiteplayer.view

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.roundToInt

/** The screen corner a floating picture window opens in. */
public enum class FloatingCorner { TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * Where and how large the floating picture window of [KitePlayerPictureInPicture] opens.
 *
 * @property widthFraction the window's width as a share of the screen's usable width, above 0 and
 *           at most 1. The height follows from the video's aspect.
 * @property corner the corner of the screen the window opens in.
 * @property margin the distance in pixels between the window and the edges of the usable screen.
 */
public data class FloatingWindowOptions(
    val widthFraction: Float = 0.25f,
    val corner: FloatingCorner = FloatingCorner.BottomRight,
    val margin: Int = 24,
    /** The words of the window's right-click menu. */
    val labels: FloatingWindowLabels = FloatingWindowLabels(),
) {
    init {
        require(widthFraction > 0f && widthFraction <= 1f) {
            "widthFraction must be above 0 and at most 1, and it was $widthFraction"
        }
        require(margin >= 0) { "margin must not be negative, and it was $margin" }
    }
}

/** The words of the floating window's right-click menu. English by default; pass translated ones. */
public data class FloatingWindowLabels(
    val play: String = "Play",
    val pause: String = "Pause",
    val backToApp: String = "Back to app",
    val close: String = "Close",
)

/** The menu entry that plays or pauses: what it does now, so Pause while the player is active. */
internal fun FloatingWindowLabels.playOrPause(active: Boolean): String = if (active) pause else play

/** The aspect a window has before any video has reported one. */
internal const val DEFAULT_FLOATING_ASPECT: Float = 16f / 9f

/** The narrowest a drag can make the window, so it cannot shrink out of reach. */
internal const val MIN_FLOATING_WIDTH: Int = 160

/** The square in the window's lower right corner where a drag resizes instead of moving. */
internal const val RESIZE_GRIP: Int = 20

/**
 * The aspect the window should have: the video's display aspect, inverted when the video is shown
 * on its side, or [DEFAULT_FLOATING_ASPECT] when there is no video yet.
 */
internal fun floatingAspect(displayAspect: Float, rotationDegrees: Int): Float {
    val aspect = if (displayAspect.isFinite() && displayAspect > 0f) displayAspect else DEFAULT_FLOATING_ASPECT
    return if (Math.floorMod(rotationDegrees, 180) == 90) 1f / aspect else aspect
}

/** The part of a screen a window may use: the screen minus its menu bar, dock or task bar. */
internal fun usableArea(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(0),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(0),
)

/**
 * The window's size: [FloatingWindowOptions.widthFraction] of the usable width at [aspect], made
 * smaller when that does not fit inside the margins.
 */
internal fun floatingWindowSize(usable: Rectangle, aspect: Float, options: FloatingWindowOptions): Dimension {
    val maxWidth = (usable.width - 2 * options.margin).coerceAtLeast(1).toFloat()
    val maxHeight = (usable.height - 2 * options.margin).coerceAtLeast(1).toFloat()
    var width = usable.width * options.widthFraction
    var height = width / aspect
    if (width > maxWidth) {
        width = maxWidth
        height = width / aspect
    }
    if (height > maxHeight) {
        height = maxHeight
        width = height * aspect
    }
    return Dimension(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))
}

/** Bounds of [size] in [corner] of the usable area, [margin] pixels in from its edges. */
internal fun cornerBounds(usable: Rectangle, size: Dimension, corner: FloatingCorner, margin: Int): Rectangle {
    val left = usable.x + margin
    val top = usable.y + margin
    val right = usable.x + usable.width - margin - size.width
    val bottom = usable.y + usable.height - margin - size.height
    return when (corner) {
        FloatingCorner.TopLeft -> Rectangle(left, top, size.width, size.height)
        FloatingCorner.TopRight -> Rectangle(right, top, size.width, size.height)
        FloatingCorner.BottomLeft -> Rectangle(left, bottom, size.width, size.height)
        FloatingCorner.BottomRight -> Rectangle(right, bottom, size.width, size.height)
    }
}

/** Where the window opens on a screen with [screen] bounds and [insets], for a picture of [aspect]. */
internal fun floatingWindowBounds(
    screen: Rectangle,
    insets: Insets,
    aspect: Float,
    options: FloatingWindowOptions,
): Rectangle {
    val usable = usableArea(screen, insets)
    val size = floatingWindowSize(usable, aspect, options)
    return cornerBounds(usable, size, options.corner, options.margin)
}

/** True when a press at ([x], [y]) in a window of [width] by [height] lands on the resize grip. */
internal fun isOnResizeGrip(x: Int, y: Int, width: Int, height: Int): Boolean =
    x >= width - RESIZE_GRIP && y >= height - RESIZE_GRIP

/**
 * The window's size after its lower right corner moved by ([dx], [dy]) from [start].
 *
 * The axis that moved more, measured in width, decides the new width, and the height follows from
 * [aspect], so the picture never needs black bars it did not have before. The width stays between
 * [MIN_FLOATING_WIDTH] and what [maxWidth] and [maxHeight] allow.
 */
internal fun resizedKeepingAspect(
    start: Dimension,
    dx: Int,
    dy: Int,
    aspect: Float,
    maxWidth: Int,
    maxHeight: Int,
): Dimension {
    val wanted = if (abs(dx.toFloat()) >= abs(dy * aspect)) start.width + dx.toFloat() else (start.height + dy) * aspect
    val widest = minOf(maxWidth.toFloat(), maxHeight * aspect).coerceAtLeast(1f)
    val width = wanted.coerceIn(minOf(MIN_FLOATING_WIDTH.toFloat(), widest), widest)
    return Dimension(width.roundToInt(), (width / aspect).roundToInt().coerceAtLeast(1))
}
