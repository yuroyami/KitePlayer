package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlayLimitException
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.CueWrap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The AWT rasterizer given cues no subtitle file should carry: every image stays within the
 * viewport, and the overlay within its pixel budget, however large the cue asks to be.
 */
class DesktopSubtitleLimitsTest {

    private val width = 640
    private val height = 360

    /** The most any bitmap may grow past the viewport on a side: the largest shadow and two box pads. */
    private val padding = 64 + 2 * 64

    private fun cue(text: String, style: CueStyle = CueStyle(), layout: CueLayout = CueLayout()) =
        SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan(text, style)), layout)

    private fun rasterize(vararg cues: SubtitleCue, fontScale: Float = 1f): List<OverlayImage> =
        DesktopSubtitleRasterizer().rasterize(cues.toList(), width, height, fontScale, 1f)

    private fun assertInsideTheViewport(image: OverlayImage) {
        assertTrue(image.bitmap.width <= width + padding, "a bitmap ${image.bitmap.width} pixels wide")
        assertTrue(image.bitmap.height <= height + padding, "a bitmap ${image.bitmap.height} pixels high")
    }

    @Test
    fun aHugeFontIsNoTallerThanTheViewport() {
        assertInsideTheViewport(rasterize(cue("W", CueStyle(fontSizePx = 1e9f))).single())
        assertInsideTheViewport(rasterize(cue("W", CueStyle(fontSizePx = 40f), CueLayout(authoredHeight = 1))).single())
        assertInsideTheViewport(rasterize(cue("W"), fontScale = 1e30f).single())
    }

    @Test
    fun aCueOfManyLinesIsCutAtTheViewportHeight() {
        val lines = List(2_000) { "line $it" }.joinToString("\n")
        val image = rasterize(cue(lines)).single()
        assertInsideTheViewport(image)
        assertTrue(image.bitmap.height > height / 2, "the lines that fit were not drawn")
    }

    @Test
    fun negativeMarginsDoNotWidenTheBitmapPastTheViewport() {
        val layout = CueLayout(marginLeft = -1000f, marginRight = -1000f)
        assertInsideTheViewport(rasterize(cue("a margin wider than the world", layout = layout)).single())
    }

    @Test
    fun aHugeBoxPaddingIsCapped() {
        val style = CueStyle(backgroundColor = 0xFF000000.toInt(), backgroundPaddingPx = 1e9f)
        assertInsideTheViewport(rasterize(cue("boxed", style)).single())
    }

    @Test
    fun oddNumbersInTheStyleDrawWithoutFailing() {
        val style = CueStyle(fontSizePx = Float.NaN, outlineWidthPx = Float.NaN, shadowOffsetPx = Float.NaN)
        assertEquals(1, rasterize(cue("still drawn", style)).size)
        assertEquals(1, rasterize(cue("still drawn", CueStyle(outlineWidthPx = 1e9f))).size)
        assertEquals(1, rasterize(cue("still drawn"), fontScale = Float.NaN).size)
    }

    @Test
    fun aCuePastTheLengthLimitIsDrawnCut() {
        val long = "word ".repeat(SubtitleRasterizer.MAX_CUE_LENGTH)
        assertInsideTheViewport(rasterize(cue(long, layout = CueLayout(wrap = CueWrap.Never))).single())
    }

    @Test
    fun theOverlayStopsAtItsPixelBudgetAndKeepsTheCuesBefore() {
        // Each cue fills the viewport with lines as wide as it, so four fit the budget and the rest
        // do not. Eight of them stay inside the text limits, so only the pixels stop the overlay.
        val full = List(30) { "w".repeat(70) }.joinToString("\n")
        val cues = List(8) { cue(full) }
        val limit = assertFailsWith<SubtitleOverlayLimitException> { rasterize(*cues.toTypedArray()) }
        val pixels = limit.drawn.sumOf { it.bitmap.width.toLong() * it.bitmap.height }
        assertTrue(pixels <= SubtitleRasterizer.overlayPixelBudget(width, height), "$pixels pixels drawn")
        assertTrue(limit.drawn.isNotEmpty(), "nothing was drawn before the budget ran out")
        assertEquals(cues.size, limit.drawn.size + limit.skipped)
    }

    @Test
    fun aViewportPastTheLimitDrawsNothing() {
        val drawn = DesktopSubtitleRasterizer().rasterize(
            listOf(cue("anything")), SubtitleRasterizer.MAX_VIEWPORT_SIZE + 1, height, 1f, 1f,
        )
        assertEquals(emptyList(), drawn)
    }

    @Test
    fun anAuthorsLineBreaksStillBreakALongParagraph() {
        // One paragraph of several lines, then a short one: the second starts on its own line.
        val paragraph = "long ".repeat(60).trim()
        val two = rasterize(cue("$paragraph\nend")).single()
        val one = rasterize(cue(paragraph)).single()
        assertTrue(two.bitmap.height > one.bitmap.height, "the author's line break was lost")
    }
}
