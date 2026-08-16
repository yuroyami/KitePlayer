package io.github.yuroyami.kiteplayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one colour-matrix law every renderer shares, tested by its promises rather than its
 * coefficients: neutral is exactly identity, grey is invariant under saturation and hue,
 * saturation zero is the BT.709 greyscale, and contrast pivots about mid-grey.
 */
class VideoAdjustmentsTest {

    private fun apply(matrix: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> = Triple(
        matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4],
        matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9],
        matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14],
    )

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$message: expected $expected, was $actual")
    }

    @Test
    fun `neutral is exactly the identity matrix`() {
        val identity = VideoAdjustments.Identity
        assertTrue(identity.isIdentity)
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        val matrix = identity.toColorMatrix()
        for (index in expected.indices) {
            assertEquals(expected[index], matrix[index], "element $index")
        }
    }

    @Test
    fun `grey stays grey under any saturation and hue`() {
        for (adjustments in listOf(
            VideoAdjustments(saturation = 0f),
            VideoAdjustments(saturation = 2f),
            VideoAdjustments(hueDegrees = 90f),
            VideoAdjustments(hueDegrees = -145f),
            VideoAdjustments(saturation = 1.5f, hueDegrees = 30f),
        )) {
            val (r, g, b) = apply(adjustments.toColorMatrix(), 0.5f, 0.5f, 0.5f)
            assertNear(0.5f, r, "red of grey through $adjustments")
            assertNear(0.5f, g, "green of grey through $adjustments")
            assertNear(0.5f, b, "blue of grey through $adjustments")
        }
    }

    @Test
    fun `saturation zero is the BT709 greyscale`() {
        val matrix = VideoAdjustments(saturation = 0f).toColorMatrix()
        // Pure red becomes its luma on every channel.
        val (r, g, b) = apply(matrix, 1f, 0f, 0f)
        assertNear(0.2126f, r, "red channel")
        assertNear(0.2126f, g, "green channel")
        assertNear(0.2126f, b, "blue channel")
    }

    @Test
    fun `contrast pivots about mid-grey and brightness lifts additively`() {
        val doubled = VideoAdjustments(contrast = 2f).toColorMatrix()
        val (low, _, _) = apply(doubled, 0.25f, 0.25f, 0.25f)
        assertNear(0f, low, "0.25 at doubled contrast lands on 0")
        val (mid, _, _) = apply(doubled, 0.5f, 0.5f, 0.5f)
        assertNear(0.5f, mid, "the pivot does not move")

        val lifted = VideoAdjustments(brightness = 0.25f).toColorMatrix()
        val (r, _, _) = apply(lifted, 0.5f, 0.5f, 0.5f)
        assertNear(0.75f, r, "brightness is an additive lift")
    }

    @Test
    fun `the alpha row never touches colour`() {
        for (adjustments in listOf(
            VideoAdjustments(brightness = 0.4f, contrast = 1.6f, saturation = 0.3f, hueDegrees = 120f),
        )) {
            val matrix = adjustments.toColorMatrix()
            assertEquals(0f, matrix[15], "alpha row red")
            assertEquals(0f, matrix[16], "alpha row green")
            assertEquals(0f, matrix[17], "alpha row blue")
            assertEquals(1f, matrix[18], "alpha row alpha")
            assertEquals(0f, matrix[19], "alpha row offset")
            assertEquals(0f, matrix[3], "no colour row reads alpha")
        }
    }
}
