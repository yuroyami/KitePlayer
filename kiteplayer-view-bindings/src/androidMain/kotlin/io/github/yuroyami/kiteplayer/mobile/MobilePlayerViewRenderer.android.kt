package io.github.yuroyami.kiteplayer.mobile

import android.view.Surface
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AndroidSurfaceVideoRenderer
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.view.AndroidPlayerViewRenderer
import io.github.yuroyami.kiteplayer.view.AndroidPlayerViewRendererFactory
import io.github.yuroyami.kiteplayer.view.KitePlayerView

/** The default KiteFFmpeg/output adapter for an Android [KitePlayerView]. */
public object MobileAndroidPlayerViewRendererFactory : AndroidPlayerViewRendererFactory {
    override fun create(
        onOverlay: (SubtitleOverlay?) -> Unit,
        onVideoGeometry: (VideoSize, Int) -> Unit,
        onScaleMode: (VideoScale) -> Unit,
    ): AndroidPlayerViewRenderer = MobileAndroidPlayerViewRenderer(
        AndroidSurfaceVideoRenderer(
            convert = { frame -> SoftwareConverter.toRgba(frame as KiteFFmpegVideoFrame) },
            onOverlay = onOverlay,
            onVideoGeometry = onVideoGeometry,
        ),
        onScaleMode,
    )
}

/** Installs the default mobile renderer adapter without making the reusable View depend on it. */
public fun KitePlayerView.installMobileRenderer() {
    rendererFactory = MobileAndroidPlayerViewRendererFactory
}

private class MobileAndroidPlayerViewRenderer(
    private val delegate: AndroidSurfaceVideoRenderer,
    private val onScaleMode: (VideoScale) -> Unit,
) : AndroidPlayerViewRenderer, VideoRenderer by delegate {
    override val presentedFrames: Long get() = delegate.presentedFrames
    override val supersededFrames: Long get() = delegate.supersededFrames
    override val failedFrames: Long get() = delegate.failedFrames

    override fun setSurface(surface: Surface?) {
        delegate.setSurface(surface)
    }

    override fun setDisplayRefreshRate(hz: Float) {
        delegate.setDisplayRefreshRate(hz)
    }

    /**
     * The software path frames the picture itself; the MediaCodec path cannot, because the codec
     * writes straight into the Surface. So the view is told too, and it sizes that Surface.
     */
    override fun setScaleMode(mode: VideoScale) {
        delegate.setScaleMode(mode)
        onScaleMode(mode)
    }
}
