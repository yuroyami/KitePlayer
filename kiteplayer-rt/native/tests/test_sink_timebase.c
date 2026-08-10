/* Host ticks to nanoseconds, checked against CoreAudio's own conversion rather than trusted.
 *
 * WHY THIS SUITE EXISTS. Plan section 15.2 B1.8 step 1 requires the callback to convert host ticks
 * "with a `mach_timebase_info` cached at create rather than calling `AudioConvertHostTimeToNanos`
 * inside the callback", and `scripts/render-audit.sh` enforces the second half by forbidding that
 * symbol in the render unit. That leaves an obligation nobody can discharge by reading the code: the
 * cached arithmetic has to produce the same number the framework function would have. So this suite
 * uses `AudioConvertHostTimeToNanos` as the reference implementation and compares
 * `kprt_sink_ticks_to_nanos` against it over a table of vectors, including the ones where a naive
 * `ticks * numer / denom` overflows.
 *
 * It also settles the one substitution this library makes in the other direction. The plan names
 * `AudioGetCurrentHostTime` as the source of the fallback host time; the callback calls
 * `mach_absolute_time` instead, because on macOS the first is documented to return the second and
 * keeping the framework off the real-time path leaves the render unit's undefined symbol list at libc
 * plus nothing. "Documented" is not evidence, so the case below measures it: a `mach_absolute_time`
 * reading must fall between two `AudioGetCurrentHostTime` readings taken around it.
 *
 * No device is opened here and no sound is made. This is arithmetic and two clock reads.
 */

#include "harness.h"
#include "kite_rt.h"
#include "kite_rt_sink_internal.h"

#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#if defined(__APPLE__)
#include <CoreAudio/HostTime.h>
#include <mach/mach_time.h>
#endif

static kprt_sink sink;

static void configure(uint32_t numer, uint32_t denom)
{
    memset((void *)&sink, 0, sizeof(sink));
    sink.sample_rate = 48000;
    sink.channels = 2;
    sink.timebase_numer = numer;
    sink.timebase_denom = denom;
    atomic_store(&sink.ring, (kprt_ring *)NULL);
}

/* The unbounded answer, computed in 128 bit so the reference has no overflow of its own. Used only
 * as an oracle in this test; nothing in the library needs 128 bit arithmetic. */
static int64_t reference_nanos(uint64_t ticks, uint32_t numer, uint32_t denom)
{
    unsigned __int128 wide = (unsigned __int128)ticks * (unsigned __int128)numer;
    return (int64_t)(uint64_t)(wide / (unsigned __int128)denom);
}

