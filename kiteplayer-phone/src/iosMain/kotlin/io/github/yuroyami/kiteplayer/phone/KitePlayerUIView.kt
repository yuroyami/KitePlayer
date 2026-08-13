@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.ffmpeg.corePixelBufferOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.uploadPlanesOrNull
import io.github.yuroyami.kiteplayer.output.MetalPicture
import io.github.yuroyami.kiteplayer.output.MetalVideoRenderer
import io.github.yuroyami.kiteplayer.output.UIKitVideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSizeMake
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView

/**
 * The reusable iOS player view: a black [UIView] whose video layer's whole lifecycle is handled
 * here.
 *
 * Assign [player] and the view does the rest: while it is in a window it keeps a renderer
 * attached over its own layer, and when it leaves the window it closes that renderer and
 * detaches. The player is never owned here: opening media, playing, seeking and closing stay
 * the caller's, and removing the view from its window only stops the picture, never the
 * playback, because a backgrounded view should not stop the sound.
 *
 * Since S2.c the default picture path is METAL: VideoToolbox frames wrap into textures with no
 * copy and software frames upload in their native format. [preferMetal] set false before the
 * view enters a window keeps the S1.b CPU-converter CALayer route, which remains the measured
 * fallback. The Compose-true flagship lives in `:kiteplayer-compose`.
 *
 * All members must be used from the main thread, where UIKit delivers the callbacks that drive
 * them.
 */
public class KitePlayerUIView : UIView(frame = CGRectZero.readValue()) {

    private val videoLayer = CALayer()
    private val metalLayer = CAMetalLayer()

    /**
     * False routes new renderers through the CG fallback. Read when a renderer is CREATED
     * (entering a window with a player attached); flipping it later applies from the next
     * attachment, which is the same honest boundary every other view property has.
     */
    public var preferMetal: Boolean = true

    /** Counters accumulated across closed renderer generations, mirroring the Android view. */
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    private val binding = PlayerViewBinding<KitePlayer, VideoRenderer>(
        createRenderer = {
            if (preferMetal) {
                MetalVideoRenderer(
                    layer = metalLayer,
                    resolver = { frame ->
                        // Same honest boundary as the Android view: a frame from a backend other
                        // than the aggregate's own fails the cast and is counted failed.
                        val decoded = frame as KiteCodecVideoFrame
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
                    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
                }
            }
        },
        attach = { player, renderer -> player.attachRenderer(renderer) },
        detach = { player ->
            try {
                player.detachRenderer()
            } catch (_: IllegalStateException) {
                // The ordinary teardown order is close-the-player-then-clear-the-view, and a
                // closed player refuses every command, including this one. Closing already
                // detached everything, so there is nothing left to undo. Found by the S1.e.2
                // smoke's teardownCompleted key.
            }
        },
        close = { renderer ->
            countersOf(renderer).let { (presented, superseded, failed) ->
                presentedBefore += presented
                supersededBefore += superseded
                failedBefore += failed
            }
            renderer.close()
        },
    )

    private fun countersOf(renderer: VideoRenderer?): Triple<Long, Long, Long> = when (renderer) {
        is MetalVideoRenderer -> Triple(renderer.presentedFrames, renderer.supersededFrames, renderer.failedFrames)
        is UIKitVideoRenderer -> Triple(renderer.presentedFrames, renderer.supersededFrames, renderer.failedFrames)
        else -> Triple(0L, 0L, 0L)
    }

    /**
     * The player whose picture this view shows. Assigning replaces the previous pairing; null
     * detaches. Playback never depends on this being set.
     */
    public var player: KitePlayer? = null
        set(value) {
            field = value
            binding.setPlayer(value)
        }

    /** Frames delivered to this view's layer, across every renderer this view has built. */
    public val presentedFrames: Long
        get() = presentedBefore + countersOf(binding.activeRenderer).first

    /** Frames replaced by a newer one before they could be shown. See the renderer's own docs. */
    public val supersededFrames: Long
        get() = supersededBefore + countersOf(binding.activeRenderer).second

    /** Frames that reached no layer for a reason other than being superseded. */
    public val failedFrames: Long
        get() = failedBefore + countersOf(binding.activeRenderer).third

    /**
     * True while a video layer holds a picture. The CG layer's close never clears the last
     * delivered contents; a CAMetalLayer keeps its last presented drawable on the glass the
     * same way. Presentation evidence, not playback state.
     */
    public val hasPicture: Boolean
        get() = videoLayer.contents != null || metalHasPresented

    /** The Metal twin of the CG layer's contents check: the renderer's own counter. */
    private val metalHasPresented: Boolean
        get() = presentedFrames > 0 && preferMetal

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(videoLayer)
        layer.addSublayer(metalLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Disable the implicit animation, or every rotation shows the video sliding into place.
        CATransaction.begin()
        try {
            CATransaction.setDisableActions(true)
            videoLayer.frame = bounds
            metalLayer.frame = bounds
            // The drawable is sized in physical pixels; without this a Retina picture renders at
            // half resolution and CAMetalLayer scales it back up.
            val scale = window?.screen?.scale ?: UIScreen.mainScreen.scale
            metalLayer.contentsScale = scale
            bounds.useContents {
                metalLayer.drawableSize = CGSizeMake(size.width * scale, size.height * scale)
            }
        } finally {
            CATransaction.commit()
        }
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        // A window is this platform's "surface ready"; leaving it is the teardown boundary,
        // because Kotlin/Native exposes no overridable deinit to do it any later.
        if (window != null) {
            binding.surfaceReady()
        } else {
            binding.surfaceGone()
        }
    }
}
