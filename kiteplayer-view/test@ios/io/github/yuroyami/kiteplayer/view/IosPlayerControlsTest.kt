@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAction
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIMenuElementState
import platform.UIKit.accessibilityLabel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The UIKit controls, against a scripted player and real UIKit objects in the simulator (#469).
 *
 * No finger touches anything here: a control is sent the events UIKit sends it for a touch.
 */
class IosPlayerControlsTest {

    private val unconfined = CoroutineScope(Dispatchers.Unconfined)

    private fun overlay(player: ScriptedControlsPlayer): ControlsOverlayView {
        // The timeout never fires here; PlayerControlsModelTest owns it.
        val model = PlayerControlsModel(player, unconfined, PlayerControlsStrings.Default, 1.hours, DefaultSpeeds, unconfined)
        return ControlsOverlayView(model, unconfined).also {
            it.setFrame(CGRectMake(0.0, 0.0, 400.0, 300.0))
            it.layoutIfNeeded()
        }
    }

    /** What the player was asked, without the subtitle lift every shown bar starts with. */
    private val ScriptedControlsPlayer.commands: List<String> get() = calls.filterNot { it.startsWith("subtitles") }

    @Test
    fun aPressOnAButtonReachesThePlayer() {
        val items = List(2) { MediaItem("item$it.mp4") }
        val player = ScriptedControlsPlayer(playingSnapshot().copy(queue = items, queueIndex = 0, queueOrder = listOf(0, 1)))
        val overlay = overlay(player)
        assertEquals("Pause", overlay.playButton.accessibilityLabel)
        overlay.playButton.sendActionsForControlEvents(UIControlEventTouchUpInside)
        overlay.nextButton.sendActionsForControlEvents(UIControlEventTouchUpInside)
        overlay.muteButton.sendActionsForControlEvents(UIControlEventTouchUpInside)
        assertEquals(listOf("pause", "next", "muted true"), player.commands)
        assertEquals("Play", overlay.playButton.accessibilityLabel)
        assertEquals("Unmute", overlay.muteButton.accessibilityLabel)
        // The first item has nothing before it, so its button is there and disabled.
        assertFalse(overlay.previousButton.hidden)
        assertFalse(overlay.previousButton.enabled)
        assertTrue(overlay.nextButton.enabled)
        overlay.close()
    }

    @Test
    fun aDragOfTheSliderScrubsAndAnAdjustmentIsOneSeek() {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 10.seconds))
        val overlay = overlay(player)
        assertTrue(overlay.showsSeekBar)
        // The slider itself waits for a window, so the test calls what its events call.
        assertNull(overlay.seekBar)

        // VoiceOver adjusts the slider with no finger down: the value changes, and that is one seek.
        overlay.seekMoved(0.5f, tracking = false)
        assertEquals(listOf("seek 50s KeyframeThenRefine"), player.commands)
        assertNull(overlay.model.state.value.scrubTarget)

        // A finger down starts a scrub where the thumb stands, and lifting it ends the scrub.
        overlay.seekTouched(0.25f)
        assertEquals(25.seconds, overlay.model.state.value.scrubTarget)
        assertEquals("0:25", overlay.scrubLabel.text)
        assertFalse(overlay.scrubLabel.hidden)
        overlay.seekMoved(0.75f, tracking = true)
        assertEquals("1:15", overlay.scrubLabel.text)
        overlay.seekReleased()
        assertNull(overlay.model.state.value.scrubTarget)
        assertTrue(overlay.scrubLabel.hidden)
        assertEquals("seek 1m 15s KeyframeThenRefine", player.commands.last())
        overlay.close()
    }

    @Test
    fun theBarShowsTheTimesAndTheBufferedRangesAndFollowsThePlayer() {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 40.seconds, bufferedRanges = listOf(10.seconds..30.seconds, 50.seconds..100.seconds)))
        val overlay = overlay(player)
        assertEquals("0:40 / 1:40", overlay.timeLabel.text)
        val ranges = overlay.bufferedFrames()
        assertEquals(2, ranges.size)
        val first = ranges[0].useContents { size.width }
        val second = ranges[1].useContents { size.width }
        // 20 seconds and 50 seconds of the same bar.
        assertTrue(abs(second / first - 2.5) < 0.01, "$first then $second")

        player.progress.value = Progress(position = 50.seconds)
        assertEquals("0:50 / 1:40", overlay.timeLabel.text)
        assertEquals(0.5f, overlay.model.state.value.fraction)
        assertEquals(0, overlay.bufferedFrames().size)

        // A live item has no seek bar, and the bar is shorter for it.
        val tall = overlay.bar.frame.useContents { size.height }
        player.state.value = player.state.value.copy(duration = null)
        overlay.layoutIfNeeded()
        assertFalse(overlay.showsSeekBar)
        assertEquals("0:50", overlay.timeLabel.text)
        assertTrue(overlay.bar.frame.useContents { size.height } < tall)
        overlay.close()
    }

    @Test
    fun aTapOnThePictureShowsAndHidesTheBarAndSubtitlesStandAboveIt() {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val overlay = overlay(player)
        assertFalse(overlay.bar.hidden)
        // 8 of margin, 32 of seek bar and 44 of buttons, of 300 points.
        assertEquals(84f / 300f, overlay.model.barShare)
        assertEquals(listOf("subtitles 720"), player.calls)

        overlay.pictureTapped()
        assertTrue(overlay.bar.hidden)
        assertEquals("subtitles 1000", player.calls.last())
        overlay.layoutIfNeeded()
        assertEquals(300.0, overlay.picture.frame.useContents { size.height })

        overlay.pictureTapped()
        assertFalse(overlay.bar.hidden)
        overlay.layoutIfNeeded()
        assertEquals(216.0, overlay.picture.frame.useContents { size.height })
        overlay.close()
    }

    @Test
    fun eachMenuButtonCarriesItsChoicesAndAChoiceReachesThePlayer() {
        val tracks = Tracks(
            all = listOf(
                TrackInfo(TrackId(1), TrackKind.Audio, "aac", language = "en"),
                TrackInfo(TrackId(2), TrackKind.Audio, "aac", language = "fr"),
            ),
            selectedAudio = TrackId(2),
        )
        val player = ScriptedControlsPlayer(playingSnapshot().copy(tracks = tracks))
        val overlay = overlay(player)
        val audio = overlay.menuButtons.getValue(PlayerControlsMenu.Audio)
        assertFalse(audio.hidden)
        assertTrue(audio.showsMenuAsPrimaryAction)
        assertEquals("Audio", audio.accessibilityLabel)
        val choices = assertNotNull(audio.menu).children.map { it as UIAction }
        assertEquals(listOf("en", "fr"), choices.map { it.title })
        assertEquals(listOf(UIMenuElementState.UIMenuElementStateOff, UIMenuElementState.UIMenuElementStateOn), choices.map { it.state })
        // An item with no subtitles and no variants has no button for either.
        assertTrue(overlay.menuButtons.getValue(PlayerControlsMenu.Subtitles).hidden)
        assertTrue(overlay.menuButtons.getValue(PlayerControlsMenu.Quality).hidden)
        assertFalse(overlay.menuButtons.getValue(PlayerControlsMenu.Speed).hidden)

        overlay.model.select(PlayerControlsMenu.Audio, 0)
        assertEquals("select Audio stream1", player.commands.last())
        overlay.close()
    }

    @Test
    fun everyIconBecomesAPathInsideItsGrid() {
        val icons = listOf(
            ControlIconShapes.Play, ControlIconShapes.Pause, ControlIconShapes.Previous, ControlIconShapes.Next,
            ControlIconShapes.Volume, ControlIconShapes.Muted, ControlIconShapes.Audio, ControlIconShapes.Subtitles,
            ControlIconShapes.Quality, ControlIconShapes.Speed, ControlIconShapes.FullScreen, ControlIconShapes.PictureInPicture,
        )
        icons.forEach { icon ->
            icon.forEach { outline ->
                outline.toBezierPath().bounds.useContents {
                    assertTrue(origin.x >= -0.01 && origin.y >= -0.01 && origin.x + size.width <= 24.01 && origin.y + size.height <= 24.01)
                }
            }
            assertEquals(24.0, iconImage(icon).size.useContents { width })
        }
        // The dial of the speed icon is the top half of its circle, not the bottom.
        ControlIconShapes.Speed.first().toBezierPath().bounds.useContents {
            assertTrue(origin.y < 10.0 && origin.y + size.height <= 17.01, "dial from ${origin.y} to ${origin.y + size.height}")
        }
    }
}
