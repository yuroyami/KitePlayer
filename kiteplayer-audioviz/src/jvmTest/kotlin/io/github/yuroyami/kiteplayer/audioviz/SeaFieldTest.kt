package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared pieces Contour's sea is built from: vortices, the extra channel, the lace and the drop. */
class SeaFieldTest {

    private val out = FloatArray(2)

    @Test
    fun aVortexTurnsAtItsSpeedOnTheEdgeOfItsCore() {
        val flow = Flows.Vortices({ 1 }, floatArrayOf(0.5f), floatArrayOf(-0.2f), floatArrayOf(0.4f), core = 0.25f)
        flow.at(0.75f, -0.2f, 1.78f, out)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.4f, out[1], 1e-5f)
        flow.at(0.5f, -0.2f, 1.78f, out)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
    }

    @Test
    fun aNegativeSpeedTurnsTheOtherWay() {
        val flow = Flows.Vortices({ 1 }, floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(-0.4f), core = 0.25f)
        flow.at(0.25f, 0f, 1f, out)
        assertEquals(-0.4f, out[1], 1e-5f)
    }

    @Test
    fun theCountSaysHowManyStir() {
        var count = 0
        val flow = Flows.Vortices({ count }, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), floatArrayOf(0.4f, 0.4f))
        flow.at(0.5f, 0.3f, 1.78f, out)
        assertEquals(0f, hypot(out[0], out[1]), 1e-6f)
        count = 1
        flow.at(0.5f, 0.3f, 1.78f, out)
        val one = hypot(out[0], out[1])
        count = 2
        flow.at(0.5f, 0.3f, 1.78f, out)
        assertTrue(one > 0f && hypot(out[0], out[1]) != one, "the second vortex adds its own turn")
    }

    @Test
    fun aVortexSlowsFarFromItsCore() {
        val flow = Flows.Vortices({ 1 }, floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(0.4f), core = 0.25f)
        flow.at(0.25f, 0f, 1f, out)
        val near = hypot(out[0], out[1])
        flow.at(1f, 0f, 1f, out)
        assertTrue(hypot(out[0], out[1]) < 0.5f * near, "four cores out the water turns far slower")
    }
}
