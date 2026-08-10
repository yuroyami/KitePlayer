/* A real producer and a real consumer, which is the pair the ring exists for.
 *
 * This suite is written to be run under ThreadSanitizer above all. Plan section 15.3 says why TSan
 * earns its keep here: the ring is two seqlocks and two lock-free counters, and a seqlock written
 * with plain or `volatile` fields instead of C11 atomics with explicit fences is a genuine data
 * race that TSan reports. A green run under `-fsanitize=thread` is therefore evidence about the
 * memory model and not just about the arithmetic, which no single-threaded suite can give.
 *
 * What the two threads assert, beyond "it did not crash":
 *
 *  - Every frame the consumer received carries the value the producer put in it, at the position
 *    the producer put it, across every wrap of the ring. A missing release or a missing acquire
 *    shows up here as a frame whose value is one buffer old.
 *  - Samples out equal samples in, exactly, with nothing lost at either end.
 *  - A third thread reads the anchor throughout, which is the only configuration in which the anchor
 *    seqlock has a real writer and a real reader at the same time. Its readings must be monotonically
 *    non-decreasing in media time: a torn read would show up as a timestamp that went backwards, and
 *    that assertion catches a broken seqlock even in a build with no sanitizer.
 *  - Both give-up counters stay a small minority of their attempts. NOT zero, and the difference is
 *    the point. This suite originally asserted `anchor_giveups == 0` and that assertion was measured
 *    wrong: under eight competing spinners on this machine, an ASan build reported 211 give-ups
 *    against 342138 reads. Zero was never a promise the design made. The promise is that the
 *    real-time WRITER never waits, which it pays for by letting the non-real-time READER abandon a
 *    read after a bounded 64 attempts and keep its previous reading. So a give-up under load is the
 *    mechanism working, and the falsifiable statement is the one made here: give-ups are the
 *    exception rather than the rule, which a writer that never restored an even sequence number, or
 *    a reader whose retry loop was broken, would fail outright. `segment_giveups` is bounded the same
 *    way and for the same reason: the feeder opens its one and only segment while the consumer is
 *    already rendering, so a render can catch that single slot update in flight.
 *
 * Frame count is 500000 by default, overridable with KPRT_THREAD_FRAMES, because TSan slows this
 * by about an order of magnitude and a gate should not take minutes.
 */

#include "harness.h"
#include "kite_rt.h"
#include "ring_support.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>

#define CHANNELS 2
#define CAPACITY_FRAMES 2048
#define SAMPLE_RATE 48000
#define MAX_RENDER 512

/* How many anchor reads the give-up bound needs behind it before it means anything. */
#define MIN_ANCHOR_READS 1000

typedef struct {
    kprt_ring *ring;
    int64_t total_frames;
    /* Set by whichever thread fails, so the failure is reported from main rather than from a
     * thread, where an exit(1) would leave the others running. */
    _Atomic int32_t failed;
    char failure[256];
    /* Cross-checked in main. */
    _Atomic int64_t produced;
    _Atomic int64_t consumed;
    _Atomic int64_t underruns_seen;
    _Atomic int64_t renders;
    _Atomic int64_t anchor_reads;
    _Atomic int32_t stop_anchor_reader;
} run_state;

/* A cheap deterministic sequence, so a failure is reproducible. Not rand(): rand() takes a lock in
 * some implementations and would make the producer's timing depend on the consumer's. */
static uint32_t next_random(uint32_t *seed)
{
    *seed = (*seed * 1664525u) + 1013904223u;
    return (*seed >> 16);
}

static void record_failure(run_state *state, const char *message)
{
    if (atomic_exchange(&state->failed, 1) == 0) {
        size_t i = 0;
        while (message[i] != '\0' && i + 1 < sizeof(state->failure)) {
            state->failure[i] = message[i];
            i++;
        }
        state->failure[i] = '\0';
    }
}

