package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the web canvas says it cannot draw, instead of drawing it silently wrong. */
class WebColorLimitsTest {

    @Test
    fun hdrIsReportedAsShownWithoutToneMapping() {
        val pq = webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.Bt2020Ncl, transfer = ColorTransfer.Pq))
        assertEquals(1, pq.size, "$pq")
        assertTrue("PQ" in pq.single() && "tone mapping" in pq.single(), pq.single())
        val hlg = webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.Bt2020Ncl, transfer = ColorTransfer.Hlg))
        assertTrue("HLG" in hlg.single(), hlg.single())
    }

    @Test
    fun aGuessedMatrixIsReported() {
        assertTrue("YCgCo" in webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.YCgCo)).single())
        assertTrue("FCC" in webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.Fcc)).single())
    }

    @Test
    fun ordinaryVideoHasNoLimit() {
        assertEquals(emptyList(), webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.Bt709, transfer = ColorTransfer.Bt709)))
        assertEquals(emptyList(), webColorLimits(ColorSpaceInfo(matrix = ColorMatrix.Bt601)))
    }
}
