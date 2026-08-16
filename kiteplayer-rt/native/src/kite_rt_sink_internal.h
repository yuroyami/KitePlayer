/* The private layout of `kprt_sink`, and the two internal entry points of the real-time
 * translation unit.
 *
 * Not in `include/`, so `src/nativeInterop/cinterop/kitert.def` never sees it and the Kotlin side
 * gets an opaque pointer with no field and no size. The tests include it on purpose: the whole
 * point of `kprt_render_into` being a named function rather than an inline body is that the
 * five million synthetic callbacks of `tests/test_sink_callback.c` drive the SAME code the device
 * drives, with no device and no sound.
 *
 * WHY THIS HEADER INCLUDES NO PLATFORM HEADER, WHICH IS THE LOAD BEARING PART. The audio unit is
 * held as a `void *` rather than as an `AudioComponentInstance`. That is what lets
 * `kite_rt_render.c` stay portable and, more importantly, what lets it be AUDITED: plan section
 * 15.2 B1.8's first assertion requires a translation unit whose undefined symbols are a short fixed
 * allowlist, and a unit that included AudioToolbox to spell one field type would be free to call
 * into it. The cast lives in `kite_rt_coreaudio.c`, which is the only file in this library that
 * knows CoreAudio exists.
 */

#ifndef KITE_RT_SINK_INTERNAL_H
#define KITE_RT_SINK_INTERNAL_H

#include "kite_rt.h"

#include <stdatomic.h>
#include <stdint.h>

/* Same cache line constant as the ring's, restated rather than shared, because this header must
 * stand on its own for a reader who only cares about the device. */
#define KPRT_SINK_CACHELINE 64

struct kprt_sink {
    /* ---- Immutable after create. The real-time thread reads these and writes none of them. ---- */

    int32_t sample_rate;
    int32_t channels;
    _Atomic int32_t device_buffer_frames;

    /* `mach_timebase_info`, read once at create.
     *
     * Cached rather than converted through `AudioConvertHostTimeToNanos` in the callback, which is
     * what plan section 15.2 B1.8 step 1 asks for and what `scripts/render-audit.sh` enforces by
     * forbidding that symbol in the render unit.
     *
     * The ratio is not the identity here and must not be assumed to be: measured on the development
     * machine, an arm64 Mac, `mach_timebase_info` reports 125 over 3, so one host tick is 41 and two
     * thirds nanoseconds and every callback's deadline depends on this multiply. An earlier draft of
     * `tests/test_sink_timebase.c` asserted 1 over 1 and failed on its first run.
     * `kprt_sink_ticks_to_nanos` is therefore checked against `AudioConvertHostTimeToNanos` itself
     * over a table of vectors rather than reasoned about. */
    uint32_t timebase_numer;
    uint32_t timebase_denom;

    /* The audio unit, as an opaque pointer. See the header note on why this is not typed. */
    void *unit;

    /* ---- The ring, published to the real-time thread ---- */

    /* Release-stored by `kprt_sink_attach_ring` and by teardown, acquire-loaded by the callback. A
     * callback that sees a non-NULL value here sees a fully constructed ring; one that sees NULL
     * zeroes its whole buffer, which after B1.8 happens during teardown and at no other time. */
    _Atomic(kprt_ring *) ring;

    /* 1 when this sink created the ring and must therefore free it. Owner-thread only. */
    int32_t owns_ring;

    /* ---- Counters, all written by the real-time thread ---- */

    _Alignas(KPRT_SINK_CACHELINE) _Atomic int64_t callbacks;
    _Atomic int64_t estimated_anchors;
    _Atomic int64_t zero_filled_callbacks;
    _Atomic int64_t worst_callback_nanos;
    _Atomic int64_t last_deadline_nanos;

    /* ---- Owner-thread lifecycle, plus the two fields other threads may read. ----
     *
     * SOL-A5: `running` is read by the stats path concurrently with start/stop, so it is
     * atomic (relaxed: a boolean snapshot needs no ordering). SOL-A4: the device period is
     * re-queried on start and updated by the format-change listener on CoreAudio's own
     * notification thread, so it lives in `device_buffer_frames` above as an atomic too. */
    _Atomic int32_t running;
    int32_t initialized;
};

/* Host ticks to nanoseconds, through the timebase cached at create.
 *
 * Divides before it multiplies for the same reason `kprt_frames_to_micros` does: the naive
 * `ticks * numer / denom` overflows once the product passes 63 bits, and a host tick count is
 * already a large number at boot plus days of uptime. Exact, not approximate. */
int64_t kprt_sink_ticks_to_nanos(const kprt_sink *sink, uint64_t ticks);

/* The whole real-time body, with the device's arguments already unpacked.
 *
 * This is what runs on the device's thread, and it is deliberately reachable by name so that a test
 * can call it five million times without a device. It allocates nothing, takes no lock, makes no
 * syscall and calls nothing outside `memcpy` and `memset`.
 *
 * @param host_ticks the instant the first frame of this buffer reaches the device, in host ticks.
 * @param host_ticks_estimated non-zero when the device did not flag its own timestamp valid and the
 *        caller substituted the clock, which is counted rather than logged.
 * @return frames of real audio written. The rest of `destination` is exact zeroes. */
int32_t kprt_render_into(kprt_sink *sink, float *destination, int32_t frames,
                         uint64_t host_ticks, int32_t host_ticks_estimated);

/* Records the wall time of one callback body, from a host tick pair taken around it. Keeps the
 * worst. Separate from `kprt_render_into` because the pair has to close after the body returns. */
void kprt_sink_note_span(kprt_sink *sink, uint64_t entered_ticks, uint64_t left_ticks);

#endif /* KITE_RT_SINK_INTERNAL_H */
