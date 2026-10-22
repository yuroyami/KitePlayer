package io.github.yuroyami.kiteplayer.ffmpeg

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Device proof for FFmpeg-owned platform decoder selection and CPU-readable buffer output. */
internal class AndroidMediaCodecDeviceTest {
    @Test
    fun h264UsesHardwareWithDownloadOffUsesSoftwareAndMpeg4RequireRefuses() = runDeviceTest {
        assertTrue(
            FFmpeg.hasDecoder("h264_mediacodec"),
            "the Android FFmpeg build must expose its named H.264 platform decoder",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val h264 = AndroidMedia.H264.materialize(context)
        val mpeg4 = AndroidMedia.Mpeg4.materialize(context)
        try {
            val hardware = decodeFirst(h264.absolutePath, HwdecPolicy.Auto)
            try {
                val software = decodeFirst(h264.absolutePath, HwdecPolicy.Off)
                try {
                    assertEquals(
                        HwdecStatus.HardwareWithDownload(HwdecKind.MediaCodec),
                        hardware.decoder.hardware,
                    )
                    assertNull(
                        hardware.frame.hardwareSurface,
                        "buffer-mode platform decode downloads into ordinary frame storage, not a Surface",
                    )
                    assertEquals(HwdecStatus.Software, software.decoder.hardware)
                    assertNull(software.frame.hardwareSurface)

                    val hardwareRgba = SoftwareConverter.toRgba(hardware.frame)
                    val softwareRgba = SoftwareConverter.toRgba(software.frame)
                    assertEquals(software.frame.size, hardware.frame.size)
                    assertEquals(softwareRgba.size, hardwareRgba.size)
                    val psnr = lumaPsnr(hardwareRgba, softwareRgba)
                    assertTrue(
                        psnr >= MIN_LUMA_PSNR_DB,
                        "platform and software decode luma PSNR was $psnr dB, need $MIN_LUMA_PSNR_DB dB",
                    )
                } finally {
                    software.close()
                }
            } finally {
                hardware.close()
            }

            val strict = openDecoder(mpeg4.absolutePath, HwdecPolicy.Require)
            try {
                assertNull(
                    strict.decoder,
                    "Require must refuse an ineligible MPEG-4 stream rather than open software",
                )
            } finally {
                strict.close()
            }
        } finally {
            h264.delete()
            mpeg4.delete()
        }
    }

    private suspend fun decodeFirst(path: String, policy: HwdecPolicy): DecodedFrame {
        val opened = openDecoder(path, policy)
        val decoder = opened.decoder ?: run {
            opened.close()
            assertNotNull<VideoDecoder>(null, "decoder refused $path under $policy")
        }
        try {
            val source = opened.session.source
            source.selectStreams(setOf(opened.stream.index))
            while (true) {
                val packet = source.readPacket() ?: break
                if (packet.streamIndex != opened.stream.index) {
                    packet.close()
                    continue
                }
                val frame = try {
                    sendAndReceive(decoder, packet)
                } finally {
                    packet.close()
                }
                if (frame != null) return DecodedFrame(opened.session, decoder, frame)
            }

            var drainAccepted = false
            repeat(MAX_DRAIN_STEPS) {
                if (!drainAccepted) drainAccepted = decoder.send(null)
                decoder.receive()?.let { frame ->
                    return DecodedFrame(opened.session, decoder, frame.asKiteFFmpegFrame())
                }
                if (decoder.isDrained) error("decoder drained without returning the fixture frame")
            }
            error("decoder did not produce the fixture frame within $MAX_DRAIN_STEPS drain steps")
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
    }

    private suspend fun sendAndReceive(decoder: VideoDecoder, packet: PlayerPacket): KiteFFmpegVideoFrame? {
        val pending = ArrayDeque<KiteFFmpegVideoFrame>()
        try {
            repeat(MAX_BACKPRESSURE_STEPS) {
                val accepted = decoder.send(packet)
                val frame = decoder.receive()
                if (frame != null) pending.addLast(frame.asKiteFFmpegFrame())
                if (accepted) {
                    check(pending.size <= 1) {
                        "bounded one-frame fixture produced ${pending.size} frames before one packet was accepted"
                    }
                    return pending.removeFirstOrNull()
                }
            }
            error("decoder did not accept one packet within $MAX_BACKPRESSURE_STEPS backpressure steps")
        } finally {
            pending.forEach { it.close() }
        }
    }

    private suspend fun openDecoder(path: String, policy: HwdecPolicy): OpenedDecoder {
        val session = KiteFFmpegMediaBackend().open(MediaItem(path))
        try {
            val stream = session.source.streams.first { it.kind == TrackKind.Video && !it.isCoverArt }
            var decoder: VideoDecoder? = null
            for (factory in session.videoDecoders) {
                decoder = factory.create(stream, policy)
                if (decoder != null) break
            }
            return OpenedDecoder(session, stream, decoder)
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    private fun lumaPsnr(first: ByteArray, second: ByteArray): Double {
        require(first.size == second.size && first.size % 4 == 0)
        var squaredError = 0.0
        var offset = 0
        while (offset < first.size) {
            val firstY = first.lumaAt(offset)
            val secondY = second.lumaAt(offset)
            val error = firstY - secondY
            squaredError += error.toDouble() * error
            offset += 4
        }
        if (squaredError == 0.0) return Double.POSITIVE_INFINITY
        val meanSquaredError = squaredError / (first.size / 4)
        return 10.0 * log10(255.0 * 255.0 / meanSquaredError)
    }

    private fun ByteArray.lumaAt(offset: Int): Int {
        val red = this[offset].toInt() and 0xff
        val green = this[offset + 1].toInt() and 0xff
        val blue = this[offset + 2].toInt() and 0xff
        return (77 * red + 150 * green + 29 * blue + 128) shr 8
    }

    private fun io.github.yuroyami.kiteplayer.spi.VideoFrame.asKiteFFmpegFrame(): KiteFFmpegVideoFrame {
        if (this is KiteFFmpegVideoFrame) return this
        close()
        error("KiteFFmpeg decoder returned an unexpected frame implementation: ${this::class}")
    }

    private class OpenedDecoder(
        val session: BackendSession,
        val stream: PlayerStreamInfo,
        val decoder: VideoDecoder?,
    ) : AutoCloseable {
        override fun close() {
            decoder?.close()
            session.close()
        }
    }

    private class DecodedFrame(
        private val session: BackendSession,
        val decoder: VideoDecoder,
        val frame: KiteFFmpegVideoFrame,
    ) : AutoCloseable {
        override fun close() {
            frame.close()
            decoder.close()
            session.close()
        }
    }

    private companion object {
        const val MIN_LUMA_PSNR_DB: Double = 40.0
        const val MAX_BACKPRESSURE_STEPS: Int = 32
        const val MAX_DRAIN_STEPS: Int = 32
    }
}

/** Keeps the test body suspendable without depending on a timing or dispatcher test library. */
private fun runDeviceTest(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
