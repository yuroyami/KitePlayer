@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.size_tVar
import platform.darwin.sysctlbyname
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hardware decode arm, Apple only.
 *
 * It lived in the shared native test set until the Kotlin/Native desktop targets were added,
 * where it failed for the right reason: Linux and Windows have no VideoToolbox and their decoder
 * selection says so honestly. A test that names one platform's hwaccel belongs to that platform.
 */
class VideoToolboxDecodeTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    @Test
    fun videotoolboxFramesArriveHardwareAndConvertThroughTheDownload() = runBlocking {
        val (source, decoder, frame) = firstVideoFrameAuto("colors-bt709.mp4")
        try {
            assertEquals(
                HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox),
                decoder.hardware,
                "Auto on Apple must select VideoToolbox for h264",
            )
            assertEquals(HwSurfaceKind.CoreVideoPixelBuffer, frame.hardwareSurface)
            assertEquals(PlayerPixelFormat.Opaque, frame.pixelFormat)
            val rgba = SoftwareConverter.toRgba(frame)
            assertEquals(320 * 240 * 4, rgba.size)
            assertTrue(frame.hasPts, "the first hardware frame carries its timestamp")
        } finally {
            frame.close()
            source.close()
        }
    }

    /**
     * A capture reads a VideoToolbox frame through its downloaded copy, so the planes the frame
     * reports are the copy's: NV12 for this 8-bit H.264 clip.
     */
    @Test
    fun aVideotoolboxFrameIsReadableAsItsDownloadedPlanes() = runBlocking {
        val (source, decoder, frame) = firstVideoFrameAuto("colors-bt709.mp4")
        try {
            assertEquals(HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox), decoder.hardware)
            assertEquals(PlayerPixelFormat.Opaque, frame.pixelFormat)
            println("VideoToolbox frame planes: ${frame.planeFormat}, ${frame.planeCount} of them")
            // A virtual machine, such as a CI runner, has no decode hardware, and its VideoToolbox
            // output is not the hardware decoder's, so there only readable planes are asserted.
            if (inVirtualMachine()) {
                assertTrue(frame.planeFormat != PlayerPixelFormat.Opaque, "the planes are not readable")
            } else {
                assertEquals(PlayerPixelFormat.Nv12, frame.planeFormat)
                assertEquals(listOf(320 to 240, 320 to 120), (0 until 2).map { frame.planeStride(it) to frame.planeHeight(it) })
            }
            val image = frame.encode(SnapshotFormat.Png)
            assertEquals(0x89.toByte(), image[0], "not a PNG")
        } finally {
            frame.close()
            source.close()
        }
    }

    /**
     * AV1 has two decoders in the build. FFmpeg finds dav1d first for the codec, and VideoToolbox
     * can attach only to FFmpeg's own AV1 decoder (#95). So the status has to match the frame:
     * hardware only when a VideoToolbox frame came out. A Mac without AV1 silicon proves the refusal
     * and the software fallback here. A newer one proves the hardware frame.
     */
    @Test
    fun anAv1FrameComesFromTheDecoderItsStatusNames() = runBlocking {
        val (source, decoder, frame) = firstVideoFrameAuto("av1.mkv")
        try {
            val hardwareFrame = frame.hardwareSurface == HwSurfaceKind.CoreVideoPixelBuffer
            assertEquals(
                if (hardwareFrame) HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox) else HwdecStatus.Software,
                decoder.hardware,
                "the status must name the decoder that made the frame",
            )
        } finally {
            frame.close()
            source.close()
        }
    }

    /** True inside a virtual machine, where the kernel reports a hypervisor. */
    private fun inVirtualMachine(): Boolean = memScoped {
        val present = alloc<IntVar>()
        val size = alloc<size_tVar>()
        size.value = sizeOf<IntVar>().convert()
        sysctlbyname("kern.hv_vmm_present", present.ptr, size.ptr, null, 0u) == 0 && present.value == 1
    }

    /** Decodes the first frame with the platform's own hwdec policy, for the hardware decode arm. */
    private suspend fun firstVideoFrameAuto(file: String): Triple<KiteFFmpegSource, VideoDecoder, KiteFFmpegVideoFrame> {
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteFFmpegSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $file")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(
            source.videoDecoderFactories().first().create(stream, HwdecPolicy.Auto),
        )
        var frame: VideoFrame? = null
        while (frame == null) {
            val packet = source.readPacket() ?: break
            if (packet.streamIndex != stream.index) {
                packet.close()
                continue
            }
            while (!decoder.send(packet)) {
                frame = decoder.receive()
                if (frame != null) break
            }
            packet.close()
            if (frame == null) frame = decoder.receive()
        }
        return Triple(source, decoder, assertNotNull(frame, "no frame decoded from $file") as KiteFFmpegVideoFrame)
    }
}
