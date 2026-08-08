package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs

/**
 * A renderer that draws nothing and measures everything.
 *
 * It exists because the interesting question at this stage is not what the picture looks like, which
 * the golden image tests in `kiteplayer-ffmpeg` already answer against FFmpeg's own output. It is
 * whether frames arrive when the schedule said they would, which is what synchronisation means.
 *
 * It also honours the ownership rule, which a real renderer must: the frame belongs to it from the
 * moment `present` is called, and it closes it exactly once.
 */
internal class CountingRenderer(private val clock: MonotonicClock) : VideoRenderer {

    private val frames = atomic(0L)
    private val worstErrorNanos = atomic(0L)

    val count: Long get() = frames.value

    /** The largest gap between when a frame was asked for and when it was actually handed over. */
    val worstErrorMillis: Long get() = worstErrorNanos.value / 1_000_000

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        val error = abs(clock.nanos() - targetNanos)
        if (error > worstErrorNanos.value) worstErrorNanos.value = error
        frames.incrementAndGet()
        // The renderer owns the frame from here, including when it fails. Anything else leaks.
        frame.close()
        return true
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) = Unit

    override suspend fun setOverlay(overlay: SubtitleOverlay?) = Unit

    override val events: Flow<RendererEvent> = emptyFlow()

    override fun close() = Unit
}
