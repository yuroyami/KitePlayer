package io.github.yuroyami.kiteplayer.view

import kotlin.time.Duration
import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.util.AttributeSet
import android.util.Rational
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.VideoScale
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
    private var videoRotation: Int = 0
    private var videoScale: VideoScale = VideoScale.Fit
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
                    onScaleMode = { mode ->
                        runForRenderer(generation) { setVideoScale(mode) }
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
            updateAccessibilityState()
        }

    /**
     * Re-reads what a screen reader should say about the player and tells the platform.
     *
     * Called when the player is assigned and by an application whenever the state it cares about
     * moved. It is NOT wired to a flow here on purpose: this view holds no scope of its own, and
     * one started for a label would outlive the pairing it belongs to. The state text is a pure
     * function, so an application already collecting the snapshot can call this from the same
     * place it updates its own controls.
     */
    public fun updateAccessibilityState() {
        val snapshot = player?.state?.value
        val text = if (snapshot == null) {
            accessibilityStateText(PlaybackStatus.Idle, Duration.ZERO, null)
        } else {
            accessibilityStateText(
                snapshot.status,
                player?.progress?.value?.position ?: Duration.ZERO,
                snapshot.duration,
            )
        }
        // stateDescription is API 30. Below it the label carries both, which is what a reader on
        // an older phone would otherwise never hear.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            stateDescription = text
            contentDescription = DEFAULT_VIDEO_ACCESSIBILITY_LABEL
        } else {
            contentDescription = "$DEFAULT_VIDEO_ACCESSIBILITY_LABEL. $text"
        }
    }

    /**
     * Marks the surface secure: excluded from screenshots, screen recording and non-secure
     * displays, which is what paid content asks for. Off by default. Reads back what this view
     * last set; the platform does not report the flag.
     */
    public var secure: Boolean = false
        set(value) {
            field = value
            surfaceView.setSecure(value)
        }

    /** The turn last reported for the picture, for the parameter keeper beside this class. */
    internal val videoRotationDegrees: Int get() = videoRotation

    /**
     * Parameters for `Activity.enterPictureInPictureMode`: the video's own aspect, turned with its
     * rotation and clamped to what the OS accepts, this view's video area as the source rectangle
     * so the transition starts from the picture, and on API 31 and later auto-enter armed while
     * the player is playing when [autoEnterWhilePlaying] is set. The activity owns the transition
     * and the manifest; rebuild these whenever the picture or the play state changes, because the
     * OS reads auto-enter from the parameters once rather than watching anything.
     */
    public fun pictureInPictureParams(autoEnterWhilePlaying: Boolean = true): PictureInPictureParams {
        val snapshot = player?.state?.value
        val size = snapshot?.videoSize
        val aspect = pictureInPictureAspect(size?.displayWidth ?: 0, size?.height ?: 0, videoRotation)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.numerator, aspect.denominator))
        val sourceRect = Rect()
        if (surfaceView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
            builder.setSourceRectHint(sourceRect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnterWhilePlaying && snapshot?.status == PlaybackStatus.Playing)
        }
        return builder.build()
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
        // A screen reader saw an unlabelled rectangle. This view IS the video, so it is the
        // element that announces itself; the surface and the subtitle overlay inside it are
        // decoration and stay out of the reader's way.
        contentDescription = DEFAULT_VIDEO_ACCESSIBILITY_LABEL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // Fill lays the surface out larger than this view; the crop is what makes it Fill and
        // not an overflow onto whatever sits next to the video.
        clipChildren = true
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                binding.activeRenderer?.setSurface(holder.surface)
                feedDisplayRefreshRate()
                binding.surfaceReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                binding.activeRenderer?.setSurface(holder.surface)
                // Re-read on every change: the window may have moved to another display.
                feedDisplayRefreshRate()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // setSurface(null) fences both Canvas and codec use before this callback returns,
                // with a time limit, so a stuck draw cannot hang the main thread.
                binding.activeRenderer?.setSurface(null)
                binding.surfaceGone()
            }
        })
    }

    /**
     * A Surface does not know its display, so the view, which does, hands the refresh rate to
     * the renderer. Null display (detached view) feeds 0, which the renderer reads as unknown.
     */
    private fun feedDisplayRefreshRate() {
        binding.activeRenderer?.setDisplayRefreshRate(display?.refreshRate ?: 0f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val picture = videoBounds(availableWidth, availableHeight, videoAspect, videoScale) ?: return

        val videoLeft = paddingLeft + picture.left
        val videoTop = paddingTop + picture.top
        // Measured again at the size it is about to get: Fill lays the Surface out LARGER than
        // this view measured it, and a child laid out past its measurement is not a contract.
        layoutChild(surfaceView, videoLeft, videoTop, picture.width, picture.height)

        // Fill and Stretch push the picture past this view, and the clip crops it. Text must not
        // go over the edge with it, so subtitles take the part of the picture that stays visible.
        val textLeft = videoLeft.coerceAtLeast(paddingLeft)
        val textTop = videoTop.coerceAtLeast(paddingTop)
        val textRight = (videoLeft + picture.width).coerceAtMost(paddingLeft + availableWidth)
        val textBottom = (videoTop + picture.height).coerceAtMost(paddingTop + availableHeight)
        layoutChild(subtitleView, textLeft, textTop, textRight - textLeft, textBottom - textTop)
    }

    private fun layoutChild(child: View, x: Int, y: Int, childWidth: Int, childHeight: Int) {
        child.measure(
            MeasureSpec.makeMeasureSpec(childWidth.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight.coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
        child.layout(x, y, x + childWidth, y + childHeight)
    }

    private fun setVideoGeometry(displayAspect: Float, rotationDegrees: Int) {
        val turn = ((rotationDegrees % 360) + 360) % 360
        videoAspect = if (turn == 90 || turn == 270) {
            if (displayAspect > 0f) 1f / displayAspect else 0f
        } else {
            displayAspect
        }
        videoRotation = turn
        subtitleView.setVideoRotation(turn)
        requestLayout()
    }

    /**
     * The mode the player is in, which decides how much of this view the picture covers. Kept
     * across a surface bounce because the player tells every new renderer generation its mode.
     */
    private fun setVideoScale(mode: VideoScale) {
        if (videoScale == mode) return
        videoScale = mode
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
 * use of the previous Surface with a bounded wait; an unfinished draw must be reported as a failure.
 * Every call comes from the main thread, so an
 * implementation must never wait on work that needs that thread, and should not wait at all for a
 * non-null Surface.
 */
public interface AndroidPlayerViewRenderer : PlayerViewRenderer {
    public fun setSurface(surface: Surface?)

    /**
     * The refresh rate of the display this view sits on, fed by the view because a Surface does
     * not know its display. 0 means unknown. Default: ignored, for renderers that do not pace.
     */
    public fun setDisplayRefreshRate(hz: Float) {}
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
        onScaleMode: (VideoScale) -> Unit,
    ): AndroidPlayerViewRenderer
}

/** The rectangle the picture occupies, relative to the padded content box. */
internal data class VideoBounds(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where the video surface goes for a scale mode.
 *
 * Android is the one platform whose picture is not drawn into a canvas the library owns: with a
 * hardware decoder, MediaCodec writes straight into the Surface, so the only lever left is how
 * big that Surface is. Fit keeps the whole picture and letterboxes it, Fill covers the view and
 * lets the view crop the overhang, Stretch takes the view as it is. Null means there is nothing
 * to lay out yet.
 */
internal fun videoBounds(
    availableWidth: Int,
    availableHeight: Int,
    aspect: Float,
    mode: VideoScale,
): VideoBounds? {
    if (!aspect.isFinite() || aspect <= 0f || availableWidth <= 0 || availableHeight <= 0) return null
    if (mode == VideoScale.Stretch) return VideoBounds(0, 0, availableWidth, availableHeight)

    // True when the view is wider than the picture, so Fit pins the height and Fill pins the width.
    val viewIsWider = availableWidth.toFloat() / availableHeight.toFloat() > aspect
    val pinHeight = if (mode == VideoScale.Fill) !viewIsWider else viewIsWider
    val width: Int
    val height: Int
    if (pinHeight) {
        height = availableHeight
        val derived = (height * aspect).roundToInt()
        width = if (mode == VideoScale.Fit) derived.coerceIn(1, availableWidth) else derived.coerceAtLeast(1)
    } else {
        width = availableWidth
        val derived = (width / aspect).roundToInt()
        height = if (mode == VideoScale.Fit) derived.coerceIn(1, availableHeight) else derived.coerceAtLeast(1)
    }
    return VideoBounds(
        left = (availableWidth - width) / 2,
        top = (availableHeight - height) / 2,
        width = width,
        height = height,
    )
}
