package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.CodecId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDecoderSelectionHostTest {

    private fun namedDecoderOf(codec: String, policy: HwdecPolicy): CodecId? =
        (platformDecoderSelection(codec, policy).hardware as? HardwareRoute.NamedDecoder)?.decoder

    @Test
    fun h264AndHevcAliasesMapToNamedMediaCodecDecoders() {
        listOf("h264", "H264", "avc1").forEach { codec ->
            assertEquals(CodecId.H264MediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto))
        }
        listOf("hevc", "HEVC", "h265", "hev1").forEach { codec ->
            assertEquals(CodecId.HevcMediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto))
        }
    }

    @Test
    fun ineligibleAndExcludedPoliciesStaySoftwareOrStrict() {
        assertNull(platformDecoderSelection("mpeg4", HwdecPolicy.Auto).hardware)
        assertNull(platformDecoderSelection("h264", HwdecPolicy.Off).hardware)
        assertNull(
            platformDecoderSelection(
                "h264",
                HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)),
            ).hardware,
        )
        assertTrue(platformDecoderSelection("mpeg4", HwdecPolicy.Require).requiresHardware)
    }
}
