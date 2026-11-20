/* See kite_ass_aarch64_cas8_acq_rel.c. */
#include <stdint.h>

#if defined(__aarch64__)
uint32_t __aarch64_ldadd4_acq_rel(uint32_t value, uint32_t *ptr);

uint32_t __aarch64_ldadd4_acq_rel(uint32_t value, uint32_t *ptr) {
    return __atomic_fetch_add(ptr, value, __ATOMIC_ACQ_REL);
}
#else
typedef int kite_ass_ldadd4_acq_rel_unused;
#endif
