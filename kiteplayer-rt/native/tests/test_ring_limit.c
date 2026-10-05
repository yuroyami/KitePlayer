/* The peak limiter: a frame past full scale is turned down smoothly instead of being clamped by the
 * device, and every other frame goes through untouched (#504).
 *
 * At or below unity the ring does not fold, so a loud downmix or an equaliser boost would hand the
 * device samples above 1, and the device clamps each one, squaring off the wave. The limiter reads
 * the frames waiting in the ring after the ones it renders and lowers the gain over the lookahead
 * before such a frame arrives. The properties:
 *
 *  - Content that never passes full scale, exactly 1 included, comes out bit for bit and counts
 *    nothing.
 *  - A burst at 1.4 times full scale inside a sine at 0.5 never leaves above full scale, its gain
 *    moves by no more than the lookahead allows from one frame to the next, the third harmonic a
 *    clamp would add stays far below the one a clamp leaves, and the frames well before and well
 *    after it come out exactly as they went in.
 *  - A loud frame the lookahead could not see, because the ring did not hold it yet, still never
 *    leaves above full scale.
 *  - A volume low enough that nothing passes full scale, and a steady boost, which the fold
 *    already keeps within it, leave the limiter out of the path.
 *  - A flush starts the limiter over, so the new position plays untouched from its first frame.
 */

#include "harness.h"
#include "kite_rt.h"

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define CHANNELS 2
#define RATE 48000
#define CAPACITY 32768
#define REQUEST 512
#define TOTAL 24000
#define BURST_START 9600
#define BURST_FRAMES 4800
#define TONE_HZ 1000.0
#define PI 3.14159265358979323846

static float input[TOTAL * CHANNELS];
static float output[TOTAL * CHANNELS];

static int32_t feed(kprt_ring *ring, const float *source, int32_t frames)
{
    kprt_ring_write_window window;
    int32_t granted;

    granted = kprt_ring_begin_write(ring, frames, &window);
    if (granted <= 0)
        return 0;
    memcpy(window.first, source, (size_t)window.first_frames * CHANNELS * sizeof(float));
    if (window.second_frames > 0)
        memcpy(window.second, source + (size_t)window.first_frames * CHANNELS,
               (size_t)window.second_frames * CHANNELS * sizeof(float));
    KT_EQ_INT(kprt_ring_commit_write(ring, granted, 1, 0), KPRT_COMMIT_PUBLISHED);
    return granted;
}

/* Feeds the whole input at once and renders it in device-sized requests into `output`. */
static void play_all(kprt_ring *ring, int32_t total)
{
    int32_t done = 0;
    KT_EQ_INT(feed(ring, input, total), total);
    while (done < total) {
        int32_t want = total - done < REQUEST ? total - done : REQUEST;
        KT_EQ_INT(kprt_ring_render(ring, output + (size_t)done * CHANNELS, want, 1000 + done), want);
        done += want;
    }
}

static void make_burst(float level)
{
    int32_t i;
    for (i = 0; i < TOTAL; i++) {
        double amplitude = i >= BURST_START && i < BURST_START + BURST_FRAMES ? level : 0.5;
        float v = (float)(amplitude * sin(2.0 * PI * TONE_HZ * (double)i / RATE));
        input[(size_t)i * CHANNELS] = v;
        input[(size_t)i * CHANNELS + 1] = -v;
    }
}

