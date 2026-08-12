package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformDecoderSelectionTest {

    private val mediaCodec = CodecId.H264MediaCodec

    @Test
    fun offNeverProbesHardware() {
        val selection = decoderSelection(HwdecPolicy.Off, mediaCodec)
        assertNull(selection.hardwareDecoder)
        assertFalse(selection.mayFallback)
        assertFalse(selection.requiresHardware)
    }

    @Test
    fun autoUsesEligibleHardwareAndMayFallback() {
        val selection = decoderSelection(HwdecPolicy.Auto, mediaCodec)
        assertEquals(mediaCodec, selection.hardwareDecoder)
        assertTrue(selection.mayFallback)
        assertFalse(selection.requiresHardware)
    }

    @Test
    fun autoUsesSoftwareDirectlyWhenIneligible() {
        assertEquals(
            DecoderSelection(null, mayFallback = false, requiresHardware = false),
            decoderSelection(HwdecPolicy.Auto, hardwareDecoder = null),
        )
    }

    @Test
    fun preferOnlyUsesKindsItActuallyLists() {
        assertEquals(
            mediaCodec,
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.MediaCodec)), mediaCodec).hardwareDecoder,
        )
        assertNull(
            decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)), mediaCodec).hardwareDecoder,
        )
    }

    @Test
    fun requireNeverAuthorizesSoftwareFallback() {
        val eligible = decoderSelection(HwdecPolicy.Require, mediaCodec)
        assertEquals(mediaCodec, eligible.hardwareDecoder)
        assertFalse(eligible.mayFallback)
        assertTrue(eligible.requiresHardware)

        val ineligible = decoderSelection(HwdecPolicy.Require, hardwareDecoder = null)
        assertNull(ineligible.hardwareDecoder)
        assertFalse(ineligible.mayFallback)
        assertTrue(ineligible.requiresHardware)
    }
}
