package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
import io.github.yuroyami.kiteplayer.audioviz.viz.fluid.FluidGrid
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SmokeVortices
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeVorticesTest {
    private fun state(busy: Boolean, dt: Float = 1f / 60f, held: Boolean = false): VizRenderState {
        val frame = SpectrumFrame(0L, FloatArray(40), FloatArray(40), FloatArray(64),
            0.6f, 0.3f, 0.3f, 0.3f, 0f, 0f, energy = 0.6f,
            density = if (busy) 1f else 0f, mood = if (busy) 1f else 0f, held = held)
        return VizRenderState(frame, 1f, dt, VizPalette.Prism)
    }

    @Test
    fun busyMusicAcceleratesTheOrbitAndPauseFreezesIt() {
        val quiet = SmokeVortices(); val busy = SmokeVortices()
        repeat(240) {
            quiet.advance(state(false), 1f, 0.055f, 0f)
            busy.advance(state(true), 1f, 0.055f, 0f)
        }
        assertTrue(busy.phase > quiet.phase * 3, "Texture density must affect motion even at equal loudness")
        val phase = busy.phase; val radius = busy.radius
        repeat(60) { busy.advance(state(true, held = true), 1f, 0.1f, 1f) }
        assertEquals(phase, busy.phase)
        assertEquals(radius, busy.radius)
        repeat(60) { busy.advance(state(true).apply { motionScale = 0f }, 1f, 0.055f, 0f) }
        assertEquals(phase, busy.phase)
        busy.reset()
        assertEquals(0.0, busy.phase)
    }

    @Test
    fun vorticesCurlRealFluidAndConsumeDyeAtTheirCores() {
        val holes = SmokeVortices()
        repeat(60) { holes.advance(state(true), 0f, 0.055f, 0f) }
        val grid = FluidGrid(128, 72, 1)
        grid.dye[0].fill(1f)
        holes.stir(grid, 1f / 60f, 2, 16f / 9f, 1f, 0.8f, 1f)
        val rightOfFirst = grid.index((holes.x[0] * 128 + 4).toInt(), (holes.y[0] * 72).toInt())
        val rightOfSecond = grid.index((holes.x[1] * 128 + 4).toInt(), (holes.y[1] * 72).toInt())
        assertTrue(grid.velocityY[rightOfFirst] > 0f, "The first wake must turn clockwise")
        assertTrue(grid.velocityY[rightOfSecond] < 0f, "The second wake must turn counterclockwise")
        assertTrue(grid.velocityX[rightOfFirst] < 0f, "Smoke must be pulled towards the core")
        assertTrue(grid.dye[0].all { it == 1f }, "Velocity forces must not bypass the absorption control")
        holes.condense(grid, 1f / 60f, 2, 16f / 9f, 1f, 0.8f, 1f, 1f)
        val core = grid.index((holes.x[0] * 128).toInt(), (holes.y[0] * 72).toInt())
        assertTrue(grid.dye[0][core] < 0.95f, "The core absorbs smoke")
        grid.clear()
        holes.stir(grid, 1f / 60f, 0, 16f / 9f, 1f, 0.8f, 1f)
        assertTrue(grid.velocityX.all { it == 0f } && grid.velocityY.all { it == 0f })
    }

    @Test
    fun lensIsLocalAndRemainsFiniteInBothOrientations() {
        val holes = SmokeVortices()
        for (aspect in listOf(16f / 9f, 9f / 16f)) {
            holes.lens(holes.x[0] + holes.radius * 1.5f * minOf(1f, aspect) / aspect, holes.y[0], 2, aspect, 1f)
            assertTrue(abs(holes.sampleY - holes.y[0]) > 0.002f, "Smoke light must bend around the core")
            val inside = holes.x[0] + holes.radius * 0.3f * minOf(1f, aspect) / aspect
            holes.lens(inside, holes.y[0], 1, aspect, 2f)
            assertEquals(inside, holes.sampleX, 1e-6f, "The lens must preserve the absorbing interior")
            assertEquals(holes.y[0], holes.sampleY)
            holes.lens(0f, 0f, 2, aspect, 2f)
            assertEquals(0f, holes.sampleX); assertEquals(0f, holes.sampleY)
            holes.lens(holes.x[0], holes.y[0], 2, aspect, 2f)
            assertTrue(holes.sampleX.isFinite() && holes.sampleY.isFinite())
            holes.lens(0.3f, 0.4f, 2, aspect, 0f)
            assertEquals(0.3f, holes.sampleX); assertEquals(0.4f, holes.sampleY)
        }
    }

    @Test
    fun smokeCondensesOutsideTheHorizonAndIsLostInsideAfterTheFluidSolve() {
        val holes = SmokeVortices()
        repeat(60) { holes.advance(state(true), 0f, 0.075f, 0f) }
        val grid = FluidGrid(128, 72, 1)
        grid.dye[0].fill(0.2f)
        repeat(30) {
            holes.stir(grid, 1f / 60f, 1, 16f / 9f, 1f, 0.8f, 1f)
            grid.step(1f / 60f, 8f, 0.88f, 0.8f)
            holes.condense(grid, 1f / 60f, 1, 16f / 9f, 1f, 0.8f, 1f, 1f)
        }
        val core = grid.index((holes.x[0] * 128).toInt(), (holes.y[0] * 72).toInt())
        val rim = grid.index((holes.x[0] * 128 + holes.radius * 72 * 1.4f).toInt(),
            (holes.y[0] * 72).toInt())
        println("Horizon density: core=${grid.dye[0][core]}, rim=${grid.dye[0][rim]}")
        assertTrue(grid.dye[0][rim] > 0.24f, "Existing smoke must gather outside the horizon")
        assertTrue(grid.dye[0][core] < grid.dye[0][rim] * 0.25f, "Smoke must disappear through the interior")
        grid.clear()
        holes.condense(grid, 1f / 60f, 2, 16f / 9f, 1f, 1f, 1f, 1f)
        assertTrue(grid.dye[0].all { it == 0f }, "An empty field must not manufacture a glowing ring")
    }

    @Test
    fun nearbyEmbersBendAndFadeThroughTheSameHorizon() {
        val holes = SmokeVortices()
        val pool = Particles(3)
        pool.spawn(holes.x[0] + holes.radius * 1.4f, holes.y[0], 0f, 0f, 3f, 0f, 0.01f)
        pool.spawn(holes.x[0], holes.y[0], 0f, 0f, 3f, 0f, 0.01f)
        pool.spawn(0.95f, 0.95f, 0f, 0f, 3f, 0f, 0.01f)
        holes.carryEmbers(pool, 1f / 60f, 1, 1f, 1f, 1f, 1f, 1f)
        assertTrue(pool.velocityX[0] < 0f && pool.velocityY[0] > 0f)
        assertTrue(pool.life[1] < 2f)
        assertEquals(3f, pool.life[2])
    }

    @Test
    fun horizonControlsDisableTransportAndExtremeSettingsStayBounded() {
        val holes = SmokeVortices()
        val grid = FluidGrid(64, 40, 1)
        grid.dye[0].fill(0.2f)
        holes.condense(grid, 1f / 30f, 0, 0.6f, 2.5f, 2f, 2f, 1f)
        assertTrue(grid.dye[0].all { it == 0.2f })
        holes.condense(grid, 1f / 30f, 2, 0.6f, 2.5f, 2f, 2f, 0f)
        assertTrue(grid.dye[0].all { it == 0.2f })
        repeat(120) {
            holes.advance(state(true, 1f / 30f), 2f, 0.14f, 1f)
            holes.condense(grid, 1f / 30f, 2, 0.6f, 2.5f, 2f, 2f, 1f)
        }
        assertTrue(grid.dye[0].all { it.isFinite() && it in 0f..12f })
    }

    @Test
    fun orbitRateDoesNotDependOnDisplayRefresh() {
        val thirty = SmokeVortices(); val sixty = SmokeVortices()
        repeat(120) { thirty.advance(state(true, 1f / 30f), 1f, 0.055f, 0f) }
        repeat(240) { sixty.advance(state(true), 1f, 0.055f, 0f) }
        assertTrue(abs(thirty.phase - sixty.phase) < 0.02)
    }
}
