package io.github.yuroyami.kiteplayer.phone

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AndroidSurfaceVideoRenderer

/**
 * The reusable Android player view: a [SurfaceView] whose whole lifecycle is handled here.
 *
 * Assign [player] and the view does the rest: when the surface exists it builds an
 * [AndroidSurfaceVideoRenderer] over it and attaches it to the player, and when the surface goes
 * away it closes that renderer before the callback returns, which is the rule the Surface
 * contract actually demands and the one every hand-rolled integration gets wrong first. The
 * player itself is never owned here: opening media, playing, seeking and closing stay the
 * caller's, and setting [player] to null (or letting the view leave the window) only stops the
 * picture, never the playback.
 *
 * The view draws through the software Surface path (S1.c). It is the BASELINE presentation
 * path per D-6: a SurfaceView composits through the display controller, which is what wins
 * sustained fullscreen battery. The Compose-true flagship lives in `:kiteplayer-compose`.
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

    /**
     * Counters accumulated across closed renderer generations, so a surface bounce (a rotation,
     * a backgrounding) does not zero what the diagnostics already saw.
     */
    private var presentedBefore = 0L
    private var supersededBefore = 0L
    private var failedBefore = 0L

    private val binding = PlayerViewBinding<KitePlayer, AndroidSurfaceVideoRenderer>(
        createRenderer = {
            // The frame cast is the honest boundary of this convenience: the view exists for the
            // aggregate's own FFmpeg backend. A frame from some other backend fails the cast
            // inside the renderer's converter seam, which counts it as a failed frame and plays
            // on, rather than crashing the process.
            AndroidSurfaceVideoRenderer(surfaceView.holder.surface) { frame ->
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
                // smoke's teardownCompleted key; the same latent throw existed here.
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
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                binding.surfaceReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // The renderer reads the canvas size on every draw, so a resize needs no action.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Must guarantee no surface access after returning; the binding closes first.
                binding.surfaceGone()
            }
        })
    }
}
