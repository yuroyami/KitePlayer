/* Assertion and reporting API for the kiteplayer-rt C test suites.
 *
 * Contract, from plan section 15.3: every suite is table driven, prints one line per case, and
 * returns non-zero on the first failure.
 *
 * Deliberately a SEPARATE file from KiteFFmpeg's `native/kitecodec-c/tests/harness.h`, and with a
 * different symbol prefix (`kt_` here, `kc_` there). Plan section 15.2 B1.7 step 1 says the two C
 * build layers must not be shared across repositories: KiteFFmpeg is a public FFmpeg binding and
 * KitePlayer is a private player, and a shared harness would make the second a build dependency
 * of the first for no gain. The KiteFFmpeg harness also links `libavutil` to quiet FFmpeg's log,
 * which this library has no business depending on.
 *
 * Shape of a suite:
 *
 *     #include "harness.h"
 *     #include "kite_rt.h"
 *
 *     int main(void) {
 *         kt_suite_begin("test_ring_basic");
 *         for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
 *             kt_case("%s", rows[i].name);
 *             KT_EQ_INT(actual, rows[i].expected);
 *             kt_detail("frames=%d", actual);
 *         }
 *         return kt_suite_end();
 *     }
 *
 * Output, verdict first so a failing gate is greppable:
 *
 *     suite test_ring_basic
 *     ok       1  samples come back in order  [frames=256]
 *     FAIL     2  a read that wraps
 *            |  at tests/test_ring_basic.c:88
 *            |  KT_EQ_INT(got, 40): actual 24, expected 40
 *
 * A failing assertion prints and exits straight away. There is no continue-after-failure mode:
 * the first failure is the one with intact state around it.
 *
 * Compiled with -std=c11 -Wall -Wextra -Werror -Werror=vla, so a suite that warns does not build.
 */

#ifndef KITE_RT_TEST_HARNESS_H
#define KITE_RT_TEST_HARNESS_H

#include <stddef.h>
#include <stdint.h>

/* ---- Suite lifecycle ---- */

/* Opens the suite and probes the allocation interposer once, so its own allocation never lands
 * inside a case's measurement window. */
void kt_suite_begin(const char *suite);

/* Closes the open case, prints the summary and returns the process exit code. Non-zero when the
 * suite ran no cases at all, so an empty suite cannot pass by accident. */
int kt_suite_end(void);

