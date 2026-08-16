package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
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

        // Bottom centre, the Android arithmetic verbatim: y = height - margin - imageHeight.
        val expectedY = 360 - (360 * 0.05f).toInt() - image.bitmap.height
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
}
