// Drives the C ring straight through its own API, which is what the engine does too.
@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import cnames.structs.kprt_ring
import kotlinx.cinterop.toCPointer
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_NEEDS_SEGMENT
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_PUBLISHED
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_frames_to_micros
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_begin_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_buffered_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_channels
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_commit_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_consumed_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_free_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_sample_rate
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_segment_giveups
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_underruns
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_write_window
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set

/**
 * Feeding and reading the C ring from a test, without the engine in between.
 *
 * ### Why the tests do it this way now
 *
 * Before B1.8 these suites drove a naive Kotlin ring of their own through an `AudioRenderCallback`,
 * because that was the shape the sink had. The sink no longer has that shape: its callback is a
 * `static` C function and the samples live in a C ring the sink itself owns. A
 * test that kept a Kotlin ring would be testing a path no Apple-backend user runs, which is the substitution
 * plan section 2 forbids. So the samples go in through the same three
 * C calls the engine's feeder uses, and what comes out is judged by what the device consumed.
 *
 * `NativeAudioRing` in `kiteplayer-core` is `internal`, so this module cannot use it and does not need
 * to: the C API is public through the `kitert` bindings, and using it directly keeps these tests one
 * step away from the sink rather than two.
 */

/** Each frame carries its own stream position as its sample value, so a lost or repeated frame shows. */
internal fun frameValue(frame: Long): Float = (frame % VALUE_PERIOD).toFloat()

/**
 * The value ramp repeats at this period, which is well above any capacity these tests use, because a
 * `Float` stops distinguishing consecutive integers above 16,777,216.
 */
private const val VALUE_PERIOD = 65536L

/**
 * Writes [frames] frames of ramp starting at stream position [from], and publishes them.
 *
 * @param pts the media timestamp of the first frame, or null to continue the previous buffer.
 * @return frames published, which is 0 when the ring was full.
 */
internal fun feedRing(ring: CPointer<kprt_ring>, frames: Int, from: Long, pts: Long?): Int = memScoped {
    val channels = kprt_ring_channels(ring)
    val window = alloc<kprt_ring_write_window>()
    val granted = kprt_ring_begin_write(ring, frames, window.ptr)
    if (granted <= 0) return@memScoped 0

    val first = window.first
    if (first != null) {
        for (i in 0 until window.first_frames) {
            val value = frameValue(from + i)
            for (c in 0 until channels) first[i * channels + c] = value
        }
    }
    val second = window.second
    if (second != null) {
        for (i in 0 until window.second_frames) {
            val value = frameValue(from + window.first_frames + i)
            for (c in 0 until channels) second[i * channels + c] = value
        }
    }

    val status = kprt_ring_commit_write(ring, granted, if (pts != null) 1 else 0, pts ?: 0L)
    when (status) {
        KPRT_COMMIT_PUBLISHED.toInt() -> granted
        // The ring holds four timestamp segments and they all still date unplayed audio. Nothing was
        // taken; the caller retries, exactly as `AudioPlayback.submit` does.
        KPRT_COMMIT_NEEDS_SEGMENT.toInt() -> 0
        else -> error("kprt_ring_commit_write refused $granted frames with status $status")
    }
}

/** Fills the ring as far as it will go, in one buffer's worth at a time, starting at [from]. */
internal fun fillRing(ring: CPointer<kprt_ring>, from: Long, chunk: Int = 1024): Long {
    var written = from
    while (true) {
        val accepted = feedRing(ring, chunk, written, if (written == from) 0L else null)
        if (accepted == 0) return written - from
        written += accepted
    }
}

internal fun ringBuffered(ring: CPointer<kprt_ring>): Int = kprt_ring_buffered_frames(ring)

internal fun ringConsumed(ring: CPointer<kprt_ring>): Long = kprt_ring_consumed_frames(ring)

internal fun ringFree(ring: CPointer<kprt_ring>): Int = kprt_ring_free_frames(ring)

internal fun ringUnderruns(ring: CPointer<kprt_ring>): Long = kprt_ring_underruns(ring)

/** Torn reads of a timestamp slot by the real-time walk. Zero on a healthy system. */
internal fun ringSegmentGiveups(ring: CPointer<kprt_ring>): Long = kprt_ring_segment_giveups(ring)

internal fun ringSampleRate(ring: CPointer<kprt_ring>): Int = kprt_ring_sample_rate(ring)

/** What the real-time thread published: the media time at the playhead, and when it is heard. */
internal class RingAnchor(val ptsUs: Long, val audibleAtNanos: Long, val valid: Boolean, val fromCache: Boolean)

internal fun ringAnchor(ring: CPointer<kprt_ring>): RingAnchor = memScoped {
    val published = alloc<kprt_anchor>()
    kprt_ring_anchor(ring, published.ptr)
    RingAnchor(
        ptsUs = published.pts_us,
        audibleAtNanos = published.audible_at_nanos,
        valid = published.valid != 0,
        fromCache = published.from_cache != 0,
    )
}

/** [frames] at [sampleRate] in microseconds, through the library's own exact rescale. */
internal fun framesToMicros(frames: Long, sampleRate: Int): Long = kprt_frames_to_micros(frames, sampleRate)

/**
 * The handoff's ring as a pointer.
 *
 * The SPI carries an ADDRESS so that no cinterop type reaches kiteplayer-core's public API. This
 * module owns the C sink pointer and the kitert coupling baseline excludes it by design, so the
 * conversion belongs here, once, rather than at twenty call sites.
 */
@OptIn(io.github.yuroyami.kiteplayer.spi.RawRingApi::class)
internal fun io.github.yuroyami.kiteplayer.spi.NativeRingHandoff.ringPointer(): CPointer<kprt_ring> =
    requireNotNull(ring.rawAddress.toCPointer()) { "the handoff carried a null ring address" }
