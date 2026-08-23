package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The connection between a player and [KiteVideo]: it owns the renderer the player draws
 * through and the one snapshot slot the draw phase reads.
 *
 * ```
 * val video = rememberKiteVideoState()
 * val player = remember { KitePlayer.create(PlayerConfig(backends = mobileBackends())) }
 * player.attachRenderer(video.renderer)
 * KiteVideo(video, Modifier.clip(RoundedCornerShape(24.dp)))
 * ```
 *
 * Built outside composition, close [renderer] when done; [rememberKiteVideoState] does that
 * automatically when it leaves composition. Closing is always safe while the player still holds
 * the renderer: a closed renderer refuses frames and the engine counts them, exactly like every
 * other renderer in this library.
 */
public class KiteVideoState internal constructor(
    convert: (VideoFrame) -> ByteArray,
    makeImage: (rgba: ByteArray, width: Int, height: Int) -> FrameImage,
    releaseImages: () -> Unit,
    hardwareRendererFactory: ((publish: (KiteVideoFrame) -> Unit) -> KiteVideoHardwareRenderer?) = { null },
) {
    public constructor() : this(FrameImagePool(), { null })

    internal constructor(
        pool: FrameImagePool,
        hardwareRendererFactory: ((publish: (KiteVideoFrame) -> Unit) -> KiteVideoHardwareRenderer?),
    ) : this(
        convert = { frame -> kiteCodecFrameToRgba(frame) },
        makeImage = { rgba, width, height -> pool.imageFor(rgba, width, height) },
        releaseImages = { pool.release() },
        hardwareRendererFactory = hardwareRendererFactory,
    )

    /**
     * The newest finished frame. LAW 1 OF 17.9 LIVES HERE: this state is read at exactly one
     * site, inside the draw phase of [KiteVideo]. Reading it during composition or layout turns
     * every video frame into a recomposition, which is the difference between smooth and
     * slideshow. Writes come from the renderer's worker thread; the snapshot system carries the
     * invalidation to the draw scope.
     */
    internal val frame: MutableState<KiteVideoFrame?> = mutableStateOf(null)

    /**
     * The active subtitle overlay, under the same law as [frame]: read only inside the draw
     * phase of [KiteVideo]. It changes on cue edges, so its invalidations are rare.
     */
    internal val overlay: MutableState<KiteVideoOverlay?> = mutableStateOf(null)

    /**
     * How the picture occupies the component, driven by the engine through the renderer's
     * `setScaleMode`. Same draw-phase law as [overlay]: it changes on a user's setting, so its
     * invalidations are rarer still.
     */
    internal val scaleMode: MutableState<io.github.yuroyami.kiteplayer.VideoScale> =
        mutableStateOf(io.github.yuroyami.kiteplayer.VideoScale.Fit)

    /**
     * The engine's framing controls, under the same draw-phase law as [scaleMode]: read only
     * inside the draw phase, invalidating on a user's setting and nothing else.
     */
    internal val transform: MutableState<io.github.yuroyami.kiteplayer.VideoTransform> =
        mutableStateOf(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    /**
     * The engine's picture controls, pre-baked into the one [androidx.compose.ui.graphics.ColorFilter]
     * the draw phase applies to the video image (never to subtitles). Null is neutral, and the
     * usual case, so normal playback pays nothing. Baked HERE, on the publishing thread, because
     * a ColorFilter is an immutable value and building it once per SETTING beats building it once
     * per FRAME by four orders of magnitude in event count.
     */
    internal val videoColorFilter: MutableState<androidx.compose.ui.graphics.ColorFilter?> =
        mutableStateOf(null)

    /**
     * How the draw phase samples the picture when it scales it, under the same law as
     * [videoColorFilter]: one setting, not one frame.
     *
     * What each value MEANS is not the same everywhere, and the difference is worth stating
     * because it decides where the work has to live. On Skia (desktop, iOS, web) High is a real
     * cubic resampler. On Android it is not: Compose maps everything above None onto the single
     * `isFilterBitmap` flag, so High and Low are the same bilinear. The Android GPU tier
     * therefore does its own enlargement in the blit, and this value changes nothing there.
     */
    internal val filterQuality: MutableState<androidx.compose.ui.graphics.FilterQuality> =
        mutableStateOf(androidx.compose.ui.graphics.FilterQuality.Low)

    /**
     * A dropped Window metric needs a newly recorded Compose draw before a later exact metric can
     * prove completion. This state is observed only through [acquireFrameForDraw], so incrementing
     * it invalidates the draw scope without recomposition or layout.
     */
    private val completionProofGeneration: MutableState<Long> = mutableStateOf(0L)

    /**
     * A hardware image stays leased while Compose records it and while Android's RenderThread can
     * still sample it. On Android the latter boundary is the owning Window's exact VSYNC-matched
     * GPU-completion metric, not display-list submission or a guessed number of newer frames. If
     * that proof never arrives, keeping the lease applies bounded decoder backpressure; reusing
     * pixels with no proven consumer fence is never allowed.
     */
    private val frameFence = SynchronizedObject()
    private val drawingFrames = mutableMapOf<KiteVideoFrame, Int>()
    private val pendingCommits = mutableMapOf<KiteVideoFrame, Int>()
    private val committedFrames = mutableMapOf<Any, KiteVideoFrame>()
    private var publishedFrame: KiteVideoFrame? = null
    private var gpuCompletedFrames = 0L
    private var firstGpuCompletionNanos: Long? = null
    private var lastGpuCompletionNanos: Long? = null

    internal val hardwareRenderer: KiteVideoHardwareRenderer? = hardwareRendererFactory(::publishFrame)

    private val videoRenderer = KiteVideoRenderer(
        convert = convert,
        makeImage = makeImage,
        publish = ::publishFrame,
        releaseImages = releaseImages,
        releasePublishedFrames = ::releaseFramesAfterRendererClosed,
        publishOverlay = { newest -> overlay.value = newest },
        publishScaleMode = { mode -> scaleMode.value = mode },
        publishTransform = { newest -> transform.value = newest },
        publishAdjustments = { adjustments ->
            videoColorFilter.value = if (adjustments.isIdentity) {
                null
            } else {
                // The engine's one matrix law, respelled into Compose's convention: same 4x5
                // rows, translation column in the 0..255 domain instead of the unit domain.
                val unit = adjustments.toColorMatrix()
                val values = unit.copyOf()
                values[4] *= 255f
                values[9] *= 255f
                values[14] *= 255f
                values[19] *= 255f
                androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(values),
                )
            }
        },
        publishFilterQuality = { quality -> filterQuality.value = quality },
        hardwareRenderer = hardwareRenderer,
    )

    /**
     * One state may feed more than one KiteVideo node. The shared renderer therefore targets the
     * pixel envelope of every active node, rather than whichever node happened to lay out last.
     * Node callbacks never write Compose state, and unchanged aggregate sizes never reach the GPU.
     */
    private val viewports = KiteVideoViewportBook(videoRenderer::setViewport)

    internal fun updateViewport(owner: Any, width: Int, height: Int) {
        viewports.update(owner, width, height)
    }

    internal fun removeViewport(owner: Any) {
        viewports.remove(owner)
    }

    internal fun publishFrame(newest: KiteVideoFrame?) {
        val retire = mutableListOf<KiteVideoFrame>()
        synchronized(frameFence) {
            val previous = publishedFrame
            publishedFrame = newest
            frame.value = newest
            if (
                previous != null &&
                previous !== newest &&
                previous !in committedFrames.values &&
                previous !in drawingFrames &&
                previous !in pendingCommits
            ) {
                retire += previous
            }
        }
        retire.forEach(KiteVideoFrame::close)
    }

    /**
     * The draw phase's only frame-state read. Reading and marking the lease in one critical
     * section prevents publication from retiring the image between those two operations.
     */
    internal fun acquireFrameForDraw(allowCommitFence: Boolean = true): KiteVideoFrame? {
        completionProofGeneration.value
        return synchronized(frameFence) {
            frame.value
                ?.takeIf { allowCommitFence || !it.requiresCommitFence }
                ?.also { drawingFrames[it] = drawingFrames.getOrElse(it) { 0 } + 1 }
        }
    }

    internal fun requestCompletionProofDraw() {
        completionProofGeneration.value += 1L
    }

    /**
     * Called from [KiteVideo]'s draw phase after this exact image was (or was not) recorded.
     * A recorded image moves from the synchronous draw lease to an asynchronous commit lease.
     */
    internal fun frameDrawFinished(drawn: KiteVideoFrame, recorded: Boolean) {
        var retire = false
        synchronized(frameFence) {
            val drawing = drawingFrames[drawn]
            if (drawing != null) {
                if (drawing == 1) drawingFrames.remove(drawn) else drawingFrames[drawn] = drawing - 1
            }
            if (recorded && drawn.requiresCommitFence) {
                pendingCommits[drawn] = pendingCommits.getOrElse(drawn) { 0 } + 1
            }
            retire = drawn !== publishedFrame &&
                drawn !in committedFrames.values &&
                drawn !in drawingFrames &&
                drawn !in pendingCommits
        }
        if (retire) drawn.close()
    }

    /**
     * Called only after the platform proves GPU completion for the display list containing
     * [drawn]. Null means a completed draw with no leased image replaced the previous display list.
     */
    internal fun frameCommitted(drawn: KiteVideoFrame?) =
        frameCommitted(DEFAULT_COMMIT_OWNER, drawn, completionVsyncNanos = null)

    /** Each KiteVideo node owns a distinct persistent display list in the shared Window. */
    internal fun frameCommitted(
        owner: Any,
        drawn: KiteVideoFrame?,
        completionVsyncNanos: Long? = null,
    ) {
        val retire = mutableListOf<KiteVideoFrame>()
        synchronized(frameFence) {
            if (drawn != null) {
                val pending = pendingCommits[drawn] ?: return
                if (pending == 1) pendingCommits.remove(drawn) else pendingCommits[drawn] = pending - 1
                if (
                    completionVsyncNanos != null &&
                    drawn.requiresCommitFence &&
                    !drawn.gpuCompletionCounted
                ) {
                    drawn.gpuCompletionCounted = true
                    gpuCompletedFrames += 1L
                    firstGpuCompletionNanos = firstGpuCompletionNanos
                        ?.coerceAtMost(completionVsyncNanos)
                        ?: completionVsyncNanos
                    lastGpuCompletionNanos = lastGpuCompletionNanos
                        ?.coerceAtLeast(completionVsyncNanos)
                        ?: completionVsyncNanos
                }
            }
            val previous = committedFrames[owner]
            if (drawn == null) committedFrames.remove(owner) else committedFrames[owner] = drawn
            if (
                previous != null &&
                previous !== drawn &&
                previous !== publishedFrame &&
                previous !in committedFrames.values &&
                previous !in drawingFrames &&
                previous !in pendingCommits
            ) {
                retire += previous
            }
        }
        retire.forEach(KiteVideoFrame::close)
    }

    /**
     * Final renderer teardown has already disconnected and destroyed the producer queue. At that
     * point no slot can be overwritten, so even a commit callback lost with a detached view can be
     * released without weakening the draw-time fence.
     */
    private fun releaseFramesAfterRendererClosed() {
        val retire = synchronized(frameFence) {
            buildSet {
                publishedFrame?.let(::add)
                addAll(committedFrames.values)
                addAll(drawingFrames.keys)
                addAll(pendingCommits.keys)
            }.also {
                publishedFrame = null
                committedFrames.clear()
                drawingFrames.clear()
                pendingCommits.clear()
                frame.value = null
            }
        }
        retire.forEach(KiteVideoFrame::close)
    }

    /** Attach this to the player. Close it when the video surface is done (or use [rememberKiteVideoState]). */
    public val renderer: VideoRenderer get() = videoRenderer

    /** Frames whose picture was published for drawing. */
    public val presentedFrames: Long get() = videoRenderer.presentedFrames

    /** Exact Android GPU-completion evidence used by the instrumented performance gate. */
    internal val gpuCompletionStats: KiteVideoGpuCompletionStats
        get() = synchronized(frameFence) {
            KiteVideoGpuCompletionStats(
                frames = gpuCompletedFrames,
                firstVsyncNanos = firstGpuCompletionNanos,
                lastVsyncNanos = lastGpuCompletionNanos,
            )
        }

    private companion object {
        val DEFAULT_COMMIT_OWNER = Any()
    }

    /** Frames replaced by a newer one before they could be converted. */
    public val supersededFrames: Long get() = videoRenderer.supersededFrames

    /** Frames that published nothing for a reason other than being superseded. */
    public val failedFrames: Long get() = videoRenderer.failedFrames

    /**
     * The measured CPU cost of the software path per published frame. On an emulator this is
     * provisional stage evidence; the numbers that count are re-taken on devices at S3's exit.
     */
    public val frameCost: KiteVideoFrameCost get() = videoRenderer.costSnapshot()

}

