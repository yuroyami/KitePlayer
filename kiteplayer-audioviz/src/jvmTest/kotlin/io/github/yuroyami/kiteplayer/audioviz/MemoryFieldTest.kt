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
}
