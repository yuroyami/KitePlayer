/* The correctness suite for the C ring.
 *
 * These cases are the C counterparts of `AudioRingTest`'s, deliberately: the Kotlin ring is the
 * specification, the differential oracle in `kiteplayer-core/src/nativeTest` compares the two
 * implementations against each other, and this suite is what makes a C-side failure land here,
 * with a case name and a byte index, instead of arriving as "the oracle disagrees somewhere".
 *
 * It also covers what the oracle cannot reach from Kotlin: the reservation-shaped writer, whose
 * two-span window has no equivalent on the Kotlin side at all.
 *
 * Every case prints one line and the suite exits on the first failure.
 */

#include "harness.h"
#include "kite_rt.h"
#include "ring_support.h"

#include <stddef.h>
#include <stdint.h>

#define MAX_RENDER_FRAMES 8192
#define CHANNELS 2

/* One destination buffer for the whole suite, prefilled with a value that is not silence, so a
 * case that expects zeroes is asserting that the ring wrote them rather than that nobody wrote
 * anything. */
static float destination[MAX_RENDER_FRAMES * CHANNELS];

static void poison_destination(int32_t frames)
{
    int32_t i;
    for (i = 0; i < frames * CHANNELS; i++)
        destination[i] = -12345.0f;
}

static int32_t render(kprt_ring *ring, int32_t frames, int64_t deadline_nanos)
{
    poison_destination(frames);
    return kprt_ring_render(ring, destination, frames, deadline_nanos);
}

/* Asserts every channel of every rendered frame in [0, frames) carries the ramp value for
 * `from + i`, which is what says the interleave and the wrap arithmetic are both right. */
static void expect_ramp(int32_t frames, int64_t from)
{
    int32_t i;
    int32_t c;
    for (i = 0; i < frames; i++) {
        for (c = 0; c < CHANNELS; c++) {
            float actual = destination[(size_t)i * CHANNELS + (size_t)c];
            float expected = kprt_test_frame_value(from + i);
            KT_CHECKF(actual == expected,
                      "rendered frame %d channel %d is %.9g, expected %.9g",
                      i, c, (double)actual, (double)expected);
        }
    }
}

static kprt_ring *make_ring(int32_t sample_rate, int32_t capacity_frames)
{
    kprt_ring *ring = kprt_ring_create(sample_rate, CHANNELS, capacity_frames);
    KT_NOT_NULL(ring);
    return ring;
}

static int64_t anchor_pts(kprt_ring *ring)
{
    kprt_anchor anchor;
    kprt_ring_anchor(ring, &anchor);
    KT_CHECKF(anchor.valid != 0, "the ring published no anchor");
    KT_CHECKF(anchor.from_cache == 0, "the anchor reader gave up, which no single-threaded case can cause");
    return anchor.pts_us;
}

static int64_t anchor_nanos(kprt_ring *ring)
{
    kprt_anchor anchor;
    kprt_ring_anchor(ring, &anchor);
    KT_CHECKF(anchor.valid != 0, "the ring published no anchor");
    return anchor.audible_at_nanos;
}

static int anchor_is_valid(kprt_ring *ring)
{
    kprt_anchor anchor;
    kprt_ring_anchor(ring, &anchor);
    return anchor.valid != 0;
}

/* ---- The table of anchor shapes, one row per callback pattern the device can produce ---- */

typedef enum { STEP_FEED, STEP_PULL } step_kind;

typedef struct {
    step_kind kind;
    /* FEED: frames handed over, and where the decoder said the first of them sits on the media
     * timeline counted in FRAMES at the rate under test, or -1 for a buffer carrying no
     * timestamp. Counting in frames rather than microseconds is what keeps one table meaningful
     * at four sample rates: a gap of a hundred thousand frames is a real discontinuity at every
     * rate. */
    int32_t frames;
    int64_t media_frame;
    /* PULL: frames of real audio expected, and where the boundary must land, as a segment start
     * plus an offset into it. Never as a number of microseconds, because that is what sample
     * exact means: the only arithmetic allowed between the two is the one the sample rate
     * defines. */
    int32_t expect_real;
    int64_t segment_starts_at;
    int64_t frames_into_segment;
} anchor_step;

