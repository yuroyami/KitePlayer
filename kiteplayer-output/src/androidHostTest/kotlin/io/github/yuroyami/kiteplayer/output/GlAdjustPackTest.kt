package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoAdjustments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The pack law: identity is null (bit-exact off), the 3x3 lands COLUMN-major, and the matrix and
 * the gamma curve each have their own flag.
 */
class GlAdjustPackTest {

    @Test
    fun identityPacksToNullAndBrightnessPacksTransposed() {
        assertNull(GlState.packGlAdjust(VideoAdjustments()), "identity must disable the uniform outright")

        val adjustments = VideoAdjustments(brightness = 0.25f, saturation = 0.5f)
        val rowMajor = adjustments.toColorMatrix()
        val packed = GlState.packGlAdjust(adjustments)!!
        // Column c, row r of the 3x3 sits at packed[c * 3 + r]; the source is row-major 4x5.
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                assertEquals(
                    rowMajor[row * 5 + column],
                    packed[column * 3 + row],
                    "matrix element ($row,$column) must transpose for GLES2",
                )
            }
        }
        assertEquals(rowMajor[4], packed[9], "offset r")
        assertEquals(rowMajor[9], packed[10], "offset g")
        assertEquals(rowMajor[14], packed[11], "offset b")
        assertEquals(1f, packed[12], "brightness must turn the matrix step on")
        assertEquals(0f, packed[14], "a gamma of 1 must leave the curve off, so its bits stay exact")
    }

    @Test
    fun gammaPacksItsExponentAndItsOwnFlag() {
        val packed = assertNotNull(GlState.packGlAdjust(VideoAdjustments(gamma = 2f)))
        assertEquals(15, packed.size, "the blit reads the gamma words after the matrix block")
        assertEquals(0f, packed[12], "a gamma alone must leave the matrix step off")
        assertEquals(0.5f, packed[13], "the exponent is one over gamma")
        assertEquals(1f, packed[14], "a gamma of 2 must turn the curve on")
    }
}
