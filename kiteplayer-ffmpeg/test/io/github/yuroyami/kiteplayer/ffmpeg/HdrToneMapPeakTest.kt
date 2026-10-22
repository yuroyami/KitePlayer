package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CPU tone map rolls off from the content's own peak when its metadata gives one (#378). */
class HdrToneMapPeakTest {

    private val pq = ColorSpaceInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = ColorPrimaries.Bt2020,
        transfer = ColorTransfer.Pq,
        fullRange = true,
    )

    /** The green byte a grey of [nits] becomes. */
    private fun mapped(nits: Double, peakNits: Float?): Int {
        val code = (HdrToneMap.pqEncode(nits / 10_000.0) * 255.0).roundToInt().toByte()
        val rgba = byteArrayOf(code, code, code, -1)
        HdrToneMap.forColorSpaceOrNull(pq, peakNits)!!.mapInPlace(rgba)
        return rgba[1].toInt() and 0xFF
    }

    @Test
    fun aBrighterMasterKeepsHighlightsApartThatAThousandNitAssumptionFlattens() {
        // Under a 1000 nit assumption 1000 and 2000 nits both land on white.
        assertEquals(mapped(1000.0, null), mapped(2000.0, null))
        // A 4000 nit master keeps 1000 nits below its 2000 nits.
        val thousand = mapped(1000.0, 4000f)
        val twoThousand = mapped(2000.0, 4000f)
        assertTrue(thousand < twoThousand, "a 4000 nit master mapped 1000 nits to $thousand and 2000 nits to $twoThousand")
    }

    @Test
    fun noPeakIsTheThousandNitsItAlwaysWas() {
        for (nits in listOf(50.0, 203.0, 500.0, 1000.0)) {
            assertEquals(mapped(nits, 1000f), mapped(nits, null), "$nits nits")
        }
    }
}
