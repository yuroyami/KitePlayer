package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

/**
 * The frame published for [KiteVideo] to draw: an image plus the two presentation facts the
 * bitmap itself cannot carry, the aspect-corrected display width and the quarter turn.
 */
internal class KiteVideoFrame(
    val image: ImageBitmap,
    val size: VideoSize,
    val rotationDegrees: Int,
    val requiresCommitFence: Boolean = false,
    private val release: () -> Unit = {},
) : AutoCloseable {
    private val closed = atomic(false)

    /** Guarded by KiteVideoState's frame fence; one image may be drawn by several nodes. */
    internal var gpuCompletionCounted: Boolean = false

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) release()
    }
}

/**
 * The subtitle overlay published for [KiteVideo] to draw above the picture (S2.d, carrying
 * S4.c's KiteVideo half): each item keeps its authored position in the OVERLAY's own viewport
 * units, and the draw phase scales that viewport onto the component, the same output-space law
 * the Metal renderer obeys.
 */
internal class KiteVideoOverlay(
    val items: List<Item>,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    internal class Item(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val image: ImageBitmap,
    )
}

/**
 * The renderer that feeds [KiteVideoState]: the fourth instance of the proven newest-wins shape
 * (Apple CALayer, AppKit, Android Surface, now Compose), differing only in where a finished
 * picture goes: not to a platform surface but into snapshot state, whose one reader is the draw
 * phase of [KiteVideo] (law 1 of 17.9).
 *
 * The ownership rules are identical to its three siblings: the frame belongs to this renderer
 * from the moment [present] is called and is closed exactly once, including when superseded,
 * when conversion fails and when the renderer closes with it still in hand. [present] hands the
 * frame to the worker and returns at once. Only the newest frame is kept, and the displaced one
 * is closed and counted here because nothing else will ever see it.
 *
 * The S1 conversion is honest CPU work (17.9's stated last-resort): RGBA bytes, then one
 * ImageBitmap per published frame. KV-2 (S2) replaces that with the YUV image path and owns
 * measuring both.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class KiteVideoRenderer(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
    /** Builds the drawable image and, when pooled, its asynchronous-consumer lease. */
    private val makeImage: (rgba: ByteArray, width: Int, height: Int) -> FrameImage,
    /** Publishes the newest finished frame, or null at close. Production writes snapshot state. */
    private val publish: (KiteVideoFrame?) -> Unit,
    /** Releases whatever backs [makeImage]. Called by close after the worker has been joined. */
    private val releaseImages: () -> Unit = {},
    /** Releases published-frame bookkeeping after the platform producer has been destroyed. */
    private val releasePublishedFrames: () -> Unit = {},
    /** Builds a premultiplied overlay image. Production asks [overlayImageBitmap]. */
    private val makeOverlayImage: (rgba: ByteArray, width: Int, height: Int) -> ImageBitmap =
        { rgba, width, height -> overlayImageBitmap(rgba, width, height) },
    /** Publishes the active overlay, or null when it clears or the renderer closes. */
    private val publishOverlay: (KiteVideoOverlay?) -> Unit = {},
    /** Publishes the engine's scale mode into the draw-phase state. */
    private val publishScaleMode: (io.github.yuroyami.kiteplayer.VideoScale) -> Unit = {},
    /** Optional platform GPU tier. Software frames still use this renderer's worker. */
    private val hardwareRenderer: KiteVideoHardwareRenderer? = null,
) : VideoRenderer {

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        publishScaleMode(mode)
    }

    /** The contentHash the published overlay was built from, so an unchanged one is not rebuilt. */
    private var overlayHash: Long = Long.MIN_VALUE

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)

    /** The single frame waiting to be converted. Newest wins; the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-kitevideo")
    private val worker = CoroutineScope(dispatcher + SupervisorJob())
    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                convertPending()
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() closed the signal channel. The ordinary way out, not a fault.
        }
    }

    /** Carries platform surface failures through the one renderer event stream exposed to core. */
    private val hardwareEventJob: Job? = hardwareRenderer?.let { renderer ->
        worker.launch {
            renderer.events.collect(eventFlow::emit)
        }
    }

    /** Frames whose picture was published for drawing. */
    val presentedFrames: Long get() = presented.value + (hardwareRenderer?.presentedFrames ?: 0L)

    /** Frames replaced in the waiting slot by a newer one before they could be converted. */
    val supersededFrames: Long get() = superseded.value + (hardwareRenderer?.supersededFrames ?: 0L)

    /** Frames that published nothing: a bad conversion, a failed image build, a close in flight. */
    val failedFrames: Long get() = failed.value + (hardwareRenderer?.failedFrames ?: 0L)

    /** Per-published-frame CPU cost of convert plus image build. See [KiteVideoFrameCost]. */
    private val cost = FrameCostTracker()

    fun costSnapshot(): KiteVideoFrameCost = cost.snapshot()

    override fun videoDecoderFactories() = hardwareRenderer?.videoDecoderFactories().orEmpty()

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        hardwareRenderer?.supportedHardwareSurfaces().orEmpty()

    override fun supports(format: PlayerPixelFormat): Boolean =
        format != PlayerPixelFormat.Opaque || hardwareRenderer?.supports(format) == true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val hardware = hardwareRenderer
        val surface = frame.hardwareSurface
        if (hardware != null && surface != null && surface in hardware.supportedHardwareSurfaces()) {
            return hardware.present(frame, targetNanos)
        }
        val displaced = pending.getAndSet(frame)
        if (displaced != null) {
            displaced.close()
            superseded.incrementAndGet()
        }
        // Re-read after the store: close() writes `closed` before draining the slot, so between
        // the two of them the frame is never stranded with no worker alive to take it.
        if (closed.value) {
            drainPending()
            return false
        }
        signal.trySend(Unit)
        return true
    }

    /** Converts and publishes whatever is waiting, if anything. Worker thread only. */
    private fun convertPending() {
        val frame = pending.getAndSet(null) ?: return
        val size = frame.size
        val rotation = quarterTurn(frame.rotationDegrees)
        // The cost clock starts before the conversion and stops after the image build, because
        // that pair is exactly the CPU work this software path pays per published frame.
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val rgba = try {
            convert(frame)
        } catch (failure: Throwable) {
            failFrame(failure.message ?: "the converter failed")
            null
        } finally {
            // Ownership ends here. Everything below works on bytes this renderer owns.
            frame.close()
        }
        if (rgba == null) return

        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            failFrame("a ${width}x$height frame has no pixels to draw")
            return
        }
        val required = width.toLong() * height.toLong() * RGBA_BYTES_PER_PIXEL
        if (rgba.size.toLong() != required) {
            failFrame("the converter returned ${rgba.size} bytes for a ${width}x$height frame, which needs $required")
            return
        }

        val image = try {
            makeImage(rgba, width, height)
        } catch (failure: Throwable) {
            failFrame(failure.message ?: "building the image failed")
            return
        }
        cost.record(started.elapsedNow().inWholeNanoseconds)
        publish(
            KiteVideoFrame(
                image = image.image,
                size = size,
                rotationDegrees = rotation,
                requiresCommitFence = image.requiresCommitFence,
                release = image.release,
            ),
        )
        presented.incrementAndGet()
    }

    /** Counts the frame and reports why. */
    private fun failFrame(detail: String) {
        failed.incrementAndGet()
        eventFlow.tryEmit(RendererEvent.Failed(detail))
    }

    /** Closes and counts a frame nobody will convert. */
    private fun drainPending() {
        val stranded = pending.getAndSet(null) ?: return
        stranded.close()
        failed.incrementAndGet()
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) {
        if (!closed.value) hardwareRenderer?.setViewport(width, height, scale)
    }

    /**
     * Converts and publishes the overlay (S2.d). Inline rather than on the worker: cues change
     * about once a second, the images are small, and the engine already calls this off the UI
     * thread. An unchanged contentHash republishes nothing, which keeps a paused picture's draw
     * state untouched.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        if (closed.value) return
        if (overlay == null || overlay.images.isEmpty()) {
            if (overlayHash != Long.MIN_VALUE) {
                overlayHash = Long.MIN_VALUE
                publishOverlay(null)
            }
            return
        }
        if (overlay.contentHash == overlayHash) return
        val items = overlay.images.mapNotNull { image ->
            val bitmap = image.bitmap
            if (bitmap.width <= 0 || bitmap.height <= 0) return@mapNotNull null
            KiteVideoOverlay.Item(
                x = image.x,
                y = image.y,
                width = bitmap.width,
                height = bitmap.height,
                image = try {
                    makeOverlayImage(bitmap.pixels, bitmap.width, bitmap.height)
                } catch (failure: Throwable) {
                    eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "building an overlay image failed"))
                    return@mapNotNull null
                },
            )
        }
        overlayHash = overlay.contentHash
        publishOverlay(
            KiteVideoOverlay(
                items = items,
                viewportWidth = overlay.viewportWidth,
                viewportHeight = overlay.viewportHeight,
            ),
        )
    }

    /**
     * Stops converting and publishes null so no closed renderer's picture outlives it.
     *
     * The order is its siblings': mark closed so [present] refuses, close the signal so the
     * worker's wait ends, join the worker so a conversion in flight finishes with the buffers it
     * started with, then drain the slot (final, because nothing is left running to refill it),
     * publish the null and release the thread.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        var failure: Throwable? = null
        try {
            hardwareRenderer?.close()
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            signal.close()
            worker.cancel()
            runBlocking { workerJob.join() }
            hardwareEventJob?.let { runBlocking { it.join() } }
            drainPending()
            publish(null)
            releasePublishedFrames()
            publishOverlay(null)
            // After the join and the null publish: no worker can ask for an image and no reader
            // should be handed one, so the image storage goes back now.
            releaseImages()
            dispatcher.close()
        }
        failure?.let { throw it }
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL: Long = 4L
    }
}
