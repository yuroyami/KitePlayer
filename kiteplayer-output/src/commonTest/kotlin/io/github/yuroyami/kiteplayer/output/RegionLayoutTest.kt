package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueDisplayAlign
import io.github.yuroyami.kiteplayer.subtitle.CueInsets
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueRegion
import io.github.yuroyami.kiteplayer.subtitle.CueShowBackground
import io.github.yuroyami.kiteplayer.subtitle.CueStacking
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TTML regions laid out by the loop every built-in rasterizer shares (#492), through a stand-in
 * rasterizer whose text is 10 pixels a character and 20 a line, placed as the real ones place it.
 */
class RegionLayoutTest {

    /** Draws a cue's text box as the rasterizers do: its lines at most the safe width, placed by [cueOrigin]. */
    private val drawer: (SubtitleCue.Text, Int, OverlayPixels) -> OverlayImage? = { cue, stacked, budget ->
        val lines = cue.plainText.split('\n')
        val width = (lines.maxOf { it.length } * CHAR).coerceIn(1, cueSafeWidth(cue.layout, W).coerceAtLeast(1))
        val height = lines.size * LINE
        if (!budget.take(width, height)) {
            null
        } else {
            val origin = cueOrigin(cue.layout, W, H, width, height, position = 1f, stackedBottom = stacked)
            OverlayImage(origin.x, origin.y, RgbaBitmap(width, height, ByteArray(width * height * 4) { -1 }))
        }
    }

    private fun cue(text: String, layout: CueLayout, start: Long = 0) =
        SubtitleCue.Text(start, 10_000_000, if (text.isEmpty()) emptyList() else listOf(StyledSpan(text)), layout)

    /** A region from 100 to 900 across and 300 to 450 down, padded to 140 to 860 and 315 to 435. */
    private fun region(
        displayAlign: CueDisplayAlign = CueDisplayAlign.Before,
        background: Int = 0,
        showBackground: CueShowBackground = CueShowBackground.Always,
    ) = CueRegion(
        "r", left = 0.1f, top = 0.6f, width = 0.8f, height = 0.3f,
        padding = CueInsets(left = 0.05f, top = 0.1f, right = 0.05f, bottom = 0.1f),
        displayAlign = displayAlign, backgroundColor = background, showBackground = showBackground,
    )

    private fun inRegion(region: CueRegion, order: Int, alignment: CueAlignment = CueAlignment.TopCenter) =
        CueLayout(alignment = alignment, region = region, regionOrder = order)

    private fun draw(vararg cues: SubtitleCue): List<OverlayImage> = rasterizeCues(cues.toList(), W, H, drawer)

    @Test
    fun paragraphsOfOneRegionStackInDocumentOrderNotTimeOrder() {
        val r = region()
        // The second paragraph of the document began first, so it comes first in time order.
        val second = cue("second paragraph", inRegion(r, 1), start = 0)
        val first = cue("first!", inRegion(r, 0), start = 1)
        val images = draw(second, first)
        assertEquals(
            listOf(60 to 315, 160 to 335),
            images.map { it.bitmap.width to it.y },
            "the first of the document on top, the second under it",
        )
        assertEquals(listOf(500 - 30, 500 - 80), images.map { it.x }, "each centred in the space inside the padding")
    }

    @Test
    fun theBlockSitsAtTheTopTheMiddleOrTheBottom() {
        for ((align, tops) in listOf(
            CueDisplayAlign.Before to listOf(315, 335),
            CueDisplayAlign.Center to listOf(355, 375),
            CueDisplayAlign.After to listOf(395, 415),
        )) {
            val r = region(displayAlign = align)
            assertEquals(tops, draw(cue("one", inRegion(r, 0)), cue("two", inRegion(r, 1))).map { it.y }, "$align")
        }
    }

    @Test
    fun linesBreakAtTheWidthInsideThePaddingAndAlignThere() {
        val r = region()
        assertEquals(720, draw(cue("w".repeat(100), inRegion(r, 0))).single().bitmap.width, "the width inside the padding")
        assertEquals(140, draw(cue("left", inRegion(r, 0, CueAlignment.TopLeft))).single().x)
        assertEquals(860 - 50, draw(cue("right", inRegion(r, 0, CueAlignment.BottomRight))).single().x)
    }

    @Test
    fun aBlockTallerThanItsRegionIsCutAtTheRegionsEdgeOnlyWhenItClips() {
        val low = CueRegion("low", left = 0.1f, top = 0.6f, width = 0.8f, height = 0.06f, displayAlign = CueDisplayAlign.After)
        val three = "one\ntwo\nthree"
        val cut = draw(cue(three, inRegion(low, 0))).single()
        assertEquals(300, cut.y, "cut at the region's top")
        assertEquals(30, cut.bitmap.height, "only the bottom lines are left")
        val whole = draw(cue(three, inRegion(low.copy(clip = false), 0))).single()
        assertEquals(270, whole.y)
        assertEquals(60, whole.bitmap.height)
    }

    @Test
    fun aBackgroundShowsUnderTheTextAndAloneOnlyWhenItAlwaysShows() {
        val r = region(background = 0x80FF0000.toInt())
        val images = draw(cue("text", inRegion(r, 0)))
        val box = images.first()
        assertEquals(listOf(100, 300, 800, 150), listOf(box.x, box.y, box.bitmap.width, box.bitmap.height), "the whole region, padding included")
        assertEquals(listOf(128, 0, 0, 128), box.bitmap.pixels.take(4).map { it.toInt() and 0xFF }, "red at half, premultiplied")
        assertEquals(2, images.size, "then the text over it")
        assertEquals(1, draw(cue("", inRegion(r, -1))).size, "an active region with no text shows its background")
        val whenActive = region(background = 0x80FF0000.toInt(), showBackground = CueShowBackground.WhenActive)
        assertEquals(emptyList(), draw(cue("", inRegion(whenActive, -1))), "but not when it shows only with text")
        assertEquals(2, draw(cue("", inRegion(whenActive, -1)), cue("text", inRegion(whenActive, 0))).size)
    }

    @Test
    fun aRegionStandsInNoStackAndLeavesOtherCuesWhereTheyWere() {
        val plain = cue("an ordinary line", CueLayout())
        val alone = draw(plain).single()
        val bottomAligned = cue("in a region", CueLayout(alignment = CueAlignment.BottomCenter, region = region(), regionOrder = 0))
        val together = draw(bottomAligned, plain)
        fun box(image: OverlayImage) = listOf(image.x, image.y, image.bitmap.width, image.bitmap.height)
        assertEquals(box(alone), box(together.last()), "the ordinary line did not move for the region's")
        assertEquals(315, together.first().y, "and the region's line is in its region")
        // A region's line that would pile its newest at the bottom stands in no pile, so it decides
        // nothing for the ordinary lines: the first of them keeps the bottom.
        val reversing = cue("in a region", CueLayout(alignment = CueAlignment.BottomCenter, region = region(), stacking = CueStacking.LastAtBottom))
        val pile = draw(reversing, cue("older line", CueLayout()), cue("newer", CueLayout())).drop(1)
        assertTrue(pile[0].y > pile[1].y, "the newer line took the bottom: ${pile.map { it.y }}")
    }

    /** Each image of [images] named by what drew it: the region's box, its lines a and b, or the line around them. */
    private fun names(images: List<OverlayImage>): List<String> = images.map { image ->
        when {
            image.bitmap.width == 800 -> "box"
            image.bitmap.width == CHAR -> if (image.y == 315) "a" else "b"
            image.bitmap.width == 6 * CHAR -> "before"
            else -> "after"
        }
    }

    @Test
    fun aRegionIsDrawnAtThePlaceOfItsFirstCueWithItsBackgroundUnderItsText() {
        val r = region(background = 0xFF000000.toInt())
        val images = draw(cue("before", CueLayout()), cue("a", inRegion(r, 0)), cue("after", CueLayout()), cue("b", inRegion(r, 1)))
        assertEquals(listOf("before", "box", "a", "b", "after"), names(images))
        // A pile that puts its newest line at the bottom is built from the end, and turned back.
        val reversed = CueLayout(stacking = CueStacking.LastAtBottom)
        val turned = draw(cue("before", reversed), cue("a", inRegion(r, 0)), cue("after", reversed), cue("b", inRegion(r, 1)))
        assertEquals(listOf("box", "a", "b"), names(turned).filter { it == "box" || it == "a" || it == "b" }, "still under its text, in its own order")
    }

    private companion object {
        const val W = 1000
        const val H = 500
        const val CHAR = 10
        const val LINE = 20
    }
}
