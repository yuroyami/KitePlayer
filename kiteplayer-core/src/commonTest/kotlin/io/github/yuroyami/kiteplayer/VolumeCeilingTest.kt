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
        // below the knee be exactly the multiply a user expects.
        for (value in listOf(0f, 0.1f, 0.5f, 0.74f, 0.75f, -0.75f, -0.3f)) {
            assertEquals(value, KotlinAudioRing.softClip(value), "softClip($value) must be the identity")
        }
    }

    @Test
    fun `the saturator folds above the knee and never reaches full scale`() {
        for (value in listOf(0.76f, 0.9f, 1f, 1.5f, 2f, 8f, 1000f)) {
            val folded = KotlinAudioRing.softClip(value)
            assertTrue(folded < 1f, "softClip($value) returned $folded, which is not inside full scale")
            assertTrue(folded > 0.75f, "softClip($value) returned $folded, which fell below the knee")
            // Monotonic, so a louder input is never quieter after folding: a curve that turned over
            // would invert the waveform's peaks and sound worse than the clipping it replaced.
            assertTrue(
                folded >= KotlinAudioRing.softClip(value - 0.01f),
                "softClip is not monotonic around $value",
            )
        }
    }

    @Test
    // No comma in this name: a backtick test name containing one compiles on JVM and breaks every
    // Kotlin/Native target. See GOTCHAS.md section 4.
    fun `the saturator is odd-symmetric so a boost adds no DC offset`() {
        for (value in listOf(0.8f, 1f, 3f)) {
            assertEquals(
                KotlinAudioRing.softClip(value),
                -KotlinAudioRing.softClip(-value),
                "softClip is not odd-symmetric at $value",
            )
        }
    }

    @Test
    fun `full scale at double gain folds to the documented value`() {
        // The number a consumer will see first, written out longhand: excess over the knee is
        // (2 - 0.75) / 0.25 = 5, and the fold maps it to 5 / 6 of the remaining headroom.
        assertEquals(0.75f + 0.25f * (5f / 6f), KotlinAudioRing.softClip(2f))
    }
}