typedef struct {
    const char *name;
    const anchor_step *steps;
    size_t step_count;
} anchor_case;

#define FEED(f, m)          { STEP_FEED, (f), (m), 0, 0, 0 }
#define PULL(f, r, s, o)    { STEP_PULL, (f), 0, (r), (s), (o) }

static const anchor_step case_one_callback[] = {
    FEED(480, 0),
    PULL(480, 480, 0, 480),
};

static const anchor_step case_partial_callbacks[] = {
    FEED(1000, 4000),
    PULL(300, 300, 4000, 300),
    PULL(300, 300, 4000, 600),
    PULL(1, 1, 4000, 601),
    PULL(399, 399, 4000, 1000),
};

static const anchor_step case_silence_tail[] = {
    FEED(200, 0),
    PULL(512, 200, 0, 200),
    /* The frame stream carries on where it stopped, so this continues the same segment even
     * though the device heard silence in between. */
    FEED(200, 200),
    PULL(512, 200, 0, 400),
};

static const anchor_step case_no_timestamp[] = {
    FEED(240, 1000),
    FEED(240, -1),
    FEED(240, -1),
    PULL(720, 720, 1000, 720),
};

static const anchor_step case_exact_boundary[] = {
    FEED(480, 0),
    /* A landing after a seek: this buffer is nowhere near where the last one ended. */
    FEED(480, 96000),
    /* The boundary after the last frame of the first segment belongs to that segment, not to the
     * one starting at the same frame index. */
    PULL(480, 480, 0, 480),
    PULL(480, 480, 96000, 480),
};

static const anchor_step case_crosses_discontinuity[] = {
    FEED(300, 0),
    FEED(300, 96000),
    PULL(600, 600, 96000, 300),
};

static const anchor_step case_one_frame_short[] = {
    FEED(256, 0),
    FEED(256, 96000),
    PULL(255, 255, 0, 255),
    PULL(2, 2, 96000, 1),
    PULL(255, 255, 96000, 256),
};

static const anchor_step case_four_segments[] = {
    FEED(100, 0),
    FEED(100, 96000),
    FEED(100, 192000),
    FEED(100, 288000),
    PULL(100, 100, 0, 100),
    PULL(100, 100, 96000, 100),
    PULL(100, 100, 192000, 100),
    PULL(100, 100, 288000, 100),
};

#define CASE(name, steps) { name, steps, sizeof(steps) / sizeof(steps[0]) }

static const anchor_case anchor_cases[] = {
    CASE("one callback takes everything", case_one_callback),
    CASE("partial callbacks walk through one segment", case_partial_callbacks),
    CASE("a callback runs into a silence tail, and the ring refills behind it", case_silence_tail),
    CASE("buffers with no timestamp continue the segment", case_no_timestamp),
    CASE("a callback ends exactly on a segment boundary", case_exact_boundary),
    CASE("a callback crosses a discontinuity", case_crosses_discontinuity),
    CASE("a callback stops one frame short of a boundary, and the next steps over it", case_one_frame_short),
    CASE("four segments in flight, each dating its own samples", case_four_segments),
};

/* Any instant will do, as long as it is far from zero so a sign mistake is visible. */
#define FIRST_DEADLINE_NANOS 1000000000LL
#define PULL_SPACING_NANOS 10000000LL

