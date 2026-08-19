/*
 * The size arithmetic of the packed subtitle buffer, in a header so a host test can compile it
 * without jni.h and without libass.
 *
 * SEC-1: region dimensions come out of the subtitle file, which is untrusted input. The packed
 * buffer's total was accumulated with no check at all, and `size_t` is 32 bit on armeabi-v7a and
 * x86, so a large enough cue wrapped the total, under-allocated, and the fill loop then wrote the
 * full un-wrapped amount past the end of the heap block.
 *
 * ONE ceiling closes two different overflows. `NewByteArray` takes a signed 32 bit `jsize` on
 * every ABI, so nothing above INT32_MAX is representable even on 64 bit; and INT32_MAX is below
 * `SIZE_MAX` on the 32 bit ABIs, so refusing above it makes the wrap unreachable there. Picking
 * INT32_MAX rather than SIZE_MAX is also what makes this testable: the guard trips at the same
 * input on a 64 bit host as on a 32 bit phone, so the test proves the shipped ABI's behaviour.
 */

#ifndef KITE_LIBASS_PACK_LIMITS_H
#define KITE_LIBASS_PACK_LIMITS_H

#include <stddef.h>
#include <stdint.h>

/** The largest packed buffer this bridge will build, in bytes. */
#define KITE_MAX_PACKED ((size_t) INT32_MAX)

/** True when `total + more` would pass the ceiling. Never evaluates the sum that would wrap. */
static inline int kite_add_passes_ceiling(size_t total, size_t more) {
    if (total > KITE_MAX_PACKED) return 1;
    return more > KITE_MAX_PACKED - total;
}

/**
 * RGBA bytes for one `width` x `height` region, or 0 when that product alone passes the ceiling.
 * Zero is never a legal region size here, so it doubles as the refusal.
 */
static inline size_t kite_region_bytes(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    size_t w = (size_t) width, h = (size_t) height;
    if (h > KITE_MAX_PACKED / 4u) return 0;
    if (w > (KITE_MAX_PACKED / 4u) / h) return 0;
    return w * h * 4u;
}

#endif /* KITE_LIBASS_PACK_LIMITS_H */
