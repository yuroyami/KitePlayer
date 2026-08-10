/* The private layout of `kprt_ring`.
 *
 * Not in `include/`, and not reachable from Kotlin: `src/nativeInterop/cinterop/kitert.def`
 * names `kite_rt.h` only, so the cinterop klib sees an opaque pointer and no field.
 *
 * Two kinds of code include this file. The implementation, `kite_rt_ring.c`. And the tests that
 * have to reach inside on purpose: `test_ring_bounded.c` holds one segment slot's sequence
 * number odd from a second thread, which is how register item B1-16's claim ("the render path
 * never blocks and counts a give-up") is turned into an experiment rather than an argument.
 * Giving the tests the struct is better than adding a test-only hook to the shipped header,
 * because a hook would be a promise to every future consumer.
 *
 * EVERY FIELD TOUCHED BY MORE THAN ONE THREAD IS `_Atomic`, INCLUDING THE SEQLOCK PAYLOADS.
 * That is not decoration. A seqlock whose payload is plain (or `volatile`) is a data race by the
 * memory model's own definition, and ThreadSanitizer says so; the sanitizer run in
 * `scripts/run-c-tests.sh tsan` is what keeps this honest. Relaxed atomics compile to plain
 * loads and stores on every target here, so the cost is zero instructions and the gain is a
 * program the model can reason about.
 */

#ifndef KITE_RT_RING_INTERNAL_H
#define KITE_RT_RING_INTERNAL_H

#include "kite_rt.h"

#include <stdatomic.h>
#include <stddef.h>

/* Cache line on every target this library is built for. Contended counters are padded onto
 * their own line so the producer's stores do not invalidate the line the consumer reads. */
#define KPRT_CACHELINE 64

/* One timestamp segment: "the frame at `start_frame` carries `pts_us`, and the frames after it
 * follow at the sample rate".
 *
 * `seq` is even while the slot is stable and odd while the feeder is rewriting it. It is PER
 * SLOT rather than one counter for the whole ring, which is what lets the real-time reader
 * validate exactly the slot it used and give up on that slot alone instead of retrying a global
 * counter until the feeder happens to finish. */
typedef struct {
    _Atomic int64_t seq;
    _Atomic int64_t start_frame;
    _Atomic int64_t pts_us;
} kprt_segment;

struct kprt_ring {
    /* ---- Immutable after create. Read by both sides, written by neither. ---- */
    int32_t sample_rate;
    int32_t channels;
    int32_t capacity_frames;
    /* Interleaved sample storage, `capacity_frames * channels` floats, 64 byte aligned, inside
     * the same allocation as this header. */
    float *data;
    /* The pointer `malloc` actually returned, which is what `free` must be given. `data` and
     * this header are both offsets into it. */
    void *block;
    /* 1000000000 / sample_rate, as a double, used only to back-date the anchor by the silence
     * tail of a short read. Kept as a double, and computed exactly like
     * `KotlinAudioRing.nanosPerFrame`, because the differential oracle compares the published
     * instant to the nanosecond and any difference in this expression would show up there. */
    double nanos_per_frame;

    /* ---- The two frame counters. One writer each, and that is the whole SPSC discipline. ---- */

    /* Total frames ever published by the feeder. Release-stored by the feeder, acquire-loaded by
     * the consumer: that release is what publishes both the samples and the segment dating them. */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t written;
    /* Total frames ever handed to the device. Release-stored by the consumer, acquire-loaded by
     * the feeder when it computes free space. */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t consumed;

    /* ---- Counters ---- */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t underruns;
    _Alignas(KPRT_CACHELINE) _Atomic int64_t segment_giveups;
    _Alignas(KPRT_CACHELINE) _Atomic int64_t anchor_giveups;
    _Alignas(KPRT_CACHELINE) _Atomic int32_t ending;

    /* ---- The segment ring. Feeder writes, consumer reads. ---- */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t segments_appended;
    _Atomic int64_t segments_retired;
    _Alignas(KPRT_CACHELINE) kprt_segment segments[KPRT_MAX_SEGMENTS];

    /* ---- The anchor seqlock. Consumer writes, anybody reads. ---- */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t anchor_seq;
    _Atomic int64_t anchor_pts_us;
    _Atomic int64_t anchor_nanos;
    _Atomic int32_t anchor_valid;

    /* ---- Consumer-private state. Only the render thread writes these. ---- */

    /* The last segment the render path resolved successfully, so a torn read has something to
     * date from instead of a spin. Atomic only so that `kprt_ring_flush` can clear it from the
     * session owner's thread without the sanitizer calling it a race; the quiescence
     * precondition is what makes that clearing correct. */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t cache_base_frame;
    _Atomic int64_t cache_base_pts_us;
    _Atomic int32_t cache_valid;

    /* ---- Anchor-reader-private state. Only `kprt_ring_anchor` writes these. ---- */
    _Alignas(KPRT_CACHELINE) _Atomic int64_t reader_pts_us;
    _Atomic int64_t reader_nanos;
    _Atomic int32_t reader_valid;

    /* ---- The outstanding write reservation. Feeder-private, so plain fields. ---- */
    _Alignas(KPRT_CACHELINE) int64_t pending_start_frame;
    int32_t pending_frames;
    int32_t has_pending;
};

#endif /* KITE_RT_RING_INTERNAL_H */
