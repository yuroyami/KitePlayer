@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.JsAny
import kotlin.test.assertTrue

/**
 * The placement contract on the web canvas renderer, through a canvas that records each draw.
 *
 * The recording canvas keeps the 2d context's transform, so each recorded rectangle is where an
 * image landed on the canvas, turned or not. Each overlay image is found by its colour. In node
 * the test gives the renderer a stage canvas that keeps its pixels, because node has no
 * `OffscreenCanvas`; a browser uses its own.
 */
class WebCanvasOverlayPlacementTest : OverlayPlacementContractTest() {

    private val paintPicture = WebFramePainter { _, destination ->
        fillOpaque(destination, pictureColor)
        true
    }

    override fun reportedOutput(scene: Scene): VideoSize? {
        val renderer = WebCanvasVideoRenderer(placementCanvas(), paintPicture)
        return try {
            renderer.setViewport(scene.outputWidth, scene.outputHeight, 1f)
            renderer.outputSize
        } finally {
            renderer.close()
        }
    }

    override fun compose(scene: Scene, overlay: SubtitleOverlay): Composite {
        val installed = installPixelStageIfMissing()
        try {
            val canvas = placementCanvas()
            val renderer = WebCanvasVideoRenderer(canvas, paintPicture)
            try {
                renderer.setViewport(scene.outputWidth, scene.outputHeight, 1f)
                renderer.setScaleMode(scene.scale)
                renderer.setTransform(scene.transform)
                val drawn = runNow {
                    renderer.setOverlay(overlay)
                    renderer.present(SceneFrame(scene.picture, scene.rotationDegrees), 0L)
                }
                assertTrue(drawn, "the renderer refused the scene's picture")
                val boxes = overlay.images.indices.map { index ->
                    val draw = lastDrawIn(canvas, markerColor(index))
                    if (draw < 0) {
                        null
                    } else {
                        Box(
                            left = drawLeft(canvas, draw).toFloat(),
                            top = drawTop(canvas, draw).toFloat(),
                            width = drawWidth(canvas, draw).toFloat(),
                            height = drawHeight(canvas, draw).toFloat(),
                        )
                    }
                }
                return Composite(placementCanvasWidth(canvas), placementCanvasHeight(canvas), boxes)
            } finally {
                renderer.close()
            }
        } finally {
            if (installed) removePixelStage()
        }
    }

    private class SceneFrame(
        override val size: VideoSize,
        override val rotationDegrees: Int,
    ) : VideoFrame {
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation(0)
        override fun close() = Unit
    }
}

/**
 * Runs [block] to its end on the calling thread. The renderer's suspend calls never suspend, and
 * the contract's tests are not suspend functions, so there is nothing to wait for.
 */
private fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the call suspended, and this test cannot wait for it" }.getOrThrow()
}

@JsFun(
    """(d, rgb) => {
      for (let i = 0; i < d.length; i += 4) {
        d[i] = (rgb >> 16) & 255; d[i + 1] = (rgb >> 8) & 255; d[i + 2] = rgb & 255; d[i + 3] = 255;
      }
    }""",
)
private external fun fillOpaque(destination: JsAny, rgb: Int)

/**
 * A canvas whose 2d context records each `drawImage`: the source, and the rectangle it covered on
 * the canvas after the context's transform. The renderer clears the whole canvas before each
 * frame, so a clear forgets what was drawn before.
 */
@JsFun(
    """() => {
      const canvas = { width: 300, height: 150, draws: [] };
      let m = [1, 0, 0, 1, 0, 0];
      const times = (a, b) => [
        a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1],
        a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5],
      ];
      const ctx = {
        setTransform(a, b, c, d, e, f) { m = [a, b, c, d, e, f]; },
        translate(x, y) { m = times(m, [1, 0, 0, 1, x, y]); },
        rotate(r) { const c = Math.cos(r), s = Math.sin(r); m = times(m, [c, s, -s, c, 0, 0]); },
        scale(x, y) { m = times(m, [x, 0, 0, y, 0, 0]); },
        clearRect() { canvas.draws = []; },
        putImageData() {},
        createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
        drawImage(image, x, y, w, h) {
          if (w === undefined) { w = image.width; h = image.height; }
          const xs = [], ys = [];
          for (const [px, py] of [[x, y], [x + w, y], [x, y + h], [x + w, y + h]]) {
            xs.push(m[0] * px + m[2] * py + m[4]);
            ys.push(m[1] * px + m[3] * py + m[5]);
          }
          const left = Math.min(...xs), top = Math.min(...ys);
          canvas.draws.push({ image: image, left: left, top: top, width: Math.max(...xs) - left, height: Math.max(...ys) - top });
        },
      };
      canvas.getContext = () => ctx;
      return canvas;
    }""",
)
private external fun placementCanvas(): JsAny

@JsFun("(c) => c.width")
private external fun placementCanvasWidth(canvas: JsAny): Int

@JsFun("(c) => c.height")
private external fun placementCanvasHeight(canvas: JsAny): Int

/**
 * The index of the last draw whose source is the colour `0xRRGGBB` at its first pixel, or -1. The
 * node stage keeps its pixels; a browser canvas is read back.
 */
@JsFun(
    """(c, rgb) => {
      const colorOf = (image) => {
        let d = image.pixels;
        if (!d && typeof image.getContext === 'function') d = image.getContext('2d').getImageData(0, 0, 1, 1).data;
        return (d && d.length >= 3) ? ((d[0] << 16) | (d[1] << 8) | d[2]) : -1;
      };
      for (let i = c.draws.length - 1; i >= 0; i--) { if (colorOf(c.draws[i].image) === rgb) return i; }
      return -1;
    }""",
)
private external fun lastDrawIn(canvas: JsAny, rgb: Int): Int

@JsFun("(c, i) => c.draws[i].left")
private external fun drawLeft(canvas: JsAny, index: Int): Double

@JsFun("(c, i) => c.draws[i].top")
private external fun drawTop(canvas: JsAny, index: Int): Double

@JsFun("(c, i) => c.draws[i].width")
private external fun drawWidth(canvas: JsAny, index: Int): Double

@JsFun("(c, i) => c.draws[i].height")
private external fun drawHeight(canvas: JsAny, index: Int): Double

/**
 * Gives node a stage canvas that keeps the last pixels written into it, and answers whether it
 * did. A browser has a real `OffscreenCanvas`, which is left alone.
 */
@JsFun(
    """() => {
      if (typeof OffscreenCanvas !== 'undefined') return false;
      globalThis.OffscreenCanvas = class {
        constructor(w, h) { this.width = w; this.height = h; this.pixels = null; }
        getContext() {
          const self = this;
          return {
            putImageData(image) { self.pixels = image.data; },
            createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
          };
        }
      };
      return true;
    }""",
)
private external fun installPixelStageIfMissing(): Boolean

@JsFun("() => { delete globalThis.OffscreenCanvas; }")
private external fun removePixelStage()
