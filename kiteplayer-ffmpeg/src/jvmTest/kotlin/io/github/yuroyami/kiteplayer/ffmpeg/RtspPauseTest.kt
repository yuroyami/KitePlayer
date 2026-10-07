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
                // Opening leaves playback paused. Exercise that startup hold before the first play,
                // so this test includes the PAUSE/PLAY pair that otherwise depends on scheduling.
                awaitPauses(camera, 1)
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(2.seconds)
                val pausesBefore = camera.pauses.get()
                val playsBefore = camera.plays.get()
                player.pause()
                awaitPauses(camera, pausesBefore + 1)
                val pausedAt = player.position()
                // The camera has accepted PAUSE, so earlier playing keepalives cannot count here.
                val keepalivesBefore = camera.keepalives.get()
                delay(PAUSE)
                val pausedKeepalives = camera.keepalives.get() - keepalivesBefore
                assertEquals(playsBefore, camera.plays.get(), "the camera played during the pause:\n${camera.transcript}")
                player.play()
                awaitPlaying(player, camera.transcript)
                delay(1.seconds)
                val moved = player.position() - pausedAt
                val log = "status ${player.state.value.status}, error ${player.state.value.error}, moved $moved\n${camera.transcript}"
                assertEquals(0, camera.expired.get(), "the camera ended the session: $log")
                assertEquals(1, camera.connections, "the stream was opened again: $log")
                assertEquals(1, camera.pauses.get() - pausesBefore, "the camera was not told of the pause once: $log")
                assertTrue(pausedKeepalives >= 2, "the paused session was kept alive $pausedKeepalives times: $log")
                assertEquals(1, camera.plays.get() - playsBefore, "the camera was not asked to play on once: $log")
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

    /** Waits for the camera to accept PAUSE; the player's pause reply can precede the demux call. */
    private suspend fun awaitPauses(camera: RtspCamera, expected: Int) {
        try {
            withTimeout(5.seconds) { while (camera.pauses.get() < expected) delay(20) }
        } catch (late: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("expected $expected PAUSE requests, got ${camera.pauses.get()}\n${camera.transcript}", late)
        }
        assertEquals(expected, camera.pauses.get(), "unexpected PAUSE requests:\n${camera.transcript}")
    }

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
