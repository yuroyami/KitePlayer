@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.Generation
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
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The window's side of picture in picture, without a window.
 *
 * The system asks the playback delegate for answers and reports to the controller delegate, so
 * both are called here directly. The controller itself stands behind a seam that records what it
 * was told. The layer's clock is a real Core Media timebase on a real layer, because the window's
 * progress bar reads exactly that clock.
 *
 * No real controller is built here: the iOS simulator refuses one. The macOS tests call the same
 * delegates through the system's own methods with a real controller.
 */
class KitePlayerPictureInPictureTest {

    /** Unconfined, so a scripted state change is handled before the assignment returns. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() {
        scope.cancel()
    }

    private fun window(
        player: FakePlayer,
        control: FakeControl = FakeControl(),
        clock: LayerClock? = null,
    ) = KitePlayerPictureInPicture(player, control, clock, scope)

    private fun seconds(value: Double) = CMTimeMakeWithSeconds(value, TIMESCALE)

    @Test
    fun theWindowReadsItsStateAgainWhenStatusDurationOrSeekabilityChange() {
        val player = FakePlayer(PlayerSnapshot(status = PlaybackStatus.Paused, duration = 60.seconds, seekable = true))
        val control = FakeControl()
        val pip = window(player, control)
        assertEquals(1, control.invalidations, "the window reads the state once at the start")

        player.state.value = player.state.value.copy(status = PlaybackStatus.Playing)
        assertEquals(2, control.invalidations, "a pause from anywhere must reach the play button")
        player.state.value = player.state.value.copy(duration = 90.seconds)
        assertEquals(3, control.invalidations)
        player.state.value = player.state.value.copy(seekable = false)
        assertEquals(4, control.invalidations)

        player.state.value = player.state.value.copy(volume = 0.5f)
        assertEquals(4, control.invalidations, "a change the window does not show asks for nothing")
        pip.close()
    }

    @Test
    fun aSourceThatCannotSeekHidesTheSkipButtons() {
        val player = FakePlayer(PlayerSnapshot(status = PlaybackStatus.Playing, seekable = false))
        val control = FakeControl()
        val pip = window(player, control)
        assertTrue(control.requiresLinearPlayback)
        player.state.value = player.state.value.copy(seekable = true)
        assertFalse(control.requiresLinearPlayback)
        pip.close()
    }

    @Test
    fun theWindowsOwnReportsDriveActive() {
        val control = FakeControl()
        val pip = window(FakePlayer(), control)
        assertSame(pip.events, control.listener, "the controller must report to this object")
        assertFalse(pip.active.value)
        pip.events.started()
        assertTrue(pip.active.value)
        pip.events.stopped()
        assertFalse(pip.active.value)
        pip.close()
    }

    @Test
    fun aWindowThatFailsToOpenIsNotActive() {
        val pip = window(FakePlayer())
        pip.events.started()
        pip.events.failed()
        assertFalse(pip.active.value)
        pip.close()
    }

    @Test
    fun theRestoreRequestWaitsForTheApplication() {
        val pip = window(FakePlayer())
        var asked: ((Boolean) -> Unit)? = null
        pip.onRestoreRequested = { done -> asked = done }
        var restored: Boolean? = null
        pip.events.restore { restored = it }
        assertNull(restored, "the window must wait until the application shows the picture again")
        assertNotNull(asked).invoke(true)
        assertEquals(true, restored)
        pip.close()
    }

    @Test
    fun withoutARestoreHandlerTheWindowRestoresAtOnce() {
        val pip = window(FakePlayer())
        var restored: Boolean? = null
        pip.events.restore { restored = it }
        assertEquals(true, restored)
        pip.close()
    }

    @Test
    fun theWindowsPlayButtonPlaysAndPauses() {
        val player = FakePlayer()
        val playback = PlaybackDelegate(player, scope, 15.seconds)
        playback.play(true)
        playback.play(false)
        assertEquals(listOf("play", "pause"), player.calls)
    }

    @Test
    fun theWindowSeesPausedUnlessThePlayerPlays() {
        val player = FakePlayer(PlayerSnapshot(status = PlaybackStatus.Playing))
        val playback = PlaybackDelegate(player, scope, 15.seconds)
        assertFalse(playback.paused())
        player.state.value = PlayerSnapshot(status = PlaybackStatus.Paused)
        assertTrue(playback.paused())
        player.state.value = PlayerSnapshot(status = PlaybackStatus.Buffering)
        assertTrue(playback.paused())
    }

    @Test
    fun aKnownDurationIsTheWindowsRange() {
        val player = FakePlayer(PlayerSnapshot(duration = 90.seconds))
        val range = PlaybackDelegate(player, scope, 15.seconds).range()
        range.useContents {
            assertEquals(0.0, CMTimeGetSeconds(start.readValue()))
            assertEquals(90.0, CMTimeGetSeconds(duration.readValue()))
        }
    }

    @Test
    fun aLiveStreamAnswersAnUnboundedRange() {
        val player = FakePlayer(PlayerSnapshot(duration = null))
        val range = PlaybackDelegate(player, scope, 15.seconds).range()
        range.useContents {
            assertEquals(Double.NEGATIVE_INFINITY, CMTimeGetSeconds(start.readValue()))
            assertEquals(Double.POSITIVE_INFINITY, CMTimeGetSeconds(duration.readValue()))
        }
    }

    @Test
    fun aSkipMovesFromThePlayersPositionAndCompletes() {
        val player = FakePlayer().apply { now = 20.seconds }
        var completed = false
        PlaybackDelegate(player, scope, 15.seconds).skip(seconds(15.0)) { completed = true }
        assertEquals(listOf(35.seconds), player.seeks)
        assertTrue(completed, "the window waits for the completion before it draws again")
    }

    @Test
    fun aSkipBackStopsAtTheStart() {
        val player = FakePlayer().apply { now = 5.seconds }
        PlaybackDelegate(player, scope, 15.seconds).skip(seconds(-15.0)) {}
        assertEquals(listOf(Duration.ZERO), player.seeks)
    }

    @Test
    fun aZeroSkipMovesByTheConfiguredInterval() {
        val player = FakePlayer().apply { now = 20.seconds }
        PlaybackDelegate(player, scope, 10.seconds).skip(seconds(0.0)) {}
        assertEquals(listOf(30.seconds), player.seeks)
    }

    @Test
    fun theLayerClockFollowsThePlayersPositionAndSpeed() {
        val layer = AVSampleBufferDisplayLayer()
        val clock = assertNotNull(LayerClock.attachTo(layer))
        assertNotNull(layer.controlTimebase, "the window reads the layer's clock, so the layer needs one")
        val player = FakePlayer(PlayerSnapshot(status = PlaybackStatus.Playing, speed = 2.0)).apply { now = 10.seconds }
        val pip = window(player, clock = clock)
        assertEquals(2.0, clock.rate)
        assertTrue(clock.seconds in 10.0..10.5, "the clock must start at the position, read ${clock.seconds}")

        player.now = 12.seconds
        player.state.value = player.state.value.copy(status = PlaybackStatus.Paused)
        assertEquals(0.0, clock.rate, "a paused player stops the clock")
        assertEquals(12.0, clock.seconds, 0.01)

        player.now = 30.seconds
        player.state.value = player.state.value.copy(generation = Generation.Initial.next())
        assertEquals(30.0, clock.seconds, 0.01, "a seek moves the clock")

        player.state.value = player.state.value.copy(status = PlaybackStatus.Playing, speed = 1.0)
        assertEquals(1.0, clock.rate)

        pip.close()
        assertNull(layer.controlTimebase, "close takes the clock off the layer")
    }

    @Test
    fun closeStopsAnOpenWindowAndLetsGo() {
        val control = FakeControl().apply { isActive = true }
        val pip = window(FakePlayer(), control)
        pip.events.started()
        pip.close()
        assertEquals(listOf("stop", "release"), control.calls)
        assertFalse(pip.active.value)
        pip.close()
        pip.start()
        assertEquals(listOf("stop", "release"), control.calls, "a closed window does nothing")
    }
}

/** A player whose state the test writes. */
private class FakePlayer(initial: PlayerSnapshot = PlayerSnapshot()) : PictureInPicturePlayer {
    override val state = MutableStateFlow(initial)
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

/** A controller that records what it was told. */
private class FakeControl : PictureInPictureControl {
    override var isPossible: Boolean = true
    override var isActive: Boolean = false
    override var requiresLinearPlayback: Boolean = false
    override var startsAutomatically: Boolean = false
    var invalidations = 0
        private set
    var listener: AVPictureInPictureControllerDelegateProtocol? = null
        private set
    val calls = mutableListOf<String>()

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun invalidatePlaybackState() {
        invalidations++
    }

    override fun listen(listener: AVPictureInPictureControllerDelegateProtocol) {
        this.listener = listener
    }

    override fun release() {
        calls += "release"
    }
}
