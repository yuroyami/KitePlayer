package io.github.yuroyami.kiteplayer.view

import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import kotlin.math.roundToInt

/**
 * A reusable Android player view for layouts, programmatic UI, and Compose `AndroidView` interop.
 *
 * This artifact owns only Android view lifecycle and layout. Install a [rendererFactory] supplied by
 * a rendering adapter before assigning [player] or opening media. The renderer is attached as soon as
 * both the player and factory exist, even before the Surface does, so renderer-coupled hardware
 * decoders can participate in decoder selection. Surface creation and destruction are forwarded into
 * that same renderer generation; temporary backgrounding does not reconstruct the player or decoder.
 *
 * Subtitles use a transparent view above the Surface, so cue changes can redraw while video is paused.
 * The renderer adapter reports subtitle overlays and video geometry through the callbacks supplied by
 * this view.
 *
 * Call [release] when the owner is permanently destroyed. An Activity normally does that from
 * `onDestroy`; an `AndroidView` wrapper does it from its `onRelease` callback. Merely detaching this
 * view from a window is not proof that it will not be reused, so detachment does not release it.
 *
 * All members must be used from the main thread, where Android delivers the callbacks that drive them.
 */
public open class KitePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val surfaceView = SurfaceView(context)
    private val subtitleView = SubtitleOverlayView(context)
    private var videoAspect: Float = 0f
    private var rendererGeneration: Long = 0L

    /**
     * Counters accumulated across closed renderer generations, so a surface bounce (a rotation,
     * a backgrounding) does not zero what the diagnostics already saw.
     */
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    private val binding = PlayerViewBinding<KitePlayer, AndroidPlayerViewRenderer>(
        createRenderer = {
            rendererFactory?.let { factory ->
                val generation = ++rendererGeneration
                val renderer = factory.create(
                    onOverlay = { overlay ->
                        runForRenderer(generation) { subtitleView.showOverlay(overlay) }
                    },
                    onVideoGeometry = { size, rotationDegrees ->
                        runForRenderer(generation) {
                            setVideoGeometry(size.displayAspect, rotationDegrees)
                        }
                    },
                )
                try {
                    renderer.setSurface(surfaceView.holder.surface.takeIf { it.isValid })
                    renderer
                } catch (configurationFailure: Throwable) {
                    // The renderer has not reached PlayerViewBinding yet, so that binding cannot
                    // own rollback. Invalidate its callbacks and close it here before propagating
                    // the failed construction transaction.
                    rendererGeneration += 1L
                    try {
                        renderer.close()
                    } catch (closeFailure: Throwable) {
                        if (closeFailure !== configurationFailure) {
                            configurationFailure.addSuppressed(closeFailure)
                        }
                    } finally {
                        subtitleView.showOverlay(null)
                        setVideoGeometry(0f, 0)
                    }
                    throw configurationFailure
                }
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
                // smoke's teardownCompleted key; the same latent throw existed here.
            }
        },
        close = { renderer ->
            rendererGeneration += 1L
            try {
                renderer.close()
            } finally {
                presentedBefore += renderer.presentedFrames
                supersededBefore += renderer.supersededFrames
                failedBefore += renderer.failedFrames
                subtitleView.showOverlay(null)
                setVideoGeometry(0f, 0)
            }
        },
        rendererNeedsSurface = false,
    )

    /**
     * Creates the renderer attached to [player]. Null keeps this view deliberately headless.
     *
     * Replacing the factory closes and detaches the current renderer before creating and attaching
     * its replacement. Install it before opening media when the renderer contributes a hardware
     * decoder factory; adding one to an already-open session does not reselect the decoder.
     */
    public var rendererFactory: AndroidPlayerViewRendererFactory? = null
        set(value) {
            if (field === value) return
            field = value
            binding.rendererConfigurationChanged()
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

    /**
     * Permanently releases this view's player pairing and active renderer.
     *
     * Call this from an Activity's `onDestroy` or an `AndroidView` wrapper's `onRelease` callback.
     * The [rendererFactory] is retained so the view can be paired again if its owner intentionally
     * reuses it.
     */
    public fun release() {
        player = null
    }

    /** Frames posted to this view's surface, across every renderer this view has built. */
    public val presentedFrames: Long
        get() = presentedBefore + (binding.activeRenderer?.presentedFrames ?: 0L)

    /** Frames replaced by a newer one before they could be drawn. See the renderer's own docs. */
    public val supersededFrames: Long
        get() = supersededBefore + (binding.activeRenderer?.supersededFrames ?: 0L)

    /** Frames that reached no surface for a reason other than being superseded. */
    public val failedFrames: Long
        get() = failedBefore + (binding.activeRenderer?.failedFrames ?: 0L)

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                binding.activeRenderer?.setSurface(holder.surface)
                binding.surfaceReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                binding.activeRenderer?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // setSurface(null) fences both Canvas and codec releases before this callback returns.
                binding.activeRenderer?.setSurface(null)
                binding.surfaceGone()
            }
        })
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val aspect = videoAspect
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (!aspect.isFinite() || aspect <= 0f || availableWidth <= 0 || availableHeight <= 0) return

        val availableAspect = availableWidth.toFloat() / availableHeight.toFloat()
        val videoWidth: Int
        val videoHeight: Int
        if (availableAspect > aspect) {
            videoHeight = availableHeight
            videoWidth = (videoHeight * aspect).roundToInt().coerceIn(1, availableWidth)
        } else {
            videoWidth = availableWidth
            videoHeight = (videoWidth / aspect).roundToInt().coerceIn(1, availableHeight)
        }
        val videoLeft = paddingLeft + (availableWidth - videoWidth) / 2
        val videoTop = paddingTop + (availableHeight - videoHeight) / 2
        surfaceView.layout(videoLeft, videoTop, videoLeft + videoWidth, videoTop + videoHeight)
        subtitleView.layout(videoLeft, videoTop, videoLeft + videoWidth, videoTop + videoHeight)
    }

    private fun setVideoGeometry(displayAspect: Float, rotationDegrees: Int) {
        val turn = ((rotationDegrees % 360) + 360) % 360
        videoAspect = if (turn == 90 || turn == 270) {
            if (displayAspect > 0f) 1f / displayAspect else 0f
        } else {
            displayAspect
        }
        subtitleView.setVideoRotation(turn)
        requestLayout()
    }

    private fun runForRenderer(generation: Long, block: () -> Unit) {
        val guarded = { if (generation == rendererGeneration) block() }
        if (Looper.myLooper() === Looper.getMainLooper()) guarded() else post(guarded)
    }
}

/**
 * A renderer that can follow the [SurfaceView] owned by [KitePlayerView].
 *
 * The view owns the [android.view.Surface] and the renderer must not release it. Passing null fences
 * all use of the previous Surface before returning.
 */
public interface AndroidPlayerViewRenderer : PlayerViewRenderer {
    public fun setSurface(surface: Surface?)
}

/** Creates the Android renderer adapter used by [KitePlayerView]. */
public fun interface AndroidPlayerViewRendererFactory {
    /**
     * Creates one renderer generation. UI callbacks may arrive off the main thread; the view safely
     * marshals them before changing its overlay or layout.
     */
    public fun create(
        onOverlay: (SubtitleOverlay?) -> Unit,
        onVideoGeometry: (VideoSize, rotationDegrees: Int) -> Unit,
    ): AndroidPlayerViewRenderer
}
