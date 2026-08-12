package io.github.yuroyami.kiteplayer.compose

import kotlinx.atomicfu.atomic

/**
 * The measured CPU cost of KiteVideo's software path, per PUBLISHED frame: the conversion to
 * RGBA plus the image build, on the renderer's worker thread, in nanoseconds.
 *
 * What this is not: it is not the draw cost (Compose draws on its own schedule), not a GPU
 * number, and not a device claim when it was measured on an emulator. Failed and superseded
 * frames contribute no sample, so the average is the average of frames a viewer could have
 * seen. These are the numbers 17.9 said would replace its ASSUMED physics; S3's exit re-takes
 * them on real hardware.
 */
public class KiteVideoFrameCost internal constructor(
    public val samples: Long,
    public val lastNanos: Long,
    public val averageNanos: Long,
    public val worstNanos: Long,
) {
    override fun toString(): String =
        "KiteVideoFrameCost(samples=$samples, last=${lastNanos}ns, average=${averageNanos}ns, worst=${worstNanos}ns)"
}

/** The lock-free accumulator behind [KiteVideoFrameCost]. Written by the worker, read anywhere. */
internal class FrameCostTracker {
    private val samples = atomic(0L)
    private val totalNanos = atomic(0L)
    private val lastNanos = atomic(0L)
    private val worstNanos = atomic(0L)

    fun record(nanos: Long) {
        if (nanos < 0) return
        samples.incrementAndGet()
        totalNanos.addAndGet(nanos)
        lastNanos.value = nanos
        // A lost race here keeps a competing larger value, which is the right loser.
        while (true) {
            val worst = worstNanos.value
            if (nanos <= worst) break
            if (worstNanos.compareAndSet(worst, nanos)) break
        }
    }

    fun snapshot(): KiteVideoFrameCost {
        val count = samples.value
        return KiteVideoFrameCost(
            samples = count,
            lastNanos = if (count == 0L) 0L else lastNanos.value,
            averageNanos = if (count == 0L) 0L else totalNanos.value / count,
            worstNanos = worstNanos.value,
        )
    }
}
