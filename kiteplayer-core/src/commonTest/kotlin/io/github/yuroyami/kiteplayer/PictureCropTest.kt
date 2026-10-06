package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The arithmetic of a container's crop (#497): what is left, and when a crop is not trusted. */
class PictureCropTest {

    @Test
    fun eightPaddingRowsLeaveSixteenByNine() {
        val shown = VideoSize(1920, 1088).cropped(PictureCrop(bottom = 8))
        assertEquals(VideoSize(1920, 1080), shown)
        assertEquals(16f / 9f, shown.displayAspect)
    }

    @Test
    fun thePixelAspectStaysAndAppliesToWhatIsLeft() {
        // Anamorphic PAL widescreen with eight columns of each side hidden.
        val shown = VideoSize(720, 576, 64, 45).cropped(PictureCrop(left = 8, right = 8))
        assertEquals(VideoSize(704, 576, 64, 45), shown)
        assertEquals(1001, shown.displayWidth)
    }

    @Test
    fun noCropAndAnEmptyCropChangeNothing() {
        val size = VideoSize(640, 480)
        assertEquals(size, size.cropped(null))
        assertEquals(size, size.cropped(PictureCrop()))
        assertTrue(PictureCrop().isEmpty)
        assertFalse(PictureCrop(top = 1).isEmpty)
    }

    @Test
    fun aCropThatLeavesNothingIsNotTrusted() {
        assertFalse(PictureCrop(top = 100, bottom = 100).fits(320, 200), "nothing left of the height")
        assertFalse(PictureCrop(left = 320).fits(320, 200), "nothing left of the width")
        assertFalse(PictureCrop(top = -1).fits(320, 200), "a negative count")
        assertFalse(PictureCrop(left = Int.MAX_VALUE, right = Int.MAX_VALUE).fits(320, 200), "a sum that wraps")
        assertTrue(PictureCrop(top = 99, bottom = 100).fits(320, 200), "one row left is a picture")
        assertEquals(VideoSize(320, 200), VideoSize(320, 200).cropped(PictureCrop(top = 100, bottom = 100)))
    }
}
