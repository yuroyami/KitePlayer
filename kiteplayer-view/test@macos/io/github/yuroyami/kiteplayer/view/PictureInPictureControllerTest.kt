@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerContentSource
import platform.AVKit.create
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The delegates called through the system's own methods, with a real controller.
 *
 * Only macOS runs this: the iOS simulator refuses to build a controller in a test process. The
 * answers themselves are tested on both platforms in `KitePlayerPictureInPictureTest`.
 */
class PictureInPictureControllerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val player = ScriptedPlayer()
    private val playback = PlaybackDelegate(player, scope, 15.seconds)
    private val controller = AVPictureInPictureController(
        contentSource = AVPictureInPictureControllerContentSource.create(
            sampleBufferDisplayLayer = AVSampleBufferDisplayLayer(),
            playbackDelegate = playback,
        ),
    )

    @AfterTest
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun theSystemsPlaybackQuestionsReachThePlayer() {
        player.state.value = PlayerSnapshot(status = PlaybackStatus.Playing, duration = 90.seconds)
        assertFalse(playback.pictureInPictureControllerIsPlaybackPaused(controller))
        playback.pictureInPictureControllerTimeRangeForPlayback(controller).useContents {
            assertEquals(90.0, CMTimeGetSeconds(duration.readValue()))
        }
        playback.pictureInPictureController(controller, setPlaying = false)
        assertEquals(listOf("pause"), player.calls)

        player.now = 20.seconds
        var completed = false
        playback.pictureInPictureController(controller, skipByInterval = CMTimeMakeWithSeconds(15.0, TIMESCALE)) {
            completed = true
        }
        assertEquals(listOf(35.seconds), player.seeks)
        assertTrue(completed)
    }

    @Test
    fun theSystemsWindowReportsReachActiveAndTheApplication() {
        val pip = KitePlayerPictureInPicture(player, ControllerControl(controller), clock = null, scope = scope)
        pip.events.pictureInPictureControllerDidStartPictureInPicture(controller)
        assertTrue(pip.active.value)
        pip.events.pictureInPictureController(
            controller,
            failedToStartPictureInPictureWithError = NSError(domain = "test", code = 1, userInfo = null),
        )
        assertFalse(pip.active.value)
        var restored: Boolean? = null
        pip.onRestoreRequested = { done -> done(false) }
        pip.events.pictureInPictureController(
            controller,
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler = { restored = it },
        )
        assertEquals(false, restored)
        pip.close()
    }

    @Test
    fun theAutomaticStartSwitchStaysOffOnMacos() {
        controller.startsAutomaticallyFromInline = true
        assertFalse(controller.startsAutomaticallyFromInline)
        val pip = KitePlayerPictureInPicture(player, ControllerControl(controller), clock = null, scope = scope)
        pip.startsAutomatically = true
        assertFalse(pip.startsAutomatically)
        pip.close()
    }
}

/** A player whose state the test writes. */
private class ScriptedPlayer : PictureInPicturePlayer {
    override val state = MutableStateFlow(PlayerSnapshot())
    var now: Duration = Duration.ZERO
    val calls = mutableListOf<String>()
    val seeks = mutableListOf<Duration>()

    override fun position(): Duration = now

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override suspend fun seek(to: Duration) {
        seeks += to
    }
}
