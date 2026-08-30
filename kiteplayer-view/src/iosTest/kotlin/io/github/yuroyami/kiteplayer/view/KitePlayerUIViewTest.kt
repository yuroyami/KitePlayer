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
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIWindow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    override val videoRenderer: VideoRendererFactory? = null
}
