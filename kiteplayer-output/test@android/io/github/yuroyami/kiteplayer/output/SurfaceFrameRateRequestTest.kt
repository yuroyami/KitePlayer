package io.github.yuroyami.kiteplayer.output

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceFrameRateRequestTest {

    /** Timestamps as Matroska stores them: the true time rounded to a whole millisecond. */
    private fun matroskaTimestamps(fps: Double, count: Int, startUs: Long = 0L): List<Long> =
        (0 until count).map { k -> startUs + (k * 1_000.0 / fps).roundToLong() * 1_000L }

    /** Every non-null answer, with the index of the frame that produced it. */
    private fun requests(timestamps: List<Long>): List<Pair<Int, Float>> {
        val request = SurfaceFrameRateRequest()
        return timestamps.mapIndexedNotNull { index, pts -> request.offer(pts)?.let { index to it } }
    }

    @Test
    fun `millisecond rounded film timestamps request the exact film rate`() {
        val made = requests(matroskaTimestamps(24_000.0 / 1_001.0, count = 24 * 10))
        assertEquals(1, made.size, "exactly one request per Surface")
        assertEquals(24_000f / 1_001f, made.single().second, 0.0001f)
    }

    @Test
    fun `the request waits until three seconds of steady frames can tell 23976 from 24000`() {
        val timestamps = matroskaTimestamps(24_000.0 / 1_001.0, count = 24 * 10)
        val (index, _) = requests(timestamps).single()
        assertTrue(timestamps[index] - timestamps.first() >= 3_000_000L, "asked after ${timestamps[index]} us")
    }

    @Test
    fun `each standard rate is recognised from rounded timestamps`() {
        val rates = listOf(24_000.0 / 1_001.0, 24.0, 25.0, 30_000.0 / 1_001.0, 30.0, 50.0, 60_000.0 / 1_001.0, 60.0)
        for (fps in rates) {
            val made = requests(matroskaTimestamps(fps, count = (fps * 10).toInt()))
            assertEquals(fps.toFloat(), made.single().second, 0.0001f, "for $fps fps")
        }
    }

    @Test
    fun `a steady rate that is not a standard one is requested as measured`() {
        val made = requests((0 until 220).map { k -> k * 1_000_000L / 22 })
        assertEquals(22f, made.single().second, 0.01f)
    }

    @Test
    fun `irregular timestamps make no request`() {
        var pts = 0L
        val timestamps = (0 until 400).map { k -> pts.also { pts += if (k % 3 == 0) 40_000L else 29_000L } }
        assertTrue(requests(timestamps).isEmpty())
    }

    @Test
    fun `a jump in the timestamps starts the measurement again`() {
        val film = 24_000.0 / 1_001.0
        val before = matroskaTimestamps(film, count = 24)
        val after = matroskaTimestamps(film, count = 24 * 6, startUs = 60_000_000L)
        val timestamps = before + after
        val (index, rate) = requests(timestamps).single()
        assertEquals(24_000f / 1_001f, rate, 0.0001f)
        assertTrue(timestamps[index] - 60_000_000L >= 3_000_000L, "measured across the jump")
    }

    @Test
    fun `nothing is asked after the one request`() {
        val request = SurfaceFrameRateRequest()
        matroskaTimestamps(25.0, count = 25 * 5).forEach { request.offer(it) }
        assertNull(request.offer(5_000_000L))
    }
}
