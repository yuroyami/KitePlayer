package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.TreeMap

private const val RGBA_OUTPUT_USAGE: Long =
    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT

/** An accepted timed release keeps its terminal outcome even if renderer close wins the race. */
internal fun acceptedTimedReleaseCompletion(recordFailure: () -> Unit): (Boolean) -> Unit =
    { rendered -> if (!rendered) recordFailure() }

/**
 * Turns direct MediaCodec output into immutable RGBA hardware [Bitmap] frames for a GPU client.
 *
 * MediaCodec writes vendor YUV into an external-OES [SurfaceTexture]. One GLES2 shader pass writes
 * RGBA_8888 into an [ImageReader], and [Bitmap.wrapHardwareBuffer] gives the client a Skia-readable
 * hardware image. No video pixels enter Kotlin or CPU memory.
 *
 * The composited target preserves Android's exact SDR RGB spaces. Android 12+ decoders may feed it
 * 10-bit/HDR sources by performing the platform's hardware HDR-to-SDR tone map first. The decoder
 * must accept that request, and any recognized contradictory output metadata is rejected before a
 * frame is exposed. The client callback may run on the renderer's GL thread and must return
 * promptly. Subtitle composition remains the client's job.
 */
public class AndroidGpuImageVideoRenderer(
    private val onImage: (AndroidGpuImageFrame) -> Unit,
) : VideoRenderer {
    init {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android GPU hardware images require API 29"
        }
    }

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val releaseCompletion = acceptedTimedReleaseCompletion { failed.incrementAndGet() }
    private val closed = atomic(false)
    private val bridgeFailure = atomic<Throwable?>(null)
    private val eventFlow = MutableSharedFlow<RendererEvent>(replay = 4, extraBufferCapacity = 4)
    private val bridge = OesRgbaBridge(::publishImage, ::recordSuperseded, ::bridgeFailed)

    /**
     * The render-quality passes (17.21). This tier blits OES to an RGBA8 image, so eight bits is
     * what the dither spreads across; anything it cannot do it ignores, as the SPI allows.
     */
    override fun setRenderQuality(quality: io.github.yuroyami.kiteplayer.RenderQuality) {
        bridge.ditherStep.set(if (quality.dither) 1f / 255f else 0f)
        // Same 1/16384 scale as the Metal packer, for the same reason recorded there: a one step
        // band survives a smaller threshold and the pass would then do nothing.
        bridge.debandThreshold.set(if (quality.deband) quality.debandThreshold / 16384f else 0f)
        bridge.debandRange.set(quality.debandRange)
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        // SOL-R14's Android half: the OES-to-RGBA blit is the ONE hook this tier has, and it
        // now applies the same unit-domain law every other renderer does. A paused picture
        // keeps its old colours until the next latch; that narrower limit stays recorded.
        bridge.adjust.set(GlState.packGlAdjust(adjustments))
    }
    private val codecTarget = MediaCodecSurfaceTarget(initialSurface = bridge.surface)
    private val directFactory = MediaCodecVideoDecoderFactory(
        target = codecTarget,
        applyCodecRotation = false,
        outputAdmission = MediaCodecOutputAdmission(::composeMediaCodecOutputContract),
    )
    private val decoderFactory = object : VideoDecoderFactory {
        override val name: String = "Android MediaCodec Compose GPU"

        override suspend fun create(stream: PlayerStreamInfo, hwdec: io.github.yuroyami.kiteplayer.HwdecPolicy): VideoDecoder? {
            if (closed.value) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            bridgeFailure.value?.let { throw IllegalStateException("the Android GPU bridge failed", it) }
            val size = stream.videoSize ?: return null
            if (!bridge.supports(size)) return null
            return try {
                if (closed.value) return null
                directFactory.create(stream, hwdec)?.let { decoder ->
                    BridgeGuardedVideoDecoder(decoder) { bridgeFailure.value }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (hwdec == io.github.yuroyami.kiteplayer.HwdecPolicy.Require) throw failure
                null
            }
        }
    }

    /** Frames whose completed RGBA hardware image reached [onImage]. */
    public val presentedFrames: Long get() = presented.value

    /** Frames coalesced by SurfaceTexture or skipped while no Compose viewport is active. */
    public val supersededFrames: Long get() = superseded.value

    /** Frames rejected or released without reaching the bridge Surface. */
    public val failedFrames: Long get() = failed.value

    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    override fun videoDecoderFactories(): List<VideoDecoderFactory> = listOf(decoderFactory)

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = setOf(HwSurfaceKind.MediaCodecBuffer)

    override fun supports(format: PlayerPixelFormat): Boolean = format == PlayerPixelFormat.Opaque

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val direct = frame as? DirectSurfaceVideoFrame
        if (direct == null || direct.target !== codecTarget) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val accepted = direct.renderAt(
            targetNanos = targetNanos,
            beforeRender = { timestamp ->
                bridge.prepareFrame(timestamp, direct.size, direct.rotationDegrees, direct.colorSpace)
            },
            onRenderFailed = bridge::cancelFrame,
            onReleased = releaseCompletion,
        )
        if (!accepted) failed.incrementAndGet()
        return accepted
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) {
        if (!closed.value) bridge.setViewport(width, height, scale)
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?): Unit = Unit

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        var failure: Throwable? = null
        try {
            codecTarget.clearGeometryConsumer()
            runCatching { codecTarget.update(null) }.onFailure {
                failure = it
                eventFlow.tryEmit(RendererEvent.Failed(it.message ?: "MediaCodec could not leave the Compose GPU Surface"))
            }
        } finally {
            runCatching { bridge.close() }.onFailure { closeFailure ->
                val first = failure
                if (first == null) failure = closeFailure else first.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun publishImage(frame: AndroidGpuImageFrame) {
        if (closed.value) {
            frame.close()
            recordSuperseded(1L)
            return
        }
        try {
            onImage(frame)
            presented.incrementAndGet()
        } catch (failure: Exception) {
            frame.close()
            bridgeFailed(failure)
        }
    }

    private fun recordSuperseded(count: Long) {
        if (count > 0L) superseded.addAndGet(count)
    }

    private fun bridgeFailed(failure: Throwable) {
        if (closed.value) return
        bridgeFailure.compareAndSet(expect = null, update = failure)
        Log.e(BRIDGE_LOG_TAG, "Android GPU image bridge failed", failure)
        failed.incrementAndGet()
        eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "the Android GPU image bridge failed"))
    }

    private companion object {
        const val BRIDGE_LOG_TAG = "KiteGpuBridge"
    }
}

internal fun isComposeSrgbSafeColor(color: ColorSpaceInfo): Boolean =
    color.matrix == ColorMatrix.Bt709 &&
        color.primaries == ColorPrimaries.Bt709 &&
        (color.transfer == ColorTransfer.Bt709 || color.transfer == ColorTransfer.Srgb)

internal fun isComposeGpuColorRepresentable(color: ColorSpaceInfo): Boolean =
    androidRgbColorSpaceOrNull(color) != null

/**
 * Shares MediaCodec parsing/probing with SurfaceView while expressing Compose's narrower target.
 *
 * An exactly taggable and MediaFormat-representable SDR source is preserved. Other
 * Android-representable sources request decoder-side SDR output, including Main10 PQ/HLG. Android's
 * tone-map contract produces limited-range BT.709 SDR; that is the trusted fallback only when the
 * input declaration was exact. The conventional untagged-HD fallback is already BT.709 and can be
 * represented directly; untagged SD/unspecified metadata still requires a recognized output
 * standard before any buffer is exposed to the bridge.
 */
