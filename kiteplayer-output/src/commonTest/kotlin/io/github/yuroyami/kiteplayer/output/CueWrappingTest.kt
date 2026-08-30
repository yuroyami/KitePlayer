package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueWrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wrap rule on its own, with a measured breaker standing in for AWT, CoreText and StaticLayout.
 *
 * The fake is a greedy breaker over fixed-width "characters", which is what all three real ones do
 * at this level of detail. That keeps the arithmetic testable on every target instead of only on
 * the two that have a text engine.
 */
class CueWrappingTest {

    /** Greedy wrap of [text] at [width], one unit per character, breaking on spaces. */
    private fun greedyLines(text: String, width: Int): Int {
        if (width <= 0) return text.length
        var lines = 1
        var used = 0
        for (word in text.split(" ")) {
            val needed = if (used == 0) word.length else used + 1 + word.length
            if (needed <= width || used == 0) {
                used = needed
            } else {
                lines++
                used = word.length
            }
        }
        return lines
    }

    private fun widthFor(wrap: CueWrap, text: String, safeWidth: Int): Int =
        wrapWidthFor(wrap, safeWidth) { greedyLines(text, it) }

    @Test
    fun `None hands the safe width straight through`() {
        assertEquals(100, widthFor(CueWrap.None, "one two three four five six", 100))
    }

    @Test
    fun `Never asks for a width no subtitle reaches`() {
        assertEquals(NO_WRAP_WIDTH, widthFor(CueWrap.Never, "one two three four five six", 100))
        assertTrue(NO_WRAP_WIDTH > 100_000, "the sentinel must be wider than any real viewport")
    }

    @Test
    fun `Balanced narrows a lopsided break until the lines even out`() {
        // Greedy at 40 fills line one and leaves one word alone on line two.
        val text = "a subtitle that is just long enough to spill"
        assertEquals(2, greedyLines(text, 40))

        val balanced = widthFor(CueWrap.Balanced, text, 40)
        assertTrue(balanced < 40, "balanced must break narrower than the safe width, was $balanced")
        assertEquals(2, greedyLines(text, balanced), "and it must not add a line")
        assertTrue(
            greedyLines(text, balanced - 1) > 2,
            "one pixel narrower must spill onto a third line, or this is not the NARROWEST width",
        )
    }

    @Test
    fun `Balanced leaves a single line alone`() {
        // A cue that already fits must not be measured into a narrower box: nothing to balance,
        // and shrinking it would move where a centred line sits.
        assertEquals(200, widthFor(CueWrap.Balanced, "short enough", 200))
    }

    @Test
    fun `Balanced never asks for more lines than greedy would take`() {
        val text = "the quick brown fox jumps over the lazy dog and keeps going for a while yet"
        for (safeWidth in 20..120) {
            val greedy = greedyLines(text, safeWidth)
            val balanced = widthFor(CueWrap.Balanced, text, safeWidth)
            assertTrue(balanced in 1..safeWidth, "width $balanced out of range at safe $safeWidth")
            assertEquals(
                greedy,
                greedyLines(text, balanced),
                "balancing at safe width $safeWidth changed the line count",
            )
        }
    }

    @Test
    fun `a zero or negative safe width is handed back untouched`() {
        // The rasterizers already bail on this; the rule must not invent a width for them.
        for (wrap in CueWrap.entries) {
            assertEquals(0, wrapWidthFor(wrap, 0) { 1 })
            assertEquals(-5, wrapWidthFor(wrap, -5) { 1 })
        }
    }
}
