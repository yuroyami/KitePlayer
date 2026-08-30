package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
 * The pairing itself is out of reach here, and that is a module boundary rather than an oversight:
 * building a renderer needs a real `KitePlayer`, and a real player needs a media backend and an
 * output backend, neither of which this module depends on. Faking that pair to watch one attach
 * call would be a bigger fake than the thing under test. Attach and detach are proved by the
 * binding's own suite, and end to end by the desktop demo driving a real player.
 *
 * Nothing here needs a display, which is the point: these run on a build machine.
 */
class KitePlayerAwtViewTest {

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
}