static void *producer(void *argument)
{
    run_state *state = (run_state *)argument;
    uint32_t seed = 0x51ed270bu;
    int64_t written = 0;

    while (written < state->total_frames && atomic_load(&state->failed) == 0) {
        int32_t want = (int32_t)(next_random(&seed) % 256u) + 1;
        int32_t accepted;
        if ((int64_t)want > state->total_frames - written)
            want = (int32_t)(state->total_frames - written);
        /* A timestamp on every chunk, continuous with the stream, so exactly one segment is ever
         * opened. That is the shape a real decoder produces, and it is also the shape in which a
         * spurious extra segment would be a visible bug. */
        accepted = kprt_test_feed(state->ring, want, written, 1,
                                  kprt_frames_to_micros(written, SAMPLE_RATE));
        if (accepted == 0)
            continue;
        written += accepted;
        atomic_store_explicit(&state->produced, written, memory_order_relaxed);
    }
    kprt_ring_mark_ending(state->ring);
    return NULL;
}

static void *consumer(void *argument)
{
    run_state *state = (run_state *)argument;
    uint32_t seed = 0x2f9a13c7u;
    static float destination[MAX_RENDER * CHANNELS];
    int64_t consumed = 0;
    int64_t deadline = 1000000000LL;
    int64_t underruns = 0;
    int64_t renders = 0;

    while (consumed < state->total_frames && atomic_load(&state->failed) == 0) {
        int32_t want = (int32_t)(next_random(&seed) % MAX_RENDER) + 1;
        int32_t real = kprt_ring_render(state->ring, destination, want, deadline);
        int32_t frame;
        int32_t channel;

        deadline += 10000000LL;
        renders++;
        if (real < want)
            underruns++;

        for (frame = 0; frame < real; frame++) {
            for (channel = 0; channel < CHANNELS; channel++) {
                float actual = destination[(size_t)frame * CHANNELS + (size_t)channel];
                float expected = kprt_test_frame_value(consumed + frame);
                if (actual != expected) {
                    record_failure(state, "a rendered frame carried the wrong value");
                    return NULL;
                }
            }
        }
        for (frame = real; frame < want; frame++) {
            for (channel = 0; channel < CHANNELS; channel++) {
                if (destination[(size_t)frame * CHANNELS + (size_t)channel] != 0.0f) {
                    record_failure(state, "the silence tail of a short read was not exact zero");
                    return NULL;
                }
            }
        }

        consumed += real;
        atomic_store_explicit(&state->consumed, consumed, memory_order_relaxed);
    }
    atomic_store_explicit(&state->underruns_seen, underruns, memory_order_relaxed);
    atomic_store_explicit(&state->renders, renders, memory_order_relaxed);
    return NULL;
}

/* Reads the anchor while the real-time thread is writing it. The only assertion it can make
 * without knowing the schedule is that media time never goes backwards, and that is enough: a torn
 * read pairs a new pts with an old one's low half, which on this data is a jump of thousands of
 * microseconds in one direction or the other. */
static void *anchor_reader(void *argument)
{
    run_state *state = (run_state *)argument;
    int64_t last_pts = -1;
    int64_t reads = 0;

    while (atomic_load_explicit(&state->stop_anchor_reader, memory_order_acquire) == 0) {
        kprt_anchor anchor;
        kprt_ring_anchor(state->ring, &anchor);
        reads++;
        if (anchor.valid == 0)
            continue;
        if (anchor.pts_us < last_pts) {
            record_failure(state, "the published anchor went backwards in media time");
            return NULL;
        }
        last_pts = anchor.pts_us;
        /* Published every iteration rather than once at the end, so the main thread can wait for
         * this thread to have actually run. Measured: under eight competing spinners an ASan run
         * reported zero anchor reads, because the reader was not scheduled at all before the main
         * thread had already joined the other two and asked it to stop. The counter was then a fact
         * about the scheduler rather than about the ring. */
        atomic_store_explicit(&state->anchor_reads, reads, memory_order_relaxed);
    }
    atomic_store_explicit(&state->anchor_reads, reads, memory_order_relaxed);
    return NULL;
}

/* Waits until the anchor reader has done real work, so its counter measures the ring rather than the
 * scheduler. Bounded, so a genuinely stuck reader fails the suite instead of hanging it. */