internal fun composeMediaCodecOutputContract(
    requirement: MediaCodecStreamRequirement,
    sdkInt: Int = Build.VERSION.SDK_INT,
): MediaCodecOutputContract? {
    val validatePreserved: (MediaCodecOutputColor) -> Boolean = { detected ->
        detected.reliable && isComposeGpuColorRepresentable(detected.colorSpace)
    }
    val sourceColor = requirement.colorSpace
    if (
        sourceColor != null &&
        !sourceColor.isConventionalUndeclaredColor() &&
        canRepresentMediaCodecColorExactly(sourceColor) &&
        isComposeGpuColorRepresentable(sourceColor)
    ) {
        return MediaCodecOutputContract(validateOutput = validatePreserved)
    }
    if (sdkInt < Build.VERSION_CODES.S) return null
    val sourceCanReachCodec = sourceColor == null ||
        canRepresentMediaCodecColorExactly(sourceColor) ||
        sourceColor.isConventionalUndeclaredColor()
    if (!sourceCanReachCodec) return null
    val trustedOutputColor = sourceColor
        ?.takeIf { color ->
            canRepresentMediaCodecColorExactly(color) && !color.isConventionalUndeclaredColor()
        }
        ?.let { COMPOSE_TONE_MAPPED_OUTPUT }
    return MediaCodecOutputContract(
        requestedColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        requireExplicitOutputColor = trustedOutputColor == null,
        trustedOutputColor = trustedOutputColor,
        validateOutput = { detected ->
            detected.reliable && detected.colorSpace.isComposeToneMappedOutput()
        },
    )
}

private val COMPOSE_TONE_MAPPED_OUTPUT: ColorSpaceInfo = ColorSpaceInfo()

private fun ColorSpaceInfo.isComposeToneMappedOutput(): Boolean =
    matrix == ColorMatrix.Bt709 &&
        primaries == ColorPrimaries.Bt709 &&
        transfer == ColorTransfer.Bt709 &&
        rangeSpecified &&
        !fullRange

private fun ColorSpaceInfo.isConventionalUndeclaredColor(): Boolean =
    !rangeSpecified && when {
        matrix == ColorMatrix.Bt601 && primaries == ColorPrimaries.Bt601 &&
            transfer == ColorTransfer.Bt601 -> true
        matrix == ColorMatrix.Bt470bg && primaries == ColorPrimaries.Bt470bg &&
            transfer == ColorTransfer.Bt709 -> true
        matrix == ColorMatrix.Unspecified && primaries == ColorPrimaries.Unspecified &&
            transfer == ColorTransfer.Unspecified -> true
        else -> false
    }

/** Makes an asynchronous EGL/ImageReader failure observable through core's decoder recovery path. */
private class BridgeGuardedVideoDecoder(
    private val delegate: VideoDecoder,
    private val failureOrNull: () -> Throwable?,
) : VideoDecoder {
    override val hardware get() = delegate.hardware
    override val isDrained: Boolean get() = delegate.isDrained

    override suspend fun send(packet: PlayerPacket?): Boolean {
        checkBridge()
        val accepted = delegate.send(packet)
        checkBridge()
        return accepted
    }

    override suspend fun receive(): VideoFrame? {
        checkBridge()
        val frame = delegate.receive()
        failureOrNull()?.let { cause ->
            frame?.close()
            throw IllegalStateException("the Android GPU bridge failed: ${cause.message}", cause)
        }
        return frame
    }

    override suspend fun flush(newGeneration: Generation) {
        checkBridge()
        delegate.flush(newGeneration)
        checkBridge()
    }

    override fun close() = delegate.close()

    private fun checkBridge() {
        failureOrNull()?.let {
            throw IllegalStateException("the Android GPU bridge failed: ${it.message}", it)
        }
    }
}

/**
 * One immutable hardware image and its explicit ImageReader lease.
 *
 * The consumer must close this frame only after it has retired the image from drawing. Until then
 * the ImageReader slot remains acquired, so the EGL producer cannot overwrite pixels being sampled.
 * Failing to close frames applies bounded BufferQueue backpressure instead of corrupting an image.
 */
