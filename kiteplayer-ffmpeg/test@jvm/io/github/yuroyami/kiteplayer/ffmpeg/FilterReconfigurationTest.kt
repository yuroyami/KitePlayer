@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A filter chain survives a stream that changes its format mid-file (#484). A filter graph takes one
 * input format, so the decoders build a new one when the format moves, and the old graph first gives
 * back what it still holds, as the `ffmpeg` command line does. The fixtures are two recordings joined
 * end to end, written by the `ffmpeg` command line.
 */
class FilterReconfigurationTest {

    /**
     * A 44.1 kHz AAC second followed by a 48 kHz one in one transport stream, slowed by the tempo filter. It keeps a window of
     * sound inside the graph, which is still there when the rate changes, so each rate's share of the
     * output is exactly what the same chain gives for that part alone only if that window comes out.
     */
    @Test
    fun aSoundThatChangesItsRateKeepsWhatTheFilterHeldBeforeTheChange() = withFixtures { dir, ffmpeg ->
        val first = File(dir, "first.ts").also { ffmpeg.sound(it, rate = 44_100, frequency = 440) }
        val second = File(dir, "second.ts").also { ffmpeg.sound(it, rate = 48_000, frequency = 660) }
        val joined = File(dir, "joined.ts").apply { writeBytes(first.readBytes() + second.readBytes()) }

        val alone = sampleFramesByRate(first, CHAIN) + sampleFramesByRate(second, CHAIN)
        val together = sampleFramesByRate(joined, CHAIN)
        assertEquals(setOf(44_100, 48_000), alone.keys, "the parts decode at other rates: $alone")
        // Without the old graph's tail the first part came out 2,221 sample frames short.
        assertEquals(alone[44_100], together[44_100], "the first part lost what the graph held at the change")
        // The second part decodes to the same sample count joined and alone, but the decoder carries
        // its state across the join, so the tempo filter splices it a few samples apart: 17, measured.
        val secondAlone = alone.getValue(48_000)
        val secondJoined = assertNotNull(together[48_000], "nothing came out after the change")
        assertTrue(abs(secondJoined - secondAlone) <= 48, "the second part came out $secondJoined, not $secondAlone")
    }

    /**
     * A 320 by 240 picture followed by a 640 by 480 one in one transport stream, through a crop to
     * half of each side. The crop is worked out from the size the graph was built for, so every
     * picture after the change has to come out at half of its own size, and none may be lost.
     */
    @Test
    fun aPictureThatChangesItsSizeIsFilteredAtItsNewSize() = withFixtures { dir, ffmpeg ->
        val small = File(dir, "small.ts").also { ffmpeg.picture(it, width = 320, height = 240) }
        val large = File(dir, "large.ts").also { ffmpeg.picture(it, width = 640, height = 480) }
        val joined = File(dir, "joined.ts").apply { writeBytes(small.readBytes() + large.readBytes()) }

        val sizes = pictureSizes(joined, "crop=iw/2:ih/2")
        assertEquals(List(FRAMES) { 160 to 120 } + List(FRAMES) { 320 to 240 }, sizes)
    }

    /** How many sample frames the backend decodes from [file] through [chain], by their rate. */
    private fun sampleFramesByRate(file: File, chain: String): Map<Int, Int> = runBlocking {
        val source = KiteFFmpegSourceFactory().open(MediaItem(file.absolutePath).copy(audioFilter = chain)) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Audio }, "${file.name} has no sound")
            source.selectStreams(setOf(stream.index))
            val decoder = source.newAudioDecoder(stream)
            val frames = LinkedHashMap<Int, Int>()
            try {
                suspend fun drain() {
                    while (true) {
                        val buffer = decoder.receive() ?: break
                        try {
                            frames[buffer.format.sampleRate] = (frames[buffer.format.sampleRate] ?: 0) + buffer.frameCount
                        } finally {
                            buffer.close()
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
            } finally {
                decoder.close()
            }
            frames
        } finally {
            source.close()
        }
    }

    /** The width and height of every picture the backend decodes from [file] through [chain], in order. */
    private fun pictureSizes(file: File, chain: String): List<Pair<Int, Int>> = runBlocking {
        val source = KiteFFmpegSourceFactory().open(MediaItem(file.absolutePath, videoFilter = chain)) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.firstVideo, "${file.name} has no picture")
            source.selectStreams(setOf(stream.index))
            val decoder = source.newVideoDecoder(stream)
            val sizes = ArrayList<Pair<Int, Int>>()
            try {
                suspend fun drain() {
                    while (true) {
                        val frame = decoder.receive() ?: break
                        try {
                            sizes += frame.size.width to frame.size.height
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
            } finally {
                decoder.close()
            }
            sizes
        } finally {
            source.close()
        }
    }

    private fun withFixtures(test: (File, String) -> Unit) {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        val dir = Files.createTempDirectory("filterreconfiguration").toFile()
        try {
            test(dir, ffmpeg)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun String.sound(out: File, rate: Int, frequency: Int) = run(
        "-f", "lavfi", "-i", "sine=frequency=$frequency:sample_rate=$rate:duration=1",
        "-c:a", "aac", "-b:a", "96k", "-f", "mpegts", out.absolutePath,
    )

    private fun String.picture(out: File, width: Int, height: Int) = run(
        "-f", "lavfi", "-i", "testsrc2=size=${width}x$height:rate=10:duration=${FRAMES / 10.0}",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "10", "-bf", "0",
        "-f", "mpegts", out.absolutePath,
    )

    private fun String.run(vararg arguments: String) {
        val process = ProcessBuilder(listOf(this, "-v", "error", "-y") + arguments).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "ffmpeg failed: $log")
    }

    private companion object {
        const val CHAIN = "atempo=0.8"
        const val FRAMES = 20
    }
}