/** A unique hardware image is counted once even when several KiteVideo nodes draw it. */
internal data class KiteVideoGpuCompletionStats(
    val frames: Long,
    val firstVsyncNanos: Long?,
    val lastVsyncNanos: Long?,
) {
    val spanNanos: Long
        get() = if (frames > 1L && firstVsyncNanos != null && lastVsyncNanos != null) {
            (lastVsyncNanos - firstVsyncNanos).coerceAtLeast(0L)
        } else {
            0L
        }
}

/**
 * Remembers one [KiteVideoState] and closes its renderer when it leaves composition.
 */
@Composable
public fun rememberKiteVideoState(): KiteVideoState {
    val state = remember { KiteVideoState() }
    DisposableEffect(state) {
        onDispose { state.renderer.close() }
    }
    return state
}

/** Pure multi-node viewport ownership, split out so lifecycle and deduplication stay host-testable. */
internal class KiteVideoViewportBook(
    private val apply: (width: Int, height: Int, scale: Float) -> Unit,
) {
    private val fence = SynchronizedObject()
    private val byOwner = mutableMapOf<Any, Viewport>()
    private var applied: Viewport? = null

    fun update(owner: Any, width: Int, height: Int) {
        synchronized(fence) {
            val changed = if (width > 0 && height > 0) {
                val viewport = Viewport(width, height)
                byOwner.put(owner, viewport) != viewport
            } else {
                byOwner.remove(owner) != null
            }
            if (changed) applyAggregateIfChanged()
        }
    }

    fun remove(owner: Any) {
        synchronized(fence) {
            if (byOwner.remove(owner) != null) applyAggregateIfChanged()
        }
    }

    /** Size changes are rare UI events, so serialize emission with ownership to preserve ordering. */
    private fun applyAggregateIfChanged() {
        val next = if (byOwner.isEmpty()) {
            Viewport.Zero
        } else {
            Viewport(
                width = byOwner.values.maxOf(Viewport::width),
                height = byOwner.values.maxOf(Viewport::height),
            )
        }
        if (next == applied) return
        apply(next.width, next.height, PHYSICAL_PIXEL_SCALE)
        applied = next
    }

    private data class Viewport(val width: Int, val height: Int) {
        companion object {
            val Zero = Viewport(0, 0)
        }
    }

    private companion object {
        const val PHYSICAL_PIXEL_SCALE = 1f
    }
}
