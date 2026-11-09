package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

/** The display flag the player holds goes back to what the app had set (#932). */
class FlagHoldsTest {

    private var flag = false
    private val writes = mutableListOf<Boolean>()
    private val holds = FlagHolds(read = { flag }, write = { flag = it; writes += it })

    @Test
    fun twoHoldsAndOneReleaseKeepTheFlagSet() {
        holds.acquire()
        holds.acquire()
        holds.release()
        assertEquals(true, flag)
        holds.release()
        assertEquals(false, flag)
        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun aFlagTheAppSetBeforeTheFirstHoldStaysSet() {
        flag = true
        holds.acquire()
        holds.release()
        assertEquals(true, flag, "the last release cleared the app's own setting")
        assertEquals(emptyList(), writes)
    }

    @Test
    fun aReleaseWithoutAHoldChangesNothing() {
        holds.release()
        holds.acquire()
        holds.release()
        assertEquals(listOf(true, false), writes)
        assertEquals(0, holds.holds)
    }
}
