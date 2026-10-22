package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteffmpeg.HardwareAccel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Direct3D 11 route on a machine that cannot attach it (#101). A Windows PC without a usable
 * GPU refuses the attach at open, and so does every other system, where FFmpeg has no Direct3D at
 * all. The factory then warns and decodes the same pictures in software, so asking for the GPU never
 * costs a playable stream. That a Windows GPU decodes faster is the half only such a PC can show.
 */
class Direct3dFallbackTest {

    @Test
    fun aMachineThatCannotAttachDirect3dWarnsAndDecodesInSoftware() = runBlocking {
        assumeFalse("a Windows machine may attach the device", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val clip = requireTestMedia(File(MEDIA_DIR, CLIP).takeIf { it.isFile }, "no $CLIP in $MEDIA_DIR")

        val plain = decode(clip, selection = null)
        val warnings = Collections.synchronizedList(ArrayList<PlaybackWarning>())
        val routed = decode(clip, DecoderSelection(HardwareRoute.Accel(HardwareAccel.D3d11va, HwdecKind.D3d11va), mayFallback = true, requiresHardware = false), warnings)

        val refusal = assertIs<PlaybackWarning.HardwareDecodeUnavailable>(warnings.singleOrNull(), "warnings: $warnings")
        assertTrue("refused to open" in refusal.reason, refusal.reason)
        assertEquals(HwdecStatus.Software, routed.status)
        assertEquals(plain.pictures, routed.pictures, "the fallback must decode the same pictures as plain software")
        assertTrue(routed.pictures.isNotEmpty())
        assertNull(routed.hardwareSurfaces.firstOrNull(), "a software picture lives in main memory")
    }

    private class Decoded(val status: HwdecStatus, val pictures: List<Pair<Long?, Int>>, val hardwareSurfaces: List<Any>)

    /** Every picture's time and plane bytes' hash, decoded with [selection]'s route or with none. */
    private suspend fun decode(clip: File, selection: DecoderSelection?, warnings: MutableList<PlaybackWarning> = ArrayList()): Decoded {
        val source = KiteFFmpegSourceFactory().open(MediaItem(clip.absolutePath)) as KiteFFmpegSource
        source.onWarning = { warnings += it }
        try {
            val stream = assertNotNull(source.firstVideo, "$CLIP has no picture")
            source.selectStreams(setOf(stream.index))
            val decoder: VideoDecoder = if (selection == null) {
                source.newVideoDecoder(stream)
            } else {
                assertNotNull(KiteFFmpegVideoDecoderFactory(source).openSelected(stream, selection, filter = null))
            }
            val pictures = ArrayList<Pair<Long?, Int>>()
            val surfaces = ArrayList<Any>()
            try {
                suspend fun drain() {
                    while (true) {
                        val frame = decoder.receive() ?: break
                        try {
                            frame.hardwareSurface?.let(surfaces::add)
                            val planes = (frame as KiteFFmpegVideoFrame).readableFrame().copyPlanesToByteArray()
                            pictures += frame.pts?.micros to planes.contentHashCode()
                        } finally {
                            frame.close()
                        }
                    }
                }
                while (true) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) drain()
                    } finally {
                        packet.close()
                    }
                    drain()
                }
                decoder.send(null)
                drain()
                return Decoded(decoder.hardware, pictures, surfaces)
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }

    private companion object {
        val MEDIA_DIR: String = System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"
        const val CLIP = "colors-bt709.mp4"
    }
}
