package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The boost's policy boundary, and the curve that makes it safe.
 *
 * The ring can multiply by up to 2 and folds anything that would leave full scale. Whether a
 * PLAYER offers that is a separate question with a separate answer: the default ceiling is unity,
 * because amplifying audio nobody asked to have amplified is not a decision a library makes for
 * its consumer. Raising `AudioConfig.volumeCeiling` is how a consumer asks.
 *
 * The sample-level behaviour is proven where it lives, against the C ring it has to agree with:
 * `kiteplayer-rt/native/tests/test_ring_gain_boost.c` for the arithmetic and
 * `AudioRingDifferentialTest` for the two implementations matching bit for bit. What is left here
 * is the boundary and the curve's shape, both of which are common code and testable everywhere.
 */
class VolumeCeilingTest {

    @Test
    fun `the default configuration refuses any boost`() {
        val config = PlayerConfig()
        assertEquals(1f, config.audio.volumeCeiling, "the default ceiling is unity")
    }

    @Test
    fun `a ceiling is accepted between unity and the ring's maximum`() {
        assertEquals(1.5f, PlayerConfig(audio = AudioConfig(volumeCeiling = 1.5f)).audio.volumeCeiling)
        assertEquals(2f, PlayerConfig(audio = AudioConfig(volumeCeiling = 2f)).audio.volumeCeiling)
    }

    @Test
    fun `a ceiling below unity or past the maximum is refused at construction`() {
        // Below unity would be an attenuation nobody can undo through the public setter, and past
        // the ring's maximum would be a value the ring silently clamps, which is worse than a
        // refusal because it reads back as accepted.
        assertFailsWith<IllegalArgumentException> { AudioConfig(volumeCeiling = 0.5f) }
        assertFailsWith<IllegalArgumentException> { AudioConfig(volumeCeiling = 2.5f) }
        assertFailsWith<IllegalArgumentException> { AudioConfig(volumeCeiling = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { AudioConfig(volumeCeiling = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `the saturator is the identity up to the knee`() {
        // Everything an ordinary mix contains passes through untouched, which is what lets a boost
        // below the knee be exactly the multiply a user expects. At a large boost the knee is 0.75.
        for (value in listOf(0f, 0.1f, 0.5f, 0.74f, 0.75f, -0.75f, -0.3f)) {
            assertEquals(value, KotlinAudioRing.softClip(value, 2f), "softClip($value) must be the identity")
        }
    }

    /** The knee follows the gain, so a boosted sample that stays below full scale is untouched (#822). */
    @Test
    fun aSmallBoostLeavesEverythingBelowFullScaleUntouched() {
        val gain = 1.0001f
        for (input in listOf(0.5f, 0.8f, 0.891f, 0.95f, 0.9998f)) {
            assertEquals(input * gain, KotlinAudioRing.softClip(input * gain, gain), "input $input")
        }
    }

    @Test
    fun `the saturator folds above the knee and never passes full scale`() {
        for (value in listOf(0.76f, 0.9f, 1f, 1.5f, 2f, 8f, 1000f)) {
            val folded = KotlinAudioRing.softClip(value, 2f)
            assertTrue(folded <= 1f, "softClip($value) returned $folded, which is past full scale")
            assertTrue(folded > 0.75f, "softClip($value) returned $folded, which fell below the knee")
            // Monotonic, so a louder input is never quieter after folding: a curve that turned over
            // would invert the waveform's peaks and sound worse than the clipping it replaced.
            assertTrue(
                folded >= KotlinAudioRing.softClip(value - 0.01f, 2f),
                "softClip is not monotonic around $value",
            )
        }
    }

    /** A full-scale peak plays at full scale at every gain, so it never drops as the volume rises (#822). */
    @Test
    fun aFullScalePeakNeverGetsQuieterAsTheVolumeRises() {
        var previous = 1f
        for (gain in listOf(1.0001f, 1.001f, 1.01f, 1.1f, 1.25f, 1.5f, 2f)) {
            val peak = KotlinAudioRing.softClip(gain, gain)
            assertEquals(1f, peak, "full scale at gain $gain")
            assertTrue(peak >= previous)
            previous = peak
            // And a level just below full scale is never turned down below what unity gave it.
            assertTrue(KotlinAudioRing.softClip(0.9f * gain, gain) >= 0.9f, "0.9 at gain $gain")
        }
    }

    @Test
    // No comma in this name: a backtick test name containing one compiles on JVM and breaks every
    // Kotlin/Native target. See CLAUDE.md.
    fun `the saturator is odd-symmetric so a boost adds no DC offset`() {
        for (value in listOf(0.8f, 1f, 3f)) {
            assertEquals(
                KotlinAudioRing.softClip(value, 2f),
                -KotlinAudioRing.softClip(-value, 2f),
                "softClip is not odd-symmetric at $value",
            )
        }
    }

    @Test
    fun `full scale at double gain folds to the documented value`() {
        // Written out longhand: 0.9 at gain 2 is 1.8, its excess over the knee of 0.75 is
        // 1.05 / 1.25 = 0.84 of the way to the boosted full scale, and the fold maps that to
        // 2 * 0.84 / 1.84 of the remaining headroom. Full scale itself maps to full scale.
        assertEquals(0.75f + 0.25f * (1.68f / 1.84f), KotlinAudioRing.softClip(1.8f, 2f), 1e-6f)
        assertEquals(1f, KotlinAudioRing.softClip(2f, 2f))
    }
}
