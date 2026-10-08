package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.PictureCrop
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
 * The frame published for [KiteVideo] to draw: an image plus the presentation facts the bitmap
 * itself cannot carry, the aspect-corrected display width, the quarter turn, the mirror and the
 * crop.
 */
internal class KiteVideoFrame(
    val image: ImageBitmap,
    val size: VideoSize,
    val rotationDegrees: Int,
    val requiresCommitFence: Boolean = false,
    private val release: () -> Unit = {},
    /** Mirrored left to right before the turn. */
    val mirrored: Boolean = false,
    /**
     * The edges of [image] that are not part of the picture, taken off before the mirror and the
     * turn (#497). Always one that fits [size]; null when the whole image is the picture.
     */
    val crop: PictureCrop? = null,
    /**
     * The flash guard's factor for this picture (#500): 1, the usual case, draws it as the picture
     * controls make it, and below 1 dims it while a flashing run lasts.
     */
    val dim: Float = 1f,
) : AutoCloseable {
    /** The size of what is shown: [size] less [crop]. */
    val shownSize: VideoSize get() = size.cropped(crop)

    private val closed = atomic(false)

    /** Set once when a converter refuses the backend's frame type; see [UnsupportedFrameType]. */
    private val unsupportedPairing = atomic(false)

    /** Guarded by KiteVideoState's frame fence; one image may be drawn by several nodes. */
    internal var gpuCompletionCounted: Boolean = false

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) release()
    }
}

