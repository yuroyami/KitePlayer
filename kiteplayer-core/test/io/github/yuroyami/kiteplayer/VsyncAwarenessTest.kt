@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The display's refresh interval reaches the schedule. Every renderer used to answer null and the
 * engine discarded [RendererEvent.VsyncChanged] outright, so a 120 Hz phone and a ProMotion Mac
 * were paced no better than a display nobody asked.
 */
class VsyncAwarenessTest {

    private class VsyncRenderer(private var interval: Long?) : VideoRenderer {
        val rendererEvents = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 4)
        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            frame.close()
            return true
        }
        override fun supportedHardwareSurfaces() = emptySet<io.github.yuroyami.kiteplayer.spi.HwSurfaceKind>()
        override fun supports(format: io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat) = true
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override fun vsyncIntervalNanos(): Long? = interval
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override fun close() = Unit
        override val events: Flow<RendererEvent> = rendererEvents
    }

    @Test
    fun `attach seeds the schedule's interval and VsyncChanged moves it`() = runTest {
        val renderer = VsyncRenderer(16_666_666L)
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.core.play()
        harness.run(300.milliseconds)
        assertEquals(
            16_666_666L,
            harness.core.videoScheduleVsyncNanos,
            "attach must hand the renderer's answer to the schedule",
        )

        renderer.rendererEvents.emit(RendererEvent.VsyncChanged(8_333_333L))
        harness.run(200.milliseconds)
        assertEquals(
            8_333_333L,
            harness.core.videoScheduleVsyncNanos,
            "a display change must reach the running schedule without a reopen",
        )
        harness.close()
    }
}
