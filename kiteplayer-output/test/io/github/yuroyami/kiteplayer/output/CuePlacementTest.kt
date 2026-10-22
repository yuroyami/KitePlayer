package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The placement rule every built-in rasterizer uses, on a 640 by 360 viewport and a 100 by 40 text box. */
class CuePlacementTest {
    private fun origin(
        alignment: CueAlignment,
        x: Float? = null,
        y: Float? = null,
        position: Float = 1f,
        stacked: Int = 0,
    ): Pair<Int, Int> = cueOrigin(
        CueLayout(alignment = alignment, positionX = x, positionY = y),
        viewportWidth = 640,
        viewportHeight = 360,
        width = 100,
        height = 40,
        position = position,
        stackedBottom = stacked,
    ).let { it.x to it.y }

    @Test
    fun anAuthoredPositionIsTheAnchorTheAlignmentNames() {
        val expected = mapOf(
            CueAlignment.TopLeft to (320 to 180),
            CueAlignment.TopCenter to (270 to 180),
            CueAlignment.TopRight to (220 to 180),
            CueAlignment.MiddleLeft to (320 to 160),
            CueAlignment.MiddleCenter to (270 to 160),
            CueAlignment.MiddleRight to (220 to 160),
            CueAlignment.BottomLeft to (320 to 140),
            CueAlignment.BottomCenter to (270 to 140),
            CueAlignment.BottomRight to (220 to 140),
        )
        for ((alignment, corner) in expected) assertEquals(corner, origin(alignment, 0.5f, 0.5f), "$alignment")
    }

    @Test
    fun aPositionedBoxStaysInsideTheViewport() {
        // A top-anchored box on the bottom edge, and a bottom-anchored one on the top edge.
        assertEquals(270 to 320, origin(CueAlignment.TopCenter, 0.5f, 1f))
        assertEquals(270 to 0, origin(CueAlignment.BottomCenter, 0.5f, 0f))
        // Boxes that would hang off the left and the right edges.
        assertEquals(0 to 140, origin(CueAlignment.BottomCenter, 0f, 0.5f))
        assertEquals(0 to 140, origin(CueAlignment.BottomRight, 0f, 0.5f))
        assertEquals(540 to 140, origin(CueAlignment.BottomLeft, 1f, 0.5f))
    }

    @Test
    fun withoutAPositionTheAlignmentPicksAMarginAndTheBottomRowStacks() {
        // The default margins are 5 percent: 32 pixels across and 18 down.
        assertEquals(32 to 302, origin(CueAlignment.BottomLeft))
        assertEquals(508 to 302, origin(CueAlignment.BottomRight))
        assertEquals(270 to 18, origin(CueAlignment.TopCenter))
        assertEquals(270 to 160, origin(CueAlignment.MiddleCenter))
        assertEquals(270 to 252, origin(CueAlignment.BottomCenter, stacked = 50))
        assertEquals(270 to (360 * 0.9f).toInt() - 58, origin(CueAlignment.BottomCenter, position = 0.9f))
    }
}
