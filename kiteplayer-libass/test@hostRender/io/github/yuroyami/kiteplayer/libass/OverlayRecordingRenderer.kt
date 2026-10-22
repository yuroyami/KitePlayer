package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A renderer that draws nothing and keeps every overlay, for proving what reached the glass. Real
 * threads publish here, so the list sits behind a lock and is copied out.
 */
internal class OverlayRecordingRenderer(private val surface: VideoSize = VideoSize(640, 360)) : VideoRenderer {
    private val lock = SynchronizedObject()
    private val received = mutableListOf<SubtitleOverlay?>()
    private var frames = 0L

    val overlays: List<SubtitleOverlay?> get() = synchronized(lock) { received.toList() }
    val presented: Long get() = synchronized(lock) { frames }

    override val outputSize: VideoSize get() = surface
    override fun setScaleMode(mode: VideoScale) = Unit
    override fun setAdjustments(adjustments: VideoAdjustments) = Unit
    override fun setTransform(transform: VideoTransform) = Unit
    override fun videoDecoderFactories(): List<VideoDecoderFactory> = emptyList()
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
    override fun supports(format: PlayerPixelFormat): Boolean = true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        frame.close()
        synchronized(lock) { frames++ }
        return true
    }

    override fun vsyncIntervalNanos(): Long? = null
    override fun setViewport(width: Int, height: Int, scale: Float) = Unit

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        synchronized(lock) { received += overlay }
    }

    override val events: Flow<RendererEvent> = emptyFlow()
    override fun close() = Unit
}
