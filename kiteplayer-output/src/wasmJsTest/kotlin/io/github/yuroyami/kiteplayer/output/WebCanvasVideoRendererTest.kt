@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The renderer's OWNERSHIP contract, which is the half a browser cannot check for us.
 *
 * Rule 2 of `VideoRenderer` says the renderer owns the frame from the moment `present` is called,
 * INCLUDING when it fails, and closes it exactly once. That rule is why this file exists: `present`
 * has seven ways to refuse a frame, every one of them must still close it, and a leak there is
 * invisible in a demo and fatal in a session, because each leaked frame holds decoder memory that
 * is 3.11 MB at 1080p and 24.9 MB at 4K.
 *
 * These tests run in BOTH environments this module declares, node and a headless browser, and
 * that is the whole reason the assertions below read the environment instead of assuming it. In
 * node there is no `OffscreenCanvas`, no `document` and no `ImageData`, so every path is a REFUSAL
 * path; in a browser those globals are real, so the same call draws. Refusal is where ownership
 * bugs hide, which is why most of this file lives there, but the ownership rule is the same on both
 * sides and is asserted on both. Nothing here claims a PIXEL was correct: that needs a real browser
 * comparison and belongs to the conformance run in a real browser.
 */
class WebCanvasVideoRendererTest {

    private val painter = WebFramePainter { _, _ -> true }

    /** Counts its own closes, which is the whole assertion in most of this file. */
    private class CountingFrame(
        override val size: VideoSize = VideoSize(640, 480),
        override val rotationDegrees: Int = 0,
        override val hardwareSurface: HwSurfaceKind? = null,
    ) : VideoFrame {
        var closes: Int = 0
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val generation: Generation = Generation(0)
        override fun close() {
            closes++
        }
    }

    /** Not a canvas at all, so the renderer has no context and refuses at the first gate. */
    @Test
    fun aFrameIsClosedEvenWhenThereIsNoCanvasToDrawOn() = runTest {
        val renderer = WebCanvasVideoRenderer(notACanvas(), painter)
        val frame = CountingFrame()
        assertFalse(renderer.present(frame, 0), "nothing can be drawn without a 2d context")
        assertEquals(1, frame.closes, "a refused frame must still be closed, exactly once")
        renderer.close()
    }

    @Test
    fun supportsIsFalseWithoutATwoDimensionalContext() {
        val renderer = WebCanvasVideoRenderer(notACanvas(), painter)
        assertFalse(
            renderer.supports(PlayerPixelFormat.Yuv420p),
            "a renderer with no context must say so at attach rather than per frame",
        )
        renderer.close()
    }

    @Test
    fun supportsIsTrueOnceThereIsAContext() {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        assertTrue(renderer.supports(PlayerPixelFormat.Yuv420p))
        renderer.close()
    }

    /**
     * Opaque is the engine's name for a frame that lives in hardware memory, and this renderer is
     * software by construction. It used to answer true for every format including that one, so a
     * mis-wired decoder was refused once per frame by `present` instead of once at attach, which is
     * what the KDoc promised all along (audit S-W5).
     */
    @Test
    fun supportsIsFalseForOpaqueEvenWithAContext() {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        assertFalse(
            renderer.supports(PlayerPixelFormat.Opaque),
            "a software renderer must refuse a hardware frame at attach, not per frame",
        )
        renderer.close()
    }

    /**
     * The frame is closed exactly once whichever way the stage goes, and the counters agree with
     * the answer.
     *
     * This used to assert a flat `assertFalse` and it was RIGHT ONLY IN NODE. The renderer builds
     * its offscreen stage from `OffscreenCanvas` or `document`, both of which a browser really has,
     * so the same call that refuses under node draws under `wasmJsBrowserTest` and the test failed
     * there. It had never run there. The fix is not to pick an environment: it is to state the rule
     * that holds in both, which is that the answer follows the stage and the frame is owned either
     * way.
     */
    @Test
    fun aFrameIsClosedExactlyOnceWhicheverWayTheStageGoes() = runTest {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        val frame = CountingFrame()
        val drawn = renderer.present(frame, 0)
        assertEquals(stageIsBuildable(), drawn, "the environment decides the stage, and the stage decides the answer")
        assertEquals(1, frame.closes, "a frame must be closed exactly once, drawn or refused")
        assertEquals(if (drawn) 1 else 0, renderer.presentedFrames)
        assertEquals(if (drawn) 0 else 1, renderer.failedFrames, "a refusal must be counted, not silent")
        renderer.close()
    }

