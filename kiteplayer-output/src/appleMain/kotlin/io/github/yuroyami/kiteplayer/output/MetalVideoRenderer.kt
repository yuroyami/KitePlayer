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
        val frame = pending.getAndSet(null) ?: return
        try {
            val picture = resolver.resolve(frame)
            if (picture == null) {
                failed.incrementAndGet()
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
            )
            presented.incrementAndGet()
        } catch (failure: Throwable) {
            failed.incrementAndGet()
            eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "Metal encode failed"))
        } finally {
            frame.close()
        }
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        scaleMode.value = mode
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        videoTransform.value = transform
        // Applied at the next draw, the same recorded paused-picture limit as setAdjustments.
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        adjustUniforms.value = packAdjustUniforms(adjustments)
        // Applied at the next draw. A PAUSED picture keeps its old colours until then: this
        // renderer holds no drawn-frame copy to re-encode, the recorded SOL-R1 family limit,
        // which is also why KiteVideo (which repaints its held image immediately) is the
        // flagship. Playing content picks the change up within one frame interval.
    }

    override fun setViewport(width: Int, height: Int, scale: Float) {
        viewportWidth.value = (width * scale).toInt()
        viewportHeight.value = (height * scale).toInt()
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        this.overlay.value = overlay
        // No frame needs to arrive for a subtitle change to show; redraw is the next present's
        // job during playback, and during a pause the newest frame has already been consumed, so
        // the change waits for the next frame. The engine republished on cue edges, which during
        // playback is at most one frame away. A paused-picture redraw is S2.d's KiteVideo story.
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        signal.close()
        drainPending()
        runBlocking { workerJob.join() }
        worker.cancel()
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
