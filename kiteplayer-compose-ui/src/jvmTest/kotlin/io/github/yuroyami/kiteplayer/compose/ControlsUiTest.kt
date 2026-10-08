package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The default controls as a screen reader, a keyboard and a finger meet them (#469). */
@OptIn(ExperimentalTestApi::class)
class ControlsUiTest {

    private val everything = PlayerSnapshot(
        status = PlaybackStatus.Paused,
        duration = 100.seconds,
        seekable = true,
        queue = List(3) { MediaItem("item$it.mp4") },
        queueIndex = 1,
        tracks = Tracks(
            all = listOf(
                TrackInfo(TrackId(1), TrackKind.Audio, "aac", language = "ja"),
                TrackInfo(TrackId(2), TrackKind.Audio, "aac", language = "en"),
                TrackInfo(TrackId(3), TrackKind.Subtitle, "ass", language = "en"),
            ),
            selectedAudio = TrackId(1),
            variants = listOf(StreamVariant(index = 0, bitrate = 800_000, height = 360), StreamVariant(index = 1, bitrate = 3_000_000, height = 720)),
            selectedVariant = 0,
        ),
    )

    private val buttons = listOf(
        "Previous", "Play", "Next", "Mute", "Audio", "Subtitles", "Quality", "Speed", "Picture in picture", "Full screen",
    )

    private fun ComposeUiTest.showControls(
        target: ScriptedTarget,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        visibility: ControlsVisibility? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                val source = remember { target.source() }
                val shown = visibility ?: remember { ControlsVisibility() }
                ControlsTimeout(shown, source.snapshot.playRequested, 3.seconds)
                Controls(source, Modifier.size(640.dp, 360.dp), shown, DefaultControlsStyle, DefaultControlsLabels, onFullScreen = {}, onPictureInPicture = {})
            }
        }
    }

    /** What the controls asked of the player, leaving out the subtitle lift that showing them makes. */
    private fun ScriptedTarget.commands(): List<String> = callsSoFar().filterNot { it.startsWith("subtitles") }

    /** A tap on the picture away from the buttons in its middle. */
    private fun SemanticsNodeInteraction.tapPicture() {
        performTouchInput { click(Offset(width * 0.1f, height * 0.1f)) }
    }

    private fun SemanticsNodeInteraction.role(): Role? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

    @Test
    fun `every control has a role and a label`() = runComposeUiTest {
        showControls(ScriptedTarget(everything, Progress(position = 10.seconds)))
        buttons.forEach { label -> assertEquals(Role.Button, onNodeWithContentDescription(label).role(), label) }
        listOf("Seek", "Volume").forEach { label ->
            onNodeWithContentDescription(label)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        }
        onNodeWithContentDescription("Seek")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:10 of 1:40"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(10f, 0f..100f)))
        onNodeWithContentDescription("Volume").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100 percent"))
        onNodeWithContentDescription("Hide controls").assertExists()
    }

    @Test
    fun `setting the seek bar's progress seeks the player`() = runComposeUiTest {
        val target = ScriptedTarget(everything)
        showControls(target)
        onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        onNodeWithContentDescription("Volume").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(listOf("seek 30s KeyframeThenRefine", "volume 0.125"), target.commands())
    }

    @Test
    fun `tab reaches every control`() = runComposeUiTest {
        showControls(ScriptedTarget(everything))
        onNodeWithContentDescription("Play").requestFocus().assert(isFocused())
        val reached = mutableSetOf<String>()
        repeat(20) {
            onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
            onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.let { reached += it }
        }
        assertTrue(reached.containsAll(buttons + "Seek" + "Volume"), "Tab reached only $reached")
    }

    @Test
    fun `arrow keys move between controls and step the seek bar`() = runComposeUiTest {
        val target = ScriptedTarget(everything, Progress(position = 40.seconds))
        showControls(target)
        onNodeWithContentDescription("Play").requestFocus().assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithContentDescription("Next").assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithContentDescription("Play").assert(isFocused())

        onNodeWithContentDescription("Seek").requestFocus().assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf("seek 50s KeyframeThenRefine"), target.commands())
        // Space plays from whatever control has focus, rather than pressing that control.
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithContentDescription("Next").requestFocus().assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("seek 50s KeyframeThenRefine", "play", "play"), target.commands())
    }

    @Test
    fun `a tap and the timeout hide the controls alike`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val target = ScriptedTarget(everything.copy(status = PlaybackStatus.Playing, playRequested = true))
        val visibility = ControlsVisibility()
        showControls(target, visibility = visibility)
        mainClock.advanceTimeBy(100)
        onNodeWithContentDescription("Pause").assertExists()
        assertTrue(target.snapshot.value.subtitlePosition < 1f, "the controls lifted no subtitles")

        mainClock.advanceTimeBy(3_100)
        val timedOut = hiddenState(target)

        onNodeWithContentDescription("Show controls").tapPicture()
        mainClock.advanceTimeBy(100)
        onNodeWithContentDescription("Pause").assertExists()
        onNodeWithContentDescription("Hide controls").tapPicture()
        mainClock.advanceTimeBy(100)
        assertEquals(timedOut, hiddenState(target))
    }

    /** What a hidden player shows: the picture's label, no buttons, and subtitles where they were. */
    private fun ComposeUiTest.hiddenState(target: ScriptedTarget): String {
        onNodeWithContentDescription("Pause").assertDoesNotExist()
        onNodeWithContentDescription("Seek").assertDoesNotExist()
        onNodeWithContentDescription("Show controls").assertExists()
        return "subtitles at ${target.snapshot.value.subtitlePosition}"
    }

    @Test
    fun `a paused player keeps its controls`() = runComposeUiTest {
        mainClock.autoAdvance = false
        showControls(ScriptedTarget(everything))
        mainClock.advanceTimeBy(10_000)
        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `the transport row and the seek bar stay left to right in a right to left layout`() = runComposeUiTest {
        val target = ScriptedTarget(everything)
        showControls(target, LayoutDirection.Rtl)
        val previous = onNodeWithContentDescription("Previous").fetchSemanticsNode().boundsInRoot
        val play = onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        val next = onNodeWithContentDescription("Next").fetchSemanticsNode().boundsInRoot
        assertTrue(previous.right <= play.left && play.right <= next.left, "previous $previous, play $play, next $next")

        // The left of the bar is the start of the item in either direction.
        onNodeWithContentDescription("Seek").performTouchInput { click(Offset(centerLeft.x + 7.dp.toPx(), centerLeft.y)) }
        assertEquals("seek 0s KeyframeThenRefine", target.callsSoFar().first { it.startsWith("seek") })

        // The other buttons follow the layout: the volume starts on the right.
        val mute = onNodeWithContentDescription("Mute").fetchSemanticsNode().boundsInRoot
        val fullScreen = onNodeWithContentDescription("Full screen").fetchSemanticsNode().boundsInRoot
        assertTrue(fullScreen.right <= mute.left, "mute $mute, full screen $fullScreen")
    }

    @Test
    fun `a menu lists every choice and closes on a pick`() = runComposeUiTest {
        val target = ScriptedTarget(everything)
        showControls(target)
        onNodeWithContentDescription("Audio").performClick()
        onNodeWithText("ja").assertIsSelected()
        onNodeWithText("en").performClick()
        onNodeWithText("ja").assertDoesNotExist()
        assertEquals(listOf("select Audio stream2"), target.commands())

        onNodeWithContentDescription("Quality").performClick()
        onNodeWithText("Automatic").assertIsSelected()
        onNodeWithText("720p").assertExists()
        onNodeWithContentDescription("Quality").performClick()
        onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    fun `escape closes a menu and gives focus back to its button`() = runComposeUiTest {
        showControls(ScriptedTarget(everything))
        onNodeWithContentDescription("Speed").requestFocus().assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        onNodeWithText("Normal").assert(isFocused())
        onNode(isFocused()).performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Normal").assertDoesNotExist()
        onNodeWithContentDescription("Speed").assert(isFocused())
    }

    @Test
    fun `subtitles move above the controls and back`() = runComposeUiTest {
        val target = ScriptedTarget(everything)
        val visibility = ControlsVisibility()
        showControls(target, visibility = visibility)
        waitForIdle()
        val lifted = target.snapshot.value.subtitlePosition
        assertTrue(lifted < 1f && lifted > 0.5f, "lifted to $lifted")
        runOnIdle { visibility.hide() }
        waitForIdle()
        assertEquals(1f, target.snapshot.value.subtitlePosition)
    }

    @Test
    fun `a sideways finger over the picture scrubs and a mouse does not`() = runComposeUiTest {
        val target = ScriptedTarget(everything, Progress(position = 10.seconds))
        showControls(target)
        onNodeWithContentDescription("Hide controls").performTouchInput {
            swipe(Offset(width * 0.25f, height * 0.25f), Offset(width * 0.75f, height * 0.25f), durationMillis = 300)
        }
        val seeks = target.callsSoFar().filter { it.startsWith("seek") }
        // Half the width of a 100 second item, from 10 seconds.
        assertEquals("seek 1m KeyframeThenRefine", seeks.last(), seeks.toString())
        // A drag is not a tap, so the controls stay.
        onNodeWithContentDescription("Hide controls").performMouseInput {
            press()
            moveBy(Offset(200f, 0f))
            release()
        }
        assertEquals(seeks, target.callsSoFar().filter { it.startsWith("seek") })
    }
}
