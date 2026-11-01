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

    /** One frame: its media time, the engine's presentation target in microseconds, and its stream. */
    private data class Shown(val ptsUs: Long, val targetUs: Long, val generation: Long)

    /** [fps] frames for [seconds], shown at [speed], from [startUs] of media and [startTargetUs] of wall time. */
    private fun shown(
        fps: Double,
        seconds: Double,
        generation: Long = 1L,
        speed: Double = 1.0,
        startUs: Long = 0L,
        startTargetUs: Long = 0L,
    ): List<Shown> = matroskaTimestamps(fps, (fps * seconds).toInt()).map { pts ->
        Shown(startUs + pts, startTargetUs + (pts / speed).roundToLong(), generation)
    }

    private fun requestsFor(frames: List<Shown>): List<Float> {
        val request = SurfaceFrameRateRequest()
        return frames.mapNotNull { request.offer(it.ptsUs, it.targetUs, it.generation) }
    }

    // A queue of a film and a sports clip of the same aspect kept the Surface, and the display
    // stayed at the film's rate for the whole clip (#598).
    @Test
    fun aNewStreamOnTheSameSurfaceIsMeasuredAgain() {
        val film = shown(24_000.0 / 1_001.0, seconds = 4.0, generation = 1L)
        val sport = shown(50.0, seconds = 4.0, generation = 2L, startTargetUs = 5_000_000L)
        val made = requestsFor(film + sport)
        assertEquals(2, made.size, "asked for $made")
        assertEquals(24_000f / 1_001f, made[0], 0.0001f)
        assertEquals(50f, made[1], 0.0001f)
    }

    @Test
    fun theRequestFollowsThePlaybackSpeed() {
        val made = requestsFor(shown(24_000.0 / 1_001.0, seconds = 8.0, speed = 2.0))
        assertEquals(listOf(48_000f / 1_001f), made.map { (it * 1_001f).roundToLong() / 1_001f })
    }

    @Test
    fun aSteadyRunThatDisagreesWithTheRequestAsksAgain() {
        // A speed change in the same stream, with no seek and so no new generation.
        val normal = shown(25.0, seconds = 4.0)
        val fast = shown(25.0, seconds = 8.0, speed = 2.0, startUs = 4_000_000L, startTargetUs = 4_000_000L)
        assertEquals(listOf(25f, 50f), requestsFor(normal + fast))
    }

    @Test
    fun aSyncNudgeDoesNotChangeTheRequest() {
        // The engine runs half a percent fast for a while to catch the sound up.
        val made = requestsFor(shown(24_000.0 / 1_001.0, seconds = 12.0, speed = 1.005))
        assertEquals(1, made.size, "asked for $made")
        assertEquals(24_000f / 1_001f, made.single(), 0.0001f)
    }

    @Test
    fun aPauseDoesNotChangeTheRequest() {
        val before = shown(30.0, seconds = 4.0)
        val after = shown(30.0, seconds = 4.0, startUs = 4_000_000L, startTargetUs = 64_000_000L)
        assertEquals(listOf(30f), requestsFor(before + after))
    }

    @Test
    fun aNearbyStandardRateIsStillAskedFor() {
        val ntsc = shown(30_000.0 / 1_001.0, seconds = 4.0, generation = 1L)
        val thirty = shown(30.0, seconds = 4.0, generation = 2L, startTargetUs = 5_000_000L)
        assertEquals(listOf(30_000f / 1_001f, 30f), requestsFor(ntsc + thirty))
    }

    @Test
    fun aSeekInTheSameStreamAsksNothingNew() {
        val before = shown(25.0, seconds = 4.0, generation = 1L)
        val after = shown(25.0, seconds = 4.0, generation = 2L, startUs = 90_000_000L, startTargetUs = 5_000_000L)
        assertEquals(listOf(25f), requestsFor(before + after))
    }
}
