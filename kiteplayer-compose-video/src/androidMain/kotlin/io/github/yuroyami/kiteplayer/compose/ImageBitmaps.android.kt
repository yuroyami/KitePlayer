package io.github.yuroyami.kiteplayer.compose

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.AndroidGpuImageVideoRenderer
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Immutable software images cannot be overwritten if Window completion metrics disappear. */
internal actual class FrameImagePool actual constructor() {
    actual fun imageFor(rgba: ByteArray, width: Int, height: Int): FrameImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return FrameImage(bitmap.asImageBitmap())
    }

    actual fun release() = Unit
}

internal actual fun kiteCodecFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame.asKiteFFmpegFrame())

internal actual fun overlayImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap.asImageBitmap()
}

/**
 * Remembers an Android video state bound to the exact [Window] that will host [KiteVideo].
 *
 * The explicit Window is the public ownership boundary for GPU images. Android exposes frame GPU
 * completion through Window metrics, not through a View, and deriving a Window from a Context is
 * incorrect for dialogs and popups. API 31+ uses the MediaCodec/HardwareBuffer path; older Android
 * versions retain the allocation-safe software path.
 */
@Composable
public fun rememberKiteVideoState(window: Window): KiteVideoState {
    val state = remember(window) { createAndroidKiteVideoState(window) }
    DisposableEffect(state) {
        onDispose { state.renderer.close() }
    }
    return state
}

internal fun createAndroidKiteVideoState(window: Window): KiteVideoState = KiteVideoState(
    pool = FrameImagePool(),
    hardwareRendererFactory = { publish ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            null
        } else {
            try {
                AndroidKiteVideoHardwareRenderer(window, publish)
            } catch (_: Exception) {
                null
            }
        }
    },
)

