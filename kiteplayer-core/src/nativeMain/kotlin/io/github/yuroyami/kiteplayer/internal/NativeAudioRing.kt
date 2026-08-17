// The cinterop bindings are all `@ExperimentalForeignApi`. Opted in at file scope rather than
// through the build file, so the boundary between managed Kotlin and the C ring is visible in the
// one file that crosses it instead of being a compiler flag nobody reads.
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package io.github.yuroyami.kiteplayer.internal

import cnames.structs.kprt_ring
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_BAD_ARGUMENT
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_NEEDS_SEGMENT
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_PUBLISHED
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_anchor_giveups
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_begin_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_buffered_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_capacity_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_channels
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_commit_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_consumed_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_create
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_destroy
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_flush
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_free_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_mark_ending
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_render
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_sample_rate
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_segment_giveups
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_underruns
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_write_window
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_written_frames
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.NativeRingAddress
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * The C ring in `kiteplayer-rt`, behind [AudioRingHandle].
 *
 * ### What this is on the shipped path, since B1.8
 *
 * On macOS this is the ring the audio device reads. `AudioPlayback.open` goes through
 * `openAudioPath`, whose native `actual` hands a sink that owns a C device callback
 * (`NativeRingAudioSink`) the job of creating both the device and the ring, and wraps the result in
 * this class through [adopting]. Everywhere else, and for every sink that does not own a C callback,
 * the engine still uses [KotlinAudioRing]; that implementation is not going away, cannot go away, and
 * must never be presented as covering the macOS path (register item B1-20).
 *
 * ### Two ownership modes, and the reason they both exist
 *
 * Constructed directly, this class creates the C ring and [close] frees it. That is what the
 * differential oracle does, and it is the mode where `use { }` or a `finally` is not optional: the
 * ring is one `malloc` in C and Kotlin's collector knows nothing about it.
 *
 * Created through [adopting], it wraps a ring somebody else owns and [close] frees nothing. That is
 * the shipped path, and the ownership is not arbitrary. Releasing a ring requires the device callback
 * to be provably out of `kprt_ring_render`, and the only code that can promise that is the code that
 * stopped the device, which is inside the sink. So the sink creates the ring, the sink destroys it,
 * and the engine writes into it.
 *
 * ### The one place this is not a straight delegation
 *
 * [write] takes a Kotlin `FloatArray`, because that is what `AudioPlayback.submit` has, and the C
 * writer is reservation shaped. So this method pins the source, `memcpy`s into the one or two spans
 * of ring storage the reservation granted, and commits. That is one copy of the samples, which is
 * what the reservation shape is for: a naive binding would have copied into an intermediate native
 * buffer first and paid two.
 *
 * The `usePinned` here does allocate a `Pinned` wrapper per call, and this is the feeder's thread,
 * not the device's, so that is allowed. It is worth being precise about it rather than claiming the
 * reservation API removed all Kotlin allocation from the write path: it removed a copy, and the
 * remaining pin is unavoidable as long as the samples arrive in a Kotlin array. Removing it would
 * mean moving `AudioPipeline`'s output buffer into native memory, which is not this sub-phase's work
 * and is not in its plan.
 */
