/* The allocation contract, measured: one malloc at create, one free at destroy, and NOTHING in
 * between.
 *
 * WHY THIS INSTRUMENT AND NOT A LEAK CHECKER. LeakSanitizer is not supported on macOS arm64
 * (register item B1-14), and it would answer the wrong question anyway. A leak checker asks whether
 * every allocation was freed. A real-time audio path needs the stronger property that there was no
 * allocation at all: a malloc paired with a free is still a malloc, and it can still take the
 * allocator's lock while the device is waiting for samples. So the instrument is the interposer in
 * tests/interpose_alloc.c and the assertion is `no calls`, not `balanced calls`.
 *
 * WHAT THIS DOES NOT PROVE, stated here because plan section 15.2 B1.8 refuses it explicitly. It
 * says nothing whatsoever about Kotlin allocation. Kotlin/Native takes pages by `mmap` and hands
 * objects out of them, so an interposer measured 229 mallocs before and 230 after one million
 * Kotlin object allocations and would read zero for a callback allocating millions of objects. The
 * evidence that the SHIPPED callback does not allocate is B1.8's business: a symbol and instruction
 * audit of the render translation unit, then a GC-pressure differential with a negative control
 * that must fail. This suite is the C half, and only the C half.
 *
 * The interposer counts nothing under asan and tsan, because both sanitizer runtimes replace the
 * allocator before dyld reaches the interpose section. The harness records that as a partial in
 * those variants, and the `interpose` gate step sets KPRT_REQUIRE_ALLOC_ACCOUNTING=1 so a partial
 * becomes a hard failure. That is what stops "the instrument was dead" from reading like "there
 * were no allocations".
 */

#include "harness.h"
#include "kite_rt.h"
#include "ring_support.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#define CHANNELS 2
#define SAMPLE_RATE 48000
#define CAPACITY_FRAMES 4096
#define RENDER_FRAMES 512
#define RENDERS 50000

static float destination[RENDER_FRAMES * CHANNELS];

/* ---- A feeder thread, so the measured window has a real producer in it ---- */

typedef struct {
    kprt_ring *ring;
    _Atomic int32_t stop;
    _Atomic int64_t written;
} feeder_state;

static void *feeder(void *argument)
{
    feeder_state *state = (feeder_state *)argument;
    int64_t written = 0;
    while (atomic_load_explicit(&state->stop, memory_order_acquire) == 0) {
        int32_t accepted = kprt_test_feed(state->ring, 256, written, 1,
                                         kprt_frames_to_micros(written, SAMPLE_RATE));
        if (accepted > 0) {
            written += accepted;
            atomic_store_explicit(&state->written, written, memory_order_relaxed);
        }
    }
    return NULL;
}

