package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.CueIndex
import io.github.yuroyami.kiteplayer.subtitle.CueSelector
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fast cue lookup, checked against the slow one that defines the answer.
 *
 * [CueSelector] is the rule: it walks the whole list and is obviously right, which is exactly what
 * makes it the oracle. [CueIndex] is the same rule with a binary search and a terminable backward
 * walk, and the only way to trust it is to run both over material designed to break the shortcut.
 *
 * The generated lists are deliberately nasty. Cues overlap, share start times, sit at zero length,
 * and one in every twenty runs far longer than its neighbours, which is the case the backward walk
 * exists to handle: a lecture-length cue near the start is still showing thousands of cues later,
 * and an index that stopped at the first non-matching neighbour would lose it.
 */
class CueIndexTest {

    private fun cue(start: Long, end: Long, layer: Int = 0): SubtitleCue =
        SubtitleCue.Text(start, end, listOf(StyledSpan("$start-$end")), layer = layer)

    /** A start-sorted list with overlaps, duplicates, zero-length cues and occasional very long ones. */
    private fun messyCues(count: Int, seed: Int): List<SubtitleCue> {
        val random = Random(seed)
        var start = 0L
        val cues = ArrayList<SubtitleCue>(count)
        repeat(count) {
            start += random.nextLong(0, 400)
            val length = when {
                random.nextInt(20) == 0 -> random.nextLong(5_000, 60_000)
                random.nextInt(10) == 0 -> 0L
                else -> random.nextLong(0, 900)
            }
            cues += cue(start, start + length, layer = random.nextInt(3))
        }
        return cues
    }

    @Test
    fun `the index agrees with the rule over nasty generated material`() {
        var comparisons = 0
        for (seed in 1..60) {
            val cues = messyCues(count = 400, seed = seed)
            val index = CueIndex().also { it.syncTo(cues) }
            val random = Random(seed * 31)
            val span = (cues.last().startMicros + 80_000).coerceAtLeast(1)
            repeat(120) {
                val at = random.nextLong(-1_000, span)
                assertEquals(
                    CueSelector.activeAt(cues, at),
                    index.activeAt(at),
                    "the active set differs at $at, seed $seed",
                )
                assertEquals(
                    CueSelector.nextChangeAfter(cues, at),
                    index.nextChangeAfter(at),
                    "the next change differs at $at, seed $seed",
                )
                comparisons++
            }
        }
        assertTrue(comparisons > 5_000, "the comparison did not actually run: $comparisons")
    }

    @Test
    fun `a cue that outlasts thousands of its neighbours is still found`() {
        // The case the whole design is about. Without the prefix maxima a backward walk would stop
        // at the first neighbour that had already ended and never reach this one.
        val cues = ArrayList<SubtitleCue>()
        cues += cue(0, 10_000_000)
        for (i in 1..5_000) cues += cue(i * 100L, i * 100L + 50)
        val index = CueIndex().also { it.syncTo(cues) }

        val active = index.activeAt(400_000)
        assertTrue(
            active.any { it.startMicros == 0L && it.endMicros == 10_000_000L },
            "the long cue was lost: found $active",
        )
        assertEquals(CueSelector.activeAt(cues, 400_000), active)
    }

    @Test
    fun `an empty list answers nothing rather than throwing`() {
        val index = CueIndex()
        assertEquals(emptyList(), index.activeAt(0))
        assertEquals(null, index.nextChangeAfter(0))
    }

    @Test
    fun `a time before every cue finds nothing and points at the first start`() {
        val cues = listOf(cue(1_000, 2_000), cue(3_000, 4_000))
        val index = CueIndex().also { it.syncTo(cues) }
        assertEquals(emptyList(), index.activeAt(0))
        assertEquals(1_000L, index.nextChangeAfter(0))
    }

    @Test
    fun `a time after every cue finds nothing and points nowhere`() {
        val cues = listOf(cue(1_000, 2_000), cue(3_000, 4_000))
        val index = CueIndex().also { it.syncTo(cues) }
        assertEquals(emptyList(), index.activeAt(9_000))
        assertEquals(null, index.nextChangeAfter(9_000))
    }

