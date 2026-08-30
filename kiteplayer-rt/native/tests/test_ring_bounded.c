/* The real-time thread must never wait for the feeder, and a give-up must be
 * visible rather than silent.
 *
 * WHAT THE KOTLIN RING DOES. `KotlinAudioRing.publishAnchor` loads `segmentSeq` and
 * `continue`s while it is odd. The class was called `AudioRing` when register item B1-16 was written
 * and B1.7 renamed it; no line numbers are quoted here on purpose, because they moved with the rename
 * and a stale line number reads as a fact. The writer of that counter is the feeder coroutine. If the feeder is
 * preempted between its two increments, the device thread spins with no bound. The class comment
 * says "No lock anywhere", which is true of mutexes and not of this: it is a priority inversion on
 * a real-time thread, and it is independent of the language, so a transliteration into C would have
 * reproduced it exactly.
 *
 * WHAT THIS FILE PROVES, and how it avoids proving nothing. A second thread holds one segment
 * slot's sequence number odd, which is precisely the state a preempted feeder leaves behind, and
 * holds it for a long interval. Then:
 *
 *  1. Two thousand renders run to completion, and the sequence number is STILL odd when they
 *     finish. That is the load-bearing assertion, and it is a fact about ordering rather than about
 *     wall-clock speed: if any render had waited for the writer, it could not have returned before
 *     the writer released. A timing bound alone would be a flaky restatement of the same claim.
 *  2. `segment_giveups` counts one per render, so the degradation is measurable from outside.
 *  3. The anchor is still published, dated from the consumer-private cache. The case is built so
 *     the cached answer DIFFERS from the correct one: the held slot is a newer segment carrying a
 *     discontinuity, so a run that silently used the right value would fail here. That is what
 *     makes this a test of the cache rather than a coincidence.
 *
 * The anchor seqlock is checked in the other direction, because its roles are inverted: its writer
 * is the real-time thread and its reader is ordinary code, so the reader is the side that gives up.
 * A held-odd anchor sequence must make `kprt_ring_anchor` return its previous reading with
 * `from_cache` set after a bounded number of attempts, never spin.
 *
 * This suite reaches into `struct kprt_ring` through src/kite_rt_ring_internal.h. That is on
 * purpose: the alternative is a test-only hook in the shipped header, which would be a promise to
 * every future consumer of a library whose whole point is a small surface.
 */

#include "harness.h"
#include "kite_rt.h"
#include "kite_rt_ring_internal.h"
#include "ring_support.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>

#define CHANNELS 2
#define RENDERS_WHILE_HELD 2000
#define RENDER_FRAMES 480

static float destination[RENDER_FRAMES * CHANNELS];

static int64_t monotonic_nanos(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + (int64_t)now.tv_nsec;
}

static void sleep_millis(long millis)
{
    struct timespec request;
    request.tv_sec = millis / 1000;
    request.tv_nsec = (millis % 1000) * 1000000L;
    (void)nanosleep(&request, NULL);
}

/* ---- The holder thread ---- */

typedef struct {
    _Atomic int64_t *sequence;
    /* Set by the holder once the sequence is odd, so the main thread starts measuring only when
     * the state it wants to measure actually exists. */
    _Atomic int32_t held;
    /* Set by the main thread when it is finished, so the holder releases and joins. */
    _Atomic int32_t release;
    /* How long the holder kept it odd, so the report carries a real number. */
    _Atomic int64_t held_nanos;
    /* A ceiling, so a bug in the main thread cannot hang the suite forever. */
    long max_hold_millis;
} holder_state;

static void *hold_sequence_odd(void *argument)
{
    holder_state *state = (holder_state *)argument;
    int64_t started;
    long waited = 0;

    /* One increment takes the sequence odd, which is exactly what a feeder preempted between its
     * two increments leaves behind. */
    atomic_fetch_add_explicit(state->sequence, 1, memory_order_relaxed);
    started = monotonic_nanos();
    atomic_store_explicit(&state->held, 1, memory_order_release);

    while (atomic_load_explicit(&state->release, memory_order_acquire) == 0 &&
           waited < state->max_hold_millis) {
        sleep_millis(1);
        waited++;
    }

    atomic_store_explicit(&state->held_nanos, monotonic_nanos() - started, memory_order_relaxed);
    /* Back to even, with the payload untouched, so the ring is consistent again. */
    atomic_fetch_add_explicit(state->sequence, 1, memory_order_relaxed);
    return NULL;
}

