package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A long file with a picture, played with video turned off as an app does in the background: the
 * open does not wait for a picture, and the sound plays every packet in order (#374).
 */
@NeedsPushedMedia
internal class ParkedVideoDeviceTest {

    @Test
    fun aParkedVideoLaneLeavesTheAudioWhole() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("no media dir on this device")
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = AndroidOutputBackend),
                progressInterval = 50.milliseconds,
                videoEnabled = false,
            ),
        )
        try {
            val opening = TimeSource.Monotonic.markNow()
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/soak30min.mp4")) }
            val openTook = opening.elapsedNow()
            player.play()
            var last = player.position()
            var biggestStep = Duration.ZERO
            val playing = TimeSource.Monotonic.markNow()
            // Long enough for the 30 s read-ahead budget to fill, which is when the relief ran.
            while (playing.elapsedNow() < 40.seconds) {
                delay(100)
                val position = player.position()
                biggestStep = maxOf(biggestStep, position - last)
                last = position
            }
            println("PARKED DEVICE open=$openTook reached=$last biggestStep=$biggestStep warnings=${player.warningHistory().map { it.warning }}")
            assertTrue(openTook < 3.seconds, "the open waited $openTook for a picture that never comes")
            assertEquals(PlaybackStatus.Playing, player.state.value.status)
            assertTrue(biggestStep < 1.seconds, "the position jumped by $biggestStep: the audio was cut")
            val warnings = player.warningHistory().map { it.warning }
            assertTrue(warnings.none { it is PlaybackWarning.PathologicalInterleaving }, "the relief cut the audio: $warnings")
        } finally {
            player.closeAndAwait()
        }
    }
}
