package io.github.yuroyami.kiteplayer.network.dash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A live stand-in playlist's media sequence across loads (#756). When the segments a load gave had
 * all left the window by the next, the new first segment was numbered as the next one however
 * many were skipped, so the time of day, which places a continuous sequence segment after
 * segment, drifted by the skipped time.
 */
class LiveSequenceTest {

    private fun plan(numbers: LongRange) = DashTimedPlan(
        null,
        null,
        numbers.map { DashTimedSegment("https://cdn.test/live/v-$it.m4s", null, it, (it - 1) * 2_000_000L, 2_000_000L) },
    )

    @Test
    fun aRefreshThatSkipsSegmentsMovesTheSequenceOnByAsMany() {
        val sequence = DashHlsMediaIo.LiveSequence()
        assertEquals(1L, sequence.first(plan(1L..5L)))
        // The same window moved on by one: its first segment is the second given before.
        assertEquals(2L, sequence.first(plan(2L..6L)))
        // Segments 7 to 10 came and went between the loads: 11 follows 6 after four more.
        assertEquals(11L, sequence.first(plan(11L..15L)), "the sequence hid the four segments skipped")
    }
}