static int wait_for_anchor_reads(run_state *state, int64_t wanted, long max_millis)
{
    long waited;
    for (waited = 0; waited < max_millis; waited++) {
        struct timespec request;
        if (atomic_load_explicit(&state->anchor_reads, memory_order_relaxed) >= wanted)
            return 1;
        request.tv_sec = 0;
        request.tv_nsec = 1000000L;
        (void)nanosleep(&request, NULL);
    }
    return atomic_load_explicit(&state->anchor_reads, memory_order_relaxed) >= wanted;
}

/* ---- Interlude item I-06: the flush-versus-feeder scaffolding ---- */
typedef struct flush_race_state {
    kprt_ring *ring;
    atomic_int stop;
    int64_t begins;
} flush_race_state;

static void *flush_race_feeder(void *arg)
{
    flush_race_state *frs = (flush_race_state *)arg;
    float scratch[64 * 8];
    while (!atomic_load(&frs->stop)) {
        kprt_ring_write_window window;
        int32_t granted = kprt_ring_begin_write(frs->ring, 64, &window);
        if (granted > 0) {
            int32_t samples = granted * CHANNELS;
            int32_t i;
            (void)scratch;
            for (i = 0; i < window.first_frames * CHANNELS; i++)
                window.first[i] = 0.25f;
            if (window.second != NULL)
                for (i = 0; i < window.second_frames * CHANNELS; i++)
                    window.second[i] = 0.25f;
            (void)samples;
            (void)kprt_ring_commit_write(frs->ring, granted, 0, 0);
        }
        frs->begins++;
    }
    return NULL;
}

