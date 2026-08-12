package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDecoderSelectionHostTest {

    @Test
    fun h264AndHevcAliasesMapToNamedMediaCodecDecoders() {
        listOf("h264", "H264", "avc1").forEach { codec ->
            assertEquals(
                CodecId.H264MediaCodec,
                platformDecoderSelection(codec, HwdecPolicy.Auto).hardwareDecoder,
            )
        }
        listOf("hevc", "HEVC", "h265", "hev1").forEach { codec ->
            assertEquals(
                CodecId.HevcMediaCodec,
                platformDecoderSelection(codec, HwdecPolicy.Auto).hardwareDecoder,
            )
        }
    }

    @Test
    fun ineligibleAndExcludedPoliciesStaySoftwareOrStrict() {
        assertNull(platformDecoderSelection("mpeg4", HwdecPolicy.Auto).hardwareDecoder)
        assertNull(platformDecoderSelection("h264", HwdecPolicy.Off).hardwareDecoder)
        assertNull(
            platformDecoderSelection(
                "h264",
                HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)),
            ).hardwareDecoder,
        )
        assertTrue(platformDecoderSelection("mpeg4", HwdecPolicy.Require).requiresHardware)
    }
}
