package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.Surface
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import kotlin.math.roundToInt
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Draws frames into a [Surface] the caller owns.
 *
 * The conversion from the decoder's pixel format to RGBA is supplied by the caller through [convert],
 * because it lives in whichever backend produced the frame. This module knows how to put pixels on a
 * Surface and nothing about how they were decoded, which is why the converter arrives as a function
 * rather than as a dependency.
 *
 * ### The Surface belongs to the caller
 *
 * The constructor stores the Surface and never calls `release()` on it, because it never owned it: it
 * belongs to whichever view, window or codec handed it over, and that owner releases it on its own
 * schedule. The one rule the caller has to keep is the other side of the same coin: close this
 * renderer before releasing or replacing the Surface. [close] waits for a lock or a post already in
 * flight, so once it returns nothing here will touch the Surface again. Releasing it first instead
 * leaves this renderer drawing into a dead handle, which on Android is not an exception but a native
 * abort.
 *
 * ### Why it has its own worker
 *
 * Converting a frame on the CPU and pushing it through a software canvas costs milliseconds, and at
 * 1080p enough of them that doing it inside [present] starves everything: the presentation schedule
 * slips, and with the process saturated the audio feeder cannot keep the device fed either. So
 * [present] hands the frame to a worker and returns at once, which is what the renderer contract
 * allows for exactly this reason.
 *
 * Only the newest frame is kept. When the renderer cannot keep up, a new frame replaces the one still
 * waiting rather than a queue building, which is the right trade for a slow renderer: the engine's
 * schedule stays intact and the picture updates as often as this renderer manages, instead of the
 * whole player being dragged down to its speed. The one slot is written out by hand rather than taken
 * from a conflated channel, because a conflated channel discards the displaced element silently, and a
 * silently discarded frame is a decoder buffer that is never closed and never counted. Owning the slot
 * means the displaced frame is closed and counted, which is both correct and visible in
 * [supersededFrames].
 *
 * ### One seam for Android graphics
 *
 * Every `Surface`, `Canvas` and `Bitmap` call sits behind [CanvasTarget], and everything above that
 * seam is ordinary Kotlin: the ownership rules, the byte validation, the swizzle and the geometry. That
 * is what lets the whole of this class be tested on a development machine, where the Android graphics
 * classes exist as stubs that throw the moment they are called. The real Surface is proved separately,
 * once, by a device test.
 *
 * ### The rules this obeys
 *
 * The frame belongs to this renderer from the moment [present] is called and is closed exactly once,
 * including when it is superseded before being drawn, when conversion fails, and when the renderer is
 * closed while it is still in hand. Losing the Surface is an event and not an exception: the renderer
 * refuses frames while it is gone, counts them, says so once through [events], and starts drawing again
 * by itself when a lock next succeeds. It never calls back into the player and never stops playback,
 * because a backgrounded window should not stop the sound.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class AndroidSurfaceVideoRenderer internal constructor(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
    /** Where finished pictures go. Production locks a real Surface; a host test records the calls. */
    private val target: CanvasTarget,
    /** Present only for renderer generations that can negotiate direct MediaCodec output. */
    private val codecTarget: MediaCodecSurfaceTarget? = null,
    /** A separate UI layer used when MediaCodec owns the video Surface. */
    private val overlayConsumer: ((SubtitleOverlay?) -> Unit)? = null,
    /** Keeps the view geometry in step with both direct and software decoder output. */
    private val geometryConsumer: ((VideoSize, Int) -> Unit)? = null,
) : VideoRenderer {

    /**
     * The renderer as a caller builds it: pictures are drawn into [surface] from this renderer's own
     * thread.
     *
     * [surface] is stored and never released here. Close this renderer before releasing or replacing
     * it.
     *
     * @param convert converts a frame to tightly packed RGBA, one byte per component, no row padding.
     */
    public constructor(
        surface: Surface,
        convert: (VideoFrame) -> ByteArray,
    ) : this(convert = convert, target = SurfaceCanvasTarget(surface))

    /**
     * Builds a renderer before its display Surface exists.
     *
     * Attach this renderer before opening media so its paired MediaCodec factory participates in
     * decoder selection, then forward every Surface lifecycle change through [setSurface]. Software
     * frames still use [convert] as a fallback. [onOverlay] should draw into a separate view above the
     * Surface because MediaCodec owns the video Surface while the direct path is active.
     */
    public constructor(
        convert: (VideoFrame) -> ByteArray,
        onOverlay: (SubtitleOverlay?) -> Unit,
        onVideoGeometry: (VideoSize, Int) -> Unit = { _, _ -> },
    ) : this(
        convert = convert,
        targets = AndroidSurfaceTargets(null, onVideoGeometry),
        overlayConsumer = onOverlay,
    )

    private constructor(
        convert: (VideoFrame) -> ByteArray,
        targets: AndroidSurfaceTargets,
        overlayConsumer: ((SubtitleOverlay?) -> Unit)? = null,
    ) : this(
        convert = convert,
        target = targets.canvas,
        codecTarget = targets.codec,
        overlayConsumer = overlayConsumer,
        geometryConsumer = targets.geometryConsumer,
    )

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)

    /** The single frame waiting to be drawn. Newest wins, and the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /** The overlay to composite above the picture. Written by the engine, read by the worker. */
    private val overlay = atomic<SubtitleOverlay?>(null)

    /** The ruling scale mode; written by the engine, read at each draw. */
    private val scaleMode = atomic(io.github.yuroyami.kiteplayer.VideoScale.Fit)

    /** The ruling framing controls, under the same ownership as the scale mode. */
    private val videoTransform = atomic(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    /**
     * The engine's picture controls as Android's colour-matrix convention (offsets 0..255), or
     * null for neutral. Baked here, once per setting; the drawing thread hands the CURRENT value
     * to the target before each frame, and the target rebuilds its paint filter only on change.
     */
    private val videoColorMatrix = atomic<FloatArray?>(null)


    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** True between a lost Surface and the next successful post, so each transition is reported once. */
    private val surfaceIsLost = atomic(false)

    /**
     * The last few transitions are replayed, so a collector that attaches after the Surface went away
     * still learns that it did. A renderer that only ever spoke to whoever was already listening would
     * report surface loss to nobody in the one case it matters, which is a loss that happens at startup.
     */
    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /**
     * The ARGB pixels of the frame being drawn, kept between frames.
     *
     * A 1080p frame is 8.3 MB of `Int`, so allocating one per frame is 500 MB/s of garbage for the
     * collector to walk. It is replaced only when the pixel count changes, which happens when the track
     * changes and not otherwise. Only the worker touches it, and [close] releases it after joining that
     * worker, so a draw already running always finishes with the buffer it started with.
     */
    private var argb: IntArray = EMPTY_ARGB

    /**
     * The drawing thread, held so [close] can end it.
     *
     * `newSingleThreadContext` starts a real thread, and closing the dispatcher is the only thing that
     * stops it. A renderer that dropped this on the floor would leak one thread per instance, which for
     * a player that reopens its output on every track change is a thread per track.
     */
    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-surface-draw")

    private val worker = CoroutineScope(dispatcher + SupervisorJob())

    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                drawPending()
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() closed the signal channel. That is the ordinary way out of this loop, not a fault.
        }
    }

    /** Frames whose picture was posted to the Surface. */
    public val presentedFrames: Long get() = presented.value

    /**
     * Frames replaced in the waiting slot by a newer one before they could be drawn.
     *
     * A non-zero count here means this renderer is the bottleneck, not the decoder and not the clock.
     * At 1080p it will be, because both the conversion and the draw are on the CPU. That is what a
     * hardware renderer fixes.
     */
    public val supersededFrames: Long get() = superseded.value

    /**
     * Frames that reached no Surface for a reason other than being superseded: a conversion that
     * failed or returned the wrong number of bytes, a frame refused while the Surface was gone, a lock
     * or a post the Surface would not give, a draw that threw, or a frame still in hand when the
     * renderer closed.
     */
    public val failedFrames: Long get() = failed.value

    override fun videoDecoderFactories(): List<VideoDecoderFactory> =
        codecTarget?.let { listOf(MediaCodecVideoDecoderFactory(it)) }.orEmpty()

    /** MediaCodec buffers go straight to the Surface; every other format uses the software fallback. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        if (codecTarget != null) setOf(HwSurfaceKind.MediaCodecBuffer) else emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean =
        format != PlayerPixelFormat.Opaque || codecTarget != null

    /**
     * Queues [frame] for drawing and returns immediately.
     *
     * Returns true because the frame was accepted for presentation, which is what the engine is asking.
     * Whether it is ultimately drawn or replaced by a newer one is this renderer's business, and is
     * reported through [presentedFrames] and [supersededFrames]. False means this frame will never be
     * drawn: the renderer is closed, or the Surface is gone. The frame is closed either way.
     */
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        if (frame is DirectSurfaceVideoFrame) {
            if (frame.target !== codecTarget) {
                frame.close()
                failFrame("a MediaCodec frame belongs to a different Surface target")
                return false
            }
            val accepted = frame.renderAt(targetNanos) { rendered ->
                if (rendered) {
                    presented.incrementAndGet()
                    noteSurfaceAvailable()
                } else if (!closed.value) {
                    failed.incrementAndGet()
                }
            }
            if (!accepted) {
                failWithLostSurface("there is no live Surface for the MediaCodec frame")
            }
            return accepted
        }
        if (!targetIsValid()) {
            frame.close()
            failWithLostSurface("the Surface is no longer valid")
            return false
        }
        // Replace whatever was waiting. The displaced frame is closed and counted here, because nothing
        // else will ever see it.
        val displaced = pending.getAndSet(frame)
        if (displaced != null) {
            displaced.close()
            superseded.incrementAndGet()
        }
        // Read `closed` again, after the store. close() writes it before it drains the slot, so between
        // the two of them at least one sees the other's work and the frame is never left in the slot
        // with no worker alive to take it.
        if (closed.value) {
            drainPending()
            return false
        }
        signal.trySend(Unit)
        return true
    }

    /** Draws whatever is waiting, if anything. Worker thread only. */
    private fun drawPending() {
        val frame = pending.getAndSet(null) ?: return
        val size = frame.size
        val rotation = quarterTurn(frame.rotationDegrees)
        geometryConsumer?.invoke(size, rotation)
        val converted = try {
            convert(frame)
        } catch (failure: Throwable) {
            failFrame(failure.message ?: "the converter failed")
            null
        } finally {
            // Ownership ends here. Everything below works on bytes this renderer owns.
            frame.close()
        }
        val picture = converted?.let { swizzle(it, size) } ?: return
        draw(picture, size, rotation)
    }

    /**
     * Turns tightly packed RGBA bytes into the ARGB integers a bitmap wants, or counts the frame as
     * failed and returns null.
     *
     * The two orders are easy to confuse and the mistake is invisible in a grey test pattern: Android
     * bitmaps are ARGB integers, whose bytes on a little-endian machine are stored BGRA, while the
     * converter hands over RGBA in address order. Reading the bytes as an integer therefore swaps red
     * and blue, and the picture comes out with skies orange and skin blue. Building each integer from
     * named components instead cannot be got wrong by accident, and the byte order of the machine stops
     * mattering.
     *
     * The size check is the other half. A converter that returns fewer bytes than the frame needs is a
     * bug in the converter, and the choice here is between a typed failure and a picture drawn from
     * whatever was in the buffer beyond the end of the data. Anything other than exactly one tightly
     * packed RGBA quad per pixel is counted, reported and refused, and no partial picture is ever
     * drawn.
     *
     * The alpha byte is not read. Video frames are opaque, the canvas underneath was cleared to opaque
     * black, and a converter that writes a zero alpha would otherwise draw nothing at all onto it. This
     * is the same choice the Apple fallback makes when it asks Core Graphics to skip the alpha channel.
     */
    private fun swizzle(rgba: ByteArray, size: VideoSize): IntArray? {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            failFrame("a ${width}x$height frame has no pixels to draw")
            return null
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > Int.MAX_VALUE.toLong()) {
            failFrame("a ${width}x$height frame is larger than one buffer can hold")
            return null
        }
        val required = pixels * RGBA_BYTES_PER_PIXEL
        if (rgba.size.toLong() != required) {
            failFrame("the converter returned ${rgba.size} bytes for a ${width}x$height frame, which needs $required")
            return null
        }

        val count = pixels.toInt()
        val buffer = argbBuffer(count)
        var at = 0
        for (index in 0 until count) {
            val red = rgba[at].toInt() and 0xFF
            val green = rgba[at + 1].toInt() and 0xFF
            val blue = rgba[at + 2].toInt() and 0xFF
            buffer[index] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
            at += RGBA_BYTES_PER_PIXEL.toInt()
        }
        return buffer
    }

    /** The reusable ARGB buffer, replaced only when the pixel count changes. Worker thread only. */
    private fun argbBuffer(pixels: Int): IntArray {
        val existing = argb
        if (existing.size == pixels) return existing
        val replacement = IntArray(pixels)
        argb = replacement
        return replacement
    }

    /**
     * Locks the Surface, draws one picture and always gives the canvas back. Worker thread only.
     *
     * The `finally` is the load-bearing part. A locked canvas holds a buffer that belongs to the
     * Surface, and a lock that is not posted is never released: the next lock blocks or fails, and the
     * picture freezes on whatever was last drawn. So a draw that throws still reaches the post, and only
     * then is the failure counted.
     *
     * Losing the Surface is not a failure of the renderer. It is counted against the frame, reported
     * once as a transition, and then the worker carries on: the next lock that succeeds says so and
     * drawing resumes. Nothing here calls the player.
     */
    private fun draw(picture: IntArray, size: VideoSize, rotationDegrees: Int) {
        if (!targetIsValid()) {
            failWithLostSurface("the Surface went away before a canvas could be locked")
            return
        }
        val canvas = try {
            target.lock()
        } catch (refusal: Throwable) {
            failWithLostSurface(refusal.message ?: "the Surface refused a canvas")
            return
        }
        if (canvas == null) {
            failWithLostSurface("the Surface refused a canvas")
            return
        }

        var drawFailure: Throwable? = null
        var postFailure: Throwable? = null
        try {
            // Everything outside the picture is black, and opaquely so: the buffer that comes back from
            // a lock holds whatever was drawn into it two frames ago, and a letterbox that is not
            // cleared shows it.
            canvas.clearToBlack()
            target.setVideoColorMatrix(videoColorMatrix.value)
            val layout = frameLayout(
                canvas.width, canvas.height, size, rotationDegrees, scaleMode.value,
                videoTransform.value,
            )
            if (layout == null) {
                drawFailure = IllegalStateException(
                    "a ${size.width}x${size.height} frame has no place on a ${canvas.width}x${canvas.height} canvas",
                )
            } else {
                canvas.drawFrame(picture, size.width, size.height, layout)
                overlay.value?.let { active ->
                    if (active.images.isNotEmpty()) drawOverlay(canvas, active, layout)
                }
            }
        } catch (failure: Throwable) {
            drawFailure = failure
        } finally {
            try {
                target.post(canvas)
            } catch (failure: Throwable) {
                postFailure = failure
            }
        }

        // A post that throws is the Surface going away underneath a draw that had already started, so it
        // is reported as the loss it is rather than as a fault of this renderer.
        if (postFailure != null) {
            failWithLostSurface(postFailure.message ?: "the Surface refused the finished picture")
            return
        }
        if (drawFailure != null) {
            failFrame(drawFailure.message ?: "drawing the picture failed")
            return
        }
        presented.incrementAndGet()
        noteSurfaceAvailable()
    }

    /**
     * Draws the overlay's images mapped by the same transform the picture used, so subtitles
     * stay glued to the video through every letterbox. Overlay coordinates are the engine's
     * video-display space ([SubtitleOverlay.viewportWidth/Height]).
     */
    private fun drawOverlay(canvas: TargetCanvas, active: SubtitleOverlay, layout: FrameLayout) {
        if (active.viewportWidth <= 0 || active.viewportHeight <= 0) return
        val scaleX = layout.width.toFloat() / active.viewportWidth
        val scaleY = layout.height.toFloat() / active.viewportHeight
        for ((imageIndex, image) in active.images.withIndex()) {
            canvas.drawOverlayImage(
                rgba = image.bitmap.pixels,
                width = image.bitmap.width,
                height = image.bitmap.height,
                left = layout.left + image.x * scaleX,
                top = layout.top + image.y * scaleY,
                drawWidth = image.bitmap.width * scaleX,
                drawHeight = image.bitmap.height * scaleY,
                contentHash = active.contentHash,
                imageIndex = imageIndex,
            )
        }
    }

    /** [CanvasTarget.isValid] must never be the reason a frame is lost, so a throwing one reads false. */
    private fun targetIsValid(): Boolean = try {
        target.isValid()
    } catch (_: Throwable) {
        false
    }

    /** Counts the frame and reports the loss, once per transition. */
    private fun failWithLostSurface(detail: String) {
        failed.incrementAndGet()
        if (surfaceIsLost.compareAndSet(expect = false, update = true)) {
            eventFlow.tryEmit(RendererEvent.SurfaceLost(detail))
        }
    }

    /** The first post to succeed after a loss says so, once. */
    private fun noteSurfaceAvailable() {
        if (surfaceIsLost.compareAndSet(expect = true, update = false)) {
            eventFlow.tryEmit(RendererEvent.SurfaceAvailable)
        }
    }

    /** Counts the frame and reports why. The Surface is fine; this frame was not. */
    private fun failFrame(detail: String) {
        failed.incrementAndGet()
        eventFlow.tryEmit(RendererEvent.Failed(detail))
    }

    /** Closes and counts a frame nobody will draw. */
    private fun drainPending() {
        val stranded = pending.getAndSet(null) ?: return
        stranded.close()
        failed.incrementAndGet()
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        scaleMode.value = mode
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        videoColorMatrix.value = if (adjustments.isIdentity) {
            null
        } else {
            // The engine's unit-domain law respelled into Android's convention: the translation
            // column moves to the 0..255 domain, everything else is the same matrix.
            adjustments.toColorMatrix().also { values ->
                values[4] *= 255f
                values[9] *= 255f
                values[14] *= 255f
                values[19] *= 255f
            }
        }
        // Applied when the next frame draws. A PAUSED picture keeps its old colours until then:
        // this renderer holds no drawn-frame copy to repaint, the same recorded limit as its
        // paused-overlay behaviour (KPKMP 17.11, SOL-R1 family). KiteVideo repaints immediately.
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        videoTransform.value = transform
        // Applied at the next drawn frame, the same recorded paused-picture limit as above.
    }

    /**
     * Stores the overlay for the worker to composite above every following picture. The engine
     * publishes on cue edges, so a cue appears with the next frame drawn after it, at most one
     * frame interval late, which at 30 fps is inside anyone's reading reaction.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        this.overlay.value = overlay
        val external = overlayConsumer
        if (external != null) {
            external(overlay)
        } else {
            signal.trySend(Unit)
        }
    }

    /**
     * Replaces the display Surface without replacing this renderer or its paired decoder.
     *
     * Passing null synchronously fences software canvas work and MediaCodec output release before it
     * returns, which makes it safe to call from `SurfaceHolder.Callback.surfaceDestroyed`.
     */
    public fun setSurface(surface: Surface?) {
        val directTarget = codecTarget
            ?: throw IllegalStateException("this renderer was built with a test CanvasTarget")
        if (closed.value) return
        runCatching { directTarget.update(surface) }
            .onFailure { failure ->
                eventFlow.tryEmit(
                    RendererEvent.Failed(
                        failure.message ?: "MediaCodec could not switch its output Surface",
                    ),
                )
            }
        val switching = target as? SwitchingSurfaceCanvasTarget ?: return
        runBlocking {
            withContext(dispatcher) { switching.refresh() }
        }
    }

    /**
     * Stops drawing and gives everything back except the Surface, which was never this renderer's.
     *
     * The order is the whole point, and every step is there because leaving it out loses something:
     * mark closed first so [present] stops accepting frames, close the signal so the worker's wait ends,
     * then cancel and join the worker so a lock or a post already in flight finishes and the frame it
     * was drawing is closed. Only then is the slot drained, which is final because nothing is left
     * running to refill it, and only then are the pixel buffers and the drawing thread released.
     *
     * This blocks the caller until the worker is done, and that is safe from any thread including the
     * one that runs the user interface: every canvas call this renderer makes is made on its own
     * private thread, so nothing it waits for can be waiting on the caller.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        codecTarget?.let { directTarget ->
            directTarget.clearGeometryConsumer()
            runCatching { directTarget.update(null) }
        }
        overlayConsumer?.invoke(null)
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        drainPending()
        argb = EMPTY_ARGB
        target.release()
        dispatcher.close()
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL: Long = 4L
        private const val OPAQUE_ALPHA: Int = 0xFF shl 24
        private val EMPTY_ARGB: IntArray = IntArray(0)
    }
}

/** One shared lifecycle target for the direct codec producer and the software Canvas fallback. */
private class AndroidSurfaceTargets(
    initialSurface: Surface?,
    val geometryConsumer: ((VideoSize, Int) -> Unit)? = null,
) {
    val codec = MediaCodecSurfaceTarget(initialSurface, geometryConsumer)
    val canvas: CanvasTarget = SwitchingSurfaceCanvasTarget(codec)
}

