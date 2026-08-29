@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.ffmpeg.corePixelBufferOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.uploadPlanesOrNull
import io.github.yuroyami.kiteplayer.output.MetalPicture
import io.github.yuroyami.kiteplayer.output.MetalVideoRenderer
import io.github.yuroyami.kiteplayer.output.UIKitVideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.view.ApplePlayerViewRendererFactory
import io.github.yuroyami.kiteplayer.view.KitePlayerUIView
import io.github.yuroyami.kiteplayer.view.PlayerViewRenderer
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer

/** The default KiteFFmpeg/output adapter for an iOS [KitePlayerUIView]. */
public object MobileApplePlayerViewRendererFactory : ApplePlayerViewRendererFactory {
    override fun create(
        videoLayer: CALayer,
        metalLayer: CAMetalLayer,
        preferMetal: Boolean,
    ): PlayerViewRenderer {
        val renderer: VideoRenderer = if (preferMetal) {
            MetalVideoRenderer(
                layer = metalLayer,
                resolver = { frame ->
                    val decoded = frame as KiteFFmpegVideoFrame
                    decoded.corePixelBufferOrNull()?.let { MetalPicture.CorePixelBuffer(it) }
                        ?: decoded.uploadPlanesOrNull()?.let { planes ->
                            MetalPicture.SoftwarePlanes(
                                width = planes.width,
                                height = planes.height,
                                format = planes.format,
                                planes = planes.planes.map {
                                    MetalPicture.SoftwarePlanes.Plane(it.bytes, it.bytesPerRow, it.rows)
                                },
                            )
                        }
                },
            )
        } else {
            UIKitVideoRenderer(videoLayer) { frame ->
                SoftwareConverter.toRgba(frame as KiteFFmpegVideoFrame)
            }
        }
        return MobileApplePlayerViewRenderer(renderer)
    }
}

/** Installs the default mobile renderer adapter without making the reusable UIView depend on it. */
public fun KitePlayerUIView.installMobileRenderer() {
    rendererFactory = MobileApplePlayerViewRendererFactory
}

private class MobileApplePlayerViewRenderer(
    private val delegate: VideoRenderer,
) : PlayerViewRenderer, VideoRenderer by delegate {
    override val presentedFrames: Long
        get() = when (delegate) {
            is MetalVideoRenderer -> delegate.presentedFrames
            is UIKitVideoRenderer -> delegate.presentedFrames
            else -> 0L
        }

    override val supersededFrames: Long
        get() = when (delegate) {
            is MetalVideoRenderer -> delegate.supersededFrames
            is UIKitVideoRenderer -> delegate.supersededFrames
            else -> 0L
        }

    override val failedFrames: Long
        get() = when (delegate) {
            is MetalVideoRenderer -> delegate.failedFrames
            is UIKitVideoRenderer -> delegate.failedFrames
            else -> 0L
        }
}