int main(void)
{
    kprt_ring *ring;
    kt_alloc_counts before;

    kt_suite_begin("test_ring_alloc");

    /* ---- create ---- */
    kt_case("kprt_ring_create performs exactly one allocation");
    kt_alloc_snapshot(&before);
    ring = kprt_ring_create(SAMPLE_RATE, CHANNELS, CAPACITY_FRAMES);
    KT_NOT_NULL(ring);
    KT_ALLOC_EXACTLY(&before, 1, 0);

    /* ---- the whole rest of the lifetime, single threaded ---- */
    kt_case("every operation between create and destroy allocates nothing, single threaded");
    {
        int i;
        kt_alloc_snapshot(&before);
        for (i = 0; i < 4000; i++) {
            kprt_anchor anchor;
            kprt_ring_stats stats;
            (void)kprt_test_feed(ring, 300, (int64_t)i * 300,
                                 1, kprt_frames_to_micros((int64_t)i * 300, SAMPLE_RATE));
            (void)kprt_ring_render(ring, destination, RENDER_FRAMES, 1000000000LL + i);
            kprt_ring_anchor(ring, &anchor);
            kprt_ring_read_stats(ring, &stats);
            (void)kprt_ring_buffered_frames(ring);
            (void)kprt_ring_free_frames(ring);
            (void)kprt_ring_underruns(ring);
            (void)kprt_ring_segment_giveups(ring);
            (void)kprt_ring_anchor_giveups(ring);
            if ((i % 500) == 499) {
                kprt_ring_mark_ending(ring);
                kprt_ring_flush(ring);
            }
        }
        KT_ALLOC_SILENT(&before);
    }

    /* ---- the same claim with a real producer thread running against the render loop ---- */
    kt_case("%d renders against a live feeder thread allocate nothing", RENDERS);
    {
        static feeder_state state;
        pthread_t thread;
        int i;
        int64_t starved = 0;
        /* Taken before the window, because `kprt_ring_flush` deliberately does NOT reset the
         * underrun counter, exactly as `KotlinAudioRing.flush` does not: it is a session total and
         * the earlier single-threaded case starved on purpose thousands of times. */
        int64_t underruns_before;

        kprt_ring_flush(ring);
        underruns_before = kprt_ring_underruns(ring);
        state.ring = ring;
        atomic_store(&state.stop, 0);
        atomic_store(&state.written, 0);

        /* Thread creation allocates a stack and a control block, so it happens OUTSIDE the
         * measured window. So does the join. What is inside is only what the two sides do to the
         * ring. */
        KT_CHECKF(pthread_create(&thread, NULL, feeder, &state) == 0, "pthread_create failed");
        while (atomic_load_explicit(&state.written, memory_order_relaxed) == 0) {
            /* Let the feeder get going, so the window is not mostly starvation.
             *
             * This wait is also what makes the `segment_giveups == 0` assertion below sound rather
             * than lucky. The feeder dates a continuous stream, so it opens exactly ONE timestamp
             * segment, inside its first commit, and `state.written` is published only after that
             * commit returns. So by the time the render loop starts, the one and only slot update in
             * the whole run is already finished and its sequence number is even again; no render can
             * catch a slot in flight, and a give-up would mean the protocol is broken rather than
             * contended. `tests/test_ring_threads.c` starts both sides at once on purpose and
             * therefore has to state the same property as a bound instead. */
        }

        kt_alloc_snapshot(&before);
        for (i = 0; i < RENDERS; i++) {
            int32_t real;
            kprt_anchor anchor;
            /* Wait for a full buffer's worth before rendering, so this window measures the path
             * that moves real samples rather than the path that fills silence. Without this the
             * render loop outruns the feeder by three orders of magnitude and 99.9 percent of the
             * calls return zero frames, which would leave the memcpy side of `kprt_ring_render`
             * essentially unmeasured. The wait is a plain counter read, which allocates nothing, so
             * it does not disturb what is being counted. */
            while (kprt_ring_buffered_frames(ring) < RENDER_FRAMES) {
                /* spin */
            }
            real = kprt_ring_render(ring, destination, RENDER_FRAMES,
                                    2000000000LL + (int64_t)i * 10000000LL);
            kprt_ring_anchor(ring, &anchor);
            if (real < RENDER_FRAMES)
                starved++;
        }
        KT_ALLOC_SILENT(&before);

        atomic_store_explicit(&state.stop, 1, memory_order_release);
        KT_CHECKF(pthread_join(thread, NULL) == 0, "pthread_join failed");
        kt_detail("frames moved=%lld, starved=%lld of %d, new underruns=%lld, giveups=%lld",
                  (long long)((int64_t)RENDERS * RENDER_FRAMES),
                  (long long)starved, RENDERS,
                  (long long)(kprt_ring_underruns(ring) - underruns_before),
                  (long long)kprt_ring_segment_giveups(ring));
        KT_EQ_I64(starved, 0);
        KT_EQ_I64(kprt_ring_underruns(ring) - underruns_before, 0);
        KT_EQ_I64(kprt_ring_segment_giveups(ring), 0);
    }

    /* ---- destroy ---- */
    kt_case("kprt_ring_destroy performs exactly one free");
    kt_alloc_snapshot(&before);
    kprt_ring_destroy(ring);
    KT_ALLOC_EXACTLY(&before, 0, 1);

    /* ---- a create and destroy cycle leaves nothing behind ---- */
    kt_case("a thousand create and destroy cycles are exactly balanced");
    {
        int i;
        kt_alloc_snapshot(&before);
        for (i = 0; i < 1000; i++) {
            kprt_ring *scratch = kprt_ring_create(SAMPLE_RATE, CHANNELS, 1024);
            KT_NOT_NULL(scratch);
            kprt_ring_destroy(scratch);
        }
        if (!kt_alloc_active()) {
            kt_partial("allocation counting not observable in this variant");
        } else {
            long long new_calls = kt_alloc_new_delta(&before);
            long long freed = kt_alloc_free_delta(&before);
            long long live = kt_alloc_live_delta(&before);
            kt_detail("new=%lld freed=%lld live=%lld", new_calls, freed, live);
            KT_EQ_I64(new_calls, 1000);
            KT_EQ_I64(freed, 1000);
            KT_EQ_I64(live, 0);
        }
    }

    /* ---- a failed create allocates nothing ---- */
    kt_case("a refused create allocates nothing");
    kt_alloc_snapshot(&before);
    KT_NULL(kprt_ring_create(0, 2, 1024));
    KT_NULL(kprt_ring_create(48000, 2, 0));
    KT_ALLOC_SILENT(&before);

    /* ---- a create the size guard refuses allocates nothing (interlude item I-01) ---- */
    /* The factor bounds are cheap sanity checks; the PRODUCT bound is the memory safety. On the
     * four 32-bit targets an admitted factor pair can wrap the byte count to a tiny number and
     * kprt_ring_create would return a ring whose storage is smaller than its capacity claims,
     * which ASan showed as a heap-buffer-overflow on the first ordinary fill. The three
     * _Static_asserts beside the guard in kite_rt_ring.c prove the arithmetic at EVERY target's
     * own pointer width at compile time; this case proves the refusal path allocates nothing,
     * and on a 32-bit build it also drives the wrap vector through the public surface. */
    kt_case("a create the size guard refuses allocates nothing");
    kt_alloc_snapshot(&before);
    KT_NULL(kprt_ring_create(48000, 65, 1024));
    KT_NULL(kprt_ring_create(48000, 2, (1 << 27) + 1));
#if SIZE_MAX <= 0xFFFFFFFFu
    /* The review's vector: admitted by both factor bounds, wraps the byte count at this width. */
    KT_NULL(kprt_ring_create(48000, 32, 1 << 27));
#endif
    KT_ALLOC_SILENT(&before);

    return kt_suite_end();
}
