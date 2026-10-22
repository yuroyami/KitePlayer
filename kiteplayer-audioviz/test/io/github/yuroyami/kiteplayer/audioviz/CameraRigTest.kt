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

    private fun frame(kick: Float = 0f, drop: Boolean = false, level: Float = 0.5f, snare: Float = 0f): SpectrumFrame = SpectrumFrame(
        ptsMicros = 0L,
        bands = FloatArray(4),
        peaks = FloatArray(4),
        scope = FloatArray(4),
        level = level,
        bass = 0f,
        mid = 0f,
        treble = 0f,
        beat = kick,
        pulse = 0f,
        kick = kick,
        snare = snare,
        energy = 0.5f,
        mood = 0.5f,
        drop = drop,
        dropPulse = if (drop) 1f else 0f,
        events = if (!drop) null else AudioEventDelivery(io.github.yuroyami.kiteplayer.Generation.Initial,
            0L, 0L, arrayOf(DeliveredAudioEvent(AudioEvent(io.github.yuroyami.kiteplayer.Generation.Initial, 0L, 0L,
                AudioDetection(AudioEventKind.Drop, 0L, 0L, 1f, 0.9f, 0.8f)), 0L))),
    )

    /** A paused player keeps its levels on screen; a silence has none. The camera stands still in both. */
    @Test
    fun nothingFliesWhilePausedOrSilent() {
        for ((name, still) in listOf("paused" to frame().withPulseHeld(), "silent" to frame(level = 0f))) {
            val rig = CameraRig(topSpeed = 10f)
            var time = 0f
            rig.advance(VizRenderState(still, time, 1f / 60f, VizPalette.Classic, time))
            val eyeX = rig.eyeX
            val eyeY = rig.eyeY
            repeat(240) {
                time += 1f / 60f
                rig.advance(VizRenderState(still, time, 1f / 60f, VizPalette.Classic, time))
            }
            assertTrue(rig.travelled == 0f, "$name: the camera flew ${rig.travelled} with nothing to hear")
            assertTrue(rig.eyeX == eyeX && rig.eyeY == eyeY, "$name: the camera wandered from $eyeX, $eyeY to ${rig.eyeX}, ${rig.eyeY}")
        }
        // The same music, audible: it flies.
        val rig = CameraRig(topSpeed = 10f)
        var time = 0f
        repeat(240) {
            rig.advance(VizRenderState(frame(), time, 1f / 60f, VizPalette.Classic, time))
            time += 1f / 60f
        }
        assertTrue(rig.travelled > 1f, "audible music should fly, travelled ${rig.travelled}")
    }

    @Test
    fun withTheQueueTheSurgePeaksOnTheKick() {
        val step = 1f / 240f
        val kickStep = 240
        val event = AudioEvent(io.github.yuroyami.kiteplayer.Generation.Initial, 0L, 0L,
            AudioDetection(AudioEventKind.LowTransient, 1_000_000L, 1_010_000L, 1f, 0.8f, 0.5f))
        fun peakAgainstKick(queued: Boolean): Float {
            val rig = CameraRig(topSpeed = 10f)
            var fastest = 0f
            var fastestAt = 0f
            for (index in 0 until 400) {
                val time = index * step
                val now = frame(kick = if (index == kickStep) 1f else 0f)
                val future = if (queued && index < kickStep) {
                    object : VizFuture {
                        override fun at(secondsAhead: Float): SpectrumFrame = frame()
                        override val nextOnsetSeconds: Float = (kickStep - index) * step
                        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? =
                            if (kind == AudioEventKind.LowTransient && nextOnsetSeconds <= 0.1f)
                                UpcomingAudioEvent(event, nextOnsetSeconds) else null
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

    @Test
    fun reducedMotionDampsTheSnareNudgeAndTheDropWiden() {
        fun swing(motionScale: Float): Pair<Float, Float> {
            val rig = CameraRig(topSpeed = 10f, baseFov = 70f)
            var widest = 0f
            var farthest = 0f
            for (index in 0 until 240 * 3) {
                val time = index / 240f
                val now = frame(drop = index == 10, snare = if (index == 20) 1f else 0f)
                rig.advance(VizRenderState(now, time, 1f / 240f, VizPalette.Classic, time).also { it.motionScale = motionScale })
                widest = maxOf(widest, rig.fov - 70f)
                farthest = maxOf(farthest, abs(rig.nudgeX))
            }
            return widest to farthest
        }
        val (wide, nudge) = swing(1f)
        val (calmWide, calmNudge) = swing(0.15f)
        println("full motion: the lens widened $wide degrees and the nudge reached $nudge; reduced: $calmWide and $calmNudge")
        assertTrue(wide > 10f && nudge > 0.05f, "the fixture must move the lens and the eye: $wide, $nudge")
        assertTrue(calmWide <= wide * 0.3f, "reduced motion should damp the drop's fling: $calmWide against $wide")
        assertTrue(calmNudge <= nudge * 0.3f, "reduced motion should damp the snare's nudge: $calmNudge against $nudge")
    }
}