/**
 * Lazily swaps the software fallback's Canvas target on the renderer worker.
 *
 * [isValid] only reads the shared lifecycle state. Delegate creation and release happen in [lock] or
 * [refresh], both serialized on the renderer dispatcher, so a Surface callback cannot recycle a
 * bitmap or replace a delegate underneath an active draw.
 */
private class SwitchingSurfaceCanvasTarget(
    private val source: MediaCodecSurfaceTarget,
) : CanvasTarget {
    private var version: Long = Long.MIN_VALUE
    private var delegate: SurfaceCanvasTarget? = null
    private var lockedDelegate: SurfaceCanvasTarget? = null

    override fun isValid(): Boolean = source.snapshot().isDisplayable

    override fun lock(): TargetCanvas? {
        refresh()
        val active = delegate ?: return null
        val canvas = active.lock() ?: return null
        check(lockedDelegate == null) { "a Canvas is already locked" }
        lockedDelegate = active
        return canvas
    }

    override fun post(canvas: TargetCanvas) {
        val active = lockedDelegate ?: error("no Canvas is locked")
        try {
            active.post(canvas)
        } finally {
            lockedDelegate = null
        }
    }

    fun refresh() {
        check(lockedDelegate == null) { "cannot replace a Surface while its Canvas is locked" }
        val snapshot = source.snapshot()
        if (snapshot.version == version) return
        delegate?.release()
        delegate = snapshot.surface?.takeIf { snapshot.isDisplayable }?.let(::SurfaceCanvasTarget)
        version = snapshot.version
    }

    override fun release() {
        check(lockedDelegate == null) { "cannot release a Surface while its Canvas is locked" }
        delegate?.release()
        delegate = null
        version = Long.MIN_VALUE
    }
}

