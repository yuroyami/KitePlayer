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
import kotlin.time.Duration.Companion.seconds

/**
 * S4.d: the dump tells a scripted session's story, the warning history is bounded and replayed
 * to late readers, renderer events become typed warnings, and the log seam stays silent until a
 * sink is installed.
 */
class DiagnosticsTest {

    @Test
    fun `the dump carries the session's story`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)

        val dump = harness.core.diagnosticsDump()
        assertTrue(dump.startsWith("KitePlayer diagnostics"), dump.lineSequence().first())
        assertTrue("status" in dump && "Playing" in dump, "the dump must carry the live status")
        assertTrue("scripted://media" in dump, "the dump must name the media")
        assertTrue("config" in dump && "hardwareDecode" in dump, "the dump must carry the resolved config")
        assertTrue("tracks" in dump && "scripted-video" in dump, "the dump must list the tracks")
        assertTrue("stats" in dump && "decoded=" in dump, "the dump must carry the stats snapshot")
        assertTrue("kd artifacts" in dump, "the dump must carry the KD section (KD-7)")
        assertTrue("warnings (" in dump, "the dump must carry the warning history section")
        harness.close()
    }

    @Test
    fun `renderer events become typed warnings in history and on the flow`() = runTest {
        val rendererEvents = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 4)
        val emitting = object : VideoRenderer {
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
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(emitting)
        harness.open()
        harness.run(100.milliseconds)

        rendererEvents.emit(RendererEvent.SurfaceLost("the layer left the window"))
        rendererEvents.emit(RendererEvent.Failed("the device rejected the pipeline"))
        harness.run(200.milliseconds)

        val warnings = harness.core.warningHistory().map { it.warning }
        assertTrue(
            warnings.any { it is PlaybackWarning.NoRenderSurface },
            "SurfaceLost must land in the history, got $warnings",
        )
        assertTrue(
            warnings.any { it is PlaybackWarning.RendererFailed },
            "Failed must land in the history, got $warnings",
        )
        assertTrue(
            harness.events.filterIsInstance<PlayerEvent.Warning>()
                .any { it.warning is PlaybackWarning.RendererFailed },
            "the warning must also reach the live event flow",
        )
        val dump = harness.core.diagnosticsDump()
        assertTrue("the renderer failed" in dump, "the dump must print the renderer failure")
        harness.close()
    }

    @Test
    fun `the history is bounded and keeps the newest`() = runTest {
        val rendererEvents = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 256)
        val emitting = object : VideoRenderer {
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
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(emitting)
        harness.open()
        harness.run(100.milliseconds)
        repeat(80) { index -> rendererEvents.emit(RendererEvent.Failed("failure $index")) }
        harness.run(1.seconds)

        val history = harness.core.warningHistory()
        assertEquals(64, history.size, "the history must hold exactly its cap")
        val last = history.last().warning
        assertTrue(
            last is PlaybackWarning.RendererFailed && "failure 79" in last.message,
            "the newest warning must survive the cap, got ${last.message}",
        )
        harness.close()
    }

    @Test
    fun `the log seam is silent until installed and silent again after removal`() = runTest {
        val lines = mutableListOf<String>()
        KiteLog.install(null)
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)

        KiteLog.install { tag, message -> lines += "$tag: $message" }
        val rendererEvents = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 4)
        val emitting = object : VideoRenderer {
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
        harness.core.attachRenderer(emitting)
        rendererEvents.emit(RendererEvent.Failed("seen by the sink"))
        harness.run(200.milliseconds)
        assertTrue(lines.any { "seen by the sink" in it }, "the installed sink must hear the warning: $lines")

        KiteLog.install(null)
        val before = lines.size
        rendererEvents.emit(RendererEvent.Failed("unheard"))
        harness.run(200.milliseconds)
        assertEquals(before, lines.size, "a removed sink must hear nothing")
        harness.close()
    }
}
