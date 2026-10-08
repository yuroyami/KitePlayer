package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.awt.Canvas
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The painted controls of the desktop view, against a scripted player (#469).
 *
 * The canvas here has no peer and no window: the mouse and key events are built by hand and sent
 * through the component, which is how AWT delivers real ones, and painting goes into an image.
 */
class AwtPlayerControlsTest {

    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val canvas = Canvas().apply { setSize(640, 360) }
    private var repaints = 0

    private fun controls(player: ScriptedControlsPlayer): AwtPlayerControls {
        // The timeout never fires here; PlayerControlsModelTest owns it.
        val model = PlayerControlsModel(player, unconfined, PlayerControlsStrings.Default, 1.hours, DefaultSpeeds, unconfined)
        return AwtPlayerControls(canvas, model, unconfined) { repaints++ }
    }

    private fun mouse(id: Int, x: Int, y: Int) {
        canvas.dispatchEvent(MouseEvent(canvas, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1))
    }

    private fun click(at: Rectangle) {
        mouse(MouseEvent.MOUSE_PRESSED, at.centerX.toInt(), at.centerY.toInt())
        mouse(MouseEvent.MOUSE_RELEASED, at.centerX.toInt(), at.centerY.toInt())
    }

    private fun press(code: Int): Boolean {
        val event = KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED)
        canvas.keyListeners.forEach { it.keyPressed(event) }
        return event.isConsumed
    }

    /** What the player was asked, without the subtitle lift every shown bar starts with. */
    private val ScriptedControlsPlayer.commands: List<String> get() = calls.filterNot { it.startsWith("subtitles") }

    private fun AwtPlayerControls.part(control: AwtControl): Rectangle = layout().parts.getValue(control)

    @Test
    fun aClickOnAButtonReachesThePlayer() {
        val items = List(2) { io.github.yuroyami.kiteplayer.MediaItem("item$it.mp4") }
        val player = ScriptedControlsPlayer(playingSnapshot().copy(queue = items, queueIndex = 0, queueOrder = listOf(0, 1)))
        val controls = controls(player)
        click(controls.part(AwtControl.Play))
        click(controls.part(AwtControl.Next))
        click(controls.part(AwtControl.Mute))
        // The first item has nothing before it, so its button does nothing.
        click(controls.part(AwtControl.Previous))
        assertEquals(listOf("pause", "next", "muted true"), player.commands)

        // A press that leaves the button before the release is not a click.
        val play = controls.part(AwtControl.Play)
        mouse(MouseEvent.MOUSE_PRESSED, play.centerX.toInt(), play.centerY.toInt())
        mouse(MouseEvent.MOUSE_RELEASED, 320, 100)
        assertEquals(3, player.commands.size)
        controls.close()
    }

    @Test
    fun aDragOnTheSeekBarScrubsAndOneOnTheVolumeSetsIt() {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 10.seconds))
        val controls = controls(player)
        val bar = controls.part(AwtControl.Seek)
        val inset = AwtControlsLayout.THUMB_RADIUS
        fun at(fraction: Float) = bar.x + inset + (fraction * (bar.width - 2 * inset)).toInt()
        mouse(MouseEvent.MOUSE_PRESSED, at(0.25f), bar.centerY.toInt())
        mouse(MouseEvent.MOUSE_DRAGGED, at(0.5f), bar.centerY.toInt())
        assertEquals(50.seconds, controls.model.state.value.scrubTarget)
        mouse(MouseEvent.MOUSE_RELEASED, at(0.5f), bar.centerY.toInt())
        assertNull(controls.model.state.value.scrubTarget)
        assertEquals(listOf("seek 25s KeyframeThenRefine", "seek 50s KeyframeThenRefine"), player.commands)

        val volume = controls.part(AwtControl.Volume)
        mouse(MouseEvent.MOUSE_PRESSED, volume.x + volume.width / 2, volume.centerY.toInt())
        mouse(MouseEvent.MOUSE_RELEASED, volume.x + volume.width / 2, volume.centerY.toInt())
        // Half the slider is an eighth of the amplitude.
        assertEquals("volume 125", player.calls.last())
        controls.close()
    }

    @Test
    fun aClickOnThePictureShowsAndHidesTheControls() {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val controls = controls(player)
        click(Rectangle(300, 100, 2, 2))
        assertFalse(controls.model.state.value.visible)
        // Hidden controls take no press: a click where a button was only brings them back.
        click(controls.part(AwtControl.Play))
        assertTrue(controls.model.state.value.visible)
        assertEquals(emptyList(), player.commands)
        // A click on the strip the controls stand on, beside the buttons, keeps them up.
        click(Rectangle(320, 360 - 20, 2, 2))
        assertTrue(controls.model.state.value.visible)
        controls.close()
    }

    @Test
    fun theKeysPlaySeekAndChangeTheVolume() {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 30.seconds))
        val controls = controls(player)
        var fullScreen = 0
        assertTrue(press(KeyEvent.VK_SPACE))
        assertTrue(press(KeyEvent.VK_RIGHT))
        assertTrue(press(KeyEvent.VK_LEFT))
        assertTrue(press(KeyEvent.VK_DOWN))
        assertTrue(press(KeyEvent.VK_M))
        // F is the application's key until it gives a full screen action.
        assertFalse(press(KeyEvent.VK_F))
        controls.model.onFullScreen = { fullScreen++ }
        assertTrue(press(KeyEvent.VK_F))
        assertFalse(press(KeyEvent.VK_A))
        assertEquals(
            listOf("pause", "seek 40s KeyframeThenRefine", "seek 20s KeyframeThenRefine", "volume 729", "muted true"),
            player.commands,
        )
        assertEquals(1, fullScreen)

        // The first arrow while the controls are hidden only shows them.
        controls.model.hide()
        assertTrue(press(KeyEvent.VK_RIGHT))
        assertTrue(controls.model.state.value.visible)
        assertEquals(5, player.commands.size)
        controls.close()
    }

    @Test
    fun aMenuButtonOpensItsMenuAtTheButton() {
        val tracks = Tracks(
            all = listOf(TrackInfo(TrackId(1), TrackKind.Audio, "aac"), TrackInfo(TrackId(2), TrackKind.Audio, "aac")),
        )
        val controls = controls(ScriptedControlsPlayer(playingSnapshot().copy(tracks = tracks)))
        val opened = mutableListOf<Triple<PlayerControlsMenu, Int, Int>>()
        controls.openMenu = { menu, x, y -> opened += Triple(menu, x, y) }
        val audio = controls.part(AwtControl.Audio)
        click(audio)
        click(controls.part(AwtControl.Speed))
        assertEquals(PlayerControlsMenu.Audio, opened[0].first)
        assertEquals(audio.x to audio.y, opened[0].second to opened[0].third)
        assertEquals(PlayerControlsMenu.Speed, opened[1].first)
        // An item with no subtitles and no variants has no button for either.
        assertFalse(AwtControl.Subtitles in controls.layout().parts)
        assertFalse(AwtControl.Quality in controls.layout().parts)
        controls.close()
    }

    @Test
    fun paintingDrawsTheControlsOnlyWhileTheyShow() {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 50.seconds, bufferedAhead = 20.seconds))
        val controls = controls(player)
        fun painted(): BufferedImage {
            val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            controls.paint(g, 640, 360)
            g.dispose()
            return image
        }
        val play = controls.part(AwtControl.Play)
        val bar = controls.part(AwtControl.Seek)
        val shown = painted()
        // The pause icon's left bar, the played half of the seek bar, and nothing over the picture.
        assertEquals(0xFFFFFFFF.toInt(), shown.getRGB(play.x + 18 - 5, play.centerY.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), shown.getRGB(bar.x + bar.width / 4, bar.centerY.toInt()))
        assertTrue(shown.getRGB(bar.x + bar.width * 3 / 4, bar.centerY.toInt()) != 0xFFFFFFFF.toInt())
        assertEquals(0, shown.getRGB(320, 100))

        controls.model.hide()
        val hidden = painted()
        assertEquals(0, hidden.getRGB(play.x + 18 - 5, play.centerY.toInt()))
        assertEquals(0, hidden.getRGB(bar.x + bar.width / 4, bar.centerY.toInt()))

        // A frame that goes to a floating window is left alone.
        controls.model.poke()
        controls.paintsHere = false
        assertEquals(0, painted().getRGB(bar.x + bar.width / 4, bar.centerY.toInt()))
        controls.close()
    }

    @Test
    fun aChangeAsksForARepaintAndTheBarKeepsSubtitlesAboveIt() {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val controls = controls(player)
        val before = repaints
        player.progress.value = Progress(position = 5.seconds)
        assertTrue(repaints > before)
        // The bar covers the bottom row, the seek bar and their margins: 76 of 360 pixels.
        assertEquals(76f / 360f, controls.model.barShare)
        assertEquals("subtitles 789", player.calls.last())

        // Hidden controls do not ask for a repaint on every tick.
        controls.model.hide()
        val hidden = repaints
        player.progress.value = Progress(position = 6.seconds)
        assertEquals(hidden, repaints)
        controls.close()
    }

    @Test
    fun closingTakesTheListenersOffTheCanvas() {
        val controls = controls(ScriptedControlsPlayer(PlayerSnapshot()))
        assertEquals(1, canvas.mouseListeners.size)
        controls.close()
        assertEquals(0, canvas.mouseListeners.size)
        assertEquals(0, canvas.mouseMotionListeners.size)
        assertEquals(0, canvas.keyListeners.size)
    }

    @Test
    fun everyIconBecomesAPathInsideItsGrid() {
        val icons = listOf(
            ControlIconShapes.Play, ControlIconShapes.Pause, ControlIconShapes.Previous, ControlIconShapes.Next,
            ControlIconShapes.Volume, ControlIconShapes.Muted, ControlIconShapes.Audio, ControlIconShapes.Subtitles,
            ControlIconShapes.Quality, ControlIconShapes.Speed, ControlIconShapes.FullScreen, ControlIconShapes.PictureInPicture,
        )
        icons.forEach { icon ->
            assertTrue(icon.isNotEmpty())
            icon.forEach { path ->
                val box = path.toPath2D().bounds2D
                assertTrue(box.minX >= 0.0 && box.minY >= 0.0 && box.maxX <= 24.0 && box.maxY <= 24.0, "$box")
            }
        }
        // The dial of the speed icon is the top half of its circle, not the bottom.
        val dial = ControlIconShapes.Speed.first().toPath2D().bounds2D
        assertTrue(dial.minY < 10.0 && dial.maxY <= 17.01, "$dial")
        // The first wave of the volume icon runs from (15, 9) to (15, 15), bulging right.
        val wave = ControlIconShapes.Volume[1].toPath2D().bounds2D
        assertTrue(wave.maxX > 16.5 && wave.minX > 14.0, "$wave")
    }
}
