package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * js has no Compose video path: the web painter of KiteFFmpeg is a wasmJs module. The renderer
 * supports no pixel format, so a player that draws through it shows no picture. Use wasmJs for
 * Compose video on the web.
 */
public actual class KiteVideoState actual constructor() {
    public actual val renderer: VideoRenderer = NoPictureRenderer()
    public actual val presentedFrames: Long get() = 0
    public actual val supersededFrames: Long get() = 0
    public actual val failedFrames: Long get() = 0
    public actual val frameCost: KiteVideoFrameCost get() = KiteVideoFrameCost(0, 0, 0, 0)
}

/** A renderer that accepts no frame, so the engine never decodes a picture for it. */
private class NoPictureRenderer : VideoRenderer {
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()
    override fun supports(format: PlayerPixelFormat): Boolean = false
    override val appliesAdjustments: Boolean get() = false
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean = false
    override fun vsyncIntervalNanos(): Long? = null
    override fun setViewport(width: Int, height: Int, scale: Float) {}
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {}
    override val events: Flow<RendererEvent> = emptyFlow()
    override fun close() {}
}
