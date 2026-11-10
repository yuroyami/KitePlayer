package io.github.yuroyami.kiteplayer.view

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A mouse that moves over the desktop controls pokes them at most four times a second (#942).
 * Every move poked, and each poke built a snapshot and restarted the hide timer.
 */
class MovePokesTest {
    @Test
    fun aSecondOfMovesEveryMillisecondPokesFourTimes() {
        val pokes = MovePokes()
        val made = (0 until 1_000).count { ms -> pokes.due(ms * 1_000_000L) }
        assertEquals(4, made)
    }

    @Test
    fun theFirstMovePokesAtOnceAndOneAfterTheIntervalPokesAgain() {
        val pokes = MovePokes()
        assertEquals(true, pokes.due(5_000_000_000L))
        assertEquals(false, pokes.due(5_249_999_999L))
        assertEquals(true, pokes.due(5_250_000_000L))
    }
}
