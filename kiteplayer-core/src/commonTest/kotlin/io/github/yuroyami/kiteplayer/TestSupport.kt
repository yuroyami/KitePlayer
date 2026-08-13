package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
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
) : VideoFrame {
    private var isClosed = false

    init {
        ledger?.onOpen()
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
) : VideoRenderer {

    private val received = mutableListOf<Presentation>()

    val presentations: List<Presentation> get() = received
    val count: Int get() = received.size
    val timestamps: List<Pts> get() = received.map { it.pts }
    val targets: List<Long> get() = received.map { it.targetNanos }

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        received += Presentation(frame.pts, frame.generation, targetNanos)
        // The renderer owns the frame from here, including when it fails. Anything else leaks.
        frame.close()
        return accepts
    }

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float) = Unit

    /** Every overlay handed over, in order, nulls included. */
    val overlays: MutableList<SubtitleOverlay?> = mutableListOf()

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        overlays += overlay
    }

    override val events: Flow<RendererEvent> = emptyFlow()

    override fun close() = Unit
}

/** One call to [RecordingRenderer.present]: which frame, and the time it was aimed at. */
internal data class Presentation(val pts: Pts, val generation: Generation, val targetNanos: Long)

/** Microseconds, spelled so a test reads like the specification it checks. */
internal val Int.us: Long get() = this.toLong()
internal val Int.ms: Long get() = this.toLong() * 1_000
internal fun pts(millis: Long): Pts = Pts(millis * 1_000)
