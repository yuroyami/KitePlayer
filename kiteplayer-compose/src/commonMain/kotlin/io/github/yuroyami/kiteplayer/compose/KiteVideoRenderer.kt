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
)

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
    /** Builds the drawable image. Production asks a [FrameImagePool]; host tests fake it. */
    private val makeImage: (rgba: ByteArray, width: Int, height: Int) -> ImageBitmap,
    /** Publishes the newest finished frame, or null at close. Production writes snapshot state. */
    private val publish: (KiteVideoFrame?) -> Unit,
    /** Releases whatever backs [makeImage]. Called by close after the worker has been joined. */
    private val releaseImages: () -> Unit = {},
) : VideoRenderer {

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

    /** Frames whose picture was published for drawing. */
    val presentedFrames: Long get() = presented.value

    /** Frames replaced in the waiting slot by a newer one before they could be converted. */
    val supersededFrames: Long get() = superseded.value

    /** Frames that published nothing: a bad conversion, a failed image build, a close in flight. */
    val failedFrames: Long get() = failed.value

    /** Per-published-frame CPU cost of convert plus image build. See [KiteVideoFrameCost]. */
    private val cost = FrameCostTracker()

    fun costSnapshot(): KiteVideoFrameCost = cost.snapshot()

    /** Nothing zero-copy here yet; KV-3 (Apple, S2) is where that lands. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque

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
        publish(KiteVideoFrame(image, size, rotation))
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

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    override suspend fun setOverlay(overlay: SubtitleOverlay?): Unit = Unit

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
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        drainPending()
        publish(null)
        // After the join and the null publish: no worker can ask for an image and no reader
        // should be handed one, so the image storage goes back now.
        releaseImages()
        dispatcher.close()
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL: Long = 4L
    }
}
