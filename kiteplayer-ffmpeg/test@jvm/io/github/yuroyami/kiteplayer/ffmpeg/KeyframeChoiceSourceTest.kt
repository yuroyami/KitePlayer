package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.KeyframeChoice
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The FFmpeg source lands a keyframe seek on the keyframe its [KeyframeChoice] names (#496), with
 * real FFmpeg on the JVM, in a 40 s file with a keyframe every 10 s and B-frames, so a keyframe's
 * decode time and show time differ. The fixture is written by the `ffmpeg` command line, and the
 * test skips where there is none, as the live tests do.
 */
class KeyframeChoiceSourceTest {

    @Test
    fun eachChoiceLandsOnItsKeyframeInMp4AndMatroska() = runBlocking {
        val ffmpeg = ffmpegCli ?: return@runBlocking
        val directory = Files.createTempDirectory("keyframe-choice").toFile()
        try {
            for (extension in listOf("mp4", "mkv")) {
                val file = File(directory, "sparse.$extension")
                writeSparseKeyframes(ffmpeg, file)
                KiteFFmpegMediaBackend().open(MediaItem(file.absolutePath)).use { session ->
                    val source = session.source
                    val video = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Video })
                    source.selectStreams(setOf(video.index))
                    suspend fun landing(target: Long, choice: KeyframeChoice): Long {
                        source.seekToKeyframe(Pts(target), choice)
                        return source.firstPictureMicros(video.index)
                    }
                    assertEquals(10_000_000, landing(17_000_000, KeyframeChoice.Before), extension)
                    assertEquals(20_000_000, landing(17_000_000, KeyframeChoice.After), extension)
                    assertEquals(20_000_000, landing(17_000_000, KeyframeChoice.Closest), extension)
                    assertEquals(10_000_000, landing(13_000_000, KeyframeChoice.Closest), extension)
                    assertEquals(20_000_000, landing(20_000_000, KeyframeChoice.After), extension)
                    // No keyframe follows 35 s, so both take the one before.
                    assertEquals(30_000_000, landing(35_000_000, KeyframeChoice.After), extension)
                    assertEquals(30_000_000, landing(35_000_000, KeyframeChoice.Closest), extension)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The show time of the first picture packet read, which a keyframe seek makes the keyframe. */
    private suspend fun PlayerMediaSource.firstPictureMicros(stream: Int): Long {
        while (true) {
            val packet = assertNotNull(readPacket(), "the media ended before a picture")
            packet.use {
                if (it.streamIndex == stream) {
                    assertTrue(it.isKeyframe, "the first picture after a keyframe seek is not a keyframe")
                    return assertNotNull(it.pts).micros
                }
            }
        }
    }

    /** 40 s of a moving test picture at 10 frames a second, a keyframe exactly every 10 s, with B-frames. */
    private fun writeSparseKeyframes(ffmpeg: String, file: File) {
        val process = ProcessBuilder(
            ffmpeg, "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=40",
            "-c:v", "libx264", "-preset", "veryfast", "-bf", "2",
            "-g", "100", "-keyint_min", "100", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "ffmpeg could not write the fixture: $log")
    }
}
