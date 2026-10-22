@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The web's view into a 360 degree picture, drawn by [WebGlVideoPass] in the browser's WebGL2
 * (#460). It is held to [SphericalLens], the law the Compose canvas path is held to on the desktop,
 * so the two draw the same views; the band checks lean on the picture alone.
 */
class WebSphereViewTest {

    private val whole = VideoProjection(ProjectionKind.Equirectangular)
    private val tile = VideoProjection(ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0))

    @Test
    fun eachViewDrawsWhatTheLensReads() {
        val views = listOf(
            SphericalView(),
            SphericalView(yaw = 90f, pitch = 30f),
            SphericalView(yaw = -135f, pitch = -45f, roll = 20f, fieldOfView = 100f),
            SphericalView(pitch = 90f, fieldOfView = 120f),
            SphericalView(yaw = 180f, fieldOfView = 30f),
        )
        for (view in views) {
            assertMatchesLens(whole, view, 160, 90)
            assertMatchesLens(whole, view, 90, 160)
        }
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0, pitch = 10.0, roll = -5.0)
        assertMatchesLens(posed, SphericalView(yaw = 200f, pitch = -20f, roll = 10f), 160, 90)
        assertMatchesLens(tile, SphericalView(), 160, 90)
        assertMatchesLens(tile, SphericalView(yaw = 90f), 160, 90)
    }

    @Test
    fun eachViewLooksWhereItsTurnsPointIt() {
        val centre = mapOf(
            SphericalView() to RED,
            SphericalView(yaw = 90f) to GREEN,
            SphericalView(yaw = -90f) to WHITE,
            SphericalView(yaw = 45f) to YELLOW,
            SphericalView(yaw = -45f) to ORANGE,
            SphericalView(yaw = 180f) to BLUE,
            SphericalView(pitch = 90f) to TOP,
            SphericalView(pitch = -90f) to BOTTOM,
        )
        for ((view, colour) in centre) {
            assertEquals(hex(colour), hex(draw(whole, view, 160, 90).at(80, 45)), "the centre of $view")
        }
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0)
        assertEquals(hex(RED), hex(draw(posed, SphericalView(yaw = 30f), 160, 90).at(80, 45)), "the file's pose")
        assertEquals(hex(ORANGE), hex(draw(posed, SphericalView(), 160, 90).at(80, 45)), "where the file starts the view")
        val rolled = draw(whole, SphericalView(roll = 90f, fieldOfView = 120f), 160, 90)
        assertEquals(hex(BOTTOM), hex(rolled.at(158, 45)), "the right edge of a view rolled clockwise")
        assertEquals(hex(TOP), hex(rolled.at(1, 45)), "the left edge of a view rolled clockwise")
    }

    @Test
    fun aTileShowsItsPartAndTheBackgroundBeyondIt() {
        assertEquals(hex(RED), hex(draw(tile, SphericalView(), 160, 90).at(80, 45)))
        assertEquals(hex(GREEN), hex(draw(tile, SphericalView(yaw = 80f), 160, 90).at(80, 45)))
        val behind = draw(tile, SphericalView(yaw = 180f), 160, 90)
        assertTrue(behind.pixels.all { it ushr 24 == 0 }, "the view behind a tile drew a picture it does not have")
    }

    /** The renderer hands the pass a sphere only for a view it draws into a picture it draws. */
    @Test
    fun theRendererHandsThePassASphereOnlyForAViewItDraws() {
        val renderer = WebKiteVideoRenderer(WebFramePaint(paint = { _, _ -> false }))
        try {
            assertTrue(renderer.projects(ProjectionKind.Equirectangular))
            assertTrue(renderer.projects(ProjectionKind.EquirectangularTile(0.25, 0.0, 0.25, 0.0)))
            assertEquals(false, renderer.projects(ProjectionKind.Cubemap(0)))
            assertEquals(false, renderer.projects(ProjectionKind.Other("fisheye")))
            val view = SphericalView(yaw = 10f)
            renderer.setProjection(whole, view)
            assertEquals(SphereView(whole, view), renderer.look.sphere)
            renderer.setProjection(whole, view.copy(projected = false))
            assertEquals(null, renderer.look.sphere, "a view switched to the flat picture")
            renderer.setProjection(VideoProjection(ProjectionKind.Cubemap(0)), view)
            assertEquals(null, renderer.look.sphere, "a cube map, which is drawn flat")
            renderer.setProjection(tile, view)
            assertEquals(SphereView(tile, view), renderer.look.sphere)
            renderer.setProjection(null, view)
            assertEquals(null, renderer.look.sphere, "a flat video")
        } finally {
            renderer.close()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private class Drawn(val width: Int, val height: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * width + x]
    }

    /** Draws [view] into a WebGL2 canvas [width] by [height] through the pass, and reads it back, top row first. */
    private fun draw(projection: VideoProjection, view: SphericalView, width: Int, height: Int): Drawn {
        val gl = glContext(width, height) ?: fail("this browser gives no WebGL2 context, so the web pass cannot be checked")
        val pass = WebGlVideoPass(gl)
        try {
            pass.failure?.let { fail("the web pass could not start: $it") }
            val tiled = projection.kind is ProjectionKind.EquirectangularTile
            val pictureWidth = if (tiled) 256 else 512
            val picture = WebStagedPicture(
                generation = 1,
                pixels = paintBands(pictureWidth, 256, if (tiled) 128 else 0),
                size = VideoSize(pictureWidth, 256),
                rotationDegrees = 0,
                mirrored = false,
                crop = null,
                dim = 1f,
            )
            val look = WebLook(VideoScale.Fit, VideoTransform.Identity, VideoAdjustments.Identity, 0, SphereView(projection, view))
            pass.draw(width, height, picture, null, look)
            val read = glRead(gl, width, height)
            // GL's rows run from the bottom up.
            return Drawn(width, height, IntArray(width * height) { index -> glPixel(read, (height - 1 - index / width) * width + index % width) })
        } finally {
            pass.release()
            glDone(gl)
        }
    }

    private fun assertMatchesLens(projection: VideoProjection, view: SphericalView, width: Int, height: Int) {
        val drawn = draw(projection, view, width, height)
        val lens = SphericalLens(projection, view, width.toFloat(), height.toFloat())
        val tiled = projection.kind is ProjectionKind.EquirectangularTile
        var compared = 0
        val failures = mutableListOf<String>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val actual = drawn.at(x, y)
                val point = lens.pictureAt(x + 0.5, y + 0.5)
                if (point == null) {
                    compared++
                    if (actual ushr 24 != 0) failures += "($x, $y) drew ${hex(actual)} beyond the picture"
                    continue
                }
                val pictureWidth = if (tiled) 256 else 512
                val expected = flatAround(point.across * pictureWidth, point.down * 256, pictureWidth, if (tiled) 128 else 0, lens.wraps)
                    ?: continue
                compared++
                if (!close(actual, expected)) failures += "($x, $y) drew ${hex(actual)} where the lens reads ${hex(expected)}"
            }
        }
        assertTrue(compared > width * height * 3 / 4, "too few pixels to compare for $view: $compared of ${width * height}")
        assertEquals(emptyList(), failures.take(5), "$view on $width by $height, ${failures.size} pixels wrong")
    }

    private fun close(actual: Int, expected: Int): Boolean {
        if (actual ushr 24 != 0xFF) return false
        return (0..2).all { channel -> abs((actual shr (8 * channel) and 0xFF) - (expected shr (8 * channel) and 0xFF)) <= 3 }
    }

    private fun hex(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    private companion object {
        const val BLUE: Int = 0xFF0000FF.toInt()
        const val MAGENTA: Int = 0xFFFF00FF.toInt()
        const val WHITE: Int = 0xFFFFFFFF.toInt()
        const val ORANGE: Int = 0xFFFF8000.toInt()
        const val RED: Int = 0xFFFF0000.toInt()
        const val YELLOW: Int = 0xFFFFFF00.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val CYAN: Int = 0xFF00FFFF.toInt()
        const val TOP: Int = 0xFF303030.toInt()
        const val BOTTOM: Int = 0xFF603000.toInt()
        val BANDS = intArrayOf(BLUE, MAGENTA, WHITE, ORANGE, RED, YELLOW, GREEN, CYAN, BLUE)

        /**
         * The colour at ([x], [y]) of the whole sphere's 512 by 256 picture: the layout
         * `scripts/testmedia.sh` encodes and [paintBands] paints, with alpha.
         */
        fun colourAt(x: Int, y: Int): Int = when {
            y < 32 -> TOP
            y >= 224 -> BOTTOM
            else -> BANDS[(x + 32) / 64]
        }

        /**
         * The colour at ([x], [y]) of a picture [width] wide that starts [offset] columns into the
         * whole sphere's, when every pixel within two of it is that colour, or null on an edge.
         */
        fun flatAround(x: Double, y: Double, width: Int, offset: Int, wraps: Boolean): Int? {
            var found: Int? = null
            for (dy in -2..2) {
                for (dx in -2..2) {
                    var column = floor(x).toInt() + dx
                    val row = floor(y).toInt() + dy
                    if (row !in 0 until 256) continue
                    if (wraps) column = ((column % width) + width) % width else if (column !in 0 until width) continue
                    val colour = colourAt(column + offset, row)
                    if (found == null) found = colour else if (found != colour) return null
                }
            }
            return found
        }
    }
}