internal class NativeAudioRing private constructor(
    override val format: AudioFormat,
    created: CPointer<kprt_ring>,
    /** 1 when [close] must free the ring. See the ownership note on the class. */
    private val owned: Boolean,
) : AudioRingHandle, AutoCloseable {

    /** Creates a ring of its own and owns it. This is the oracle's constructor. */
    constructor(format: AudioFormat, capacityFrames: Int) : this(
        format = format,
        created = kprt_ring_create(format.sampleRate, format.channels, capacityFrames)
            ?: error(
                "kprt_ring_create refused ${format.sampleRate} Hz, ${format.channels} channels, " +
                    "$capacityFrames frames",
            ),
        owned = true,
    )

    private val channels = format.channels

    /** Frames the ring holds, read from the ring rather than remembered, so an adopted one is right. */
    val capacityFrames: Int = kprt_ring_capacity_frames(created)

    private var handle: CPointer<kprt_ring>? = created

    /** The live handle, or an error naming what was done to a closed ring. */
    private val ring: CPointer<kprt_ring>
        get() = handle ?: error("this native audio ring is closed")

    override val underruns: Long get() = kprt_ring_underruns(ring)

    override val bufferedFrames: Int get() = kprt_ring_buffered_frames(ring)

    override val bufferedUs: Long get() = framesToMicros(bufferedFrames.toLong(), format.sampleRate)

    /** Frames the ring can still take. Not on the interface, for the reason [KotlinAudioRing] gives. */
    val freeFrames: Int get() = kprt_ring_free_frames(ring)

    /** Total frames ever published by the feeder. Read by the oracle, not by the engine. */
    val writtenFrames: Long get() = kprt_ring_written_frames(ring)

    /** Total frames ever handed to the device. Read by the oracle, not by the engine. */
    val consumedFrames: Long get() = kprt_ring_consumed_frames(ring)

    /**
     * How often the real-time segment walk read a torn slot and dated the anchor from its private
     * cache instead.
     *
     * Zero on a healthy system, and it has no counterpart in [KotlinAudioRing] because the Kotlin
     * ring answers the same situation by spinning without a bound (register item B1-16). The oracle
     * asserts it stays zero, which is what says the C ring's extra freedom was never needed in a
     * single-threaded comparison and therefore cannot explain any agreement between the two.
     */
    val segmentGiveups: Long get() = kprt_ring_segment_giveups(ring)

    /** How often the bounded anchor reader reused its previous reading. Zero in the oracle. */
    val anchorGiveups: Long get() = kprt_ring_anchor_giveups(ring)

    override fun write(source: FloatArray, sourceOffset: Int, frames: Int, pts: Pts?): Int {
        if (frames <= 0) return 0
        // Checked, and not assumed. [KotlinAudioRing] gets this for free: `copyInto` throws
        // IndexOutOfBoundsException on a short source. Here the copy is a `memcpy` through a pinned
        // pointer, which would read past the end of the Kotlin array and report nothing, so the
        // bound has to be stated. An asymmetry in a safety check is worse than either behaviour on
        // its own, because the differential oracle would then be comparing a throw against silent
        // corruption.
        require(sourceOffset >= 0 && frames.toLong() * channels + sourceOffset <= source.size) {
            "a write of $frames frames of $channels channels from offset $sourceOffset needs " +
                "${frames.toLong() * channels + sourceOffset} floats, was given ${source.size}"
        }
        val handle = ring
        return memScoped {
            val window = alloc<kprt_ring_write_window>()
            val granted = kprt_ring_begin_write(handle, frames, window.ptr)
            if (granted <= 0) return@memScoped 0

            source.usePinned { pinned ->
                val origin = pinned.addressOf(sourceOffset)
                val first = window.first
                if (first != null && window.first_frames > 0) {
                    // `.convert()` and not `.toULong()`: `size_t` is `UInt` on the four 32 bit
                    // targets `kiteplayer-core` declares (watchosArm32, watchosArm64,
                    // androidNativeArm32, androidNativeX86) and `ULong` on the other thirteen. A
                    // `.toULong()` here compiled on macOS and failed on all four, which is the
                    // reason the whole native target set is compiled rather than just the host's.
                    memcpy(first, origin, (window.first_frames.toLong() * channels * FLOAT_BYTES).convert())
                }
                val second = window.second
                if (second != null && window.second_frames > 0) {
                    // The second span continues where the first stopped, so its source is offset by
                    // exactly the first span's worth of interleaved samples. Getting this wrong is
                    // the wrapped-write bug, and the ring tests carry a case for it.
                    val continued = origin + (window.first_frames.toLong() * channels)
                    memcpy(
                        second,
                        continued,
                        (window.second_frames.toLong() * channels * FLOAT_BYTES).convert(),
                    )
                }
            }

            when (val status = kprt_ring_commit_write(handle, granted, if (pts != null) 1 else 0, pts?.micros ?: 0L)) {
                KPRT_COMMIT_PUBLISHED.toInt() -> granted
                // The same answer KotlinAudioRing gives: nothing was taken, the caller retries.
                KPRT_COMMIT_NEEDS_SEGMENT.toInt() -> {
                    // Release the reservation before reporting "try later": since the interlude
                    // (I-04) a second begin is refused while one is outstanding, so returning 0
                    // with the reservation still held would make every retry of submit's loop
                    // begin-refuse forever, a livelock on the shipped feeder. A zero-frame commit
                    // publishes nothing and frees the reservation; the fill work is lost, which
                    // is the documented cost of giving up between a begin and a commit, and the
                    // Kotlin ring's needs-segment answer costs the same.
                    kprt_ring_commit_write(handle, 0, 0, 0)
                    0
                }
                KPRT_COMMIT_BAD_ARGUMENT.toInt() ->
                    error("kprt_ring_commit_write rejected $granted frames as a bad argument")
                else -> error("kprt_ring_commit_write returned an unknown status $status")
            }
        }
    }

    /**
     * Fills [destination] from the ring, exactly as the device callback does.
     *
     * Not on [AudioRingHandle], and not called by the engine. Since B1.8 the caller of the underlying
     * `kprt_ring_render` on the shipped path is a `static` C function that Kotlin cannot reach and does
     * not install; this overload exists so the differential oracle can drive the same code path from a
     * test.
     *
     * @param deadlineNanos when the LAST frame of the whole request becomes audible.
     * @return frames of real audio written. The rest of [destination] is exact zeroes.
     */
    fun render(destination: FloatArray, frames: Int, deadlineNanos: Long): Int {
        require(frames * channels <= destination.size) {
            "a request for $frames frames of $channels channels needs ${frames * channels} floats, " +
                "was given ${destination.size}"
        }
        if (frames <= 0) return 0
        val handle = ring
        return destination.usePinned { pinned ->
            kprt_ring_render(handle, pinned.addressOf(0), frames, deadlineNanos)
        }
    }

    override fun anchor(): AudioAnchor? {
        val handle = ring
        return memScoped {
            val published = alloc<kprt_anchor>()
            kprt_ring_anchor(handle, published.ptr)
            if (published.valid == 0) return@memScoped null
            AudioAnchor(Pts(published.pts_us), published.audible_at_nanos)
        }
    }

    override fun markEnding() {
        kprt_ring_mark_ending(ring)
    }

    override fun flush() {
        kprt_ring_flush(ring)
    }

    /**
     * Releases the one C allocation, when this object owns it.
     *
     * Idempotent, and it claims the same quiescence precondition `kprt_ring_destroy` does: the device
     * callback is provably out of render and the feeder is not between a begin and a commit. For an
     * adopted ring this drops the reference and frees nothing, because the sink that stopped the device
     * is the only code that can honour that precondition.
     */
    override fun close() {
        val live = handle ?: return
        handle = null
        if (owned) kprt_ring_destroy(live)
    }

    internal companion object {
        private const val FLOAT_BYTES = 4

        /**
         * Wraps a ring created and owned by somebody else, which on the shipped path is the sink that
         * owns the device callback reading it.
         *
         * @param format the format the device accepted, passed in rather than read back from the ring:
         *        the ring knows its rate and its channel count but not its channel layout, and a layout
         *        inferred from a channel count is the fabrication defect D30 is about.
         */
        // This IS the native audio handoff boundary the marker names, so the opt-in belongs here.
        @OptIn(io.github.yuroyami.kiteplayer.spi.RawRingApi::class)
        fun adopting(address: NativeRingAddress, format: AudioFormat): NativeAudioRing {
            // W-18: the SPI carries the ring as an address so no cinterop type reaches core's
            // public API. This file is one of the two the coupling baseline already allows to name
            // the C type, so the conversion belongs here and nowhere else.
            val handle = requireNotNull(address.rawAddress.toCPointer<kprt_ring>()) {
                "the sink handed over a null ring address"
            }
            val rate = kprt_ring_sample_rate(handle)
            val channels = kprt_ring_channels(handle)
            // A ring whose format disagrees with the format it is being described by would date every
            // sample wrongly and silently. Checked here because this is the one place the two meet.
            require(rate == format.sampleRate && channels == format.channels) {
                "the adopted ring holds $channels channels at $rate Hz but is described as " +
                    "${format.channels} channels at ${format.sampleRate} Hz"
            }
            return NativeAudioRing(format = format, created = handle, owned = false)
        }
    }
}