    /**
     * Nothing on the web produces a hardware frame for this renderer, so one arriving means a
     * mis-wired decoder. Refused and closed rather than drawn as garbage.
     */
    @Test
    fun aHardwareFrameIsRefusedAndStillClosed() = runTest {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        val frame = CountingFrame(hardwareSurface = HwSurfaceKind.CoreVideoPixelBuffer)
        assertFalse(renderer.present(frame, 0))
        assertEquals(1, frame.closes)
        assertEquals(1, renderer.failedFrames)
        renderer.close()
    }

    @Test
    fun aFrameWithNoPictureIsRefusedAndStillClosed() = runTest {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        val frame = CountingFrame(size = VideoSize(0, 0))
        assertFalse(renderer.present(frame, 0))
        assertEquals(1, frame.closes)
        assertEquals(1, renderer.failedFrames)
        renderer.close()
    }

    /** A closed renderer still owns anything handed to it afterwards. */
    @Test
    fun aFrameArrivingAfterCloseIsStillClosed() = runTest {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        renderer.close()
        val frame = CountingFrame()
        assertFalse(renderer.present(frame, 0))
        assertEquals(1, frame.closes)
    }

    @Test
    fun closeIsIdempotent() {
        val renderer = WebCanvasVideoRenderer(fakeCanvas(), painter)
        renderer.close()
        renderer.close()
    }

    /** A painter that refuses is a drop, not a crash, and the frame is still this renderer's. */
    @Test
    fun aPainterThatRefusesCostsTheFrameAndNothingElse() = runTest {
        val renderer = WebCanvasVideoRenderer(fakeCanvas()) { _, _ -> false }
        val frame = CountingFrame()
        assertFalse(renderer.present(frame, 0))
        assertEquals(1, frame.closes)
        assertEquals(1, renderer.failedFrames)
        renderer.close()
    }

    /** The viewport is the canvas's BACKING STORE, scaled by the device pixel ratio. */
    @Test
    fun setViewportSizesTheBackingStoreByTheDevicePixelRatio() {
        val canvas = fakeCanvas()
        val renderer = WebCanvasVideoRenderer(canvas, painter)
        renderer.setViewport(width = 800, height = 450, scale = 2f)
        assertEquals(1600, canvasWidthOf(canvas), "a HiDPI canvas must get the pixels it has")
        assertEquals(900, canvasHeightOf(canvas))
        renderer.close()
    }
}

@JsFun("() => ({ notACanvas: true })")
private external fun notACanvas(): JsAny

/**
 * Whether this environment lets the renderer build its offscreen stage.
 *
 * The same two globals `ensureStage` tries, in the same order, so the test cannot disagree with the
 * code about what the environment offers. A browser has both, node has neither.
 */
@JsFun("() => typeof OffscreenCanvas !== 'undefined' || typeof document !== 'undefined'")
private external fun stageIsBuildable(): Boolean

/**
 * A canvas whose 2d context accepts every call and remembers nothing.
 *
 * Enough for the renderer to build its state and reach the paths above. It deliberately does NOT
 * fake `OffscreenCanvas` or `document`: those are global, faking them would make this test claim a
 * drawing path it cannot actually verify, and the drawing itself belongs to a real browser.
 */
@JsFun(
    """() => {
      const ctx = {
        setTransform() {}, clearRect() {}, translate() {}, rotate() {}, drawImage() {}, putImageData() {},
        createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
      };
      return { width: 640, height: 360, getContext: () => ctx };
    }""",
)
private external fun fakeCanvas(): JsAny

@JsFun("(c) => c.width")
private external fun canvasWidthOf(canvas: JsAny): Int

@JsFun("(c) => c.height")
private external fun canvasHeightOf(canvas: JsAny): Int
