@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a renderer's events reach, and for how long: a failure it reported to an earlier attach is
 * not a failure of this one (#596), and a closed player stops collecting them (#602).
 */
class RendererEventLifetimeTest {

    /** A renderer whose events replay, as four shipped renderers' do, and that draws every frame. */
    private class ReplayingRenderer : VideoRenderer {
        val feed = MutableSharedFlow<RendererEvent>(replay = 8)
        var presented = 0

        override val events = feed

        override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
            presented++
            frame.close()
            return true
        }

        override fun supports(format: PlayerPixelFormat) = true
        override fun supportedHardwareSurfaces() = emptySet<HwSurfaceKind>()
        override fun vsyncIntervalNanos(): Long? = null
        override fun setViewport(width: Int, height: Int, scale: Float) = Unit
        override fun setScaleMode(mode: VideoScale) = Unit
        override fun setAdjustments(adjustments: VideoAdjustments) = Unit
        override fun setTransform(transform: VideoTransform) = Unit
        override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit
        override fun close() = Unit
    }

    @Test
    fun aFailureReplayedFromAnEarlierAttachDetachesNothing() = runTest {
        val renderer = ReplayingRenderer()
        renderer.feed.tryEmit(RendererEvent.Failed("old"))
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.core.play()
        harness.run(1.seconds)

        assertTrue(renderer.presented > 1, "the renderer was detached by its old failure and got ${renderer.presented} frames")
        val failures = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.RendererFailed>()
        assertEquals(emptyList(), failures, "an old failure was reported again")

        // A failure after frames reached it is still this attach's own.
        renderer.feed.tryEmit(RendererEvent.Failed("new"))
        harness.run(300.milliseconds)
        assertTrue(
            harness.core.warningHistory().any { it.warning is PlaybackWarning.RendererFailed && "new" in it.warning.message },
            "a failure of this attach was not reported",
        )
        harness.close()
    }

    @Test
    fun aClosedPlayerStopsCollectingItsRenderersEvents() = runTest {
        val renderer = ReplayingRenderer()
        val harness = CoreHarness(this, renderer = null)
        harness.core.attachRenderer(renderer)
        harness.open()
        harness.run(200.milliseconds)
        assertEquals(1, renderer.feed.subscriptionCount.value)

        harness.close()
        testScheduler.runCurrent()
        assertEquals(0, renderer.feed.subscriptionCount.value, "a closed player still collects the renderer it outlived")
    }
}