/**
 * The one place Android graphics are reached from.
 *
 * A locked canvas, a bitmap and a Surface cannot be had on a development machine: the classes exist
 * there as stubs whose every method throws. Putting them behind this interface is what lets the frame
 * ownership, the byte validation, the swizzle and the geometry above it be pinned by ordinary host
 * tests, and leaves exactly one implementation that needs a real device to prove.
 *
 * The lock and the post are separate calls rather than one block, deliberately: the renderer owns the
 * rule that a successful lock always reaches a post, and a rule the seam kept would be a rule only the
 * device could test.
 */
internal interface CanvasTarget {

    /** False once the Surface is gone. Asked before every lock, and cheap enough to ask that often. */
    fun isValid(): Boolean

    /** Locks and returns a canvas, or null when the target would not give one. */
    fun lock(): TargetCanvas?

    /**
     * The colour matrix to draw VIDEO pixels through (Android's 4x5, offsets 0..255), or null
     * for none. Applies to [TargetCanvas.drawFrame] only, never to overlay images; the engine
     * hands the current value before each draw and a target rebuilds its filter only on change.
     * Defaulted so the geometry test doubles keep compiling unfiltered.
     */
    fun setVideoColorMatrix(matrix: FloatArray?) {}

    /** Posts whatever was drawn into [canvas] and gives the lock back. */
    fun post(canvas: TargetCanvas)

