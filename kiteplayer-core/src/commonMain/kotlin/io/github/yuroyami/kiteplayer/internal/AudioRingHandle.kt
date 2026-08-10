package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat

/**
 * The buffer between the engine and the audio device, as the engine sees it.
 *
 * ### Why this interface exists, and why it has no `render`
 *
 * There are two implementations of this contract and there always will be. [KotlinAudioRing] is the
 * portable one; `NativeAudioRing`, in `nativeMain`, is a thin wrapper over the C ring in
 * `kiteplayer-rt`. The reason for the second is register item B1-17: on macOS the device's real-time
 * callback enters managed Kotlin on its first instruction and becomes a mutator the garbage
 * collector has to stop at a safepoint, and the only way out is a callback that never leaves C,
 * which needs a ring that lives in C.
 *
 * The reason the first cannot be deleted, ever, is register item B1-20. `kiteplayer-core`'s
 * `commonMain` targets js and wasmJs, which can never contain C. It cannot become an `expect class`
 * either, because that would remove the portable implementation from the native targets, and the
 * portable implementation is the only oracle the C ring can be checked against. So two
 * implementations of one contract exist permanently, and the differential oracle at
 * `kiteplayer-core/src/nativeTest/.../AudioRingDifferentialTest.kt` is the only thing that keeps
 * them from drifting apart.
 *
 * The members here are exactly the ones [io.github.yuroyami.kiteplayer.AudioPlayback] uses, and
 * `render` is deliberately not among them. Rendering belongs to whoever owns the device callback: in
 * the Kotlin case that is an `AudioRenderCallback` closure that `AudioPlayback` hands to the sink,
 * and in the C case, from B1.8 onward, it is a `static` C function the engine never calls and never
 * sees. Putting `render` on this interface would have made the second arrangement look like the
 * first, which is the whole thing B1.8 is trying to stop being true.
 */
internal interface AudioRingHandle {

    /** The format the device accepted, which is also the format of the samples handed to [write]. */
    val format: AudioFormat

    /** Callbacks that were handed silence because the ring had run dry while the stream was live. */
    val underruns: Long

    /** Frames written but not yet handed to the device. */
    val bufferedFrames: Int

    /** [bufferedFrames] as a duration in microseconds. */
    val bufferedUs: Long

    /**
     * Adds interleaved float samples.
     *
     * @param pts the media timestamp of the first frame in [source], when known. Null is normal for
     *        a buffer that continues the previous one.
     * @return frames accepted, which is fewer than [frames] when the ring is nearly full and zero in
     *         two cases: the ring is full, or [pts] needs a timestamp segment of its own and all
     *         four still date audio the device has not played. The caller retries the remainder
     *         rather than dropping it, and the wait is the same one in both cases.
     */
    fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int

    /**
     * How far the media has been played, and the instant it reaches that point.
     *
     * Null before the device has played anything, and after a flush. Null is a normal answer, not an
     * error: it is what the clock says between a seek and the first audio of the new position.
     */
    fun anchor(): AudioAnchor?

    /** Tells the ring that the feeder has finished, so trailing silence is not an underrun. */
    fun markEnding()

    /**
     * Discards everything unplayed and invalidates the anchor. This is the seek path.
     *
     * Precondition, and not advice: the sink is stopped and both sides are quiescent, meaning the
     * device callback is provably out of its render path and the feeder is not inside [write]. Flush
     * writes the consumer's own counter and drops the segments the consumer dates the clock from, so
     * a device still pulling from a ring being cleared would play a mixture of the old position and
     * the new one and would date it from either.
     */
    fun flush()
}

/**
 * The media timestamp the playhead reaches at a given instant on the monotonic clock.
 *
 * The timestamp is a boundary between two samples, not a sample's own start: everything before it has
 * been heard by [audibleAtNanos], and nothing after it has.
 */
internal data class AudioAnchor(val pts: Pts, val audibleAtNanos: Long)

/**
 * [frames] sample frames at [sampleRate], in microseconds, truncated toward zero.
 *
 * ### Why this is a function and not an expression
 *
 * Register item B1-18. Both places that dated a frame in the ring used to compute
 * `frames * 1_000_000L / sampleRate`, which overflows a signed 64 bit intermediate once the frame
 * delta passes about 9.2e12. It is the same shape KPKMP.md section 4 records against KiteCodec's
 * timestamp helpers as defect D9: latent at ordinary session lengths and wrong at long ones. Writing
 * it once, correctly, is what stops the C ring being a faithful transliteration of a bug, and it is
 * what lets the differential oracle compare two correct implementations rather than two matching
 * ones.
 *
 * ### Why this form is exact and not merely safer
 *
 * With `frames = whole * rate + rest`, the unbounded value of `frames * 1000000 / rate` is
 * `whole * 1000000 + rest * 1000000 / rate`, and `rest * 1000000` cannot overflow because
 * `|rest| < rate` and a sample rate fits in an `Int`. So this returns the same integer the naive
 * form would have returned had it had unlimited width, at every input, and not an approximation of
 * it. Kotlin's `Long` division truncates toward zero and its remainder takes the sign of the
 * dividend, which is exactly what C does, so `kprt_frames_to_micros` in `native/src/kite_rt_render.c`
 * agrees with this for negative deltas as well as positive ones. That file and not
 * `native/src/kite_rt_ring.c`, which is where B1.7 put it: B1.8 moved the function into the real-time
 * translation unit with the rest of the render path, and that move is what makes the unit's undefined
 * symbol list auditable.
 *
 * Returns 0 for a non-positive [sampleRate], matching [AudioFormat.durationOf].
 */
internal fun framesToMicros(frames: Long, sampleRate: Int): Long {
    if (sampleRate <= 0) return 0
    val rate = sampleRate.toLong()
    val whole = frames / rate
    val rest = frames % rate
    return whole * 1_000_000L + rest * 1_000_000L / rate
}
