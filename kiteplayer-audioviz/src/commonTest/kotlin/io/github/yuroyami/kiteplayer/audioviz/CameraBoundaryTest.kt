package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraBoundaryTest {
    private fun state(index: Int, rise: Boolean = false): VizRenderState {
        val time = index / 60f
        val frame = SpectrumFrame(index * 1_000_000L / 60, FloatArray(1), FloatArray(1), FloatArray(1),
            0.3f, 0.3f, 0.3f, 0.3f, 0f, 0f, bpm = 120f, beatConfidence = 1f,
            barPhase = time % 2f / 2f, drop = rise, dropPulse = if (rise) 1f else 0f)
        return VizRenderState(frame, time, 1f / 60f, VizPalette.Classic)
    }

    @Test
    fun countedBarsDoNotCutEitherCamera() {
        val flat = Camera2D(cuts = true, shake = 0f)
        val flatReference = Camera2D(cuts = false, shake = 0f)
        val flying = CameraRig(topSpeed = 1f, cuts = true)
        val flyingReference = CameraRig(topSpeed = 1f, cuts = false)
        repeat(60 * 30) { index ->
            val state = state(index)
            flat.advance(state); flatReference.advance(state)
            flying.advance(state); flyingReference.advance(state)
            assertEquals(flatReference.panX, flat.panX, "counted flat cut at $index")
            assertEquals(flyingReference.eyeX, flying.eyeX, "counted flying cut at $index")
        }
    }

    @Test
    fun anEnergyOnlyRecoveryDoesNotWhipOrWidenEitherCamera() {
        val flat = Camera2D(wander = 0f, roll = 0f, cuts = false, shake = 0f)
        val flying = CameraRig(topSpeed = 1f, cuts = false)
        repeat(120) { index ->
            val state = state(index, rise = index == 1)
            flat.advance(state); flying.advance(state)
            assertEquals(0f, flat.angle)
            assertEquals(70f, flying.fov)
        }
    }
}
