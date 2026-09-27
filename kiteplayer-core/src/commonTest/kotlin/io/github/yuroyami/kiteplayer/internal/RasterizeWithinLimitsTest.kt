package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlayLimitException
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The limits the engine holds any rasterizer to, built in or not. */
class RasterizeWithinLimitsTest {

    private val cues = listOf(SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("a line"))))

    /** A rasterizer that answers [answer] and records what it was given. */
    private class Scripted(private val answer: (List<SubtitleCue>, Int, Int) -> List<OverlayImage>) : SubtitleRasterizer {
        val given = mutableListOf<List<SubtitleCue>>()
        val viewports = mutableListOf<Pair<Int, Int>>()

        override fun rasterize(
            cues: List<SubtitleCue>,
            viewportWidth: Int,
            viewportHeight: Int,
            fontScale: Float,
            position: Float,
        ): List<OverlayImage> {
            given += cues
            viewports += viewportWidth to viewportHeight
            return answer(cues, viewportWidth, viewportHeight)
        }
    }

    private fun image(width: Int, height: Int, x: Int = 0) = OverlayImage(x, 0, RgbaBitmap(width, height, ByteArray(width * height * 4)))

    private val warnings = mutableListOf<Pair<UndrawnSubtitles, String>>()

    private fun SubtitleRasterizer.draw(
        cues: List<SubtitleCue> = this@RasterizeWithinLimitsTest.cues,
        width: Int = 640,
        height: Int = 360,
        safeArea: SubtitleSafeArea = SubtitleSafeArea.None,
    ): List<OverlayImage>? = rasterizeWithinLimits(safeArea, cues, width, height, 1f, 1f) { cause, detail ->
        warnings += cause to detail
    }

    @Test
    fun aRasterizerThatThrowsDrawsNothingAndSaysWhy() {
        val failing = Scripted { _, _, _ -> throw IllegalStateException("no font engine") }
        assertNull(failing.draw())
        assertEquals(UndrawnSubtitles.Failed, warnings.single().first)
        assertTrue("no font engine" in warnings.single().second, warnings.single().second)
    }

    @Test
    fun cancellationIsNotAFailure() {
        val cancelled = Scripted { _, _, _ -> throw CancellationException("gone") }
        assertFailsWith<CancellationException> { cancelled.draw() }
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun aRasterizerThatReachesItsPixelLimitShowsWhatItDrew() {
        val drawn = listOf(image(4, 4))
        val limited = Scripted { _, _, _ -> throw SubtitleOverlayLimitException(drawn, skipped = 3) }
        assertEquals(drawn, limited.draw())
        assertEquals(UndrawnSubtitles.Limited, warnings.single().first)
        assertTrue("3 cues" in warnings.single().second, warnings.single().second)
    }

    @Test
    fun imagesPastThePixelBudgetAreDroppedInOrder() {
        // 640 by 360 allows four viewports of pixels: three images of one viewport and a half fit.
        val oversized = Scripted { _, w, h -> List(3) { image(w, h * 3 / 2, x = it) } }
        val kept = oversized.draw()
        assertEquals(listOf(0, 1), kept?.map { it.x })
        assertEquals(UndrawnSubtitles.Limited, warnings.single().first)
    }

    @Test
    fun imagesPastTheCueLimitAreDropped() {
        val many = Scripted { _, _, _ -> List(SubtitleRasterizer.MAX_CUES + 5) { image(1, 1, x = it) } }
        assertEquals(SubtitleRasterizer.MAX_CUES, many.draw()?.size)
        assertEquals(UndrawnSubtitles.Limited, warnings.single().first)
    }

    @Test
    fun onlyTheCuesInsideTheLimitsReachTheRasterizer() {
        val counting = Scripted { given, _, _ -> given.map { image(1, 1) } }
        val tooMany = List(SubtitleRasterizer.MAX_CUES * 2) { cues.single() }
        assertEquals(SubtitleRasterizer.MAX_CUES, counting.draw(cues = tooMany)?.size)
        assertEquals(SubtitleRasterizer.MAX_CUES, counting.given.single().size)
        assertEquals(UndrawnSubtitles.Limited, warnings.single().first)
    }

    @Test
    fun aSubtitleAreaPastTheViewportLimitDrawsNothing() {
        val never = Scripted { _, _, _ -> error("the rasterizer was asked to draw a viewport past the limit") }
        assertEquals(emptyList(), never.draw(width = SubtitleRasterizer.MAX_VIEWPORT_SIZE + 1, height = 360))
        assertEquals(UndrawnSubtitles.Limited, warnings.single().first)
        // The safe area is what is laid out, so an output past the limit whose safe area is inside draws.
        val inside = Scripted { _, _, _ -> listOf(image(1, 1)) }
        val safeArea = SubtitleSafeArea(left = 0.25f, right = 0.25f)
        assertEquals(1, inside.draw(width = 20_000, height = 360, safeArea = safeArea)?.size)
        assertEquals(10_000 to 360, inside.viewports.single())
    }

    @Test
    fun cuesInsideEveryLimitPassUntouchedAndWarnNothing() {
        val plain = Scripted { given, _, _ -> given.map { image(8, 8) } }
        assertEquals(1, plain.draw()?.size)
        assertTrue(plain.given.single() === cues)
        assertEquals(emptyList(), warnings)
    }
}
