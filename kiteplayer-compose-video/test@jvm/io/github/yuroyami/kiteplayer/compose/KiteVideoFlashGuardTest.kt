package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flash guard on the Compose canvas renderer (#500): it measures the pictures it converts and
 * publishes each with the factor to draw it with, and the draw folds that factor into the picture
 * controls. See `docs/video-flash-guard.md`.
 */
class KiteVideoFlashGuardTest {
    init { useSkiaGraphics() }

    /** A picture of one grey level, which the fake converter fills in. */
    private class Flat(val level: Int, val colour: Int = level * 0x010101) : VideoFrame {
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(32, 18)
        override val rotationDegrees: Int = 0
        override val hardwareSurface: HwSurfaceKind? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
        override val generation: Generation = Generation(0)
        override fun close() = Unit
    }

    private class Harness {
        val published = CopyOnWriteArrayList<KiteVideoFrame>()
        @Volatile var nanos = 0L
        val renderer = KiteVideoRenderer(
            convert = { frame ->
                val colour = (frame as Flat).colour
                ByteArray(frame.size.width * frame.size.height * 4) { if (it % 4 == 3) -1 else (colour shr (16 - 8 * (it % 4))).toByte() }
            },
            makeImage = { _, width, height -> FrameImage(ImageBitmap(width, height)) },
            publish = { frame -> if (frame != null) published += frame },
            guardNanos = { nanos },
        )

        /** Shows [levels] at 30 frames a second, each once it is converted, and answers their factors. */
        fun show(levels: List<Int>): List<Float> = showColours(levels.map { it * 0x010101 })

        /** As [show], with each picture one packed `0xRRGGBB` colour. */
        fun showColours(colours: List<Int>): List<Float> = runBlocking {
            val first = published.size
            colours.forEachIndexed { index, colour ->
                nanos += 1_000_000_000L / 30
                renderer.present(Flat(0, colour), nanos)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (published.size < first + index + 1) {
                    check(System.nanoTime() < deadline) { "frame $index was not published" }
                    Thread.sleep(1)
                }
            }
            published.drop(first).map { it.dim }
        }
    }

    /** A strobe at 5 Hz, three frames of black, then three of white. */
    private fun strobe(frames: Int) = List(frames) { if ((it / 3) % 2 == 1) 255 else 0 }

    @Test
    fun aStrobeIsDimmedFromItsSeventhLegWithTheGuardOn() {
        val h = Harness()
        try {
            h.renderer.setFlashGuard(FlashGuard.On)
            val dims = h.show(strobe(60))
            assertTrue(dims.take(21).all { it == 1f }, "three flashes are drawn as they are: $dims")
            assertTrue(dims.drop(21).all { it < 1f }, "and the run is dimmed from its seventh leg: $dims")
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun aRedAndBlueStrobeOfOneLuminanceIsDimmedByTheRedRule() {
        val h = Harness()
        try {
            h.renderer.setFlashGuard(FlashGuard.On)
            val dims = h.showColours(List(60) { if ((it / 3) % 2 == 1) 0xFF0000 else 0x007CFF })
            assertTrue(dims.take(21).all { it == 1f }, "three red flashes are drawn as they are: $dims")
            assertTrue(dims.drop(21).all { it < 0.3f }, "and the run is dimmed until a red leg is under the threshold (#561): $dims")
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun withTheGuardOffOrFollowingASystemItCannotReadNothingIsDimmed() {
        val h = Harness()
        try {
            assertTrue(h.show(strobe(60)).all { it == 1f }, "FollowSystem, the default, has no system here")
            h.renderer.setFlashGuard(FlashGuard.Off)
            assertTrue(h.show(strobe(60)).all { it == 1f })
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun aPictureThatDoesNotFlashIsNeverDimmed() {
        val h = Harness()
        try {
            h.renderer.setFlashGuard(FlashGuard.On)
            // A slow fade out and in, twice.
            val fade = List(30) { 255 - it * 8 } + List(30) { 15 + it * 8 }
            assertTrue(h.show(fade + fade).all { it == 1f })
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun takingThePictureOffForgetsTheRun() {
        val h = Harness()
        try {
            h.renderer.setFlashGuard(FlashGuard.On)
            assertTrue(h.show(strobe(60)).last() < 1f)
            h.renderer.clearPicture()
            assertTrue(h.show(strobe(18)).all { it == 1f }, "a fresh history: three flashes again")
        } finally {
            h.renderer.close()
        }
    }

    @Test
    fun turningTheGuardOffAndOnAgainStartsAFreshHistory() {
        val h = Harness()
        try {
            h.renderer.setFlashGuard(FlashGuard.On)
            assertTrue(h.show(strobe(30)).last() < 1f)
            h.renderer.setFlashGuard(FlashGuard.Off)
            h.renderer.setFlashGuard(FlashGuard.On)
            assertTrue(h.show(strobe(18)).all { it == 1f }, "three flashes again, not the old run")
        } finally {
            h.renderer.close()
        }
    }

    /** The colour drawn at the middle of a white picture through [filter]. */
    private fun drawnWhite(filter: ColorFilter?): Int {
        val white = FrameImagePool().imageFor(ByteArray(4 * 4 * 4) { -1 }, 4, 4).image
        val layout = videoLayout(40, 40, VideoSize(4, 4), 0)!!
        val target = ImageBitmap(40, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(40f, 40f)) {
            drawVideoPicture(white, layout, false, FilterQuality.None, colorFilter = filter)
        }
        val pixels = IntArray(40 * 40)
        target.readPixels(pixels)
        return pixels[20 * 40 + 20] and 0xFF
    }

    @Test
    fun aFrameIsDrawnWithItsOwnFactorAndThePictureControls() {
        val state = KiteVideoState()
        try {
            val image = ImageBitmap(4, 4)
            fun frame(dim: Float) = KiteVideoFrame(image, VideoSize(4, 4), 0, dim = dim)
            assertEquals(null, state.pictureFilterFor(frame(1f)), "outside a run, neutral controls draw with no filter")
            assertTrue(abs(drawnWhite(state.pictureFilterFor(frame(0.5f))) - 128) <= 1, "a run draws at its factor")
            assertTrue(abs(drawnWhite(state.pictureFilterFor(frame(0.25f))) - 64) <= 1, "and at each new one")
            state.renderer.setAdjustments(VideoAdjustments(contrast = 1.5f))
            assertTrue(abs(drawnWhite(state.pictureFilterFor(frame(0.5f))) - 159) <= 1, "with the controls folded in")
            assertEquals(255, drawnWhite(state.pictureFilterFor(frame(1f))), "and only the controls outside a run")
        } finally {
            state.renderer.close()
        }
    }

    @Test
    fun theDimIsFoldedIntoThePictureControls() {
        assertEquals(255, drawnWhite(null))
        val half = ColorFilter.colorMatrix(ColorMatrix(dimmedColorMatrix(VideoAdjustments.Identity, 0.5f)))
        assertTrue(abs(drawnWhite(half) - 128) <= 1, "white at half is ${drawnWhite(half)}")
        // A contrast of 1.5 takes white to 1.25 before the dim, in one matrix, so it comes out 0.625.
        val contrasted = ColorFilter.colorMatrix(ColorMatrix(dimmedColorMatrix(VideoAdjustments(contrast = 1.5f), 0.5f)))
        assertTrue(abs(drawnWhite(contrasted) - 159) <= 1, "contrast then the dim is ${drawnWhite(contrasted)}")
    }
}
