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
 * [close] is the one place that blocks, and only on this renderer's own worker. It marks the renderer
 * closed, ends the worker's wait, then waits for a conversion already running to finish before draining
 * both slots and closing the conversion thread. That order is what makes the two drains final: nothing
 * else is left that could put a frame or an image back.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class AppKitVideoRenderer internal constructor(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
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
    ) : this(
        convert = convert,
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

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

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
        val frame = pending.getAndSet(null) ?: return
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
        dispatcher.close()
    }
}
