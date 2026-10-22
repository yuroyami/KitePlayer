package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The clock the picture reads. The player republishes its position about every fifty milliseconds,
 * and between those updates the picture still has to move on every frame.
 */
class SmoothClockTest {

    private var published = 1_000_000L
    private var rate = 1.0
    private var now = 0L
    private val clock = SmoothClock(published = { published }, rate = { rate }, nanos = { now })

    private fun after(millis: Long): Long {
        now += millis * 1_000_000
        return clock.micros()
    }

    @Test
    fun `between two updates from the player the clock moves on with the wall clock`() {
        assertEquals(1_000_000, clock.micros())
        assertEquals(1_016_000, after(16))
        assertEquals(1_033_000, after(17))
    }

    @Test
    fun `a new position from the player is taken as it is`() {
        clock.micros()
        after(33)
        published = 1_050_000
        assertEquals(1_050_000, after(17))
        assertEquals(1_066_000, after(16))
    }

    @Test
    fun `while paused it holds the position and starts from it again on resume`() {
        rate = 0.0
        clock.micros()
        assertEquals(1_000_000, after(500))
        rate = 1.0
        assertEquals(1_000_000, clock.micros())
        assertEquals(1_016_000, after(16))
    }

    @Test
    fun `it never runs more than a tenth of a second past the player`() {
        clock.micros()
        assertEquals(1_100_000, after(500))
    }

    @Test
    fun `speed scales how far it moves on`() {
        rate = 2.0
        clock.micros()
        assertEquals(1_040_000, after(20))
    }

    @Test
    fun `an update a little behind the clock holds it still instead of stepping back`() {
        clock.micros()
        assertEquals(1_040_000, after(40))
        published = 1_030_000
        assertEquals(1_040_000, after(5))
        assertEquals(1_045_000, after(15))
    }

    @Test
    fun `a seek back is followed at once`() {
        clock.micros()
        after(20)
        published = 500_000
        assertEquals(500_000, after(16))
    }
}
