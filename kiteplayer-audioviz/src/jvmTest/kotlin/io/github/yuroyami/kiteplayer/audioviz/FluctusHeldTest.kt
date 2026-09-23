package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FluctusSurface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FluctusHeldTest {
    @Test
    fun selectingWhilePausedUsesTheHeldSpectrumAndCompletesAnExplicitWireChoice() {
        val loud = FluctusSurface()
        val silent = FluctusSurface()
        loud.advance(state(tone(0.003f).withPulseHeld(), 0f), wireMode = 2)
        silent.advance(state(tone(0f).withPulseHeld(), 0f), wireMode = 2)
        assertTrue(loud.height.indices.any { abs(loud.height[it] - silent.height[it]) > 0.1f },
            "Selecting a paused drawing must retain the measured spectrum rather than seed silence")
        assertEquals(1f, loud.wireMix, "An explicit paused wire choice must not freeze halfway through a fade")
        val before = loud.height.copyOf()
        val world = loud.worldX.copyOf()
        repeat(30) { loud.advance(state(tone(0f).withPulseHeld(), (it + 1f) / 60f), wireMode = 2) }
        assertContentEquals(before, loud.height, "Later held frames cannot change the seeded audio shape")
        assertContentEquals(world, loud.worldX, "Later held frames cannot rotate the seeded sheet")
    }

    @Test
    fun reliefAndWireControlsWorkAtAnAlreadyConsumedPausedInstant() {
        val surface = FluctusSurface()
        val held = state(tone(0.003f).withPulseHeld(), 1f)
        surface.advance(held, wireMode = 1)
        val height = surface.height.copyOf()
        val worldX = surface.worldX.copyOf()
        val worldY = surface.worldY.copyOf()
        val worldZ = surface.worldZ.copyOf()
        surface.configure(deformation = 2f, wireMode = 2)
        assertEquals(1f, surface.wireMix)
        for (index in height.indices) assertEquals(height[index] * 2f, surface.height[index])
        // The same render state is normally deduplicated. Explicit user controls still apply.
        surface.advance(held, deformation = 1f, wireMode = 1)
        assertEquals(0f, surface.wireMix)
        assertContentEquals(height, surface.height)
        assertContentEquals(worldX, surface.worldX)
        assertContentEquals(worldY, surface.worldY)
        assertContentEquals(worldZ, surface.worldZ)
        surface.configure(deformation = 1f, wireMode = 1)
        assertContentEquals(height, surface.height, "Unchanged paused controls are pixel-stable")
    }

    @Test
    fun theLowestCameraAngleKeepsTheShadowOnTheGround() {
        val surface = FluctusSurface()
        surface.advance(state(tone(0.003f).withPulseHeld(), 0f))
        for ((width, height) in listOf(1080f to 2400f, 1920f to 1080f)) {
            for (zoom in listOf(0.6f, 1.4f)) {
                surface.project(width, height, zoom = zoom, tiltDegrees = -20f)
                assertTrue(surface.shadowY.all { it.isFinite() && it >= surface.horizon },
                    "The orbit must stay above the floor so its shadow stays below the horizon")
            }
        }
    }

    private fun state(frame: SpectrumFrame, time: Float): VizRenderState = VizRenderState(
        frame = frame, timeSeconds = time, deltaSeconds = if (time == 0f) 0f else 1f / 60f,
        palette = VizPalette.Prism, musicTime = time,
    )

    private fun tone(amplitude: Float): SpectrumFrame {
        val analyser = SpectrumAnalyzer(sampleRate = 48_000, fftSize = 2_048)
        val samples = FloatArray(4_096) { (sin(2 * PI * 2_250 * it / 48_000) * amplitude).toFloat() }
        analyser.feed(samples, samples.size, channels = 1, ptsMicros = 0L)
        return analyser.latest.also { assertNotNull(it.power) }
    }
}