int main(void)
{
    static run_state state;
    const char *override_frames = getenv("KPRT_THREAD_FRAMES");
    pthread_t producer_thread;
    pthread_t consumer_thread;
    pthread_t reader_thread;

    kt_suite_begin("test_ring_threads");

    state.ring = kprt_ring_create(SAMPLE_RATE, CHANNELS, CAPACITY_FRAMES);
    KT_NOT_NULL(state.ring);
    state.total_frames = (override_frames != NULL) ? atoll(override_frames) : 500000;
    KT_CHECKF(state.total_frames > 0, "KPRT_THREAD_FRAMES must be positive");

    kt_case("a producer and a consumer move %lld frames with no lost or reordered sample",
            (long long)state.total_frames);

    KT_CHECKF(pthread_create(&reader_thread, NULL, anchor_reader, &state) == 0, "pthread_create failed");
    KT_CHECKF(pthread_create(&producer_thread, NULL, producer, &state) == 0, "pthread_create failed");
    KT_CHECKF(pthread_create(&consumer_thread, NULL, consumer, &state) == 0, "pthread_create failed");

    KT_CHECKF(pthread_join(producer_thread, NULL) == 0, "pthread_join failed");
    KT_CHECKF(pthread_join(consumer_thread, NULL) == 0, "pthread_join failed");
    KT_CHECKF(wait_for_anchor_reads(&state, MIN_ANCHOR_READS, 10000),
              "the anchor reader managed only %lld reads in ten seconds, so its give-up count would "
              "describe the scheduler rather than the ring",
              (long long)atomic_load(&state.anchor_reads));
    atomic_store_explicit(&state.stop_anchor_reader, 1, memory_order_release);
    KT_CHECKF(pthread_join(reader_thread, NULL) == 0, "pthread_join failed");

    KT_CHECKF(atomic_load(&state.failed) == 0, "%s", state.failure);
    KT_EQ_I64(atomic_load(&state.produced), state.total_frames);
    KT_EQ_I64(atomic_load(&state.consumed), state.total_frames);
    KT_EQ_I64(kprt_ring_written_frames(state.ring), state.total_frames);
    KT_EQ_I64(kprt_ring_consumed_frames(state.ring), state.total_frames);
    kt_detail("underrun callbacks=%lld ring underruns=%lld anchor reads=%lld",
              (long long)atomic_load(&state.underruns_seen),
              (long long)kprt_ring_underruns(state.ring),
              (long long)atomic_load(&state.anchor_reads));

    /* Exactly one segment for a continuous stream. A ring that opened one per buffer would make the
     * clock follow the container's timestamp jitter, and would show up here as thousands. */
    kt_case("a continuous stream opens exactly one timestamp segment");
    {
        kprt_ring_stats stats;
        kprt_ring_read_stats(state.ring, &stats);
        KT_EQ_I64(stats.segments_appended, 1);
        KT_EQ_I64(stats.segments_retired, 0);
        kt_detail("appended=%lld retired=%lld", (long long)stats.segments_appended,
                  (long long)stats.segments_retired);
    }

    /* The give-ups, bounded rather than absent. See the note at the top of this file: asserting zero
     * here was measured wrong, and it was wrong about the design and not just about the machine. */
    kt_case("both give-up counters stay a small minority of their attempts");
    {
        kprt_ring_stats stats;
        int64_t renders = atomic_load(&state.renders);
        int64_t reads = atomic_load(&state.anchor_reads);
        kprt_ring_read_stats(state.ring, &stats);
        kt_detail("segment %lld of %lld renders, anchor %lld of %lld reads",
                  (long long)stats.segment_giveups, (long long)renders,
                  (long long)stats.anchor_giveups, (long long)reads);
        KT_CHECKF(renders > 0 && reads > 0, "no renders or no anchor reads happened at all");
        KT_CHECKF(stats.segment_giveups * 2 < renders,
                  "the segment walk gave up on %lld of %lld renders, which is not a minority: the "
                  "per-slot sequence protocol is broken rather than merely contended",
                  (long long)stats.segment_giveups, (long long)renders);
        KT_CHECKF(stats.anchor_giveups * 2 < reads,
                  "the anchor reader gave up on %lld of %lld reads, which is not a minority: either "
                  "the writer never restores an even sequence number or the retry loop is broken",
                  (long long)stats.anchor_giveups, (long long)reads);
    }

    /* Now that both sides are provably quiescent, the flush precondition holds. */
    kt_case("a flush after both sides are quiescent leaves an empty, usable ring");
    kprt_ring_flush(state.ring);
    KT_EQ_INT(kprt_ring_buffered_frames(state.ring), 0);
    KT_EQ_INT(kprt_ring_free_frames(state.ring), CAPACITY_FRAMES);
    {
        kprt_anchor anchor;
        kprt_ring_anchor(state.ring, &anchor);
        KT_EQ_INT(anchor.valid, 0);
    }
    KT_EQ_INT(kprt_test_feed(state.ring, 64, 0, 1, 0), 64);

    kprt_ring_destroy(state.ring);
    /* ---- Interlude item I-06: a flusher racing a live feeder is DEFINED, not clean-by-luck ----
     *
     * The engine reaches this interleaving on purpose: runSeek warns BadTimestamps when its
     * quiesce times out and then continues to the flush, so a flush CAN land between a feeder's
     * begin and its commit. The semantic outcome is contractual (the commit answers
     * KPRT_COMMIT_BAD_ARGUMENT and the Kotlin side decides what that means); what must never be
     * true is that the interleaving is a DATA RACE, and before the reservation fields went
     * _Atomic, TSan reported exactly that here: `Write of size 4 kprt_ring_flush ... Previous
     * write kprt_ring_begin_write`. This case drives the race hard under the tsan variant and
     * accepts every verdict; the sanitizer is the assertion. */
    {
        static flush_race_state frs;
        pthread_t feeder_thread;
        int flushes;
        frs.ring = kprt_ring_create(SAMPLE_RATE, CHANNELS, 4096);
        KT_NOT_NULL(frs.ring);
        atomic_store(&frs.stop, 0);
        kt_case("a flush racing a live feeder is a defined interleaving");
        KT_CHECKF(pthread_create(&feeder_thread, NULL, flush_race_feeder, &frs) == 0,
                  "pthread_create failed");
        for (flushes = 0; flushes < 20000; flushes++)
            kprt_ring_flush(frs.ring);
        atomic_store(&frs.stop, 1);
        pthread_join(feeder_thread, NULL);
        kt_detail("feeder made %lld begins against 20000 flushes", (long long)frs.begins);
        KT_CHECKF(frs.begins > 0, "the feeder never ran, so nothing raced");
        kprt_ring_destroy(frs.ring);
    }

    return kt_suite_end();
}
