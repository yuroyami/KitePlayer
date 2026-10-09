package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.LaceReaction
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
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

    @Test
    fun theExtraChannelIsCarriedWithTheInk() {
        val field = MemoryField(rows = 36, withExtra = true)
        field.size(1f)
        field.halfLife = 100f
        val lace = checkNotNull(field.extra)
        val column = 9
        for (row in 16 until 20) for (c in column until column + 4) lace[row * field.columns + c] = 1f
        val before = field.extraAt(field.xOf(column + 2), field.yOf(18))
        field.advance(Flows.Drift(0.5f, 0f), 1f)
        assertTrue(before > 0.8f, "the lace was written, read $before")
        assertTrue(field.extraAt(field.xOf(column + 2) + 0.5f, field.yOf(18)) > 0.6f, "the lace moved with the flow")
        assertTrue(field.extraAt(field.xOf(column + 2), field.yOf(18)) < 0.3f, "and left its old place")
    }

    @Test
    fun aFieldWithoutAnExtraChannelGrowsNoLace() {
        val field = MemoryField(rows = 36)
        field.size(1f)
        LaceReaction().step(field, FloatArray(field.columns * field.rows) { 1f })
        assertEquals(null, field.extra)
        assertEquals(0f, field.extraAt(0f, 0f))
    }

    @Test
    fun laceGrowsInsideTheMaskAndNowhereElse() {
        val field = MemoryField(rows = 36, withExtra = true)
        field.size(16f / 9f)
        val columns = field.columns
        val mask = FloatArray(columns * field.rows) { cell -> if (cell % columns < columns / 2) 1f else 0f }
        val reaction = LaceReaction()
        reaction.sprout(field, mask, 12, 1.6f, Rng(7L))
        val start = laced(field, leftHalf = true)
        repeat(1_500) { reaction.step(field, mask) }
        val grown = laced(field, leftHalf = true)
        println("lace: $start cells seeded, $grown laced after 1500 steps")
        assertTrue(start > 0, "the seeds were planted inside the mask")
        assertTrue(grown > start, "the lace grows inside the mask: $start to $grown")
        assertEquals(0, laced(field, leftHalf = false), "nothing grows outside the mask")
    }

    private fun laced(field: MemoryField, leftHalf: Boolean): Int {
        val lace = checkNotNull(field.extra)
        var count = 0
        for (cell in lace.indices) {
            val inLeft = cell % field.columns < field.columns / 2
            if (inLeft == leftHalf && lace[cell] > 0.25f) count++
        }
        return count
    }
}
