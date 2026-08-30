/* Silence fill and underrun counting collapse into `kprt_ring_render`.
 *
 * When this suite was written the same two things happened at two levels: `CoreAudioSink` filled the
 * buffer when the render callback was absent, and the Kotlin ring filled it when the ring ran dry,
 * and a comment in the sink called that duplication deliberate. B1.8 removed the sink's copy, because
 * a C callback installed for the life of the sink cannot be absent, so there was no case left for it
 * to cover. This suite is what made the surviving copy trustworthy enough for the other one to go
 * away, and it is why the C ring's tail is `memset` rather than a scalar loop. `KotlinAudioRing`
 * keeps its own fill, because it still serves every sink that renders through a Kotlin callback.
 *
 * The properties, each with its own reason for existing:
 *
 *  - A short read produces exactly the real frames it reported, then exact zeroes to the end of the
 *    request. Not "small values": exact +0.0f in every channel. A destination prefilled with a
 *    loud value is what turns "the ring wrote silence" into a measurement rather than an
 *    assumption about uninitialised memory.
 *  - The underrun counter moves once per short read while the stream is running, and never once
 *    the feeder has said it is ending. Without that distinction every file finishes by reporting a
 *    handful of underruns, which makes the counter useless for spotting the real thing.
 *  - The bytes beyond the request are not touched, which is what says the memset length is right.
 *    ASan catches an overrun into unmapped memory; a guard region catches one into memory that
 *    happens to be ours.
 */

#include "harness.h"
#include "kite_rt.h"
#include "ring_support.h"

#include <stddef.h>
#include <stdint.h>

#define CHANNELS 2
#define REQUEST_MAX 1024
#define GUARD_FRAMES 16

/* The request area, followed by a guard area that must survive every render untouched. */
static float buffer[(REQUEST_MAX + GUARD_FRAMES) * CHANNELS];

#define LOUD (-7.5f)
#define GUARD_VALUE (99.25f)

static void arm_buffer(int32_t request_frames)
{
    int32_t i;
    for (i = 0; i < request_frames * CHANNELS; i++)
        buffer[i] = LOUD;
    for (i = 0; i < GUARD_FRAMES * CHANNELS; i++)
        buffer[(size_t)request_frames * CHANNELS + (size_t)i] = GUARD_VALUE;
}

static void check_guard(int32_t request_frames)
{
    int32_t i;
    for (i = 0; i < GUARD_FRAMES * CHANNELS; i++) {
        float actual = buffer[(size_t)request_frames * CHANNELS + (size_t)i];
        KT_CHECKF(actual == GUARD_VALUE,
                  "render wrote past its request: guard float %d is %.9g, expected %.9g",
                  i, (double)actual, (double)GUARD_VALUE);
    }
}

typedef struct {
    const char *name;
    /* Frames the feeder hands over before the render. */
    int32_t fed;
    /* Frames the device asks for. */
    int32_t requested;
    /* Whether the feeder has declared the stream ending before the render. */
    int32_t ending;
    int32_t expect_real;
    int64_t expect_underruns;
} silence_row;

static const silence_row rows[] = {
    { "a full request is all real audio and counts nothing",        512, 512, 0, 512, 0 },
    { "one frame short of the request leaves one frame of silence", 511, 512, 0, 511, 1 },
    { "half a request",                                            256, 512, 0, 256, 1 },
    { "one real frame and the rest silence",                         1, 512, 0,   1, 1 },
    { "an empty ring is all silence and counts one underrun",        0, 512, 0,   0, 1 },
    { "an empty ring while ending is all silence and counts nothing", 0, 512, 1,  0, 0 },
    { "a short read while ending counts nothing",                  200, 512, 1, 200, 0 },
    { "a one frame request that is starved",                         0,   1, 0,   0, 1 },
    { "the largest request this suite uses",                      1000, 1024, 0, 1000, 1 },
};

