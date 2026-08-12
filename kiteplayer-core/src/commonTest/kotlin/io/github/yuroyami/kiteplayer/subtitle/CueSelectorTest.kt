package io.github.yuroyami.kiteplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CueSelectorTest {

    private fun cue(start: Long, end: Long, text: String, layer: Int = 0) = SubtitleCue.Text(
        startMicros = start,
        endMicros = end,
        spans = listOf(StyledSpan(text)),
        layer = layer,
    )

    private val cues = listOf(
        cue(1_000_000, 3_000_000, "first"),
        cue(2_000_000, 4_000_000, "overlap", layer = 1),
        cue(5_000_000, 6_000_000, "last"),
    )

    private fun textsAt(at: Long) =
        CueSelector.activeAt(cues, at).map { (it as SubtitleCue.Text).plainText }

    @Test
    fun theWindowIsClosedOpenAndOverlapsStack() {
        assertEquals(emptyList(), textsAt(999_999))
        assertEquals(listOf("first"), textsAt(1_000_000))
        assertEquals(listOf("first", "overlap"), textsAt(2_500_000))
        // End is exclusive: at exactly 3s the first cue is gone.
        assertEquals(listOf("overlap"), textsAt(3_000_000))
        assertEquals(emptyList(), textsAt(4_500_000))
        assertEquals(listOf("last"), textsAt(5_000_000))
    }

    @Test
    fun seekingIsJustAskingAgainInBothDirections() {
        // Forward past everything, then back into the middle: no state, so no reconstruction.
        assertEquals(emptyList(), textsAt(10_000_000))
        assertEquals(listOf("first", "overlap"), textsAt(2_500_000))
        assertEquals(listOf("first"), textsAt(1_500_000))
    }

    @Test
    fun layerOrdersTheDrawStack() {
        val layered = listOf(
            cue(0, 10, "under", layer = 0),
            cue(0, 10, "over", layer = 5),
        )
        assertEquals(
            listOf("under", "over"),
            CueSelector.activeAt(layered, 5).map { (it as SubtitleCue.Text).plainText },
        )
    }

    @Test
    fun theNextChangeIsTheNearestEdge() {
        assertEquals(1_000_000, CueSelector.nextChangeAfter(cues, 0))
        assertEquals(2_000_000, CueSelector.nextChangeAfter(cues, 1_000_000))
        assertEquals(3_000_000, CueSelector.nextChangeAfter(cues, 2_500_000))
        assertEquals(5_000_000, CueSelector.nextChangeAfter(cues, 4_000_000))
        assertEquals(6_000_000, CueSelector.nextChangeAfter(cues, 5_000_000))
        assertNull(CueSelector.nextChangeAfter(cues, 6_000_000))
        assertNull(CueSelector.nextChangeAfter(emptyList(), 0))
    }
}
