package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyFlight
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyCamera
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdysseyMotionTest {
    private class Run {
        val flight = OdysseyFlight()
        private val gestures = Gestures()
        private var time = 0f
        fun step(busy: Boolean, hit: Boolean = false, held: Boolean = false, motion: Float = 1f, dt: Float = 1f / 60f) {
            time += dt
            val level = if (busy) 0.85f else 0.18f
            val frame = SpectrumFrame((time * 1_000_000).toLong(), FloatArray(40) { level },
                FloatArray(40) { level }, FloatArray(256), level, level, level * 0.8f, level * 0.7f,
                0f, 0f, kick = if (hit) 0.9f else 0f, energy = level,
                density = if (busy) 0.85f else 0.05f, mood = if (busy) 0.85f else 0.08f,
                novelty = if (busy) 1.6f else 0.02f, held = held)
            val state = VizRenderState(frame, time, dt, VizPalette.Prism).apply { motionScale = motion }
            gestures.update(state)
            flight.advance(state, gestures, 1f, 1f, 1.15f, 1f, 0.75f, 1f)
        }
    }

    @Test
    fun busyMusicAcceleratesAndTurnsThenReleasesIntoQuiet() {
        val run = Run()
        repeat(240) { run.step(false) }
        val quietSpeed = run.flight.speed
        val quietYaw = abs(run.flight.yaw)
        var left = 0f
        var right = 0f
        repeat(480) {
            run.step(true, hit = it % 30 == 0)
            left = minOf(left, run.flight.yaw); right = maxOf(right, run.flight.yaw)
        }
        val busySpeed = run.flight.speed
        assertTrue(busySpeed > quietSpeed * 5f, "Busy music must produce unmistakable momentum")
        assertTrue(right - left > 0.5f, "The camera must reveal both sides of the world")
        assertTrue(quietYaw < 0.1f, "Quiet music must not trigger a busy camera dance")
        repeat(240) { run.step(false) }
        assertTrue(run.flight.speed < busySpeed * 0.25f, "A quiet section must release the momentum")
    }

    @Test
    fun attacksLaunchTravellingFrontsButHeldTonesDoNot() {
        val run = Run()
        repeat(180) { run.step(true) }
        assertEquals(0f, run.flight.waves[1])
        assertEquals(0f, run.flight.waves[3])
        val speed = run.flight.speed
        run.step(true, hit = true)
        assertTrue(run.flight.waves[1] > 0.5f)
        val front = run.flight.waves[0]
        repeat(12) { run.step(true) }
        assertTrue(run.flight.waves[0] > front + 1f, "The impact must travel through the architecture")
        assertTrue(run.flight.speed > speed, "An attack must provide thrust beyond the section average")
        repeat(420) { run.step(true) }
        assertTrue(run.flight.waves[1] < 0.005f, "A sustained tone must not invent repeating impacts")
    }

    @Test
    fun pauseFreezesFlightAndReducedMotionSuppressesIt() {
        val run = Run()
        repeat(90) { run.step(true, hit = it % 30 == 0) }
        val before = listOf(run.flight.travelled, run.flight.phase, run.flight.yaw.toDouble(), run.flight.waves[0].toDouble())
        repeat(120) { run.step(true, held = true) }
        assertEquals(before, listOf(run.flight.travelled, run.flight.phase, run.flight.yaw.toDouble(), run.flight.waves[0].toDouble()))
        val still = Run()
        repeat(240) { still.step(true, hit = it % 30 == 0, motion = 0f) }
        assertEquals(12.0, still.flight.travelled)
        assertEquals(0f, still.flight.yaw)
        assertEquals(0f, still.flight.bank)
        assertEquals(0f, still.flight.waves[1])
    }

    @Test
    fun bankedCameraKeepsAnOrthonormalBasisThroughItsFullRange() {
        val pose = FloatArray(12)
        for (angle in listOf(-1.5f, -0.4f, 0f, 0.7f, 1.5f)) {
            OdysseyCamera.write(pose, floatArrayOf(angle, angle * 0.7f, 1.2f), 1f,
                0.6f, -0.3f, angle, angle * 0.3f, angle * 0.4f)
            for (a in 1..3) for (b in a..3) {
                val dot = (0..2).sumOf { (pose[a * 3 + it] * pose[b * 3 + it]).toDouble() }
                assertTrue(abs(dot - if (a == b) 1.0 else 0.0) < 0.00001,
                    "Camera turns must retain the field of view without skew")
            }
        }
    }

    @Test
    fun flightRemainsConsistentAtThirtyAndSixtyFrames() {
        val slow = Run(); val fast = Run()
        repeat(240) { slow.step(true, hit = it % 15 == 0, dt = 1f / 30f) }
        repeat(480) { fast.step(true, hit = it % 30 == 0) }
        assertTrue(abs(slow.flight.travelled - fast.flight.travelled) < 3.0)
        assertTrue(abs(slow.flight.yaw - fast.flight.yaw) < 0.04f)
    }
}
