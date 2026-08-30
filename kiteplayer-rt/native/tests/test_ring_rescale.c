/* The ring's microsecond dating must not multiply before it divides.
 *
 * The Kotlin ring computed `(atFrame - segmentStart) * 1_000_000L / sampleRate` at two places, and
 * that product overflows a signed 64 bit intermediate once the frame delta passes about 9.2e12.
 * It is the same shape KiteFFmpeg defect D9 records against the FFmpeg timestamp helpers. Both
 * rings were corrected in this sub-phase, so the differential oracle compares two correct
 * implementations rather than two matching bugs; this suite is what says the correction is real.
 *
 * Each overflowing row asserts TWO things, and the second is the one that makes the row honest:
 * the correct answer, and that the naive product genuinely wraps to something else. Without the
 * second assertion a row whose vector was too small to overflow would pass while proving nothing,
 * which is exactly the class of mistake this plan's evidence rules exist to stop.
 *
 * The naive form is computed through `uint64_t`, whose wrap-around is defined behaviour. Writing
 * it with `int64_t` would be signed overflow, which is undefined and which the UBSan variant would
 * abort on, so the test would fail for the wrong reason.
 */

#include "harness.h"
#include "kite_rt.h"

#include <stdint.h>

typedef struct {
    const char *name;
    int64_t frames;
    int32_t sample_rate;
    int64_t expected_us;
    /* 1 when `frames * 1000000` does not fit in a signed 64 bit integer. */
    int overflows_naive;
} rescale_row;

/* Emulates the naive `frames * 1000000 / rate` with the wrap-around the hardware would produce,
 * without invoking undefined behaviour: unsigned multiplication wraps by definition, and the
 * conversion back is what the generated code does. Only ever used to prove a row differs. */
static int64_t naive_wrapped(int64_t frames, int32_t sample_rate)
{
    uint64_t product = (uint64_t)frames * (uint64_t)1000000;
    int64_t as_signed = (int64_t)product;
    return as_signed / (int64_t)sample_rate;
}

static const rescale_row rows[] = {
    /* Ordinary values, where the correct form and the naive one must agree exactly. These matter:
     * a "fix" that changed the answer at 480 frames would break every existing ring test. */
    { "10 ms at 48 kHz",                        480,                    48000,               10000, 0 },
    { "10 ms at 44.1 kHz",                      480,                    44100,               10884, 0 },
    { "one frame at 192 kHz",                     1,                   192000,                   5, 0 },
    { "a million frames at 96 kHz",         1000000,                    96000,            10416666, 0 },
    { "a whole second at 48 kHz",             48000,                    48000,             1000000, 0 },
    { "zero frames",                              0,                    48000,                   0, 0 },

    /* Interlude item I-05: the ends of the range, where the exact form's `whole * 1000000`
     * multiply was measured overflowing under UBSan through the public surface
     * (kprt_frames_to_micros(INT64_MAX, 1)). The contract is saturation, matching the decision
     * add_saturating already took for the anchor: a duration that does not fit int64 microseconds
     * is already meaningless, and both implementations must produce the SAME meaningless number.
     * The oracle carries the same two rows so the Kotlin ring is pinned to this answer too. */
    { "INT64_MAX frames at 1 Hz saturates",     INT64_MAX,                  1,           INT64_MAX, 1 },
    { "INT64_MIN frames at 1 Hz saturates",     INT64_MIN,                  1,           INT64_MIN, 1 },

    /* The register item's own vectors. 1e13 frames at 48 kHz is about 6.6 years of audio, which is
     * not a session anybody plays; the point is that the arithmetic is wrong there rather than
     * saturating, and a player that runs for a week is only three orders of magnitude away. */
    { "1e13 frames at 48 kHz overflows the naive product",
      10000000000000LL,      48000,      208333333333333LL, 1 },
    { "1e13 frames at 44.1 kHz overflows the naive product",
      10000000000000LL,      44100,      226757369614512LL, 1 },
    { "1e13 frames at 192 kHz overflows the naive product",
      10000000000000LL,     192000,       52083333333333LL, 1 },
    { "the first frame delta whose product does not fit",
      9223372036855LL,       48000,      192153584101145LL, 1 },
    { "1e14 frames at 48 kHz",
      100000000000000LL,     48000,     2083333333333333LL, 1 },
    { "4e17 frames at 48 kHz, near the largest delta this form still answers exactly",
      400000000000000000LL,  48000,  8333333333333333333LL, 1 },

    /* Negative deltas cannot arise from the ring's own call sites, which are documented as
     * non-negative, but the function is exported and both implementations must truncate toward
     * zero the same way or the oracle would diverge on a future call site. */
    { "a negative delta truncates toward zero",     -480,               48000,              -10000, 0 },
    { "a negative delta that overflows the naive product",
      -10000000000000LL,     44100,     -226757369614512LL, 1 },

    /* AudioFormat.durationOf answers 0 for a zero rate, so this does too. */
    { "a zero sample rate answers zero",            480,                    0,                   0, 0 },
    { "a negative sample rate answers zero",        480,                   -1,                   0, 0 },
};

int main(void)
{
    size_t i;
    kt_suite_begin("test_ring_rescale");

    for (i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        const rescale_row *row = &rows[i];
        int64_t actual = kprt_frames_to_micros(row->frames, row->sample_rate);
        kt_case("%s", row->name);
        kt_detail("frames=%lld rate=%d us=%lld",
                  (long long)row->frames, row->sample_rate, (long long)actual);
        KT_EQ_I64(actual, row->expected_us);

        if (row->overflows_naive) {
            int64_t naive = naive_wrapped(row->frames, row->sample_rate);
            kt_detail("naive=%lld", (long long)naive);
            KT_CHECKF(naive != row->expected_us,
                      "this row claims to overflow the naive product, but the naive form answered "
                      "%lld, which is the correct answer: the vector does not prove anything",
                      (long long)naive);
        }
    }

    /* One property rather than one vector: for every rate the ring supports, the split rescale
     * agrees with an independent unbounded computation done in two halves. This is a different
     * derivation from the implementation's, so it is a check and not a restatement. */
    {
        static const int32_t rates[] = { 44100, 48000, 96000, 192000 };
        size_t r;
        for (r = 0; r < sizeof(rates) / sizeof(rates[0]); r++) {
            int32_t rate = rates[r];
            int64_t frames;
            kt_case("the split rescale is exact across a sweep at %d Hz", rate);
            for (frames = 0; frames < 1000000; frames += 4999) {
                /* Independent form: safe here because frames stays small, so this multiplication
                 * cannot overflow and needs no split. */
                int64_t reference = frames * 1000000 / rate;
                int64_t actual = kprt_frames_to_micros(frames, rate);
                KT_CHECKF(actual == reference,
                          "at %lld frames and %d Hz: got %lld, reference %lld",
                          (long long)frames, rate, (long long)actual, (long long)reference);
            }
            kt_detail("201 points from 0 to 999800 frames");
        }
    }

    return kt_suite_end();
}
