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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AppKit.NSImage
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

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
 * ### The rules this obeys
 *
 * The frame belongs to this renderer from the moment [present] is called, and is closed exactly once,
 * including when it is superseded before being drawn or when conversion fails. AppKit is touched only
 * from the main thread, through `dispatch_async`, and never waited on: blocking the engine on the UI
 * thread is how a player deadlocks, and libmpv's own headers warn about that twice.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
public class AppKitVideoRenderer(
    private val window: AppKitWindow,
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
) : VideoRenderer {

    private val presented = atomic(0L)
    private val failed = atomic(0L)
    private val superseded = atomic(0L)

    /** The single frame waiting to be drawn. Newest wins, and the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val closed = atomic(false)

    private val worker = CoroutineScope(newSingleThreadContext("kiteplayer-appkit-convert") + SupervisorJob())

    init {
        worker.launch {
            while (!closed.value) {
                signal.receive()
                val frame = pending.getAndSet(null) ?: continue
                val width = frame.size.width
                val height = frame.size.height
                val displayWidth = frame.size.displayWidth
                val image = try {
                    if (width <= 0 || height <= 0) null else makeImage(convert(frame), width, height, displayWidth)
                } catch (failure: Throwable) {
                    eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "conversion failed"))
                    null
                } finally {
                    // Ownership ends here. The image holds its own copy of the pixels.
                    frame.close()
                }

                if (image == null) {
                    failed.incrementAndGet()
                    continue
                }
                dispatch_async(dispatch_get_main_queue()) {
                    window.imageView.image = image
                }
                presented.incrementAndGet()
            }
        }
    }

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /** Frames actually drawn. */
    public val presentedFrames: Long get() = presented.value

    /** Frames that could not be converted. */
    public val failedFrames: Long get() = failed.value

    /**
     * Frames replaced by a newer one before they could be drawn.
     *
     * A non-zero count here means this renderer is the bottleneck, not the decoder and not the clock.
     * At 1080p it will be, because the conversion is on the CPU. That is what a GPU renderer fixes.
     */
    public val supersededFrames: Long get() = superseded.value

    /** Nothing zero-copy here. That is what a Metal renderer is for. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque

    /**
     * Queues [frame] for drawing and returns immediately.
     *
     * Returns true because the frame was accepted for presentation, which is what the engine is asking.
     * Whether it is ultimately drawn or replaced by a newer one is this renderer's business, and is
     * reported through [presentedFrames] and [supersededFrames].
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
        signal.trySend(Unit)
        return true
    }

    /**
     * Builds an image from RGBA bytes.
     *
     * A bitmap context is used rather than a data provider so that Core Graphics copies the pixels
     * immediately. The alternative keeps a reference to the caller's buffer and requires it to outlive
     * the image, which across a `dispatch_async` boundary is a use-after-free waiting to happen.
     */
    private fun makeImage(rgba: ByteArray, width: Int, height: Int, displayWidth: Int): NSImage? {
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            return rgba.usePinned { pinned ->
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
                    val cgImage = CGBitmapContextCreateImage(context) ?: return@usePinned null
                    try {
                        // Sizing by the display width applies a non-square pixel aspect, so anamorphic
                        // content is not stretched.
                        NSImage(cGImage = cgImage, size = CGSizeMake(displayWidth.toDouble(), height.toDouble()))
                    } finally {
                        CGImageRelease(cgImage)
                    }
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

    override suspend fun setOverlay(overlay: SubtitleOverlay?): Unit = Unit

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        pending.getAndSet(null)?.close()
        signal.close()
        worker.cancel()
    }
}