/** A WebGL2 context on a new canvas [width] by [height], or null where the browser gives none. */
@JsFun(
    """(w, h) => {
      if (typeof OffscreenCanvas === 'undefined') return null;
      const gl = new OffscreenCanvas(w, h).getContext('webgl2', { antialias: false, premultipliedAlpha: true });
      return gl || null;
    }""",
)
private external fun glContext(width: Int, height: Int): JsAny?

/**
 * The bands of the whole sphere's picture, painted in JavaScript, one call rather than one per
 * byte: [width] columns by [height] rows, starting [offset] columns into the 512 wide whole.
 */
@JsFun(
    """(w, h, offset) => {
      const bands = [0x0000FF, 0xFF00FF, 0xFFFFFF, 0xFF8000, 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF];
      const pixels = new Uint8Array(w * h * 4);
      for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
          const c = y < 32 ? 0x303030 : (y >= 224 ? 0x603000 : bands[(x + offset + 32) >> 6]);
          const i = (y * w + x) * 4;
          pixels[i] = c >> 16; pixels[i + 1] = (c >> 8) & 255; pixels[i + 2] = c & 255; pixels[i + 3] = 255;
        }
      }
      return pixels;
    }""",
)
private external fun paintBands(width: Int, height: Int, offset: Int): JsAny

/** The context's drawing as RGBA bytes, bottom row first. */
@JsFun(
    """(gl, w, h) => {
      const pixels = new Uint8Array(w * h * 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      return pixels;
    }""",
)
private external fun glRead(gl: JsAny, width: Int, height: Int): JsAny

/** One pixel of [glRead]'s bytes as ARGB. */
@JsFun("(p, i) => ((p[i * 4 + 3] << 24) | (p[i * 4] << 16) | (p[i * 4 + 1] << 8) | p[i * 4 + 2]) | 0")
private external fun glPixel(pixels: JsAny, index: Int): Int

/** Lets the context go now rather than when the page collects it, as a browser keeps only a few. */
@JsFun("(gl) => { const lose = gl.getExtension('WEBGL_lose_context'); if (lose) lose.loseContext(); }")
private external fun glDone(gl: JsAny)
