package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.CueWrap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CoreText rasterizer proved with real text (S2.c carrying S4.c's Apple half): a cue
 * becomes pixels, lands where the Android arithmetic would put it, and the outline colour is
 * present beside the fill. Placement equality with Android is BY CONSTRUCTION (the same code,
 * mirrored); these arms hold the Apple half to it.
 */
class AppleSubtitleRasterizerTest {

    /**
     * The default [CueStyle] draws a one pixel drop shadow, so every default-styled bitmap is one
     * pixel wider and taller than the text inside it. Placement still measures the TEXT box, which
     * is why the numbers below subtract this rather than shifting.
     */
    private val shadowPad = 1

    private fun cue(
        text: String,
        alignment: CueAlignment = CueAlignment.BottomCenter,
        style: CueStyle = CueStyle(),
    ) = SubtitleCue.Text(
        startMicros = 0,
        endMicros = 1_000_000,
        spans = listOf(StyledSpan(text, style)),
        layout = CueLayout(alignment = alignment),
    )

    @Test
    fun aCueBecomesPixelsAtTheBottomCentre() {
        val images = AppleSubtitleRasterizer().rasterize(
            cues = listOf(cue("Hello from KitePlayer")),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
        )
        assertEquals(1, images.size)
        val image = images.single()
        assertTrue(image.bitmap.width > 0 && image.bitmap.height > 0, "the cue rasterised to nothing")

        var drawn = 0
        val pixels = image.bitmap.pixels
        for (index in 3 until pixels.size step 4) {
            if ((pixels[index].toInt() and 0xFF) > 0) drawn++
        }
        assertTrue(drawn > 50, "the bitmap has $drawn non-transparent pixels; text did not draw")

        // Bottom centre, the Android arithmetic verbatim: y = height - margin - textHeight.
        val expectedY = 360 - (360 * 0.05f).toInt() - (image.bitmap.height - shadowPad)
        assertEquals(expectedY, image.y, "the cue is not at the bottom margin")
        assertTrue(
            image.x in 0..(640 - image.bitmap.width),
            "the cue is horizontally outside the viewport at x=${image.x}",
        )
    }

    @Test
    fun theOutlineColourAppearsBesideTheFill() {
        val images = AppleSubtitleRasterizer().rasterize(
            cues = listOf(
                cue(
                    "O",
                    style = CueStyle(
                        // Big on purpose: at subtitle sizes a 3 px stroke swallows a thin glyph
                        // stem whole, and this arm needs surviving fill INTERIOR to count.
                        fontSizePx = 96f,
                        primaryColor = 0xFFFF0000.toInt(),
                        outlineColor = 0xFF00FF00.toInt(),
                        outlineWidthPx = 3f,
                    ),
                ),
            ),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
        )
        val pixels = images.single().bitmap.pixels
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
    fun twoBottomCuesStackUpward() {
        val images = AppleSubtitleRasterizer().rasterize(
            cues = listOf(cue("first"), cue("second")),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
        )
        assertEquals(2, images.size)
        assertTrue(
            images[1].y + images[1].bitmap.height <= images[0].y,
            "the second cue (y=${images[1].y}) does not stack above the first (y=${images[0].y})",
        )
    }

    @Test
    fun theSubPositionLiftsTheImplicitStackByTheViewportFraction() {
        val bottom = AppleSubtitleRasterizer().rasterize(
            cues = listOf(cue("line")),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
        ).single()
        val lifted = AppleSubtitleRasterizer().rasterize(
            cues = listOf(cue("line")),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
            position = 0.5f,
        ).single()
        // Anchoring at half the height moves the cue up by exactly half the viewport.
        assertEquals(bottom.y - 180, lifted.y, "sub-pos 0.5 must lift the stack by half the height")
        assertEquals(bottom.x, lifted.x, "and never touch the horizontal")
    }

    // ── per-span size and outline, flattened to the first span until 2026-08-30 ─────────────

    private fun spans(vararg parts: Pair<String, CueStyle>) = AppleSubtitleRasterizer().rasterize(
        cues = listOf(
            SubtitleCue.Text(
                startMicros = 0,
                endMicros = 1_000_000,
                spans = parts.map { (text, style) -> StyledSpan(text, style) },
                layout = CueLayout(alignment = CueAlignment.BottomCenter),
            ),
        ),
        viewportWidth = 640,
        viewportHeight = 360,
        fontScale = 1f,
    ).single()

    @Test
    fun eachSpanKeepsItsOwnSize() {
        val mixed = spans("aa" to CueStyle(fontSizePx = 12f), "bb" to CueStyle(fontSizePx = 48f))
        val bothSmall = spans("aa" to CueStyle(fontSizePx = 12f), "bb" to CueStyle(fontSizePx = 12f))
        val bothBig = spans("aa" to CueStyle(fontSizePx = 48f), "bb" to CueStyle(fontSizePx = 48f))
        assertEquals(bothBig.bitmap.height, mixed.bitmap.height, "the tallest span must set the line height")
        assertTrue(
            bothSmall.bitmap.height < mixed.bitmap.height,
            "12px alone must be shorter (${bothSmall.bitmap.height} vs ${mixed.bitmap.height})",
        )
    }

    @Test
    fun twoSpansDrawTheirTwoOutlineColours() {
        val image = spans(
            "OO" to CueStyle(
                fontSizePx = 96f,
                primaryColor = 0xFF808080.toInt(),
                outlineColor = 0xFF00FF00.toInt(),
                outlineWidthPx = 4f,
                shadowOffsetPx = 0f,
            ),
            "OO" to CueStyle(
                fontSizePx = 96f,
                primaryColor = 0xFF808080.toInt(),
                outlineColor = 0xFF0000FF.toInt(),
                outlineWidthPx = 4f,
                shadowOffsetPx = 0f,
            ),
        )
        var green = 0
        var blue = 0
        val pixels = image.bitmap.pixels
        for (index in pixels.indices step 4) {
            val r = pixels[index].toInt() and 0xFF
            val g = pixels[index + 1].toInt() and 0xFF
            val b = pixels[index + 2].toInt() and 0xFF
            val a = pixels[index + 3].toInt() and 0xFF
            if (a > 200 && g > 150 && r < 100 && b < 100) green++
            if (a > 200 && b > 150 && r < 100 && g < 100) blue++
        }
        assertTrue(green > 20, "the first span's green outline is missing ($green pixels)")
        assertTrue(blue > 20, "the second span's blue outline is missing ($blue pixels)")
    }

    // ── the shadow pass, which this rasterizer ignored until 2026-08-30 ─────────────────────

    private fun shadowed(style: CueStyle) = AppleSubtitleRasterizer().rasterize(
        cues = listOf(cue("shadowed", style = style)),
        viewportWidth = 640,
        viewportHeight = 360,
        fontScale = 1f,
    ).single()

    @Test
    fun theShadowIsDrawnInItsOwnColour() {
        val image = shadowed(
            CueStyle(
                fontSizePx = 96f,
                primaryColor = 0xFFFFFFFF.toInt(),
                outlineColor = 0xFF000000.toInt(),
                shadowColor = 0xFFFF0000.toInt(),
                shadowOffsetPx = 8f,
            ),
        )
        var red = 0
        val pixels = image.bitmap.pixels
        for (index in pixels.indices step 4) {
            val r = pixels[index].toInt() and 0xFF
            val g = pixels[index + 1].toInt() and 0xFF
            val a = pixels[index + 3].toInt() and 0xFF
            if (a > 200 && r > 150 && g < 100) red++
        }
        assertTrue(red > 20, "the shadow did not draw: only $red shadow-coloured pixels")
    }

    @Test
    fun aShadowGrowsTheBitmapAndLeavesTheTextWhereItWas() {
        val none = shadowed(CueStyle(shadowOffsetPx = 0f))
        val withShadow = shadowed(CueStyle(shadowOffsetPx = 8f))
        assertEquals(none.bitmap.width + 8, withShadow.bitmap.width, "the bitmap must grow by the offset")
        assertEquals(none.bitmap.height + 8, withShadow.bitmap.height, "on both axes")
        // The shadow falls down and right, so the text box does not move: the extra pixels hang
        // off the bottom-right corner.
        assertEquals(none.x, withShadow.x, "a shadow must not move the text sideways")
        assertEquals(none.y, withShadow.y, "a shadow must not move the text up or down")
    }

    @Test
    fun aShadowThatReachesUpAndLeftMovesTheBitmapInsteadOfTheText() {
        val none = shadowed(CueStyle(shadowOffsetPx = 0f))
        val behind = shadowed(CueStyle(shadowOffsetPx = -8f))
        assertEquals(none.bitmap.width + 8, behind.bitmap.width)
        assertEquals(none.x - 8, behind.x, "the bitmap must start where the shadow does")
        assertEquals(none.y - 8, behind.y)
    }

    @Test
    fun aTransparentShadowCostsNothing() {
        val off = shadowed(CueStyle(shadowOffsetPx = 0f))
        val invisible = shadowed(CueStyle(shadowColor = 0x00FF0000, shadowOffsetPx = 8f))
        assertEquals(off.bitmap.width, invisible.bitmap.width, "a shadow nobody can see must not grow the bitmap")
        assertTrue(off.bitmap.pixels.contentEquals(invisible.bitmap.pixels), "and must not change a pixel")
    }

    // ── CueWrap, which this rasterizer ignored until 2026-08-30 ─────────────────────────────

    /** A cue that greedy-wraps onto two very uneven lines, which is what balancing is for. */
    private val lopsided = "a subtitle long enough that the safe width forces it onto more than one line"

    private fun withWrap(mode: CueWrap, text: String = lopsided) =
        AppleSubtitleRasterizer().rasterize(
            cues = listOf(
                SubtitleCue.Text(
                    startMicros = 0,
                    endMicros = 1_000_000,
                    spans = listOf(StyledSpan(text, CueStyle())),
                    layout = CueLayout(alignment = CueAlignment.BottomCenter, wrap = mode),
                ),
            ),
            viewportWidth = 640,
            viewportHeight = 360,
            fontScale = 1f,
        ).single()

    @Test
    fun neverKeepsALongCueOnOneLine() {
        val never = withWrap(CueWrap.Never)
        val greedy = withWrap(CueWrap.None)
        assertTrue(
            never.bitmap.height < greedy.bitmap.height,
            "Never must not break, so it must be shorter than the wrapped cue: " +
                "${never.bitmap.height} vs ${greedy.bitmap.height}",
        )
        assertTrue(
            never.bitmap.width > (640 * 0.9).toInt(),
            "the one long line is wider than the safe area, so the bitmap must grow past it, " +
                "was ${never.bitmap.width}",
        )
        assertTrue(never.bitmap.width <= 640, "and must stop at the viewport, was ${never.bitmap.width}")
    }

    @Test
    fun balancedKeepsTheLineCountAndEvensTheLinesOut() {
        val greedy = withWrap(CueWrap.None)
        val balanced = withWrap(CueWrap.Balanced)
        assertEquals(
            greedy.bitmap.height,
            balanced.bitmap.height,
            "balancing must not add or remove a line, only move the break",
        )
        assertTrue(
            balanced.bitmap.width < greedy.bitmap.width,
            "and it must actually move it: a balanced two-liner is narrower than a greedy one " +
                "(${balanced.bitmap.width} vs ${greedy.bitmap.width})",
        )
    }

    @Test
    fun aCueThatFitsOnOneLineRendersTheSameWhateverTheWrapModeSays() {
        val balanced = withWrap(CueWrap.Balanced, "short")
        for (mode in listOf(CueWrap.None, CueWrap.Never)) {
            val other = withWrap(mode, "short")
            assertTrue(
                balanced.bitmap.pixels.contentEquals(other.bitmap.pixels),
                "nothing to break, so $mode must render like Balanced",
            )
            assertEquals(balanced.x, other.x, "and must land in the same place")
        }
    }
}
