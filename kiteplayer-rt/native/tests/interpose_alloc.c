/* Allocation interposer for the kiteplayer-rt C tests.
 *
 * WHY THIS FILE EXISTS. LeakSanitizer is not supported on macOS arm64: an ASan and UBSan binary
 * built by Apple clang 17 answers ASAN_OPTIONS=detect_leaks=1 with "AddressSanitizer:
 * detect_leaks is not supported on this platform". The ring's claim
 * is stronger than "no leak" anyway: it is "one malloc at create, one free at destroy, and
 * nothing in between", which no leak checker can express. This file is the instrument for it.
 *
 * WHAT IT CAN AND CANNOT PROVE, stated here because plan section 15.2 B1.8 refuses one specific
 * use of it as evidence. It is exactly correct for C allocation, which does go through these
 * entry points. It is exactly WRONG for Kotlin/Native allocation, which takes pages by `mmap`
 * and hands objects out of them: measured, an interposer read 229 mallocs before and 230 after
 * one million Kotlin object allocations. So a zero from this instrument says nothing whatsoever
 * about a Kotlin callback, and nothing in this repository may present it as if it did.
 *
 * HOW IT WORKS, and the two traps it avoids.
 *
 *  1. It uses the Mach-O __DATA,__interpose section. The obvious alternative, inserting a library
 *     that simply defines its own malloc, silently counts zero: the two-level namespace binds
 *     every call to the definition in libSystem, so the shadowing definition is never reached.
 *  2. dyld does not apply an interpose section to the image that carries it. That is what lets
 *     the wrappers below call the real malloc and free with no recursion and no dlsym dance, and
 *     it is why the "is the interposer effective" probe lives in harness.c instead.
 *
 * The section is honoured both when this library is linked into the test binary and when it
 * arrives through DYLD_INSERT_LIBRARIES. build-host.sh links it, because that needs no
 * environment variable and survives a binary being run by hand.
 *
 * MEASURED SCOPE. In the plain variant the counters are live. Under asan and tsan they read zero,
 * because each sanitizer runtime replaces the allocator before dyld reaches this section, so
 * kt_alloc_active() returns 0 there and a suite records the gap with kt_partial(). The
 * `interpose` gate step sets KPRT_REQUIRE_ALLOC_ACCOUNTING=1, which turns that gap into a hard
 * failure, so "the interposer was not effective" can never pass as "there were no allocations".
 *
 * calloc, realloc, posix_memalign, aligned_alloc and valloc are separate entry points in
 * libmalloc rather than wrappers over malloc, so counting all of them does not double count.
 * strdup is deliberately not interposed: it does call malloc internally, and interposing both
 * would count one allocation twice.
 */

#include "harness.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/types.h>

static _Atomic long long n_malloc;
static _Atomic long long n_calloc;
static _Atomic long long n_realloc;
static _Atomic long long n_posix_memalign;
static _Atomic long long n_aligned_alloc;
static _Atomic long long n_valloc;
static _Atomic long long n_free;
static _Atomic long long n_mmap;
static _Atomic long long n_munmap;
static _Atomic long long n_live;

static void bump(_Atomic long long *counter, long long by)
{
    atomic_fetch_add_explicit(counter, by, memory_order_relaxed);
}

static long long read_counter(const _Atomic long long *counter)
{
    return atomic_load_explicit(counter, memory_order_relaxed);
}

void kt_alloc_snapshot(kt_alloc_counts *out)
{
    if (out == NULL)
        return;
    out->malloc_calls = read_counter(&n_malloc);
    out->calloc_calls = read_counter(&n_calloc);
    out->realloc_calls = read_counter(&n_realloc);
    out->posix_memalign_calls = read_counter(&n_posix_memalign);
    out->aligned_alloc_calls = read_counter(&n_aligned_alloc);
    out->valloc_calls = read_counter(&n_valloc);
    out->free_calls = read_counter(&n_free);
    out->mmap_calls = read_counter(&n_mmap);
    out->munmap_calls = read_counter(&n_munmap);
    out->live_blocks = read_counter(&n_live);
}

static void *kt_malloc(size_t size)
{
    void *block = malloc(size);
    bump(&n_malloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kt_calloc(size_t count, size_t size)
{
    void *block = calloc(count, size);
    bump(&n_calloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kt_realloc(void *old, size_t size)
{
    void *block = realloc(old, size);
    bump(&n_realloc, 1);
    /* Net effect on live blocks. A grow in place and a move to a fresh block are both net zero:
     * one block goes away and one appears. */
    if (old == NULL) {
        if (block != NULL)
            bump(&n_live, 1);
    } else if (block == NULL && size == 0) {
        bump(&n_live, -1);
    }
    return block;
}

static int kt_posix_memalign(void **out, size_t alignment, size_t size)
{
    int rc = posix_memalign(out, alignment, size);
    bump(&n_posix_memalign, 1);
    if (rc == 0 && out != NULL && *out != NULL)
        bump(&n_live, 1);
    return rc;
}

static void *kt_aligned_alloc(size_t alignment, size_t size)
{
    void *block = aligned_alloc(alignment, size);
    bump(&n_aligned_alloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void *kt_valloc(size_t size)
{
    void *block = valloc(size);
    bump(&n_valloc, 1);
    if (block != NULL)
        bump(&n_live, 1);
    return block;
}

static void kt_free(void *block)
{
    /* free(NULL) is legal and allocates nothing, so it must not move either counter. */
    if (block != NULL) {
        bump(&n_free, 1);
        bump(&n_live, -1);
    }
    free(block);
}

static void *kt_mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    bump(&n_mmap, 1);
    return mmap(addr, length, prot, flags, fd, offset);
}

static int kt_munmap(void *addr, size_t length)
{
    bump(&n_munmap, 1);
    return munmap(addr, length);
}

typedef struct {
    const void *replacement;
    const void *original;
} kt_interpose_entry;

__attribute__((used)) static const kt_interpose_entry
kt_interposers[] __attribute__((section("__DATA,__interpose"))) = {
    { (const void *)&kt_malloc,         (const void *)&malloc },
    { (const void *)&kt_calloc,         (const void *)&calloc },
    { (const void *)&kt_realloc,        (const void *)&realloc },
    { (const void *)&kt_posix_memalign, (const void *)&posix_memalign },
    { (const void *)&kt_aligned_alloc,  (const void *)&aligned_alloc },
    { (const void *)&kt_valloc,         (const void *)&valloc },
    { (const void *)&kt_free,           (const void *)&free },
    { (const void *)&kt_mmap,           (const void *)&mmap },
    { (const void *)&kt_munmap,         (const void *)&munmap },
};
