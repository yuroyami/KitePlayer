@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KitePlayer
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
 * A renderer adapter must be installed before assigning [player]. The default adapter in
 * `kiteplayer-mobile` uses Metal: VideoToolbox frames wrap into textures with no copy and software
 * frames upload in their native format. [preferMetal] set false before the view enters a window
 * asks that adapter for its CPU-converter CALayer fallback. A custom backend can install its own
 * adapter without this view depending on KiteFFmpeg or the output implementation.
 *
 * All members must be used from the main thread, where UIKit delivers the callbacks that drive
 * them.
 */
public class KitePlayerUIView : UIView(frame = CGRectZero.readValue()) {

    private val videoLayer = CALayer()
    private val metalLayer = CAMetalLayer()

    /**
     * False asks an installed adapter for its CG fallback. Read when a renderer is CREATED
     * (entering a window with a player attached); flipping it later applies from the next
     * attachment, which is the same honest boundary every other view property has.
     */
    public var preferMetal: Boolean = true

    /** Counters accumulated across closed renderer generations, mirroring the Android view. */
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    /**
     * Which of the two layers the NEWEST renderer generation drew into, or null before the first
     * one (17.11 SOL-R9). Kept past that generation's close, because both layers hold their last
     * content on the glass; it is the visible layer, and the one [hasPicture] answers about.
     */
    private var showingMetal: Boolean? = null

    /** Presented frames of CLOSED generations that drew into the Metal layer. */
    private var metalPresentedBefore = 0L

    /**
     * Renderer adapter installed by the playback-stack module. A view with no adapter remains a
     * valid UIKit container and attaches no renderer; installing one later rebuilds immediately.
     */
    public var rendererFactory: ApplePlayerViewRendererFactory? = null
        set(value) {
            if (field === value) return
            field = value
            binding.rendererConfigurationChanged()
        }

    private val binding = PlayerViewBinding<KitePlayer, PlayerViewRenderer>(
        createRenderer = {
            val useMetal = preferMetal
            rendererFactory?.create(videoLayer, metalLayer, useMetal)?.also {
                // 17.11 SOL-R9: exactly one layer is on the glass. Both stayed visible before,
                // with the Metal layer on top, so its last drawable covered every CG frame a
                // fallback generation delivered afterwards.
                showingMetal = useMetal
                metalLayer.hidden = !useMetal
                videoLayer.hidden = useMetal
            }
        },
        attach = { player, renderer -> player.attachRenderer(renderer) },
        detach = { player, renderer ->
            try {
                player.detachRenderer(expected = renderer)
            } catch (_: IllegalStateException) {
                // The ordinary teardown order is close-the-player-then-clear-the-view, and a
                // closed player refuses every command, including this one. Closing already
                // detached everything, so there is nothing left to undo. Found by the S1.e.2
                // smoke's teardownCompleted key.
            }
        },
        close = { renderer ->
            try {
                renderer.close()
            } finally {
                presentedBefore += renderer.presentedFrames
                supersededBefore += renderer.supersededFrames
                failedBefore += renderer.failedFrames
                if (showingMetal == true) metalPresentedBefore += renderer.presentedFrames
            }
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

    /** Permanently releases this view's renderer pairing. The player itself remains caller-owned. */
    public fun release() {
        player = null
    }

    /**
     * True while the VISIBLE layer holds a picture. The CG layer's close never clears the last
     * delivered contents; a CAMetalLayer keeps its last presented drawable on the glass the
     * same way. Presentation evidence, not playback state.
     *
     * 17.11 SOL-R9: the answer is about the layer the newest generation chose, never about the
     * cumulative frame count, which mixes generations that drew into the other layer entirely.
     */
    public val hasPicture: Boolean
        get() = when (showingMetal) {
            true -> metalPresentedBefore + liveGenerationPresented > 0L
            false -> videoLayer.contents != null
            null -> false
        }

    /** Frames the live generation has presented, or zero between generations. */
    private val liveGenerationPresented: Long
        get() = binding.activeRenderer?.presentedFrames ?: 0L

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(videoLayer)
        layer.addSublayer(metalLayer)
        // Neither layer shows anything until a renderer generation claims one (17.11 SOL-R9).
        videoLayer.hidden = true
        metalLayer.hidden = true
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
