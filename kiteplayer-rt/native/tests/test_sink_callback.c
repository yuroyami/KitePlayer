/* The device callback, including malformed CoreAudio buffers, with no device.
 *
 * This suite is the second of the four assertions of plan section 15.2 B1.8, in the order of
 * authority the plan fixes: the render audit is first because it needs no runtime at all, this is
 * second, the supervised device run is third, and the Kotlin heap drift check is corroboration only.
 * Nothing in this file says anything about Kotlin allocation, and the interposer it uses would read
 * zero for a Kotlin callback allocating millions of objects (measured: 229 mallocs before and 230
 * after one million Kotlin objects, because Kotlin/Native takes pages by `mmap`). The evidence about
 * managed allocation is the GC-pressure differential in `kiteplayer-ffmpeg`'s device soak, and this
 * suite is the C half and only the C half.
 *
 * WHY IT CAN DRIVE THE REAL BODY. The ordinary and long cases call `kprt_render_into`, the named
 * sample-moving body of the callback. Malformed AudioBufferList cases call the static
 * `kprt_render_cb` through `kprt_test_invoke_render_callback`, a seam compiled only when
 * build-host.sh defines KPRT_TESTING. CompileKiteRtTask never defines that macro, so the seam is
 * absent from shipped archives and cinterop while these cases still exercise the actual callback
 * guards rather than a test reimplementation.
 *
 * The struct comes from `src/kite_rt_sink_internal.h`, so a sink can be built here field by field
 * with no audio unit behind it. That is the same choice `test_ring_bounded.c` made for the ring, and
 * for the same reason: a test-only hook in the shipped header would be a promise to every future
 * consumer, while an internal header is a promise to nobody.
 */

#include "harness.h"
#include "kite_rt.h"
#include "kite_rt_ring_internal.h"
#include "kite_rt_sink_internal.h"
#include "ring_support.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CHANNELS 2
#define SAMPLE_RATE 48000
#define CAPACITY_FRAMES 8192
#define MAX_RENDER_FRAMES 512

#if !defined(KPRT_TESTING)
#error "test_sink_callback requires the compile-only callback seam"
#endif

/* How many synthetic callbacks the long case makes.
 *
 * Five million, which is what plan section 15.2 B1.8 assertion 2 asks for, in EVERY variant. That was
 * worth measuring before deciding: the full count takes 0.6 seconds wall in plain, 3.9 under ASan and
 * UBSan and 2.9 under TSan on the development machine, so there is no reason to run a smaller number
 * anywhere and no need for the scaling an earlier draft of this file had. KPRT_SINK_CALLBACKS
 * overrides it for experiments; the count actually run is printed on the case line, so a report can
 * never quote five million for a run that did fewer. */
#define DEFAULT_CALLBACKS 5000000

/* Frames the feeder publishes per commit. Smaller than the render's average on purpose, so the
 * render regularly outruns the feeder and the starvation path is exercised rather than described. */
#define FEED_FRAMES 192

static float destination[MAX_RENDER_FRAMES * CHANNELS];

/* ---- A sink with no device behind it ---- */

static kprt_sink sink;

static void reset_sink(kprt_ring *ring)
{
    sink.sample_rate = SAMPLE_RATE;
    sink.channels = CHANNELS;
    sink.device_buffer_frames = MAX_RENDER_FRAMES;
    /* 1 over 1 makes the tick to nanosecond conversion the identity, which keeps the expected
     * deadlines in this file arithmetic a reader can check by hand.
     *
     * It is NOT what this machine reports: `test_sink_timebase.c` measured 125 over 3 here, and that
     * suite is where the real ratio is checked against `AudioConvertHostTimeToNanos` itself. Splitting
     * the two is deliberate. This file is about what the callback does with a time; that file is about
     * whether the time is right. */
    sink.timebase_numer = 1;
    sink.timebase_denom = 1;
    sink.unit = NULL;
    sink.owns_ring = 0;
    sink.running = 0;
    sink.initialized = 0;
    atomic_store(&sink.ring, ring);
    atomic_store(&sink.callbacks, 0);
    atomic_store(&sink.estimated_anchors, 0);
    atomic_store(&sink.zero_filled_callbacks, 0);
    atomic_store(&sink.worst_callback_nanos, 0);
    atomic_store(&sink.last_deadline_nanos, 0);
}

