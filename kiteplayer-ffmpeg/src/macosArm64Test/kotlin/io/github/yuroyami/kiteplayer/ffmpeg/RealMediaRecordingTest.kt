@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.BufferPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A recording made through the whole player, on real threads: the engine starts it on its own
 * thread while the demux thread copies packets. macOS only, because the iOS simulator cannot open
 * the audio device that a playing player needs.
 */
class RealMediaRecordingTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    /** A short read-ahead, because a recording starts where the player has read to, not at the picture. */
    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(
            backends = Backends(backend = KiteFFmpegMediaBackend(), output = AppleOutputBackend),
            buffer = BufferPolicy(softTarget = 1.seconds, totalDuration = 2.seconds),
            progressInterval = 50.milliseconds,
        ),
    )

    @Test
    fun aSeekEndsARecordingMadeWhilePlayingAndLeavesAValidFile() = runBlocking {
        val path = recordingScratchPath("kiteplayer-player-recording.mkv")
        val player = player()
        try {
            player.open(MediaItem("$mediaDir/sync1080p30.mp4"))
            player.play()
            player.startRecording(path)
            val played = withTimeoutOrNull(10.seconds) { player.progress.first { it.position > 3.seconds } }
            assertNotNull(played, "playback did not reach three seconds")

            player.seek(1.seconds, SeekMode.Precise)

            val stopped = player.warningHistory().map { it.warning }
                .filterIsInstance<PlaybackWarning.RecordingStopped>()
            assertEquals(listOf(path), stopped.map { it.path }, "the seek must end the recording with one warning")
            // A recording the seek failed to end still ends, one packet later, when the muxer refuses a
            // packet timed before the last one. That is a different warning, and a broken file.
            assertTrue("seek" in stopped.single().reason, "the seek must end the recording: ${stopped.single().reason}")
        } finally {
            player.close()
            withTimeoutOrNull(10.seconds) { player.state.first { it.status == PlaybackStatus.Idle } }
        }

        val recorded = KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource
        try {
            assertEquals(listOf(TrackKind.Video, TrackKind.Audio), recorded.streams.map { it.kind })
            val seconds = assertNotNull(recorded.duration, "the file must state its duration").micros / 1e6
            // Three seconds played. The file starts at a keyframe after the read-ahead, so it holds a
            // little less or a little more, and never the whole ten second clip.
            assertTrue(seconds in 1.0..5.0, "about three seconds were recorded and the file holds $seconds")
        } finally {
            recorded.close()
        }
    }
}
