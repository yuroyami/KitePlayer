package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.DeinterlacePolicy
import io.github.yuroyami.kiteplayer.DolbyVisionInfo
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.toneMapPeakNits
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dolby Vision through the FFmpeg backend (#470): a profile 5 stream says what it is, and every
 * frame reaches a renderer composed into HDR10 with its scene's peak.
 *
 * The clip is KiteFFmpeg's profile 5 fixture: two 32x24 frames in MP4 whose RPUs differ in their
 * level 1, made by KiteFFmpeg's scripts/dolby-vision-fixture/make-fixture.sh. KiteFFmpeg's own
 * contract test holds the composed pictures to libplacebo's; this one holds the player to using
 * them.
 */
class DolbyVisionTest {

    /** Every frame the backend decodes from the clip under [deinterlace], each one closed after [check] reads it. */
    private fun decodeEach(deinterlace: DeinterlacePolicy, check: (index: Int, frame: KiteFFmpegVideoFrame) -> Unit): Int = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(PROFILE_5), label = "profile5.mp4")
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        var count = 0
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Video })
            assertEquals(DolbyVisionInfo(profile = 5, level = 1, baseLayerCompatibility = 0), stream.dolbyVision)
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(KiteFFmpegVideoDecoderFactory(source).create(stream, HwdecPolicy.Off, deinterlace))
            try {
                fun take(frame: VideoFrame?) {
                    frame ?: return
                    try {
                        check(count++, frame as KiteFFmpegVideoFrame)
                    } finally {
                        frame.close()
                    }
                }
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) take(decoder.receive() ?: break)
                    } finally {
                        packet.close()
                    }
                    while (true) take(decoder.receive() ?: break)
                }
                decoder.send(null)
                while (true) take(decoder.receive() ?: break)
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
        count
    }

    private fun assertComposed(index: Int, frame: KiteFFmpegVideoFrame) {
        assertEquals(PlayerPixelFormat.Yuv420p10le, frame.pixelFormat, "frame $index")
        assertEquals(ColorTransfer.Pq, frame.colorSpace.transfer, "frame $index is HDR10, where its base layer said nothing")
        assertEquals(ColorPrimaries.Bt2020, frame.colorSpace.primaries, "frame $index")
        assertFalse(frame.colorSpace.fullRange, "frame $index is limited range, where its base layer is full")
        val hdr = assertNotNull(frame.hdr, "frame $index carries the source's range")
        assertTrue(abs(hdr.masteringMaxNits!! - 1000f) < 1.5f, "the source's peak is PQ 3079, read ${hdr.masteringMaxNits}")
        assertEquals(943, hdr.maxContentLightNits, "level 6's brightest pixel")
        // Level 1: PQ 2081 is 100.1 nits and PQ 3079 is 1000.6.
        val scene = assertNotNull(frame.sceneMaxNits, "frame $index carries its scene's peak")
        assertTrue(abs(scene - SCENE_PEAKS[index]) < 0.1f, "frame $index's scene peaks at ${SCENE_PEAKS[index]} nits, read $scene")
        // The first scene's peak is the one a tone mapper uses; the second's is held at the title's 943.
        val peak = assertNotNull(frame.toneMapPeakNits, "frame $index")
        assertTrue(abs(peak - TONE_MAP_PEAKS[index]) < 0.1f, "frame $index rolls off from ${TONE_MAP_PEAKS[index]} nits, read $peak")
    }

    @Test
    fun aProfileFiveStreamSaysWhatItIsAndReachesTheRendererComposed() {
        assertEquals(2, decodeEach(DeinterlacePolicy.Off, ::assertComposed))
    }

    @Test
    fun aFilterSeesTheComposedPicture() {
        assertEquals(2, decodeEach(DeinterlacePolicy.Always, ::assertComposed))
    }

    @Test
    fun theProfileIsNamedAsDolbyWritesIt() {
        val info = DolbyVisionInfo(profile = 5, level = 1, baseLayerCompatibility = 0)
        assertFalse(info.baseLayerPlaysAlone)
        assertEquals("5", info.profileName)
        assertEquals("8.1", DolbyVisionInfo(profile = 8, level = 6, baseLayerCompatibility = 1).profileName)
        assertTrue(DolbyVisionInfo(profile = 8, level = 6, baseLayerCompatibility = 4).baseLayerPlaysAlone)
        assertEquals("10.0", DolbyVisionInfo(profile = 10, level = 9, baseLayerCompatibility = 0).profileName)
    }

    private companion object {
        val SCENE_PEAKS = floatArrayOf(100.1f, 1000.6f)
        val TONE_MAP_PEAKS = floatArrayOf(100.1f, 943f)

        val PROFILE_5: ByteArray = (
            "000000146674797069736f340000000169736f34000003ab6d6f6f760000006c6d76686400000000e6e70ef0e6e7" +
            "0ef00000025800000032000100000100000000000000000000000001000000000000000000000000000000010000" +
            "00000000000000000000000040000000000000000000000000000000000000000000000000000000000000020000" +
            "02bf7472616b0000005c746b686400000007e6e70ef0e6e70ef00000000100000000000000320000000000000000" +
            "00000000000000000001000000000000000000000000000000010000000000000000000000000000400000000020" +
            "0000001800000000025b6d646961000000206d64686400000000e6e70ef0e6e70ef0000000180000000255c40000" +
            "0000005568646c72000000000000000076696465000000000000000000000000686576633a6476703d353a667073" +
            "3d32344047504143322e322e312d726576322e322e312b64667367312d332e316275696c643200000001de6d696e" +
            "6600000014766d68640000000100000000000000000000002464696e660000001c64726566000000000000000100" +
            "00000c75726c20000000010000019e7374626c0000011e7374736400000000000000010000010e64766831000000" +
            "00000000010000000000000000000000000000000000200018004800000048000000000000000100000000000000" +
            "000000000000000000000000000000000000000000000000000018ffff0000007468766343010220000000900000" +
            "0000001ef000fdfdfafa00000f03a00001001840010c01ffff02200000030090000003000003001e959809a10001" +
            "002842010102200000030090000003000003001ea04219365959aaf2bc05b02000000300200000030301a2000100" +
            "064401c173d089000000206476634301000a0d000000000000000000000000000000000000000000000010706173" +
            "7000000001000000010000001462747274000001b300013aa000013aa00000001873747473000000000000000100" +
            "0000020000000100000014737473730000000000000001000000010000001c737473630000000000000001000000" +
            "0100000002000000010000001c7374737a000000000000000000000002000001b300000194000000147374636f00" +
            "00000000000001000003c70000007875647461000000706d657461000000000000002168646c7200000000000000" +
            "006d6469720000000000000000000000000000000043696c73740000003ba9746f6f000000336461746100000001" +
            "00000000475041432d322e322e312d726576322e322e312b64667367312d332e316275696c64320000034f6d6461" +
            "74000000652801af0de0523271482fe668f39ba3f5f1e584b005f1f993892627ace0bfac1f8fd6a8d321b3c43504" +
            "040ce49fdd5b8969b540cac885448caf051b7e028073d98a38a180fb35229a75ffe0eb7731771e40b9e419ae5844" +
            "5e81ea1b7543b03ae4b83276ff80000001467c01190809004061b6506ec00401805fe007ff003ffea0000010cccc" +
            "d799999aa0a3d73f5c291f9999aa7eb8521028f5c828f5c540a3d74147ae7ccccd40a3d73f851ed051eb9028f5cf" +
            "f5c2940a3d7428f5c40000040000040a3d7400000400000400000303fae149000003010000030100000301000003" +
            "0100000300a7fae149fd70a48147ae40a3d720a3d71fc28f68000008147ae8000008000007f0a3da051eba000003" +
            "02000003020000030200000302000003020a3d720000030200000302000003020000030344000063e0d224001f8b" +
            "6088640000217d4ae0000003000100000300010000030008573fd47fd47fd468573fd47fd47fd468573fffe00000" +
            "03000003000003000c8401f01c2a203008004109998080500000030000030000030120c07d00002075e03205301c" +
            "004004000209007fc000450b0100000300c79a2f9d80000000460201d0097e10c61bc0950bb7eecfd9e32219fb1f" +
            "93710dce3c38930a1530270caaeb316d19f18642fda3f169906eee9969138185596932f422186eaed7751c29e519" +
            "1513f6d8000001467c01190809004061b6506ec00401805fe007ff003ffea0000010666667cccccea051ebbfae14" +
            "9fc28f6a7f5c2910147ae8147ae540a3d74147ae7ccccd40a3d73f851ed051eb9028f5cff5c2940a3d7428f5c400" +
            "00040000040a3d7400000400000400000303fae1490000030100000301000003010000030100000300a7fae149fd" +
            "70a48147ae40a3d720a3d71fc28f68000008147ae8000008000007f0a3da051eba00000302000003020000030200" +
            "000302000003020a3d720000030200000302000003020000030344000063e0d224001f8b6088640000217d4ae000" +
            "0003000100000300010000030008573fd47fd47fd468573fd47fd47fd468573fffe0000003000003000003000c84" +
            "01f01c2a20300803e03aaa8080500000030000030000030120c07d00002075e03205301c004004000209007fc000" +
            "450b0100000300042f1bf980000000486672656549736f4d656469612046696c652050726f647563656420776974" +
            "68204750414320322e322e312d726576322e322e312b64667367312d332e316275696c643200"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