static void prefill(int32_t frames, float value)
{
    int32_t i;
    for (i = 0; i < frames * CHANNELS; i++)
        destination[i] = value;
}

/* The deadline `kprt_render_into` must have handed the ring: the host instant of the buffer's first
 * frame, plus the buffer's own length. Written out here rather than reused from the library, so the
 * two are independent statements of the same rule. */
static int64_t expected_deadline(uint64_t host_ticks, int32_t frames)
{
    int64_t whole = (int64_t)frames / SAMPLE_RATE;
    int64_t rest = (int64_t)frames % SAMPLE_RATE;
    return (int64_t)host_ticks + whole * 1000000000LL + rest * 1000000000LL / SAMPLE_RATE;
}

/* ---- The feeder thread of the long case ---- */

/* The long case's sample values repeat with this period instead of rising forever, and the reason is
 * a measured limit of `float` rather than a preference. `kprt_test_frame_value` in ring_support.h is
 * `(float)frame`, which stops distinguishing consecutive integers above 16,777,216, and this case
 * moves more than a billion frames. Past that point a ramp cannot detect an off-by-one, so the value
 * wraps at a period eight times the ring's capacity: two frames close enough for the ring's wrap
 * arithmetic to confuse can never share a value, which is exactly the property the check needs, and
 * every value stays exactly representable. */
#define VALUE_PERIOD 65536

static float periodic_frame_value(int64_t frame)
{
    return (float)(frame % VALUE_PERIOD);
}

/* `kprt_test_feed` with the periodic value function. The reservation dance is repeated here rather
 * than parameterised into ring_support.h because this is the only suite that needs it, and a shared
 * helper with a switch in it would make five other suites read worse to spare twenty lines in one. */
static int32_t feed_periodic(kprt_ring *ring, int32_t frames, int64_t from, int64_t pts_us)
{
    kprt_ring_write_window window;
    int32_t granted;
    int32_t i;
    int32_t c;
    int32_t status;

    granted = kprt_ring_begin_write(ring, frames, &window);
    if (granted <= 0)
        return 0;

    for (i = 0; i < window.first_frames; i++) {
        float value = periodic_frame_value(from + i);
        for (c = 0; c < CHANNELS; c++)
            window.first[(size_t)i * CHANNELS + (size_t)c] = value;
    }
    for (i = 0; i < window.second_frames; i++) {
        float value = periodic_frame_value(from + window.first_frames + i);
        for (c = 0; c < CHANNELS; c++)
            window.second[(size_t)i * CHANNELS + (size_t)c] = value;
    }

    status = kprt_ring_commit_write(ring, granted, 1, pts_us);
    if (status == KPRT_COMMIT_NEEDS_SEGMENT)
        return 0;
    KT_CHECKF(status == KPRT_COMMIT_PUBLISHED,
              "kprt_ring_commit_write returned %d, which is neither published nor needs-segment",
              status);
    return granted;
}

typedef struct {
    kprt_ring *ring;
    _Atomic int32_t stop;
    _Atomic int64_t published;
    _Atomic int32_t first_commit_done;
} feeder_state;

static void *feeder(void *argument)
{
    feeder_state *state = (feeder_state *)argument;
    int64_t written = 0;
    while (atomic_load_explicit(&state->stop, memory_order_acquire) == 0) {
        int32_t accepted = feed_periodic(state->ring, FEED_FRAMES, written,
                                         kprt_frames_to_micros(written, SAMPLE_RATE));
        if (accepted > 0) {
            written += accepted;
            atomic_store_explicit(&state->published, written, memory_order_release);
            atomic_store_explicit(&state->first_commit_done, 1, memory_order_release);
        }
    }
    return NULL;
}

