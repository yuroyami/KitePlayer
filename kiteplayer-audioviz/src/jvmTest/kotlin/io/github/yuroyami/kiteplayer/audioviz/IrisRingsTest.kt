package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.IrisRings
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Iris's rings: past waveforms as dots, in a fixed pool, spreading by the step they are given. */
class IrisRingsTest {

    private val scope = FloatArray(256) { 0.5f * sin(2f * PI.toFloat() * 4f * it / 256f) }

    @Test
    fun aRingKeepsTheWaveformItWasBornWith() {
        val rings = IrisRings(capacity = 12, dots = 128)
        rings.birth(scope, gain = 2f, startRadius = 0.5f, hue = 0f, turn = 0f, strength = 1f, most = 12)
        assertEquals(1L, rings.born)
        assertEquals((scope.sampleAt(32 / 127f) * 2f).coerceIn(-1f, 1f), rings.sampleOf(0, 32), 1e-6f)
    }

    @Test
    fun aRingSpreadsOnlyByTheStepItIsGiven() {
        val rings = IrisRings(capacity = 12, dots = 128)
        rings.birth(scope, 1f, 0.5f, 0f, 0f, 1f, most = 12)
        rings.advance(radiusStep = 0f, cycles = 0f, innermost = 0f)
        assertEquals(0.5f, rings.radiusOf(0), 1e-6f)
        rings.advance(radiusStep = 0.1f, cycles = 0.1f, innermost = 0f)
        assertEquals(0.6f, rings.radiusOf(0), 1e-6f)
    }

    @Test
    fun aRingLivesEightCyclesAndThenGoes() {
        val rings = IrisRings(capacity = 12, dots = 128)
        rings.birth(scope, 1f, 0.5f, 0f, 0f, 1f, most = 12)
        rings.advance(0.01f, 7.9f, 0f)
        assertEquals(1, rings.alive)
        assertTrue(rings.lightOf(0) in 0.001f..0.05f, "an old ring is nearly faded, light ${rings.lightOf(0)}")
        rings.advance(0.01f, 0.2f, 0f)
        assertEquals(0, rings.alive)
    }

    @Test
    fun noMoreThanTheFormsNumberOfRingsStay() {
        val rings = IrisRings(capacity = 12, dots = 128)
        repeat(10) {
            rings.birth(scope, 1f, 0.5f, 0f, 0f, 1f, most = 4)
            rings.advance(0f, 0.3f, 0f)
        }
        assertEquals(4, rings.alive)
        assertEquals(4, rings.kept)
        assertEquals(10L, rings.born)
    }

    @Test
    fun aRingFlyingInwardDiesAtThePupil() {
        val rings = IrisRings(capacity = 12, dots = 128)
        rings.birth(scope, 1f, 0.5f, 0f, 0f, 1f, most = 12)
        rings.advance(radiusStep = -0.45f, cycles = 0.1f, innermost = 0.1f)
        assertEquals(0, rings.alive)
    }

    @Test
    fun aPositiveSampleComesNearerAsWavySpiralsDotsDid() {
        assertEquals(175f, IrisRings.depthOf(0f), 1e-4f)
        assertEquals(175f - 0.5f * 192 * 0.18f, IrisRings.depthOf(0.5f), 1e-3f)
        assertTrue(IrisRings.depthOf(1f) < IrisRings.depthOf(0.5f))
        assertEquals(175f, IrisRings.depthOf(-1f), 1e-4f)
    }
}
