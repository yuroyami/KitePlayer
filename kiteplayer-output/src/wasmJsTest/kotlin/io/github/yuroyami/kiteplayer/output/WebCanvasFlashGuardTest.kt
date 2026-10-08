@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flash guard on the web canvas (#500, `docs/video-flash-guard.md`): the stage is measured at
 * the detector's own points, and a flashing run is dimmed by black laid over the picture.
 *
 * The visible canvas is a recorder, so these tests read which black was laid and how strong, not
 * pixels. Node has no `OffscreenCanvas`, so each test installs a stand-in for the stage where the
 * environment has none.
 */
class WebCanvasFlashGuardTest {

    private class Frame(override val size: VideoSize) : VideoFrame {
        override val rotationDegrees: Int = 0
        override val hardwareSurface: HwSurfaceKind? = null
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val generation: Generation = Generation(0)
        override fun close() {}
    }

    private val size = VideoSize(640, 360)

    /** What the detector answers for a black and white strobe: 0.08 of its light. */
    private val strobeFactor = 0.08f.pow(1f / 2.2f)

    private inline fun withStage(block: () -> Unit) {
        val installed = installFlashStageIfMissing()
        try {
            block()
        } finally {
            if (installed) removeFlashStage()
        }
    }

    /** Presents [frames] pictures 100 ms apart, white and black in turn, which is a 5 Hz strobe. */
    private suspend fun strobe(renderer: WebCanvasVideoRenderer, level: IntArray, frames: Int, from: Int = 0) {
        for (i in from until from + frames) {
            level[0] = if (i % 2 == 0) 255 else 0
            renderer.flashNanos = { i * 100_000_000L }
            assertTrue(renderer.present(Frame(size), 0), "the frame must draw")
        }
    }

    @Test
    fun theStageIsMeasuredAtTheDetectorsOwnPoints() = runTest {
        withStage {
            val renderer = WebCanvasVideoRenderer(flashCanvas()) { _, destination -> paintPattern(destination, size.width); true }
            renderer.setFlashGuard(FlashGuard.On)
            assertTrue(renderer.present(Frame(size), 0))

            val rgba = ByteArray(size.width * size.height * 4)
            for (p in 0 until size.width * size.height) {
                rgba[p * 4] = (p * 7).toByte()
                rgba[p * 4 + 1] = (p * 13 + (p / size.width) * 5).toByte()
                rgba[p * 4 + 2] = (p * 3).toByte()
                rgba[p * 4 + 3] = 255.toByte()
            }
            val expected = FloatArray(VideoFlashGuard.CELLS)
            VideoFlashGuard.cellsFromRgba(rgba, size.width, size.height, into = expected)
            var spread = 0f
            for (i in expected.indices) {
                assertTrue(abs(expected[i] - renderer.flashCells[i]) < 1e-5f, "cell $i: ${renderer.flashCells[i]} against ${expected[i]}")
                spread = maxOf(spread, abs(expected[i] - expected[0]))
            }
            assertTrue(spread > 0.01f, "the pattern must differ from cell to cell, or the points are not tested")
            renderer.close()
        }
    }

    @Test
    fun aStrobeIsDimmedFromTheLegThatStartsItsRun() = runTest {
        withStage {
            val canvas = flashCanvas()
            val level = intArrayOf(0)
            val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintLevel(destination, level[0]); true }
            renderer.setFlashGuard(FlashGuard.On)

            // The first picture and six legs: three flashes, which are allowed.
            strobe(renderer, level, frames = 7)
            assertEquals(0, fillCount(canvas), "three flashes in a second are never touched")

            strobe(renderer, level, frames = 6, from = 7)
            assertEquals(6, fillCount(canvas), "every picture from the seventh leg is dimmed")
            assertTrue(abs(lastFillAlpha(canvas) - (1.0 - strobeFactor)) < 1e-4, "black at 1 - k, was ${lastFillAlpha(canvas)}")
            val leg = (1.0 - lastFillAlpha(canvas)).pow(2.2)
            assertTrue(leg < 0.10, "a dimmed leg must stay under the 0.10 that makes one, was $leg")
            assertEquals(640.0, lastFillWidth(canvas), "the black covers the picture")
            renderer.close()
        }
    }

    /**
     * The pixels themselves, on a real canvas, so only where the environment has one: Node skips.
     * A white picture of a run comes out at k of 255, which is 81.
     */
    @Test
    fun aRealCanvasShowsAStrobeDimmedWithTheGuardOnAndWholeWithItOff() = runTest {
        if (!hasRealCanvas()) return@runTest
        for (mode in listOf(FlashGuard.Off, FlashGuard.On)) {
            val canvas = realCanvas(size.width, size.height)
            val level = intArrayOf(0)
            val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintLevel(destination, level[0]); true }
            renderer.setFlashGuard(mode)
            // Thirteen pictures end on a white one, six legs into the run.
            strobe(renderer, level, frames = 13)
            val shown = centreRed(canvas)
            if (mode == FlashGuard.Off) assertEquals(255, shown) else assertTrue(abs(shown - 81) <= 2, "dimmed white was $shown")
            renderer.close()
        }
    }

    @Test
    fun aStrobeIsDrawnWholeWithTheGuardOffOrFollowingAPage() = runTest {
        withStage {
            for (mode in listOf(FlashGuard.Off, FlashGuard.FollowSystem)) {
                val canvas = flashCanvas()
                val level = intArrayOf(0)
                val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintLevel(destination, level[0]); true }
                renderer.setFlashGuard(mode)
                strobe(renderer, level, frames = 20)
                assertEquals(0, fillCount(canvas), "$mode must not dim")
                renderer.close()
            }
        }
    }

    @Test
    fun aPictureThatDoesNotFlashIsDrawnAsWithoutTheGuard() = runTest {
        withStage {
            val canvas = flashCanvas()
            val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintPattern(destination, size.width); true }
            renderer.setFlashGuard(FlashGuard.On)
            for (i in 0 until 20) {
                renderer.flashNanos = { i * 100_000_000L }
                assertTrue(renderer.present(Frame(size), 0))
            }
            assertEquals(0, fillCount(canvas))
            renderer.close()
        }
    }

    @Test
    fun aHeldPictureIsDrawnAgainAtTheFactorItWasShownWith() = runTest {
        withStage {
            val canvas = flashCanvas()
            val level = intArrayOf(0)
            val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintLevel(destination, level[0]); true }
            renderer.setFlashGuard(FlashGuard.On)
            strobe(renderer, level, frames = 10)
            val fills = fillCount(canvas)

            renderer.setViewport(width = 1280, height = 720, scale = 1f)

            assertEquals(fills + 1, fillCount(canvas), "the redraw must dim the held picture too")
            assertTrue(abs(lastFillAlpha(canvas) - (1.0 - strobeFactor)) < 1e-4)
            assertEquals(1280.0, lastFillWidth(canvas), "at the new size")
            renderer.close()
        }
    }

    @Test
    fun takingThePictureOffStartsTheHistoryAfresh() = runTest {
        withStage {
            val canvas = flashCanvas()
            val level = intArrayOf(0)
            val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> paintLevel(destination, level[0]); true }
            renderer.setFlashGuard(FlashGuard.On)
            strobe(renderer, level, frames = 10)
            val fills = fillCount(canvas)

            renderer.clearPicture()
            strobe(renderer, level, frames = 7, from = 10)

            assertEquals(fills, fillCount(canvas), "a run from before the clear must not dim the pictures after it")
            renderer.close()
        }
    }
}

