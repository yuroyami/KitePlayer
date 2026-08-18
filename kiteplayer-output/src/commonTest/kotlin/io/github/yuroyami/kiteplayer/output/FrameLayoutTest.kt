package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The geometry arms of S1.c.5 step 7: aspect fit both ways, non-square pixels, every quarter
 * turn, and the draw-rectangle exchange that makes a turned picture land on its own letterbox.
 * All pure functions, no renderer instance and no platform anywhere.
 *
 * In commonTest since X-11, because the law it tests moved to commonMain when the web canvas
 * renderer needed the same arithmetic. It ran on Android alone while it was the Android renderer's
 * private law; now that two renderers share it, the tests run everywhere both do.
 *
 * Backticked names here may contain SPACES and nothing else exotic. Kotlin/Native refuses a comma
 * in an identifier ("Name contains illegal characters"), and these names compiled on Android and
 * wasm for a while before macosArm64 said so, which is the whole hazard of a test file moving from
 * one target to all of them.
 */
class FrameLayoutTest {

    @Test
    fun `four by three into sixteen by nine pillarboxes symmetrically`() {
        val layout = frameLayout(1920, 1080, VideoSize(640, 480), 0)!!
        assertEquals(1080, layout.bottom - layout.top, "height fills")
        assertEquals(1440, layout.width, "4:3 at 1080 high is 1440 wide")
        assertEquals(240, layout.left)
        assertEquals(1680, layout.right)
        assertEquals(0, layout.top)
    }

    @Test
    fun `sixteen by nine into four by three letterboxes symmetrically`() {
        val layout = frameLayout(1024, 768, VideoSize(1920, 1080), 0)!!
        assertEquals(1024, layout.width, "width fills")
        assertEquals(576, layout.height, "16:9 at 1024 wide is 576 high")
        assertEquals(96, layout.top)
        assertEquals(672, layout.bottom)
    }

    @Test
    fun `anamorphic pixels widen the picture through displayWidth`() {
        /* 720x480 with 32:27 pixels displays as 853 wide (DVD widescreen). */
        val size = VideoSize(720, 480, 32, 27)
        val layout = frameLayout(853, 480, size, 0)!!
        assertEquals(853, layout.width, "the display width, not the stored width, fills the canvas")
        assertEquals(480, layout.height)
    }

    @Test
    fun `every quarter turn is drawn and everything else shows as stored`() {
        assertEquals(0, quarterTurn(0))
        assertEquals(90, quarterTurn(90))
        assertEquals(180, quarterTurn(180))
        assertEquals(270, quarterTurn(270))
        assertEquals(270, quarterTurn(-90), "a source that says -90 is 270")
        assertEquals(0, quarterTurn(45), "a non-quarter transform shows the stored picture")
        assertEquals(0, quarterTurn(361))
        assertEquals(90, quarterTurn(450))
    }

    @Test
    fun `a quarter turned frame swaps its content axes before the fit`() {
        /* A 1920x1080 frame turned 90 degrees occupies 1080x1920 of content: on a 1080x1920
         * portrait canvas it fills it exactly. */
        val layout = frameLayout(1080, 1920, VideoSize(1920, 1080), 90)!!
        assertEquals(1080, layout.width)
        assertEquals(1920, layout.height)
        assertEquals(90, layout.rotationDegrees)
    }

    @Test
    fun `the draw rectangle is the destination with its sides exchanged about the same centre`() {
        val layout = frameLayout(1080, 1920, VideoSize(1920, 1080), 90)!!
        assertEquals(layout.centerX, (layout.drawLeft + layout.drawRight) / 2f)
        assertEquals(layout.centerY, (layout.drawTop + layout.drawBottom) / 2f)
        assertEquals(layout.height.toFloat(), layout.drawWidth, "sides exchange under a quarter turn")
        assertEquals(layout.width.toFloat(), layout.drawHeight)
        val flat = frameLayout(1920, 1080, VideoSize(1280, 720), 180)!!
        assertEquals(flat.width.toFloat(), flat.drawWidth, "a half turn exchanges nothing")
        assertEquals(flat.height.toFloat(), flat.drawHeight)
    }

    @Test
    fun `nothing to draw is a null layout and never a crash`() {
        assertNull(frameLayout(0, 1080, VideoSize(640, 480), 0))
        assertNull(frameLayout(1920, 0, VideoSize(640, 480), 0))
        assertNull(frameLayout(1920, 1080, VideoSize(0, 480), 0))
        assertNull(frameLayout(1920, 1080, VideoSize(640, 0), 0))
    }

    @Test
    fun `the fit never overhangs the canvas by a rounding error`() {
        for (cw in intArrayOf(101, 640, 1919)) for (ch in intArrayOf(99, 480, 1079)) {
            for (turn in intArrayOf(0, 90)) {
                val layout = frameLayout(cw, ch, VideoSize(1280, 719), turn)!!
                kotlin.test.assertTrue(layout.left >= 0 && layout.top >= 0)
                kotlin.test.assertTrue(layout.right <= cw && layout.bottom <= ch)
                kotlin.test.assertTrue(layout.width >= 1 && layout.height >= 1)
            }
        }
    }

    @Test
    fun `fill covers the canvas and crops the overhanging axis symmetrically`() {
        // 4:3 content on a 16:9 canvas: width fills, height overhangs and splits evenly.
        val layout = frameLayout(1920, 1080, VideoSize(640, 480), 0, io.github.yuroyami.kiteplayer.VideoScale.Fill)!!
        assertEquals(0, layout.left)
        assertEquals(1920, layout.right)
        assertEquals(1440, layout.height)
        assertEquals(-180, layout.top)
        assertEquals(1260, layout.bottom)
    }

    @Test
    fun `stretch takes the canvas whole and keeps the turn`() {
        val layout = frameLayout(1920, 1080, VideoSize(640, 480), 180, io.github.yuroyami.kiteplayer.VideoScale.Stretch)!!
        assertEquals(0, layout.left)
        assertEquals(0, layout.top)
        assertEquals(1920, layout.width)
        assertEquals(1080, layout.height)
        assertEquals(180, layout.rotationDegrees)
    }

    @Test
    fun `the framing transform matches the compose geometry word for word`() {
        // The forced aspect replaces the container's shape: square content forced to 2:1.
        val forced = frameLayout(
            100, 100, VideoSize(100, 100), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform(aspectOverride = 2f),
        )!!
        assertEquals(100, forced.width)
        assertEquals(50, forced.height)
        assertEquals(25, forced.top)

        // Zoom doubles about the centre; pan moves by a fraction of the drawn size.
        val zoomed = frameLayout(
            100, 100, VideoSize(100, 100), 0,
            transform = io.github.yuroyami.kiteplayer.VideoTransform(zoom = 2f, panY = -0.25f),
        )!!
        assertEquals(200, zoomed.width)
        assertEquals(-50, zoomed.left)
        assertEquals(-100, zoomed.top, "zoomed to -50, then panned up by a quarter of 200")

        // The identity transform is exactly the untouched layout, field for field.
        assertEquals(
            frameLayout(1920, 1080, VideoSize(640, 480), 0),
            frameLayout(
                1920, 1080, VideoSize(640, 480), 0,
                transform = io.github.yuroyami.kiteplayer.VideoTransform.Identity,
            ),
        )
    }
}
