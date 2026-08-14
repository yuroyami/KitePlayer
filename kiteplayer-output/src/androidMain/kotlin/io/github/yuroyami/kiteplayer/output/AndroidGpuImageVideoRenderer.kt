package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
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

/**
 * Turns direct MediaCodec output into immutable RGBA hardware [Bitmap] frames for a GPU client.
 *
 * MediaCodec writes vendor YUV into an external-OES [SurfaceTexture]. One GLES2 shader pass writes
 * RGBA_8888 into an [ImageReader], and [Bitmap.wrapHardwareBuffer] gives the client a Skia-readable
 * hardware image. No video pixels enter Kotlin or CPU memory.
 *
 * This renderer is SDR-only. Its decoder factory rejects HDR before codec creation rather than
 * clipping it into an 8-bit target. The client callback may run on the renderer's GL thread and
 * must return promptly. Subtitle composition remains the client's job.
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
    private val closed = atomic(false)
    private val bridgeFailure = atomic<Throwable?>(null)
    private val eventFlow = MutableSharedFlow<RendererEvent>(replay = 4, extraBufferCapacity = 4)
    private val bridge = OesRgbaBridge(::publishImage, ::bridgeFailed)
    private val codecTarget = MediaCodecSurfaceTarget(initialSurface = bridge.surface)
    private val directFactory = MediaCodecVideoDecoderFactory(
        target = codecTarget,
        applyCodecRotation = false,
        outputColorValidator = { detected ->
            detected.reliable && isComposeSrgbSafeColor(detected.colorSpace)
        },
    )
    private val decoderFactory = object : VideoDecoderFactory {
        override val name: String = "Android MediaCodec Compose GPU"

        override suspend fun create(stream: PlayerStreamInfo, hwdec: io.github.yuroyami.kiteplayer.HwdecPolicy): VideoDecoder? {
            if (closed.value) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            bridgeFailure.value?.let { throw IllegalStateException("the Android GPU bridge failed", it) }
            stream.colorSpace?.takeUnless(::isComposeSrgbSafeColor)?.let { return null }
            val size = stream.videoSize ?: return null
            if (!bridge.supports(size)) return null
            return try {
                if (closed.value) return null
                directFactory.create(stream, hwdec)?.let { decoder ->
                    BridgeGuardedVideoDecoder(decoder) { bridgeFailure.value }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Frames whose completed RGBA hardware image reached [onImage]. */
    public val presentedFrames: Long get() = presented.value

    /** Frames replaced inside the GPU bridge. Currently zero because SurfaceTexture owns that queue. */
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
            onReleased = { rendered ->
                if (!rendered && !closed.value) failed.incrementAndGet()
            },
        )
        if (!accepted) failed.incrementAndGet()
        return accepted
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

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
            startup.set(runCatching { GlState.create(handler, imageHandler, publish, reportFailure) })
            ready.countDown()
        }
        try {
            state
        } catch (failure: Throwable) {
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
        runOnGlThread { it.prepareFrame(timestampNanos, size, rotationDegrees, colorSpace) }
    }

    fun supports(size: VideoSize): Boolean =
        size.width > 0 &&
            size.height > 0 &&
            HardwareBuffer.isSupported(
                size.width,
                size.height,
                HardwareBuffer.RGBA_8888,
                1,
                RGBA_OUTPUT_USAGE,
            )

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

