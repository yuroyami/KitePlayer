package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteffmpeg.HardwareAccel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which codecs Windows asks Direct3D 11 for (#101), pinned per codec as the Apple and Android tables
 * are. Route tables are data, and untested data drifts.
 */
class Direct3dRouteTest {

    private val direct3d = HardwareRoute.Accel(HardwareAccel.D3d11va, HwdecKind.D3d11va)

    @Test
    fun everyCodecTheLibraryAttachesReachesDirect3d() {
        listOf("h264", "H264", "avc1", " hevc ", "h265", "hev1", "vp9", "VP9", "mpeg2video", "vc1", "wmv3").forEach { codec ->
            assertEquals(direct3d, codec.d3d11vaRoute(), "$codec must route to Direct3D 11")
        }
    }

    @Test
    fun codecsTheLibraryDoesNotAttachStaySoftware() {
        listOf("av1", "vp8", "mpeg4", "prores", "mjpeg", "theora", "").forEach { codec ->
            assertNull(codec.d3d11vaRoute(), "$codec must decode in software on Windows")
        }
    }

    @Test
    fun aDirect3dRouteRidesTheOrdinaryPolicyTable() {
        val auto = decoderSelection(HwdecPolicy.Auto, "h264".d3d11vaRoute())
        assertEquals(direct3d, auto.hardware)
        assertTrue(auto.mayFallback, "a machine without a usable device must still play in software")
        assertFalse(auto.requiresHardware)

        val required = decoderSelection(HwdecPolicy.Require, "hevc".d3d11vaRoute())
        assertEquals(direct3d, required.hardware)
        assertFalse(required.mayFallback)

        assertEquals(direct3d, decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.D3d11va)), "vp9".d3d11vaRoute()).hardware)
        assertNull(decoderSelection(HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox)), "vp9".d3d11vaRoute()).hardware)
        assertNull(decoderSelection(HwdecPolicy.Off, "h264".d3d11vaRoute()).hardware)
    }

    /** A frame of these kinds is read through its downloaded copy, so a renderer that reads planes can draw it. */
    @Test
    fun onlyVideoToolboxAndDirect3dFramesDownloadToMemory() {
        HwSurfaceKind.entries.forEach { kind ->
            val downloads = kind == HwSurfaceKind.CoreVideoPixelBuffer || kind == HwSurfaceKind.D3d11Texture
            assertEquals(downloads, kind.downloadsToMemory(), "$kind")
        }
    }
}
