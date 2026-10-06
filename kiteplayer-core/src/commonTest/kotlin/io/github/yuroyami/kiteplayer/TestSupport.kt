package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A clock the test moves by hand.
 *
 * Every timing rule in the engine reads time through [MonotonicClock], which is the reason a whole
 * playback session can be driven through hours of media in a few milliseconds, deterministically.
 * That property is the point of the architecture, so it is exercised by nearly every test here.
 */
internal class TestClock(startNanos: Long = 0L) : MonotonicClock {
    private var now = startNanos
    override fun nanos(): Long = now

    fun advance(duration: Duration) {
        require(!duration.isNegative()) { "a monotonic clock cannot go backwards" }
        now += duration.inWholeNanoseconds
    }

    fun advanceNanos(nanos: Long) {
        require(nanos >= 0) { "a monotonic clock cannot go backwards" }
        now += nanos
    }
}

/** Counts every frame and packet created, so a test can assert nothing leaked. */
internal class LeakLedger {
    private val opened = atomic(0)
    private val closed = atomic(0)
    private val doubleClosed = atomic(0)

    fun onOpen() { opened.incrementAndGet() }

    fun onClose(alreadyClosed: Boolean) {
        if (alreadyClosed) doubleClosed.incrementAndGet() else closed.incrementAndGet()
    }

    val openCount: Int get() = opened.value
    val closeCount: Int get() = closed.value
    val doubleCloseCount: Int get() = doubleClosed.value
    val liveCount: Int get() = opened.value - closed.value
}

internal class FakePacket(
    override val streamIndex: Int,
    override val pts: Pts?,
    override val duration: Pts? = 40.milliseconds.let { Pts(it.inWholeMicroseconds) },
    override val isKeyframe: Boolean = false,
    override val sizeBytes: Int = 1024,
    override val dts: Pts? = pts,
    override val bytePosition: Long? = null,
    private val ledger: LeakLedger? = null,
    private val packetBytes: ByteArray? = null,
) : PlayerPacket {
    private var isClosed = false

    override var newStreams: List<io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo>? = null
    override var newPrograms: List<io.github.yuroyami.kiteplayer.MediaProgram>? = null
    override var newContainerTags: Map<String, String>? = null

    init {
        ledger?.onOpen()
    }

    override fun close() {
        ledger?.onClose(isClosed)
        isClosed = true
    }

    override fun copyBytes(): ByteArray = packetBytes?.copyOf() ?: ByteArray(sizeBytes)

    val closed: Boolean get() = isClosed
}

internal class FakeVideoFrame(
    override val pts: Pts,
    override val generation: Generation = Generation.Initial,
    override val duration: Pts? = null,
    override val size: VideoSize = VideoSize(1920, 1080),
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p,
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.guessFor(1080),
    override val hardwareSurface: Nothing? = null,
    private val ledger: LeakLedger? = null,
) : io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame {
    private var isClosed = false

    init {
        ledger?.onOpen()
    }

    /**
     * Readable planes for the captureFrame path: tiny deterministic 2x2 content whose
     * luma bytes derive from the pts, so a capture test can prove WHICH frame it copied.
     */
    override val planeCount: Int get() = 3

    override fun planeStride(index: Int): Int = if (index == 0) 2 else 1

    override fun planeHeight(index: Int): Int = if (index == 0) 2 else 1

    override fun copyPlane(index: Int, into: ByteArray, offset: Int) {
        val value = if (index == 0) (pts.micros % 251).toByte() else 128.toByte()
        val bytes = planeStride(index) * planeHeight(index)
        for (at in 0 until bytes) into[offset + at] = value
    }

    override fun close() {
        ledger?.onClose(isClosed)
        isClosed = true
    }

    val closed: Boolean get() = isClosed

    override fun toString(): String = "Frame($pts, $generation)"
}

/**
 * A renderer that draws nothing and records everything it was asked for.
 *
 * The question the video scheduler has to answer is not what the picture looks like, which the golden
 * image tests in `kiteplayer-ffmpeg` settle against FFmpeg's own output. It is whether each frame was
 * handed over with the time the schedule intended, so this keeps every (timestamp, target) pair for a
 * test to compare against a schedule it drove by hand.
 *
 * It also honours the ownership rule a real renderer must honour: the frame belongs to it from the
 * moment [present] is called, including when it refuses, and it closes it exactly once.
 */
