package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PictureCrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The filters that take a stored picture to the one a renderer shows, in a renderer's order (#608). */
class ShownGeometryTest {

    private fun shown(
        width: Int = 1920,
        height: Int = 1080,
        sar: Pair<Int, Int> = 1 to 1,
        crop: PictureCrop? = null,
        rotation: Int = 0,
        mirrored: Boolean = false,
        maxWidth: Int? = null,
    ) = shownGeometry(width, height, sar.first, sar.second, crop, rotation, mirrored, maxWidth)

    @Test
    fun aNeutralPictureNeedsNoFilter() {
        val geometry = shown(maxWidth = 1920)
        assertNull(geometry.filter)
        assertEquals(1920 to 1080, geometry.width to geometry.height)
        // An unknown aspect is square, as a renderer takes it.
        assertNull(shown(sar = 0 to 1).filter)
    }

    @Test
    fun aPortraitRecordingIsTurnedBeforeItIsScaled() {
        val geometry = shown(rotation = 90, maxWidth = 320)
        assertEquals("transpose=clock,scale=320:568", geometry.filter)
        assertEquals(320 to 568, geometry.width to geometry.height)
        assertEquals("transpose=cclock", shown(rotation = 270).filter)
        assertEquals("transpose=cclock", shown(rotation = -90).filter)
        assertEquals("hflip,vflip", shown(rotation = 180).filter)
    }

    @Test
    fun anAnamorphicPictureGetsSquarePixelsAtItsShownWidth() {
        // A DVD picture of 720x576 at 64:45 is 16:9 on screen, 1024 wide.
        val geometry = shown(width = 720, height = 576, sar = 64 to 45, maxWidth = 320)
        assertEquals("scale=1024:576,setsar=1,scale=320:180", geometry.filter)
        assertEquals(320 to 180, geometry.width to geometry.height)
    }

    @Test
    fun theCropComesOffFirstAndTheMirrorGoesBeforeTheTurn() {
        val geometry = shown(crop = PictureCrop(top = 140, bottom = 140), rotation = 90, mirrored = true)
        assertEquals("crop=1920:800:0:140,hflip,transpose=clock", geometry.filter)
        assertEquals(800 to 1920, geometry.width to geometry.height)
        // A crop that leaves nothing is the file's mistake, and is left out.
        assertNull(shown(crop = PictureCrop(left = 1000, right = 1000)).filter)
    }
}
