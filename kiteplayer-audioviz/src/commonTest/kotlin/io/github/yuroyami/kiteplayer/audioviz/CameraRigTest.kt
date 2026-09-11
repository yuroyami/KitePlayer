package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The one camera the flying drawings share. */
class CameraRigTest {

    private fun frame(kick: Float = 0f, drop: Boolean = false): SpectrumFrame = SpectrumFrame(
        ptsMicros = 0L,
        bands = FloatArray(4),
        peaks = FloatArray(4),
        scope = FloatArray(4),
        level = 0f,
        bass = 0f,
        mid = 0f,
        treble = 0f,
        beat = kick,
        pulse = 0f,
        kick = kick,
        energy = 0.5f,
        mood = 0.5f,
        drop = drop,
        dropPulse = if (drop) 1f else 0f,
    )

    @Test
    fun withTheQueueTheSurgePeaksOnTheKick() {
        val step = 1f / 240f
        val kickStep = 240
        fun peakAgainstKick(queued: Boolean): Float {
            val rig = CameraRig(topSpeed = 10f)
            var fastest = 0f
            var fastestAt = 0f
            for (index in 0 until 400) {
                val time = index * step
                val now = frame(kick = if (index == kickStep) 1f else 0f)
                val future = if (queued && index < kickStep) {
                    object : VizFuture {
                        override fun at(secondsAhead: Float): SpectrumFrame = frame(kick = 1f)
                        override val nextOnsetSeconds: Float = (kickStep - index) * step
                    }
                } else {
                    null
                }
                val speed = rig.advance(VizRenderState(now, time, step, VizPalette.Classic, time, future)) / step
                if (index > 120 && speed > fastest) {
                    fastest = speed
                    fastestAt = time
                }
            }
            return fastestAt - kickStep * step
        }
        val early = peakAgainstKick(queued = true)
        val late = peakAgainstKick(queued = false)
        println("fastest moment against the kick: ${early * 1000} ms with the queue, ${late * 1000} ms without")
        assertTrue(abs(early) < 0.03f, "seen coming, the surge should peak on the kick, was ${early * 1000} ms out")
        assertTrue(late > 0.08f, "unseen, the surge can only peak after the kick, was ${late * 1000} ms")
    }

    @Test
    fun aDropFlingsTheLensWideAndItSettles() {
        val rig = CameraRig(topSpeed = 10f, baseFov = 70f)
        var widest = 0f
        for (index in 0 until 240 * 4) {
            val time = index / 240f
            rig.advance(VizRenderState(frame(drop = index == 10), time, 1f / 240f, VizPalette.Classic, time))
            widest = maxOf(widest, rig.fov)
        }
        println("a drop widened the lens to $widest degrees, and four seconds later it was ${rig.fov}")
        assertTrue(widest > 80f, "a drop should widen the lens clearly, it reached $widest")
        assertTrue(abs(rig.fov - 70f) < 2f, "and it should settle back within about a bar or two, was ${rig.fov}")
    }
}
