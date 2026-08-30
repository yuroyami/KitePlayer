package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.AppKit.NSImage
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextRotateCTM
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI

/**
 * Draws frames into an [AppKitWindow].
 *
 * The conversion from the decoder's pixel format to RGBA is supplied by the caller through [convert],
 * because it lives in whichever backend produced the frame: the software path in `kiteplayer-ffmpeg`
 * knows how to read that decoder's planes, and this module must not depend on it.
 *
 * ### Why it has its own worker
 *
 * Converting a frame on the CPU and building an image from it costs milliseconds, and at 1080p enough
 * of them that doing it inside [present] starves everything: the presentation schedule slips, and with
 * the process saturated the audio feeder cannot keep the device fed either. Measured that way, a 1080p
 * clip produced 700 audio underruns and showed 11 frames out of 300.
 *
 * So [present] hands the frame to a worker and returns at once, which is what the renderer
 * contract allows for exactly this reason. Only the newest frame is kept: when the
 * renderer cannot keep up, a new frame replaces the one still waiting rather than a queue building. That
 * is the right trade for a slow renderer. The engine's schedule stays intact and the picture updates as
 * often as the renderer manages, instead of the whole player being dragged down to its speed.
 *
 * The one-slot handover is written out by hand rather than using a conflated channel, and that is not
 * fussiness. A conflated channel discards the displaced element silently, so the frame it drops is never
 * closed and never counted: the first version of this class leaked 291 of 300 frames that way, and
 * reported having drawn 9 with nothing dropped. Owning the slot means the displaced frame is closed and
 * counted, which is both correct and visible in [supersededFrames].
 *
 * ### Two slots, not one
 *
 * There is a second slot on the other side of the worker, for the same reason as the first. AppKit can
 * only be touched from the main thread, so a finished image has to be handed over to it, and the main
 * thread is not the renderer's to schedule: it belongs to the user interface and can be busy for as
 * long as it likes. Posting every finished image to it puts unbounded work in a queue nobody drains,
 * and every one of those images holds a full frame of pixels.
 *
 * So the worker stores the finished image in its own latest-only slot and queues at most one delivery
 * block at a time. A newer image replaces the one still waiting, and the block that eventually runs
 * draws whatever is in the slot then, which is the newest picture there is. A slow main thread costs
 * smoothness and one image of memory, never a growing backlog.
 *
 * ### The rules this obeys
 *
 * The frame belongs to this renderer from the moment [present] is called, and is closed exactly once,
 * including when it is superseded before being drawn, when conversion fails, and when the renderer is
 * closed while it is still in hand. AppKit is touched only from the main thread, through
 * `dispatch_async`, and never waited on: blocking the engine on the UI thread is how a player
 * deadlocks, and libmpv's own headers warn about that twice.
 *
 * A frame that carries a [VideoFrame.rotationDegrees] is drawn turned, so a recording made in portrait
 * is not shown on its side. The turn is a second pass over the pixels and is only paid by a clip whose
 * container asks for one.
 *
 * [close] is the one place that blocks, and only on this renderer's own worker. It marks the renderer
 * closed, ends the worker's wait, then waits for a conversion already running to finish before draining
 * both slots and closing the conversion thread. That order is what makes the two drains final: nothing
 * else is left that could put a frame or an image back.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class AppKitVideoRenderer internal constructor(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
    /**
     * Whether [convert] rolls HDR off to standard dynamic range for this frame.
     *
     * This renderer publishes `RendererEvent.ToneMapEngaged` on the strength of this and nothing
     * else. The default is false, the truthful answer for a converter that leaves colour alone:
     * only the converter can tell tone mapping apart from handing HDR through untouched.
     */
    private val toneMapped: (VideoFrame) -> Boolean = { false },
    /** Puts one block on the main queue. A test supplies a queue it drains by hand instead. */
    private val enqueueOnMain: (block: () -> Unit) -> Unit,
    /** Shows a finished image. Called from [enqueueOnMain]'s thread, which in production is the main one. */
    private val showImage: (NSImage) -> Unit,
) : VideoRenderer {

    /**
     * The renderer as a player uses it: finished images go into [window]'s image view, on the main
     * thread, through the main queue.
     *
     * @param convert converts a frame to tightly packed RGBA, one byte per component, no row padding.
     */
    public constructor(
        window: AppKitWindow,
        convert: (VideoFrame) -> ByteArray,
        toneMapped: (VideoFrame) -> Boolean = { false },
    ) : this(
        convert = convert,
        toneMapped = toneMapped,
        enqueueOnMain = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
        showImage = { image -> window.imageView.image = image },
    )

    private val presented = atomic(0L)
    private val failed = atomic(0L)
    private val superseded = atomic(0L)

    /** The single frame waiting to be converted. Newest wins, and the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /** The single finished image waiting for the main thread. Newest wins. */
    private val pendingImage = atomic<NSImage?>(null)

    /** True from the moment a delivery block is queued until that block starts running. */
    private val deliveryQueued = atomic(false)

    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val closed = atomic(false)

    /** The active subtitle overlay; replaced wholesale by [setOverlay]. */
    private val overlaySlot = atomic<SubtitleOverlay?>(null)

    /** Overlay CGImages, rebuilt only when the cue changes. Worker-owned. */
    private val overlayImages = OverlayImageCache(::rgbaImage)

    /** The picture controls, read by the worker on every draw. */
    private val adjustSlot = atomic(io.github.yuroyami.kiteplayer.VideoAdjustments.Identity)

    /** The framing controls, under the same ownership as the picture controls. */
    private val transformSlot = atomic(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /** Published once, not once per frame: the engine latches it anyway, and a flood is noise. */
    private val toneMapAnnounced = atomic(false)

    /**
     * The conversion thread, held so [close] can end it.
     *
     * `newSingleThreadContext` starts a real thread, and closing the dispatcher is the only thing that
     * stops it. A renderer that dropped this on the floor leaked one thread for every instance ever
     * built, which for a player that reopens its output on every track change is a thread per track.
     */
    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-appkit-convert")

    private val worker = CoroutineScope(dispatcher + SupervisorJob())

    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                convertPending()
                // An overlay change during a pause re-composites the retained pixels;
                // a converted frame above already baked the new overlay in.
                if (redrawWanted.getAndSet(false) && pending.value == null) redrawRetained()
                // getAndSet(false) is the whole consumption: the old else-arm
                // blindly wrote false over a request that raced in after the read.
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() closed the signal channel. That is the ordinary way out of this loop, not a fault:
            // an unhandled one used to travel up and kill the coroutine with an exception nobody read.
        }
    }

    /** Frames whose picture reached the window. */
    public val presentedFrames: Long get() = presented.value

    /**
     * Frames replaced by a newer one before they could be drawn, in either slot.
     *
     * A non-zero count here means this renderer is the bottleneck, not the decoder and not the clock.
     * At 1080p it will be, because the conversion is on the CPU. That is what a GPU renderer fixes. A
     * count that grows while the conversion keeps up means the main thread is the slow part instead.
     */
    public val supersededFrames: Long get() = superseded.value

    /**
     * Frames that reached no window for a reason other than being superseded: a conversion that failed,
     * a frame with no pixels in it, or a frame still in hand when the renderer closed.
     */
    public val failedFrames: Long get() = failed.value

    /** Nothing zero-copy here. That is what a Metal renderer is for. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque

    /**
     * Queues [frame] for drawing and returns immediately.
     *
     * Returns true because the frame was accepted for presentation, which is what the engine is asking.
     * Whether it is ultimately drawn or replaced by a newer one is this renderer's business, and is
     * reported through [presentedFrames] and [supersededFrames]. False means the renderer is closed and
     * this frame will never be drawn.
     */
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        // Replace whatever was waiting. The displaced frame is closed and counted here, because
        // nothing else will ever see it.
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

    /** Converts whatever is waiting, if anything, and hands the image on. Worker thread only. */
    private fun convertPending() {
        // close() blocks its caller, which is the UI thread. It must not wait on a
        // conversion this worker had not started yet; close() drains the slot after the join.
        if (closed.value) return
        val frame = pending.getAndSet(null) ?: return
        val width = frame.size.width
        val height = frame.size.height
        val displayWidth = frame.size.displayWidth
        val rotation = quarterTurn(frame.rotationDegrees)
        val image = try {
            if (width <= 0 || height <= 0) {
                null
            } else {
                if (toneMapped(frame) && toneMapAnnounced.compareAndSet(false, true)) {
                    eventFlow.tryEmit(RendererEvent.ToneMapEngaged(transfer = frame.colorSpace.transfer.name))
                }
                val rgba = convert(frame)
                // Retained for paused-overlay re-composites, worker-confined.
                retainedRgba = rgba
                retainedWidth = width
                retainedHeight = height
                retainedDisplayWidth = displayWidth
                retainedRotation = rotation
                makeImage(rgba, width, height, displayWidth, rotation)
            }
        } catch (failure: Throwable) {
            eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "conversion failed"))
            null
        } finally {
            // Ownership ends here. The image holds its own copy of the pixels.
            frame.close()
        }

        if (image == null) {
            failed.incrementAndGet()
            return
        }
        deliver(image)
    }

    /**
     * Hands [image] to the main thread, keeping only the newest.
     *
     * The flag is what bounds the work: it is set when a block is queued and cleared when that block
     * starts, so while one is waiting every further image just replaces the one in the slot.
     */
    private fun deliver(image: NSImage) {
        if (pendingImage.getAndSet(image) != null) superseded.incrementAndGet()
        if (deliveryQueued.compareAndSet(expect = false, update = true)) {
            enqueueOnMain { drawPendingImage() }
        }
    }

    /** Draws whatever is in the image slot. Main thread only. */
    private fun drawPendingImage() {
        // Cleared first. An image stored while this block runs must be able to queue a block of its own,
        // or it would sit in the slot waiting for a delivery that has already happened.
        deliveryQueued.value = false
        val image = pendingImage.getAndSet(null) ?: return
        if (closed.value) {
            // The renderer is closed, so the window is no longer this renderer's to draw into.
            failed.incrementAndGet()
            return
        }
        showImage(image)
        presented.incrementAndGet()
    }

    /** Closes and counts a frame nobody will draw. */
    private fun drainPending() {
        val stranded = pending.getAndSet(null) ?: return
        stranded.close()
        failed.incrementAndGet()
    }

    /**
     * The quarter turn this renderer will actually draw, given a frame's [VideoFrame.rotationDegrees].
     *
     * Only 0, 90, 180 and 270 are drawn. A display matrix can in principle describe any affine
     * transform, and a value that is not a quarter turn comes back as 0, which shows the picture as
     * stored rather than refusing to show it at all: a slightly skewed picture is not worth a black
     * window. Negative and out-of-range values are normalised first, so a source that reports -90
     * rather than 270 is drawn the same way.
     */
    private fun quarterTurn(rotationDegrees: Int): Int {
        val normalised = ((rotationDegrees % 360) + 360) % 360
        return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
    }

    /**
     * Builds an image from RGBA bytes, turned by [rotationDegrees] if the container asked for it.
     *
     * A bitmap context is used rather than a data provider so that Core Graphics copies the pixels
     * immediately. The alternative keeps a reference to the caller's buffer and requires it to outlive
     * the image, which across a `dispatch_async` boundary is a use-after-free waiting to happen.
     *
     * [rotationDegrees] is already a quarter turn, from [quarterTurn].
     */
    private fun makeImage(
        rgba: ByteArray,
        width: Int,
        height: Int,
        displayWidth: Int,
        rotationDegrees: Int,
    ): NSImage? {
        // The engine's one colour-matrix law, applied to bytes here instead of in
        // a shader. Identity hands back the same array, so an untouched picture copies nothing.
        val pixels = adjustRgba(rgba, adjustSlot.value)
        val transform = transformSlot.value
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            return pixels.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (width * 4).toULong(),
                    space = colorSpace,
                    // The converter writes an opaque alpha, so skipping it is both correct and faster
                    // than asking Core Graphics to composite with it.
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                ) ?: return@usePinned null

                try {
                    val stored = CGBitmapContextCreateImage(context) ?: return@usePinned null
                    try {
                        // Sizing by the display width applies a non-square pixel aspect, so anamorphic
                        // content is not stretched. A quarter turn moves that stretch onto the other
                        // axis, because the picture's own width is vertical afterwards.
                        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
                        val presentedWidth = if (quarterTurned) height else displayWidth
                        val presentedHeight = if (quarterTurned) displayWidth else height
                        // An aspect override reshapes the picture AS PRESENTED,
                        // which for this path is only a different declared size and costs nothing.
                        val size = CGSizeMake(
                            framedPresentedWidth(presentedWidth, presentedHeight, transform).toDouble(),
                            presentedHeight.toDouble(),
                        )
                        // The unrotated fast path is only a fast path while there is nothing to
                        // composite; an active overlay routes through the drawing pass at every
                        // rotation (S2.c, the S4.c Apple half), and so does zoom or pan.
                        if (rotationDegrees == 0 && overlaySlot.value == null && !transform.needsDrawingPass()) {
                            NSImage(cGImage = stored, size = size)
                        } else {
                            val turned = turn(stored, width, height, rotationDegrees, transform, colorSpace)
                                ?: return@usePinned null
                            try {
                                NSImage(cGImage = turned, size = size)
                            } finally {
                                CGImageRelease(turned)
                            }
                        }
                    } finally {
                        CGImageRelease(stored)
                    }
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    /**
     * Redraws [image] turned clockwise by [rotationDegrees], into a bitmap of its own.
     *
     * Core Graphics rotates about the origin and its bitmap contexts have their origin at the bottom
     * left with y increasing upward, so each turn needs a translation that brings the turned rectangle
     * back over the destination. The transform is written as a translate followed by a rotate because
     * that is the order Core Graphics applies to the point it is given: the point is rotated first and
     * the translation lands the result.
     *
     * A positive angle turns counter-clockwise in this space, so a clockwise quarter turn is a negative
     * angle. The angles are exact quarters and the destination rectangle is the source rectangle to the
     * pixel, so nothing is resampled: this moves bytes, it does not filter them.
     */
    private fun turn(
        image: CGImageRef,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        transform: io.github.yuroyami.kiteplayer.VideoTransform,
        colorSpace: CGColorSpaceRef,
    ): CGImageRef? {
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val outputWidth = if (quarterTurned) height else width
        val outputHeight = if (quarterTurned) width else height
        val context = CGBitmapContextCreate(
            // Core Graphics allocates and owns this buffer, and works out its own row alignment. The
            // pixels are read back through an image, so no Kotlin array has to be kept alive for it.
            data = null,
            width = outputWidth.toULong(),
            height = outputHeight.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = 0u,
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        ) ?: return null
        try {
            CGContextSaveGState(context)
            // Zoom and pan concatenated OUTSIDE the turn, so what is magnified and
            // moved is the picture as presented. Core Graphics applies the last concat first.
            if (transform.needsDrawingPass()) {
                val framing = framingConcat(outputWidth, outputHeight, transform)
                CGContextTranslateCTM(context, framing[0], framing[1])
                CGContextScaleCTM(context, framing[2], framing[2])
            }
            when (rotationDegrees) {
                90 -> {
                    CGContextTranslateCTM(context, 0.0, width.toDouble())
                    CGContextRotateCTM(context, -PI / 2)
                }
                180 -> {
                    CGContextTranslateCTM(context, width.toDouble(), height.toDouble())
                    CGContextRotateCTM(context, PI)
                }
                270 -> {
                    CGContextTranslateCTM(context, height.toDouble(), 0.0)
                    CGContextRotateCTM(context, PI / 2)
                }
            }
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            CGContextRestoreGState(context)
            drawOverlayInto(context, outputWidth, outputHeight)
            return CGBitmapContextCreateImage(context)
        } finally {
            CGContextRelease(context)
        }
    }


    /**
     * Draws the active overlay above the picture, in DISPLAY space with identity CTM (S2.c,
     * carrying S4.c's Apple half): overlay coordinates are authored top-down against the
     * overlay's own viewport, Core Graphics draws bottom-up, and the scale maps one onto the
     * other, the same law the Android compositor and the Metal renderer obey.
     */
    private fun drawOverlayInto(context: platform.CoreGraphics.CGContextRef, outputWidth: Int, outputHeight: Int) {
        val active = overlaySlot.value ?: return
        if (active.images.isEmpty()) return
        val sx = outputWidth.toDouble() / active.viewportWidth.coerceAtLeast(1)
        val sy = outputHeight.toDouble() / active.viewportHeight.coerceAtLeast(1)
        // The cache owns these; a held cue is built once, not once per frame.
        val cached = overlayImages.imagesFor(active)
        active.images.forEachIndexed { index, image ->
            val cg = cached.getOrNull(index) ?: return@forEachIndexed
            val drawWidth = image.bitmap.width * sx
            val drawHeight = image.bitmap.height * sy
            val cgY = outputHeight - image.y * sy - drawHeight
            CGContextDrawImage(context, CGRectMake(image.x * sx, cgY, drawWidth, drawHeight), cg)
        }
    }

    /** An overlay bitmap as a CGImage. The pixels are premultiplied, which is what CG blends. */
    private fun rgbaImage(bitmap: io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap): CGImageRef? {
        val rowBytes = bitmap.width * 4
        // RgbaBitmap promises AT LEAST this many bytes, never exactly this many.
        // Core Graphics reads only the rows it is given, so slack past them is harmless.
        if (bitmap.pixels.size < rowBytes * bitmap.height) return null
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            return bitmap.pixels.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = bitmap.width.toULong(),
                    height = bitmap.height.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = rowBytes.toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                ) ?: return@usePinned null
                try {
                    CGBitmapContextCreateImage(context)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    /** A paused picture shows the change too: the retained pixels re-draw. */
    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        adjustSlot.value = adjustments
        requestRedraw()
    }

    /** 17.11 SOL-R14, the framing half. Same delivery law as [setAdjustments]. */
    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        transformSlot.value = transform
        requestRedraw()
    }

    private fun requestRedraw() {
        if (closed.value) return
        redrawWanted.value = true
        signal.trySend(Unit)
    }

    /**
     * Stored wholesale and drawn above every later frame. A paused picture shows the change on
     * the next frame, the same honest note as the Metal renderer; cue edges republish during
     * playback at most one frame away.
     */
    /* SOL-R1: worker-thread confined. */
    private var retainedRgba: ByteArray? = null
    private var retainedWidth: Int = 0
    private var retainedHeight: Int = 0
    private var retainedDisplayWidth: Int = 0
    private var retainedRotation: Int = 0
    private val redrawWanted = kotlinx.atomicfu.atomic(false)

    /** Re-composites the retained pixels under the CURRENT overlay. Worker thread only. */
    private fun redrawRetained() {
        // Nobody will ever see a picture drawn after the close began.
        if (closed.value) return
        val rgba = retainedRgba ?: return
        val image = try {
            makeImage(rgba, retainedWidth, retainedHeight, retainedDisplayWidth, retainedRotation)
        } catch (_: Throwable) {
            null
        } ?: return
        deliver(image)
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        overlaySlot.value = overlay
        // A paused picture shows the change too.
        redrawWanted.value = true
        signal.trySend(Unit)
    }

    /**
     * Stops drawing and gives everything back.
     *
     * The order is the whole point, and every step is there because leaving it out loses something:
     * mark closed first so [present] stops accepting frames, close the signal so the worker's wait ends,
     * then cancel and join the worker so a conversion already running finishes and closes its frame.
     * Only then are the two slots drained, which is final because nothing is left running to refill
     * them, and only then is the conversion thread closed.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        drainPending()
        if (pendingImage.getAndSet(null) != null) failed.incrementAndGet()
        // The worker is out, so the overlay cache has no other owner left.
        overlayImages.release()
        dispatcher.close()
    }
}
