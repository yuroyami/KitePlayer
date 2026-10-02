package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryFieldTest {

    private val out = FloatArray(2)

    @Test
    fun aSwirlTurnsAndATunnelPullsOutward() {
        Flows.Swirl(1f).at(1f, 0f, 1.78f, out)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        Flows.Tunnel(0.5f).at(1f, 0f, 1.78f, out)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
        Flows.Burst(0.5f).at(0f, 1f, 1.78f, out)
        assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test
    fun aKaleidoPullsTowardsTheNearestWedgeMirror() {
        // Six wedges: a point at 40 degrees is pulled back towards 30 degrees, the middle of its wedge.
        Flows.Kaleido(6, 1f).at(kotlin.math.cos(0.698f), kotlin.math.sin(0.698f), 1f, out)
        assertTrue(out[1] < 0f && out[0] > 0f, "pulled towards the middle line: ${out.toList()}")
    }

    @Test
    fun aMixBlendsTwoFlows() {
        val mixed = Flows.Mixed(Flows.Drift(1f, 0f), Flows.Drift(0f, 1f), 0.25f)
        mixed.at(0f, 0f, 1f, out)
        assertEquals(0.75f, out[0], 1e-6f)
        assertEquals(0.25f, out[1], 1e-6f)
    }

    @Test
    fun inkDecaysByHalfLife() {
        val field = io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField(rows = 36)
        field.size(1f)
        field.halfLife = 1f
        field.disc(0f, 0f, 0.3f, 1f)
        val before = field.inkAt(0f, 0f)
        field.advance(Flows.Drift(0f, 0f), 1f)
        assertEquals(before * 0.5f, field.inkAt(0f, 0f), 0.02f)
        assertEquals(1f, field.ageAt(0f, 0f), 0.02f)
    }

    @Test
    fun aDriftCarriesInkAlong() {
        val field = io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField(rows = 36)
        field.size(1f)
        field.halfLife = 100f
        field.disc(-0.5f, 0f, 0.2f, 1f)
        field.advance(Flows.Drift(1f, 0f), 0.5f)
        assertTrue(field.inkAt(0f, 0f) > 0.6f, "the disc moved half a unit to the right: ${field.inkAt(0f, 0f)}")
        assertTrue(field.inkAt(-0.5f, 0f) < 0.3f, "and left its old place: ${field.inkAt(-0.5f, 0f)}")
    }

    @Test
    fun aLineIsInkedAlongItsWholeLength() {
        val field = io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField(rows = 36)
        field.size(1f)
        val xs = floatArrayOf(-0.8f, 0.8f)
        val ys = floatArrayOf(-0.4f, 0.4f)
        field.line(xs, ys, 2, width = 1.5f, ink = 1f)
        assertTrue(field.inkAt(0f, 0f) > 0.8f, "on the line at the middle: ${field.inkAt(0f, 0f)}")
        assertTrue(field.inkAt(0.4f, 0.2f) > 0.8f, "on the line further along: ${field.inkAt(0.4f, 0.2f)}")
        assertEquals(0f, field.inkAt(0.4f, -0.6f), 0.01f)
    }

    @Test
    fun nothingMovesWithoutHeardTime() {
        val field = io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField(rows = 36)
        field.size(1f)
        field.disc(0f, 0f, 0.3f, 1f)
        val before = field.inkAt(0f, 0f)
        field.advance(Flows.Tunnel(5f), 0f)
        assertEquals(before, field.inkAt(0f, 0f))
    }
}
