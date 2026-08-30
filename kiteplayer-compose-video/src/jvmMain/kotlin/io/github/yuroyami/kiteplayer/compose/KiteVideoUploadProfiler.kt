package io.github.yuroyami.kiteplayer.compose

import java.util.concurrent.atomic.AtomicInteger

/**
 * One measured run of the desktop upload path. The two halves are paired by index, so frame `i`
 * cost `convertNanos[i] + imageNanos[i]`.
 */
public class KiteVideoUploadSamples internal constructor(
    /** CPU time turning the decoded frame into tightly packed RGBA. */
    public val convertNanos: LongArray,
    /** CPU time turning those bytes into a Skia-backed ImageBitmap. */
    public val imageNanos: LongArray,
) {
    /** Frames whose whole upload was recorded. */
    public val size: Int get() = convertNanos.size

    /** The per-frame totals, which is what [KiteVideoFrameCost] averages. */
    public fun totalNanos(): LongArray = LongArray(size) { convertNanos[it] + imageNanos[it] }
}

/**
 * The measurement instrument: the desktop upload path's cost per published
 * frame, kept as raw samples so a percentile can be taken and split so the expensive half is
 * named rather than guessed.
 *
 * [KiteVideoFrameCost] stays the authority for the sample count, the mean and the worst; it holds
 * four numbers by design and no distribution. This records the SAME window, the convert plus the
 * Skia image build, and costs one volatile read per frame while no run is active.
 *
 * Desktop only, and deliberately global: the converter and the image pool are built inside
 * [KiteVideoState], so a measurement has no other seam to reach them through.
 */
public object KiteVideoUploadProfiler {

    /** Non-null only while recording. The one field every hot-path check reads. */
    @Volatile
    private var convert: LongArray? = null
    private var image: LongArray = LongArray(0)

    private val next = AtomicInteger(0)

    /** Bumped per run, so a frame half-timed by the previous run cannot land in this one. */
    private val generation = AtomicInteger(0)

    /**
     * Per worker thread, so two states measured at once cannot pair each other's timestamps.
     * The three slots are the start mark, the convert mark and the run this frame belongs to.
     */
    private val marks = ThreadLocal.withInitial { LongArray(3) }

    /** Starts a run. At most [capacity] frames are kept; frames past that are not recorded. */
    public fun start(capacity: Int) {
        require(capacity > 0) { "a profiler run needs room for at least one frame" }
        next.set(0)
        generation.incrementAndGet()
        image = LongArray(capacity)
        convert = LongArray(capacity)
    }

    /** Stops the run and returns its samples, in arrival order. */
    public fun stop(): KiteVideoUploadSamples {
        val converts = convert ?: return KiteVideoUploadSamples(LongArray(0), LongArray(0))
        convert = null
        val kept = next.get().coerceAtMost(converts.size)
        return KiteVideoUploadSamples(converts.copyOf(kept), image.copyOf(kept))
    }

    /** Called before the first pixel is read. Worker thread only. */
    internal fun frameStarted() {
        if (convert == null) return
        val mark = marks.get()
        mark[0] = System.nanoTime()
        mark[2] = generation.get().toLong()
    }

    /** Called once the RGBA bytes exist, before the image is built. */
    internal fun frameConverted() {
        if (convert == null) return
        marks.get()[1] = System.nanoTime()
    }

    /** Called once the image exists. A frame that failed to convert leaves no sample. */
    internal fun frameFinished() {
        val converts = convert ?: return
        val mark = marks.get()
        if (mark[0] == 0L || mark[2] != generation.get().toLong()) return
        val finished = System.nanoTime()
        val index = next.getAndIncrement()
        if (index < converts.size) {
            converts[index] = mark[1] - mark[0]
            image[index] = finished - mark[1]
        }
        mark[0] = 0L
    }
}
