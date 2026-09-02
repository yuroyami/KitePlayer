/* Gain above unity, and the soft limiter that makes it safe.
 *
 * The ring's gain used to be clamped to unity on the way in, so a caller asking for 2.0 got 1.0 and
 * a player could not offer a boost at all. Letting the multiply exceed unity is what mpv and VLC do
 * and it clips hard on anything already loud, which is the worst sound a player can make. So the
 * boost lands with a saturator instead: identity below a knee, folding smoothly above it, never
 * reaching full scale however hard it is driven.
 *
 * The properties, each with its own reason for existing:
 *
 *  - At or below unity NOTHING changes, bit for bit. The saturator is not in the path at all, which
 *    is what lets every existing measurement and every golden stay valid. This is the first case
 *    because it is the one a regression would be quietest about.
 *  - Above unity the output is the saturator's exact value, checked against the arithmetic written
 *    out longhand here rather than against the implementation's own helper. A test that called
 *    `kprt_soft_clip` to compute its expectation would agree with any curve, including a wrong one.
 *  - However hard it is driven, no output sample reaches full scale. That is the whole promise: a
 *    boosted passage folds instead of clipping, so it can never produce the square edges that make
 *    a hard clip audible.
 *  - The fold is odd-symmetric, so a waveform is not given a DC offset by being boosted.
 *  - The walk from one gain to another still clips per frame, so the frames of a ramp that are
 *    below unity are untouched while the ones above it are folded. The ramp is where a per-buffer
 *    check would pass and be wrong.
 *
 * `kprt_soft_clip` is deliberately not exported: the curve is an implementation detail of the
 * render path and the only thing anyone outside may depend on is the shape checked here.
 */

#include "harness.h"
#include "kite_rt.h"
#include "ring_support.h"

#include <stddef.h>
#include <stdint.h>

#define CHANNELS 2
#define RATE 48000
#define CAPACITY 4096
#define REQUEST 1024

static float buffer[REQUEST * CHANNELS];

/* The saturator's arithmetic, written out longhand and independently of the implementation.
 * Deliberately a copy: this is the expectation, and an expectation that calls the thing it checks
 * proves only that the code equals itself. */
static float expected_clip(float x)
{
    const float knee = 0.75f;
    float mag = x < 0.0f ? -x : x;
    float excess;
    float folded;
    if (mag <= knee)
        return x;
    excess = (mag - knee) / (1.0f - knee);
    folded = excess / (1.0f + excess);
    folded = knee + (1.0f - knee) * folded;
    return x < 0.0f ? -folded : folded;
}

/* Writes `frames` frames whose every sample is `value`, and commits them. The ramp helper in
 * ring_support.h dates each frame by its own index, which is right for spotting a wrap mistake and
 * useless here: this suite needs to know the exact sample value going in so it can predict the one
 * coming out. */
static int32_t feed_constant(kprt_ring *ring, int32_t frames, float value)
{
    kprt_ring_write_window window;
    int32_t granted;
    int32_t channels = kprt_ring_channels(ring);
    int32_t i;
    int32_t c;

    granted = kprt_ring_begin_write(ring, frames, &window);
    if (granted <= 0)
        return 0;
    for (i = 0; i < window.first_frames; i++)
        for (c = 0; c < channels; c++)
            window.first[(size_t)i * (size_t)channels + (size_t)c] = value;
    for (i = 0; i < window.second_frames; i++)
        for (c = 0; c < channels; c++)
            window.second[(size_t)i * (size_t)channels + (size_t)c] = value;
    KT_EQ_INT(kprt_ring_commit_write(ring, granted, 1, 0), KPRT_COMMIT_PUBLISHED);
    return granted;
}

/* A ring whose gain is set BEFORE its first render, which snaps rather than walking, so the very
 * first buffer is already at the steady state the case is about. */
static kprt_ring *ring_at_gain(float gain)
{
    kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
    KT_NOT_NULL(ring);
    kprt_ring_set_gain(ring, gain);
    return ring;
}

