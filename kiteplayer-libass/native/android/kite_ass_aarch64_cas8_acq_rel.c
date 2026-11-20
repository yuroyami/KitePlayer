/*
 * The NDK clang that builds the chain calls outline atomics helpers on Android arm64, and the
 * Kotlin/Native linker for Android links no library that has them. HarfBuzz calls two of them; this
 * file and kite_ass_aarch64_ldadd4_acq_rel.c define one each, with the arguments and the result of
 * compiler-rt's helper. The build compiles them with -mno-outline-atomics, so each builtin below is
 * an exclusive load and store loop and does not call itself.
 *
 * One helper per file, as in compiler-rt: a link that also sees KiteFFmpeg's copies of the helpers
 * then takes each helper from one archive only.
 */
#include <stdint.h>

#if defined(__aarch64__)
uint64_t __aarch64_cas8_acq_rel(uint64_t expected, uint64_t desired, uint64_t *ptr);

uint64_t __aarch64_cas8_acq_rel(uint64_t expected, uint64_t desired, uint64_t *ptr) {
    __atomic_compare_exchange_n(ptr, &expected, desired, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
    return expected;
}
#else
typedef int kite_ass_cas8_acq_rel_unused;
#endif