    /** Releases the drawing storage this target owns. Never the Surface, which belongs to the caller. */
    fun release()
}

/** One locked canvas, for the length of one frame. */
internal interface TargetCanvas {

    /** The canvas size in pixels, which is the output size the picture is fitted to. */
    val width: Int
    val height: Int

    /** Fills the whole canvas with opaque black, so the letterbox is black and last frame's is gone. */
    fun clearToBlack()

    /**
     * Draws [argb] ([sourceWidth] by [sourceHeight] pixels, row major) where [layout] says, turned by
     * [FrameLayout.rotationDegrees] about the destination centre.
     */
    fun drawFrame(argb: IntArray, sourceWidth: Int, sourceHeight: Int, layout: FrameLayout)

    /**
     * Composites one subtitle image above whatever [drawFrame] painted. [rgba] is straight
     * non-premultiplied RGBA per the cue contract; the production target premultiplies on
     * upload and caches by [contentHash] so an unchanged overlay uploads nothing.
     */
    fun drawOverlayImage(
        rgba: ByteArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        contentHash: Long,
        imageIndex: Int,
    )
}

/**
 * The production target: a real [Surface], a real [Canvas] and one reusable bitmap.
 *
 * The bitmap is the expensive object here, so it is kept between frames and replaced only when the
 * frame's stored dimensions change. That replacement is safe without any locking because every call
 * into this class comes from the renderer's single drawing thread, so the previous draw has always
 * finished before the next one asks for a bitmap.
 */
