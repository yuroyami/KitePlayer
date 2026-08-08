package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
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
) : PlayerPacket {
    private var isClosed = false

    init {
        ledger?.onOpen()
    }

    override fun close() {
        ledger?.onClose(isClosed)
        isClosed = true
    }

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

/** Microseconds, spelled so a test reads like the specification it checks. */
internal val Int.us: Long get() = this.toLong()
internal val Int.ms: Long get() = this.toLong() * 1_000
internal fun pts(millis: Long): Pts = Pts(millis * 1_000)
