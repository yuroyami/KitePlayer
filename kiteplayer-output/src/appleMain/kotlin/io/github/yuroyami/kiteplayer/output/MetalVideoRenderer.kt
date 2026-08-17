@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CloseableCoroutineDispatcher
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
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.QuartzCore.CAMetalLayer

/**
 * The GPU renderer of S2.c: one Metal core for macOS and iOS, drawing into a caller-owned
 * [CAMetalLayer].
 *
 * The division of labour is exactly the CG renderers': the engine hands frames to [present],
 * which stores the newest in a single slot and returns at once; a dedicated render thread takes
 * the slot, resolves the frame through the caller's [MetalPictureResolver] (a CVPixelBuffer with
 * no copy, or planes with one memcpy each and no CPU colour conversion), and encodes through
 * [MetalFrameComposer] into the layer's next drawable. Newest wins; the displaced frame is
 * closed and counted, which at 4K is the difference between a smooth picture and a memory storm.
 *
 * Presentation is snapped by the layer itself: `presentDrawable` on a CAMetalLayer with display
 * sync enabled lands on the display's own refresh, so [vsyncIntervalNanos] honestly answers null
 * and the engine's clock keeps pacing DECODE while Metal paces the glass.
 */
public class MetalVideoRenderer public constructor(
    private val layer: CAMetalLayer,
    private val resolver: MetalPictureResolver,
) : VideoRenderer {

    // CAMetalLayer speaks the forward-declared protocol type; the casts bridge the two names of
    // the same ObjC protocol.
    private val device = (layer.device as? platform.Metal.MTLDeviceProtocol) ?: run {
        val created = MTLCreateSystemDefaultDevice() ?: error("this machine has no Metal device")
        layer.device = created as objcnames.protocols.MTLDeviceProtocol
        created
    }

    private val composer = MetalFrameComposer(device)

    private val presented = atomic(0L)
    private val failed = atomic(0L)
    private val superseded = atomic(0L)

    /** The single frame waiting to be drawn. Newest wins; the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /** Replaced wholesale by [setOverlay]; read by the render thread on every draw. */
    private val overlay = atomic<SubtitleOverlay?>(null)

    /** The ruling scale mode; written by the engine, read by the render thread per draw. */
    private val scaleMode = atomic(io.github.yuroyami.kiteplayer.VideoScale.Fit)

    /** The picture controls, pre-packed for the shader once per setting. Read per draw. */
    private val adjustUniforms = atomic(DISABLED_ADJUST_UNIFORMS)

    /** The ruling framing controls, under the same ownership as the scale mode. */
    private val videoTransform = atomic(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    private val viewportWidth = atomic(0)
    private val viewportHeight = atomic(0)

    private val closed = atomic(false)

    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-metal")
    private val worker = CoroutineScope(dispatcher + SupervisorJob())

    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                drawPending()
                // SOL-R1: overlay and picture-control changes reach a PAUSED frame by
                // re-encoding the retained picture. A frame drawn above already carried them.
                // getAndSet(false) is the whole consumption (audit F-RDW1): the old else-arm
                // blindly wrote false over a request that raced in after the read, and that
                // request's queued token then found the flag already spent.
                if (redrawWanted.getAndSet(false) && pending.value == null) drawRetained()
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() closed the signal channel; the ordinary way out, not a fault.
        }
    }

    /** Frames whose picture reached the layer. */
    public val presentedFrames: Long get() = presented.value

    /** Frames replaced by a newer one before the render thread could draw them. */
    public val supersededFrames: Long get() = superseded.value

    /** Frames that reached no drawable: resolver refusal, encode failure, or a closed renderer. */
    public val failedFrames: Long get() = failed.value

    /** The zero-copy claim of this renderer, and the reason S2.b's download twin goes unused here. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = setOf(HwSurfaceKind.CoreVideoPixelBuffer)

    /** Opaque is drawable exactly when the frame carries a surface this renderer wraps. */
    override fun supports(format: PlayerPixelFormat): Boolean =
        format == PlayerPixelFormat.Opaque || planeRecipeFor(format) != null

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val displaced = pending.getAndSet(frame)
        if (displaced != null) {
            displaced.close()
            superseded.incrementAndGet()
        }
        if (closed.value) {
            drainPending()
            return false
        }
        signal.trySend(Unit)
        return true
    }

    /** Draws whatever is in the slot. Render thread only. */
    private fun drawPending() {
        // 17.11 SOL-R11: close() blocks its caller, which is the UI thread. A drawable wait
        // started after the close began is time that thread spends for nothing.
        if (closed.value) return
        val frame = pending.getAndSet(null) ?: return
        try {
            val picture = resolver.resolve(frame)
            if (picture == null) {
                failed.incrementAndGet()
                return
            }
            // The format gate runs BEFORE a drawable is acquired (audit F-DRW1): refusing after
            // nextDrawable left that drawable unpresented and its buffer uncommitted, and a
            // layer holds only about three, so a stream of unwrappable frames starved the pool.
            if (!composer.canEncode(picture)) {
                failed.incrementAndGet()
                eventFlow.tryEmit(
                    RendererEvent.Failed("the Metal renderer cannot wrap this picture's pixel format"),
                )
                return
            }
            val drawable = layer.nextDrawable()
            if (drawable == null) {
                // The layer has no backing store right now (offscreen, zero size, teardown).
                // Surface loss is an event, not an exception; the schedule counts a drop.
                failed.incrementAndGet()
                eventFlow.tryEmit(RendererEvent.SurfaceLost("CAMetalLayer produced no drawable"))
                return
            }
            val width = viewportWidth.value.takeIf { it > 0 }
                ?: layer.drawableSize.useContents { width }.toInt().coerceAtLeast(1)
            val height = viewportHeight.value.takeIf { it > 0 }
                ?: layer.drawableSize.useContents { height }.toInt().coerceAtLeast(1)
            composer.encode(
                target = drawable.texture as platform.Metal.MTLTextureProtocol,
                frame = frame,
                picture = picture,
                overlay = overlay.value,
                viewportWidth = width,
                viewportHeight = height,
                presentDrawable = drawable,
                scaleMode = scaleMode.value,
                videoTransform = videoTransform.value,
                adjustUniforms = adjustUniforms.value,
                toneMapped = true,
            )
            presented.incrementAndGet()
            retainForRedraw(frame, picture)
        } catch (failure: Throwable) {
            failed.incrementAndGet()
            eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "Metal encode failed"))
        } finally {
            frame.close()
        }
    }

    /* ── SOL-R1: the retained last picture, render-thread confined. ─────────────────────── */

    private var retainedPicture: MetalPicture? = null
    private var retainedMeta: RetainedFrameMeta? = null
    private val redrawWanted = kotlinx.atomicfu.atomic(false)

    /** The frame facts encode() reads, kept past the frame's close. Never closed itself. */
    private class RetainedFrameMeta(
        override val size: io.github.yuroyami.kiteplayer.VideoSize,
        override val rotationDegrees: Int,
        override val colorSpace: io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo,
    ) : VideoFrame {
        override val pts: io.github.yuroyami.kiteplayer.Pts = io.github.yuroyami.kiteplayer.Pts.Zero
        override val duration: io.github.yuroyami.kiteplayer.Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Opaque
        override val hardwareSurface: io.github.yuroyami.kiteplayer.spi.HwSurfaceKind? = null
        override val generation: io.github.yuroyami.kiteplayer.Generation =
            io.github.yuroyami.kiteplayer.Generation.Initial
        override fun close() = Unit
    }

    /** A hardware picture outlives its frame only through its own retain; software planes are
     *  plain arrays already owned by the resolved picture. */
    private fun retainForRedraw(frame: VideoFrame, picture: MetalPicture) {
        if (picture is MetalPicture.CorePixelBuffer) {
            platform.CoreVideo.CVPixelBufferRetain(picture.buffer.reinterpret())
        }
        releaseRetained()
        retainedPicture = picture
        retainedMeta = RetainedFrameMeta(frame.size, frame.rotationDegrees, frame.colorSpace)
    }

    private fun releaseRetained() {
        (retainedPicture as? MetalPicture.CorePixelBuffer)?.let {
            platform.CoreVideo.CVPixelBufferRelease(it.buffer.reinterpret())
        }
        retainedPicture = null
        retainedMeta = null
    }

    /** drawPending's shape over the retained picture; render thread only. */
    private fun drawRetained() {
        // 17.11 SOL-R11: nobody will ever see a picture drawn after the close began.
        if (closed.value) return
        val picture = retainedPicture ?: return
        val meta = retainedMeta ?: return
        try {
            val drawable = layer.nextDrawable() ?: return
            val width = viewportWidth.value.takeIf { it > 0 }
                ?: layer.drawableSize.useContents { width }.toInt().coerceAtLeast(1)
            val height = viewportHeight.value.takeIf { it > 0 }
                ?: layer.drawableSize.useContents { height }.toInt().coerceAtLeast(1)
            composer.encode(
                target = drawable.texture as platform.Metal.MTLTextureProtocol,
                frame = meta,
                picture = picture,
                overlay = overlay.value,
                viewportWidth = width,
                viewportHeight = height,
                presentDrawable = drawable,
                scaleMode = scaleMode.value,
                videoTransform = videoTransform.value,
                adjustUniforms = adjustUniforms.value,
                toneMapped = true,
            )
        } catch (failure: Throwable) {
            eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "Metal redraw failed"))
        }
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        scaleMode.value = mode
        requestRedraw()
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        videoTransform.value = transform
        // SOL-R1 retired the old paused-picture limit: the retained picture re-encodes now.
        requestRedraw()
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        adjustUniforms.value = packAdjustUniforms(adjustments)
        // SOL-R1 retired the old paused-picture limit: the retained picture re-encodes now.
        requestRedraw()
    }

    override fun setViewport(width: Int, height: Int, scale: Float) {
        viewportWidth.value = (width * scale).toInt()
        viewportHeight.value = (height * scale).toInt()
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        this.overlay.value = overlay
        // SOL-R1: a paused picture shows the change too; the render worker re-encodes the
        // retained picture when no fresh frame is on its way.
        requestRedraw()
    }

    private fun requestRedraw() {
        if (closed.value) return
        redrawWanted.value = true
        signal.trySend(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        signal.close()
        // 17.11 SOL-R11: cancel BEFORE the join, as both CPU fallbacks already do. A worker
        // parked on a signal that was buffered before the close would otherwise wake and spend
        // the closing thread's time on one more drawable wait.
        worker.cancel()
        drainPending()
        runBlocking { workerJob.join() }
        // After the join the render thread is out; the retained picture's release cannot race
        // a redraw (SOL-R1's ownership half).
        releaseRetained()
        // After the join no draw is in flight from this renderer, so the composer can fence the
        // GPU and give back its texture cache and native holder (17.11 SOL-R6).
        composer.close()
        dispatcher.close()
    }

    private fun drainPending() {
        val frame = pending.getAndSet(null)
        if (frame != null) {
            frame.close()
            failed.incrementAndGet()
        }
    }
}
