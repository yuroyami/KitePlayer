package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Metal quad reads only the part of the texture a container's crop leaves, and is shaped by
 * that part (#497). Each texture coordinate the quad's corners reach is worked out here the way the
 * vertex shader does: basis rows times the corner, plus a half, plus the shift.
 */
class MetalCropQuadTest {

    private class CroppedFrame(
        override val size: VideoSize,
        override val crop: PictureCrop?,
        override val rotationDegrees: Int = 0,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Nv12
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo(
            matrix = ColorMatrix.Bt709,
            primaries = ColorPrimaries.Bt709,
            transfer = ColorTransfer.Bt709,
            fullRange = false,
        )
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    /** The texture coordinate the shader gives the quad point ([x], [y]), each from -0.5 to 0.5. */
    private fun FloatArray.read(x: Float, y: Float): Pair<Float, Float> =
        (x * this[2] + y * this[3] + 0.5f + this[8]) to (x * this[4] + y * this[5] + 0.5f + this[9])

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1e-5f, "$what: expected $expected, got $actual")

    @Test
    fun eightPaddingRowsFillASixteenByNineViewWithoutTheirRows() {
        val quad = quadUniformsFor(CroppedFrame(VideoSize(1920, 1088), PictureCrop(bottom = 8)), 1920, 1080)
        near(1f, quad[0], "the quad spans the whole width")
        near(1f, quad[1], "and the whole height, so no bars")
        val (left, top) = quad.read(-0.5f, -0.5f)
        val (right, bottom) = quad.read(0.5f, 0.5f)
        near(0f, left, "left edge")
        near(0f, top, "top edge")
        near(1f, right, "right edge")
        near(1080f / 1088f, bottom, "the bottom stops above the eight padding rows")
    }

    @Test
    fun barsOnEverySideAreLeftOutOfTheRead() {
        val crop = PictureCrop(top = 10, bottom = 20, left = 30, right = 40)
        val quad = quadUniformsFor(CroppedFrame(VideoSize(200, 100), crop), 130, 70)
        val (left, top) = quad.read(-0.5f, -0.5f)
        val (right, bottom) = quad.read(0.5f, 0.5f)
        near(30f / 200f, left, "left")
        near(10f / 100f, top, "top")
        near(160f / 200f, right, "right")
        near(80f / 100f, bottom, "bottom")
    }

    @Test
    fun aQuarterTurnStillReadsOnlyThePartLeft() {
        val quad = quadUniformsFor(CroppedFrame(VideoSize(1920, 1088), PictureCrop(bottom = 8), 90), 1080, 1920)
        near(1f, quad[0], "the turned picture fills a portrait view")
        near(1f, quad[1], "top to bottom")
        val reads = listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f).map { (x, y) -> quad.read(x, y) }
        near(0f, reads.minOf { it.second }, "the stored top row is read")
        near(1080f / 1088f, reads.maxOf { it.second }, "the stored padding rows are not")
        near(0f, reads.minOf { it.first }, "every stored column is read")
        near(1f, reads.maxOf { it.first }, "to the last one")
    }

    @Test
    fun noCropAndACropThatDoesNotFitReadTheWholeTexture() {
        val whole = quadUniformsFor(CroppedFrame(VideoSize(320, 200), null), 320, 200)
        val unfitted = quadUniformsFor(CroppedFrame(VideoSize(320, 200), PictureCrop(top = 100, bottom = 100)), 320, 200)
        assertEquals(whole.toList(), unfitted.toList())
        assertEquals(listOf(0f, 0f), whole.toList().takeLast(2), "no shift")
    }
}
