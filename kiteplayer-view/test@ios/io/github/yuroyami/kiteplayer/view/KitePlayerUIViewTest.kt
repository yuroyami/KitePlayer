@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIWindow
import platform.UIKit.accessibilityHint
import platform.UIKit.isAccessibilityElement
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityValue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Driven against real UIKit objects in the simulator.
 *
 * The view hosts two layers, one for Metal and one for Core Graphics, and only one of them can be
 * the picture. The renderers are fakes because none of what is asked here is about drawing: it is
 * about which layer the view leaves on the glass across a renderer switch, and about what
 * [KitePlayerUIView.hasPicture] answers once a generation that used the other layer has closed.
 */
class KitePlayerUIViewTest {

    private val players = mutableListOf<KitePlayer>()

    @AfterTest
    fun closePlayers() {
        players.forEach { it.close() }
        players.clear()
    }

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = Backends(backend = StubMediaBackend, output = StubOutputBackend)),
    ).also { players += it }

    /** A view already in a window with a player attached, so a generation is live. */
    private fun attachedView(preferMetal: Boolean, renderer: FakeRenderer): KitePlayerUIView {
        val view = KitePlayerUIView()
        view.preferMetal = preferMetal
        view.rendererFactory = ApplePlayerViewRendererFactory { _, _, _ -> renderer }
        UIWindow(frame = CGRectMake(0.0, 0.0, 320.0, 240.0)).addSubview(view)
        view.player = player()
        return view
    }

    @Test
    fun `only the layer the live generation uses is on the glass`() {
        val view = attachedView(preferMetal = true, renderer = FakeRenderer())
        assertFalse(view.metalLayerHidden(), "a Metal generation shows the Metal layer")
        assertTrue(view.videoLayerHidden(), "and hides the one it does not draw into")

        // The switch the row is about: a CG generation must not sit under stale Metal content.
        view.preferMetal = false
        view.rendererFactory = ApplePlayerViewRendererFactory { _, _, _ -> FakeRenderer() }
        assertTrue(view.metalLayerHidden(), "the stale Metal drawable must leave the glass")
        assertFalse(view.videoLayerHidden(), "the CG layer is the picture now")
    }

    @Test
    fun `hasPicture answers about the visible layer rather than the cumulative count`() {
        val metalGeneration = FakeRenderer(presentedFrames = 12L)
        val view = attachedView(preferMetal = true, renderer = metalGeneration)
        assertTrue(view.hasPicture, "the Metal layer has been presented into")

        // The CG generation has drawn nothing, and the Metal frames belong to a hidden layer.
        view.preferMetal = false
        view.rendererFactory = ApplePlayerViewRendererFactory { _, _, _ -> FakeRenderer() }
        assertEquals(12L, view.presentedFrames, "the cumulative ledger still carries every frame")
        assertFalse(view.hasPicture, "but the visible layer has never been drawn into")
    }

    @Test
    fun `a fresh view claims no picture and shows neither layer`() {
        val view = KitePlayerUIView()
        assertFalse(view.hasPicture)
        assertTrue(view.metalLayerHidden())
        assertTrue(view.videoLayerHidden())
    }

    @Test
    fun `VoiceOver says the label and the state text the application passed`() {
        val view = KitePlayerUIView()
        assertEquals(DEFAULT_VIDEO_ACCESSIBILITY_LABEL, view.accessibilityLabel)
        view.updateAccessibilityState()
        assertEquals("No media", view.accessibilityValue)

        view.accessibilityVideoLabel = "Vídeo"
        view.accessibilityStateFormat = { status, _, _ ->
            if (status == io.github.yuroyami.kiteplayer.PlaybackStatus.Idle) "Sin contenido" else "Otro"
        }
        assertEquals("Vídeo", view.accessibilityLabel)
        assertEquals("Sin contenido", view.accessibilityValue)
        view.release()
    }

    // The controls are opt-in, so a screen that never asks for them is as it was (#469).
    @Test
    fun theViewHasNoControlsUntilItIsAsked() {
        val view = KitePlayerUIView()
        view.player = player()
        assertFalse(view.showsControls)
        assertNull(view.controls)
        assertEquals(0, view.subviews.size)
        assertTrue(view.isAccessibilityElement)
        view.release()
    }

    @Test
    fun turningTheControlsOnAddsThemAndTurningThemOffTakesThemAway() {
        val view = KitePlayerUIView()
        view.setFrame(CGRectMake(0.0, 0.0, 320.0, 240.0))
        // No player yet, so there is nothing to control.
        view.showsControls = true
        assertNull(view.controls)
        assertEquals(0, view.subviews.size)

        view.player = player()
        val model = assertNotNull(view.controls)
        assertTrue(model.state.value.visible)
        val overlay = assertNotNull(view.controlsOverlay)
        assertEquals(listOf<Any?>(overlay), view.subviews)
        view.layoutIfNeeded()
        assertEquals(320.0, overlay.frame.useContents { size.width })
        assertEquals(240.0, overlay.frame.useContents { size.height })
        // VoiceOver does not look inside an element, so the picture inside the controls is the video now.
        assertFalse(view.isAccessibilityElement)
        assertEquals(DEFAULT_VIDEO_ACCESSIBILITY_LABEL, overlay.picture.accessibilityLabel)
        assertEquals("No media", overlay.picture.accessibilityValue)
        assertEquals("Hide controls", overlay.picture.accessibilityHint)
        assertEquals("Play", overlay.playButton.accessibilityLabel)

        view.showsControls = false
        assertNull(view.controls)
        assertEquals(0, view.subviews.size)
        assertTrue(view.isAccessibilityElement)

        // Releasing the view drops them with the player.
        view.showsControls = true
        assertNotNull(view.controls)
        view.release()
        assertNull(view.controls)
        assertEquals(0, view.subviews.size)
    }

    @Test
    fun theWordsAndTheButtonsOfTheApplicationReachTheControls() {
        val view = KitePlayerUIView()
        view.setFrame(CGRectMake(0.0, 0.0, 320.0, 240.0))
        view.controlsStrings = PlayerControlsStrings(play = "Lecture", fullScreen = "Plein écran")
        var asked = 0
        view.onFullScreen = { asked++ }
        view.player = player()
        view.showsControls = true
        val overlay = assertNotNull(view.controlsOverlay)
        assertEquals("Lecture", overlay.playButton.accessibilityLabel)
        assertEquals("Plein écran", overlay.fullScreenButton.accessibilityLabel)
        assertFalse(overlay.fullScreenButton.hidden)
        assertTrue(overlay.pictureInPictureButton.hidden)
        overlay.fullScreenButton.sendActionsForControlEvents(UIControlEventTouchUpInside)
        assertEquals(1, asked)

        // Words set later reach the controls already on screen.
        view.controlsStrings = PlayerControlsStrings(play = "Wiedergabe")
        assertEquals("Wiedergabe", overlay.playButton.accessibilityLabel)
        view.release()
    }

    /** The application no longer has to tell the view: it follows the status while in a window (#307). */
    @Test
    fun `VoiceOver hears a status change without the application asking`() {
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, 320.0, 240.0))
        val view = KitePlayerUIView()
        view.rendererFactory = ApplePlayerViewRendererFactory { _, _, _ -> FakeRenderer() }
        window.addSubview(view)
        val player = player()
        view.player = player
        assertEquals("No media", view.accessibilityValue)

        // The backend refuses every open, so the status moves on to Failed.
        runBlocking { runCatching { player.open(MediaItem("refused.mp4")) } }
        // The view follows the player on the main queue, which runs only while the run loop turns.
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (view.accessibilityValue != "Failed" && deadline.hasNotPassedNow()) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.02))
        }
        assertEquals("Failed", view.accessibilityValue)
        view.release()
        window.hidden = true
    }
}

/** The view keeps its layers private, so the test reads them through its own sublayer list. */
private fun KitePlayerUIView.metalLayerHidden(): Boolean =
    (layer.sublayers?.get(1) as platform.QuartzCore.CALayer).hidden

private fun KitePlayerUIView.videoLayerHidden(): Boolean =
    (layer.sublayers?.get(0) as platform.QuartzCore.CALayer).hidden

private class FakeRenderer(
    override val presentedFrames: Long = 0L,
    override val supersededFrames: Long = 0L,
    override val failedFrames: Long = 0L,
) : PlayerViewRenderer {
    override val events: Flow<RendererEvent> = emptyFlow()
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
    override fun supports(format: PlayerPixelFormat): Boolean = true
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        frame.close()
        return false
    }
    override fun vsyncIntervalNanos(): Long? = null
    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit
    override suspend fun setOverlay(overlay: SubtitleOverlay?): Unit = Unit
    override fun close(): Unit = Unit
}

/** Never opened: these tests attach and detach a renderer, they never play anything. */
private object StubMediaBackend : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("no media in this test")
}

private object StubOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "stub"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
