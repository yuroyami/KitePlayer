package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FlashGuard
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a reduced-motion setting takes away, and what it leaves.
 *
 * It must damp what throws the picture about, and it must not empty the picture: a still frame with
 * a moving spectrum in it reads as broken rather than as calm. The setting reaches a drawing as
 * [VizRenderState.motionScale], so this drives the camera at both settings and compares.
 */
class ReducedMotionTest {

    private val step = 1f / 60f

    /** The widest swing of the camera over a run of drums, at one motion scale. */
    private fun swing(scale: Float): Triple<Float, Float, Float> {
        val camera = Camera2D(wander = 0.08f, punch = 0.12f, shake = 0.006f, cuts = true, seed = 7)
        val player = SongPlayer(SyntheticSong.drumLoop(8f))
        var pan = 0f
        var zoom = 0f
        var angle = 0f
        var time = 0f
        repeat(300) {
            val frame = player.next(step)
            time += step
            val state = VizRenderState(frame, time, step, VizPalette.Prism)
            state.motionScale = scale
            camera.advance(state)
            pan = maxOf(pan, abs(camera.panX))
            zoom = maxOf(zoom, abs(camera.zoom - 1f))
            angle = maxOf(angle, abs(camera.angle))
        }
        return Triple(pan, zoom, angle)
    }

    @Test
    fun reducedMotionDampsTheCameraAndKeepsItAlive() {
        val (fullPan, fullZoom, fullAngle) = swing(1f)
        val (calmPan, calmZoom, calmAngle) = swing(0.15f)
        println("camera swing, full then reduced: pan $fullPan $calmPan, zoom $fullZoom $calmZoom, roll $fullAngle $calmAngle")
        // Not zero: the zoom also carries a slow swell with the loudness, which the setting keeps
        // on purpose. What has to go is the punch on every kick, and that is most of the rest.
        assertTrue(calmZoom < fullZoom * 0.65f, "the zoom punch survived: $calmZoom against $fullZoom")
        assertTrue(calmPan < fullPan, "the camera moved as far sideways: $calmPan against $fullPan")
        assertTrue(calmPan > 0f, "the camera stopped dead, which reads as broken rather than as calm")
    }

    @Test
    fun reducedMotionAllowsNoFlashAtAll() {
        val guard = FlashGuard(mostPerSecond = 0)
        var held = 0
        repeat(120) { index ->
            val wanted = if ((index / 3) % 2 == 0) 0.05f else 0.85f
            if (guard.limit(wanted, 0f, step) < wanted - 1e-4f) held++
        }
        assertTrue(held > 0, "a flash train passed a guard that allows none")
        assertTrue(guard.recent == 0, "the guard counted ${guard.recent} flashes it should have held")
    }
}
