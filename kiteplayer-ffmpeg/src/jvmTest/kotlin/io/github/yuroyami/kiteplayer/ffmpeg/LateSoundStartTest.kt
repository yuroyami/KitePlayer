package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A file whose sound starts after its picture shows that picture, with silence, and keeps the two in
 * step once the sound starts (#526), as mpv and VLC do. The fixture is the marker clip's flashing
 * picture for six seconds, with its beeping sound starting two seconds in, written as Matroska and as
 * a transport stream by the `ffmpeg` command line. The test skips where there is none.
 */
class LateSoundStartTest {

    @Test
    fun aMatroskaFileShowsThePictureBeforeItsSound() = playsFromThePicture("mkv")

    @Test
    fun aTransportStreamShowsThePictureBeforeItsSound() = playsFromThePicture("ts")

    @Test
    fun aSeekBeforeTheSoundLandsWhereItWasAsked() = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg, "mkv") { file ->
            val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
            try {
                player.attachRendererAndAwait(FlashRecorder())
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
                player.seek(500.milliseconds, SeekMode.Precise)
                player.play()
                delay(500.milliseconds)
                val position = player.position()
                assertTrue(position in 700.milliseconds..1300.milliseconds, "it plays on from the seek: $position")
            } finally {
                player.closeAndAwait()
            }
        }
    }

    private fun playsFromThePicture(extension: String) = runBlocking {
        val ffmpeg = requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        withFixture(ffmpeg, extension) { file ->
            val output = PacedOutput()
            val recorder = FlashRecorder()
            val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), output)))
            try {
                player.attachRendererAndAwait(recorder)
                withTimeout(30.seconds) { player.open(MediaItem(file.absolutePath)) }
                assertTrue(player.position() < 100.milliseconds, "it opens at the picture: ${player.position()}")
                player.play()
                delay(500.milliseconds)
                val early = player.position()
                assertTrue(early < 1.seconds, "playing starts with the picture, not the sound: $early")
                assertTrue(waitFor(8.seconds) { player.position() > 4500.milliseconds }, "it plays on: ${player.position()}")

                // The flashes at 0 and 1 s, before the sound, showed, and on time.
                val flashes = recorder.flashes.toList()
                assertTrue(flashes.size >= 5, "every flash showed: ${flashes.size}")
                val gaps = flashes.zipWithNext { a, b -> (b - a) / 1e6 }
                assertTrue(gaps.all { abs(it - 1000) < 80 }, "a second apart: $gaps")
                // Once the sound starts, it plays with the picture.
                val offsets = syncOffsetsMillis(output.beeps.toList(), flashes)
                assertTrue(offsets.size >= 2, "the sound was heard with its flashes: ${output.beeps}")
                assertTrue(offsets.all { abs(it) < 60 }, "in step: $offsets")
            } finally {
                player.closeAndAwait()
            }
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(limit) {
            while (!condition()) delay(50.milliseconds)
            true
        } ?: false

    private suspend fun withFixture(ffmpeg: String, extension: String, block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("latesound").toFile()
        try {
            val file = File(directory, "late.$extension")
            val flash = "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(t,1),0.1)'"
            val beep = "aevalsrc='0.8*sin(2*PI*(400+200*mod(floor(t),8))*t)*lt(mod(t,1),0.1)':s=48000:c=stereo:d=4"
            val process = ProcessBuilder(
                ffmpeg, "-v", "error", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=160x90:r=25:d=6,$flash",
                "-itsoffset", "2", "-f", "lavfi", "-i", beep,
                "-map", "0:v", "-map", "1:a",
                "-c:v", "libx264", "-preset", "veryfast", "-g", "25", "-pix_fmt", "yuv420p",
                "-c:a", "aac", file.absolutePath,
            ).redirectErrorStream(true).start()
            val log = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.waitFor(), "ffmpeg could not write the fixture: $log")
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }
}
