@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AppKit.NSImage
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fill on the AppKit image view (#768): the picture is cropped to the view's shape, centred. */
class VideoImageViewTest {

    private fun picture(width: Int, height: Int, shownWidth: Double = width.toDouble()): NSImage {
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            null, width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )
        CGColorSpaceRelease(space)
        val image = CGBitmapContextCreateImage(context)!!
        CGContextRelease(context)
        return try {
            NSImage(cGImage = image, size = CGSizeMake(shownWidth, height.toDouble()))
        } finally {
            CGImageRelease(image)
        }
    }

    private fun NSImage.pixels(): Pair<Int, Int> {
        val cg = CGImageForProposedRect(null, null, null)!!
        return CGImageGetWidth(cg).toInt() to CGImageGetHeight(cg).toInt()
    }

    private fun NSImage.aspect(): Double = size.useContents { width / height }

    @Test
    fun aWidePictureLosesItsSidesInAFourByThreeView() {
        val view = VideoImageView(CGRectMake(0.0, 0.0, 640.0, 480.0))
        view.fill = true
        view.show(picture(1920, 800))
        val shown = assertNotNull(view.image)
        assertTrue(abs(shown.aspect() - 4.0 / 3.0) < 0.01, "aspect ${shown.aspect()}")
        assertEquals(1067 to 800, shown.pixels(), "the full height stays, the sides go")
    }

    @Test
    fun aTallPictureLosesItsTopAndBottomInAWideView() {
        val view = VideoImageView(CGRectMake(0.0, 0.0, 1600.0, 900.0))
        view.fill = true
        view.show(picture(1080, 1080))
        val shown = assertNotNull(view.image)
        assertTrue(abs(shown.aspect() - 16.0 / 9.0) < 0.01, "aspect ${shown.aspect()}")
        assertEquals(1080 to 608, shown.pixels())
    }

    @Test
    fun fitShowsThePictureUncropped() {
        val view = VideoImageView(CGRectMake(0.0, 0.0, 640.0, 480.0))
        val source = picture(1920, 800)
        view.show(source)
        assertEquals(1920 to 800, assertNotNull(view.image).pixels())
        view.fill = true
        assertEquals(800, assertNotNull(view.image).pixels().second)
        view.fill = false
        assertEquals(1920 to 800, assertNotNull(view.image).pixels(), "leaving Fill puts the whole picture back")
    }

    @Test
    fun aResizeCropsThePausedPictureAgain() {
        val view = VideoImageView(CGRectMake(0.0, 0.0, 640.0, 480.0))
        view.fill = true
        view.show(picture(1920, 800))
        view.setFrameSize(CGSizeMake(500.0, 500.0))
        val shown = assertNotNull(view.image)
        assertTrue(abs(shown.aspect() - 1.0) < 0.01, "aspect ${shown.aspect()}")
        assertEquals(800 to 800, shown.pixels())
    }

    @Test
    fun anAnamorphicPictureCropsByItsShownShape() {
        // 1440 pixels shown 1920 wide: the crop keeps the same share of the pixels as of the shape.
        val view = VideoImageView(CGRectMake(0.0, 0.0, 800.0, 800.0))
        view.fill = true
        view.show(picture(1440, 1080, shownWidth = 1920.0))
        val shown = assertNotNull(view.image)
        assertTrue(abs(shown.aspect() - 1.0) < 0.01, "aspect ${shown.aspect()}")
        assertEquals(810 to 1080, shown.pixels())
    }

    @Test
    fun matchingShapesNeedNoCrop() {
        assertNull(fillKeep(1920.0, 1080.0, 1280.0, 720.0))
        assertNull(fillKeep(0.0, 1080.0, 1280.0, 720.0))
        assertEquals(0.5 to 1.0, fillKeep(2.0, 1.0, 1.0, 1.0))
    }
}
