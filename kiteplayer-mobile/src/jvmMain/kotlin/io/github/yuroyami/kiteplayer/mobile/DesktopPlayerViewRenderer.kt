package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AwtCanvasVideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.view.AwtPlayerViewRenderer
import io.github.yuroyami.kiteplayer.view.AwtPlayerViewRendererFactory
import io.github.yuroyami.kiteplayer.view.KitePlayerAwtView
import java.awt.Canvas

/**
 * The default KiteFFmpeg and output adapter for a desktop [KitePlayerAwtView].
 *
 * It exists for the same reason its Android twin does: the reusable view must not depend on a
 * codec, and the renderer must not either, so the one module that already depends on both joins
 * them. This is where the conversion actually happens, which is why the boundary holds.
 */
public object DesktopAwtPlayerViewRendererFactory : AwtPlayerViewRendererFactory {
    override fun create(
        onVideoGeometry: (VideoSize, Int) -> Unit,
    ): AwtPlayerViewRenderer = DesktopAwtPlayerViewRenderer(
        AwtCanvasVideoRenderer(
            painter = { frame, destination, width, height ->
                packRgbInto(frame as KiteFFmpegVideoFrame, destination, width, height)
            },
            onVideoGeometry = onVideoGeometry,
        ),
    )
}

/** Installs the default desktop renderer without making the reusable view depend on it. */
public fun KitePlayerAwtView.installDesktopRenderer() {
    rendererFactory = DesktopAwtPlayerViewRendererFactory
}

/**
 * Converts one frame into the packed integers a `BufferedImage` of type `TYPE_INT_RGB` stores.
 *
 * The converter answers RGBA bytes, which is what every other consumer of it wants, so the one
 * repacking step lives here rather than being pushed into the shared converter for one platform's
 * image model. Alpha is dropped deliberately: the destination is an opaque picture and the video
 * frame has no meaningful alpha to carry.
 */
private fun packRgbInto(
    frame: KiteFFmpegVideoFrame,
    destination: IntArray,
    width: Int,
    height: Int,
): Boolean {
    val rgba = SoftwareConverter.toRgba(frame)
    val pixels = width * height
    if (rgba.size < pixels * 4 || destination.size < pixels) return false
    var i = 0
    while (i < pixels) {
        val base = i * 4
        val r = rgba[base].toInt() and 0xFF
        val g = rgba[base + 1].toInt() and 0xFF
        val b = rgba[base + 2].toInt() and 0xFF
        destination[i] = (r shl 16) or (g shl 8) or b
        i++
    }
    return true
}

private class DesktopAwtPlayerViewRenderer(
    private val delegate: AwtCanvasVideoRenderer,
) : AwtPlayerViewRenderer, VideoRenderer by delegate {
    override val presentedFrames: Long get() = delegate.presentedFrames
    override val supersededFrames: Long get() = delegate.supersededFrames
    override val failedFrames: Long get() = delegate.failedFrames

    override fun setCanvas(canvas: Canvas?) {
        delegate.setCanvas(canvas)
    }
}
