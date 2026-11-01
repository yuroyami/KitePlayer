package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDecoderSelectionHostTest {

    private fun namedDecoderOf(codec: String, policy: HwdecPolicy): DecoderId? =
        (platformDecoderSelection(codec, policy).hardware as? HardwareRoute.NamedDecoder)?.decoder

    @Test
    fun h264AndHevcAliasesMapToNamedMediaCodecDecoders() {
        listOf("h264", "H264", "avc1").forEach { codec ->
            assertEquals(DecoderId.H264MediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto))
        }
        listOf("hevc", "HEVC", "h265", "hev1").forEach { codec ->
            assertEquals(DecoderId.HevcMediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto))
        }
    }

    // A live AV1 or VP9 stream took FFmpeg's route and decoded on the CPU, though the phone's
    // decoder played the same stream as a file through the view's own MediaCodec path (#643).
    @Test
    fun av1Vp9AndVp8MapToTheirMediaCodecDecoders() {
        listOf("av1", "AV1", "av01").forEach { codec ->
            assertEquals(DecoderId.Av1MediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto), codec)
        }
        listOf("vp9", "VP9", "vp09").forEach { codec ->
            assertEquals(DecoderId.Vp9MediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto), codec)
        }
        listOf("vp8", "vp08").forEach { codec ->
            assertEquals(DecoderId.Vp8MediaCodec, namedDecoderOf(codec, HwdecPolicy.Auto), codec)
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
