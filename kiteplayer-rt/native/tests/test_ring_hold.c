/* The hold: a fade to silence before the device stops, and a fade back in after it starts (#486).
 *
 * A device stopped mid-wave drops from the wave's level to silence in one sample, and that step
 * is heard as a click; a start at full level is the same step the other way. The hold walks the
 * gain down over the real frames at the ramp's slope, consumes nothing once it reaches silence,
 * and, released, walks back up from silence. The properties:
 *
 *  - The fade takes exactly the ramp's frames from full scale, ends at an exact zero, and the
 *    frames after it stay in the ring. A hold that kept consuming would throw away audio under
 *    the silence and the resume would skip it.
 *  - Once silent, a held render hands over exact zeroes, consumes nothing, publishes no anchor,
 *    counts no underrun and answers every frame, because the silence is deliberate.
 *  - The silent flag is up only once the walk reaches zero, and the anchor then names the frame
 *    after the last one faded, which is where the pause holds the clock.
 *  - Released, the walk comes up from zero to the gain set while held, so a resume fades in and
 *    a volume change made while paused is in place from its first frame.
 *  - A ring held while it is dry, or before it ever rendered, is silent at once, so a pause on a
 *    starved device does not wait and a resume does not start at full level.
 */

#include "harness.h"
#include "kite_rt.h"

#include <stddef.h>
#include <stdint.h>

#define CHANNELS 2
#define RATE 48000
#define CAPACITY 4096
#define REQUEST 512

static float buffer[REQUEST * CHANNELS];

static int32_t feed_constant(kprt_ring *ring, int32_t frames, float value)
{
    kprt_ring_write_window window;
    int32_t granted;
    int32_t i;
    int32_t c;

    granted = kprt_ring_begin_write(ring, frames, &window);
    if (granted <= 0)
        return 0;
    for (i = 0; i < window.first_frames; i++)
        for (c = 0; c < CHANNELS; c++)
            window.first[(size_t)i * CHANNELS + (size_t)c] = value;
    for (i = 0; i < window.second_frames; i++)
        for (c = 0; c < CHANNELS; c++)
            window.second[(size_t)i * CHANNELS + (size_t)c] = value;
    KT_EQ_INT(kprt_ring_commit_write(ring, granted, 1, 0), KPRT_COMMIT_PUBLISHED);
    return granted;
}

