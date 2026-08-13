package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.HardwareAccel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformDecoderSelectionTest {

    private val mediaCodec = HardwareRoute.NamedDecoder(CodecId.H264MediaCodec, HwdecKind.MediaCodec)
    private val videoToolbox = HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)

    @Test
    fun offNeverProbesHardware() {
        val selection = decoderSelection(HwdecPolicy.Off, mediaCodec)
        assertNull(selection.hardware)
        assertFalse(selection.mayFallback)
        assertFalse(selection.requiresHardware)
    }

    @Test
    fun autoUsesEligibleHardwareAndMayFallback() {
        val selection = decoderSelection(HwdecPolicy.Auto, mediaCodec)
        assertEquals(mediaCodec, selection.hardware)
        assertTrue(selection.mayFallback)
        assertFalse(selection.requiresHardware)

        // The hwaccel shape rides the same table: policy cares about the KIND, not the shape.
        assertEquals(videoToolbox, decoderSelection(HwdecPolicy.Auto, videoToolbox).hardware)
    }

    @Test
    fun autoUsesSoftwareDirectlyWhenIneligible() {
        assertEquals(
            DecoderSelection(null, mayFallback = false, requiresHardware = false),
            decoderSelection(HwdecPolicy.Auto, route = null),
        )
    }

    @Test
    fun preferOnlyUsesKindsItActuallyLists() {
        assertEquals(
            mediaCodec,
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.MediaCodec)), mediaCodec).hardware,
        )
        assertNull(
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)), mediaCodec).hardware,
        )
        assertEquals(
            videoToolbox,
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)), videoToolbox).hardware,
        )
        assertNull(
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.MediaCodec)), videoToolbox).hardware,
        )
    }

    @Test
    fun requireNeverAuthorizesSoftwareFallback() {
        val eligible = decoderSelection(HwdecPolicy.Require, mediaCodec)
        assertEquals(mediaCodec, eligible.hardware)
        assertFalse(eligible.mayFallback)
        assertTrue(eligible.requiresHardware)

        val ineligible = decoderSelection(HwdecPolicy.Require, route = null)
        assertNull(ineligible.hardware)
        assertFalse(ineligible.mayFallback)
        assertTrue(ineligible.requiresHardware)
    }
}