/* Opens a case, closing and reporting the previous one as passed. printf format. */
void kt_case(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Appends measured values to the open case's line, in square brackets. */
void kt_detail(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Records that the open case could not assert one of its properties in this build variant, and
 * why. The case still passes, the reason lands on its line, and the summary reports how many
 * cases were partial. Use it for exactly one thing: a property this variant cannot observe, such
 * as allocation counting under a sanitizer that owns the allocator. Never to skip a property the
 * variant can observe.
 *
 * Turned into a hard failure by setting KPRT_REQUIRE_ALLOC_ACCOUNTING=1, which is what the
 * `interpose` gate step does, so "the interposer was not effective" can never be mistaken for
 * "there were no allocations". */
void kt_partial(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* An extra indented line inside the open case, for context one line cannot hold. */
void kt_note(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

/* Fails the open case and exits non-zero. Not usually called directly; use the macros. */
_Noreturn void kt_fail_at(const char *file, int line, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

/* ---- Assertions ---- */

#define KT_FAIL(...) kt_fail_at(__FILE__, __LINE__, __VA_ARGS__)

#define KT_CHECK(cond) \
    do { if (!(cond)) KT_FAIL("KT_CHECK(%s) is false", #cond); } while (0)

#define KT_CHECKF(cond, ...) \
    do { if (!(cond)) KT_FAIL(__VA_ARGS__); } while (0)

#define KT_EQ_INT(actual, expected) \
    do { \
        int kt_a_ = (actual); \
        int kt_e_ = (expected); \
        if (kt_a_ != kt_e_) \
            KT_FAIL("KT_EQ_INT(%s, %s): actual %d, expected %d", \
                    #actual, #expected, kt_a_, kt_e_); \
    } while (0)

#define KT_EQ_I64(actual, expected) \
    do { \
        int64_t kt_a_ = (int64_t)(actual); \
        int64_t kt_e_ = (int64_t)(expected); \
        if (kt_a_ != kt_e_) \
            KT_FAIL("KT_EQ_I64(%s, %s): actual %lld, expected %lld", \
                    #actual, #expected, (long long)kt_a_, (long long)kt_e_); \
    } while (0)

#define KT_EQ_F32(actual, expected) \
    do { \
        float kt_a_ = (actual); \
        float kt_e_ = (expected); \
        if (!(kt_a_ == kt_e_)) \
            KT_FAIL("KT_EQ_F32(%s, %s): actual %.9g, expected %.9g", \
                    #actual, #expected, (double)kt_a_, (double)kt_e_); \
    } while (0)

#define KT_NOT_NULL(value) \
    do { \
        if ((const void *)(value) == NULL) KT_FAIL("KT_NOT_NULL(%s): got NULL", #value); \
    } while (0)

#define KT_NULL(value) \
    do { \
        const void *kt_a_ = (const void *)(value); \
        if (kt_a_ != NULL) KT_FAIL("KT_NULL(%s): got %p", #value, kt_a_); \
    } while (0)

/* Every float in [start, start + count) must be exactly +0.0f. The exact-silence assertion of
 * "exact zeroes" and not "small values". */
#define KT_ALL_ZERO_F32(start, count) \
    do { \
        const float *kt_s_ = (start); \
        size_t kt_n_ = (size_t)(count); \
        long kt_at_ = kt_first_nonzero_f32(kt_s_, kt_n_); \
        if (kt_at_ >= 0) \
            KT_FAIL("KT_ALL_ZERO_F32(%s, %zu): float %ld is %.9g, not zero", \
                    #start, kt_n_, kt_at_, (double)kt_s_[kt_at_]); \
    } while (0)

/* Index of the first float that is not exactly zero, or -1. */
long kt_first_nonzero_f32(const float *start, size_t count);

/* ---- Allocation accounting ---- */

/* Counters maintained by tests/interpose_alloc.c through the Mach-O __DATA,__interpose section.
 *
 * `live_blocks` is the net count of allocations not yet freed, which already accounts for realloc
 * growing in place, realloc(NULL, n) allocating and realloc(p, 0) freeing.
 *
 * mmap and munmap are counted separately and kept out of live_blocks: libmalloc maps its own
 * regions, so those counts carry allocator noise rather than test-owned lifetime. They are
 * reported because plan section 15.2 B1.7's gate asks for zero mmap between create and destroy
 * too, and because a Kotlin/Native heap grows by mmap rather than by malloc, which is the exact
 * reason a malloc interposer is refused as evidence about Kotlin allocation. */
typedef struct {
    long long malloc_calls;
    long long calloc_calls;
    long long realloc_calls;
    long long posix_memalign_calls;
    long long aligned_alloc_calls;
    long long valloc_calls;
    long long free_calls;
    long long mmap_calls;
    long long munmap_calls;
    long long live_blocks;
} kt_alloc_counts;

/* 1 when the interposer actually observes this process's allocations.
 *
 * Measured on this machine: 1 in the `plain` variant, 0 under `asan` and `tsan`, because both
 * sanitizer runtimes replace the allocator before dyld reaches the interpose section. So the
 * allocation evidence comes from the plain run and the sanitizer runs contribute their own
 * findings instead. LeakSanitizer is not an option here at all. */
int kt_alloc_active(void);

void kt_alloc_snapshot(kt_alloc_counts *out);

/* Deltas since `before` was taken. */
long long kt_alloc_live_delta(const kt_alloc_counts *before);
long long kt_alloc_new_delta(const kt_alloc_counts *before);
long long kt_alloc_free_delta(const kt_alloc_counts *before);
long long kt_alloc_mmap_delta(const kt_alloc_counts *before);

/* Asserts that the window starting at `before` performed no allocating call, no free and no mmap
 * at all. This is the real-time claim, and it is stricter than "balanced": a malloc paired with a
 * free is still a malloc on a real-time thread. */
#define KT_ALLOC_SILENT(before) \
    do { \
        if (!kt_alloc_active()) { \
            kt_partial("allocation counting not observable in this variant"); \
        } else { \
            long long kt_new_ = kt_alloc_new_delta(before); \
            long long kt_freed_ = kt_alloc_free_delta(before); \
            long long kt_mapped_ = kt_alloc_mmap_delta(before); \
            kt_detail("new=%lld freed=%lld mmap=%lld", kt_new_, kt_freed_, kt_mapped_); \
            if (kt_new_ != 0 || kt_freed_ != 0 || kt_mapped_ != 0) \
                KT_FAIL("KT_ALLOC_SILENT(%s): %lld allocations, %lld frees, %lld mmaps", \
                        #before, kt_new_, kt_freed_, kt_mapped_); \
        } \
    } while (0)

/* Asserts the window allocated exactly `news` times and freed exactly `frees` times. Used for
 * create and destroy, whose contract is one malloc and one free. */
#define KT_ALLOC_EXACTLY(before, news, frees) \
    do { \
        if (!kt_alloc_active()) { \
            kt_partial("allocation counting not observable in this variant"); \
        } else { \
            long long kt_new_ = kt_alloc_new_delta(before); \
            long long kt_freed_ = kt_alloc_free_delta(before); \
            kt_detail("new=%lld freed=%lld", kt_new_, kt_freed_); \
            if (kt_new_ != (long long)(news) || kt_freed_ != (long long)(frees)) \
                KT_FAIL("KT_ALLOC_EXACTLY(%s, %s, %s): actual new=%lld freed=%lld", \
                        #before, #news, #frees, kt_new_, kt_freed_); \
        } \
    } while (0)

#endif /* KITE_RT_TEST_HARNESS_H */
