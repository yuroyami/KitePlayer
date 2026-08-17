package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.HardwareAccel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which codecs the Apple axis asks VideoToolbox for, pinned per codec.
 *
 * The Android twin of this test exists and this one did not, which is how av1 stayed unrouted while
 * the hwaccel sat compiled in the archive. Route tables are data, and untested data drifts.
 */
class AppleDecoderSelectionTest {

    private val videoToolbox = HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)

    private fun routeOf(codec: String, policy: HwdecPolicy = HwdecPolicy.Auto): HardwareRoute? =
        platformDecoderSelection(codec, policy).hardware

    @Test
    fun everyEligibleCodecAndAliasReachesVideoToolbox() {
        listOf(
            "h264", "H264", "avc1",
            "hevc", "HEVC", "h265", "hev1",
            "av1", "AV1", " av1 ",
        ).forEach { codec ->
            assertEquals(videoToolbox, routeOf(codec), "$codec must route to VideoToolbox")
        }
    }

    /**
     * An eligible AV1 route still authorises the software attempt, because a pre-A17-Pro device
     * refuses the attach. That the software attempt then ALSO fails without dav1d is FFmpeg's
     * answer, not this table's business.
     */
    @Test
    fun av1RidesTheOrdinaryFallbackContract() {
        val auto = platformDecoderSelection("av1", HwdecPolicy.Auto)
        assertEquals(videoToolbox, auto.hardware)
        assertTrue(auto.mayFallback)

        val required = platformDecoderSelection("av1", HwdecPolicy.Require)
        assertEquals(videoToolbox, required.hardware)
        assertTrue(required.requiresHardware)
    }

    @Test
    fun ineligibleCodecsAndDisabledPolicyStaySoftware() {
        // vp9's hwaccel compiles but no Apple silicon decodes VP9, so the table must not claim it.
        listOf("vp9", "vp8", "mpeg4", "mpeg2video", "prores").forEach { codec ->
            assertNull(routeOf(codec), "$codec must stay software on Apple")
        }
        assertNull(routeOf("av1", HwdecPolicy.Off))
        assertNull(routeOf("h264", HwdecPolicy.Off))
        assertNull(routeOf("av1", HwdecPolicy.Prefer(listOf(HwdecKind.MediaCodec))))
        assertEquals(
            videoToolbox,
            routeOf("av1", HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox))),
        )
    }

    /**
     * The AudioToolbox table, pinned per codec so the short list stays a decision and not a drift.
     */
    @Test
    fun audioToolboxIsNamedForExactlyTheFourOffloadCodecs() {
        mapOf(
            "aac" to "aac_at",
            "AAC" to "aac_at",
            "alac" to "alac_at",
            "ac3" to "ac3_at",
            "eac3" to "eac3_at",
        ).forEach { (codec, expected) ->
            assertEquals(CodecId(expected), platformAudioDecoder(codec), "$codec must name $expected")
        }

        // Deliberate omissions, each for a reason recorded next to the table itself.
        listOf("mp3", "mp2", "mp1", "pcm_alaw", "pcm_mulaw", "amr_nb", "gsm_ms", "ilbc", "flac", "opus", "vorbis")
            .forEach { codec ->
                assertNull(platformAudioDecoder(codec), "$codec must decode in software")
            }
    }
}