static void start_holder(holder_state *state, pthread_t *thread, _Atomic int64_t *sequence)
{
    state->sequence = sequence;
    atomic_store(&state->held, 0);
    atomic_store(&state->release, 0);
    atomic_store(&state->held_nanos, 0);
    state->max_hold_millis = 5000;
    KT_CHECKF(pthread_create(thread, NULL, hold_sequence_odd, state) == 0, "pthread_create failed");
    while (atomic_load_explicit(&state->held, memory_order_acquire) == 0)
        sleep_millis(1);
}

static void stop_holder(holder_state *state, pthread_t thread)
{
    atomic_store_explicit(&state->release, 1, memory_order_release);
    KT_CHECKF(pthread_join(thread, NULL) == 0, "pthread_join failed");
}

int main(void)
{
    kt_suite_begin("test_ring_bounded");

    /* ---- 1. A held segment slot does not stop the render path ---- */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 8192);
        holder_state state;
        pthread_t thread;
        int64_t giveups_before;
        int64_t started;
        int64_t elapsed;
        int64_t worst = 0;
        int32_t slot_of_new_segment;
        int i;

        KT_NOT_NULL(ring);
        kt_case("a segment slot held odd never blocks render, and the give-up is counted");

        /* A first segment at media time zero, rendered, so the consumer-private cache holds it. */
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 0), 480);
        KT_EQ_INT(kprt_ring_render(ring, destination, RENDER_FRAMES, 1000000000LL), 480);
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 1);
            KT_EQ_I64(anchor.pts_us, kprt_frames_to_micros(480, 48000));
        }

        /* A second segment carrying a real discontinuity: 50 seconds away, so continuity from the
         * first segment predicts something completely different. This is what makes the cached
         * answer distinguishable from the correct one. */
        KT_EQ_INT(kprt_test_feed(ring, 4800, 480, 1, 50000000LL), 4800);
        slot_of_new_segment = (int32_t)((atomic_load(&ring->segments_appended) - 1) % KPRT_MAX_SEGMENTS);
        KT_EQ_I64(atomic_load(&ring->segments_appended), 2);

        giveups_before = kprt_ring_segment_giveups(ring);
        KT_EQ_I64(giveups_before, 0);

        start_holder(&state, &thread, &ring->segments[slot_of_new_segment].seq);

        started = monotonic_nanos();
        for (i = 0; i < RENDERS_WHILE_HELD; i++) {
            int64_t at = monotonic_nanos();
            int64_t took;
            int32_t real = kprt_ring_render(ring, destination, 1, 2000000000LL + i);
            took = monotonic_nanos() - at;
            if (took > worst)
                worst = took;
            KT_CHECKF(real == 1, "render %d returned %d frames while a slot was held", i, real);
        }
        elapsed = monotonic_nanos() - started;

        /* THE assertion. The writer has not released yet, and every render already returned, so no
         * render can have waited for the writer. Nothing here depends on how fast the machine is. */
        KT_CHECKF((atomic_load_explicit(&ring->segments[slot_of_new_segment].seq,
                                        memory_order_relaxed) & 1) != 0,
                  "the holder released before the renders finished, so this run proves nothing "
                  "about waiting: raise max_hold_millis or lower RENDERS_WHILE_HELD");

        KT_EQ_I64(kprt_ring_segment_giveups(ring) - giveups_before, RENDERS_WHILE_HELD);

        /* The anchor was published from the cache, which dates the newest frames by continuity from
         * the FIRST segment. That answer is wrong by 50 seconds, and being wrong here is the point:
         * a torn read is answered with a stale reading and a counter, never with a spin and never
         * with silence. */
        {
            kprt_anchor anchor;
            int64_t last_real_frame = kprt_ring_consumed_frames(ring) - 1;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 1);
            KT_EQ_I64(anchor.pts_us, kprt_frames_to_micros(last_real_frame + 1, 48000));
        }

        stop_holder(&state, thread);

        kt_detail("%d renders in %lld us, worst %lld us, held for %lld us, giveups %lld",
                  RENDERS_WHILE_HELD, (long long)(elapsed / 1000), (long long)(worst / 1000),
                  (long long)(atomic_load(&state.held_nanos) / 1000),
                  (long long)kprt_ring_segment_giveups(ring));

        /* Once the slot is even again the walk resolves normally and the anchor is correct, so the
         * degradation is not sticky. */
        kt_case("the segment walk recovers as soon as the slot is even again");
        KT_EQ_INT(kprt_ring_render(ring, destination, 1, 3000000000LL), 1);
        {
            kprt_anchor anchor;
            int64_t last_real_frame = kprt_ring_consumed_frames(ring) - 1;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 1);
            KT_EQ_I64(anchor.pts_us,
                      50000000LL + kprt_frames_to_micros(last_real_frame + 1 - 480, 48000));
        }
        KT_EQ_I64(kprt_ring_segment_giveups(ring) - giveups_before, RENDERS_WHILE_HELD);
        kprt_ring_destroy(ring);
    }

    /* ---- 2. With no cache at all, a torn walk publishes nothing rather than guessing ---- */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 8192);
        holder_state state;
        pthread_t thread;
        int32_t slot;

        KT_NOT_NULL(ring);
        kt_case("a torn walk with an empty cache publishes nothing, and the clock reads null");
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 0), 480);
        slot = (int32_t)((atomic_load(&ring->segments_appended) - 1) % KPRT_MAX_SEGMENTS);
        start_holder(&state, &thread, &ring->segments[slot].seq);

        KT_EQ_INT(kprt_ring_render(ring, destination, RENDER_FRAMES, 1000000000LL), 480);
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 0);
        }
        KT_EQ_I64(kprt_ring_segment_giveups(ring), 1);
        stop_holder(&state, thread);
        kt_detail("giveups=1 anchor=invalid");
        kprt_ring_destroy(ring);
    }

    /* ---- 3. The anchor reader gives up after a bounded number of attempts ---- */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 8192);
        holder_state state;
        pthread_t thread;
        int64_t good_pts;
        int64_t started;
        int64_t elapsed;
        int reads;

        KT_NOT_NULL(ring);
        kt_case("an anchor sequence held odd makes the reader keep its previous reading");

        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 7000000LL), 480);
        KT_EQ_INT(kprt_ring_render(ring, destination, RENDER_FRAMES, 1000000000LL), 480);
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 1);
            KT_EQ_INT(anchor.from_cache, 0);
            good_pts = anchor.pts_us;
        }
        KT_EQ_I64(kprt_ring_anchor_giveups(ring), 0);

        start_holder(&state, &thread, &ring->anchor_seq);

        started = monotonic_nanos();
        for (reads = 0; reads < 1000; reads++) {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_CHECKF(anchor.from_cache == 1, "read %d did not report a give-up", reads);
            KT_CHECKF(anchor.valid == 1, "read %d lost the previous reading", reads);
            KT_CHECKF(anchor.pts_us == good_pts,
                      "read %d reported %lld, expected the previous reading %lld",
                      reads, (long long)anchor.pts_us, (long long)good_pts);
        }
        elapsed = monotonic_nanos() - started;

        KT_CHECKF((atomic_load_explicit(&ring->anchor_seq, memory_order_relaxed) & 1) != 0,
                  "the holder released before the reads finished, so this run proves nothing");
        KT_EQ_I64(kprt_ring_anchor_giveups(ring), 1000);

        stop_holder(&state, thread);
        kt_detail("1000 bounded reads in %lld us, budget %d attempts each",
                  (long long)(elapsed / 1000), KPRT_ANCHOR_READ_ATTEMPTS);

        kt_case("the anchor reader recovers once the sequence is even again");
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.from_cache, 0);
            KT_EQ_I64(anchor.pts_us, good_pts);
        }
        kprt_ring_destroy(ring);
    }

    /* ---- 4. A flush drops the reader's previous reading too ---- */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 8192);
        holder_state state;
        pthread_t thread;

        KT_NOT_NULL(ring);
        kt_case("after a flush the give-up path reports no anchor rather than the old position");
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 7000000LL), 480);
        KT_EQ_INT(kprt_ring_render(ring, destination, RENDER_FRAMES, 1000000000LL), 480);
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.valid, 1);
        }
        kprt_ring_flush(ring);

        start_holder(&state, &thread, &ring->anchor_seq);
        {
            kprt_anchor anchor;
            kprt_ring_anchor(ring, &anchor);
            KT_EQ_INT(anchor.from_cache, 1);
            KT_EQ_INT(anchor.valid, 0);
        }
        stop_holder(&state, thread);
        kprt_ring_destroy(ring);
    }

    return kt_suite_end();
}