static int64_t callbacks_wanted(void)
{
    const char *text = getenv("KPRT_SINK_CALLBACKS");
    long long value;
    if (text == NULL || text[0] == '\0')
        return DEFAULT_CALLBACKS;
    value = atoll(text);
    if (value <= 0)
        return DEFAULT_CALLBACKS;
    return (int64_t)value;
}

int main(void)
{
    kprt_ring *ring;
    kt_alloc_counts before;

    kt_suite_begin("test_sink_callback");

    ring = kprt_ring_create(SAMPLE_RATE, CHANNELS, CAPACITY_FRAMES);
    KT_NOT_NULL(ring);

    /* ---- the CoreAudio structure boundary itself ---- */
    kt_case("a wrong buffer count zeroes every declared byte and marks output silent");
    {
        enum { FIRST_BYTES = 11, SECOND_BYTES = 7 };
        unsigned char first[FIRST_BYTES + 1];
        unsigned char second[SECOND_BYTES + 1];
        kprt_test_audio_buffer buffers[2];
        uint32_t flags = 0;
        int32_t i;

        memset(first, 0x5a, sizeof(first));
        memset(second, 0x6b, sizeof(second));
        buffers[0].data = first;
        buffers[0].byte_size = FIRST_BYTES;
        buffers[0].channels = 1;
        buffers[1].data = second;
        buffers[1].byte_size = SECOND_BYTES;
        buffers[1].channels = 1;
        reset_sink(NULL);

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, 8, 2, buffers, 1, 1000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        for (i = 0; i < FIRST_BYTES; i++)
            KT_EQ_INT(first[i], 0);
        for (i = 0; i < SECOND_BYTES; i++)
            KT_EQ_INT(second[i], 0);
        KT_EQ_INT(first[FIRST_BYTES], 0x5a);
        KT_EQ_INT(second[SECOND_BYTES], 0x6b);
        kt_detail("buffers=2 zeroed=%d+%d canaries=intact silence=1",
                  FIRST_BYTES, SECOND_BYTES);
    }

    kt_case("a null destination is refused and marked silent");
    {
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;

        buffer.data = NULL;
        buffer.byte_size = 8u * CHANNELS * (uint32_t)sizeof(float);
        buffer.channels = CHANNELS;
        reset_sink(NULL);

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, 8, 1, &buffer, 1, 2000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        kt_detail("destination=null silence=1");
    }

    kt_case("a zero-sized destination is untouched and marked silent");
    {
        unsigned char canary = 0x7c;
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;

        buffer.data = &canary;
        buffer.byte_size = 0;
        buffer.channels = CHANNELS;
        reset_sink(NULL);

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, 8, 1, &buffer, 1, 3000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        KT_EQ_INT(canary, 0x7c);
        kt_detail("bytes=0 canary=intact silence=1");
    }

    kt_case("a short destination clamps frames without crossing its byte canary");
    {
        enum { WRITABLE_FRAMES = 3, REQUESTED_FRAMES = 8 };
        float samples[WRITABLE_FRAMES * CHANNELS + 2];
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;
        int32_t i;

        for (i = 0; i < WRITABLE_FRAMES * CHANNELS; i++)
            samples[i] = 1.0f;
        samples[WRITABLE_FRAMES * CHANNELS] = 91.0f;
        samples[WRITABLE_FRAMES * CHANNELS + 1] = 92.0f;
        buffer.data = samples;
        buffer.byte_size = WRITABLE_FRAMES * CHANNELS * (uint32_t)sizeof(float);
        buffer.channels = CHANNELS;
        reset_sink(NULL);

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, REQUESTED_FRAMES, 1, &buffer, 1, 4000, &flags), 0);
        KT_ALL_ZERO_F32(samples, WRITABLE_FRAMES * CHANNELS);
        KT_EQ_F32(samples[WRITABLE_FRAMES * CHANNELS], 91.0f);
        KT_EQ_F32(samples[WRITABLE_FRAMES * CHANNELS + 1], 92.0f);
        KT_EQ_I64(atomic_load(&sink.callbacks), 1);
        kt_detail("requested=%d rendered=%d canary=91,92",
                  REQUESTED_FRAMES, WRITABLE_FRAMES);
    }

    kt_case("a zero-frame request silences a writable buffer rather than repeating it");
    {
        /* Every other refusal that HOLDS a writable buffer zeroes it. This one did not: it shares
         * its guard with the null-destination and zero-byte cases, which have nothing to zero, so a
         * zero-frame request handed the device back whatever the previous callback left there. The
         * silence flag is advisory, and a host that renders the buffer anyway repeats the last
         * period of audio. */
        enum { FRAMES = 4 };
        float samples[FRAMES * CHANNELS + 1];
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;
        int32_t i;

        for (i = 0; i < FRAMES * CHANNELS; i++)
            samples[i] = 7.0f;
        samples[FRAMES * CHANNELS] = 93.0f;
        buffer.data = samples;
        buffer.byte_size = FRAMES * CHANNELS * (uint32_t)sizeof(float);
        buffer.channels = CHANNELS;
        reset_sink(NULL);

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, 0, 1, &buffer, 1, 5000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        KT_ALL_ZERO_F32(samples, FRAMES * CHANNELS);
        KT_EQ_F32(samples[FRAMES * CHANNELS], 93.0f);
        kt_detail("requested=0 zeroed=%d canary=93 silence=1", FRAMES * CHANNELS);
    }

    kt_case("a null sink silences the buffer it was handed");
    {
        enum { FRAMES = 4 };
        float samples[FRAMES * CHANNELS + 1];
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;
        int32_t i;

        for (i = 0; i < FRAMES * CHANNELS; i++)
            samples[i] = 11.0f;
        samples[FRAMES * CHANNELS] = 94.0f;
        buffer.data = samples;
        buffer.byte_size = FRAMES * CHANNELS * (uint32_t)sizeof(float);
        buffer.channels = CHANNELS;

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      NULL, FRAMES, 1, &buffer, 1, 6000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        KT_ALL_ZERO_F32(samples, FRAMES * CHANNELS);
        KT_EQ_F32(samples[FRAMES * CHANNELS], 94.0f);
        kt_detail("sink=null zeroed=%d canary=94 silence=1", FRAMES * CHANNELS);
    }

    kt_case("a sink with no channels silences the buffer it was handed");
    {
        enum { FRAMES = 4 };
        float samples[FRAMES * CHANNELS + 1];
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;
        int32_t i;

        for (i = 0; i < FRAMES * CHANNELS; i++)
            samples[i] = 13.0f;
        samples[FRAMES * CHANNELS] = 95.0f;
        buffer.data = samples;
        buffer.byte_size = FRAMES * CHANNELS * (uint32_t)sizeof(float);
        buffer.channels = CHANNELS;
        reset_sink(NULL);
        sink.channels = 0;

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, FRAMES, 1, &buffer, 1, 7000, &flags), 0);
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) != 0);
        KT_ALL_ZERO_F32(samples, FRAMES * CHANNELS);
        KT_EQ_F32(samples[FRAMES * CHANNELS], 95.0f);
        reset_sink(NULL);
        kt_detail("channels=0 zeroed=%d canary=95 silence=1", FRAMES * CHANNELS);
    }

    kt_case("the callback still renders a correct single interleaved buffer");
    {
        enum { FRAMES = 4 };
        float samples[FRAMES * CHANNELS];
        kprt_test_audio_buffer buffer;
        uint32_t flags = 0;
        int32_t i;

        kprt_ring_flush(ring);
        reset_sink(ring);
        KT_EQ_INT(kprt_test_feed(ring, FRAMES, 0, 1, 0), FRAMES);
        for (i = 0; i < FRAMES * CHANNELS; i++)
            samples[i] = -1.0f;
        buffer.data = samples;
        buffer.byte_size = (uint32_t)sizeof(samples);
        buffer.channels = CHANNELS;

        KT_EQ_INT(kprt_test_invoke_render_callback(
                      &sink, FRAMES, 1, &buffer, 1, 5000, &flags), 0);
        for (i = 0; i < FRAMES; i++) {
            KT_EQ_F32(samples[(size_t)i * CHANNELS], kprt_test_frame_value(i));
            KT_EQ_F32(samples[(size_t)i * CHANNELS + 1], kprt_test_frame_value(i));
        }
        KT_CHECK((flags & KPRT_TEST_OUTPUT_IS_SILENCE) == 0);
        KT_EQ_I64(atomic_load(&sink.callbacks), 1);
        kt_detail("buffers=1 frames=%d interleaved=stereo", FRAMES);
        kprt_ring_flush(ring);
    }

    /* ---- teardown: the one silence case left outside the ring ---- */
    kt_case("a callback that finds no ring zeroes the whole buffer and counts it");
    {
        reset_sink(NULL);
        prefill(64, 1.0f);
        KT_EQ_INT(kprt_render_into(&sink, destination, 64, 1000, 0), 0);
        KT_ALL_ZERO_F32(destination, (size_t)64 * CHANNELS);
        KT_EQ_I64(atomic_load(&sink.zero_filled_callbacks), 1);
        KT_EQ_I64(atomic_load(&sink.callbacks), 1);
        /* Nothing was dated, because nothing was played. A deadline stored here would be a lie the
         * clock would then anchor to. */
        KT_EQ_I64(atomic_load(&sink.last_deadline_nanos), 0);
        kt_detail("zero_filled=1 last_deadline=0");
    }

    /* ---- a full buffer, and the deadline the ring was given ---- */
    kt_case("a full buffer of real audio is copied and dated at the buffer boundary");
    {
        const uint64_t host = 900000000ULL;
        const int32_t frames = 256;
        kprt_anchor anchor;
        int32_t i;

        kprt_ring_flush(ring);
        reset_sink(ring);
        KT_EQ_INT(kprt_test_feed(ring, frames, 0, 1, 0), frames);
        prefill(frames, -1.0f);

        KT_EQ_INT(kprt_render_into(&sink, destination, frames, host, 0), frames);
        for (i = 0; i < frames; i++) {
            KT_EQ_F32(destination[(size_t)i * CHANNELS], kprt_test_frame_value(i));
            KT_EQ_F32(destination[(size_t)i * CHANNELS + 1], kprt_test_frame_value(i));
        }
        kprt_ring_anchor(ring, &anchor);
        KT_EQ_INT(anchor.valid, 1);
        KT_EQ_I64(anchor.audible_at_nanos, expected_deadline(host, frames));
        /* 256 frames at 48 kHz is 5333.33 microseconds, truncated toward zero by the exact split
         * rescale. */
        KT_EQ_I64(anchor.pts_us, kprt_frames_to_micros(frames, SAMPLE_RATE));
        KT_EQ_I64(atomic_load(&sink.last_deadline_nanos), expected_deadline(host, frames));
        KT_EQ_I64(atomic_load(&sink.estimated_anchors), 0);
        kt_detail("deadline=%lld pts_us=%lld",
                  (long long)anchor.audible_at_nanos, (long long)anchor.pts_us);
    }

    /* ---- a short read: exact zeroes after the real frames, and exactly one underrun ---- */
    kt_case("a short read is exact zeroes after the real frames and counts one underrun");
    {
        const int32_t frames = 128;
        const int32_t supplied = 40;
        int64_t underruns_before;
        int32_t i;

        kprt_ring_flush(ring);
        reset_sink(ring);
        underruns_before = kprt_ring_underruns(ring);
        KT_EQ_INT(kprt_test_feed(ring, supplied, 0, 1, 0), supplied);
        prefill(frames, 0.75f);

        KT_EQ_INT(kprt_render_into(&sink, destination, frames, 1000000, 0), supplied);
        for (i = 0; i < supplied; i++)
            KT_EQ_F32(destination[(size_t)i * CHANNELS], kprt_test_frame_value(i));
        KT_ALL_ZERO_F32(destination + (size_t)supplied * CHANNELS,
                        (size_t)(frames - supplied) * CHANNELS);
        KT_EQ_I64(kprt_ring_underruns(ring) - underruns_before, 1);
        /* And the buffer was NOT zero filled by the callback: the ring did it, which is the whole of
         * One counter moved, not two. */
        KT_EQ_I64(atomic_load(&sink.zero_filled_callbacks), 0);
        kt_detail("real=%d silence=%d underruns=+1", supplied, frames - supplied);
    }

    /* ---- the ending case: silence without an underrun ---- */
    kt_case("silence after the feeder marked the stream ending is not an underrun");
    {
        const int32_t frames = 128;
        int64_t underruns_before;

        kprt_ring_flush(ring);
        reset_sink(ring);
        kprt_ring_mark_ending(ring);
        underruns_before = kprt_ring_underruns(ring);
        prefill(frames, 0.5f);

        KT_EQ_INT(kprt_render_into(&sink, destination, frames, 2000000, 0), 0);
        KT_ALL_ZERO_F32(destination, (size_t)frames * CHANNELS);
        KT_EQ_I64(kprt_ring_underruns(ring) - underruns_before, 0);
        KT_EQ_I64(atomic_load(&sink.zero_filled_callbacks), 0);
        kt_detail("ending=1 underruns=+0");
        kprt_ring_flush(ring);
    }

    /* ---- an estimated host time is counted rather than logged ---- */
    kt_case("a host time the device did not flag valid is counted as estimated");
    {
        reset_sink(ring);
        KT_EQ_INT(kprt_render_into(&sink, destination, 64, 3000000, 1), 0);
        KT_EQ_INT(kprt_render_into(&sink, destination, 64, 3010000, 1), 0);
        KT_EQ_INT(kprt_render_into(&sink, destination, 64, 3020000, 0), 0);
        KT_EQ_I64(atomic_load(&sink.estimated_anchors), 2);
        KT_EQ_I64(atomic_load(&sink.callbacks), 3);
        kt_detail("estimated=2 of 3 callbacks");
    }

    /* ---- the callback span, which is the number the device run judges ---- */
    kt_case("the worst callback span is kept and a shorter one does not replace it");
    {
        reset_sink(ring);
        kprt_sink_note_span(&sink, 1000, 1400);
        KT_EQ_I64(atomic_load(&sink.worst_callback_nanos), 400);
        kprt_sink_note_span(&sink, 5000, 5100);
        KT_EQ_I64(atomic_load(&sink.worst_callback_nanos), 400);
        kprt_sink_note_span(&sink, 9000, 12000);
        KT_EQ_I64(atomic_load(&sink.worst_callback_nanos), 3000);
        /* A tick pair that went backwards is a clock that cannot happen; it must not be read as an
         * enormous span through unsigned wrap. */
        kprt_sink_note_span(&sink, 100, 50);
        KT_EQ_I64(atomic_load(&sink.worst_callback_nanos), 3000);
        kt_detail("worst=3000 ns after four spans");
    }

    /* ---- assertion 2 proper ---- */
    {
        const int64_t wanted = callbacks_wanted();
        static feeder_state state;
        pthread_t thread;
        uint32_t random_state = 0x5eed1234u;
        int64_t i;
        int64_t real_frames = 0;
        int64_t starvations = 0;
        int64_t requested_frames = 0;
        int64_t underruns_before;
        int64_t consumed_before;

        kt_case("%lld synthetic callbacks against a live feeder allocate nothing and lose no sample",
                (long long)wanted);

        kprt_ring_flush(ring);
        reset_sink(ring);
        underruns_before = kprt_ring_underruns(ring);
        consumed_before = kprt_ring_consumed_frames(ring);
        state.ring = ring;
        atomic_store(&state.stop, 0);
        atomic_store(&state.published, 0);
        atomic_store(&state.first_commit_done, 0);

        /* Thread creation allocates a stack and a control block, so it happens outside the measured
         * window, and so does the join. */
        KT_CHECKF(pthread_create(&thread, NULL, feeder, &state) == 0, "pthread_create failed");

        /* Wait for the feeder's first commit before the window opens.
         *
         * This is what makes `segment_giveups == 0` a sound assertion rather than a lucky one, and
         * B1.7 measured why: the feeder dates one continuous stream, so it opens exactly ONE
         * timestamp segment, inside its first commit. Once that commit has returned, the only slot
         * update of the whole run is finished and its sequence number is even again, so no render can
         * catch a slot in flight and a give-up would mean the protocol is broken rather than
         * contended. `tests/test_ring_threads.c` starts both sides together on purpose and therefore
         * has to state the same property as a bound instead of as a zero. */
        while (atomic_load_explicit(&state.first_commit_done, memory_order_acquire) == 0) {
            /* spin */
        }

        kt_alloc_snapshot(&before);
        for (i = 0; i < wanted; i++) {
            int32_t frames;
            int32_t real;
            /* xorshift32, so the frame counts are a fixed pseudo-random sequence rather than a
             * pattern the ring's wrap arithmetic could accidentally agree with. */
            random_state ^= random_state << 13;
            random_state ^= random_state >> 17;
            random_state ^= random_state << 5;
            frames = (int32_t)(random_state % MAX_RENDER_FRAMES) + 1;

            real = kprt_render_into(&sink, destination, frames,
                                   (uint64_t)4000000000ULL + (uint64_t)i * 10666666ULL, 0);
            KT_CHECKF(real >= 0 && real <= frames, "a render returned %d of %d frames", real, frames);

            /* Spot checks rather than a full scan of every sample, and the reason is measured: a full
             * scan of ten gigabytes of samples under ASan takes tens of minutes, while the first and
             * last real frame catch every wrap and offset mistake the ring can make, because the last
             * real frame is the one on the far side of the wrap. The full sample-by-sample comparison
             * is `test_ring_basic.c`'s job and the differential oracle's. */
            if (real > 0) {
                /* The feeder's value ramp starts at 0 at the moment the window opened, because the
                 * flush above set the ring's consumed counter to its written counter, and the feeder
                 * began publishing from exactly there. */
                int64_t first_frame = real_frames;
                int64_t last_frame = first_frame + real - 1;
                KT_EQ_F32(destination[0], periodic_frame_value(first_frame));
                KT_EQ_F32(destination[(size_t)(real - 1) * CHANNELS + 1],
                          periodic_frame_value(last_frame));
            }
            if (real < frames) {
                starvations++;
                KT_EQ_F32(destination[(size_t)real * CHANNELS], 0.0f);
                KT_EQ_F32(destination[(size_t)frames * CHANNELS - 1], 0.0f);
            }
            real_frames += real;
            requested_frames += frames;
        }
        KT_ALLOC_SILENT(&before);

        atomic_store_explicit(&state.stop, 1, memory_order_release);
        KT_CHECKF(pthread_join(thread, NULL) == 0, "pthread_join failed");

        kt_detail("requested=%lld real=%lld starved=%lld underruns=+%lld giveups=%lld/%lld",
                  (long long)requested_frames, (long long)real_frames, (long long)starvations,
                  (long long)(kprt_ring_underruns(ring) - underruns_before),
                  (long long)kprt_ring_segment_giveups(ring),
                  (long long)kprt_ring_anchor_giveups(ring));

        /* Samples out equal samples in: every frame the feeder published and the render took is
         * accounted for, and the ring's own consumed counter agrees with what this loop counted. */
        KT_EQ_I64(kprt_ring_consumed_frames(ring) - consumed_before, real_frames);
        KT_EQ_I64(atomic_load(&sink.callbacks), wanted);
        /* The underrun counter moved exactly once per starvation, and not once per silent frame. */
        KT_EQ_I64(kprt_ring_underruns(ring) - underruns_before, starvations);
        KT_EQ_I64(kprt_ring_segment_giveups(ring), 0);
        /* A starvation-free run would mean the render never outran the feeder, and then the whole
         * silence and underrun path was never entered. */
        KT_CHECKF(starvations > 0,
                  "no callback was ever starved, so the silence path was not exercised at all");
    }

    kt_case("the ring outlives every callback and is released once");
    kt_alloc_snapshot(&before);
    kprt_ring_destroy(ring);
    KT_ALLOC_EXACTLY(&before, 0, 1);

    return kt_suite_end();
}