public class AndroidGpuImageFrame internal constructor(
    public val bitmap: Bitmap,
    public val size: VideoSize,
    public val rotationDegrees: Int,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Owns one EGL context, its decoder input Surface, and the leased RGBA output queue. */
private class OesRgbaBridge(
    private val publish: (AndroidGpuImageFrame) -> Unit,
    private val recordSuperseded: (Long) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("kiteplayer-compose-gpu").apply { start() }
    private val handler = Handler(thread.looper)
    private val imageThread = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        HandlerThread("kiteplayer-compose-gpu-images").apply { start() }
    } else {
        null
    }
    private val imageHandler = imageThread?.let { Handler(it.looper) }
    private val frameConfigurations = FrameConfigurationBook()
    private val requestedViewport = AtomicReference<GpuViewport?>()

    /** SOL-R14: the packed colour law the blit reads per draw; null is bit-exact off. */
    val adjust = AtomicReference<FloatArray?>()

    /** One output step when dithering is on, zero when it is off. Read by the blit. */
    val ditherStep = java.util.concurrent.atomic.AtomicReference(0f)

    /** Above zero turns debanding on and is the flatness threshold in 0..1. Read by the blit. */
    val debandThreshold = java.util.concurrent.atomic.AtomicReference(0f)

    /** Ring radius in source pixels at the first iteration. */
    val debandRange = java.util.concurrent.atomic.AtomicReference(16f)
    private val startup = AtomicReference<Result<GlState>>()
    private val ready = CountDownLatch(1)
    @Volatile private var closed = false

    private val state: GlState
        get() {
            check(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Android GPU image renderer did not start" }
            return requireNotNull(startup.get()).getOrThrow()
        }

    val surface: Surface get() = state.decoderSurface

    init {
        handler.post {
            startup.set(
                runCatching {
                    GlState.create(
                        handler,
                        imageHandler,
                        frameConfigurations,
                        requestedViewport,
                        adjust,
                        ditherStep,
                        debandThreshold,
                        debandRange,
                        publish,
                        recordSuperseded,
                        reportFailure,
                    )
                },
            )
            ready.countDown()
        }
        try {
            state
        } catch (failure: Throwable) {
            frameConfigurations.close()
            thread.quitSafely()
            imageThread?.quitSafely()
            thread.join(CLOSE_TIMEOUT_MILLIS)
            imageThread?.join(CLOSE_TIMEOUT_MILLIS)
            check(!thread.isAlive && imageThread?.isAlive != true) {
                "Android GPU image renderer startup threads did not terminate"
            }
            throw failure
        }
    }

    fun prepareFrame(
        timestampNanos: Long,
        size: VideoSize,
        rotationDegrees: Int,
        colorSpace: ColorSpaceInfo,
    ) {
        if (closed) return
        check(size.width > 0 && size.height > 0) {
            "a ${size.width}x${size.height} frame has no drawable geometry"
        }
        check(
            frameConfigurations.register(
                timestampNanos,
                FrameConfiguration(
                    size,
                    normalizedGpuQuarterTurn(rotationDegrees),
                    androidRgbColorSpace(colorSpace),
                ),
            ),
        ) { "Android GPU image renderer stopped accepting frame configurations" }
    }

    fun cancelFrame(timestampNanos: Long) {
        check(frameConfigurations.cancel(timestampNanos)) {
            "failed MediaCodec release had no registered GPU frame configuration"
        }
    }

    fun supports(size: VideoSize): Boolean = size.width > 0 && size.height > 0

    fun setViewport(width: Int, height: Int, scale: Float) {
        if (closed) return
        val viewport = physicalGpuViewport(width, height, scale)
        if (requestedViewport.getAndSet(viewport) == viewport) return
        if (!handler.post {
                runCatching(state::viewportChanged).onFailure(reportFailure)
            } && !closed
        ) {
            reportFailure(IllegalStateException("Android GPU image renderer thread is stopped"))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val isGlThread = Thread.currentThread() === thread
        var failure: Throwable? = null
        try {
            failure = cleanupFailure(failure) { runOnGlThread(GlState::beginClose) }
            imageThread?.quitSafely()
            if (Thread.currentThread() !== imageThread) {
                imageThread?.join(CLOSE_TIMEOUT_MILLIS)
                check(imageThread?.isAlive != true) {
                    "Android GPU image fence thread did not terminate"
                }
            } else {
                failure = cleanupFailure(failure) {
                    error("Android GPU image renderer cannot close from its image callback")
                }
            }
            failure = cleanupFailure(failure) { runOnGlThread(GlState::finishClose) }
        } finally {
            thread.quitSafely()
            if (!isGlThread) {
                thread.join(CLOSE_TIMEOUT_MILLIS)
                check(!thread.isAlive) { "Android GPU image renderer thread did not terminate" }
            }
        }
        failure?.let { throw it }
    }

    private fun runOnGlThread(block: (GlState) -> Unit) {
        if (Thread.currentThread() === thread) {
            block(state)
            return
        }
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        check(handler.post {
            try {
                block(state)
            } catch (caught: Throwable) {
                failure.set(caught)
            } finally {
                finished.countDown()
            }
        }) { "Android GPU image renderer thread is stopped" }
        check(finished.await(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "Android GPU image renderer did not answer"
        }
        failure.get()?.let { throw it }
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 5L
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

/** EGL state is bridge-Handler confined; [frameConfigurations] is the thread-safe codec handoff. */
internal class GlState private constructor(
    private val handler: Handler,
    private val imageHandler: Handler?,
    private val frameConfigurations: FrameConfigurationBook,
    private val requestedViewport: AtomicReference<GpuViewport?>,
    private val publish: (AndroidGpuImageFrame) -> Unit,
    private val recordSuperseded: (Long) -> Unit,
    private val reportFailure: (Throwable) -> Unit,
    private val display: EGLDisplay,
    private val config: EGLConfig,
    private val context: EGLContext,
    private val pbuffer: EGLSurface,
    private val texture: Int,
    private val surfaceTexture: SurfaceTexture,
    val decoderSurface: Surface,
    private val program: Int,
    private val position: Int,
    private val texCoord: Int,
    private val sampler: Int,
    private val texMatrixUniform: Int,
    private val colorMatrixUniform: Int,
    private val colorOffsetUniform: Int,
    private val colorEnabledUniform: Int,
    /** SOL-R14: the packed colour law, written by setAdjustments on any thread. */
    private val adjust: AtomicReference<FloatArray?>,
    private val ditherStep: java.util.concurrent.atomic.AtomicReference<Float>,
    private val ditherStepUniform: Int,
    private val debandThreshold: java.util.concurrent.atomic.AtomicReference<Float>,
    private val debandRange: java.util.concurrent.atomic.AtomicReference<Float>,
    private val debandThresholdUniform: Int,
    private val debandStepUniform: Int,
    private val debandSeedUniform: Int,
) : AutoCloseable {
    /** Advances per draw so the debanding ring and its grain do not sit still. */
    private val debandSeed = java.util.concurrent.atomic.AtomicInteger(0)
    private val transform = FloatArray(16)
    private var outputQueue: OutputQueue? = null
    private var outputSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var configuredSourceSize: VideoSize? = null
    private var configuredOutputSize: VideoSize? = null
    private var configuredRotation = 0
    private var configuredColorSpace: AndroidRgbColorSpace = AndroidRgbColorSpace.Srgb
    private var currentSurface: EGLSurface = pbuffer
    private var hasLatchedFrame = false
    private var lastLatchedTimestamp = 0L
    @Volatile private var closed = false
    private val queueFence = Any()
    private val allQueues = mutableSetOf<OutputQueue>()

    private fun configure(
        size: VideoSize,
        rotationDegrees: Int,
        colorSpace: AndroidRgbColorSpace,
    ) {
        if (closed || size.width <= 0 || size.height <= 0) return
        val rotation = normalizedGpuQuarterTurn(rotationDegrees)
        val outputSize = fittedGpuOutputSize(size, rotation, requestedViewport.get())
        val sourceChanged = size != configuredSourceSize
        val metadataChanged =
            sourceChanged || rotation != configuredRotation || colorSpace != configuredColorSpace
        val outputChanged = outputSize != configuredOutputSize
        val queuePresenceChanged = (outputSize == null) != (outputQueue == null)
        if (
            !metadataChanged &&
            !outputChanged &&
            !queuePresenceChanged
        ) return
        configuredSourceSize = size
        configuredOutputSize = outputSize
        configuredRotation = rotation
        configuredColorSpace = colorSpace
        if (sourceChanged) surfaceTexture.setDefaultBufferSize(size.width, size.height)
        if (outputSize == null) {
            if (outputQueue != null) destroyOutput(force = false)
        } else {
            rebuildOutput()
        }
    }

    fun viewportChanged() {
        if (closed) return
        val sourceSize = configuredSourceSize ?: return
        configure(sourceSize, configuredRotation, configuredColorSpace)
    }

    private fun drawNewestFrame() {
        if (closed) return
        surfaceTexture.updateTexImage()
        val timestamp = surfaceTexture.timestamp
        val matched = frameConfigurations.takeExact(timestamp)
        if (matched == null) {
            // SurfaceTexture may leave more than one callback queued for the buffer already latched
            // by the first callback. A stale callback can arrive after an intervening frame, so the
            // last timestamp alone is insufficient: retain bounded identities that already received
            // an exact presented/superseded outcome.
            if (
                hasLatchedFrame && timestamp == lastLatchedTimestamp ||
                frameConfigurations.wasResolved(timestamp)
            ) return
            error(
                "SurfaceTexture produced timestamp $timestamp without its MediaCodec frame " +
                    "configuration (${frameConfigurations.describeMissing(timestamp)})",
            )
        }
        hasLatchedFrame = true
        lastLatchedTimestamp = timestamp
        recordSuperseded(matched.skippedFrames)
        val frameConfiguration = matched.configuration
        configure(
            frameConfiguration.size,
            frameConfiguration.rotationDegrees,
            frameConfiguration.androidColorSpace,
        )
        val outputSize = configuredOutputSize
        if (outputSize == null) {
            // The frame reached the bridge, but there is deliberately no Compose node to receive it.
            recordSuperseded(1L)
            return
        }
        val queue = checkNotNull(outputQueue) { "an active GPU viewport has no RGBA output queue" }
        if (!queue.beginOutput()) {
            // ImageReader would be allowed to block the GL producer while all immutable buffers are
            // still leased by Compose. Dropping this newest latch keeps render and close bounded.
            recordSuperseded(1L)
            return
        }
        try {
            surfaceTexture.getTransformMatrix(transform)
            makeCurrent(outputSurface)
            GLES20.glViewport(0, 0, outputSize.width, outputSize.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glUniformMatrix4fv(texMatrixUniform, 1, false, transform, 0)
            // SOL-R14: the picture controls at the one hook the direct-to-Surface tier has.
            val packed = adjust.get()
            if (packed == null) {
                GLES20.glUniform1f(colorEnabledUniform, 0f)
            } else {
                GLES20.glUniformMatrix3fv(colorMatrixUniform, 1, false, packed, 0)
                GLES20.glUniform3f(colorOffsetUniform, packed[9], packed[10], packed[11])
                GLES20.glUniform1f(colorEnabledUniform, 1f)
            }
            GLES20.glUniform1f(ditherStepUniform, ditherStep.get())
            GLES20.glUniform1f(debandThresholdUniform, debandThreshold.get())
            // The ring is walked in OUTPUT texels on this tier, which is an approximation the Metal
            // body does not need: there the source's own size is known, here the external texture's
            // is not exposed. At or near 1:1 the two agree; on a large upscale the ring is wider in
            // source terms than asked for, which softens more, so the range is the knob to lower.
            val ring = debandRange.get()
            GLES20.glUniform2f(
                debandStepUniform,
                ring / outputSize.width.coerceAtLeast(1),
                ring / outputSize.height.coerceAtLeast(1),
            )
            GLES20.glUniform1f(debandSeedUniform, (debandSeed.getAndIncrement() % 1024).toFloat())
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)
            VERTICES.position(0)
            TEX_COORDS.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            glCheck("draw external texture")
            // API 33 exposes Image.getFence(), so that path waits on a second handler and lets this
            // GL thread pipeline subsequent blits. API 29-32 have no public acquire-fence API for
            // Bitmap; glFinish is the portable correctness fallback there.
            if (imageHandler == null) {
                GLES20.glFinish()
                glCheck("finish RGBA output")
            }
            eglCheck(EGL14.eglSwapBuffers(display, outputSurface), "swap RGBA output")
        } catch (failure: Throwable) {
            check(queue.cancelOutput()) { "the failed RGBA swap lost its output ownership" }
            throw failure
        }
    }

    private fun rebuildOutput() {
        val sourceSize = checkNotNull(configuredSourceSize) { "RGBA output has no source size" }
        val outputSize = checkNotNull(configuredOutputSize) { "RGBA output has no viewport size" }
        check(
            HardwareBuffer.isSupported(
                outputSize.width,
                outputSize.height,
                HardwareBuffer.RGBA_8888,
                1,
                RGBA_OUTPUT_USAGE,
            ),
        ) {
            "Android cannot allocate a ${outputSize.width}x${outputSize.height} GPU RGBA output"
        }
        destroyOutput(force = false)
        val reader = ImageReader.newInstance(
            outputSize.width,
            outputSize.height,
            PixelFormat.RGBA_8888,
            MAX_ACQUIRED_IMAGES,
            RGBA_OUTPUT_USAGE,
        )
        var surface = EGL14.EGL_NO_SURFACE
        var queue: OutputQueue? = null
        try {
            surface = EGL14.eglCreateWindowSurface(
                display,
                config,
                reader.surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(surface !== EGL14.EGL_NO_SURFACE) {
                "EGL could not target the RGBA ImageReader: 0x${EGL14.eglGetError().toString(16)}"
            }
            val createdQueue = OutputQueue(
                reader = reader,
                frameSize = sourceSize,
                rotationDegrees = configuredRotation,
                colorSpace = configuredColorSpace,
                reportFailure = reportFailure,
                recordSuperseded = recordSuperseded,
                onCapacityAvailable = { available ->
                    (imageHandler ?: handler).post { acquireAndPublish(available) }
                },
                onClosed = { closedQueue -> synchronized(queueFence) { allQueues.remove(closedQueue) } },
            )
            queue = createdQueue
            synchronized(queueFence) { allQueues += createdQueue }
            reader.setOnImageAvailableListener(
                { acquireAndPublish(createdQueue) },
                imageHandler ?: handler,
            )
            outputQueue = createdQueue
            outputSurface = surface
        } catch (failure: Throwable) {
            if (surface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (queue != null) queue.forceClose() else reader.close()
            throw failure
        }
    }

    private fun acquireAndPublish(queue: OutputQueue) {
        if (closed || !queue.active || !queue.hasAcquisitionCapacity) return
        var image: Image? = null
        var registered = false
        try {
            val acquired = queue.reader.acquireNextImage() ?: return
            image = acquired
            if (!queue.register(acquired)) return
            registered = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) waitForAcquireFence(acquired)
            if (closed || !queue.active) return
            val buffer = checkNotNull(acquired.hardwareBuffer) {
                "RGBA ImageReader returned an image without a HardwareBuffer"
            }
            val bitmap = checkNotNull(buffer.use {
                Bitmap.wrapHardwareBuffer(it, queue.bitmapColorSpace)
            }) { "Android could not wrap the RGBA HardwareBuffer as a Bitmap" }
            bitmap.setHasAlpha(false)
            lateinit var lease: ImageLease
            lease = ImageLease(acquired, bitmap) { queue.remove(lease) }
            if (!queue.promote(acquired, lease)) return
            image = null // Ownership moved into the published lease.
            publishOnGlThread(
                queue,
                lease,
                AndroidGpuImageFrame(
                    bitmap,
                    queue.frameSize,
                    queue.rotationDegrees,
                    lease::close,
                ),
            )
        } catch (failure: Throwable) {
            val outcomeWasPending = if (registered) {
                image?.let(queue::markFailed) == true
            } else {
                queue.failNextUnacquired()
            }
            if (outcomeWasPending) reportFailure(failure)
        } finally {
            image?.let { acquired ->
                queue.remove(acquired)
                acquired.close()
            }
        }
    }

    /** Publication and force-close both run on this handler, removing their check/use race. */
    private fun publishOnGlThread(queue: OutputQueue, lease: ImageLease, frame: AndroidGpuImageFrame) {
        if (!handler.post {
                if (closed || !queue.markPublished(lease)) frame.close() else publish(frame)
            }
        ) {
            frame.close()
        }
    }

    private fun destroyOutput(force: Boolean) {
        val surface = outputSurface
        val queue = outputQueue
        outputSurface = EGL14.EGL_NO_SURFACE
        outputQueue = null
        var failure: Throwable? = null
        failure = cleanupFailure(failure) { queue?.reader?.setOnImageAvailableListener(null, null) }
        if (surface !== EGL14.EGL_NO_SURFACE) {
            failure = cleanupFailure(failure) { makeCurrent(pbuffer) }
            failure = cleanupFailure(failure) {
                eglCheck(EGL14.eglDestroySurface(display, surface), "destroy RGBA output")
            }
        }
        if (force) {
            synchronized(queueFence) { allQueues.toList() }.forEach(OutputQueue::forceClose)
        } else {
            queue?.retire()
        }
        failure?.let { throw it }
    }

    fun beginClose() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        failure = cleanupFailure(failure) { surfaceTexture.setOnFrameAvailableListener(null) }
        failure = cleanupFailure(failure) {
            outputQueue?.reader?.setOnImageAvailableListener(null, null)
        }
        recordSuperseded(frameConfigurations.close())
        failure?.let { throw it }
    }

    fun finishClose() {
        beginClose()
        var failure: Throwable? = null
        failure = cleanupFailure(failure) { makeCurrent(pbuffer) }
        failure = cleanupFailure(failure) { destroyOutput(force = true) }
        failure = cleanupFailure(failure, decoderSurface::release)
        failure = cleanupFailure(failure, surfaceTexture::release)
        failure = cleanupFailure(failure) {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            glCheck("delete bridge resources")
        }
        failure = cleanupFailure(failure) {
            eglCheck(
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                ),
                "clear current context",
            )
            currentSurface = EGL14.EGL_NO_SURFACE
        }
        failure = cleanupFailure(failure) {
            eglCheck(EGL14.eglDestroySurface(display, pbuffer), "destroy pbuffer")
        }
        failure = cleanupFailure(failure) {
            eglCheck(EGL14.eglDestroyContext(display, context), "destroy context")
        }
        failure = cleanupFailure(failure) { eglCheck(EGL14.eglTerminate(display), "terminate") }
        failure = cleanupFailure(failure) { eglCheck(EGL14.eglReleaseThread(), "release thread") }
        failure?.let { throw it }
    }

    override fun close() {
        beginClose()
        finishClose()
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (currentSurface === surface) return
        eglCheck(EGL14.eglMakeCurrent(display, surface, surface, context), "make current")
        currentSurface = surface
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 16
        const val MAX_ACQUIRED_IMAGES = 6
        val VERTICES: ByteBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TEX_COORDS: ByteBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform mat3 uColorMatrix;
            uniform vec3 uColorOffset;
            uniform float uColorEnabled;
            uniform float uDitherStep;
            uniform float uDebandThreshold;
            uniform vec2 uDebandStep;
            uniform float uDebandSeed;
            varying vec2 vTexCoord;

            float kpHash(vec2 p, float seed) {
                return fract(sin(dot(vec3(p, seed), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
            }

            /* The 8x8 ordered Bayer value for a pixel, in 0..63.
             *
             * Computed rather than read from a const array, because GLES2 cannot index an array
             * with a non-constant expression. This is the standard bit-interleave of the recursive
             * construction and produces the SAME matrix the Metal body spells out literally, which
             * is what lets both renderers band a gradient the same way. */
            float kpBayer8(vec2 pixel) {
                vec2 p = floor(mod(pixel, 8.0));
                float x = p.x;
                float y = p.y;
                float xb2 = floor(x / 4.0);
                float xb1 = floor(mod(x / 2.0, 2.0));
                float xb0 = mod(x, 2.0);
                float yb2 = floor(y / 4.0);
                float yb1 = floor(mod(y / 2.0, 2.0));
                float yb0 = mod(y, 2.0);
                /* Interleaved as x2 y2 x1 y1 x0 y0, most significant first: the y bits carry the
                 * coarse split and the x bits the fine one, which is the classic ordering. */
                return 32.0 * xb2 + 16.0 * yb2 + 8.0 * xb1 + 4.0 * yb1 + 2.0 * xb0 + yb0;
            }

            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                /* Debanding, this tier's version of RQ-2.
                 *
                 * It differs from the Metal body in a way worth stating rather than hiding: there
                 * the pass runs on the Y, Cb and Cr planes BEFORE the matrix, which is where mpv
                 * does it. Here MediaCodec hands over an external texture that is already RGB, so
                 * the ring is walked on the converted colour instead. Same shape, same threshold
                 * law, one conversion later. */
                if (uDebandThreshold > 0.0) {
                    float angle = kpHash(gl_FragCoord.xy, uDebandSeed) * 6.2831853;
                    vec2 dir = vec2(cos(angle), sin(angle)) * uDebandStep;
                    vec2 perp = vec2(-dir.y, dir.x);
                    vec3 a = texture2D(uTexture, vTexCoord + dir).rgb;
                    vec3 b = texture2D(uTexture, vTexCoord - dir).rgb;
                    vec3 d = texture2D(uTexture, vTexCoord + perp).rgb;
                    vec3 e = texture2D(uTexture, vTexCoord - perp).rgb;
                    vec3 avg = (a + b + d + e) * 0.25;
                    /* Per channel, and against the AVERAGE rather than the worst tap: a one step
                     * band is smaller than any usable worst-tap threshold, so that test would
                     * reject exactly what this pass exists to fix. */
                    vec3 flat3 = step(abs(c.rgb - avg), vec3(uDebandThreshold));
                    c.rgb = mix(c.rgb, avg, flat3);
                }
                if (uColorEnabled > 0.5) {
                    c.rgb = clamp(uColorMatrix * c.rgb + uColorOffset, 0.0, 1.0);
                }
                /* Last, and only when asked: one output step of centred ordered noise, the same
                 * amplitude and the same centring law as the Metal path (17.21 RQ-1). Zero is the
                 * pre-17.21 write, bit for bit. */
                if (uDitherStep > 0.0) {
                    float pattern = (kpBayer8(gl_FragCoord.xy) + 0.5) / 64.0 - 0.5;
                    c.rgb = clamp(c.rgb + pattern * uDitherStep, 0.0, 1.0);
                }
                gl_FragColor = c;
            }
        """

        /**
         * SOL-R14's Android half: the engine's ONE colour-matrix law, packed for the blit.
         * Nine COLUMN-major 3x3 values plus three unit-domain offsets, exactly the Metal
         * pack transposed, because GLES2 refuses transpose=true. Null means disabled, which
         * keeps the untouched path bit-exact.
         */
        fun packGlAdjust(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments): FloatArray? {
            if (adjustments.isIdentity) return null
            val m = adjustments.toColorMatrix()
            return floatArrayOf(
                m[0], m[5], m[10],
                m[1], m[6], m[11],
                m[2], m[7], m[12],
                m[4], m[9], m[14],
            )
        }

        fun create(
            handler: Handler,
            imageHandler: Handler?,
            frameConfigurations: FrameConfigurationBook,
            requestedViewport: AtomicReference<GpuViewport?>,
            adjust: AtomicReference<FloatArray?>,
            ditherStep: java.util.concurrent.atomic.AtomicReference<Float>,
            debandThreshold: java.util.concurrent.atomic.AtomicReference<Float>,
            debandRange: java.util.concurrent.atomic.AtomicReference<Float>,
            publish: (AndroidGpuImageFrame) -> Unit,
            recordSuperseded: (Long) -> Unit,
            reportFailure: (Throwable) -> Unit,
        ): GlState {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display !== EGL14.EGL_NO_DISPLAY) { "EGL has no display" }
            var initialized = false
            var context = EGL14.EGL_NO_CONTEXT
            var pbuffer = EGL14.EGL_NO_SURFACE
            var texture = 0
            var surfaceTexture: SurfaceTexture? = null
            var decoderSurface: Surface? = null
            var program = 0
            try {
                val version = IntArray(2)
                eglCheck(EGL14.eglInitialize(display, version, 0, version, 1), "initialize")
                initialized = true
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                eglCheck(
                    EGL14.eglChooseConfig(
                        display,
                        intArrayOf(
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_ALPHA_SIZE, 8,
                            EGLExt.EGL_RECORDABLE_ANDROID, 1,
                            EGL14.EGL_NONE,
                        ),
                        0,
                        configs,
                        0,
                        configs.size,
                        count,
                        0,
                    ) && count[0] > 0,
                    "choose config",
                )
                val config = requireNotNull(configs[0])
                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
                check(context !== EGL14.EGL_NO_CONTEXT) { "EGL could not create a GLES2 context" }
                pbuffer = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
                check(pbuffer !== EGL14.EGL_NO_SURFACE) { "EGL could not create a pbuffer" }
                eglCheck(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "make initial context current")
                texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                check(texture != 0) { "GLES could not create an external texture" }
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                glCheck("configure external texture")
                surfaceTexture = SurfaceTexture(texture).apply {
                    setDefaultBufferSize(DEFAULT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE)
                }
                decoderSurface = Surface(surfaceTexture)
                program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                val position = GLES20.glGetAttribLocation(program, "aPosition")
                val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
                val sampler = GLES20.glGetUniformLocation(program, "uTexture")
                val texMatrixUniform = GLES20.glGetUniformLocation(program, "uTexMatrix")
                val colorMatrixUniform = GLES20.glGetUniformLocation(program, "uColorMatrix")
                val colorOffsetUniform = GLES20.glGetUniformLocation(program, "uColorOffset")
                val colorEnabledUniform = GLES20.glGetUniformLocation(program, "uColorEnabled")
                val ditherStepUniform = GLES20.glGetUniformLocation(program, "uDitherStep")
                val debandThresholdUniform = GLES20.glGetUniformLocation(program, "uDebandThreshold")
                val debandStepUniform = GLES20.glGetUniformLocation(program, "uDebandStep")
                val debandSeedUniform = GLES20.glGetUniformLocation(program, "uDebandSeed")
                check(position >= 0 && texCoord >= 0 && sampler >= 0 && texMatrixUniform >= 0) {
                    "Android GPU image shader interface was optimized away"
                }
                val state = GlState(
                    handler = handler,
                    imageHandler = imageHandler,
                    frameConfigurations = frameConfigurations,
                    requestedViewport = requestedViewport,
                    publish = publish,
                    recordSuperseded = recordSuperseded,
                    reportFailure = reportFailure,
                    display = display,
                    config = config,
                    context = context,
                    pbuffer = pbuffer,
                    texture = texture,
                    surfaceTexture = surfaceTexture,
                    decoderSurface = decoderSurface,
                    program = program,
                    position = position,
                    texCoord = texCoord,
                    sampler = sampler,
                    texMatrixUniform = texMatrixUniform,
                    colorMatrixUniform = colorMatrixUniform,
                    colorOffsetUniform = colorOffsetUniform,
                    colorEnabledUniform = colorEnabledUniform,
                    adjust = adjust,
                    ditherStep = ditherStep,
                    ditherStepUniform = ditherStepUniform,
                    debandThreshold = debandThreshold,
                    debandRange = debandRange,
                    debandThresholdUniform = debandThresholdUniform,
                    debandStepUniform = debandStepUniform,
                    debandSeedUniform = debandSeedUniform,
                )
                surfaceTexture.setOnFrameAvailableListener(
                    {
                        runCatching(state::drawNewestFrame).onFailure(reportFailure)
                    },
                    handler,
                )
                return state
            } catch (failure: Throwable) {
                var teardownFailure: Throwable? = failure
                teardownFailure = cleanupFailure(teardownFailure) { decoderSurface?.release() }
                teardownFailure = cleanupFailure(teardownFailure) { surfaceTexture?.release() }
                if (program != 0) {
                    teardownFailure = cleanupFailure(teardownFailure) { GLES20.glDeleteProgram(program) }
                }
                if (texture != 0) {
                    teardownFailure = cleanupFailure(teardownFailure) {
                        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                    }
                }
                if (initialized) {
                    teardownFailure = cleanupFailure(teardownFailure) {
                        EGL14.eglMakeCurrent(
                            display,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT,
                        )
                    }
                    if (pbuffer !== EGL14.EGL_NO_SURFACE) {
                        teardownFailure = cleanupFailure(teardownFailure) {
                            EGL14.eglDestroySurface(display, pbuffer)
                        }
                    }
                    if (context !== EGL14.EGL_NO_CONTEXT) {
                        teardownFailure = cleanupFailure(teardownFailure) {
                            EGL14.eglDestroyContext(display, context)
                        }
                    }
                    teardownFailure = cleanupFailure(teardownFailure) { EGL14.eglTerminate(display) }
                    teardownFailure = cleanupFailure(teardownFailure) { EGL14.eglReleaseThread() }
                }
                throw requireNotNull(teardownFailure)
            }
        }
    }
}

/** One ImageReader generation shared by the GL producer and API-33 fence-wait handler. */
private class OutputQueue(
    val reader: ImageReader,
    val frameSize: VideoSize,
    val rotationDegrees: Int,
    val colorSpace: AndroidRgbColorSpace,
    private val reportFailure: (Throwable) -> Unit,
    private val recordSuperseded: (Long) -> Unit,
    private val onCapacityAvailable: (OutputQueue) -> Unit,
    private val onClosed: (OutputQueue) -> Unit,
) {
    private val book = OutputGenerationLeaseBook<Image, ImageLease>(GlState.MAX_ACQUIRED_IMAGES)
    val bitmapColorSpace: ColorSpace = colorSpace.toAndroidColorSpace()

    val active: Boolean get() = book.active
    val hasAcquisitionCapacity: Boolean get() = book.hasAcquisitionCapacity

    fun beginOutput(): Boolean = book.beginOutput()

    fun cancelOutput(): Boolean = book.cancelOutput()

    fun register(image: Image): Boolean = book.register(image)

    fun promote(image: Image, lease: ImageLease): Boolean = book.promote(image, lease)

    fun markPublished(lease: ImageLease): Boolean = book.markPublished(lease)

    fun markFailed(image: Image): Boolean = book.markFailed(image)

    fun failNextUnacquired(): Boolean = book.failNextUnacquired()

    fun remove(image: Image) {
        val resolution = book.removeImage(image)
        recordSuperseded(resolution.supersededFrames)
        if (resolution.closeReader) closeReader() else if (book.active) onCapacityAvailable(this)
    }

    fun remove(lease: ImageLease) {
        val resolution = book.removeLease(lease)
        recordSuperseded(resolution.supersededFrames)
        if (resolution.closeReader) closeReader() else if (book.active) onCapacityAvailable(this)
    }

    /** Stops production but keeps the reader alive until every published lease is returned. */
    fun retire() {
        val resolution = book.retire()
        recordSuperseded(resolution.supersededFrames)
        if (resolution.closeReader) closeReader()
    }

    /** Final renderer teardown: the producer is gone, so callbacks and leases can be forced shut. */
    fun forceClose() {
        val closure = book.forceDrain()
        recordSuperseded(closure.supersededFrames)
        closure.images.forEach { image -> runCatching(image::close).onFailure(reportFailure) }
        closure.leases.forEach { lease -> runCatching(lease::close).onFailure(reportFailure) }
        if (closure.closeReader) closeReader()
    }

    private fun closeReader() {
        runCatching(reader::close).onFailure(reportFailure)
        onClosed(this)
    }

}

/** Pure ownership state for an output generation; split out so retirement is host-testable. */
internal class OutputGenerationLeaseBook<I : Any, L : Any>(
    private val capacity: Int = Int.MAX_VALUE,
) {
    init {
        require(capacity > 0)
    }

    private val fence = Any()
    private var accepting = true
    private var readerClosed = false
    private var unacquiredFrames = 0L
    private val images = mutableMapOf<I, Boolean>()
    private val leases = mutableMapOf<L, Boolean>()

    val active: Boolean get() = synchronized(fence) { accepting && !readerClosed }
    val hasCapacity: Boolean get() = synchronized(fence) {
        accepting && !readerClosed && unacquiredFrames + images.size + leases.size < capacity
    }
    val hasAcquisitionCapacity: Boolean get() = synchronized(fence) {
        accepting &&
            !readerClosed &&
            unacquiredFrames > 0L &&
            images.size + leases.size < capacity
    }

    /** Registers a successful GL producer attempt before eglSwapBuffers can wake ImageReader. */
    fun beginOutput(): Boolean = synchronized(fence) {
        if (
            !accepting ||
            readerClosed ||
            unacquiredFrames + images.size + leases.size >= capacity
        ) return@synchronized false
        unacquiredFrames++
        true
    }

    /** Cancels the most recent producer attempt when eglSwapBuffers itself fails. */
    fun cancelOutput(): Boolean = synchronized(fence) {
        if (unacquiredFrames <= 0L) return@synchronized false
        unacquiredFrames--
        true
    }

    fun register(image: I): Boolean = synchronized(fence) {
        if (
            !accepting ||
            readerClosed ||
            unacquiredFrames <= 0L ||
            images.size + leases.size >= capacity ||
            images.containsKey(image)
        ) return@synchronized false
        unacquiredFrames--
        images[image] = true
        true
    }

    fun promote(image: I, lease: L): Boolean = synchronized(fence) {
        if (!accepting || leases.containsKey(lease)) return@synchronized false
        val outcomePending = images.remove(image) ?: return@synchronized false
        leases[lease] = outcomePending
        true
    }

    /** Resolves the renderer outcome while retaining the lease until Compose's GPU fence retires. */
    fun markPublished(lease: L): Boolean = synchronized(fence) {
        if (!accepting || leases[lease] != true) return@synchronized false
        leases[lease] = false
        true
    }

    /** Transfers an in-flight image's renderer outcome to the failure counter. */
    fun markFailed(image: I): Boolean = synchronized(fence) {
        if (images[image] != true) return@synchronized false
        images[image] = false
        true
    }

    /** Transfers an acquire failure's oldest not-yet-acquired output to the failure counter. */
    fun failNextUnacquired(): Boolean = synchronized(fence) {
        if (!accepting || unacquiredFrames <= 0L) return@synchronized false
        unacquiredFrames--
        true
    }

    fun removeImage(image: I): OutputGenerationResolution = synchronized(fence) {
        val superseded = if (images.remove(image) == true) 1L else 0L
        OutputGenerationResolution(markReaderClosedIfDrained(), superseded)
    }

    fun removeLease(lease: L): OutputGenerationResolution = synchronized(fence) {
        val superseded = if (leases.remove(lease) == true) 1L else 0L
        OutputGenerationResolution(markReaderClosedIfDrained(), superseded)
    }

    fun retire(): OutputGenerationResolution = synchronized(fence) {
        if (!accepting) return@synchronized OutputGenerationResolution(closeReader = false, supersededFrames = 0L)
        accepting = false
        val superseded = resolveEveryPendingOutcome()
        OutputGenerationResolution(markReaderClosedIfDrained(), superseded)
    }

    fun forceDrain(): Drain<I, L> = synchronized(fence) {
        accepting = false
        val closeReader = !readerClosed
        readerClosed = true
        val superseded = resolveEveryPendingOutcome()
        Drain(images.keys.toList(), leases.keys.toList(), closeReader, superseded).also {
            images.clear()
            leases.clear()
        }
    }

    private fun resolveEveryPendingOutcome(): Long {
        var resolved = unacquiredFrames
        unacquiredFrames = 0L
        images.entries.forEach { entry ->
            if (entry.value) {
                resolved++
                entry.setValue(false)
            }
        }
        leases.entries.forEach { entry ->
            if (entry.value) {
                resolved++
                entry.setValue(false)
            }
        }
        return resolved
    }

    private fun markReaderClosedIfDrained(): Boolean {
        if (accepting || readerClosed || images.isNotEmpty() || leases.isNotEmpty()) return false
        readerClosed = true
        return true
    }

    internal class Drain<I : Any, L : Any>(
        val images: List<I>,
        val leases: List<L>,
        val closeReader: Boolean,
        val supersededFrames: Long,
    )
}

internal data class OutputGenerationResolution(
    val closeReader: Boolean,
    val supersededFrames: Long,
)

internal data class FrameConfiguration(
    val size: VideoSize,
    val rotationDegrees: Int,
    val androidColorSpace: AndroidRgbColorSpace = AndroidRgbColorSpace.Srgb,
)

internal data class GpuViewport(
    val width: Int,
    val height: Int,
)

/** Converts the renderer contract's logical extent into a safe physical-pixel bound. */
internal fun physicalGpuViewport(width: Int, height: Int, scale: Float): GpuViewport? {
    if (width <= 0 || height <= 0 || !scale.isFinite() || scale <= 0f) return null
    val physicalWidth =
        (width.toDouble() * scale.toDouble()).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    val physicalHeight =
        (height.toDouble() * scale.toDouble()).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    if (physicalWidth <= 0 || physicalHeight <= 0) return null
    return GpuViewport(physicalWidth, physicalHeight)
}

/**
 * Fits the unrotated RGBA buffer into the physical display footprint without upscaling it.
 *
 * Pixel aspect is reflected in the target footprint, while the original [VideoSize] remains frame
 * metadata for Compose layout. A quarter turn swaps the fitted footprint back into the unrotated
 * buffer's axes. Each axis is capped independently: the original frame metadata makes Compose
 * restore pixel aspect, so forcing this intermediate RGBA buffer back to display aspect would
 * unnecessarily discard resolution on the other axis at native anamorphic size.
 */
internal fun fittedGpuOutputSize(
    sourceSize: VideoSize,
    rotationDegrees: Int,
    viewport: GpuViewport?,
): VideoSize? {
    if (sourceSize.width <= 0 || sourceSize.height <= 0) return null
    if (viewport == null || viewport.width <= 0 || viewport.height <= 0) return null
    val displayWidth = sourceSize.displayWidth.takeIf { it > 0 } ?: sourceSize.width
    val rotation = normalizedGpuQuarterTurn(rotationDegrees)
    val quarterTurned = rotation == 90 || rotation == 270
    val contentWidth = (if (quarterTurned) sourceSize.height else displayWidth).toLong()
    val contentHeight = (if (quarterTurned) displayWidth else sourceSize.height).toLong()
    val fitsByHeight = contentWidth * viewport.height <= contentHeight * viewport.width
    val footprintWidth: Int
    val footprintHeight: Int
    if (fitsByHeight) {
        footprintHeight = viewport.height
        footprintWidth = (contentWidth * viewport.height / contentHeight).toInt().coerceAtLeast(1)
    } else {
        footprintWidth = viewport.width
        footprintHeight = (contentHeight * viewport.width / contentWidth).toInt().coerceAtLeast(1)
    }
    val desiredWidth = if (quarterTurned) footprintHeight else footprintWidth
    val desiredHeight = if (quarterTurned) footprintWidth else footprintHeight
    return VideoSize(
        width = minOf(sourceSize.width, desiredWidth).coerceAtLeast(1),
        height = minOf(sourceSize.height, desiredHeight).coerceAtLeast(1),
    )
}

internal data class MatchedFrameConfiguration(
    val configuration: FrameConfiguration,
    val skippedFrames: Long,
)

/**
 * Configurations are registered immediately before MediaCodec releases each buffer. The exact
 * release timestamp becomes SurfaceTexture's timestamp; skipped older buffers are discarded when
 * the newest texture is latched, so a format change cannot relabel an older queued frame. Codec
 * registration races GL consumption and close, so every operation is one bounded critical section.
 */
internal class FrameConfigurationBook(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0)
    }

    private val fence = Any()
    private val configurations = TreeMap<Long, FrameConfiguration>()
    private val resolvedTimestamps = linkedSetOf<Long>()
    private var accepting = true
    private var prunedFrames = 0L

    internal val size: Int get() = synchronized(fence) { configurations.size }

    fun describeMissing(timestampNanos: Long): String = synchronized(fence) {
            "requested=$timestampNanos pending=${configurations.size} " +
            "first=${configurations.firstKeyOrNull()} last=${configurations.lastKeyOrNull()} " +
            "pruned=$prunedFrames resolved=${timestampNanos in resolvedTimestamps}/" +
            "${resolvedTimestamps.size} accepting=$accepting"
    }

    fun wasResolved(timestampNanos: Long): Boolean = synchronized(fence) {
        timestampNanos in resolvedTimestamps
    }

    fun register(timestampNanos: Long, configuration: FrameConfiguration): Boolean = synchronized(fence) {
        if (!accepting) return@synchronized false
        if (configurations.put(timestampNanos, configuration) != null) prunedFrames++
        // SurfaceTexture updateTexImage normally latches the newest queued buffer, so stale entries
        // are useful only across a bounded producer pipeline. Exact matching remains mandatory:
        // if a device ever latches a timestamp older than this generous bound, takeExact returns
        // null and the bridge fails into software recovery rather than applying wrong geometry.
        while (configurations.size > capacity) {
            configurations.pollFirstEntry()
            prunedFrames++
        }
        true
    }

    fun takeExact(timestampNanos: Long): MatchedFrameConfiguration? = synchronized(fence) {
        val exact = configurations[timestampNanos] ?: return@synchronized null
        val resolved = configurations.headMap(timestampNanos, true).keys.toList()
        val skippedFrames = prunedFrames + (resolved.size - 1).toLong()
        configurations.headMap(timestampNanos, true).clear()
        rememberResolved(resolved)
        prunedFrames = 0L
        MatchedFrameConfiguration(exact, skippedFrames)
    }

    /** Removes a configuration whose matching MediaCodec release failed before reaching Surface. */
    fun cancel(timestampNanos: Long): Boolean = synchronized(fence) {
        if (configurations.remove(timestampNanos) != null) return@synchronized true
        // The only absent registered value can be an out-of-order insertion immediately pruned by
        // the capacity bound. Release/cancel are serialized on MediaCodec's one decoder thread.
        if (prunedFrames <= 0L) return@synchronized false
        prunedFrames--
        true
    }

    /** Returns every accepted frame that close prevents SurfaceTexture from consuming. */
    fun close(): Long = synchronized(fence) {
        if (!accepting) return@synchronized 0L
        accepting = false
        val pendingFrames = prunedFrames + configurations.size.toLong()
        configurations.clear()
        resolvedTimestamps.clear()
        prunedFrames = 0L
        pendingFrames
    }

    private fun rememberResolved(timestamps: List<Long>) {
        timestamps.forEach { timestamp ->
            resolvedTimestamps.remove(timestamp)
            resolvedTimestamps += timestamp
        }
        while (resolvedTimestamps.size > RESOLVED_HISTORY_CAPACITY) {
            val oldest = resolvedTimestamps.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val RESOLVED_HISTORY_CAPACITY = DEFAULT_CAPACITY * 2
    }
}

private fun <V> TreeMap<Long, V>.firstKeyOrNull(): Long? = if (isEmpty()) null else firstKey()

private fun <V> TreeMap<Long, V>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()

/** Holds the BufferQueue slot until the display lease expires. */
private class ImageLease(
    private val image: Image,
    @Suppress("unused") private val bitmap: Bitmap,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            image.close()
        } finally {
            onClose()
        }
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
private fun waitForAcquireFence(image: Image) {
    image.fence.use { fence ->
        if (fence.isValid) {
            check(fence.await(Duration.ofMillis(ACQUIRE_FENCE_TIMEOUT_MILLIS))) {
                "RGBA ImageReader acquire fence did not signal within $ACQUIRE_FENCE_TIMEOUT_MILLIS ms"
            }
        }
    }
}

internal enum class AndroidRgbColorSpace {
    Srgb,
    Bt709,
    SmpteC,
    Bt601Pal,
    Bt2020,
}

internal fun androidRgbColorSpace(color: ColorSpaceInfo): AndroidRgbColorSpace =
    requireNotNull(androidRgbColorSpaceOrNull(color)) {
        "Compose GPU output cannot tag $color as an Android RGB color space"
    }

internal fun androidRgbColorSpaceOrNull(color: ColorSpaceInfo): AndroidRgbColorSpace? = when {
    color.matrix == ColorMatrix.Bt709 && color.primaries == ColorPrimaries.Bt709 &&
        color.transfer == ColorTransfer.Srgb -> AndroidRgbColorSpace.Srgb
    color.matrix == ColorMatrix.Bt709 && color.primaries == ColorPrimaries.Bt709 &&
        color.transfer == ColorTransfer.Bt709 -> AndroidRgbColorSpace.Bt709
    color.matrix == ColorMatrix.Smpte170m && color.primaries == ColorPrimaries.Smpte170m &&
        (color.transfer == ColorTransfer.Bt601 || color.transfer == ColorTransfer.Bt709) ->
        AndroidRgbColorSpace.SmpteC
    color.matrix == ColorMatrix.Bt470bg && color.primaries == ColorPrimaries.Bt470bg &&
        (color.transfer == ColorTransfer.Bt601 || color.transfer == ColorTransfer.Bt709) ->
        AndroidRgbColorSpace.Bt601Pal
    color.matrix == ColorMatrix.Bt2020Ncl && color.primaries == ColorPrimaries.Bt2020 &&
        (color.transfer == ColorTransfer.Bt2020Ten || color.transfer == ColorTransfer.Bt2020Twelve) ->
        AndroidRgbColorSpace.Bt2020
    else -> null
}

private fun AndroidRgbColorSpace.toAndroidColorSpace(): ColorSpace = when (this) {
    AndroidRgbColorSpace.Srgb -> ColorSpace.get(ColorSpace.Named.SRGB)
    AndroidRgbColorSpace.Bt709 -> ColorSpace.get(ColorSpace.Named.BT709)
    AndroidRgbColorSpace.SmpteC -> ColorSpace.get(ColorSpace.Named.SMPTE_C)
    AndroidRgbColorSpace.Bt601Pal -> BT601_PAL_COLOR_SPACE
    AndroidRgbColorSpace.Bt2020 -> ColorSpace.get(ColorSpace.Named.BT2020)
}

private val BT601_PAL_COLOR_SPACE: ColorSpace = ColorSpace.Rgb(
    "Rec. ITU-R BT.601 PAL",
    floatArrayOf(0.640f, 0.330f, 0.290f, 0.600f, 0.150f, 0.060f),
    ColorSpace.ILLUMINANT_D65,
    ColorSpace.Rgb.TransferParameters(
        1.0 / 1.099,
        0.099 / 1.099,
        1.0 / 4.5,
        0.081,
        1.0 / 0.45,
    ),
)

private const val ACQUIRE_FENCE_TIMEOUT_MILLIS = 2_000L

private fun floatBuffer(vararg values: Float): ByteBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .also { buffer ->
            buffer.asFloatBuffer().put(values)
            buffer.position(0)
        }

private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    return try {
        val program = GLES20.glCreateProgram()
        check(program != 0) { "GLES could not create the Android GPU image program" }
        try {
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) {
                "Android GPU image program link failed: ${GLES20.glGetProgramInfoLog(program)}"
            }
            program
        } catch (failure: Throwable) {
            GLES20.glDeleteProgram(program)
            throw failure
        }
    } finally {
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    check(shader != 0) { "GLES could not create an Android GPU image shader" }
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        error("Android GPU image shader compilation failed: $log")
    }
    return shader
}

private fun eglCheck(success: Boolean, operation: String) {
    check(success) { "EGL $operation failed: 0x${EGL14.eglGetError().toString(16)}" }
}

private fun glCheck(operation: String) {
    val error = GLES20.glGetError()
    check(error == GLES20.GL_NO_ERROR) { "GLES $operation failed: 0x${error.toString(16)}" }
}

private inline fun cleanupFailure(previous: Throwable?, action: () -> Unit): Throwable? = try {
    action()
    previous
} catch (caught: Throwable) {
    previous?.also { it.addSuppressed(caught) } ?: caught
}

internal fun normalizedGpuQuarterTurn(degrees: Int): Int {
    val normalized = ((degrees % 360) + 360) % 360
    return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 } ?: 0
}
