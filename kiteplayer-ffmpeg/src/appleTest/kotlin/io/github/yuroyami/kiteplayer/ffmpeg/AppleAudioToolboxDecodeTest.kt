@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AudioToolbox, measured rather than assumed.
 *
 * The build now compiles Apple's `*_at` decoders and the factory names four of them, which means real
 * AAC files stopped going through FFmpeg's native decoder on every Apple target. That is a decoder
 * swap on the most common audio codec there is, so it owes evidence: that it actually opens, that it
 * decodes the same audio, and by how much its samples differ from the decoder it replaced.
 *
 * The comparison is native-against-platform on the SAME file in the SAME run, so nothing here depends
 * on a stored reference or on this machine's FFmpeg.
 */
class AppleAudioToolboxDecodeTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    private class Opened(
        val source: KiteCodecSource,
        val stream: PlayerStreamInfo,
        val decoder: AudioDecoder,
        val warnings: MutableList<PlaybackWarning>,
    )

    private suspend fun open(clip: String, platformPreferred: Boolean): Opened {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/$clip")) as KiteCodecSource
        val warnings = mutableListOf<PlaybackWarning>()
        source.onWarning = { warnings += it }
        source.preferPlatformAudioDecoder = platformPreferred
        val stream = assertNotNull(source.firstAudio, "no audio stream in $clip")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(
            source.audioDecoderFactories().first().create(stream),
            "the factory must produce an audio decoder for $clip",
        )
        return Opened(source, stream, decoder, warnings)
    }

    /** Interleaved float for the first [buffers] decoded buffers, and the channel count they carried. */
    private suspend fun decode(opened: Opened, buffers: Int): Pair<FloatArray, Int> {
        val out = mutableListOf<Float>()
        var channels = 0
        var produced = 0
        while (produced < buffers) {
            val packet = opened.source.readPacket() ?: break
            val forUs = packet.streamIndex == opened.stream.index
            if (!forUs) {
                packet.close()
                continue
            }
            if (!opened.decoder.send(packet)) {
                packet.close()
                continue
            }
            packet.close()
            while (true) {
                val buffer = opened.decoder.receive() ?: break
                channels = buffer.format.channels
                out += buffer.interleavedFloat().toList()
                buffer.close()
                produced += 1
                if (produced >= buffers) break
            }
        }
        return out.toFloatArray() to channels
    }

    /**
     * The headline: `aac_at` opens on this platform, and its output is the same audio as native aac.
     *
     * "Same audio" is not "same bits". AAC is not specified to the bit across decoders, so the bound
     * here is an AUDIBILITY bound rather than a rounding bound: 1e-2 on a full-scale float is about
     * -40 dBFS on a single sample. The measured worst difference sits well inside it. If a future SDK
     * ever makes Apple's decoder disagree about the actual content (wrong channel order, wrong
     * sample rate, dropped priming) this bound is crossed by orders of magnitude, not by a little.
     */
    @Test
    fun audioToolboxDecodesTheSameAudioAsTheNativeDecoder() = runBlocking {
        val platform = open("surround51.mp4", platformPreferred = true)
        val native = open("surround51.mp4", platformPreferred = false)

        // No fallback fired: the platform decoder really is the one under measurement.
        assertTrue(
            platform.warnings.none { it is PlaybackWarning.HardwareDecodeUnavailable },
            "aac_at must open on this platform, but the factory fell back: ${platform.warnings}",
        )
        assertEquals(CodecIdNameForAac, platformAudioDecoder("aac")?.name)

        val (platformSamples, platformChannels) = decode(platform, buffers = 24)
        val (nativeSamples, nativeChannels) = decode(native, buffers = 24)

        assertEquals(6, nativeChannels, "the fixture must decode as six channels")
        assertEquals(
            nativeChannels,
            platformChannels,
            "aac_at must report the same channel count as the native decoder",
        )
        assertTrue(platformSamples.isNotEmpty(), "aac_at produced no samples at all")

        // Both decoders must reach the same amount of audio for the same input. A priming or delay
        // difference would show here first, as a length mismatch rather than a sample mismatch.
        val compared = minOf(platformSamples.size, nativeSamples.size)
        assertTrue(compared > 0, "nothing decoded to compare")
        val lengthDrift = abs(platformSamples.size - nativeSamples.size).toDouble() / compared
        assertTrue(
            lengthDrift < 0.05,
            "aac_at produced ${platformSamples.size} samples against native ${nativeSamples.size}: " +
                "a length difference this large means the priming samples were handled differently",
        )

        var worst = 0f
        var total = 0.0
        for (i in 0 until compared) {
            val difference = abs(platformSamples[i] - nativeSamples[i])
            total += difference
            if (difference > worst) worst = difference
        }
        val mean = total / compared

        // Printed, not only asserted: the whole point of this file is the NUMBER, and a passing test
        // that hides its measurement teaches nothing the next time an SDK changes.
        println(
            "aac_at vs native aac on surround51.mp4: worst=$worst mean=$mean over $compared samples, " +
                "platform=${platformSamples.size} native=${nativeSamples.size} samples, ${platformChannels}ch",
        )

        assertTrue(
            worst < 1e-2,
            "aac_at worst sample difference $worst against native aac is above the audibility bound; " +
                "mean was $mean over $compared samples",
        )
        assertTrue(
            mean < 1e-3,
            "aac_at mean sample difference $mean against native aac is too high over $compared samples",
        )

        // Real content, not silence, or the bounds above would pass on two silent buffers.
        assertTrue(
            platformSamples.any { abs(it) > 1e-3f },
            "aac_at decoded only silence, so nothing above was actually measured",
        )

        platform.decoder.close()
        native.decoder.close()
        platform.source.close()
        native.source.close()
    }

    private companion object {
        const val CodecIdNameForAac = "aac_at"
    }
}