int main(void)
{
    int32_t ramp = kprt_gain_ramp_frames(RATE);

    kt_suite_begin("ring_hold");

    kt_case("the fade walks to an exact zero over the ramp and leaves the rest in the ring");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        kprt_anchor anchor;
        int32_t i;
        int32_t fade;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 2048, 1.0f), 2048);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 100, 1000), 100);
        KT_EQ_INT(kprt_ring_is_silent(ring), 0);

        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_is_silent(ring), 0);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        /* Each frame one slope below the one before until the exact zero, which ends the fade. The
         * slope is a float, so the walk can land a hair above zero after the ramp's frames and
         * reach it one frame later; that is the same walk a volume change takes. */
        fade = 0;
        for (i = 0; i < REQUEST && fade == 0; i++) {
            if (i > 0)
                KT_CHECKF(buffer[(size_t)i * CHANNELS] < buffer[(size_t)(i - 1) * CHANNELS],
                          "the fade did not fall at frame %d", i);
            if (buffer[(size_t)i * CHANNELS] == 0.0f)
                fade = i + 1;
        }
        KT_CHECKF(fade == ramp || fade == ramp + 1, "the fade took %d frames, the ramp is %d", fade, ramp);
        KT_ALL_ZERO_F32(buffer + (size_t)(fade - 1) * CHANNELS, (size_t)(REQUEST - fade + 1) * CHANNELS);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), 100 + fade);
        KT_EQ_INT(kprt_ring_is_silent(ring), 1);
        KT_EQ_I64(kprt_ring_underruns(ring), 0);

        /* The anchor names the frame after the last one faded. */
        kprt_ring_anchor(ring, &anchor);
        KT_EQ_INT(anchor.valid, 1);
        KT_EQ_I64(anchor.pts_us, (int64_t)(100 + fade) * 1000000 / RATE);
        kt_detail("fade of %d frames over a ramp of %d, anchor %lld us", fade, ramp,
                  (long long)anchor.pts_us);
        kprt_ring_destroy(ring);
    }

    kt_case("held at silence the render consumes nothing, dates nothing and counts nothing");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        kprt_anchor before;
        kprt_anchor after;
        int64_t faded;
        int32_t round;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 2048, 0.5f), 2048);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 64, 1000), 64);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        kprt_ring_anchor(ring, &before);
        faded = kprt_ring_consumed_frames(ring);
        for (round = 0; round < 8; round++) {
            KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 3000 + round), REQUEST);
            KT_ALL_ZERO_F32(buffer, (size_t)REQUEST * CHANNELS);
        }
        kprt_ring_anchor(ring, &after);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), faded);
        KT_EQ_I64(after.pts_us, before.pts_us);
        KT_EQ_I64(after.audible_at_nanos, before.audible_at_nanos);
        KT_EQ_I64(kprt_ring_underruns(ring), 0);
        KT_EQ_INT(kprt_ring_is_silent(ring), 1);
        kprt_ring_destroy(ring);
    }

    kt_case("released, the sound walks back up from silence to the gain set while held");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        float slope = 1.0f / (float)ramp;
        int64_t faded;
        int32_t i;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 2048, 1.0f), 2048);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 64, 1000), 64);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        faded = kprt_ring_consumed_frames(ring);
        kprt_ring_set_gain(ring, 0.25f);
        kprt_ring_set_hold(ring, 0);
        KT_EQ_INT(kprt_ring_is_silent(ring), 0);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 3000), REQUEST);
        /* One slope up from silence, never above the new gain, and at it once the walk arrives. */
        KT_EQ_F32(buffer[0], slope);
        for (i = 0; i < REQUEST; i++)
            KT_CHECKF(buffer[(size_t)i * CHANNELS] <= 0.25f,
                      "frame %d rendered %.9g, over the gain set while held", i,
                      (double)buffer[(size_t)i * CHANNELS]);
        KT_EQ_F32(buffer[(size_t)(REQUEST - 1) * CHANNELS], 0.25f);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), faded + REQUEST);
        KT_EQ_INT(kprt_ring_is_silent(ring), 0);
        kprt_ring_destroy(ring);
    }

    kt_case("a hold on a dry ring is silent at once, and the resume still fades in");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        float slope = 1.0f / (float)ramp;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 64, 1.0f), 64);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 64, 1000), 64);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        KT_ALL_ZERO_F32(buffer, (size_t)REQUEST * CHANNELS);
        KT_EQ_INT(kprt_ring_is_silent(ring), 1);
        KT_EQ_I64(kprt_ring_underruns(ring), 0);

        KT_EQ_INT(feed_constant(ring, 1024, 1.0f), 1024);
        kprt_ring_set_hold(ring, 0);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 3000), REQUEST);
        KT_EQ_F32(buffer[0], slope);
        kprt_ring_destroy(ring);
    }

    kt_case("a ring that runs dry partway down the fade is silent from there");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        int32_t short_by = ramp / 2;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 64 + short_by, 1.0f), 64 + short_by);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 64, 1000), 64);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        KT_CHECKF(buffer[(size_t)(short_by - 1) * CHANNELS] > 0.0f, "the partial fade was not rendered");
        KT_ALL_ZERO_F32(buffer + (size_t)short_by * CHANNELS, (size_t)(REQUEST - short_by) * CHANNELS);
        KT_EQ_INT(kprt_ring_is_silent(ring), 1);
        KT_EQ_I64(kprt_ring_underruns(ring), 0);
        /* Audio arriving now waits for the release rather than stepping back in mid-fade. */
        KT_EQ_INT(feed_constant(ring, 512, 1.0f), 512);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 3000), REQUEST);
        KT_ALL_ZERO_F32(buffer, (size_t)REQUEST * CHANNELS);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), 64 + short_by);
        kprt_ring_destroy(ring);
    }

    kt_case("a hold before the first render is silent at once and the first play fades in");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        float slope = 1.0f / (float)ramp;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 1024, 1.0f), 1024);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 1000), REQUEST);
        KT_ALL_ZERO_F32(buffer, (size_t)REQUEST * CHANNELS);
        KT_EQ_I64(kprt_ring_consumed_frames(ring), 0);
        KT_EQ_INT(kprt_ring_is_silent(ring), 1);
        kprt_ring_set_hold(ring, 0);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        KT_EQ_F32(buffer[0], slope);
        kprt_ring_destroy(ring);
    }

    kt_case("a flush keeps the silence a fade left, so the new position fades in");
    {
        kprt_ring *ring = kprt_ring_create(RATE, CHANNELS, CAPACITY);
        float slope = 1.0f / (float)ramp;
        KT_NOT_NULL(ring);
        KT_EQ_INT(feed_constant(ring, 2048, 1.0f), 2048);
        KT_EQ_INT(kprt_ring_render(ring, buffer, 64, 1000), 64);
        kprt_ring_set_hold(ring, 1);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 2000), REQUEST);
        kprt_ring_flush(ring);
        KT_EQ_INT(feed_constant(ring, 1024, 1.0f), 1024);
        kprt_ring_set_hold(ring, 0);
        KT_EQ_INT(kprt_ring_render(ring, buffer, REQUEST, 3000), REQUEST);
        KT_EQ_F32(buffer[0], slope);
        kprt_ring_destroy(ring);
    }

    return kt_suite_end();
}
