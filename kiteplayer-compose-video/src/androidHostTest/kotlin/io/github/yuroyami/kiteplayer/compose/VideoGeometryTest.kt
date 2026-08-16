package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoGeometryTest {

    @Test
    fun aMatchingAspectFillsTheArea() {
        val layout = videoLayout(1920, 1080, VideoSize(1920, 1080), 0)!!
        assertEquals(0, layout.left)
        assertEquals(0, layout.top)
        assertEquals(1920, layout.width)
        assertEquals(1080, layout.height)
        assertEquals(0, layout.rotationDegrees)
    }

    @Test
    fun aWideFrameLetterboxesSymmetrically() {
        val layout = videoLayout(100, 100, VideoSize(200, 100), 0)!!
        assertEquals(100, layout.width)
        assertEquals(50, layout.height)
        assertEquals(0, layout.left)
        assertEquals(25, layout.top)
        assertEquals(75, layout.bottom)
    }

    @Test
    fun aTallFramePillarboxesSymmetrically() {
        val layout = videoLayout(100, 100, VideoSize(100, 200), 0)!!
        assertEquals(50, layout.width)
        assertEquals(100, layout.height)
        assertEquals(25, layout.left)
        assertEquals(75, layout.right)
    }

    @Test
    fun anamorphicWidthScalesBeforeTheFit() {
        // 720x576 with a 16:11 pixel aspect displays as 1047x576.
        val layout = videoLayout(1047, 576, VideoSize(720, 576, 16, 11), 0)!!
        assertEquals(1047, layout.width)
        assertEquals(576, layout.height)
    }

    @Test
    fun aQuarterTurnExchangesTheContentSides() {
        // A 1920x1080 frame turned 90 degrees occupies a 1080-wide, 1920-tall footprint,
        // fitted into a 1080x1920 portrait area exactly.
        val layout = videoLayout(1080, 1920, VideoSize(1920, 1080), 90)!!
        assertEquals(1080, layout.width)
        assertEquals(1920, layout.height)
        assertEquals(90, layout.rotationDegrees)
        // The draw rectangle is the footprint with its sides exchanged about the same centre.
        assertEquals(1920f, layout.drawWidth)
        assertEquals(1080f, layout.drawHeight)
        assertEquals(layout.centerX - 960f, layout.drawLeft)
        assertEquals(layout.centerY - 540f, layout.drawTop)
    }

    @Test
    fun anOddSideHalvesFractionally() {
        val layout = videoLayout(100, 100, VideoSize(99, 100), 90)!!
        // Footprint: 100 tall content becomes width; 99 wide content becomes height.
        assertEquals(100, layout.width)
        assertEquals(99, layout.height)
        assertEquals(99f, layout.drawWidth)
        assertEquals(100f, layout.drawHeight)
        assertEquals(layout.centerX - 49.5f, layout.drawLeft)
    }

    @Test
    fun nonQuarterRotationsDrawUnrotated() {
        assertEquals(0, quarterTurn(45))
        assertEquals(0, quarterTurn(1))
        assertEquals(270, quarterTurn(-90))
        assertEquals(180, quarterTurn(540))
        assertEquals(0, videoLayout(10, 10, VideoSize(10, 10), 45)!!.rotationDegrees)
    }

    @Test
    fun degenerateInputsProduceNoLayout() {
        assertNull(videoLayout(0, 100, VideoSize(10, 10), 0))
        assertNull(videoLayout(100, -1, VideoSize(10, 10), 0))
        assertNull(videoLayout(100, 100, VideoSize(0, 10), 0))
        assertNull(videoLayout(100, 100, VideoSize(10, 0), 0))
    }

    @Test
    fun aNonsensePixelAspectFallsBackToTheStoredWidth() {
        val layout = videoLayout(100, 100, VideoSize(100, 100, 0, 5), 0)!!
        assertEquals(100, layout.width)
        assertEquals(100, layout.height)
    }

    @Test
    fun theAspectOverrideReplacesTheContainersShape() {
        // A square picture forced to 2:1 in a square viewport: full width, half height, centred.
        val forced = videoLayout(
            100, 100, VideoSize(100, 100), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform(aspectOverride = 2f),
        )!!
        assertEquals(100, forced.width)
        assertEquals(50, forced.height)
        assertEquals(25, forced.top)
    }

    @Test
    fun zoomScalesAboutTheCentreAndPanMovesByTheDrawnSize() {
        val zoomed = videoLayout(
            100, 100, VideoSize(100, 100), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform(zoom = 2f),
        )!!
        assertEquals(200, zoomed.width)
        assertEquals(200, zoomed.height)
        assertEquals(-50, zoomed.left)
        assertEquals(-50, zoomed.top)

        // A quarter pan of an unzoomed picture moves it by a quarter of its own drawn width.
        val panned = videoLayout(
            100, 100, VideoSize(100, 100), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform(panX = 0.25f),
        )!!
        assertEquals(25, panned.left)
        assertEquals(0, panned.top)

        // And the identity transform is exactly the untouched layout, field for field.
        val plain = videoLayout(160, 90, VideoSize(1280, 720), 0)!!
        val identity = videoLayout(
            160, 90, VideoSize(1280, 720), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform.Identity,
        )!!
        assertEquals(plain, identity)
    }
}
