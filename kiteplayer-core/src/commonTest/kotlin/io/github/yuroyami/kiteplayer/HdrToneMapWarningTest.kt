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
 * Tone mapping announces itself where it ENGAGES.
 *
 * The old warning was derived from the stream's metadata, so it fired on every HDR stream whatever
 * the display path did with it. That was false for every built-in path, because the engine has
 * tone mapped HDR since 2026-08-16, and it would be false in the other direction for a path that
 * hands HDR to a display able to show it. Only the renderer knows which happened, so the renderer
 * is what says so.
 */
class HdrToneMapWarningTest {

    private class ScriptedRenderer : VideoRenderer {
        val published: MutableSharedFlow<RendererEvent> = MutableSharedFlow(extraBufferCapacity = 16)
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
        override val events: Flow<RendererEvent> get() = published
    }

    @Test
    fun `a renderer that tone maps produces one typed warning carrying the transfer`() = runTest {
        val renderer = ScriptedRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.run(100.milliseconds)

        renderer.published.emit(RendererEvent.ToneMapEngaged(transfer = "PQ", streamIndex = 3))
        harness.run(200.milliseconds)

        val warnings = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.HdrToneMapped>()
        assertEquals(1, warnings.size, "one warning, got ${warnings.map { it.message }}")
        assertEquals("PQ", warnings.single().transfer, "the transfer is carried, not re-derived")
        assertEquals(3, warnings.single().streamIndex)
        harness.close()
    }

    /**
     * A renderer is handed frames, not streams, so it answers -1 and the engine names the stream.
     *
     * Without this the warning would say "stream -1" for every renderer that ships, because none
     * of them has an index to quote: the SPI hands them a `VideoFrame` and nothing else.
     */
    @Test
    fun `the engine names the stream when the renderer cannot`() = runTest {
        val renderer = ScriptedRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.run(100.milliseconds)

        renderer.published.emit(RendererEvent.ToneMapEngaged(transfer = "HLG"))
        harness.run(200.milliseconds)

        val warning = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.HdrToneMapped>()
            .single()
        assertEquals("HLG", warning.transfer)
        assertTrue(
            warning.streamIndex >= 0,
            "the engine knows which video stream it is feeding; it must fill the gap in, got ${warning.streamIndex}",
        )
        harness.close()
    }

    /**
     * A renderer publishing on every tone mapped frame is behaving correctly. The engine latches.
     *
     * Without this the warning feed would carry one entry per frame for the whole file, which is
     * the failure that makes a warning history useless rather than merely noisy.
     */
    @Test
    fun `many engagements in one open produce one warning`() = runTest {
        val renderer = ScriptedRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.run(100.milliseconds)

        repeat(5) { renderer.published.emit(RendererEvent.ToneMapEngaged("HLG", 0)) }
        harness.run(200.milliseconds)

        val warnings = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.HdrToneMapped>()
        assertEquals(1, warnings.size, "latched once per open, got ${warnings.size}")
        harness.close()
    }

    /**
     * The arm that dies if anyone regresses to metadata-based emission.
     *
     * A renderer that never publishes the event is a renderer that never tone mapped: the Android
     * MediaCodec interop tier decodes straight to a Surface and touches no pixel, and its display
     * may be showing real HDR. Warning there would be the same lie pointing the other way.
     */
    @Test
    fun `a renderer that never tone maps produces no tone map warning at all`() = runTest {
        val renderer = ScriptedRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.run(300.milliseconds)

        renderer.published.emit(RendererEvent.SurfaceAvailable)
        harness.run(200.milliseconds)

        val warnings = harness.core.warningHistory().map { it.warning }
        assertTrue(
            warnings.none { it is PlaybackWarning.HdrToneMapped },
            "nothing tone mapped, so nothing may claim it did. Got: ${warnings.map { it.message }}",
        )
        harness.close()
    }

    /** The deprecated type is not emitted by anything, on any path. */
    @Test
    fun `the deprecated warning is never emitted`() = runTest {
        val renderer = ScriptedRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        renderer.published.emit(RendererEvent.ToneMapEngaged("PQ", 0))
        harness.run(300.milliseconds)

        val warnings = harness.core.warningHistory().map { it.warning }
        @Suppress("DEPRECATION")
        assertTrue(
            warnings.none { it is PlaybackWarning.TonemappingUnavailable },
            "TonemappingUnavailable is deprecated and sited nowhere. Got: ${warnings.map { it.message }}",
        )
        harness.close()
    }
}