static void run_anchor_case(const anchor_case *test_case, int32_t sample_rate)
{
    kprt_ring *ring = make_ring(sample_rate, 2048);
    int64_t written_frames = 0;
    int32_t pull_index = 0;
    size_t i;

    kt_case("%s, at %d Hz", test_case->name, sample_rate);

    for (i = 0; i < test_case->step_count; i++) {
        const anchor_step *step = &test_case->steps[i];
        if (step->kind == STEP_FEED) {
            int32_t has_pts = step->media_frame >= 0 ? 1 : 0;
            int64_t pts_us = has_pts ? kprt_test_media_us(step->media_frame, sample_rate) : 0;
            int32_t accepted = kprt_test_feed(ring, step->frames, written_frames, has_pts, pts_us);
            KT_CHECKF(accepted == step->frames,
                      "step %zu: the whole write must be taken, %d of %d was",
                      i, accepted, step->frames);
            written_frames += accepted;
        } else {
            int64_t deadline = FIRST_DEADLINE_NANOS + (int64_t)pull_index * PULL_SPACING_NANOS;
            int32_t real = render(ring, step->frames, deadline);
            int64_t expected_pts;
            int64_t expected_nanos;
            pull_index++;

            KT_CHECKF(real == step->expect_real,
                      "callback %d: %d frames of real audio, expected %d",
                      pull_index, real, step->expect_real);

            expected_pts = kprt_test_media_us(step->segment_starts_at, sample_rate) +
                kprt_frames_to_micros(step->frames_into_segment, sample_rate);
            KT_CHECKF(anchor_pts(ring) == expected_pts,
                      "callback %d: boundary timestamp %lld, expected %lld",
                      pull_index, (long long)anchor_pts(ring), (long long)expected_pts);

            expected_nanos = deadline -
                (int64_t)((double)(step->frames - real) * (1000000000.0 / (double)sample_rate));
            KT_CHECKF(anchor_nanos(ring) == expected_nanos,
                      "callback %d: boundary instant %lld, expected %lld (deadline less its silence tail)",
                      pull_index, (long long)anchor_nanos(ring), (long long)expected_nanos);
        }
    }

    kt_detail("%zu steps, %d callbacks", test_case->step_count, pull_index);
    kprt_ring_destroy(ring);
}

