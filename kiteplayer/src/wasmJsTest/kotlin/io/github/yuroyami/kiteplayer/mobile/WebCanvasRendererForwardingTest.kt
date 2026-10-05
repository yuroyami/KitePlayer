@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player's web renderer hands every call to the plain canvas renderer. When it named them one
 * by one, a call it did not name fell to the interface's default and never reached the canvas.
 */
class WebCanvasRendererForwardingTest {

    /** The factory builds without suspending, and this module's tests have no coroutine test tools. */
    private fun rendererOn(canvas: JsAny): VideoRenderer {
        var made: Result<VideoRenderer>? = null
        suspend { WebCanvasRendererFactory(canvas).create() }
            .startCoroutine(Continuation(EmptyCoroutineContext) { made = it })
        return checkNotNull(made) { "the factory suspended" }.getOrThrow()
    }

    @Test
    fun aClearReachesTheCanvas() {
        val canvas = clearCountingCanvas()
        val renderer = rendererOn(canvas)
        renderer.clearPicture()
        assertEquals(1, clearsOf(canvas), "the picture was taken off the canvas (#530)")
        renderer.close()
    }

    @Test
    fun theOutputSizeIsTheCanvasSoSubtitlesAreLaidOutForIt() {
        val renderer = rendererOn(clearCountingCanvas())
        assertEquals(VideoSize(640, 360), renderer.outputSize, "the canvas's own size before any viewport (#535)")
        renderer.setViewport(800, 450, 2f)
        assertEquals(VideoSize(1600, 900), renderer.outputSize, "the viewport in device pixels")
        renderer.close()
    }
}

/** A canvas whose 2d context accepts every call and counts its clears. */
@JsFun(
    """() => {
      const canvas = { width: 640, height: 360, clears: 0 };
      const ctx = {
        setTransform() {}, clearRect() { canvas.clears++; }, translate() {}, rotate() {}, scale() {},
        drawImage() {}, putImageData() {},
        createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
      };
      canvas.getContext = () => ctx;
      return canvas;
    }""",
)
private external fun clearCountingCanvas(): JsAny

@JsFun("(c) => c.clears")
private external fun clearsOf(canvas: JsAny): Int
