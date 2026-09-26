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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.awt.Canvas
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