internal class RecordingRenderer(
    /** False makes every frame refused, the way a renderer whose surface went away refuses. */
    private val accepts: Boolean = true,
    private val decoderFactories: List<VideoDecoderFactory> = emptyList(),
    /**
     * How long each [present] takes, which is the only way to make the schedule run late.
     *
     * Zero is a renderer that keeps up. Anything longer than one frame's period is a display that
     * cannot, so the schedule falls behind and the late-drop rule starts firing. Under the test
     * clock this is a virtual wait, so a slow renderer costs the suite nothing in real seconds.
     */
    private val presentDuration: Duration = Duration.ZERO,
    /**
     * How long an overlay with text takes to publish, and nothing can cancel that wait. It models
     * the Compose renderer, which builds its images before it publishes them.
     */
    private val overlayPublishDuration: Duration = Duration.ZERO,
) : VideoRenderer {

    private val received = mutableListOf<Presentation>()

    /** The last scale mode the engine told this renderer, or null when it never did. */
    var scaleMode: VideoScale? = null
        private set

    /** What this renderer claims its surface is, so a test can prove which canvas the text used. */
    var outputSizeOverride: io.github.yuroyami.kiteplayer.VideoSize? = null

    override val outputSize: io.github.yuroyami.kiteplayer.VideoSize?
        get() = outputSizeOverride

    /** What this renderer claims about showing HDR, for the variant choice (#447). */
    var showsHdrOverride: Boolean = false

    override val showsHdr: Boolean
        get() = showsHdrOverride

    /** False plays a renderer that cannot redraw its held picture, as Android's cannot (#463). */
    var redrawsHeldPictureOverride: Boolean = true

    override val redrawsHeldPicture: Boolean
        get() = redrawsHeldPictureOverride

    override fun setScaleMode(mode: VideoScale) {
        scaleMode = mode
    }

    /** The last picture controls the engine told this renderer, or null when it never did. */
    var adjustments: VideoAdjustments? = null
        private set

    override fun setAdjustments(adjustments: VideoAdjustments) {
        this.adjustments = adjustments
    }

    /** The last framing controls the engine told this renderer, or null when it never did. */
    var transform: VideoTransform? = null
        private set

    override fun setTransform(transform: VideoTransform) {
        this.transform = transform
    }

    /** The last render quality the engine told this renderer, or null when it never did. */
    var renderQuality: RenderQuality? = null
        private set

    override fun setRenderQuality(quality: RenderQuality) {
        renderQuality = quality
    }

    val presentations: List<Presentation> get() = received
    val count: Int get() = received.size
    val timestamps: List<Pts> get() = received.map { it.pts }
    val targets: List<Long> get() = received.map { it.targetNanos }

    override fun videoDecoderFactories(): List<VideoDecoderFactory> = decoderFactories

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        received += Presentation(frame.pts, frame.generation, targetNanos)
        // The renderer owns the frame from here, including when it fails. Anything else leaks.
        frame.close()
        if (presentDuration > Duration.ZERO) kotlinx.coroutines.delay(presentDuration)
        return accepts
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) = Unit

    /** Every overlay handed over, in order, nulls included. */
    val overlays: MutableList<SubtitleOverlay?> = mutableListOf()

    /** Thrown by every overlay with images while set, as a renderer that cannot upload would. */
    var overlayFailure: Exception? = null

    /** Thrown by every overlay with no images while set, as a renderer that lost its target would. */
    var withdrawalFailure: Exception? = null

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        if (overlay != null && overlay.images.isNotEmpty()) overlayFailure?.let { throw it }
        if (overlay != null && overlay.images.isEmpty()) withdrawalFailure?.let { throw it }
        if (overlayPublishDuration > Duration.ZERO && overlay != null && overlay.images.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.delay(overlayPublishDuration)
            }
        }
        overlays += overlay
    }

    /** How many frames had been presented at each [clearPicture], in order. */
    val clearedAt: MutableList<Int> = mutableListOf()

    /** Thrown by every [clearPicture] while set, as a renderer with a bug in it would. */
    var clearFailure: Exception? = null

    override fun clearPicture() {
        clearFailure?.let { throw it }
        clearedAt += received.size
    }

    /** True while the last thing this renderer was told is a picture, not that none plays (#530). */
    val showsPicture: Boolean get() = received.size > (clearedAt.lastOrNull() ?: 0)

    override val events: Flow<RendererEvent> = emptyFlow()

    override fun close() = Unit
}

/** One call to [RecordingRenderer.present]: which frame, and the time it was aimed at. */
internal data class Presentation(val pts: Pts, val generation: Generation, val targetNanos: Long)

/** Microseconds, spelled so a test reads like the specification it checks. */
internal val Int.us: Long get() = this.toLong()
internal val Int.ms: Long get() = this.toLong() * 1_000
internal fun pts(millis: Long): Pts = Pts(millis * 1_000)