@JsFun("(d, v) => { d.fill(v); }")
private external fun paintLevel(destination: JsAny, level: Int)

/** A picture that differs at every point, so a measurement at the wrong points reads differently. */
@JsFun(
    """(d, w) => {
      for (let p = 0; p * 4 < d.length; p++) {
        d[p * 4] = (p * 7) & 255; d[p * 4 + 1] = (p * 13 + Math.trunc(p / w) * 5) & 255; d[p * 4 + 2] = (p * 3) & 255; d[p * 4 + 3] = 255;
      }
    }""",
)
private external fun paintPattern(destination: JsAny, width: Int)

/** A canvas whose 2d context records each black laid over the picture: its alpha and its width. */
@JsFun(
    """() => {
      const canvas = { width: 640, height: 360, fills: [] };
      const ctx = {
        globalAlpha: 1, fillStyle: '',
        setTransform() {}, clearRect() {}, translate() {}, rotate() {}, scale() {}, putImageData() {}, drawImage() {},
        fillRect(x, y, w, h) { canvas.fills.push({ alpha: ctx.globalAlpha, w: w, style: ctx.fillStyle }); },
        createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
      };
      canvas.getContext = () => ctx;
      return canvas;
    }""",
)
private external fun flashCanvas(): JsAny

@JsFun("(c) => c.fills.length")
private external fun fillCount(canvas: JsAny): Int

@JsFun("(c) => c.fills.length ? c.fills[c.fills.length - 1].alpha : -1")
private external fun lastFillAlpha(canvas: JsAny): Double

@JsFun("(c) => c.fills.length ? c.fills[c.fills.length - 1].w : -1")
private external fun lastFillWidth(canvas: JsAny): Double

@JsFun(
    """() => {
      if (typeof OffscreenCanvas !== 'undefined') return false;
      globalThis.OffscreenCanvas = class {
        constructor(w, h) { this.width = w; this.height = h; }
        getContext() {
          return { putImageData() {}, createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }) };
        }
      };
      return true;
    }""",
)
private external fun installFlashStageIfMissing(): Boolean

@JsFun("() => { delete globalThis.OffscreenCanvas; }")
private external fun removeFlashStage()

@JsFun("() => typeof OffscreenCanvas !== 'undefined' && !!new OffscreenCanvas(1, 1).getContext('2d')")
private external fun hasRealCanvas(): Boolean

@JsFun("(w, h) => new OffscreenCanvas(w, h)")
private external fun realCanvas(width: Int, height: Int): JsAny

@JsFun("(c) => c.getContext('2d').getImageData(c.width >> 1, c.height >> 1, 1, 1).data[0]")
private external fun centreRed(canvas: JsAny): Int
