@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player's web renderer hands every call to the plain canvas renderer by name, so a call it
 * does not name falls to the interface's default and never reaches the canvas.
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
