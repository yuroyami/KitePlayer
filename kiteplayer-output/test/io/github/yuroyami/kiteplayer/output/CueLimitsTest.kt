package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.SubtitleOverlayLimitException
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The shared limits every built-in rasterizer draws within. */
class CueLimitsTest {

    /** A bitmap cue of one [width] by [height] region at its authored size on a 640 by 360 canvas. */
    private fun bitmapCue(width: Int, height: Int, x: Int = 0) = SubtitleCue.Bitmap(
        0,
        1_000_000,
        listOf(BitmapRegion(x, 0, width, height, 640, 360, RgbaBitmap(width, height, ByteArray(width * height * 4)))),
    )

    private val noText: (SubtitleCue.Text, Int, OverlayPixels) -> Nothing? = { _, _, _ -> null }

    @Test
    fun theOverlayStopsAtTheFirstCueThatDoesNotFitItsPixelBudget() {
        // 640 by 360 allows four viewports: four full-screen cues fit, and the fifth does not.
        val cues = List(5) { bitmapCue(640, 360, x = 0) } + bitmapCue(8, 8)
        val limit = assertFailsWith<SubtitleOverlayLimitException> { rasterizeCues(cues, 640, 360, noText) }
        assertEquals(4, limit.drawn.size)
        assertEquals(2, limit.skipped, "the cue that did not fit and the small one after it")
    }

    @Test
    fun cuesInsideTheBudgetAreAllDrawn() {
        val drawn = rasterizeCues(List(4) { bitmapCue(640, 360) }, 640, 360, noText)
        assertEquals(4, drawn.size)
    }

    @Test
    fun aViewportPastTheLimitDrawsNothing() {
        val cues = listOf(bitmapCue(8, 8))
        assertEquals(emptyList(), rasterizeCues(cues, SubtitleRasterizer.MAX_VIEWPORT_SIZE + 1, 360, noText))
        assertEquals(emptyList(), rasterizeCues(cues, 640, 0, noText))
    }

    @Test
    fun theTextDrawerIsOnlyGivenWhatTheLimitsKeep() {
        val seen = mutableListOf<Int>()
        val long = SubtitleCue.Text(0, 1, listOf(StyledSpan("w".repeat(SubtitleRasterizer.MAX_CUE_LENGTH * 2))))
        rasterizeCues(listOf(long), 640, 360) { cue, _, _ -> seen += cue.plainText.length; null }
        assertEquals(listOf(SubtitleRasterizer.MAX_CUE_LENGTH), seen)
    }

    @Test
    fun theFontSizeStaysBetweenOnePixelAndTheViewportHeight() {
        val layout = CueLayout()
        assertEquals(18f, cueFontSizePx(CueStyle(), layout, 360, 1f), "the default is a twentieth of the height")
        assertEquals(360f, cueFontSizePx(CueStyle(fontSizePx = 1e9f), layout, 360, 1f))
        assertEquals(360f, cueFontSizePx(CueStyle(fontSizePx = 20f), CueLayout(authoredHeight = 1), 360, 1f))
        assertEquals(360f, cueFontSizePx(CueStyle(), layout, 360, Float.POSITIVE_INFINITY))
        assertEquals(1f, cueFontSizePx(CueStyle(fontSizePx = -40f), layout, 360, 1f))
        assertEquals(18f, cueFontSizePx(CueStyle(fontSizePx = Float.NaN), layout, 360, 1f))
        // A stylesheet's factor scales the size the cue would have had (#498), and a broken one is ignored.
        assertEquals(27f, cueFontSizePx(CueStyle(relativeSize = 1.5f), layout, 360, 1f))
        assertEquals(18f, cueFontSizePx(CueStyle(relativeSize = Float.NaN), layout, 360, 1f))
        assertEquals(18f, cueFontSizePx(CueStyle(relativeSize = -2f), layout, 360, 1f))
        // An authoring height that is not a height is ignored rather than divided by.
        assertEquals(20f, cueFontSizePx(CueStyle(fontSizePx = 20f), CueLayout(authoredHeight = 0), 360, 1f))
    }

    @Test
    fun theSafeWidthNeverPassesTheViewport() {
        assertEquals(576, cueSafeWidth(CueLayout(), 640))
        assertEquals(640, cueSafeWidth(CueLayout(marginLeft = -1000f, marginRight = -1000f), 640))
        assertEquals(0, cueSafeWidth(CueLayout(marginLeft = 0.6f, marginRight = 0.6f), 640))
        assertEquals(0, cueSafeWidth(CueLayout(marginLeft = Float.NaN), 640))
    }

    @Test
    fun theBoxPaddingAndTheOutlineStayInProportion() {
        val boxed = CueStyle(backgroundColor = 0xFF000000.toInt(), backgroundPaddingPx = 1e9f)
        assertEquals(64, cueBoxPadPx(boxed, 1f))
        assertEquals(0, cueBoxPadPx(boxed.copy(backgroundPaddingPx = -10f), 1f))
        assertEquals(0, cueBoxPadPx(boxed.copy(backgroundColor = 0x00FFFFFF), 1f), "a transparent box has no padding")
        assertEquals(8, cueBoxPadPx(boxed.copy(backgroundPaddingPx = 4f), 2f))

        assertEquals(30f, cueOutlinePx(CueStyle(outlineWidthPx = 1e9f), 1f, fontSizePx = 30f))
        assertEquals(0f, cueOutlinePx(CueStyle(outlineWidthPx = Float.NaN), 1f, fontSizePx = 30f))
        assertEquals(4f, cueOutlinePx(CueStyle(outlineWidthPx = 2f), 2f, fontSizePx = 30f))
    }

    @Test
    fun theBudgetRefusesAnImageBeforeItIsCountedAndStaysRefused() {
        val budget = OverlayPixels(10, 10)
        assertTrue(budget.take(10, 30))
        assertTrue(!budget.take(10, 11), "the image past four viewports was taken")
        assertTrue(!budget.take(1, 1), "a small image after the refusal was taken")
        assertTrue(budget.exhausted)
    }
}