int main(void)
{
    kt_suite_begin("test_sink_timebase");

#if !defined(__APPLE__)
    kt_case("the host timebase suite has nothing to compare against off Apple platforms");
    kt_partial("AudioConvertHostTimeToNanos exists only on Apple platforms");
    return kt_suite_end();
#else

    /* ---- what this machine actually reports ---- */
    kt_case("mach_timebase_info reports a usable ratio on this machine");
    {
        mach_timebase_info_data_t timebase;
        memset(&timebase, 0, sizeof(timebase));
        KT_CHECKF(mach_timebase_info(&timebase) == KERN_SUCCESS, "mach_timebase_info failed");
        KT_CHECKF(timebase.numer != 0 && timebase.denom != 0,
                  "a zero in the timebase would make every conversion zero: %u/%u",
                  timebase.numer, timebase.denom);
        kt_detail("numer=%u denom=%u", timebase.numer, timebase.denom);
        if (timebase.numer == timebase.denom)
            kt_note("host ticks are nanoseconds on this machine, so the conversion is the identity");
    }

    /* ---- the identity path, which is what Apple silicon takes ---- */
    kt_case("a one to one timebase converts a tick to itself, exactly");
    {
        static const uint64_t vectors[] = {
            0ULL, 1ULL, 999ULL, 48000ULL, 1000000000ULL,
            1000000000000ULL, 9223372036854775807ULL,
        };
        size_t i;
        configure(1, 1);
        for (i = 0; i < sizeof(vectors) / sizeof(vectors[0]); i++)
            KT_EQ_I64(kprt_sink_ticks_to_nanos(&sink, vectors[i]), (int64_t)vectors[i]);
        kt_detail("%zu vectors, largest %llu",
                  sizeof(vectors) / sizeof(vectors[0]), (unsigned long long)vectors[6]);
    }

    /* ---- the ratio this machine reports, and the point where the naive product breaks ---- */
    kt_case("a 125 over 3 timebase agrees with 128 bit arithmetic, including past the naive overflow");
    {
        /* 125/3 is what THIS machine reports, measured by the case above: host ticks are 24 MHz ticks
         * and one tick is 41 and two thirds nanoseconds. That was worth measuring rather than
         * assuming, because an earlier draft of this file asserted the timebase was 1 over 1 on Apple
         * silicon and the assertion above failed on the first run.
         *
         * How reachable the naive overflow is, stated honestly rather than dramatically: `ticks * 125`
         * leaves 64 bits above 147,573,952,589,676,411 ticks, which at 24 MHz is about 195 years of
         * uptime. So on this timebase the split form removes a class rather than fixes a bug that was
         * about to happen. It is still the right form, for two reasons that do not depend on that
         * number: the file already rescales frames the same way for a hazard that IS reachable
         * (register item B1-18), so there is one rule here instead of two; and a timebase whose numer
         * is large, which some Apple hardware has reported, reaches the same overflow in seconds. */
        static const uint64_t vectors[] = {
            0ULL, 1ULL, 2ULL, 3ULL, 24000000ULL, 86400ULL * 24000000ULL,
            147573952589676411ULL, 200000000000000000ULL,
        };
        const size_t count = sizeof(vectors) / sizeof(vectors[0]);
        const size_t past_overflow = count - 1;
        size_t i;
        configure(125, 3);
        for (i = 0; i < count; i++) {
            int64_t actual = kprt_sink_ticks_to_nanos(&sink, vectors[i]);
            int64_t expected = reference_nanos(vectors[i], 125, 3);
            KT_CHECKF(actual == expected,
                      "tick %llu converted to %lld, 128 bit arithmetic says %lld",
                      (unsigned long long)vectors[i], (long long)actual, (long long)expected);
        }
        /* The last vector is chosen so the naive product really does wrap while the true answer still
         * fits in a signed 64 bit nanosecond count. Without that second property the two forms would
         * agree on their own wrapped garbage and the case would prove nothing. */
        {
            uint64_t naive = vectors[past_overflow] * 125ULL / 3ULL;
            int64_t split = kprt_sink_ticks_to_nanos(&sink, vectors[past_overflow]);
            kt_detail("vector %llu: split=%lld naive=%llu",
                      (unsigned long long)vectors[past_overflow],
                      (long long)split, (unsigned long long)naive);
            KT_CHECKF((int64_t)naive != split,
                      "the naive product did not overflow on this vector, so it proves nothing");
        }
    }

    /* ---- the framework's own conversion, as the oracle ---- */
    kt_case("kprt_sink_ticks_to_nanos agrees with AudioConvertHostTimeToNanos on real host times");
    {
        mach_timebase_info_data_t timebase;
        uint64_t vectors[8];
        size_t i;

        memset(&timebase, 0, sizeof(timebase));
        KT_CHECKF(mach_timebase_info(&timebase) == KERN_SUCCESS, "mach_timebase_info failed");
        configure(timebase.numer, timebase.denom);

        vectors[0] = 0ULL;
        vectors[1] = 1ULL;
        vectors[2] = 1000ULL;
        vectors[3] = 24000000ULL;
        vectors[4] = AudioGetCurrentHostTime();
        vectors[5] = vectors[4] + 512ULL * 20833ULL;
        vectors[6] = 1000000000000ULL;
        vectors[7] = 100000000000000ULL;

        for (i = 0; i < sizeof(vectors) / sizeof(vectors[0]); i++) {
            int64_t ours = kprt_sink_ticks_to_nanos(&sink, vectors[i]);
            int64_t theirs = (int64_t)AudioConvertHostTimeToNanos(vectors[i]);
            KT_CHECKF(ours == theirs,
                      "tick %llu: this library says %lld, AudioConvertHostTimeToNanos says %lld",
                      (unsigned long long)vectors[i], (long long)ours, (long long)theirs);
        }
        kt_detail("%zu vectors agree, including a live host time %llu",
                  sizeof(vectors) / sizeof(vectors[0]), (unsigned long long)vectors[4]);
    }

    /* ---- the substitution the callback makes ---- */
    kt_case("mach_absolute_time and AudioGetCurrentHostTime read the same clock");
    {
        int i;
        for (i = 0; i < 1000; i++) {
            uint64_t before = AudioGetCurrentHostTime();
            uint64_t mach = mach_absolute_time();
            uint64_t after = AudioGetCurrentHostTime();
            KT_CHECKF(mach >= before && mach <= after,
                      "reading %d: mach_absolute_time %llu did not fall between two host times "
                      "%llu and %llu, so the two are not the same clock",
                      i, (unsigned long long)mach,
                      (unsigned long long)before, (unsigned long long)after);
        }
        kt_detail("1000 interleaved readings, all ordered");
    }

    /* ---- a sink with no timebase at all must not silently produce zero ---- */
    kt_case("a zero timebase falls back to the identity rather than to zero");
    {
        configure(0, 0);
        KT_EQ_I64(kprt_sink_ticks_to_nanos(&sink, 123456789ULL), 123456789LL);
        KT_EQ_I64(kprt_sink_ticks_to_nanos(NULL, 123456789ULL), 0);
        kt_detail("identity on a zero timebase, and zero for a NULL sink");
    }

    return kt_suite_end();
#endif
}