/**
 * The subtitle overlay published for [KiteVideo] to draw above the picture: each item keeps its
 * authored position in the OVERLAY's own viewport
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
 * phase of [KiteVideo].
 *
 * The ownership rules are identical to its three siblings: the frame belongs to this renderer
 * from the moment [present] is called and is closed exactly once, including when superseded,
 * when conversion fails and when the renderer closes with it still in hand. [present] hands the
 * frame to the worker and returns at once. Only the newest frame is kept, and the displaced one
 * is closed and counted here because nothing else will ever see it.
 *
 * The software conversion is honest CPU work, and a stated last resort: RGBA bytes, then one
 * ImageBitmap per published frame. The YUV image path replaces that and owns
 * measuring both.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class KiteVideoRenderer(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
    /** Whether [convert] rolls a frame's HDR off to SDR, which only the converter knows. */
    private val toneMapped: (VideoFrame) -> Boolean = { false },
    /** Releases what [convert] holds. Called once by close, on the worker, after the last conversion. */
    private val closeConverter: () -> Unit = {},
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
    /** Publishes the engine's picture controls into the draw-phase state. */
    private val publishAdjustments: (io.github.yuroyami.kiteplayer.VideoAdjustments) -> Unit = {},
    /** Publishes the engine's framing controls into the draw-phase state. */
    private val publishTransform: (io.github.yuroyami.kiteplayer.VideoTransform) -> Unit = {},
    /** Publishes the sampling the draw phase should scale the picture with. */
    private val publishFilterQuality: (androidx.compose.ui.graphics.FilterQuality) -> Unit = {},
    /** Optional platform GPU tier. Software frames still use this renderer's worker. */
    private val hardwareRenderer: KiteVideoHardwareRenderer? = null,
    /** The flash guard's clock, in nanoseconds: when each converted picture is about to show. */
    private val guardNanos: () -> Long = monotonicGuardClock(),
) : VideoRenderer {

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        publishScaleMode(mode)
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        publishAdjustments(adjustments)
    }

    /**
     * The flash guard's mode (#500). This renderer guards the pictures it converts itself; it cannot
     * read a system setting, so [io.github.yuroyami.kiteplayer.FlashGuard.FollowSystem] is off here.
     * The platform GPU tier guards its own pictures in its blit, so the mode goes on to it.
     */
    override fun setFlashGuard(mode: io.github.yuroyami.kiteplayer.FlashGuard) {
        // A change of mode starts the history afresh, as taking the picture off does.
        if (flashGuard.getAndSet(mode) != mode) guardForgets.value = true
        hardwareRenderer?.setFlashGuard(mode)
    }

    private val flashGuard = atomic(io.github.yuroyami.kiteplayer.FlashGuard.FollowSystem)

    /** The detector and its grid, the worker's alone. */
    @OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
    private val guard = io.github.yuroyami.kiteplayer.spi.VideoFlashGuard()
    @OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
    private val guardCells = FloatArray(io.github.yuroyami.kiteplayer.spi.VideoFlashGuard.MEASURES)

    /** Set when the picture is taken off or the mode changes, so the worker starts the guard afresh. */
    private val guardForgets = atomic(false)

    /**
     * The flash guard's factor for [rgba], measured on a sparse lattice of it. Worker thread only.
     * 1 when the guard is off.
     */
    @OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)
    private fun dimFor(rgba: ByteArray, width: Int, height: Int): Float {
        if (flashGuard.value != io.github.yuroyami.kiteplayer.FlashGuard.On) return 1f
        if (guardForgets.getAndSet(false)) guard.reset()
        io.github.yuroyami.kiteplayer.spi.VideoFlashGuard.cellsFromRgba(rgba, width, height, into = guardCells)
        return guard.factorFor(guardCells, guardNanos())
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        publishTransform(transform)
        // The hardware tier sizes its images for the turned picture (#428); the draw turns it.
        if (!closed.value) hardwareRenderer?.setTransform(transform)
    }

    /**
     * The render-quality ladder reaches TWO places from here, and it has to reach both.
     *
     * The scaler is Compose's own: the draw phase enlarges the published image, so the kernel is
     * a `filterQuality` on that one call. Dithering, debanding and the animation upscaler are not
     * Compose's to do, so they go on to the platform GPU tier, which is the only thing in this path
     * holding a shader.
     * Forgetting the second half is why a dither could be switched on and change nothing on the
     * Android GPU tier: the engine talks to THIS renderer, never to the one underneath it.
     */
    override fun setRenderQuality(quality: io.github.yuroyami.kiteplayer.RenderQuality) {
        publishFilterQuality(
            when (quality.scaler) {
                io.github.yuroyami.kiteplayer.VideoScaler.CatmullRom ->
                    androidx.compose.ui.graphics.FilterQuality.High
                io.github.yuroyami.kiteplayer.VideoScaler.Bilinear ->
                    androidx.compose.ui.graphics.FilterQuality.Low
            },
        )
        hardwareRenderer?.setRenderQuality(quality)
    }

    /** The contentHash the published overlay was built from, so an unchanged one is not rebuilt. */
    private var overlayHash: Long = Long.MIN_VALUE

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)

    /** Set once when a converter refuses the backend's frame type; see [UnsupportedFrameType]. */
    private val unsupportedPairing = atomic(false)

    /** Orders the overlay publications against close's final null. */
    private val overlayPublishLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** The single frame waiting to be converted. Newest wins; the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /**
     * Orders a conversion's take and publication against [clearPicture]. The worker takes the
     * waiting frame and reads [pictureEpoch] in one hold, and publishes in another only if the
     * epoch has not moved, so a frame accepted before a clear never reaches the screen after it.
     */
    private val pictureLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** Moved on by each [clearPicture]. Read and written only under [pictureLock]. */
    private var pictureEpoch = 0L

    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /**
     * When this renderer last said that it tone mapped, in milliseconds on [toneMapClock]. It says
     * so at most once a second: the engine warns once per open and ignores the rest, and a once
     * only announcement left every later HDR item on this renderer without its warning. Worker only.
     */
    private var toneMapSaidAt = Long.MIN_VALUE
    private val toneMapClock = kotlin.time.TimeSource.Monotonic.markNow()

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

    /**
     * Frames replaced in the waiting slot by a newer one before they could be converted, or let go
     * because the picture was taken off before they were published.
     */
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

    override fun accepts(shape: io.github.yuroyami.kiteplayer.spi.FrameShape): Boolean = when (shape) {
        is io.github.yuroyami.kiteplayer.spi.FrameShape.Memory -> shape.pixelFormat != PlayerPixelFormat.Opaque
        is io.github.yuroyami.kiteplayer.spi.FrameShape.Surface -> hardwareRenderer?.accepts(shape) == true
    }

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
        var epoch = 0L
        val frame = kotlinx.atomicfu.locks.synchronized(pictureLock) {
            epoch = pictureEpoch
            pending.getAndSet(null)
        } ?: return
        val size = frame.size
        val rotation = quarterTurn(frame.rotationDegrees)
        val mirrored = frame.mirrored
        val crop = frame.crop?.takeIf { !it.isEmpty && it.fits(size.width, size.height) }
        // The cost clock starts before the conversion and stops after the image build, because
        // that pair is exactly the CPU work this software path pays per published frame.
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        // A refused PAIRING is refused for this renderer's whole life. Retrying it per
        // frame pays the same doomed conversion thirty times a second and republishes the same
        // sentence; the frame is still closed and still counted, because silence would be worse.
        if (unsupportedPairing.value) {
            frame.close()
            failed.incrementAndGet()
            return
        }
        if (toneMapped(frame)) {
            val now = toneMapClock.elapsedNow().inWholeMilliseconds
            if (toneMapSaidAt == Long.MIN_VALUE || now - toneMapSaidAt >= 1_000L) {
                toneMapSaidAt = now
                eventFlow.tryEmit(RendererEvent.ToneMapEngaged(transfer = frame.colorSpace.transfer.name))
            }
        }
        val rgba = try {
            convert(frame)
        } catch (refusal: UnsupportedFrameType) {
            unsupportedPairing.value = true
            failFrame(refusal.message ?: "this renderer cannot read the backend's frames")
            null
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

        // Measured before the image is built, so the picture that completes a run is already dimmed.
        val dim = dimFor(rgba, width, height)
        val image = try {
            makeImage(rgba, width, height)
        } catch (failure: Throwable) {
            failFrame(failure.message ?: "building the image failed")
            return
        }
        cost.record(started.elapsedNow().inWholeNanoseconds)
        val finished = KiteVideoFrame(
            image = image.image,
            size = size,
            rotationDegrees = rotation,
            requiresCommitFence = image.requiresCommitFence,
            release = image.release,
            mirrored = mirrored,
            crop = crop,
            dim = dim,
        )
        val current = kotlinx.atomicfu.locks.synchronized(pictureLock) {
            if (pictureEpoch == epoch) publish(finished)
            pictureEpoch == epoch
        }
        if (!current) {
            // The picture was taken off while this frame was converted (#530).
            finished.close()
            superseded.incrementAndGet()
            return
        }
        presented.incrementAndGet()
    }

    /**
     * Takes the picture off (#530). The GPU tier first makes sure nothing it accepted can still
     * arrive, then the frame waiting for the worker goes, a conversion in flight is told its
     * picture is gone, and null is published, so [KiteVideo] draws the subtitles alone over
     * whatever lies behind it until the next frame.
     */
    override fun clearPicture() {
        if (closed.value) return
        hardwareRenderer?.clearPicture()
        kotlinx.atomicfu.locks.synchronized(pictureLock) {
            if (closed.value) return
            pending.getAndSet(null)?.let { waiting ->
                waiting.close()
                superseded.incrementAndGet()
            }
            pictureEpoch += 1
            publish(null)
        }
        guardForgets.value = true
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
        surfaceWidth.value = (width * scale).toInt()
        surfaceHeight.value = (height * scale).toInt()
        if (!closed.value) hardwareRenderer?.setViewport(width, height, scale)
    }

    private val surfaceWidth = atomic(0)
    private val surfaceHeight = atomic(0)

    /**
     * KiteVideo composites overlays in OUTPUT space, mapping the overlay's viewport onto the whole
     * component, so the engine can rasterise text at these pixels and have it drawn at 1:1 instead
     * of stretched from the video's smaller canvas. Every renderer follows the same rule:
     * docs/subtitle-placement.md.
     */
    override val outputSize: io.github.yuroyami.kiteplayer.VideoSize?
        get() {
            val width = surfaceWidth.value
            val height = surfaceHeight.value
            if (width <= 0 || height <= 0) return null
            return io.github.yuroyami.kiteplayer.VideoSize(width, height)
        }

    /**
     * Converts and publishes the overlay. Inline rather than on the worker: cues change
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
        var anyFailed = false
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
                    anyFailed = true
                    eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "building an overlay image failed"))
                    return@mapNotNull null
                },
            )
        }
        /* Both halves. A failed image build must NOT advance the hash: recording the
         * content as published would skip the retry the next setOverlay call is, and the text
         * would simply never appear. And a close that raced this build must win: publishing
         * after close would hand a dead renderer's images to a live composition. */
        kotlinx.atomicfu.locks.synchronized(overlayPublishLock) {
            // Checked and published under one lock: the plain check let a close
            // land between it and the publish, pinning a dead renderer's cues on screen for ever.
            if (closed.value) return
            if (!anyFailed) overlayHash = overlay.contentHash
            publishOverlay(
                KiteVideoOverlay(
                    items = items,
                    viewportWidth = overlay.viewportWidth,
                    viewportHeight = overlay.viewportHeight,
                ),
            )
        }
    }

    /**
     * Stops converting and publishes null so no closed renderer's picture outlives it.
     *
     * The order is its siblings': mark closed so [present] refuses, close the signal so the
     * worker's wait ends, join the worker so a conversion in flight finishes with the buffers it
     * started with, then drain the slot (final, because nothing is left running to refill it),
     * publish the null, close the converter on the worker's thread and release that thread. A
     * step that throws does not stop the steps after it; the first failure is thrown at the end.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        var failure: Throwable? = null
        fun step(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught
            }
        }
        step { hardwareRenderer?.close() }
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        hardwareEventJob?.let { runBlocking { it.join() } }
        step {
            drainPending()
            publish(null)
            releasePublishedFrames()
            kotlinx.atomicfu.locks.synchronized(overlayPublishLock) { publishOverlay(null) }
        }
        // After the join and the null publish: no worker can ask for an image and no reader
        // should be handed one, so the image storage goes back now.
        step(releaseImages)
        // The worker's job has ended, so its thread is idle: the converter closes where it ran.
        step { runBlocking(dispatcher) { closeConverter() } }
        dispatcher.close()
        failure?.let { throw it }
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL: Long = 4L
    }
}

/** A monotonic clock in nanoseconds from its first reading, for the flash guard. */
private fun monotonicGuardClock(): () -> Long {
    val start = kotlin.time.TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeNanoseconds }
}
