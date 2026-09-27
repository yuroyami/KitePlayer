package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop JVM on macOS decodes H.264 with VideoToolbox (#237), and the software renderers read
 * each frame through its downloaded copy. Other hosts have no VideoToolbox and skip.
 */
class DesktopVideoToolboxTest {

    private class Decoded(val source: KiteFFmpegSource, val decoder: VideoDecoder, val frame: KiteFFmpegVideoFrame) {
        fun close() {
            frame.close()
            decoder.close()
            source.close()
        }
    }

    private suspend fun firstFrame(path: String, policy: HwdecPolicy): Decoded {
        val source = KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $path")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, policy))
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
        return Decoded(source, decoder, assertNotNull(frame, "no frame decoded from $path") as KiteFFmpegVideoFrame)
    }

    /** True inside a macOS virtual machine, where the kernel reports a hypervisor. */
    private fun inVirtualMachine(): Boolean = runCatching {
        val process = ProcessBuilder("sysctl", "-n", "kern.hv_vmm_present").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim() == "1"
    }.getOrDefault(false)

    @Test
    fun autoDecodesH264WithVideoToolboxAndShowsTheSoftwarePicture() = runBlocking {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) {
            return@runBlocking println("SKIP: VideoToolbox exists only on macOS")
        }
        val clip = "${formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh")}/colors-bt709.mp4"
        val hardware = firstFrame(clip, HwdecPolicy.Auto)
        val software = firstFrame(clip, HwdecPolicy.Off)
        try {
            assertEquals(HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox), hardware.decoder.hardware)
            assertEquals(HwSurfaceKind.CoreVideoPixelBuffer, hardware.frame.hardwareSurface)
            assertEquals(HwdecStatus.Software, software.decoder.hardware)

            val fromHardware = SoftwareConverter.toRgba(hardware.frame)
            val fromSoftware = SoftwareConverter.toRgba(software.frame)
            assertEquals(fromSoftware.size, fromHardware.size)
            val largest = fromSoftware.indices.maxOf {
                abs((fromSoftware[it].toInt() and 0xFF) - (fromHardware[it].toInt() and 0xFF))
            }
            println(
                "VideoToolbox against software decode: largest RGBA channel difference $largest; " +
                    "pts ${hardware.frame.pts} against ${software.frame.pts}; " +
                    "planes ${hardware.frame.planeFormat} against ${software.frame.planeFormat}; " +
                    "colour ${hardware.frame.colorSpace} against ${software.frame.colorSpace}; " +
                    "first pixels ${fromHardware.take(8)} against ${fromSoftware.take(8)}",
            )
            // On a Mac the hardware decoder gives the software decoder's picture exactly, as NV12.
            // Inside a virtual machine, such as a CI runner, VideoToolbox has no decode hardware,
            // and its picture was measured to differ, so there only readable planes are asserted.
            if (inVirtualMachine()) {
                println("SKIP: the picture comparison, because this Mac is a virtual machine")
                assertTrue(hardware.frame.planeFormat != PlayerPixelFormat.Opaque, "the planes are not readable")
            } else {
                assertTrue(largest <= 2, "the VideoToolbox picture differs from the software picture by up to $largest")
                assertEquals(PlayerPixelFormat.Nv12, hardware.frame.planeFormat)
            }

            // A capture reads the same downloaded copy.
            assertEquals(0x89.toByte(), hardware.frame.encode(SnapshotFormat.Png)[0], "not a PNG")
        } finally {
            hardware.close()
            software.close()
        }
    }
}
