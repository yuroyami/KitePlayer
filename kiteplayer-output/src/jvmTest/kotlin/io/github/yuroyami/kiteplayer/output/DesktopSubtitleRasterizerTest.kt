package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AWT rasterizer proved with real text: a cue becomes pixels, the pixels obey [RgbaBitmap]'s
 * PREMULTIPLIED contract, and the placement lands where the Android and Apple arithmetic puts it.
 * Placement equality across backends is BY CONSTRUCTION (the same code, mirrored); these arms
 * hold the desktop half to it.
 */
class DesktopSubtitleRasterizerTest {

    private fun cue(
        text: String,
        alignment: CueAlignment = CueAlignment.BottomCenter,
        style: CueStyle = CueStyle(),
        layout: CueLayout = CueLayout(alignment = alignment),
    ) = SubtitleCue.Text(
        startMicros = 0,
        endMicros = 1_000_000,
        spans = listOf(StyledSpan(text, style)),
        layout = layout,
    )

    private fun rasterize(
        vararg cues: SubtitleCue,
        width: Int = 640,
        height: Int = 360,
        fontScale: Float = 1f,
        position: Float = 1f,
    ) = DesktopSubtitleRasterizer().rasterize(cues.toList(), width, height, fontScale, position)

    // ── the alpha contract (SOL-S1, audit F-ALPHA1) ─────────────────────────────────────────

    /**
     * [RgbaBitmap] says PREMULTIPLIED, and both shipped rasterizers produce exactly that (Android
     * copies an ARGB_8888 bitmap, Apple draws into a kCGImageAlphaPremultipliedLast context).
     * Half-transparent white therefore lands as (128, 128, 128, 128) and NOT (255, 255, 255, 128):
     * a straight-alpha producer here would be premultiplied a second time downstream and the text
     * would go grey.
     */
    @Test
    fun `a half transparent cue emits premultiplied pixels, not straight ones`() {
        val image = rasterize(
            cue(
                "MMMM",
                style = CueStyle(fontSizePx = 96f, primaryColor = 0x80FFFFFF.toInt(), outlineWidthPx = 0f),
            ),
        ).single()
        val pixels = image.bitmap.pixels
        var strongest = 0
        var strongestIndex = -1
        for (index in pixels.indices step 4) {
            val a = pixels[index + 3].toInt() and 0xFF
            if (a > strongest) { strongest = a; strongestIndex = index }
        }
        assertTrue(strongestIndex >= 0 && strongest > 100, "the cue did not draw (peak alpha $strongest)")
        val r = pixels[strongestIndex].toInt() and 0xFF
        val g = pixels[strongestIndex + 1].toInt() and 0xFF
        val b = pixels[strongestIndex + 2].toInt() and 0xFF
        assertTrue(strongest in 120..136, "50% white must keep its alpha, got $strongest")
        assertTrue(
            r <= strongest + 2 && g <= strongest + 2 && b <= strongest + 2,
            "premultiplied means colour can never exceed alpha; got r=$r g=$g b=$b a=$strongest",
        )
        assertTrue(r >= strongest - 4, "and white at 50% must reach its alpha; got r=$r a=$strongest")
    }

    @Test
    fun `an opaque cue keeps its full colour`() {
        val image = rasterize(
            cue(
                "MMMM",
                style = CueStyle(fontSizePx = 96f, primaryColor = 0xFFFFFFFF.toInt(), outlineWidthPx = 0f),
            ),
        ).single()
        val pixels = image.bitmap.pixels
        var opaqueWhite = 0
        for (index in pixels.indices step 4) {
            val r = pixels[index].toInt() and 0xFF
            val a = pixels[index + 3].toInt() and 0xFF
            if (a == 255 && r == 255) opaqueWhite++
        }
        assertTrue(opaqueWhite > 20, "opaque white text must stay 255 at full alpha, found $opaqueWhite pixels")
    }

    @Test
    fun `no pixel ever carries more colour than alpha`() {
        val image = rasterize(
            cue("Hello from KitePlayer", style = CueStyle(primaryColor = 0xFFFFFFFF.toInt())),
        ).single()
        val pixels = image.bitmap.pixels
        for (index in pixels.indices step 4) {
            val a = pixels[index + 3].toInt() and 0xFF
            val r = pixels[index].toInt() and 0xFF
            val g = pixels[index + 1].toInt() and 0xFF
            val b = pixels[index + 2].toInt() and 0xFF
            assertTrue(
                r <= a && g <= a && b <= a,
                "pixel ${index / 4} is r=$r g=$g b=$b over a=$a; that is straight alpha, not premultiplied",
            )
        }
    }