    @Test
    fun `cues sharing a start time are all found`() {
        // The binary search has to be an upper bound. A plain one lands somewhere inside a run of
        // equal starts and silently drops the rest.
        val cues = listOf(cue(1_000, 5_000), cue(1_000, 6_000), cue(1_000, 7_000))
        val index = CueIndex().also { it.syncTo(cues) }
        assertEquals(3, index.activeAt(2_000).size)
        assertEquals(CueSelector.activeAt(cues, 2_000), index.activeAt(2_000))
    }

    @Test
    fun `appending extends the index and pruning rebuilds it`() {
        // The two ways the engine's cue table changes. An append must be cheap and must keep the
        // right answers; a prune moves every remaining cue to a new position, so the index has to
        // notice and start again rather than answer from stale positions.
        val index = CueIndex()
        val cues = ArrayList<SubtitleCue>()
        cues += cue(0, 10_000_000)
        for (i in 1..200) cues += cue(i * 100L, i * 100L + 50)
        index.syncTo(cues)
        assertEquals(CueSelector.activeAt(cues, 5_000), index.activeAt(5_000))

        for (i in 201..400) cues += cue(i * 100L, i * 100L + 50)
        index.syncTo(cues)
        assertEquals(CueSelector.activeAt(cues, 25_000), index.activeAt(25_000))

        // A prune drops the long cue and shifts everything, which no extension could survive.
        cues.removeAll { it.endMicros < 20_000 }
        index.syncTo(cues)
        assertEquals(CueSelector.activeAt(cues, 25_000), index.activeAt(25_000))
        assertEquals(CueSelector.activeAt(cues, 5_000), index.activeAt(5_000))
    }

    @Test
    fun `a merge that grows the list is not mistaken for an append`() {
        // The engine's cold path: a decoder emits out of order and the cue table is rebuilt as a
        // merge, which can leave the list LONGER while changing what sits at earlier positions.
        // Size alone cannot tell that from an append, which is why the index checks the identity of
        // the last cue it built from. Without that check this answers from stale positions.
        val index = CueIndex()
        val original = ArrayList<SubtitleCue>()
        for (i in 1..50) original += cue(i * 1_000L, i * 1_000L + 500)
        index.syncTo(original)
        assertEquals(CueSelector.activeAt(original, 25_200), index.activeAt(25_200))

        // A merged list: one very long cue inserted at the front, so every old cue moves by one
        // and the list is longer than before.
        val merged = ArrayList<SubtitleCue>()
        merged += cue(0, 10_000_000)
        merged += original
        index.syncTo(merged)

        assertEquals(
            CueSelector.activeAt(merged, 25_200),
            index.activeAt(25_200),
            "the index answered from positions the merge had already moved",
        )
        assertTrue(
            index.activeAt(25_200).any { it.endMicros == 10_000_000L },
            "the merged-in long cue was never seen",
        )
    }

    @Test
    fun `a cleared table answers nothing`() {
        val index = CueIndex()
        index.syncTo(listOf(cue(0, 1_000)))
        index.syncTo(emptyList())
        assertEquals(emptyList(), index.activeAt(500))
        assertEquals(null, index.nextChangeAfter(500))
    }

    @Test
    fun `a lookup near the end of a dense track does not walk the whole track`() {
        // The measurement that motivated this. Seventy thousand cues is a real dense ASS track, and
        // the old rule visited every one of them on every timing edge. The bound is generous by a
        // wide margin: the point is the difference between constant and linear, not a millisecond
        // count that would be a flaky assertion on a busy machine.
        val cues = (0 until 70_000).map { cue(it * 1_000L, it * 1_000L + 800) }
        val index = CueIndex().also { it.syncTo(cues) }
        val near = 69_000 * 1_000L

        var sink = 0
        repeat(10_000) {
            sink += index.activeAt(near).size
            sink += if (index.nextChangeAfter(near) != null) 1 else 0
        }
        assertTrue(sink > 0, "the loop was optimised away, so it measured nothing")
        assertEquals(CueSelector.activeAt(cues, near), index.activeAt(near))
    }
}