/* The power at `hz` over frames [from, to) of channel 0, by Goertzel. */
static double power_at(const float *samples, int32_t from, int32_t to, double hz)
{
    double w = 2.0 * PI * hz / RATE;
    double coefficient = 2.0 * cos(w);
    double s1 = 0.0;
    double s2 = 0.0;
    int32_t i;
    for (i = from; i < to; i++) {
        double s0 = samples[(size_t)i * CHANNELS] + coefficient * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    return s1 * s1 + s2 * s2 - coefficient * s1 * s2;
}

static int same_bits(const float *a, const float *b, int32_t frames)
{
    return memcmp(a, b, (size_t)frames * CHANNELS * sizeof(float)) == 0;
}

int main(void)
{
    int32_t lookahead = kprt_limit_lookahead_frames(RATE);
    int32_t release = kprt_limit_release_frames(RATE);

    kt_suite_begin("ring_limit");

    kt_case("the lookahead and the release follow their laws");
    KT_EQ_INT(lookahead, 240);
    KT_EQ_INT(release, 4800);
    KT_EQ_INT(kprt_limit_lookahead_frames(8000), 40);
    KT_EQ_INT(kprt_limit_lookahead_frames(768000), KPRT_LIMIT_MAX_LOOKAHEAD);
    KT_EQ_INT(kprt_limit_lookahead_frames(100), 1);
    KT_EQ_INT(kprt_limit_lookahead_frames(0), 1);

    kt_case("content within full scale, full scale itself included, goes through bit for bit");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        uint32_t state = 12345u;
        int32_t i;
        KT_NOT_NULL(ring);
        for (i = 0; i < TOTAL * CHANNELS; i++) {
            state = state * 1664525u + 1013904223u;
            input[i] = (float)((double)(state >> 8) / (double)(1u << 23) - 1.0);
        }
        input[100] = 1.0f;
        input[101] = -1.0f;
        play_all(ring, TOTAL);
        KT_CHECK(same_bits(input, output, TOTAL));
        KT_EQ_I64(kprt_ring_limited_frames(ring), 0);
        kprt_ring_destroy(ring);
    }

    kt_case("a burst at 1.4 times full scale is turned down smoothly and nothing else moves");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        int32_t burst_end = BURST_START + BURST_FRAMES;
        int32_t settled = burst_end + 2 * lookahead + release;
        float largest = 0.0f;
        float largest_step = 0.0f;
        float previous = -1.0f;
        double fundamental;
        double third;
        double clamped_third;
        int32_t i;
        KT_NOT_NULL(ring);
        make_burst(1.4f);
        play_all(ring, TOTAL);

        for (i = 0; i < TOTAL * CHANNELS; i++) {
            float a = fabsf(output[i]);
            if (a > largest)
                largest = a;
        }
        KT_CHECKF(largest <= 1.0f, "a sample left at %.9g", (double)largest);

        /* Before the lookahead reaches the burst, and once the release has given the gain back,
         * the output is the input exactly. */
        KT_CHECK(same_bits(input, output, BURST_START - lookahead));
        KT_CHECK(same_bits(input + (size_t)settled * CHANNELS, output + (size_t)settled * CHANNELS,
                           TOTAL - settled));

        /* The gain, read where the wave is far enough from zero to read it, moves by no more than
         * the lookahead's share of the reduction from one frame to the next. A clamp would step
         * from 1 to 1/1.4 within a frame. */
        for (i = BURST_START - 2 * lookahead; i < settled; i++) {
            float in = input[(size_t)i * CHANNELS];
            float ratio;
            if (fabsf(in) < 0.1f)
                continue;
            ratio = output[(size_t)i * CHANNELS] / in;
            if (previous >= 0.0f && fabsf(ratio - previous) > largest_step)
                largest_step = fabsf(ratio - previous);
            previous = ratio;
        }
        kt_detail("largest gain step %.6f", (double)largest_step);
        /* Up to (1 - 1/1.4) / lookahead per frame, read across the few frames of a zero crossing. */
        KT_CHECKF(largest_step < 0.02f, "the gain stepped by %.6f", (double)largest_step);

        /* Over the burst, the third harmonic a clamp would add. */
        fundamental = power_at(output, BURST_START, burst_end, TONE_HZ);
        third = power_at(output, BURST_START, burst_end, 3.0 * TONE_HZ);
        for (i = 0; i < TOTAL * CHANNELS; i++)
            input[i] = input[i] > 1.0f ? 1.0f : (input[i] < -1.0f ? -1.0f : input[i]);
        clamped_third = power_at(input, BURST_START, burst_end, 3.0 * TONE_HZ);
        kt_detail("third harmonic %.1f dB under the tone, a clamp's %.1f dB",
                  10.0 * log10(fundamental / third), 10.0 * log10(fundamental / clamped_third));
        KT_CHECKF(third * 1000.0 < clamped_third, "the third harmonic is %.3g against a clamp's %.3g",
                  third, clamped_third);

        KT_CHECK(kprt_ring_limited_frames(ring) >= BURST_FRAMES);
        KT_CHECK(kprt_ring_limited_frames(ring) <= settled - (BURST_START - lookahead));
        kprt_ring_destroy(ring);
    }

    kt_case("a loud frame the ring did not hold yet still never leaves above full scale");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        float largest = 0.0f;
        int32_t i;
        KT_NOT_NULL(ring);
        make_burst(1.4f);
        /* Each render sees exactly the frames it plays and nothing after them. */
        for (i = 0; i < TOTAL; i += REQUEST) {
            int32_t want = TOTAL - i < REQUEST ? TOTAL - i : REQUEST;
            KT_EQ_INT(feed(ring, input + (size_t)i * CHANNELS, want), want);
            KT_EQ_INT(kprt_ring_render(ring, output + (size_t)i * CHANNELS, want, 1000 + i), want);
        }
        for (i = 0; i < TOTAL * CHANNELS; i++) {
            float a = fabsf(output[i]);
            if (a > largest)
                largest = a;
        }
        KT_CHECKF(largest <= 1.0f, "a sample left at %.9g", (double)largest);
        KT_CHECK(kprt_ring_limited_frames(ring) > 0);
        kprt_ring_destroy(ring);
    }

    kt_case("half volume takes the burst under full scale, and the limiter stays out of the path");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        int32_t i;
        int exact = 1;
        KT_NOT_NULL(ring);
        kprt_ring_set_gain(ring, 0.5f);
        make_burst(1.4f);
        play_all(ring, TOTAL);
        for (i = 0; i < TOTAL * CHANNELS; i++)
            if (output[i] != input[i] * 0.5f)
                exact = 0;
        KT_CHECK(exact);
        KT_EQ_I64(kprt_ring_limited_frames(ring), 0);
        kprt_ring_destroy(ring);
    }

    kt_case("a steady boost is left to the fold");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        KT_NOT_NULL(ring);
        kprt_ring_set_gain(ring, 2.0f);
        make_burst(1.4f);
        play_all(ring, TOTAL);
        KT_EQ_I64(kprt_ring_limited_frames(ring), 0);
        kprt_ring_destroy(ring);
    }

    kt_case("a flush in the middle of a reduction starts the limiter over");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        int64_t limited;
        int32_t i;
        KT_NOT_NULL(ring);
        make_burst(1.4f);
        KT_EQ_INT(feed(ring, input, BURST_START + 1000), BURST_START + 1000);
        for (i = 0; i < BURST_START + 512; i += REQUEST)
            KT_EQ_INT(kprt_ring_render(ring, output, REQUEST, 1000 + i), REQUEST);
        limited = kprt_ring_limited_frames(ring);
        KT_CHECK(limited > 0);
        kprt_ring_flush(ring);

        for (i = 0; i < 4096 * CHANNELS; i++)
            input[i] = (float)(0.5 * sin(2.0 * PI * TONE_HZ * (double)i / RATE));
        KT_EQ_INT(feed(ring, input, 4096), 4096);
        for (i = 0; i < 4096; i += REQUEST)
            KT_EQ_INT(kprt_ring_render(ring, output + (size_t)i * CHANNELS, REQUEST, 50000 + i), REQUEST);
        KT_CHECK(same_bits(input, output, 4096));
        KT_EQ_I64(kprt_ring_limited_frames(ring), limited);
        kprt_ring_destroy(ring);
    }

    return kt_suite_end();
}
