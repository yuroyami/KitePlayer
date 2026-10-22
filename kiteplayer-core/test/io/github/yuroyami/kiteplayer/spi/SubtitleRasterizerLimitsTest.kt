package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The limits of one subtitle overlay that every rasterizer shares. */
class SubtitleRasterizerLimitsTest {

    private fun text(vararg spans: String): SubtitleCue.Text =
        SubtitleCue.Text(0, 1_000_000, spans.map { StyledSpan(it) })

    private fun bitmap(regions: Int): SubtitleCue.Bitmap =
        SubtitleCue.Bitmap(
            0,
            1_000_000,
            List(regions) { BitmapRegion(it, 0, 1, 1, 640, 360, RgbaBitmap(1, 1, ByteArray(4))) },
        )

    private fun SubtitleCue.length(): Int = (this as SubtitleCue.Text).plainText.length

    @Test
    fun cuesInsideEveryLimitComeBackAsTheSameList() {
        val cues = listOf(text("one"), bitmap(3), text("two", "three"))
        assertSame(cues, SubtitleRasterizer.limitCues(cues))
    }

    @Test
    fun onlyTheFirstCuesUpToTheLimitAreKept() {
        val cues = List(SubtitleRasterizer.MAX_CUES + 10) { text("line $it") }
        val kept = SubtitleRasterizer.limitCues(cues)
        assertEquals(cues.subList(0, SubtitleRasterizer.MAX_CUES), kept)
    }

    @Test
    fun eachRegionOfABitmapCueCountsAsACue() {
        val cues = listOf(text("before"), bitmap(SubtitleRasterizer.MAX_CUES), text("after"))
        val kept = SubtitleRasterizer.limitCues(cues)
        assertEquals(2, kept.size)
        assertEquals(SubtitleRasterizer.MAX_CUES - 1, (kept[1] as SubtitleCue.Bitmap).regions.size)
    }

    @Test
    fun aLongCueIsCutAtTheCueLimitAndTheCuesAfterItAreLeftOut() {
        val long = "x".repeat(SubtitleRasterizer.MAX_CUE_LENGTH + 5)
        val kept = SubtitleRasterizer.limitCues(listOf(text("a", long), text("next")))
        assertEquals(1, kept.size)
        assertEquals(SubtitleRasterizer.MAX_CUE_LENGTH, kept.single().length())
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        // A face with tears of joy is one code point in two UTF-16 units. The limit falls between them.
        val emoji = "😂"
        val cue = text("y".repeat(SubtitleRasterizer.MAX_CUE_LENGTH - 1) + emoji)
        val kept = SubtitleRasterizer.limitCues(listOf(cue)).single() as SubtitleCue.Text
        assertEquals(SubtitleRasterizer.MAX_CUE_LENGTH - 1, kept.plainText.length)
        assertTrue(kept.plainText.none { it.isSurrogate() }, "a lone half of the pair survived the cut")
    }

    @Test
    fun theOverlayTextLimitLeavesOutTheCuesPastIt() {
        val full = "z".repeat(SubtitleRasterizer.MAX_CUE_LENGTH)
        val perOverlay = SubtitleRasterizer.MAX_OVERLAY_TEXT_LENGTH / SubtitleRasterizer.MAX_CUE_LENGTH
        val cues = List(perOverlay + 2) { text(full) }
        val kept = SubtitleRasterizer.limitCues(cues)
        assertEquals(perOverlay, kept.size)
        assertEquals(SubtitleRasterizer.MAX_OVERLAY_TEXT_LENGTH, kept.sumOf { it.length() })
    }

    @Test
    fun theSpanLimitCutsTheCueThatReachesIt() {
        val spans = Array(SubtitleRasterizer.MAX_OVERLAY_SPANS + 50) { "s" }
        val kept = SubtitleRasterizer.limitCues(listOf(text(*spans), text("after")))
        assertEquals(1, kept.size)
        assertEquals(SubtitleRasterizer.MAX_OVERLAY_SPANS, (kept.single() as SubtitleCue.Text).spans.size)
    }

    @Test
    fun theSpanLimitCountsEmptySpansToo() {
        val spans = Array(SubtitleRasterizer.MAX_OVERLAY_SPANS * 4) { "" }
        val kept = SubtitleRasterizer.limitCues(listOf(text(*spans)))
        assertEquals(SubtitleRasterizer.MAX_OVERLAY_SPANS, (kept.single() as SubtitleCue.Text).spans.size)
    }

    @Test
    fun thePixelBudgetIsFourViewportsUpToItsCeiling() {
        assertEquals(4L * 1920 * 1080, SubtitleRasterizer.overlayPixelBudget(1920, 1080))
        assertEquals(SubtitleRasterizer.MAX_OVERLAY_PIXELS, SubtitleRasterizer.overlayPixelBudget(7680, 4320))
        assertEquals(SubtitleRasterizer.MAX_OVERLAY_PIXELS, SubtitleRasterizer.overlayPixelBudget(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0L, SubtitleRasterizer.overlayPixelBudget(-5, 1080))
    }
}
