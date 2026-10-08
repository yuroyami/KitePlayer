package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioSource
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A picture file and a sound file played as one item (#392), through the whole player and FFmpeg.
 * Both files are stream copies of one clip, so played together they must come out as that clip does:
 * each beep with its flash, within the window the one-file marker clip is held to, before a seek
 * and after it.
 */
class ExternalAudioPlaybackTest {

    @Test
    fun aPictureFileAndASoundFilePlayInStepThroughASeek() = runBlocking {
        requireTestMedia(ffmpegCli, "no ffmpeg on PATH")
        val whole = MarkerClip.make(14)
        val picture = cut(whole, "marker-14-picture.mp4", "-an")
        val sound = cut(whole, "marker-14-sound.m4a", "-vn")
        val output = PacedOutput()
        val renderer = FlashRecorder()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), output), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(renderer)
            val item = MediaItem(picture.absolutePath, externalAudio = listOf(AudioSource(sound.absolutePath, title = "Sound")))
            withTimeout(30.seconds) { player.open(item) }
            val tracks = player.state.value.tracks
            assertEquals(1, tracks.video.size, "only the sound of an input is the item's: ${tracks.all}")
            val track = tracks.audio.single()
            assertEquals("Sound", track.title)
            assertEquals(track.id, tracks.selectedAudio, "the only sound was not chosen")

            player.play()
            assertTrue(waitFor(15.seconds) { player.state.value.status == PlaybackStatus.Playing }, "it did not start")
            val settled = output.clock.nanos() + 1_000_000_000L
            delay(5.seconds)
            val offsets = syncOffsetsMillis(output.beeps.filter { it.atNanos >= settled }, renderer.flashes)
            println("ExternalAudioPlaybackTest: sound behind the picture by ${offsets.map { it.toInt() }} ms")
            assertTrue(offsets.size >= 3, "only ${offsets.size} beeps with a flash: ${output.beeps}, ${renderer.flashes.size} flashes")
            assertTrue(offsets.all { it in SYNC_WINDOW_MILLIS }, "sound behind the picture by $offsets ms, outside $SYNC_WINDOW_MILLIS")

            // From 9.3 s the clip beeps at 10, 11 and 12 s, and each beep's pitch names its second.
            player.seek(9300.milliseconds, SeekMode.Precise)
            val sought = output.clock.nanos()
            delay(3500.milliseconds)
            val beeps = output.beeps.filter { it.atNanos >= sought }
            val seconds = beeps.map { MarkerClip.secondOf(it.hertz) }
            assertEquals(listOf(10 % 8, 11 % 8), seconds.take(2), "the sound did not land where the picture did: $beeps")
            val after = syncOffsetsMillis(beeps, renderer.flashes.filter { it >= sought })
            println("ExternalAudioPlaybackTest: after the seek, sound behind the picture by ${after.map { it.toInt() }} ms")
            assertTrue(after.size >= 2, "only ${after.size} beeps with a flash after the seek: $beeps")
            assertTrue(after.all { it in SYNC_WINDOW_MILLIS }, "after the seek, sound behind the picture by $after ms")

            val warnings = player.warningHistory().map { it.warning }
            assertTrue(
                warnings.none { it is PlaybackWarning.AudioSourceUnreadable || it is PlaybackWarning.PathologicalInterleaving },
                "$warnings",
            )
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun theHouseClipCutInTwoSeeksWithinAFrameAndPlaysToItsEnd() = runBlocking {
        val dir = File(formatMatrixMediaDir() ?: "testmedia")
        val picture = requireTestMedia(File(dir, "sync1080p30-video.mp4").takeIf { it.isFile }, "no sync1080p30-video.mp4; run scripts/testmedia.sh")
        val sound = requireTestMedia(File(dir, "sync1080p30-audio.m4a").takeIf { it.isFile }, "no sync1080p30-audio.m4a; run scripts/testmedia.sh")
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        try {
            player.attachRendererAndAwait(FlashRecorder())
            withTimeout(30.seconds) {
                player.open(MediaItem(picture.absolutePath, externalAudio = listOf(AudioSource(sound.absolutePath))))
            }
            val tracks = player.state.value.tracks
            assertEquals(tracks.audio.single().id, tracks.selectedAudio)
            assertEquals("aac", tracks.audio.single().codec)

            player.seek(6.seconds, SeekMode.Precise)
            val landed = player.position()
            // One frame of the 30 fps clip.
            assertTrue((landed - 6.seconds).absoluteValue <= 34.milliseconds, "the seek to 6 s landed at $landed")
            player.play()
            assertTrue(waitFor(8.seconds) { player.position() > 8.seconds }, "it did not play on from the seek: ${player.position()}")
            assertTrue(
                waitFor(8.seconds) { player.state.value.status == PlaybackStatus.Ended },
                "it did not end: ${player.state.value.status} at ${player.position()}",
            )
            val warnings = player.warningHistory().map { it.warning }
            assertTrue(warnings.none { it is PlaybackWarning.AudioSourceUnreadable }, "$warnings")
        } finally {
            player.closeAndAwait()
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(limit) {
            while (!condition()) delay(20.milliseconds)
            true
        } ?: false

    /** A stream copy of [whole] with one kind of stream taken out by [drop], `-an` or `-vn`. */
    private fun cut(whole: File, name: String, drop: String): File {
        val file = File(MarkerClip.dir, name)
        if (file.isFile) return file
        FFmpegProcess(
            listOf("-v", "error", "-y", "-i", whole.absolutePath, drop, "-c", "copy", file.absolutePath),
            File(MarkerClip.dir, "$name.log"),
        ).use { maker ->
            check(maker.waitFor(60) == 0 && file.isFile) { "the clip was not cut: ${maker.logText()}" }
        }
        return file
    }

    private companion object {
        /** The window `LivePlaybackTest` holds the one-file marker clip to. */
        val SYNC_WINDOW_MILLIS = -40.0..60.0
    }
}