private class AndroidKiteVideoHardwareRenderer(
    val window: Window,
    publish: (KiteVideoFrame) -> Unit,
) : KiteVideoHardwareRenderer {
    private val consumer = AndroidVideoConsumerGate()
    private val rejected = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var completionTracker: AndroidGpuCompletionTracker? = null
    private val delegate = AndroidGpuImageVideoRenderer { frame ->
        publish(
            KiteVideoFrame(
                image = frame.bitmap.asImageBitmap(),
                size = frame.size,
                rotationDegrees = frame.rotationDegrees,
                requiresCommitFence = true,
                release = frame::close,
            ),
        )
    }
    private val guardedFactories = delegate.videoDecoderFactories().map { factory ->
        ConsumerGuardedDecoderFactory(factory, consumer)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun bindConsumer(view: View, state: KiteVideoState): Long? {
        val tracker = completionTracker ?: AndroidGpuCompletionTracker(
            window = window,
            state = state,
            onWindowLost = { consumer.invalidate("KiteVideo's owning Window was destroyed") },
        ).also {
            completionTracker = it
        }
        val binding = tracker.bind(view)
        if (binding != null || tracker.hasUsableConsumer) {
            consumer.activate()
        } else {
            consumer.pause("KiteVideo has no hardware-accelerated View in its owning Window")
        }
        return binding
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun unbindConsumer(binding: Long?, owner: Any, detail: String) {
        val tracker = completionTracker ?: return
        if (!tracker.unbind(binding, owner)) consumer.pause(detail)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun recordFrame(binding: Long?, owner: Any, frame: KiteVideoFrame?, state: KiteVideoState) {
        val tracker = completionTracker ?: AndroidGpuCompletionTracker(
            window = window,
            state = state,
            onWindowLost = { consumer.invalidate("KiteVideo's owning Window was destroyed") },
        ).also {
            completionTracker = it
        }
        if (!tracker.record(binding, owner, frame)) {
            consumer.pause("KiteVideo lost its hardware Window while drawing")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun canRecordFrame(binding: Long?): Boolean = completionTracker?.isBindingUsable(binding) == true

    val consumerAvailable: Boolean get() = consumer.active

    override val presentedFrames: Long get() = delegate.presentedFrames
    override val supersededFrames: Long get() = delegate.supersededFrames
    override val failedFrames: Long get() = delegate.failedFrames + rejected.get()

    override fun videoDecoderFactories(): List<VideoDecoderFactory> = guardedFactories

    override fun supportedHardwareSurfaces() = delegate.supportedHardwareSurfaces()

    override fun supports(format: PlayerPixelFormat): Boolean =
        consumer.active && delegate.supports(format)

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (!consumer.active) {
            frame.close()
            rejected.incrementAndGet()
            return false
        }
        return delegate.present(frame, targetNanos)
    }

    override fun vsyncIntervalNanos() = delegate.vsyncIntervalNanos()
    override fun setViewport(width: Int, height: Int, scale: Float) = delegate.setViewport(width, height, scale)
    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) = delegate.setScaleMode(mode)
    override suspend fun setOverlay(overlay: SubtitleOverlay?) = delegate.setOverlay(overlay)
    override val events get() = delegate.events

    override fun close() {
        consumer.invalidate("the Compose video renderer closed")
        try {
            delegate.close()
        } finally {
            closeCompletionTrackerOnMain()
        }
    }

    private fun closeCompletionTrackerOnMain() {
        val close = {
            completionTracker?.close()
            completionTracker = null
        }
        if (Looper.myLooper() === Looper.getMainLooper()) {
            close()
            return
        }
        val finished = CountDownLatch(1)
        check(mainHandler.post {
                try {
                    close()
                } finally {
                    finished.countDown()
                }
            }
        ) { "the Android main thread rejected GPU completion observer teardown" }
        check(finished.await(TRACKER_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "the Android GPU completion observer did not close on the main thread"
        }
    }

    private companion object {
        const val TRACKER_CLOSE_TIMEOUT_SECONDS = 5L
    }
}

private class AndroidVideoConsumerGate {
    private var started = false
    private var factoryAvailable = false
    private var epoch = 0L
    private var unavailableReason = "KiteVideo is not attached to its owning hardware Window"

    /** Existing decoder output remains valid across a transient same-Window detach/rebind. */
    val active: Boolean get() = synchronized(this) { started }

    fun activate() = synchronized(this) {
        started = true
        factoryAvailable = true
        unavailableReason = "KiteVideo's Window consumer changed"
    }

    /** Declines new decoder sessions without poisoning the already-running MediaCodec session. */
    fun pause(detail: String) = synchronized(this) {
        factoryAvailable = false
        unavailableReason = detail
    }

    fun invalidate(detail: String) = synchronized(this) {
        epoch += 1L
        started = false
        factoryAvailable = false
        unavailableReason = detail
    }

    fun sessionOrNull(): Session? = synchronized(this) {
        if (factoryAvailable) Session(this, epoch) else null
    }

    fun failureOrNull(sessionEpoch: Long): IllegalStateException? = synchronized(this) {
        if (epoch == sessionEpoch) null else IllegalStateException(unavailableReason)
    }

    class Session internal constructor(
        private val gate: AndroidVideoConsumerGate,
        private val epoch: Long,
    ) {
        fun failureOrNull(): IllegalStateException? = gate.failureOrNull(epoch)

        fun check() {
            failureOrNull()?.let { throw it }
        }
    }
}

private class ConsumerGuardedDecoderFactory(
    private val delegate: VideoDecoderFactory,
    private val consumer: AndroidVideoConsumerGate,
) : VideoDecoderFactory {
    override val name: String = delegate.name

    override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
        val session = consumer.sessionOrNull() ?: return null
        val decoder = delegate.create(stream, hwdec) ?: return null
        return if (session.failureOrNull() == null) {
            ConsumerGuardedDecoder(decoder, session)
        } else {
            decoder.close()
            null
        }
    }
}

private class ConsumerGuardedDecoder(
    private val delegate: VideoDecoder,
    private val session: AndroidVideoConsumerGate.Session,
) : VideoDecoder {
    override val hardware get() = delegate.hardware
    override val isDrained: Boolean get() = delegate.isDrained

    override suspend fun send(packet: PlayerPacket?): Boolean {
        session.check()
        val accepted = delegate.send(packet)
        session.check()
        return accepted
    }

    override suspend fun receive(): VideoFrame? {
        session.check()
        val frame = delegate.receive()
        session.failureOrNull()?.let { failure ->
            frame?.close()
            throw failure
        }
        return frame
    }

    override suspend fun flush(newGeneration: Generation) {
        session.check()
        delegate.flush(newGeneration)
        session.check()
    }

    override fun close() = delegate.close()
}

@Composable
internal actual fun rememberKiteVideoFrameCommitter(
    state: KiteVideoState,
): KiteVideoFrameCommitter {
    val hardware = state.hardwareRenderer as? AndroidKiteVideoHardwareRenderer
        ?: return ImmediateFrameCommitter(state)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ImmediateFrameCommitter(state)
    val view = LocalView.current
    val committer = remember(view, state, hardware.window) {
        AndroidFrameMetricsCommitter(view, state, hardware)
    }
    DisposableEffect(committer) {
        committer.attach()
        onDispose { committer.detach() }
    }
    return committer
}

private class ImmediateFrameCommitter(
    private val state: KiteVideoState,
) : KiteVideoFrameCommitter {
    private val owner = Any()
    override val canDrawCommitFencedFrames: Boolean get() = false

    override fun frameRecorded(frame: KiteVideoFrame?) {
        // A fenced image deliberately remains leased when no GPU-completion observer exists.
        if (frame == null) state.frameCommitted(owner, null)
    }
}

/**
 * `View.drawingTime` and `FrameMetrics.VSYNC_TIMESTAMP / 1_000_000` are the same documented
 * Choreographer value. GPU_DURATION is available from API 31 and is produced only after that
 * frame's GPU work completes. That exact pair is the lease retirement proof.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class AndroidFrameMetricsCommitter(
    private val view: View,
    private val state: KiteVideoState,
    private val hardware: AndroidKiteVideoHardwareRenderer,
) : KiteVideoFrameCommitter,
    View.OnAttachStateChangeListener {
    private val owner = Any()
    private var binding: Long? = null
    override val canDrawCommitFencedFrames: Boolean
        get() = hardware.canRecordFrame(binding)

    fun attach() {
        view.addOnAttachStateChangeListener(this)
        bindIfUsable()
    }

    fun detach() {
        view.removeOnAttachStateChangeListener(this)
        hardware.unbindConsumer(binding, owner, "KiteVideo left its owning Window")
        binding = null
    }

    private fun bindIfUsable() {
        binding = hardware.bindConsumer(view, state)
        if (binding != null) view.postInvalidateOnAnimation()
    }

    override fun onViewAttachedToWindow(attached: View) = bindIfUsable()

    override fun onViewDetachedFromWindow(detached: View) {
        hardware.unbindConsumer(binding, owner, "KiteVideo's View detached from its owning Window")
        binding = null
    }

    override fun frameRecorded(frame: KiteVideoFrame?) {
        hardware.recordFrame(binding, owner, frame, state)
    }
}

/**
 * State-owned completion observer. A disposable Compose node may leave while its hoisted state is
 * still alive; keeping the ledger here prevents abandoning pending ImageReader leases. Rebinding a
 * View to the same Window resumes exact proof frames. Renderer close destroys the producer before
 * state teardown force-releases any proof that can no longer arrive.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class AndroidGpuCompletionTracker(
    private val window: Window,
    private val state: KiteVideoState,
    private val onWindowLost: () -> Unit,
) : Window.OnFrameMetricsAvailableListener,
    View.OnAttachStateChangeListener,
    AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batches = GpuCompletionBatchLedger<CommittedDraw>()
    private val bindings = GpuConsumerBindingBook<View>()
    private var observing = false

    init {
        window.decorView.addOnAttachStateChangeListener(this)
    }

    val hasUsableConsumer: Boolean
        get() = observing && bindings.values().any(::isUsable)

    fun isBindingUsable(binding: Long?): Boolean {
        val view = binding?.let(bindings::get) ?: return false
        return observing && isUsable(view)
    }

    fun bind(view: View): Long? {
        if (!isUsable(view)) return null
        if (!observing) {
            try {
                window.addOnFrameMetricsAvailableListener(this, mainHandler)
                observing = true
            } catch (_: Exception) {
                return null
            }
        }
        return bindings.bind(view)
    }

    fun unbind(binding: Long?, owner: Any): Boolean {
        if (binding != null) bindings.remove(binding)
        // Removing a node replaces its persistent display list. A later same-Window GPU proof is
        // required before the image previously committed by that node can be returned.
        batches.holdUntilNextProof(CommittedDraw(owner, null, recordedVsyncNanos = null))
        requestProofFrame()
        // Do not remove the listener here. Metrics already in flight can still prove pending
        // batches, and a later View can bind to this same state/Window and request another proof.
        return hasUsableConsumer
    }

    fun record(binding: Long?, owner: Any, frame: KiteVideoFrame?): Boolean {
        val view = binding?.let(bindings::get)
        if (!observing || view == null || !isUsable(view)) {
            // An invalid/wrong-Window node is never allowed to acquire a hardware frame. If this
            // branch ever sees one, keep its pending state lease until producer teardown: another
            // Window's GPU completion cannot prove that it stopped sampling.
            if (binding != null && view != null) bindings.remove(binding)
            requestProofFrame()
            return hasUsableConsumer
        }
        val drawingTimeMillis = view.drawingTime
        if (drawingTimeMillis <= 0L) {
            batches.holdUntilNextProof(CommittedDraw(owner, frame, recordedVsyncNanos = null))
            view.postInvalidateOnAnimation()
            return true
        }
        batches.record(
            drawingTimeMillis,
            CommittedDraw(
                owner = owner,
                frame = frame,
                recordedVsyncNanos = drawingTimeMillis * NANOS_PER_MILLISECOND,
            ),
        )
        return true
    }

    override fun onFrameMetricsAvailable(
        observedWindow: Window,
        frameMetrics: FrameMetrics,
        dropCountSinceLastInvocation: Int,
    ) {
        if (!observing || observedWindow !== window) return
        val gpuDuration = frameMetrics.getMetric(FrameMetrics.GPU_DURATION)
        val vsyncTimestamp = frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        if (gpuDuration < 0L || vsyncTimestamp <= 0L) {
            requestProofFrame()
            return
        }
        val completed = batches.completeThroughExact(vsyncTimestamp / NANOS_PER_MILLISECOND)
        if (completed == null) {
            requestProofFrame()
            return
        }
        completed.forEach { draw ->
            state.frameCommitted(
                owner = draw.owner,
                drawn = draw.frame,
                completionVsyncNanos = draw.recordedVsyncNanos,
            )
        }
        if (dropCountSinceLastInvocation > 0 || batches.hasPending) requestProofFrame()
    }

    private fun requestProofFrame() {
        if (!batches.hasPending) return
        // Invalidating the Android View alone can replay Compose's existing display list without
        // executing drawBehind. Tick draw state so a new VSYNC-keyed batch is definitely recorded.
        // POSTED, never written inline: record() runs inside the Compose draw
        // phase, and writing a draw-observed snapshot state from inside the draw that reads it
        // is the reentrant-invalidation shape Compose forbids. The post also keeps the tick on
        // the main thread whichever thread asked for the proof.
        mainHandler.post {
            state.requestCompletionProofDraw()
            bindings.values().forEach { view ->
                if (view.isAttachedToWindow) view.postInvalidateOnAnimation()
            }
        }
    }

    private fun isUsable(view: View): Boolean {
        val decor = window.decorView
        return view.isAttachedToWindow &&
            view.isHardwareAccelerated &&
            decor.isAttachedToWindow &&
            view.windowToken != null &&
            view.windowToken == decor.windowToken
    }

    override fun onViewAttachedToWindow(attached: View) = Unit

    override fun onViewDetachedFromWindow(detached: View) {
        if (detached === window.decorView) onWindowLost()
    }

    override fun close() {
        window.decorView.removeOnAttachStateChangeListener(this)
        if (observing) {
            try {
                window.removeOnFrameMetricsAvailableListener(this)
            } catch (_: Exception) {
                // The renderer is already closing and the Window may already be destroyed.
            }
        }
        observing = false
        bindings.clear()
        batches.clear()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private class CommittedDraw(
        val owner: Any,
        val frame: KiteVideoFrame?,
        val recordedVsyncNanos: Long?,
    )
}

/** The one place the backend pairing is checked, so all three actuals refuse the same way. */
private fun VideoFrame.asKiteFFmpegFrame(): KiteFFmpegVideoFrame = this as? KiteFFmpegVideoFrame
    ?: throw UnsupportedFrameType(
        actual = this::class.simpleName ?: "an unnamed frame type",
        expected = "KiteFFmpegVideoFrame",
    )
