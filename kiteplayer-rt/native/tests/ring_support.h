/* Shared helpers for the ring test suites.
 *
 * `static inline` and in a header on purpose: an unused `static inline` is not a warning under
 * -Wall -Wextra, while an unused plain `static` is, and these are needed by four of the six
 * suites and not by all of them.
 *
 * The feed helper is the C counterpart of the Kotlin ring's `write(array, offset, frames, pts)`.
 * It exists because the C writer is reservation shaped, so what was one call is three, and
 * repeating those three in every case would bury what each case is actually about.
 */

#ifndef KITE_RT_TEST_RING_SUPPORT_H
#define KITE_RT_TEST_RING_SUPPORT_H

#include "harness.h"
#include "kite_rt.h"

#include <stddef.h>
#include <stdint.h>

/* Every frame is identifiable by its value, so a wrap mistake or an off-by-one shows up as a
 * value rather than as a silence. Frame n of the stream carries the value n in every channel. */
static inline float kprt_test_frame_value(int64_t frame)
{
    return (float)frame;
}

/* Writes `frames` frames of ramp starting at stream position `from`, and commits them.
 *
 * @param has_pts non-zero to date the first frame at `pts_us`.
 * @return frames published, which is 0 when the ring was full or a segment was needed and none
 *         was free. Matches what `KotlinAudioRing.write` returns in both of those cases, which is
 *         what makes the two comparable at the call site.
 */
static inline int32_t kprt_test_feed(kprt_ring *ring, int32_t frames, int64_t from,
                                     int32_t has_pts, int64_t pts_us)
{
    kprt_ring_write_window window;
    int32_t granted;
    int32_t channels = kprt_ring_channels(ring);
    int32_t i;
    int32_t c;
    int32_t status;

    granted = kprt_ring_begin_write(ring, frames, &window);
    if (granted <= 0)
        return 0;

    for (i = 0; i < window.first_frames; i++) {
        float value = kprt_test_frame_value(from + i);
        for (c = 0; c < channels; c++)
            window.first[(size_t)i * (size_t)channels + (size_t)c] = value;
    }
    for (i = 0; i < window.second_frames; i++) {
        float value = kprt_test_frame_value(from + window.first_frames + i);
        for (c = 0; c < channels; c++)
            window.second[(size_t)i * (size_t)channels + (size_t)c] = value;
    }

    status = kprt_ring_commit_write(ring, granted, has_pts, pts_us);
    if (status == KPRT_COMMIT_NEEDS_SEGMENT)
        return 0;
    KT_CHECKF(status == KPRT_COMMIT_PUBLISHED,
              "kprt_ring_commit_write returned %d, which is neither published nor needs-segment",
              status);
    return granted;
}

/* A media position given in frames, as the timestamp a decoder would carry for it. Uses the
 * library's own rescale, because a test that reimplemented it would be testing itself. */
static inline int64_t kprt_test_media_us(int64_t frames, int32_t sample_rate)
{
    return kprt_frames_to_micros(frames, sample_rate);
}

#endif /* KITE_RT_TEST_RING_SUPPORT_H */
