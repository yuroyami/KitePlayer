package io.github.yuroyami.kiteplayer.view

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleTimerHoldsTest {

    private val flags = mutableListOf<Boolean>()
    private var original: (Boolean) -> Unit = {}
    private var originalRead: () -> Boolean = { false }
    private var appFlag = false

    @BeforeTest
    fun start() {
        original = IdleTimerHolds.setIdleTimerDisabled
        originalRead = IdleTimerHolds.isIdleTimerDisabled
        IdleTimerHolds.setIdleTimerDisabled = { flags += it; appFlag = it }
        IdleTimerHolds.isIdleTimerDisabled = { appFlag }
        repeat(IdleTimerHolds.count()) { IdleTimerHolds.release() }
        flags.clear()
    }

    @AfterTest
    fun finish() {
        repeat(IdleTimerHolds.count()) { IdleTimerHolds.release() }
        IdleTimerHolds.setIdleTimerDisabled = original
        IdleTimerHolds.isIdleTimerDisabled = originalRead
    }

    /** A timer the app had turned off itself stays off after the last hold (#932). */
    @Test
    fun theAppsOwnSettingSurvivesTheLastRelease() {
        appFlag = true
        IdleTimerHolds.acquire()
        IdleTimerHolds.acquire()
        IdleTimerHolds.release()
        IdleTimerHolds.release()
        assertEquals(emptyList(), flags, "the player wrote over the app's own setting")
        assertEquals(true, appFlag)
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
