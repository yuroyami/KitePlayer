package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizTick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The clocks of a surface keep counting after days, where a Float would stop adding one frame. */
class RenderClockTest {

    private val heard = SpectrumFrame(
        100_000L, FloatArray(4), FloatArray(4), FloatArray(4), 0.3f, 0.3f, 0.3f, 0.3f, 0f, 0f,
        mood = 0.5f, energy = 0.5f,
    )

    @Test
    fun aClockThatRanForDaysStillAdvancesEveryStep() {
        assertTrue(heard.motionRate > 0.05f, "the fixture needs a music clock that runs: ${heard.motionRate}")
        val delta = 1f / 144f
        // 55 hours in. A Float sum of 200000f and one 144 Hz step is 200000f again.
        val start = VizTick(1L, heard, 200_000.0, delta, 200_000.0)
        var tick = start
        repeat(1_000) { tick = tick.after(heard, delta) }
        assertEquals(1_001L, tick.serial)
        assertEquals(1_000 * delta.toDouble(), tick.instant - start.instant, 1e-6)
        assertTrue(tick.timeSeconds - start.timeSeconds > 6.5f, "the clock moved ${tick.timeSeconds - start.timeSeconds} s of 6.94")
        val music = 1_000 * delta * heard.motionRate
        assertTrue(tick.musicTime - start.musicTime > music * 0.9f, "the music clock moved ${tick.musicTime - start.musicTime} of $music")
    }

    @Test
    fun theFirstStepStartsFromNothing() {
        val first = VizTick.first(heard, 0.02f)
        assertEquals(1L, first.serial)
        assertEquals(0.02f, first.timeSeconds)
        assertEquals(0.02f * heard.motionRate, first.musicTime, 1e-7f)
    }
}
