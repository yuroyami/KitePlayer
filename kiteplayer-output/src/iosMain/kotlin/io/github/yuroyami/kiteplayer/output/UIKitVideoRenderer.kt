package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretObjCPointer
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
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
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAGravityResize
import platform.QuartzCore.kCAGravityResizeAspect
import platform.QuartzCore.kCAGravityResizeAspectFill
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.math.PI

/**
 * Converts software frames away from the caller and presents the newest finished image in [layer].
 *
 * The caller owns the layer. In particular, closing this renderer gives up the renderer's image
 * references but does not clear or replace the layer's last contents.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class UIKitVideoRenderer internal constructor(
    private val convert: (VideoFrame) -> ByteArray,
    private val enqueueOnMain: (block: () -> Unit) -> Unit,
    private val deliverImage: (CGImageRef?) -> Unit,
) : VideoRenderer {

    public constructor(
        layer: CALayer,
        convert: (VideoFrame) -> ByteArray,
    ) : this(
        convert = convert,
        enqueueOnMain = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
        deliverImage = { image ->
            if (image != null) {
                CATransaction.begin()
                try {
                    CATransaction.setDisableActions(true)
                    layer.contents = interpretObjCPointer<Any>(image.rawValue)
                } finally {
                    CATransaction.commit()
                }
            }
        },
    ) {
        // Gravity is set once here and thereafter only by [setScaleMode], so a mode choice is
        // never overwritten by the next delivered frame.
        gravityLayer = layer
        dispatch_async(dispatch_get_main_queue()) { layer.contentsGravity = kCAGravityResizeAspect }
    }

    /**
     * The layer whose gravity [setScaleMode] drives, known only on the layer-owning constructor.
     * A renderer built on the raw delivery constructor scales inside whatever the caller does
     * with the delivered image, so for it the call is a documented no-op.
     */
    private var gravityLayer: CALayer? = null

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)

    /** The active subtitle overlay; replaced wholesale by [setOverlay]. */
    private val overlaySlot = atomic<SubtitleOverlay?>(null)

    /** Overlay CGImages, rebuilt only when the cue changes. Worker-owned. */
    private val overlayImages = OverlayImageCache(::rgbaImage)

    /** The picture controls, read by the worker on every draw. */
    private val adjustSlot = atomic(io.github.yuroyami.kiteplayer.VideoAdjustments.Identity)

    /** The framing controls, under the same ownership as the picture controls. */
    private val transformSlot = atomic(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    private val pendingFrame = atomic<VideoFrame?>(null)
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** All image ownership and delivery state is changed while holding this one lock. */
    private val deliveryLock = SynchronizedObject()
    private var pendingImage: CGImageRef? = null
    private var deliveryQueued: Boolean = false
    private var lastDeliveredImage: CGImageRef? = null

    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-uikit-convert")
    private val worker = CoroutineScope(dispatcher + SupervisorJob())
    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                convertPending()
                // An overlay change during a pause re-composites the retained pixels.
                // When a frame DID convert above, the new overlay is already baked into it and
                // the flag clears without a second draw.
                if (redrawWanted.getAndSet(false) && pendingFrame.value == null) redrawRetained()
                // getAndSet(false) is the whole consumption: the old else-arm
                // blindly wrote false over a request that raced in after the read.
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() ends the worker's wait by closing the signal.
        }
    }

    public val presentedFrames: Long get() = presented.value

    public val supersededFrames: Long get() = superseded.value

    public val failedFrames: Long get() = failed.value

    override val events: Flow<RendererEvent> = emptyFlow()

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val displaced = pendingFrame.getAndSet(frame)
        if (displaced != null) {
            displaced.close()
            superseded.incrementAndGet()
        }
        if (closed.value) {
            drainPendingFrame()
            return false
        }
        signal.trySend(Unit)
        return true
    }

    private fun convertPending() {
        // close() blocks its caller, which is the UI thread. It must not wait on a
        // conversion this worker had not started yet; close() drains the slot after the join.
        if (closed.value) return
        val frame = pendingFrame.getAndSet(null) ?: return
        val size = frame.size
        val rotation = quarterTurn(frame.rotationDegrees)
        val image = try {
            val rgba = convert(frame)
            // The newest source pixels stay behind, worker-confined, so an overlay
            // change during a pause can re-composite without a frame arriving. One RGBA frame
            // of memory, exactly the paused picture the viewer is looking at.
            retainedRgba = rgba
            retainedSize = size
            retainedRotation = rotation
            makeImage(rgba, size, rotation)
        } catch (_: Throwable) {
            null
        } finally {
            frame.close()
        }
        if (image == null) {
            failed.incrementAndGet()
            return
        }
        deliver(image)
    }

    /* SOL-R1: worker-thread confined, like every conversion input. */
    private var retainedRgba: ByteArray? = null
    private var retainedSize: VideoSize? = null
    private var retainedRotation: Int = 0
    private val redrawWanted = kotlinx.atomicfu.atomic(false)

    /** Re-composites the retained pixels under the CURRENT overlay. Worker thread only. */
    private fun redrawRetained() {
        // Nobody will ever see a picture drawn after the close began.
        if (closed.value) return
        val rgba = retainedRgba ?: return
        val size = retainedSize ?: return
        val image = try {
            makeImage(rgba, size, retainedRotation)
        } catch (_: Throwable) {
            null
        } ?: return
        deliver(image)
    }

    private fun deliver(image: CGImageRef) {
        var displaced: CGImageRef? = null
        var rejected = false
        var shouldEnqueue = false
        synchronized(deliveryLock) {
            if (closed.value) {
                rejected = true
                failed.incrementAndGet()
            } else {
                displaced = pendingImage
                pendingImage = image
                if (displaced != null) superseded.incrementAndGet()
                if (!deliveryQueued) {
                    deliveryQueued = true
                    shouldEnqueue = true
                }
            }
        }
        displaced?.let(::CGImageRelease)
        if (rejected) {
            CGImageRelease(image)
            return
        }
        if (!shouldEnqueue) return
        try {
            enqueueOnMain { drawPendingImage() }
        } catch (_: Throwable) {
            var stranded: CGImageRef? = null
            synchronized(deliveryLock) {
                deliveryQueued = false
                stranded = pendingImage
                pendingImage = null
                if (stranded != null) failed.incrementAndGet()
            }
            stranded?.let(::CGImageRelease)
        }
    }

    private fun drawPendingImage() {
        synchronized(deliveryLock) {
            deliveryQueued = false
            val image = pendingImage
            pendingImage = null
            if (image == null) return@synchronized
            if (closed.value) {
                failed.incrementAndGet()
                CGImageRelease(image)
                return@synchronized
            }
            try {
                deliverImage(image)
                presented.incrementAndGet()
                lastDeliveredImage?.let(::CGImageRelease)
                lastDeliveredImage = image
            } catch (_: Throwable) {
                failed.incrementAndGet()
                CGImageRelease(image)
            }
        }
    }

    private fun makeImage(rgba: ByteArray, size: VideoSize, rotationDegrees: Int): CGImageRef? {
        val width = size.width
        val height = size.height
        val displayWidth = displayWidth(size)
        if (width <= 0 || height <= 0 || displayWidth <= 0) return null

        val rowBytes = width.toLong() * RGBA_BYTES
        if (rowBytes > Int.MAX_VALUE || height.toLong() > Int.MAX_VALUE.toLong() / rowBytes) return null
        val requiredBytes = rowBytes * height.toLong()
        if (rgba.size.toLong() != requiredBytes) return null

        // The engine's one colour-matrix law, applied to bytes here instead of in
        // a shader. Identity hands back the same array, so an untouched picture copies nothing.
        val pixels = adjustRgba(rgba, adjustSlot.value)
        val videoTransform = transformSlot.value
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            val context = CGBitmapContextCreate(
                data = null,
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = rowBytes.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
            ) ?: return null
            try {
                val destination = CGBitmapContextGetData(context) ?: return null
                pixels.usePinned { pinned ->
                    memcpy(destination, pinned.addressOf(0), requiredBytes.convert())
                }
                val stored = CGBitmapContextCreateImage(context) ?: return null
                // With identity geometry and nothing to composite, the stored image
                // IS the finished picture, so the second bitmap pass was pure waste.
                if (
                    rotationDegrees == 0 &&
                    displayWidth == width &&
                    overlaySlot.value == null &&
                    videoTransform.isIdentity
                ) {
                    return stored
                }
                try {
                    return transform(
                        stored, displayWidth, height, rotationDegrees, videoTransform, colorSpace,
                    )
                } finally {
                    CGImageRelease(stored)
                }
            } finally {
                CGContextRelease(context)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    private fun transform(
        image: CGImageRef,
        displayWidth: Int,
        height: Int,
        rotationDegrees: Int,
        videoTransform: io.github.yuroyami.kiteplayer.VideoTransform,
        colorSpace: CGColorSpaceRef,
    ): CGImageRef? {
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val presentedWidth = if (quarterTurned) height else displayWidth
        val presentedHeight = if (quarterTurned) displayWidth else height
        // An aspect override reshapes the picture AS PRESENTED. A CALayer sizes
        // itself from the pixels it is given, so this path resamples rather than relabels.
        val outputWidth = framedPresentedWidth(presentedWidth, presentedHeight, videoTransform)
        val outputHeight = presentedHeight
        val outputRowBytes = outputWidth.toLong() * RGBA_BYTES
        if (
            outputRowBytes > Int.MAX_VALUE ||
            outputHeight.toLong() > Int.MAX_VALUE.toLong() / outputRowBytes
        ) {
            return null
        }
        val context = CGBitmapContextCreate(
            data = null,
            width = outputWidth.toULong(),
            height = outputHeight.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = outputRowBytes.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        ) ?: return null
        try {
            CGContextSaveGState(context)
            // Core Graphics applies the LAST concat to the point first, so the
            // reading order here is the reverse of the drawing order: turn, then zoom and pan
            // about the presented centre, then stretch onto any forced aspect.
            if (outputWidth != presentedWidth) {
                CGContextScaleCTM(context, outputWidth.toDouble() / presentedWidth, 1.0)
            }
            if (videoTransform.needsDrawingPass()) {
                val framing = framingConcat(presentedWidth, presentedHeight, videoTransform)
                CGContextTranslateCTM(context, framing[0], framing[1])
                CGContextScaleCTM(context, framing[2], framing[2])
            }
            when (rotationDegrees) {
                90 -> {
                    CGContextTranslateCTM(context, 0.0, displayWidth.toDouble())
                    CGContextRotateCTM(context, -PI / 2)
                }
                180 -> {
                    CGContextTranslateCTM(context, displayWidth.toDouble(), height.toDouble())
                    CGContextRotateCTM(context, PI)
                }
                270 -> {
                    CGContextTranslateCTM(context, height.toDouble(), 0.0)
                    CGContextRotateCTM(context, PI / 2)
                }
            }
            CGContextDrawImage(
                context,
                CGRectMake(0.0, 0.0, displayWidth.toDouble(), height.toDouble()),
                image,
            )
            CGContextRestoreGState(context)
            drawOverlayInto(context, outputWidth, outputHeight)
            return CGBitmapContextCreateImage(context)
        } finally {
            CGContextRelease(context)
        }
    }

    private fun displayWidth(size: VideoSize): Int {
        if (size.pixelAspectDenominator == 0) return size.width
        val scaled = size.width.toLong() * size.pixelAspectNumerator.toLong() /
            size.pixelAspectDenominator.toLong()
        return if (scaled in 1..Int.MAX_VALUE.toLong()) scaled.toInt() else 0
    }

    private fun quarterTurn(rotationDegrees: Int): Int {
        val normalised = ((rotationDegrees % 360) + 360) % 360
        return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
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

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        val layer = gravityLayer ?: return
        val gravity = when (mode) {
            io.github.yuroyami.kiteplayer.VideoScale.Fit -> kCAGravityResizeAspect
            io.github.yuroyami.kiteplayer.VideoScale.Fill -> kCAGravityResizeAspectFill
            io.github.yuroyami.kiteplayer.VideoScale.Stretch -> kCAGravityResize
        }
        enqueueOnMain {
            CATransaction.begin()
            try {
                CATransaction.setDisableActions(true)
                layer.contentsGravity = gravity
                // Fill overhangs the layer's bounds by design; without the mask the crop would
                // paint over whatever sits beside the video.
                layer.masksToBounds = mode == io.github.yuroyami.kiteplayer.VideoScale.Fill
            } finally {
                CATransaction.commit()
            }
        }
    }

    /**
     * Stored wholesale and drawn above every later frame. A paused picture shows the change on
     * the next frame, the same honest note as the Metal renderer; cue edges republish during
     * playback at most one frame away.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        overlaySlot.value = overlay
        // A paused picture shows the change too; the worker re-composites from the
        // retained pixels when no fresh frame is on its way.
        redrawWanted.value = true
        signal.trySend(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        drainPendingFrame()

        var pending: CGImageRef? = null
        var delivered: CGImageRef? = null
        synchronized(deliveryLock) {
            pending = pendingImage
            pendingImage = null
            if (pending != null) failed.incrementAndGet()
            delivered = lastDeliveredImage
            lastDeliveredImage = null
            deliveryQueued = false
            try {
                deliverImage(null)
            } catch (_: Throwable) {
                // Teardown still releases both renderer-owned image references.
            }
        }
        pending?.let(::CGImageRelease)
        delivered?.let(::CGImageRelease)
        // The worker is out, so the overlay cache has no other owner left.
        overlayImages.release()
        dispatcher.close()
    }

    private fun drainPendingFrame() {
        val frame = pendingFrame.getAndSet(null) ?: return
        frame.close()
        failed.incrementAndGet()
    }

    private companion object {
        private const val RGBA_BYTES: Long = 4L
    }
}