int main(void)
{
    size_t i;
    kt_suite_begin("test_ring_silence");

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        const silence_row *row = &rows[i];
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 4096);
        int32_t real;
        int32_t frame;
        int32_t channel;

        KT_NOT_NULL(ring);
        kt_case("%s", row->name);

        if (row->fed > 0)
            KT_EQ_INT(kprt_test_feed(ring, row->fed, 0, 1, 0), row->fed);
        if (row->ending)
            kprt_ring_mark_ending(ring);

        arm_buffer(row->requested);
        real = kprt_ring_render(ring, buffer, row->requested, 1000000000LL);

        KT_EQ_INT(real, row->expect_real);
        KT_EQ_I64(kprt_ring_underruns(ring), row->expect_underruns);
        kt_detail("real=%d silence=%d underruns=%lld",
                  real, row->requested - real, (long long)kprt_ring_underruns(ring));

        /* The real part carries the ramp, every channel of every frame. */
        for (frame = 0; frame < real; frame++) {
            for (channel = 0; channel < CHANNELS; channel++) {
                float actual = buffer[(size_t)frame * CHANNELS + (size_t)channel];
                KT_CHECKF(actual == kprt_test_frame_value(frame),
                          "real frame %d channel %d is %.9g, expected %.9g",
                          frame, channel, (double)actual, (double)kprt_test_frame_value(frame));
            }
        }

        /* The tail is exact zeroes, every float of it. */
        KT_ALL_ZERO_F32(buffer + (size_t)real * CHANNELS,
                        (size_t)(row->requested - real) * CHANNELS);
        check_guard(row->requested);

        kprt_ring_destroy(ring);
    }

    /* Repeated starvation counts once per callback, not once per missing frame. A counter that did
     * the latter would report thousands and mean nothing. */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 4096);
        int callback;
        KT_NOT_NULL(ring);
        kt_case("ten starved callbacks count ten underruns");
        for (callback = 0; callback < 10; callback++) {
            arm_buffer(256);
            KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 0);
            KT_ALL_ZERO_F32(buffer, 256 * CHANNELS);
        }
        KT_EQ_I64(kprt_ring_underruns(ring), 10);
        kprt_ring_destroy(ring);
    }

    /* The end of a file, in the order the engine actually produces it: audio, then the feeder says
     * it has finished, then the ring drains, then silence forever. Marking it late is what makes
     * every file report a handful of underruns as it finishes. */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 4096);
        int callback;
        KT_NOT_NULL(ring);
        kt_case("a file that ends cleanly reports zero underruns");
        KT_EQ_INT(kprt_test_feed(ring, 1000, 0, 1, 0), 1000);
        kprt_ring_mark_ending(ring);
        for (callback = 0; callback < 8; callback++) {
            arm_buffer(256);
            (void)kprt_ring_render(ring, buffer, 256, 0);
            check_guard(256);
        }
        KT_EQ_I64(kprt_ring_underruns(ring), 0);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), 1000);
        kt_detail("consumed=%lld underruns=0", (long long)kprt_ring_consumed_frames(ring));
        kprt_ring_destroy(ring);
    }

    /* A flush clears the ending flag, because the next segment of playback has not finished. */
    {
        kprt_ring *ring = kprt_ring_create(48000, CHANNELS, 4096);
        KT_NOT_NULL(ring);
        kt_case("a flush clears the ending flag, so the next starvation is an underrun again");
        kprt_ring_mark_ending(ring);
        arm_buffer(256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 0);
        KT_EQ_I64(kprt_ring_underruns(ring), 0);
        kprt_ring_flush(ring);
        arm_buffer(256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 0);
        KT_EQ_I64(kprt_ring_underruns(ring), 1);
        kprt_ring_destroy(ring);
    }

    /* Multichannel, because the silence length is a frame count multiplied by a channel count and
     * that multiplication is exactly where a fill length goes wrong. */
    {
        static const int32_t channel_counts[] = { 1, 2, 6, 8 };
        size_t c;
        for (c = 0; c < sizeof(channel_counts) / sizeof(channel_counts[0]); c++) {
            int32_t channels = channel_counts[c];
            kprt_ring *ring = kprt_ring_create(48000, channels, 1024);
            float *wide = buffer;
            int32_t requested = 100;
            int32_t real;
            int32_t k;
            KT_NOT_NULL(ring);
            kt_case("the silence tail is exact at %d channels", channels);
            /* 100 frames at 8 channels is 800 floats, well inside `buffer`. */
            for (k = 0; k < requested * channels; k++)
                wide[k] = LOUD;
            {
                /* 30 frames of real audio, so 70 frames of silence in every channel. */
                kprt_ring_write_window window;
                int32_t granted = kprt_ring_begin_write(ring, 30, &window);
                int32_t frame;
                KT_EQ_INT(granted, 30);
                for (frame = 0; frame < window.first_frames; frame++) {
                    int32_t ch;
                    for (ch = 0; ch < channels; ch++)
                        window.first[(size_t)frame * (size_t)channels + (size_t)ch] = 1.0f;
                }
                KT_EQ_INT(kprt_ring_commit_write(ring, 30, 1, 0), KPRT_COMMIT_PUBLISHED);
            }
            real = kprt_ring_render(ring, wide, requested, 0);
            KT_EQ_INT(real, 30);
            for (k = 0; k < 30 * channels; k++)
                KT_EQ_F32(wide[k], 1.0f);
            KT_ALL_ZERO_F32(wide + (size_t)30 * (size_t)channels,
                            (size_t)(requested - 30) * (size_t)channels);
            kt_detail("silence floats=%d", (requested - 30) * channels);
            kprt_ring_destroy(ring);
        }
    }

    return kt_suite_end();
}
