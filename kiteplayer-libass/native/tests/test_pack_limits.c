/*
 * SEC-1: a subtitle must not be able to overflow the heap through the packed-buffer arithmetic.
 *
 * `libass_jni.c` accumulated the buffer size with no check. On armeabi-v7a and x86 `size_t` is 32
 * bit, so a large enough cue wrapped the total, `malloc` under-allocated, and the fill loop wrote
 * the full un-wrapped amount past the end. Dimensions come from the subtitle file, which is
 * untrusted input.
 *
 * This suite compiles the SHIPPED arithmetic out of `libass_pack_limits.h`, so it tests the code
 * that runs rather than a copy of it. It needs neither jni.h nor libass to do that, which is the
 * whole reason the arithmetic lives in a header.
 *
 * Every overflowing row asserts TWO things, the same discipline test_ring_rescale uses: that the
 * guard refuses, AND that the unguarded form genuinely wrapped. Without the second assertion a row
 * whose numbers were too small to overflow would pass while proving nothing.
 *
 * The wrap is emulated through `uint32_t`, whose overflow is defined behaviour. Writing it as a
 * real 32 bit `size_t` is impossible on a 64 bit host, and writing it signed would be undefined
 * behaviour that the UBSan variant aborts on, so the suite would fail for the wrong reason.
 */

#include "harness.h"
#include "libass_pack_limits.h"

#include <stdint.h>

/** What `(size_t) w * (size_t) h * 4` did on a 32 bit ABI, with the wrap made defined. */
static uint32_t wrapped_region_bytes_32(int width, int height) {
    return (uint32_t) width * (uint32_t) height * 4u;
}

typedef struct {
    const char *name;
    int width;
    int height;
    /* 0 when the guard must refuse. */
    size_t expected;
    /* 1 when the old 32 bit arithmetic wrapped to something smaller than the truth. */
    int wrapped_on_32bit;
} region_row;

static const region_row region_rows[] = {
    { "an ordinary cue",                 640,    64, 640u * 64u * 4u,   0 },
    { "a full 1080p region",            1920,  1080, 1920u * 1080u * 4u, 0 },
    { "the largest region that fits",  536870911, 1, 536870911u * 4u,   0 },
    { "one pixel too wide",            536870912, 1, 0,                 0 },
    { "a 4 GB region wraps to zero",       65536, 16384, 0,             1 },
    { "a 6 GB region wraps small",         40000, 40000, 0,             1 },
    { "a degenerate width",                    0,   100, 0,             0 },
    { "a negative height",                   100,    -1, 0,             0 },
};

typedef struct {
    const char *name;
    size_t total;
    size_t more;
    int refuses;
} add_row;

static const add_row add_rows[] = {
    { "room to spare",              1024,                 1024,      0 },
    { "exactly at the ceiling",     KITE_MAX_PACKED - 16,   16,      0 },
    { "one byte past the ceiling",  KITE_MAX_PACKED - 16,   17,      1 },
    { "a total already past it",    KITE_MAX_PACKED,         1,      1 },
    { "adding nothing to a full buffer", KITE_MAX_PACKED,    0,      0 },
};

/* The accumulation renderPacked performs, guarded exactly as the shipped loop guards it. */
static int accumulate_refuses(const int *widths, const int *heights, int regions) {
    size_t pixelBytes = 0;
    size_t headerBytes = sizeof(int32_t);
    for (int i = 0; i < regions; i++) {
        size_t bytes = kite_region_bytes(widths[i], heights[i]);
        if (bytes == 0 ||
            kite_add_passes_ceiling(pixelBytes, bytes) ||
            kite_add_passes_ceiling(headerBytes + pixelBytes, 5u * sizeof(int32_t))) {
            return 1;
        }
        pixelBytes += bytes;
        headerBytes += 5u * sizeof(int32_t);
    }
    return 0;
}

int main(void) {
    size_t i;

    kt_suite_begin("test_pack_limits");

    for (i = 0; i < sizeof(region_rows) / sizeof(region_rows[0]); i++) {
        const region_row *row = &region_rows[i];
        size_t got = kite_region_bytes(row->width, row->height);
        kt_case("%s", row->name);
        KT_EQ_I64((int64_t) got, (int64_t) row->expected);
        kt_detail("%dx%d -> %llu", row->width, row->height, (unsigned long long) got);
        if (row->wrapped_on_32bit) {
            uint32_t naive = wrapped_region_bytes_32(row->width, row->height);
            uint64_t truth = (uint64_t) row->width * (uint64_t) row->height * 4u;
            KT_CHECKF((uint64_t) naive != truth,
                      "row claims a 32 bit wrap but %u equals the true %llu",
                      naive, (unsigned long long) truth);
            kt_note("unguarded 32 bit arithmetic gave %u for a real %llu",
                    naive, (unsigned long long) truth);
        }
    }

    for (i = 0; i < sizeof(add_rows) / sizeof(add_rows[0]); i++) {
        const add_row *row = &add_rows[i];
        kt_case("%s", row->name);
        KT_EQ_INT(kite_add_passes_ceiling(row->total, row->more), row->refuses);
        kt_detail("%llu + %llu", (unsigned long long) row->total, (unsigned long long) row->more);
    }

    {
        /* Each region fits on its own; the running total is what passes the ceiling. This is the
         * shape the single-region check alone would have missed. */
        int widths[6];
        int heights[6];
        int at;
        for (at = 0; at < 6; at++) { widths[at] = 400000000; heights[at] = 1; }
        kt_case("six regions that each fit but do not fit together");
        KT_EQ_INT(accumulate_refuses(widths, heights, 6), 1);
        kt_note("each region is 1.6 GB, and the guard trips on the running total");

        kt_case("a normal document of many small regions is accepted");
        for (at = 0; at < 6; at++) { widths[at] = 1920; heights[at] = 120; }
        KT_EQ_INT(accumulate_refuses(widths, heights, 6), 0);
    }

    return kt_suite_end();
}
