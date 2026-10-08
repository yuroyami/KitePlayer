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
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.image.BufferedImage
import javax.accessibility.AccessibleRole
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What this reaches, and what it deliberately does not.
 *
 * The attach state machine is `PlayerViewBinding`, and `PlayerViewBindingTest` already drives
 * every ordering rule through it with scripted fakes. Testing those same rules again through a
 * component would add no evidence.
 *
 * What is genuinely the DESKTOP view's own logic is the bookkeeping across renderer generations,
 * which is why it lives in `AwtViewLedger` where it can be driven directly. A generation dies
 * whenever the factory changes or the pairing drops, taking its counters with it, and the view
 * must answer for the whole of its own life rather than for the current generation.
 *
 * Attach and detach are proved by the binding's own suite, and end to end by the desktop demo
 * driving a real player. The floating window tests below do build a real `KitePlayer`, over stub
 * backends that are never opened, because the question there is which canvas a LIVE renderer is
 * told to paint into, and only a paired view has a live renderer.
 *
 * Nothing here needs a display, which is the point: these run on a build machine.
 */
class KitePlayerAwtViewTest {

    private val players = mutableListOf<KitePlayer>()

    @AfterTest
    fun closePlayers() {
        players.forEach { it.close() }
        players.clear()
    }

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = Backends(backend = StubMediaBackend, output = StubOutputBackend)),
    ).also { players += it }

    /** Remembers every canvas the view hands it, in order. */
    private class CanvasRecorder : AwtPlayerViewRenderer {
        val canvases = mutableListOf<Canvas?>()
        val current: Canvas? get() = canvases.lastOrNull()
        override fun setCanvas(canvas: Canvas?) {
            canvases += canvas
        }
        override val presentedFrames: Long = 0
        override val supersededFrames: Long = 0
        override val failedFrames: Long = 0
        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            frame.close()
            return false
        }
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override fun supports(format: PlayerPixelFormat): Boolean = true
        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override val events: Flow<RendererEvent> = emptyFlow()
        override fun close() = Unit
    }

    /** A paired view with its peer, so a renderer is live and painting into the view. */
    private fun pairedView(renderer: CanvasRecorder): KitePlayerAwtView {
        val view = KitePlayerAwtView()
        view.rendererFactory = AwtPlayerViewRendererFactory { renderer }
        view.player = player()
        view.canvasAvailable()
        return view
    }

    private class Counted(
        override val presentedFrames: Long,
        override val supersededFrames: Long,
        override val failedFrames: Long,
    ) : PlayerViewRenderer {
        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = false
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override fun supports(format: PlayerPixelFormat): Boolean = true
        override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override val events: Flow<RendererEvent> = emptyFlow()
        override fun close() = Unit
    }

    @Test
    fun `a live generation's counters are reported without being double counted`() {
        val ledger = AwtViewLedger()
        val live = Counted(presentedFrames = 5, supersededFrames = 2, failedFrames = 1)
        assertEquals(5L, ledger.presented(live))
        assertEquals(2L, ledger.superseded(live))
        assertEquals(1L, ledger.failed(live))
    }

    @Test
    fun `a generation that ends leaves its counts behind rather than taking them`() {
        val ledger = AwtViewLedger()
        ledger.absorb(Counted(presentedFrames = 5, supersededFrames = 2, failedFrames = 1))
        // No live renderer now, and the totals must still cover the view's whole life.
        assertEquals(5L, ledger.presented(null))
        assertEquals(2L, ledger.superseded(null))
        assertEquals(1L, ledger.failed(null))
    }

    @Test
    fun `counts accumulate across several generations and add the live one on top`() {
        val ledger = AwtViewLedger()
        ledger.absorb(Counted(5, 2, 1))
        ledger.absorb(Counted(3, 1, 0))
        val live = Counted(4, 0, 2)
        assertEquals(12L, ledger.presented(live))
        assertEquals(3L, ledger.superseded(live))
        assertEquals(3L, ledger.failed(live))
    }

    @Test
    fun `geometry is forgotten when the generation ends, because a view with no renderer has none`() {
        val ledger = AwtViewLedger()
        ledger.geometry(1.777f, 90)
        assertEquals(90, ledger.rotationDegrees)
        assertEquals(1.777f, ledger.displayAspect)
        ledger.absorb(Counted(0, 0, 0))
        assertEquals(0, ledger.rotationDegrees)
        assertEquals(0f, ledger.displayAspect)
    }

    @Test
    fun `a view with no factory is deliberately headless rather than broken`() {
        val view = KitePlayerAwtView()
        // AWT delivers these whether or not anyone installed a renderer, so they must be safe.
        view.canvasAvailable()
        view.canvasLost()
        assertNull(view.binding.activeRenderer)
        assertEquals(0L, view.presentedFrames)
    }

    @Test
    fun aViewWithNoFactoryOfItsOwnUsesTheDefault() {
        val renderer = CanvasRecorder()
        val before = PlayerViewDefaults.rendererFactory
        PlayerViewDefaults.rendererFactory = AwtPlayerViewRendererFactory { renderer }
        try {
            val view = KitePlayerAwtView()
            view.player = player()
            view.canvasAvailable()
            assertSame(renderer, view.binding.activeRenderer, "the default factory built the renderer")
            assertSame(view, renderer.current, "the default renderer paints into the view")
        } finally {
            PlayerViewDefaults.rendererFactory = before
        }
    }

    @Test
    fun aViewsOwnFactoryWinsOverTheDefault() {
        val own = CanvasRecorder()
        val before = PlayerViewDefaults.rendererFactory
        PlayerViewDefaults.rendererFactory = AwtPlayerViewRendererFactory { CanvasRecorder() }
        try {
            val view = KitePlayerAwtView()
            view.rendererFactory = AwtPlayerViewRendererFactory { own }
            view.player = player()
            view.canvasAvailable()
            assertSame(own, view.binding.activeRenderer)
        } finally {
            PlayerViewDefaults.rendererFactory = before
        }
    }

    @Test
    fun `a fresh view reports no geometry`() {
        val view = KitePlayerAwtView()
        assertEquals(0f, view.videoDisplayAspect)
        assertEquals(0, view.videoRotation)
    }

    @Test
    fun openingTheFloatingWindowMovesTheRendererAndClosingItHandsItBack() {
        val renderer = CanvasRecorder()
        val view = pairedView(renderer)
        assertSame(view, renderer.current, "a view with its peer paints into itself")

        val floating = Canvas()
        view.floatingCanvas = floating
        assertSame(floating, renderer.current, "the open window's canvas takes the picture")

        view.floatingCanvas = null
        assertSame(view, renderer.current, "closing the window hands the picture back to the view")
    }

    @Test
    fun whileTheWindowIsOpenThePeerEventsOfTheViewLeaveTheRendererThere() {
        val renderer = CanvasRecorder()
        val view = pairedView(renderer)
        val floating = Canvas()
        view.floatingCanvas = floating
        val handedBefore = renderer.canvases.size

        // The app's own window hides and comes back while the floating window stays open.
        view.canvasLost()
        view.canvasAvailable()

        assertTrue(
            renderer.canvases.drop(handedBefore).all { it === floating },
            "the view's peer events pulled the renderer off the window: ${renderer.canvases}",
        )
        assertSame(floating, renderer.current)
    }

    @Test
    fun aRendererRebuiltWhileTheWindowIsOpenStartsOnTheWindow() {
        val view = pairedView(CanvasRecorder())
        val floating = Canvas()
        view.floatingCanvas = floating

        val rebuilt = CanvasRecorder()
        view.rendererFactory = AwtPlayerViewRendererFactory { rebuilt }

        assertSame(rebuilt, view.binding.activeRenderer)
        assertEquals(listOf<Canvas?>(floating), rebuilt.canvases, "a new renderer must start on the open window")
    }

    @Test
    fun closingTheWindowWhenTheViewHasNoPeerPaintsNowhere() {
        val renderer = CanvasRecorder()
        val view = pairedView(renderer)
        view.floatingCanvas = Canvas()
        view.canvasLost()

        view.floatingCanvas = null

        assertNull(renderer.current, "a view without a peer cannot take the picture back")
    }

    @Test
    fun aScreenReaderReadsTheLabelAsTheNameAndTheStateAsTheDescription() {
        val view = KitePlayerAwtView()
        assertEquals(DEFAULT_VIDEO_ACCESSIBILITY_LABEL, view.accessibleContext.accessibleName)
        assertEquals("No media", view.accessibleContext.accessibleDescription)

        view.accessibilityVideoLabel = "Vídeo"
        view.accessibilityStateFormat = { status, _, _ -> if (status == PlaybackStatus.Idle) "Sin contenido" else "Otro" }
        assertEquals("Vídeo", view.accessibleContext.accessibleName)
        assertEquals("Sin contenido", view.accessibleContext.accessibleDescription)
    }

    /** The application does not have to tell the view: it follows the status while it has its peer (#310). */
    @Test
    fun theDescriptionFollowsThePlayersStatusWhileTheViewHasItsPeer() {
        val view = KitePlayerAwtView()
        view.canvasAvailable()
        val player = player()
        view.player = player
        assertEquals("No media", description(view))

        // The backend refuses every open, so the status moves on to Failed.
        runBlocking { runCatching { player.open(MediaItem("refused.mp4")) } }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (description(view) != "Failed" && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("Failed", description(view))
        view.release()
    }

    /** A renderer that can draw the view's controls into its frames, and remembers what it was given. */
    private class ControlsRecorder(inner: CanvasRecorder = CanvasRecorder()) : AwtPlayerViewRenderer by inner, AwtControlsCanvas {
        val painters = mutableListOf<AwtControlsPainter?>()
        var repaints = 0
        override fun setControlsPainter(painter: AwtControlsPainter?) {
            painters += painter
        }
        override fun repaintControls() {
            repaints++
        }
    }

    // The controls are opt-in, so a screen that never asks for them is as it was (#469).
    @Test
    fun theViewHasNoControlsUntilItIsAsked() {
        val view = KitePlayerAwtView()
        view.player = player()
        assertFalse(view.showsControls)
        assertNull(view.controls)
        assertEquals(0, view.mouseListeners.size)
        assertEquals(0, view.keyListeners.size)
        assertEquals(0, view.accessibleContext.accessibleChildrenCount)
        view.release()
    }

    @Test
    fun turningTheControlsOnAddsThemAndTurningThemOffTakesThemAway() {
        val view = KitePlayerAwtView()
        view.setSize(640, 360)
        // No player yet, so there is nothing to control.
        view.showsControls = true
        assertNull(view.controls)

        view.player = player()
        val model = assertNotNull(view.controls)
        assertTrue(model.state.value.visible)
        assertEquals(1, view.mouseListeners.size)
        assertEquals(1, view.mouseMotionListeners.size)
        assertEquals(1, view.keyListeners.size)

        // A screen reader reaches each painted part by name. Nothing is open, so there is no seek bar.
        val context = view.accessibleContext
        val parts = List(context.accessibleChildrenCount) { context.getAccessibleChild(it).accessibleContext }
        assertEquals(listOf("Play", "Mute", "Volume", "Speed"), parts.map { it.accessibleName })
        assertEquals(
            listOf(AccessibleRole.PUSH_BUTTON, AccessibleRole.PUSH_BUTTON, AccessibleRole.SLIDER, AccessibleRole.PUSH_BUTTON),
            parts.map { it.accessibleRole },
        )
        assertEquals("100 percent", parts[2].accessibleDescription)
        assertEquals(100, parts[2].accessibleValue.currentAccessibleValue)
        assertSame(context.getAccessibleChild(0), context.getAccessibleChild(0))

        view.showsControls = false
        assertNull(view.controls)
        assertEquals(0, view.mouseListeners.size)
        assertEquals(0, view.keyListeners.size)
        assertEquals(0, context.accessibleChildrenCount)

        // Releasing the view drops them with the player.
        view.showsControls = true
        assertNotNull(view.controls)
        view.release()
        assertNull(view.controls)
        assertEquals(0, view.mouseListeners.size)
    }

    @Test
    fun theWordsAndTheButtonsOfTheApplicationReachTheControls() {
        val view = KitePlayerAwtView()
        view.setSize(640, 360)
        view.controlsStrings = PlayerControlsStrings(play = "Lecture", fullScreen = "Plein écran")
        var asked = 0
        view.onFullScreen = { asked++ }
        view.player = player()
        view.showsControls = true
        val context = view.accessibleContext
        val names = List(context.accessibleChildrenCount) { context.getAccessibleChild(it).accessibleContext.accessibleName }
        assertEquals(listOf("Lecture", "Mute", "Volume", "Speed", "Plein écran"), names)
        context.getAccessibleChild(4).accessibleContext.accessibleAction.doAccessibleAction(0)
        assertEquals(1, asked)

        // Words set later reach the controls already on screen.
        view.controlsStrings = PlayerControlsStrings(play = "Wiedergabe")
        assertEquals("Wiedergabe", context.getAccessibleChild(0).accessibleContext.accessibleName)
        view.release()
    }

    @Test
    fun aRendererThatCanDrawTheControlsIsGivenThemForEveryGeneration() {
        val first = ControlsRecorder()
        val view = KitePlayerAwtView()
        view.rendererFactory = AwtPlayerViewRendererFactory { first }
        view.player = player()
        // Off: the renderer is told there is nothing to draw.
        assertTrue(first.painters.all { it == null })

        view.showsControls = true
        val painter = assertNotNull(first.painters.last())
        assertSame<Any?>(view.awtControls, painter)
        assertTrue(first.repaints > 0)

        // A new generation is handed the same controls before it paints.
        val second = ControlsRecorder()
        view.rendererFactory = AwtPlayerViewRendererFactory { second }
        assertSame(painter, second.painters.last())

        view.showsControls = false
        assertNull(second.painters.last())
        view.release()
    }

    @Test
    fun aViewWhoseRendererCannotDrawTheControlsPaintsThemItself() {
        val view = KitePlayerAwtView()
        view.setSize(640, 360)
        view.player = player()
        view.showsControls = true
        val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        view.paint(g)
        g.dispose()
        // The play icon, at the left of the bottom row, is white.
        assertEquals(0xFFFFFFFF.toInt(), image.getRGB(8 + 18, 360 - 8 - 18))

        // With the controls off the same paint leaves the canvas as AWT cleared it.
        view.showsControls = false
        val plain = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
        val p = plain.createGraphics()
        view.paint(p)
        p.dispose()
        assertTrue(plain.getRGB(8 + 18, 360 - 8 - 18) != 0xFFFFFFFF.toInt())
        view.release()
    }

    @Test
    fun theFloatingWindowDoesNotGetTheControls() {
        val view = pairedView(CanvasRecorder())
        view.showsControls = true
        val painted = assertNotNull(view.awtControls)
        assertTrue(painted.paintsHere)
        view.floatingCanvas = Canvas()
        assertFalse(painted.paintsHere)
        view.floatingCanvas = null
        assertTrue(painted.paintsHere)
        view.release()
    }

    /** Read on the event dispatch thread, which is where the view refreshes it. */
    private fun description(view: KitePlayerAwtView): String? {
        var text: String? = null
        EventQueue.invokeAndWait { text = view.accessibleContext.accessibleDescription }
        return text
    }
}

/** Never opened: these tests pair and unpair a renderer, they never play anything. */
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
