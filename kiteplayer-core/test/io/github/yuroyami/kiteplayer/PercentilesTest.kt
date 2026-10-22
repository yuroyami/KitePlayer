package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.Percentiles
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ring behind the frame-timing stats: a fixed window, sorted only when asked. */
class PercentilesTest {

    @Test
    fun `the median and the 95th of one to 240`() {
        val ring = Percentiles(240)
        for (value in 1L..240L) ring.add(value)
        assertEquals(120L, ring.p(0.5))
        assertEquals(228L, ring.p(0.95))
    }

    @Test
    fun `fewer than two samples read zero`() {
        val ring = Percentiles(240)
        assertEquals(0L, ring.p(0.5), "an empty ring has no percentile to report")
        ring.add(7L)
        assertEquals(0L, ring.p(0.5), "one sample is a reading, not a distribution")
        assertEquals(0L, ring.p(0.95))
        ring.add(9L)
        assertEquals(7L, ring.p(0.5), "two samples: the lower one is the median")
    }

    @Test
    fun `only the last 240 samples count`() {
        val ring = Percentiles(240)
        for (value in 1L..480L) ring.add(value)
        assertEquals(360L, ring.p(0.5), "the first 240 must have fallen out of the window")
        assertEquals(468L, ring.p(0.95))
    }

    @Test
    fun `arrival order does not matter`() {
        val ring = Percentiles(240)
        for (value in 240L downTo 1L) ring.add(value)
        assertEquals(120L, ring.p(0.5))
        assertEquals(228L, ring.p(0.95))
    }
}