    // ── geometry ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cue becomes pixels at the bottom centre of the safe area`() {
        val image = rasterize(cue("Hello from KitePlayer")).single()
        assertTrue(image.bitmap.width > 0 && image.bitmap.height > 0, "the cue rasterised to nothing")

        var drawn = 0
        val pixels = image.bitmap.pixels
        for (index in 3 until pixels.size step 4) {
            if ((pixels[index].toInt() and 0xFF) > 0) drawn++
        }
        assertTrue(drawn > 50, "the bitmap has $drawn non-transparent pixels; text did not draw")

        // The safe width is the viewport minus both margins, exactly like Android's StaticLayout.
        assertEquals(576, image.bitmap.width, "an unpositioned cue's bitmap is the whole safe width")
        // Bottom centre, the Android arithmetic verbatim: y = height - margin - imageHeight.
        assertEquals(360 - (360 * 0.05f).toInt() - image.bitmap.height, image.y, "not at the bottom margin")
        assertEquals((640 - image.bitmap.width) / 2, image.x, "not horizontally centred")
        assertEquals(image.bitmap.width * image.bitmap.height * 4, image.bitmap.pixels.size, "RGBA8888, no padding")
    }

    @Test
    fun `long text wraps inside the safe width instead of overflowing it`() {
        val one = rasterize(cue("Hello")).single()
        val many = rasterize(
            cue("Hello from KitePlayer, this line is long enough that AWT has to break it into several lines"),
        ).single()
        assertEquals(576, many.bitmap.width, "wrapping must never widen the bitmap past the safe width")
        assertTrue(many.bitmap.height > one.bitmap.height, "a wrapped cue must be taller than a single line")
    }

    @Test
    fun `two bottom cues stack upward`() {
        val images = rasterize(cue("first"), cue("second"))
        assertEquals(2, images.size)
        assertTrue(
            images[1].y + images[1].bitmap.height <= images[0].y,
            "the second cue (y=${images[1].y}) does not stack above the first (y=${images[0].y})",
        )
    }

    @Test
    fun `the sub position lifts the implicit stack by the viewport fraction`() {
        val bottom = rasterize(cue("line")).single()
        val lifted = rasterize(cue("line"), position = 0.5f).single()
        assertEquals(bottom.y - 180, lifted.y, "sub-pos 0.5 must lift the stack by half the height")
        assertEquals(bottom.x, lifted.x, "and never touch the horizontal")
    }

    @Test
    fun `a top aligned cue sits at the top margin and a middle one is centred`() {
        val top = rasterize(cue("top", alignment = CueAlignment.TopCenter)).single()
        assertEquals((360 * 0.05f).toInt(), top.y)
        val middle = rasterize(cue("mid", alignment = CueAlignment.MiddleCenter)).single()
        assertEquals((360 - middle.bitmap.height) / 2, middle.y)
        val left = rasterize(cue("left", alignment = CueAlignment.BottomLeft)).single()
        assertEquals((640 * 0.05f).toInt(), left.x)
    }

    // Audit F-POS1: an authored \pos is an ANCHOR oriented by the alignment, and the bitmap of a
    // positioned cue is its own text extent, not the whole safe width.
    @Test
    fun `a positioned cue's bitmap is its text extent, anchored on the authored point`() {
        val image = rasterize(
            cue(
                "pos",
                layout = CueLayout(
                    alignment = CueAlignment.BottomCenter,
                    positionX = 0.5f,
                    positionY = 0.5f,
                ),
            ),
        ).single()
        assertTrue(image.bitmap.width < 576, "a positioned cue must not carry the whole safe width")
        assertEquals(320 - image.bitmap.width / 2, image.x, "an \\an2 anchor centres the extent on the point")
        assertEquals(180 - image.bitmap.height, image.y, "an \\an2 anchor puts the BOTTOM of the text on the point")
    }

    @Test
    fun `the outline colour appears beside the fill`() {
        val image = rasterize(
            cue(
                "O",
                style = CueStyle(
                    fontSizePx = 96f,
                    primaryColor = 0xFFFF0000.toInt(),
                    outlineColor = 0xFF00FF00.toInt(),
                    outlineWidthPx = 3f,
                ),
            ),
        ).single()
        val pixels = image.bitmap.pixels
        var redish = 0
        var greenish = 0
        for (index in pixels.indices step 4) {
            val r = pixels[index].toInt() and 0xFF
            val g = pixels[index + 1].toInt() and 0xFF
            val a = pixels[index + 3].toInt() and 0xFF
            if (a > 200) {
                if (r > 150 && g < 100) redish++
                if (g > 150 && r < 100) greenish++
            }
        }
        assertTrue(redish > 5, "no red fill pixels found ($redish)")
        assertTrue(greenish > 5, "no green outline pixels found ($greenish)")
    }

    @Test
    fun `font scale grows the glyphs`() {
        val small = rasterize(cue("scale")).single()
        val big = rasterize(cue("scale"), fontScale = 2f).single()
        assertTrue(big.bitmap.height > small.bitmap.height, "fontScale 2 must produce taller text")
    }

    // ── the pass-through and refusal arms ───────────────────────────────────────────────────

    @Test
    fun `bitmap cues are placed and scaled, never re-rasterised`() {
        val pixels = ByteArray(4 * 4 * 4) { 0x7F }
        val region = BitmapRegion(
            x = 100, y = 50, width = 4, height = 4,
            canvasWidth = 320, canvasHeight = 180,
            bitmap = RgbaBitmap(4, 4, pixels),
        )
        val image = rasterize(SubtitleCue.Bitmap(0, 1_000_000, listOf(region))).single()
        assertEquals(200, image.x, "authored x scales from the 320-wide canvas to the 640-wide viewport")
        assertEquals(100, image.y, "authored y scales from the 180-tall canvas to the 360-tall viewport")
        assertTrue(image.bitmap.pixels === pixels, "authored pixels pass through untouched")
    }

    @Test
    fun `an empty viewport or an empty cue produces nothing`() {
        assertTrue(rasterize(cue("x"), width = 0).isEmpty())
        assertTrue(rasterize(cue("x"), height = 0).isEmpty())
        assertTrue(rasterize(cue("")).isEmpty())
    }
    /**
     * SOL-S8. A cue the author PLACED must not consume space in the stack it never stood in.
     *
     * The implicit bottom stack is a running offset: each bottom cue rasterized pushes the next one
     * up by its own height plus the gap. A cue carrying an explicit `positionY` is laid out from
     * that fraction instead and never reads the offset, but it was still ADDING to it, so an
     * authored caption anywhere on screen silently shoved every later ordinary subtitle upward by
     * its height. The taller the placed cue, the further the shove.
     */
    @Test
    fun `a positioned cue does not consume implicit stacking space`() {
        val alone = rasterize(cue("ordinary")).single()

        val placed = cue(
            "placed up here",
            layout = CueLayout(
                alignment = CueAlignment.BottomCenter,
                positionX = 0.5f,
                positionY = 0.2f,
            ),
        )
        val images = rasterize(placed, cue("ordinary"))
        assertEquals(2, images.size)

        assertEquals(
            alone.y,
            images[1].y,
            "the ordinary cue must sit where it would have sat with no placed cue present",
        )
    }

    /** The stack itself still works: two ordinary bottom cues DO stack. */
    @Test
    fun `two ordinary bottom cues still stack`() {
        val alone = rasterize(cue("first")).single()
        val images = rasterize(cue("first"), cue("second"))
        assertEquals(2, images.size)
        assertEquals(alone.y, images[0].y, "the first cue is unmoved")
        assertTrue(
            images[1].y < images[0].y,
            "the second cue (y=${images[1].y}) must sit above the first (y=${images[0].y})",
        )
    }

    /**
     * A placed cue is still PLACED, which is the other half of the contract.
     *
     * Without this, deleting the whole positioned branch would satisfy the row above.
     */
    @Test
    fun `a positioned cue is still laid out from its own fraction`() {
        val placed = cue(
            "placed",
            layout = CueLayout(
                alignment = CueAlignment.BottomCenter,
                positionX = 0.5f,
                positionY = 0.2f,
            ),
        )
        val withNeighbour = rasterize(cue("ordinary"), placed)[1]
        val onItsOwn = rasterize(placed).single()
        assertEquals(onItsOwn.y, withNeighbour.y, "a placed cue never moves with the stack either")
        assertEquals((360 * 0.2f).toInt() - onItsOwn.bitmap.height, onItsOwn.y)
    }

}
