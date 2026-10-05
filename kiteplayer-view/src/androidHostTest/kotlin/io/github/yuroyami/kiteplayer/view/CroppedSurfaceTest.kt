package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the Surface goes when a decoder writes a picture with a container crop straight into it
 * (#497). The view lays the Surface out larger by what the crop takes and clips it back, so the
 * part the crop leaves must land exactly on the picture's rectangle, turned the way the view shows
 * the picture.
 */
class CroppedSurfaceTest {

    @Test
    fun eightPaddingRowsHangBelowTheView() {
        val stored = VideoSize(1920, 1088)
        val crop = PictureCrop(bottom = 8)
        val picture = videoBounds(1920, 1080, stored.cropped(crop).displayAspect, VideoScale.Fit)!!
        assertEquals(VideoBounds(0, 0, 1920, 1080), picture, "the whole view, at 16:9")
        assertEquals(VideoBounds(0, 0, 1920, 1088), croppedSurfaceBounds(picture, CropShare.of(crop, stored, 0)))
    }

    @Test
    fun barsTheFileHidesHangAboveAndBelow() {
        val stored = VideoSize(1920, 1080)
        val crop = PictureCrop(top = 140, bottom = 140)
        val picture = videoBounds(1920, 800, stored.cropped(crop).displayAspect, VideoScale.Fit)!!
        assertEquals(VideoBounds(0, 0, 1920, 800), picture)
        assertEquals(VideoBounds(0, -140, 1920, 1080), croppedSurfaceBounds(picture, CropShare.of(crop, stored, 0)))
    }

    @Test
    fun aQuarterTurnPutsTheStoredBottomOnTheLeft() {
        // A portrait clip stored on its side: the codec turns it clockwise, so the stored bottom
        // rows become the left columns of what the view shows.
        val stored = VideoSize(1920, 1088)
        val share = CropShare.of(PictureCrop(bottom = 8), stored, 90)
        assertEquals(CropShare(left = 8f / 1088f, top = 0f, right = 0f, bottom = 0f), share)
        assertEquals(VideoBounds(-8, 0, 1088, 1920), croppedSurfaceBounds(VideoBounds(0, 0, 1080, 1920), share))
    }

    @Test
    fun everyTurnMovesEachSideWhereThePictureGoes() {
        val stored = VideoSize(100, 100)
        val crop = PictureCrop(top = 1, bottom = 2, left = 3, right = 4)
        assertEquals(CropShare(0.03f, 0.01f, 0.04f, 0.02f), CropShare.of(crop, stored, 0))
        assertEquals(CropShare(0.02f, 0.03f, 0.01f, 0.04f), CropShare.of(crop, stored, 90))
        assertEquals(CropShare(0.04f, 0.02f, 0.03f, 0.01f), CropShare.of(crop, stored, 180))
        assertEquals(CropShare(0.01f, 0.04f, 0.02f, 0.03f), CropShare.of(crop, stored, 270))
    }

    @Test
    fun anAnamorphicPictureKeepsItsShareOfEachSide() {
        // 720 stored columns shown 1001 wide: the share of the width a crop takes is the same
        // either way, so the Surface grows by the same share of the shown width.
        val stored = VideoSize(720, 576, 64, 45)
        val crop = PictureCrop(left = 8, right = 8)
        val picture = VideoBounds(0, 0, 1001, 576)
        val surface = croppedSurfaceBounds(picture, CropShare.of(crop, stored, 0))
        assertEquals(1024, surface.width, "1001 shown columns are 704 of 720 stored ones")
        assertEquals(-11, surface.left)
    }
}