/** All members are confined to the bridge HandlerThread. */
private class GlState private constructor(
    private val handler: Handler,
    private val imageHandler: Handler?,
    private val publish: (AndroidGpuImageFrame) -> Unit,
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
) : AutoCloseable {
    private val transform = FloatArray(16)
    private var outputQueue: OutputQueue? = null
    private var outputSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var configuredSize: VideoSize = VideoSize(DEFAULT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE)
    private var configuredRotation = 0
    private var configuredColorSpace: AndroidRgbColorSpace = AndroidRgbColorSpace.Srgb
    private val frameConfigurations = FrameConfigurationBook()
    @Volatile private var closed = false
    private val queueFence = Any()
    private val allQueues = mutableSetOf<OutputQueue>()

    fun prepareFrame(
        timestampNanos: Long,
        size: VideoSize,
        rotationDegrees: Int,
        colorSpace: ColorSpaceInfo,
    ) {
        check(!closed) { "Android GPU image renderer is closed" }
        check(size.width > 0 && size.height > 0) { "a ${size.width}x${size.height} frame has no drawable geometry" }
        frameConfigurations.register(
            timestampNanos,
            FrameConfiguration(
                size,
                normalizedGpuQuarterTurn(rotationDegrees),
                androidRgbColorSpace(colorSpace),
            ),
        )
    }

    private fun configure(
        size: VideoSize,
        rotationDegrees: Int,
        colorSpace: AndroidRgbColorSpace,
    ) {
        if (closed || size.width <= 0 || size.height <= 0) return
        val rotation = normalizedGpuQuarterTurn(rotationDegrees)
        if (
            size == configuredSize &&
            rotation == configuredRotation &&
            colorSpace == configuredColorSpace &&
            outputQueue != null
        ) return
        configuredSize = size
        configuredRotation = rotation
        configuredColorSpace = colorSpace
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        rebuildOutput()
    }

    private fun drawNewestFrame() {
        if (closed) return
        makeCurrent(pbuffer)
        surfaceTexture.updateTexImage()
        val timestamp = surfaceTexture.timestamp
        val frameConfiguration = checkNotNull(frameConfigurations.takeExact(timestamp)) {
            "SurfaceTexture produced timestamp $timestamp without its MediaCodec frame configuration"
        }
        configure(
            frameConfiguration.size,
            frameConfiguration.rotationDegrees,
            frameConfiguration.androidColorSpace,
        )
        if (outputQueue == null) rebuildOutput()
        surfaceTexture.getTransformMatrix(transform)
        makeCurrent(outputSurface)
        GLES20.glViewport(0, 0, configuredSize.width, configuredSize.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glUniformMatrix4fv(texMatrixUniform, 1, false, transform, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)
        VERTICES.position(0)
        TEX_COORDS.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        glCheck("draw external texture")
        // API 33 exposes Image.getFence(), so that path waits on a second handler and lets this GL
        // thread pipeline subsequent blits. API 29-32 have no public acquire-fence API for Bitmap;
        // glFinish is the portable correctness fallback there.
        if (imageHandler == null) {
            GLES20.glFinish()
            glCheck("finish RGBA output")
        }
        eglCheck(EGL14.eglSwapBuffers(display, outputSurface), "swap RGBA output")
    }

    private fun rebuildOutput() {
        destroyOutput(force = false)
        val reader = ImageReader.newInstance(
            configuredSize.width,
            configuredSize.height,
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
                size = configuredSize,
                rotationDegrees = configuredRotation,
                colorSpace = configuredColorSpace,
                reportFailure = reportFailure,
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
        try {
            val acquired = queue.reader.acquireNextImage() ?: return
            image = acquired
            if (!queue.register(acquired)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) waitForAcquireFence(acquired)
            if (closed || !queue.active) return
            val buffer = acquired.hardwareBuffer ?: return
            val bitmap = buffer.use {
                Bitmap.wrapHardwareBuffer(it, queue.bitmapColorSpace)
            } ?: return
            bitmap.setHasAlpha(false)
            lateinit var lease: ImageLease
            lease = ImageLease(acquired, bitmap) { queue.remove(lease) }
            if (!queue.promote(acquired, lease)) return
            image = null // Ownership moved into the published lease.
            publishOnGlThread(
                queue,
                AndroidGpuImageFrame(
                    bitmap,
                    queue.size,
                    queue.rotationDegrees,
                    lease::close,
                ),
            )
        } catch (failure: Throwable) {
            reportFailure(failure)
        } finally {
            image?.let { acquired ->
                queue.remove(acquired)
                acquired.close()
            }
        }
    }

    /** Publication and force-close both run on this handler, removing their check/use race. */
    private fun publishOnGlThread(queue: OutputQueue, frame: AndroidGpuImageFrame) {
        if (!handler.post {
                if (closed || !queue.active) frame.close() else publish(frame)
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
        frameConfigurations.clear()
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
        eglCheck(EGL14.eglMakeCurrent(display, surface, surface, context), "make current")
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
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        fun create(
            handler: Handler,
            imageHandler: Handler?,
            publish: (AndroidGpuImageFrame) -> Unit,
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
                check(position >= 0 && texCoord >= 0 && sampler >= 0 && texMatrixUniform >= 0) {
                    "Android GPU image shader interface was optimized away"
                }
                val state = GlState(
                    handler = handler,
                    imageHandler = imageHandler,
                    publish = publish,
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
    val size: VideoSize,
    val rotationDegrees: Int,
    val colorSpace: AndroidRgbColorSpace,
    private val reportFailure: (Throwable) -> Unit,
    private val onCapacityAvailable: (OutputQueue) -> Unit,
    private val onClosed: (OutputQueue) -> Unit,
) {
    private val book = OutputGenerationLeaseBook<Image, ImageLease>(GlState.MAX_ACQUIRED_IMAGES)
    val bitmapColorSpace: ColorSpace = colorSpace.toAndroidColorSpace()

    val active: Boolean get() = book.active
    val hasAcquisitionCapacity: Boolean get() = book.hasCapacity

    fun register(image: Image): Boolean = book.register(image)

    fun promote(image: Image, lease: ImageLease): Boolean = book.promote(image, lease)

    fun remove(image: Image) {
        if (book.removeImage(image)) closeReader() else if (book.active) onCapacityAvailable(this)
    }

    fun remove(lease: ImageLease) {
        if (book.removeLease(lease)) closeReader() else if (book.active) onCapacityAvailable(this)
    }

    /** Stops production but keeps the reader alive until every published lease is returned. */
    fun retire() {
        if (book.retire()) closeReader()
    }

    /** Final renderer teardown: the producer is gone, so callbacks and leases can be forced shut. */
    fun forceClose() {
        val closure = book.forceDrain()
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
    private val images = mutableSetOf<I>()
    private val leases = mutableSetOf<L>()

    val active: Boolean get() = synchronized(fence) { accepting && !readerClosed }
    val hasCapacity: Boolean get() = synchronized(fence) {
        accepting && !readerClosed && images.size + leases.size < capacity
    }

    fun register(image: I): Boolean = synchronized(fence) {
        accepting && !readerClosed && images.size + leases.size < capacity && images.add(image)
    }

    fun promote(image: I, lease: L): Boolean = synchronized(fence) {
        if (!accepting) return@synchronized false
        images.remove(image)
        leases += lease
        true
    }

    /** Returns true exactly once when the retired generation's reader can now close. */
    fun removeImage(image: I): Boolean = synchronized(fence) {
        images.remove(image)
        markReaderClosedIfDrained()
    }

    /** Returns true exactly once when the retired generation's reader can now close. */
    fun removeLease(lease: L): Boolean = synchronized(fence) {
        leases.remove(lease)
        markReaderClosedIfDrained()
    }

    /** Returns true when there was no outstanding image and the reader can close immediately. */
    fun retire(): Boolean = synchronized(fence) {
        accepting = false
        markReaderClosedIfDrained()
    }

    fun forceDrain(): Drain<I, L> = synchronized(fence) {
        accepting = false
        val closeReader = !readerClosed
        readerClosed = true
        Drain(images.toList(), leases.toList(), closeReader).also {
            images.clear()
            leases.clear()
        }
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
    )
}

internal data class FrameConfiguration(
    val size: VideoSize,
    val rotationDegrees: Int,
    val androidColorSpace: AndroidRgbColorSpace = AndroidRgbColorSpace.Srgb,
)

/**
 * Configurations are registered immediately before MediaCodec releases each buffer. The exact
 * release timestamp becomes SurfaceTexture's timestamp; skipped older buffers are discarded when
 * the newest texture is latched, so a format change cannot relabel an older queued frame.
 */
internal class FrameConfigurationBook(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0)
    }

    private val configurations = TreeMap<Long, FrameConfiguration>()

    internal val size: Int get() = configurations.size

    fun register(timestampNanos: Long, configuration: FrameConfiguration) {
        configurations[timestampNanos] = configuration
        // SurfaceTexture updateTexImage normally latches the newest queued buffer, so stale entries
        // are useful only across a bounded producer pipeline. Exact matching remains mandatory:
        // if a device ever latches a timestamp older than this generous bound, takeExact returns
        // null and the bridge fails into software recovery rather than applying wrong geometry.
        while (configurations.size > capacity) configurations.pollFirstEntry()
    }

    fun takeExact(timestampNanos: Long): FrameConfiguration? {
        val exact = configurations[timestampNanos] ?: return null
        configurations.headMap(timestampNanos, true).clear()
        return exact
    }

    fun clear() = configurations.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

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
}

internal fun androidRgbColorSpace(color: ColorSpaceInfo): AndroidRgbColorSpace = when (color.transfer) {
    ColorTransfer.Bt709 -> AndroidRgbColorSpace.Bt709
    ColorTransfer.Srgb -> AndroidRgbColorSpace.Srgb
    else -> error("Compose GPU output cannot tag ${color.transfer} as BT.709 RGB")
}

private fun AndroidRgbColorSpace.toAndroidColorSpace(): ColorSpace = ColorSpace.get(
    when (this) {
        AndroidRgbColorSpace.Srgb -> ColorSpace.Named.SRGB
        AndroidRgbColorSpace.Bt709 -> ColorSpace.Named.BT709
    },
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