int main(void)
{
    kt_suite_begin("ring_gain_boost");

    kt_case("at unity the buffer is untouched, bit for bit");
    {
        kprt_ring *ring = ring_at_gain(1.0f);
        int32_t i;
        KT_EQ_INT(feed_constant(ring, 256, 0.9f), 256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 256);
        /* 0.9 is above the knee. It still comes out untouched, because the saturator engages on the
         * GAIN being above unity and not on the sample being loud: a file that was already hot must
         * sound exactly as it did before this feature existed. */
        for (i = 0; i < 256 * CHANNELS; i++)
            KT_EQ_F32(buffer[i], 0.9f);
        kprt_ring_destroy(ring);
    }

    kt_case("below unity is the plain multiply it always was");
    {
        /* The values are chosen so the PRODUCT lands above the knee while the gain stays below
         * unity: 0.95 * 0.9 = 0.855, and the knee is 0.75. A saturator that keyed on the sample
         * being loud instead of on the gain being a boost would fold this to about 0.798, so this
         * case is what pins the attenuating path to the arithmetic it has always had. A product
         * below the knee could not tell the two apart. */
        kprt_ring *ring = ring_at_gain(0.9f);
        int32_t i;
        KT_EQ_INT(feed_constant(ring, 256, 0.95f), 256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 256);
        for (i = 0; i < 256 * CHANNELS; i++)
            KT_EQ_F32(buffer[i], 0.95f * 0.9f);
        kt_detail("0.95 at gain 0.9 = %.9g, unfolded", (double)buffer[0]);
        kprt_ring_destroy(ring);
    }

    kt_case("a gain above unity is accepted rather than clamped");
    {
        /* The old setter clamped to 1.0f, so this case is the one that fails first on a tree
         * without the boost: the samples come back at their unity value. */
        kprt_ring *ring = ring_at_gain(2.0f);
        int32_t i;
        float expected;
        KT_EQ_INT(feed_constant(ring, 256, 0.25f), 256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 256);
        /* 0.25 * 2.0 = 0.5, which is below the knee, so the boost is exact and the saturator is
         * not in the way of ordinary quiet material. */
        expected = 0.5f;
        for (i = 0; i < 256 * CHANNELS; i++)
            KT_EQ_F32(buffer[i], expected);
        kt_detail("0.25 at gain 2.0 = %.9g", (double)buffer[0]);
        kprt_ring_destroy(ring);
    }

    kt_case("above the knee the fold is the documented curve");
    {
        kprt_ring *ring = ring_at_gain(2.0f);
        int32_t i;
        /* 1.0 * 2.0 = 2.0. excess = (2 - 0.75) / 0.25 = 5, folded = 0.75 + 0.25 * (5 / 6). */
        float expected = 0.75f + 0.25f * (5.0f / 6.0f);
        KT_EQ_INT(feed_constant(ring, 256, 1.0f), 256);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 256, 0), 256);
        for (i = 0; i < 256 * CHANNELS; i++)
            KT_EQ_F32(buffer[i], expected);
        KT_EQ_F32(buffer[0], expected_clip(2.0f));
        kt_detail("full scale at gain 2.0 = %.9g", (double)buffer[0]);
        kprt_ring_destroy(ring);
    }

    kt_case("nothing reaches full scale however hard it is driven");
    {
        static const float inputs[] = { 0.8f, 1.0f, 4.0f, 100.0f, 100000.0f };
        size_t k;
        for (k = 0; k < sizeof(inputs) / sizeof(inputs[0]); k++) {
            kprt_ring *ring = ring_at_gain(2.0f);
            int32_t i;
            KT_EQ_INT(feed_constant(ring, 128, inputs[k]), 128);
            KT_EQ_INT(kprt_ring_render(ring, buffer, 128, 0), 128);
            for (i = 0; i < 128 * CHANNELS; i++) {
                float actual = buffer[i];
                KT_CHECKF(actual < 1.0f && actual > -1.0f,
                          "input %.9g at gain 2.0 rendered %.9g, which is not inside full scale",
                          (double)inputs[k], (double)actual);
                KT_EQ_F32(actual, expected_clip(inputs[k] * 2.0f));
            }
            kprt_ring_destroy(ring);
        }
    }

    kt_case("the fold is odd-symmetric, so a boost adds no DC");
    {
        kprt_ring *positive = ring_at_gain(2.0f);
        kprt_ring *negative = ring_at_gain(2.0f);
        float got_positive;
        float got_negative;
        KT_EQ_INT(feed_constant(positive, 64, 0.8f), 64);
        KT_EQ_INT(kprt_ring_render(positive, buffer, 64, 0), 64);
        got_positive = buffer[0];
        KT_EQ_INT(feed_constant(negative, 64, -0.8f), 64);
        KT_EQ_INT(kprt_ring_render(negative, buffer, 64, 0), 64);
        got_negative = buffer[0];
        KT_EQ_F32(got_positive, -got_negative);
        kprt_ring_destroy(positive);
        kprt_ring_destroy(negative);
    }

    kt_case("a walk down through unity folds only while it is above it");
    {
        /* One buffer that crosses unity, which is the only shape that can tell a per-frame
         * saturator from a per-buffer one. Walking UP from unity cannot do it: the first slope step
         * already puts the gain above 1.0, so every frame of that walk is boosted and a per-buffer
         * decision would look identical. Walking DOWN from a boost to below unity puts both halves
         * in one render.
         *
         * The two ends are what carry the proof. The first frame is folded, so a saturator keyed on
         * the TARGET gain (0.5, not a boost) would leave it at 1.8 and fail. The frames after the
         * walk arrives are the exact plain multiply, so a saturator keyed on the STARTING gain, or
         * one applied to the whole buffer, would fold them and fail too. */
        kprt_ring *ring = ring_at_gain(2.0f);
        int32_t ramp_frames = kprt_gain_ramp_frames(RATE);
        int32_t real;
        int32_t i;
        int32_t arrived = 0;
        /* 0.95 into a target of 0.9 lands at 0.855, above the knee, for the same reason case 2
         * uses those numbers: an unconditional fold would be invisible on a quieter pair. */
        KT_EQ_INT(feed_constant(ring, 1024, 0.95f), 1024);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 8, 0), 8);
        KT_EQ_F32(buffer[0], expected_clip(0.95f * 2.0f));
        kprt_ring_set_gain(ring, 0.9f);
        real = kprt_ring_render(ring, buffer, REQUEST - 8, 0);
        KT_CHECKF(real > ramp_frames,
                  "the render must span the whole %d frame ramp to see both halves, got %d",
                  ramp_frames, real);
        /* Nothing anywhere in the walk leaves full scale, including the frames still boosted. */
        for (i = 0; i < real; i++) {
            float actual = buffer[(size_t)i * CHANNELS];
            KT_CHECKF(actual < 1.0f && actual > -1.0f,
                      "walk frame %d rendered %.9g, outside full scale", i, (double)actual);
            if (actual == 0.95f * 0.9f)
                arrived++;
        }
        /* The walk is 240 frames of a 1016 frame render, so the tail is long and exact. Every one
         * of those frames is a sub-unity gain over a product above the knee, which is the pair a
         * per-buffer or unconditional saturator gets wrong. */
        KT_CHECKF(arrived > 0,
                  "no frame after the walk arrived was the plain multiply 0.855");
        KT_EQ_F32(buffer[(size_t)(real - 1) * CHANNELS], 0.95f * 0.9f);
        /* And the first frame of the walk is still folded, one slope step below where it started. */
        KT_CHECKF(buffer[0] != 0.95f * 0.9f,
                  "the first frame of the walk was already at the target, so nothing was walked");
        kt_detail("walk spans %d frames, %d at the arrived multiply, ramp=%d",
                  real, arrived, ramp_frames);
        kprt_ring_destroy(ring);
    }

    return kt_suite_end();
}
