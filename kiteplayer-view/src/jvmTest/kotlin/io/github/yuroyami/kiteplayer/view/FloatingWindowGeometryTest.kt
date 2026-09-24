package io.github.yuroyami.kiteplayer.view

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the floating picture window opens and how it resizes, as plain arithmetic.
 *
 * The window itself needs a display, so it is not built here. Everything that decides its size and
 * place is a pure function over screen rectangles, and a build machine can check every corner of it.
 */
class FloatingWindowGeometryTest {

    private val hd = Rectangle(0, 0, 1920, 1080)
    private val noInsets = Insets(0, 0, 0, 0)
    private val wide = 16f / 9f

    @Test
    fun theWindowTakesItsShareOfTheWidthAndTheVideosAspect() {
        val bounds = floatingWindowBounds(hd, noInsets, wide, FloatingWindowOptions())
        assertEquals(480, bounds.width, "a quarter of a 1920 pixel screen")
        assertEquals(270, bounds.height, "the height follows the 16:9 picture")
    }

    @Test
    fun eachCornerPlacesTheWindowInsideTheMargins() {
        fun at(corner: FloatingCorner) =
            floatingWindowBounds(hd, noInsets, wide, FloatingWindowOptions(corner = corner)).location
        assertEquals(java.awt.Point(24, 24), at(FloatingCorner.TopLeft))
        assertEquals(java.awt.Point(1416, 24), at(FloatingCorner.TopRight))
        assertEquals(java.awt.Point(24, 786), at(FloatingCorner.BottomLeft))
        assertEquals(java.awt.Point(1416, 786), at(FloatingCorner.BottomRight))
    }

    @Test
    fun theInsetsKeepTheWindowOffTheMenuBarAndTheDock() {
        val screen = Rectangle(0, 0, 1600, 900)
        val menuBarAndDock = Insets(25, 0, 75, 0)
        val bottom = floatingWindowBounds(screen, menuBarAndDock, wide, FloatingWindowOptions())
        assertEquals(Rectangle(1176, 576, 400, 225), bottom, "the dock's 75 pixels stay free below the margin")
        val top = floatingWindowBounds(screen, menuBarAndDock, wide, FloatingWindowOptions(corner = FloatingCorner.TopLeft))
        assertEquals(49, top.y, "the menu bar's 25 pixels stay free above the margin")
    }

    @Test
    fun aTaskBarOnTheLeftMovesTheLeftCornersAndNarrowsTheShare() {
        val taskBar = Insets(0, 60, 0, 0)
        val bounds = floatingWindowBounds(hd, taskBar, wide, FloatingWindowOptions(corner = FloatingCorner.TopLeft))
        assertEquals(84, bounds.x)
        assertEquals(465, bounds.width, "a quarter of the 1860 usable pixels, not of the whole screen")
    }

    @Test
    fun aSecondScreenIsUsedInItsOwnCoordinates() {
        val right = Rectangle(1920, 0, 2560, 1440)
        assertEquals(
            Rectangle(3816, 1056, 640, 360),
            floatingWindowBounds(right, noInsets, wide, FloatingWindowOptions()),
        )
        val aboveAndLeft = Rectangle(-1280, -1024, 1280, 1024)
        assertEquals(
            Rectangle(-1256, -1000, 320, 180),
            floatingWindowBounds(aboveAndLeft, noInsets, wide, FloatingWindowOptions(corner = FloatingCorner.TopLeft)),
        )
    }

    @Test
    fun aTallPictureIsFittedInsideTheScreen() {
        val bounds = floatingWindowBounds(hd, noInsets, 3f / 4f, FloatingWindowOptions(widthFraction = 0.5f))
        assertEquals(Rectangle(1122, 24, 774, 1032), bounds, "960 wide would be 1280 tall, so the height decides")
    }

    @Test
    fun theWholeWidthIsFittedInsideTheMargins() {
        val bounds = floatingWindowBounds(hd, noInsets, wide, FloatingWindowOptions(widthFraction = 1f))
        assertEquals(1835, bounds.width)
        assertEquals(1032, bounds.height)
    }

    @Test
    fun aVideoWithNoAspectYetOpensAtSixteenByNine() {
        assertEquals(wide, floatingAspect(0f, 0))
        assertEquals(wide, floatingAspect(Float.NaN, 0))
        assertEquals(wide, floatingAspect(-2f, 0))
    }

    @Test
    fun aVideoShownOnItsSideTurnsTheAspect() {
        assertEquals(9f / 16f, floatingAspect(wide, 90), 0.0001f)
        assertEquals(9f / 16f, floatingAspect(wide, 270), 0.0001f)
        assertEquals(9f / 16f, floatingAspect(wide, -90), 0.0001f)
        assertEquals(wide, floatingAspect(wide, 180))
    }

    @Test
    fun resizingFollowsTheAxisThatMovedMoreAndKeepsTheAspect() {
        val start = Dimension(480, 270)
        assertEquals(Dimension(640, 360), resizedKeepingAspect(start, dx = 160, dy = 0, aspect = wide, maxWidth = 1920, maxHeight = 1080))
        assertEquals(Dimension(640, 360), resizedKeepingAspect(start, dx = 0, dy = 90, aspect = wide, maxWidth = 1920, maxHeight = 1080))
        assertEquals(Dimension(640, 360), resizedKeepingAspect(start, dx = 10, dy = 90, aspect = wide, maxWidth = 1920, maxHeight = 1080))
    }

    @Test
    fun resizingStopsAtTheSmallestWidthAndAtTheScreen() {
        val start = Dimension(480, 270)
        assertEquals(Dimension(MIN_FLOATING_WIDTH, 90), resizedKeepingAspect(start, -1000, 0, wide, 1872, 1032))
        assertEquals(Dimension(1835, 1032), resizedKeepingAspect(start, 5000, 0, wide, 1872, 1032))
    }

    @Test
    fun onlyTheLowerRightCornerResizes() {
        assertTrue(isOnResizeGrip(470, 260, 480, 270))
        assertFalse(isOnResizeGrip(10, 10, 480, 270))
        assertFalse(isOnResizeGrip(470, 10, 480, 270), "the upper right corner moves the window")
        assertFalse(isOnResizeGrip(10, 260, 480, 270), "the lower left corner moves the window")
    }

    @Test
    fun theOptionsRefuseAShareOrAMarginThatCannotBeLaidOut() {
        assertFailsWith<IllegalArgumentException> { FloatingWindowOptions(widthFraction = 0f) }
        assertFailsWith<IllegalArgumentException> { FloatingWindowOptions(widthFraction = 1.5f) }
        assertFailsWith<IllegalArgumentException> { FloatingWindowOptions(widthFraction = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { FloatingWindowOptions(margin = -1) }
        FloatingWindowOptions(widthFraction = 1f, margin = 0)
    }
}
