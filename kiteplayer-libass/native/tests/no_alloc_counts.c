/*
 * The harness's allocation counters for a build with no interposer, such as wasm32. They stay at
 * zero, so kt_alloc_active() answers 0 and no suite can read an allocation claim from that build.
 */

#include "harness.h"

#include <string.h>

void kt_alloc_snapshot(kt_alloc_counts *out) {
    memset(out, 0, sizeof(*out));
}
