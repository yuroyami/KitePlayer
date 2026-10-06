package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.BufferPolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player on an RTSP camera that ends a session it does not hear from within two seconds
 * (#441). A pause of five seconds outlasts that and the player's two seconds of read-ahead, so only
 * the keepalives a paused player sends keep the session, and play then goes on at the live edge,
 * over the same connection. A camera that
 * drops the session anyway is opened again on play rather than failing.
 *
 * [RtspCamera] stamps its media with the wall time, as a camera does, so the position jumps by
 * about the length of the pause when play goes on at the live edge, and would not move at all if
 * the player played what it had buffered before the pause.
 */
class RtspPauseTest {

    @Test
    fun aPausePastTheSessionTimeoutIsKeptAliveAndPlaysOnAtTheLiveEdge() = runBlocking {
        RtspCamera(timeoutSeconds = 2).use { camera ->
            val player = player()
            try {
                withTimeout(30.seconds) { player.open(MediaItem(camera.url, openOptions = mapOf("rtsp_transport" to "tcp"))) }
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(2.seconds)
                player.pause()
                val pausedAt = player.position()
                delay(PAUSE)
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(1.seconds)
                val moved = player.position() - pausedAt
                val log = "status ${player.state.value.status}, error ${player.state.value.error}, moved $moved\n${camera.transcript}"
                assertEquals(0, camera.expired.get(), "the camera ended the session: $log")
                assertEquals(1, camera.connections, "the stream was opened again: $log")
                assertEquals(1, camera.pauses.get(), "the camera was not told of the pause: $log")
                assertTrue(camera.keepalives.get() >= 2, "the session was kept alive ${camera.keepalives.get()} times: $log")
                assertEquals(2, camera.plays.get(), "the camera was not asked to play on: $log")
                assertTrue(moved >= PAUSE, "play went on $moved from where the pause left it: $log")
                assertEquals(PlaybackStatus.Playing, player.state.value.status, log)
            } finally {
                player.closeAndAwait()
            }
        }
    }

    @Test
    fun aSessionTheCameraDroppedIsOpenedAgainOnPlay() = runBlocking {
        RtspCamera(timeoutSeconds = 2, keepsPausedSessions = false).use { camera ->
            val player = player()
            val seen = Collections.synchronizedList(mutableListOf<PlayerEvent>())
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { player.events.collect { seen += it } }
            try {
                withTimeout(30.seconds) { player.open(MediaItem(camera.url, openOptions = mapOf("rtsp_transport" to "tcp"))) }
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(2.seconds)
                player.pause()
                delay(PAUSE)
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(1.seconds)
                val log = "status ${player.state.value.status}, error ${player.state.value.error}\n${camera.transcript}"
                assertEquals(1, camera.expired.get(), "the camera kept the session: $log")
                assertEquals(2, camera.connections, "the stream was not opened again: $log")
                assertTrue(
                    seen.any { it is PlayerEvent.Warning && it.warning is PlaybackWarning.SourceReconnecting },
                    "nothing said the stream was opened again: $log",
                )
                assertEquals(PlaybackStatus.Playing, player.state.value.status, log)
            } finally {
                listener.cancel()
                player.closeAndAwait()
            }
        }
    }

    /**
     * A player that reads two seconds ahead, so a pause of five fills its buffer and its reads stop
     * well before the pause ends, as the default buffer does in a pause of a minute or two.
     */
    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(
            backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()),
            buffer = BufferPolicy(softTarget = 1.seconds, totalDuration = 2.seconds),
            progressInterval = 50.milliseconds,
        ),
    )

    private suspend fun awaitPlaying(player: KitePlayer, transcript: String) {
        try {
            withTimeout(15.seconds) { while (player.state.value.status != PlaybackStatus.Playing) delay(20) }
        } catch (late: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("not playing: ${player.state.value.status}, error ${player.state.value.error}\n$transcript", late)
        }
    }

    private companion object {
        /** Longer than the camera's session timeout, so only a keepalive keeps the session. */
        val PAUSE: Duration = 5.seconds
    }
}