internal class SurfaceCanvasTarget(private val surface: Surface) : CanvasTarget {

    /** Filtered because the picture is almost always scaled, and a nearest sample of a scaled frame shimmers. */
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }

    /**
     * The video's own paint, split from [paint] so the picture controls never touch subtitles.
     * The filter is rebuilt only when the engine hands a DIFFERENT matrix reference, which is
     * once per user setting, not once per frame.
     */
    private val videoPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }
    private var appliedColorMatrix: FloatArray? = null

    override fun setVideoColorMatrix(matrix: FloatArray?) {
        if (matrix === appliedColorMatrix) return
        appliedColorMatrix = matrix
        videoPaint.colorFilter = matrix?.let { android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
    }

    private var bitmap: Bitmap? = null

    /** Reused so that drawing a frame allocates nothing at all. */
    private val destination = RectF()

    /** Uploaded overlay images, keyed by the overlay's contentHash, then by image index. */
    private var overlayHash: Long = Long.MIN_VALUE
    private val overlayBitmaps = mutableListOf<Bitmap>()

    /**
     * The premultiply-on-upload the cue contract prescribes: cue pixels arrive straight, a
     * Canvas needs premultiplied. Done once per contentHash and image index.
     */
    private fun overlayBitmapFor(
        rgba: ByteArray,
        width: Int,
        height: Int,
        contentHash: Long,
        imageIndex: Int,
    ): Bitmap {
        if (contentHash != overlayHash) {
            overlayBitmaps.forEach { it.recycle() }
            overlayBitmaps.clear()
            overlayHash = contentHash
        }
        overlayBitmaps.getOrNull(imageIndex)?.let { return it }
        check(imageIndex == overlayBitmaps.size) { "overlay images must be drawn in index order" }
        val pixels = IntArray(width * height)
        var at = 0
        for (index in pixels.indices) {
            val r = rgba[at].toInt() and 0xFF
            val g = rgba[at + 1].toInt() and 0xFF
            val b = rgba[at + 2].toInt() and 0xFF
            val a = rgba[at + 3].toInt() and 0xFF
            pixels[index] = (a shl 24) or ((r * a / 255) shl 16) or ((g * a / 255) shl 8) or (b * a / 255)
            at += 4
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        overlayBitmaps += bitmap
        return bitmap
    }

    override fun isValid(): Boolean = surface.isValid

    override fun lock(): TargetCanvas? {
        // Null means the whole canvas is redrawn, which it is: every frame clears and refills it.
        val canvas = surface.lockCanvas(null) ?: return null
        return SurfaceTargetCanvas(canvas)
    }

    override fun post(canvas: TargetCanvas) {
        surface.unlockCanvasAndPost((canvas as SurfaceTargetCanvas).canvas)
    }

    override fun release() {
        bitmap?.recycle()
        bitmap = null
        overlayBitmaps.forEach { it.recycle() }
        overlayBitmaps.clear()
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val existing = bitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    private inner class SurfaceTargetCanvas(val canvas: Canvas) : TargetCanvas {

        override val width: Int get() = canvas.width

        override val height: Int get() = canvas.height

        override fun clearToBlack() {
            // SRC rather than the default, so the buffer's old contents are replaced and not blended
            // with. A canvas from a lock is recycled memory, not a fresh page.
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        }

        override fun drawFrame(argb: IntArray, sourceWidth: Int, sourceHeight: Int, layout: FrameLayout) {
            val picture = bitmapFor(sourceWidth, sourceHeight)
            picture.setPixels(argb, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
            destination.set(layout.drawLeft, layout.drawTop, layout.drawRight, layout.drawBottom)
            val saved = canvas.save()
            try {
                // Positive degrees are clockwise on a canvas, whose y axis points down, and the turn is
                // about the destination centre so that the drawn rectangle lands back on it.
                if (layout.rotationDegrees != 0) {
                    canvas.rotate(layout.rotationDegrees.toFloat(), layout.centerX, layout.centerY)
                }
                canvas.drawBitmap(picture, null, destination, videoPaint)
            } finally {
                canvas.restoreToCount(saved)
            }
        }

        override fun drawOverlayImage(
            rgba: ByteArray,
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            drawWidth: Float,
            drawHeight: Float,
            contentHash: Long,
            imageIndex: Int,
        ) {
            val bitmap = overlayBitmapFor(rgba, width, height, contentHash, imageIndex)
            destination.set(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, destination, paint)
        }
    }
}

/**
 * Where one picture lands on the canvas, in canvas pixels.
 *
 * The rectangle named by [left], [top], [right] and [bottom] is where the picture ends up on screen,
 * after the turn. The rectangle the bitmap is actually drawn into is [drawLeft] to [drawRight] and
 * [drawTop] to [drawBottom], which for a quarter turn is the same rectangle with its sides exchanged
 * about the same centre: turning that one by [rotationDegrees] about ([centerX], [centerY]) is what
 * lands it exactly on the first one.
 *
 * The drawing rectangle is in fractions of a pixel because the exchange halves an odd side, and a
 * rounded half pixel is a visible seam of stale black down one edge of the picture.
 */
internal data class FrameLayout(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rotationDegrees: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    private val quarterTurned: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    val drawWidth: Float get() = (if (quarterTurned) height else width).toFloat()
    val drawHeight: Float get() = (if (quarterTurned) width else height).toFloat()

    val drawLeft: Float get() = centerX - drawWidth / 2f
    val drawTop: Float get() = centerY - drawHeight / 2f
    val drawRight: Float get() = centerX + drawWidth / 2f
    val drawBottom: Float get() = centerY + drawHeight / 2f
}

/**
 * Fits a frame into a canvas, centred, keeping its shape, or returns null when there is nothing to
 * draw.
 *
 * Three things decide the shape and all three come from the frame rather than from the canvas. The
 * stored width and height are the pixels there are; the pixel aspect ratio says how wide those pixels
 * are meant to be shown, so anamorphic content is not drawn squeezed; and a quarter turn moves that
 * stretch onto the other axis, because the picture's stored width runs vertically afterwards.
 *
 * The fit is integer arithmetic on purpose. Exactly one axis fills the canvas exactly and the other is
 * centred with the remainder split in two, so the letterbox is symmetric to the pixel and the picture
 * never overhangs the canvas by a rounding error.
 */
internal fun frameLayout(
    canvasWidth: Int,
    canvasHeight: Int,
    size: VideoSize,
    rotationDegrees: Int,
    mode: io.github.yuroyami.kiteplayer.VideoScale = io.github.yuroyami.kiteplayer.VideoScale.Fit,
    transform: io.github.yuroyami.kiteplayer.VideoTransform = io.github.yuroyami.kiteplayer.VideoTransform.Identity,
): FrameLayout? {
    if (canvasWidth <= 0 || canvasHeight <= 0) return null
    if (size.width <= 0 || size.height <= 0) return null

    // The core rule for a non-square pixel aspect, borrowed rather than restated so the two cannot
    // drift. A nonsense aspect that scales the width away leaves the stored width, which shows the
    // picture slightly wrong instead of showing nothing at all.
    val displayWidth = size.displayWidth.takeIf { it > 0 } ?: size.width
    val turn = quarterTurn(rotationDegrees)
    val quarterTurned = turn == 90 || turn == 270
    val aspect = transform.aspectOverride
    val contentWidth: Long
    val contentHeight: Long
    if (aspect != null && aspect > 0f && aspect.isFinite()) {
        // The forced aspect describes the picture AS PRESENTED, after the turn, and only its
        // ratio matters to the fit: the same words as the Compose geometry, so no drift.
        contentWidth = (aspect * 100_000f).toLong().coerceAtLeast(1)
        contentHeight = 100_000L
    } else {
        contentWidth = (if (quarterTurned) size.height else displayWidth).toLong()
        contentHeight = (if (quarterTurned) displayWidth else size.height).toLong()
    }

    // Fit keeps the smaller axis ratio and letterboxes; Fill keeps the larger and overhangs the
    // canvas, which the Surface's own bounds crop; Stretch takes the canvas as it is. The pixel
    // aspect and rotation above are already folded into the content shape for all three.
    val fitsByHeight = contentWidth * canvasHeight <= contentHeight * canvasWidth
    val fillAxisFlipped = when (mode) {
        io.github.yuroyami.kiteplayer.VideoScale.Fill -> !fitsByHeight
        else -> fitsByHeight
    }
    var destinationWidth: Int
    var destinationHeight: Int
    if (mode == io.github.yuroyami.kiteplayer.VideoScale.Stretch) {
        destinationWidth = canvasWidth
        destinationHeight = canvasHeight
    } else if (fillAxisFlipped) {
        destinationHeight = canvasHeight
        destinationWidth = (contentWidth * canvasHeight / contentHeight).toInt().coerceAtLeast(1)
    } else {
        destinationWidth = canvasWidth
        destinationHeight = (contentHeight * canvasWidth / contentWidth).toInt().coerceAtLeast(1)
    }
    var left = (canvasWidth - destinationWidth) / 2
    var top = (canvasHeight - destinationHeight) / 2
    // Zoom about the centre, then pan by a fraction of the drawn size, after the fit so both
    // compose with every mode. The overhang is cropped by the canvas bounds like Fill's is.
    if (transform.zoom != 1f) {
        destinationWidth = (destinationWidth * transform.zoom).roundToInt().coerceAtLeast(1)
        destinationHeight = (destinationHeight * transform.zoom).roundToInt().coerceAtLeast(1)
        left = (canvasWidth - destinationWidth) / 2
        top = (canvasHeight - destinationHeight) / 2
    }
    if (transform.panX != 0f) left += (transform.panX * destinationWidth).roundToInt()
    if (transform.panY != 0f) top += (transform.panY * destinationHeight).roundToInt()
    return FrameLayout(
        left = left,
        top = top,
        right = left + destinationWidth,
        bottom = top + destinationHeight,
        rotationDegrees = turn,
    )
}

/**
 * The quarter turn this renderer will actually draw, given a frame's [VideoFrame.rotationDegrees].
 *
 * Only 0, 90, 180 and 270 are drawn. A display matrix can in principle describe any affine transform,
 * and a value that is not a quarter turn comes back as 0, which shows the picture as stored rather than
 * refusing to show it at all: a slightly skewed picture is not worth a black screen. Negative and
 * out-of-range values are normalised first, so a source that reports -90 rather than 270 is drawn the
 * same way.
 */
internal fun quarterTurn(rotationDegrees: Int): Int {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
}
