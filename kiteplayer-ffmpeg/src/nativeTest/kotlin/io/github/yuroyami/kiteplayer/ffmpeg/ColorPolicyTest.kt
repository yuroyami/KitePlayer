@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the backend says out loud about colour it can only approximate.
 *
 * There is no tone mapping and no constant luminance path, and both facts are invisible in the
 * picture: an HDR clip converted with the matrix alone looks flat rather than broken, which is exactly
 * the kind of degradation a developer would otherwise have to discover by measurement. So the decoder
 * says it once, playback continues, and these cases hold it to both halves of that: it does say it,
 * and it says it once.
 */
class ColorPolicyTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    /** Decodes every frame of a clip's video track, collecting the warnings the source reported. */
    private suspend fun warningsFromDecodingAll(clip: String): Pair<List<PlaybackWarning>, List<VideoFrame>> {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/$clip")) as KiteCodecSource
        val warnings = mutableListOf<PlaybackWarning>()
        source.onWarning = { warnings += it }

        val stream = assertNotNull(source.firstVideo, "no video stream in $clip")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, HwdecPolicy.Auto))
        val frames = mutableListOf<VideoFrame>()
        try {
            while (true) {
                val packet = source.readPacket()
                if (packet == null) {
                    decoder.send(null)
                    while (true) frames += decoder.receive() ?: break
                    break
                }
                while (!decoder.send(packet)) {
                    frames += decoder.receive() ?: break
                }
                packet.close()
                while (true) frames += decoder.receive() ?: break
            }
        } finally {
            decoder.close()
            source.close()
        }
        return warnings to frames
    }

    @Test
    fun `a PQ clip reports tone mapping unavailable exactly once`() = runBlocking {
        val (warnings, frames) = warningsFromDecodingAll("colors-pq.mp4")
        try {
            assertTrue(frames.size > 1, "the fixture must have more than one frame, or once is trivial")
            assertEquals(
                ColorTransfer.Pq,
                frames.first().colorSpace.transfer,
                "the fixture must decode as PQ, or nothing here is about HDR",
            )
            assertEquals(
                1,
                warnings.size,
                "one warning per stream and not one per frame, and ${frames.size} frames were decoded. " +
                    "Got: ${warnings.map { it.message }}",
            )
            val warning = assertNotNull(warnings.single() as? PlaybackWarning.TonemappingUnavailable)
            assertTrue(
                warning.detail.contains("Pq"),
                "the warning must name what was approximated: ${warning.detail}",
            )
        } finally {
            frames.forEach { it.close() }
        }
    }

    @Test
    fun `a PQ clip still converts to a picture`() = runBlocking {
        // The policy is approximate and carry on, not refuse. A warning that came with a blank frame
        // would be a failure wearing a warning's clothes.
        val (_, frames) = warningsFromDecodingAll("colors-pq.mp4")
        try {
            val rgba = SoftwareConverter.toRgba(frames.first() as KiteCodecVideoFrame)
            assertEquals(320 * 240 * 4, rgba.size)
            assertTrue(rgba.any { it.toInt() != 0 }, "an approximated frame is still a frame")
        } finally {
            frames.forEach { it.close() }
        }
    }

    @Test
    fun `a BT2020 constant luminance clip reports the same warning once`() = runBlocking {
        // Constant luminance is converted with the non-constant luminance matrix, which is wrong in the
        // same unfixable way HDR is: both need the transfer function in the loop. Same policy, same
        // warning, and the detail is what tells the two apart.
        val (warnings, frames) = warningsFromDecodingAll("colors-bt2020cl.mp4")
        try {
            assertEquals(
                ColorMatrix.Bt2020Cl,
                frames.first().colorSpace.matrix,
                "the fixture must decode as constant luminance",
            )
            assertEquals(1, warnings.size, "once per stream. Got: ${warnings.map { it.message }}")
            val warning = assertNotNull(warnings.single() as? PlaybackWarning.TonemappingUnavailable)
            assertTrue(
                warning.detail.contains("constant luminance"),
                "the detail must say which approximation was made: ${warning.detail}",
            )
        } finally {
            frames.forEach { it.close() }
        }
    }

    @Test
    fun `an ordinary clip reports nothing`() = runBlocking {
        // The other half of a one-time warning: a warning nobody needs is noise, and noise is what
        // stops the ones that matter from being read.
        val (warnings, frames) = warningsFromDecodingAll("colors-bt709.mp4")
        try {
            assertEquals(
                emptyList(),
                warnings.map { it.message },
                "BT.709 with a BT.709 transfer is converted exactly, so there is nothing to admit to",
            )
        } finally {
            frames.forEach { it.close() }
        }
    }
    // Audit F-HDR1: the native converter must run the same HDR-to-SDR law the packed common
    // path runs. It used to skip the hook entirely, so the same public API returned washed-out
    // pixels on Apple and tone-mapped ones on the JVM.
    @Test
    fun `the native converter tone maps a pq frame exactly like the packed law`() = runBlocking {
        val (_, frames) = warningsFromDecodingAll("colors-pq.mp4")
        try {
            val frame = frames.first() as KiteCodecVideoFrame
            val native = SoftwareConverter.toRgba(frame)
            val readable = frame.readableFrame()
            val packed = tightlyPackedToRgba(
                bytes = readable.copyPlanesToByteArray(),
                width = frame.size.width,
                height = frame.size.height,
                pixelFormat = readable.info.pixelFormat.toPlayerFormat(),
                colorSpace = frame.colorSpace,
            )
            assertTrue(
                native.contentEquals(packed),
                "the two software converters must agree byte for byte on an HDR frame; " +
                    "first difference at index " +
                    native.indices.firstOrNull { native[it] != packed[it] }.toString(),
            )
        } finally {
            frames.forEach { it.close() }
        }
    }
}
