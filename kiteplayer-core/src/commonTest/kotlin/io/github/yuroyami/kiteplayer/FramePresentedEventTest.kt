@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A presented frame becomes an application event, but only when asked: sixty events a second is
 * a cost nobody should pay unasked, so [PlayerConfig.frameEvents] gates it and the default
 * swallows the renderer's reports without backpressure.
 */
class FramePresentedEventTest {

    private class ReportingRenderer : VideoRenderer {
        val rendererEvents = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)
        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            frame.close()
            return true
        }
        override fun supportedHardwareSurfaces() = emptySet<io.github.yuroyami.kiteplayer.spi.HwSurfaceKind>()
        override fun supports(format: io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat) = true
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override fun close() = Unit
        override val events: Flow<RendererEvent> = rendererEvents
    }

    @Test
    fun `renderer reports become player events when asked for`() = runTest {
        val renderer = ReportingRenderer()
        val harness = CoreHarness(this, renderer = null, config = PlayerConfig(frameEvents = true))
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.core.play()
        harness.run(200.milliseconds)

        renderer.rendererEvents.emit(RendererEvent.FramePresented(Pts(40_000), atNanos = 1_000_000L, exact = true))
        renderer.rendererEvents.emit(RendererEvent.FramePresented(Pts(80_000), atNanos = 2_000_000L, exact = false))
        renderer.rendererEvents.emit(RendererEvent.FramePresented(Pts(120_000), atNanos = 3_000_000L, exact = true))
        harness.run(200.milliseconds)

        val presented = harness.events.filterIsInstance<PlayerEvent.FramePresented>()
        assertEquals(3, presented.size, "three reports must become three events")
        assertEquals(listOf(40_000L, 80_000L, 120_000L), presented.map { it.pts.micros })
        assertEquals(listOf(true, false, true), presented.map { it.exact })
        harness.close()
    }

    @Test
    fun `off by default and the reports are still consumed`() = runTest {
        val renderer = ReportingRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.core.play()
        harness.run(200.milliseconds)

        repeat(30) { index ->
            renderer.rendererEvents.emit(
                RendererEvent.FramePresented(Pts(index * 40_000L), atNanos = index.toLong(), exact = false),
            )
        }
        harness.run(200.milliseconds)

        assertTrue(
            harness.events.filterIsInstance<PlayerEvent.FramePresented>().isEmpty(),
            "nothing asked for frame events, so none may reach the application",
        )
        harness.close()
    }
}
