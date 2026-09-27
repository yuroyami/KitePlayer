package io.github.yuroyami.kiteplayer.view

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleTimerHoldsTest {

    private val flags = mutableListOf<Boolean>()
    private var original: (Boolean) -> Unit = {}

    @BeforeTest
    fun start() {
        original = IdleTimerHolds.setIdleTimerDisabled
        IdleTimerHolds.setIdleTimerDisabled = { flags += it }
        repeat(IdleTimerHolds.count()) { IdleTimerHolds.release() }
        flags.clear()
    }

    @AfterTest
    fun finish() {
        repeat(IdleTimerHolds.count()) { IdleTimerHolds.release() }
        IdleTimerHolds.setIdleTimerDisabled = original
    }

    @Test
    fun theTimerStaysOffUntilTheLastHoldLetsGo() {
        IdleTimerHolds.acquire()
        IdleTimerHolds.acquire()
        IdleTimerHolds.release()
        assertEquals(listOf(true), flags, "one of two holds letting go must not wake the timer")
        IdleTimerHolds.release()
        assertEquals(listOf(true, false), flags)
        assertEquals(0, IdleTimerHolds.count())
    }

    @Test
    fun aReleaseWithoutAHoldChangesNothing() {
        IdleTimerHolds.release()
        assertEquals(emptyList(), flags)
        assertEquals(0, IdleTimerHolds.count())
    }

    @Test
    fun aViewHoldsThroughTheSharedCount() {
        val holder = DisplayAwakeHolder(hold = IdleTimerHolds::acquire, release = IdleTimerHolds::release)
        holder.onScreen = true
        holder.playing = true
        assertEquals(1, IdleTimerHolds.count())
        holder.playing = false
        assertEquals(0, IdleTimerHolds.count())
        assertEquals(listOf(true, false), flags)
    }
}
