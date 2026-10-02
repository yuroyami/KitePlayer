package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.DisplayPrimaries
import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** MediaCodec gets a stream's HDR metadata as the 25 byte descriptor `KEY_HDR_STATIC_INFO` takes (#378). */
class HdrStaticInfoTest {

    private val pq = ColorSpaceInfo(matrix = ColorMatrix.Bt2020Ncl, primaries = ColorPrimaries.Bt2020, transfer = ColorTransfer.Pq)

    private val master = HdrStaticMetadata(
        masteringPrimaries = DisplayPrimaries(0.708f, 0.292f, 0.17f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.329f),
        masteringMinNits = 0.005f,
        masteringMaxNits = 4000f,
        maxContentLightNits = 4000,
        maxFrameAverageNits = 400,
    )

    private fun stream(colorSpace: ColorSpaceInfo, hdr: HdrStaticMetadata?) =
        PlayerStreamInfo(index = 0, kind = TrackKind.Video, codec = "hevc", colorSpace = colorSpace, hdr = hdr)

    @Test
    fun aPqStreamPacksItsMetadataInTheDescriptorsOrderAndUnits() {
        val buffer = checkNotNull(hdrStaticInfo(stream(pq, master)))
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        assertEquals(25, bytes.size)
        assertEquals(0, bytes[0].toInt(), "type 1 is written as 0")
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        assertEquals(
            listOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 4000, 50, 4000, 400),
            (0 until 12).map { u16(1 + it * 2) },
        )
    }

    @Test
    fun anSdrStreamOrAStreamWithoutMetadataPacksNothing() {
        assertNull(hdrStaticInfo(stream(ColorSpaceInfo(), master)))
        assertNull(hdrStaticInfo(stream(pq, null)))
    }
}