int main(void)
{
    static const int32_t rates[] = { 44100, 48000, 96000, 192000 };
    size_t i;
    size_t r;

    kt_suite_begin("test_ring_basic");

    /* ---- Bad arguments answer rather than crash ---- */
    kt_case("a ring with a bad argument is refused instead of half built");
    KT_NULL(kprt_ring_create(0, 2, 1024));
    KT_NULL(kprt_ring_create(48000, 0, 1024));
    KT_NULL(kprt_ring_create(48000, 2, 0));
    KT_NULL(kprt_ring_create(48000, 2, -1));
    KT_NULL(kprt_ring_create(48000, 65, 1024));

    kt_case("every entry point tolerates a NULL ring");
    KT_EQ_INT(kprt_ring_render(NULL, destination, 16, 0), 0);
    KT_EQ_INT(kprt_ring_buffered_frames(NULL), 0);
    KT_EQ_INT(kprt_ring_free_frames(NULL), 0);
    KT_EQ_I64(kprt_ring_underruns(NULL), 0);
    KT_EQ_I64(kprt_ring_segment_giveups(NULL), 0);
    kprt_ring_mark_ending(NULL);
    kprt_ring_flush(NULL);
    kprt_ring_destroy(NULL);
    {
        kprt_anchor anchor;
        kprt_ring_anchor(NULL, &anchor);
        KT_EQ_INT(anchor.valid, 0);
    }

    /* ---- Samples ---- */
    {
        kprt_ring *ring = make_ring(48000, 1024);
        kt_case("samples come back in order");
        KT_EQ_INT(kprt_test_feed(ring, 256, 0, 1, 0), 256);
        KT_EQ_INT(render(ring, 256, 0), 256);
        expect_ramp(256, 0);
        kprt_ring_destroy(ring);
    }

    {
        /* This is the bug a two-span window exists to prevent: the second half of a wrapped write
         * or read overwriting the first half instead of following it. */
        kprt_ring *ring = make_ring(48000, 100);
        kt_case("a write and a read that wrap the ring still land contiguously");
        KT_EQ_INT(kprt_test_feed(ring, 80, 0, 1, 0), 80);
        KT_EQ_INT(render(ring, 80, 0), 80);
        KT_EQ_INT(kprt_test_feed(ring, 40, 1000, 0, 0), 40);
        KT_EQ_INT(render(ring, 40, 0), 40);
        expect_ramp(40, 1000);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 100);
        kprt_ring_write_window window;
        kt_case("the granted window is two spans exactly when the reservation wraps");
        KT_EQ_INT(kprt_test_feed(ring, 80, 0, 1, 0), 80);
        KT_EQ_INT(render(ring, 80, 0), 80);
        KT_EQ_INT(kprt_ring_begin_write(ring, 40, &window), 40);
        KT_EQ_INT(window.first_frames, 20);
        KT_EQ_INT(window.second_frames, 20);
        KT_NOT_NULL(window.first);
        KT_NOT_NULL(window.second);
        KT_EQ_I64(window.start_frame, 80);
        kt_detail("first=%d second=%d", window.first_frames, window.second_frames);
        KT_EQ_INT(kprt_ring_commit_write(ring, 40, 0, 0), KPRT_COMMIT_PUBLISHED);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 100);
        kt_case("a write larger than the free space is accepted in part");
        KT_EQ_INT(kprt_test_feed(ring, 500, 0, 1, 0), 100);
        KT_EQ_INT(kprt_test_feed(ring, 10, 100, 0, 0), 0);
        KT_EQ_INT(kprt_ring_free_frames(ring), 0);
        KT_EQ_INT(render(ring, 30, 0), 30);
        KT_EQ_INT(kprt_ring_free_frames(ring), 30);
        KT_EQ_INT(kprt_test_feed(ring, 100, 100, 0, 0), 30);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 48000);
        kt_case("buffered and free frames are reported from the two counters");
        KT_EQ_INT(kprt_test_feed(ring, 4800, 0, 1, 0), 4800);
        KT_EQ_INT(kprt_ring_buffered_frames(ring), 4800);
        KT_EQ_INT(kprt_ring_free_frames(ring), 43200);
        KT_EQ_I64(kprt_frames_to_micros(kprt_ring_buffered_frames(ring), 48000), 100000);
        kprt_ring_destroy(ring);
    }

    /* ---- The anchor ---- */
    {
        kprt_ring *ring = make_ring(48000, 1024);
        kt_case("there is no anchor before the device has played anything");
        KT_CHECK(!anchor_is_valid(ring));
        KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, 0), 100);
        KT_CHECKF(!anchor_is_valid(ring),
                  "writing is not playing: the anchor comes from the render callback");
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 4800);
        kt_case("the anchor is the media time at the playhead boundary when that boundary is reached");
        /* 480 frames is 10 ms at 48 kHz, starting at media time 5.000 s. */
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 5000000), 480);
        KT_EQ_INT(render(ring, 480, 1000000000LL), 480);
        /* Once all 480 frames have been heard the media has played up to 5.000 s plus 480/48000 s.
         * Publishing the last frame's own start instead, 479/48000 s, is one sample period of fixed
         * error that nothing later corrects. */
        KT_EQ_I64(anchor_pts(ring), 5000000LL + 10000LL);
        KT_EQ_I64(anchor_nanos(ring), 1000000000LL);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 4800);
        kt_case("a partly silent buffer back-dates the anchor by the silence");
        KT_EQ_INT(kprt_test_feed(ring, 240, 0, 1, 1000000), 240);
        /* The device asked for 480 frames and got 240 of audio then 240 of silence. The last real
         * frame is heard 240 frames before the end of the buffer, which is 5 ms earlier. */
        KT_EQ_INT(render(ring, 480, 1000000000LL), 240);
        KT_EQ_I64(anchor_nanos(ring), 1000000000LL - 5000000LL);
        KT_EQ_I64(anchor_pts(ring), 1000000LL + 5000LL);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 48000);
        int i2;
        kt_case("the timestamp mapping survives buffers that carry no timestamp");
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 2000000), 480);
        for (i2 = 0; i2 < 9; i2++)
            KT_EQ_INT(kprt_test_feed(ring, 480, 480 + (int64_t)i2 * 480, 0, 0), 480);
        KT_EQ_INT(render(ring, 4800, 0), 4800);
        KT_EQ_I64(anchor_pts(ring), 2000000LL + 100000LL);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 48000);
        kt_case("a real discontinuity opens a segment of its own");
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 1000000), 480);
        /* The next buffer claims 9.000 s, an eight second gap. Continuity would predict 1.010 s,
         * so this buffer needs a segment of its own rather than the previous one being moved. */
        KT_EQ_INT(kprt_test_feed(ring, 480, 480, 1, 9000000), 480);
        KT_EQ_INT(render(ring, 960, 0), 960);
        KT_EQ_I64(anchor_pts(ring), 9000000LL + 10000LL);
        kt_detail("segments=%lld", (long long)2);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 48000);
        kt_case("a timestamp within the tolerance does not open a segment");
        KT_EQ_INT(kprt_test_feed(ring, 480, 0, 1, 1000000), 480);
        /* Continuity predicts 1.010 s. This buffer says 1.010999 s, which is inside the 1000 us
         * tolerance, so the clock must not follow it. */
        KT_EQ_INT(kprt_test_feed(ring, 480, 480, 1, 1010999), 480);
        KT_EQ_INT(render(ring, 960, 0), 960);
        KT_EQ_I64(anchor_pts(ring), 1000000LL + 20000LL);
        kprt_ring_destroy(ring);
    }

    /* ---- Flush ---- */
    {
        kprt_ring *ring = make_ring(48000, 4800);
        kt_case("flush discards what is unplayed and drops the anchor");
        KT_EQ_INT(kprt_test_feed(ring, 1000, 0, 1, 3000000), 1000);
        KT_EQ_INT(render(ring, 100, 0), 100);
        KT_CHECK(anchor_is_valid(ring));

        kprt_ring_flush(ring);
        KT_EQ_INT(kprt_ring_buffered_frames(ring), 0);
        KT_CHECKF(!anchor_is_valid(ring),
                  "after a seek the audio clock has no valid reading until new audio plays");

        /* And the ring is usable again immediately. */
        KT_EQ_INT(kprt_test_feed(ring, 500, 0, 1, 20000000), 500);
        KT_EQ_INT(render(ring, 500, 0), 500);
        KT_EQ_I64(anchor_pts(ring), 20000000LL + kprt_frames_to_micros(500, 48000));
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 4800);
        kt_case("flush clears the consumer's segment cache, so nothing dates from the old position");
        KT_EQ_INT(kprt_test_feed(ring, 500, 0, 1, 3000000), 500);
        KT_EQ_INT(render(ring, 500, 0), 500);
        kprt_ring_flush(ring);
        /* Untimestamped audio after a flush has nothing to be dated by. The honest answer is no
         * anchor at all; dating it from the cache would report the abandoned position. */
        KT_EQ_INT(kprt_test_feed(ring, 500, 0, 0, 0), 500);
        KT_EQ_INT(render(ring, 500, 0), 500);
        KT_CHECKF(!anchor_is_valid(ring), "the pre-flush segment cache must not date post-flush audio");
        KT_EQ_I64(kprt_ring_segment_giveups(ring), 0);
        kprt_ring_destroy(ring);
    }

    /* ---- Segment pressure ---- */
    {
        kprt_ring *ring = make_ring(48000, 4800);
        int segment;
        kt_case("a fifth segment waits for one to be consumed and then dates its own samples");
        /* Four discontinuous buffers, none of them played, so all four segments are still needed
         * to date what is queued. */
        for (segment = 0; segment < 4; segment++) {
            KT_CHECKF(kprt_test_feed(ring, 100, segment * 100, 1, segment * 10000LL) == 100,
                      "segment %d must be accepted", segment);
        }
        KT_CHECKF(kprt_test_feed(ring, 100, 400, 1, 999000) == 0,
                  "a fifth segment must not displace one that still dates queued audio: "
                  "the feeder is told to wait");

        /* Playing past the first segment finishes it, which frees its slot. */
        KT_EQ_INT(render(ring, 150, 0), 150);
        KT_EQ_INT(kprt_test_feed(ring, 100, 400, 1, 999000), 100);

        /* Frames 150 to 499, so the last of them is the hundredth frame of the newest segment. */
        KT_EQ_INT(render(ring, 350, 0), 350);
        KT_EQ_I64(anchor_pts(ring), 999000LL + kprt_frames_to_micros(100, 48000));
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 4800);
        kprt_ring_write_window window;
        kt_case("a refused commit leaves the reservation outstanding and publishes nothing");
        {
            int segment;
            for (segment = 0; segment < 4; segment++)
                KT_EQ_INT(kprt_test_feed(ring, 100, segment * 100, 1, segment * 10000LL), 100);
        }
        KT_EQ_INT(kprt_ring_begin_write(ring, 100, &window), 100);
        KT_EQ_INT(kprt_ring_commit_write(ring, 100, 1, 999000), KPRT_COMMIT_NEEDS_SEGMENT);
        KT_EQ_I64(kprt_ring_written_frames(ring), 400);
        /* Consume one segment's worth, then retry the SAME reservation. */
        KT_EQ_INT(render(ring, 150, 0), 150);
        KT_EQ_INT(kprt_ring_commit_write(ring, 100, 1, 999000), KPRT_COMMIT_PUBLISHED);
        KT_EQ_I64(kprt_ring_written_frames(ring), 500);
        kprt_ring_destroy(ring);
    }

    {
        kprt_ring *ring = make_ring(48000, 1024);
        kt_case("a commit with no reservation, or for more frames than were granted, is refused");
        KT_EQ_INT(kprt_ring_commit_write(ring, 10, 0, 0), KPRT_COMMIT_BAD_ARGUMENT);
        {
            kprt_ring_write_window window;
            KT_EQ_INT(kprt_ring_begin_write(ring, 32, &window), 32);
            KT_EQ_INT(kprt_ring_commit_write(ring, 33, 0, 0), KPRT_COMMIT_BAD_ARGUMENT);
            KT_EQ_INT(kprt_ring_commit_write(ring, 16, 0, 0), KPRT_COMMIT_PUBLISHED);
            KT_EQ_I64(kprt_ring_written_frames(ring), 16);
        }
        kprt_ring_destroy(ring);
    }

    /* ---- The tolerance boundary, and the arithmetic at the ends of the range ----
     *
     * These four cases exist because of the independent verification of B1.8, and each one is a
     * regression test for something it found rather than a case anyone wrote first.
     *
     * The boundary: the tolerance is a strict `<`, and turning that one character into `<=` in this
     * ring alone passed every test in the whole B1.7 and B1.8 gate, because nothing drove a drift of
     * exactly 1000 microseconds. A pseudo-random session does not find a boundary; only a row on it
     * does.
     *
     * The ends of the range: `predicted - pts_us` and its negation used to be unchecked, and UBSan
     * named the subtraction with `-9223372036854774474 - 9223372036854775807 cannot be represented in
     * type 'int64_t'`. These cases drive exactly that, so the `asan` variant, which is built with
     * `-fsanitize=address,undefined`, is what proves the undefined behaviour is gone rather than
     * merely rearranged. None of it is reachable from real media: it needs a timestamp about 292,000
     * years from the origin. Definedness is not an argument about reachability. */
    {
        const int32_t rate = 48000;
        const int64_t hundred_us = kprt_frames_to_micros(100, rate);

        kt_case("a drift of 999 microseconds continues the segment and 1000 opens a new one");
        {
            kprt_ring *ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, 0), 100);
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, hundred_us + 999), 100);
            KT_EQ_INT(render(ring, 200, FIRST_DEADLINE_NANOS), 200);
            /* Continued, so the anchor is dated from the FIRST segment: 200 frames past pts zero. */
            KT_EQ_I64(anchor_pts(ring), kprt_frames_to_micros(200, rate));
            kprt_ring_destroy(ring);

            ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, 0), 100);
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, hundred_us + 1000), 100);
            KT_EQ_INT(render(ring, 200, FIRST_DEADLINE_NANOS), 200);
            /* A segment opened, so the anchor is dated from it: its own timestamp plus 100 frames. */
            KT_EQ_I64(anchor_pts(ring), hundred_us + 1000 + hundred_us);
            kprt_ring_destroy(ring);
        }
        kt_detail("999 continued at pts %lld, 1000 opened at pts %lld",
                  (long long)kprt_frames_to_micros(200, rate),
                  (long long)(hundred_us + 1000 + hundred_us));

        kt_case("the same boundary on the negative side of the prediction");
        {
            kprt_ring *ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, 0), 100);
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, hundred_us - 999), 100);
            KT_EQ_INT(render(ring, 200, FIRST_DEADLINE_NANOS), 200);
            KT_EQ_I64(anchor_pts(ring), kprt_frames_to_micros(200, rate));
            kprt_ring_destroy(ring);

            ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, 0), 100);
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, hundred_us - 1000), 100);
            KT_EQ_INT(render(ring, 200, FIRST_DEADLINE_NANOS), 200);
            KT_EQ_I64(anchor_pts(ring), hundred_us - 1000 + hundred_us);
            kprt_ring_destroy(ring);
        }
        kt_detail("minus 999 continued, minus 1000 opened");

        kt_case("a drift too large to represent is a discontinuity, not undefined behaviour");
        {
            kprt_ring *ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, INT64_MIN + 1000), 100);
            /* The subtraction UBSan named: predicted is near INT64_MIN, the timestamp is INT64_MAX. */
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, INT64_MAX), 100);
            KT_EQ_INT(render(ring, 100, FIRST_DEADLINE_NANOS), 100);
            KT_EQ_I64(anchor_pts(ring), INT64_MIN + 1000 + hundred_us);
            kprt_ring_destroy(ring);
        }
        kt_detail("both writes accepted, anchor dated from the first segment at %lld",
                  (long long)(INT64_MIN + 1000 + hundred_us));

        kt_case("an anchor too large to represent saturates instead of wrapping");
        {
            kprt_ring *ring = make_ring(rate, 4800);
            KT_EQ_INT(kprt_test_feed(ring, 100, 0, 1, INT64_MAX - 1000), 100);
            /* Continuity from that base overflows on the ADD, which is the other unchecked site. */
            KT_EQ_INT(kprt_test_feed(ring, 100, 100, 1, 0), 100);
            KT_EQ_INT(render(ring, 100, FIRST_DEADLINE_NANOS), 100);
            KT_EQ_I64(anchor_pts(ring), INT64_MAX);
            /* And the next hundred frames belong to the second segment, where nothing overflows. */
            KT_EQ_INT(render(ring, 100, FIRST_DEADLINE_NANOS + PULL_SPACING_NANOS), 100);
            KT_EQ_I64(anchor_pts(ring), hundred_us);
            kprt_ring_destroy(ring);
        }
        kt_detail("saturated at INT64_MAX, then dated normally at %lld", (long long)hundred_us);
    }

    /* ---- A long session, where an off-by-one in the wrap arithmetic hides ---- */
    {
        kprt_ring *ring = make_ring(48000, 333);
        int64_t next_frame = 0;
        int64_t read_frames = 0;
        int iteration;
        kt_case("a long session of small writes and reads stays consistent");
        for (iteration = 0; iteration < 2000; iteration++) {
            int32_t accepted = kprt_test_feed(ring, 77, next_frame, 0, 0);
            int32_t got;
            next_frame += accepted;
            got = render(ring, 50, 0);
            expect_ramp(got, read_frames);
            read_frames += got;
        }
        KT_CHECKF(read_frames > 90000, "the session moved only %lld frames", (long long)read_frames);
        kt_detail("moved %lld frames", (long long)read_frames);
        kprt_ring_destroy(ring);
    }

    /* ---- Every anchor shape, at every rate the plan names ---- */
    for (r = 0; r < sizeof(rates) / sizeof(rates[0]); r++) {
        for (i = 0; i < sizeof(anchor_cases) / sizeof(anchor_cases[0]); i++)
            run_anchor_case(&anchor_cases[i], rates[r]);
    }

    return kt_suite_end();
}
