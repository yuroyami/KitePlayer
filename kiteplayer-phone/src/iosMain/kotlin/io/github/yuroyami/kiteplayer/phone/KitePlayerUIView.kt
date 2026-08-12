@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.UIKitVideoRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * The reusable iOS player view: a black [UIView] whose video layer's whole lifecycle is handled
 * here.
 *
 * Assign [player] and the view does the rest: while it is in a window it keeps a
 * [UIKitVideoRenderer] attached over its own [CALayer], and when it leaves the window it closes
 * that renderer and detaches. The player is never owned here: opening media, playing, seeking
 * and closing stay the caller's, and removing the view from its window only stops the picture,
 * never the playback, because a backgrounded view should not stop the sound.
 *
 * This is the BASELINE presentation path per D-6, the CPU-converter CALayer route from S1.b;
 * Metal is S2 work and the Compose-true flagship lives in `:kiteplayer-compose`.
 *
 * All members must be used from the main thread, where UIKit delivers the callbacks that drive
 * them.
 */
public class KitePlayerUIView : UIView(frame = CGRectZero.readValue()) {

    private val videoLayer = CALayer()

    /** Counters accumulated across closed renderer generations, mirroring the Android view. */
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    private val binding = PlayerViewBinding<KitePlayer, UIKitVideoRenderer>(
        createRenderer = {
            // Same honest boundary as the Android view: a frame from a backend other than the
            // aggregate's own fails the cast inside the converter seam and is counted as a
            // failed frame; playback carries on.
            UIKitVideoRenderer(videoLayer) { frame ->
                SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
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
            presentedBefore += renderer.presentedFrames
            supersededBefore += renderer.supersededFrames
            failedBefore += renderer.failedFrames
            renderer.close()
        },
    )

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
        get() = presentedBefore + (binding.activeRenderer?.presentedFrames ?: 0L)

    /** Frames replaced by a newer one before they could be shown. See the renderer's own docs. */
    public val supersededFrames: Long
        get() = supersededBefore + (binding.activeRenderer?.supersededFrames ?: 0L)

    /** Frames that reached no layer for a reason other than being superseded. */
    public val failedFrames: Long
        get() = failedBefore + (binding.activeRenderer?.failedFrames ?: 0L)

    /**
     * True while the video layer holds a picture. The renderer's close never clears the last
     * delivered contents, so this stays true after teardown until something replaces the layer's
     * contents; it is presentation evidence, not playback state.
     */
    public val hasPicture: Boolean
        get() = videoLayer.contents != null

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(videoLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Disable the implicit animation, or every rotation shows the video sliding into place.
        CATransaction.begin()
        try {
            CATransaction.setDisableActions(true)
            videoLayer.frame = bounds
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
