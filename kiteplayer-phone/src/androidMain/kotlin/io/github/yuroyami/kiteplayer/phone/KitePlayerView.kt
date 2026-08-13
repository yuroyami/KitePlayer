package io.github.yuroyami.kiteplayer.phone

import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AndroidSurfaceVideoRenderer
import kotlin.math.roundToInt

/**
 * The reusable Android player view: a [SurfaceView] whose whole lifecycle is handled here.
 *
 * Assign [player] before opening media and the view attaches a renderer immediately. On Android 10
 * and newer that renderer offers a paired hardware MediaCodec decoder for strict
 * [io.github.yuroyami.kiteplayer.HwdecPolicy.Require] sessions, releasing decoded output straight
 * into the SurfaceView. Auto stays on the replay-safe backend path until cross-factory runtime
 * fallback exists. Older or unsupported devices keep the software Canvas fallback.
 * Surface creation and destruction are forwarded into the same renderer generation, so a temporary
 * backgrounding does not force playback or decoder reconstruction.
 *
 * Subtitles use a transparent view above the Surface. MediaCodec remains the Surface's only producer,
 * while cue changes can redraw even when video is paused.
 *
 * All members must be used from the main thread, where Android delivers the callbacks that
 * drive them.
 */
public class KitePlayerView @JvmOverloads constructor(
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

    private val binding = PlayerViewBinding<KitePlayer, AndroidSurfaceVideoRenderer>(
        createRenderer = {
            val generation = ++rendererGeneration
            // The frame cast is the honest boundary of this convenience: the view exists for the
            // aggregate's own FFmpeg backend. A frame from some other backend fails the cast
            // inside the renderer's converter seam, which counts it as a failed frame and plays
            // on, rather than crashing the process.
            AndroidSurfaceVideoRenderer(
                convert = { frame -> SoftwareConverter.toRgba(frame as KiteCodecVideoFrame) },
                onOverlay = { overlay ->
                    runForRenderer(generation) { subtitleView.showOverlay(overlay) }
                },
                onVideoGeometry = { size, rotationDegrees ->
                    runForRenderer(generation) { setVideoGeometry(size.displayAspect, rotationDegrees) }
                },
            ).also { renderer ->
                surfaceView.holder.surface.takeIf { it.isValid }?.let(renderer::setSurface)
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
                // smoke's teardownCompleted key; the same latent throw existed here.
            }
        },
        close = { renderer ->
            rendererGeneration += 1L
            renderer.close()
            presentedBefore += renderer.presentedFrames
            supersededBefore += renderer.supersededFrames
            failedBefore += renderer.failedFrames
            subtitleView.showOverlay(null)
            setVideoGeometry(0f, 0)
        },
        rendererNeedsSurface = false,
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
