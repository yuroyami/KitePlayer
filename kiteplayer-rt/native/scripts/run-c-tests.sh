#!/usr/bin/env bash
#
# Run the host C test suites for one build variant.
#
# Usage:  ./scripts/run-c-tests.sh <variant> [suite ...]
#         variant is one of: plain asan tsan interpose
#         suite names are the file stems, for example test_ring_basic. With none given, all nine
#         run, which is what a gate does.
#
# Build first: ./scripts/build-host.sh <variant>. This script never builds, so a gate cannot
# accidentally pass on a stale binary that was never recompiled.
#
# THE `interpose` MODE IS NOT A BUILD VARIANT. It runs the `plain` binaries with
# KPRT_REQUIRE_ALLOC_ACCOUNTING=1, which makes the harness fail rather than record a partial when
# the allocation interposer is not effective. Plan section 15.2 B1.7's gate lists it as a separate
# step because the claim it carries ("zero malloc, calloc, realloc, free and mmap between
# kprt_ring_create returning and kprt_ring_destroy") is worthless unless the instrument is proven
# live. Without the flag a variant where the counters read zero because nothing is counting looks
# exactly like a variant where they read zero because nothing allocated.
#
# Each suite returns non-zero on its first failing case and prints one line per case, which is the
# contract in plan section 15.3. This runner does not stop at the first failing suite: it runs all
# of them and then exits non-zero, because when three suites break at once the useful output is all
# three.
#
set -uo pipefail

MODE="${1:-}"
case "$MODE" in
    plain|asan|tsan) VARIANT="$MODE"; shift ;;
    interpose)       VARIANT="plain"; shift ;;
    "") echo "run-c-tests.sh: usage: $0 <plain|asan|tsan|interpose> [suite ...]" >&2; exit 2 ;;
    *)  echo "run-c-tests.sh: unknown mode '$MODE', expected plain, asan, tsan or interpose" >&2
        exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BIN="$ROOT/build/$VARIANT/bin"

ALL_SUITES="test_ring_rescale test_ring_basic test_ring_silence test_ring_bounded test_ring_threads test_ring_alloc test_ring_gain_boost test_sink_callback test_sink_teardown test_sink_timebase"
SUITES="${*:-$ALL_SUITES}"

[ -d "$BIN" ] || {
    echo "run-c-tests.sh: no binaries for variant $VARIANT. Run ./scripts/build-host.sh $VARIANT" >&2
    exit 1
}

# Sanitizer options, per variant.
#
# detect_leaks=0 is set explicitly rather than left to the default, because of one fact:
# LeakSanitizer is not supported on macOS arm64, and asking for it gets
# "detect_leaks is not supported on this platform" instead of leak evidence. The local instrument
# is the allocation interposer in the plain variant.
export ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"
export TSAN_OPTIONS="halt_on_error=1:second_deadlock_stack=1"

if [ "$MODE" = "interpose" ]; then
    export KPRT_REQUIRE_ALLOC_ACCOUNTING=1
else
    unset KPRT_REQUIRE_ALLOC_ACCOUNTING
fi

echo "run-c-tests.sh: mode $MODE (binaries from variant $VARIANT)"
echo "  binaries   $BIN"
case "$MODE" in
    plain)     echo "  allocation accounting is live in this variant" ;;
    interpose) echo "  allocation accounting is REQUIRED: a suite that cannot observe it fails" ;;
    asan)      echo "  allocation accounting reads zero here: the ASan runtime owns the allocator" ;;
    tsan)      echo "  allocation accounting reads zero here: the TSan runtime owns the allocator" ;;
esac
echo

passed=""
failed=""
missing=""

for suite in $SUITES; do
    binary="$BIN/$suite"
    if [ ! -x "$binary" ]; then
        echo "MISSING  $suite has no binary in $BIN"
        missing="$missing $suite"
        continue
    fi
    echo "=== $suite"
    if "$binary"; then
        passed="$passed $suite"
    else
        code="$?"
        echo "=== $suite exited $code"
        failed="$failed $suite"
    fi
    echo
done

count() { echo $# ; }
# shellcheck disable=SC2086
echo "run-c-tests.sh: mode $MODE, $(count $passed) suites passed, $(count $failed) failed, $(count $missing) missing"
[ -n "$failed" ] && echo "  failed: ${failed# }"
[ -n "$missing" ] && echo "  missing: ${missing# }"

if [ -n "$failed" ] || [ -n "$missing" ]; then
    exit 1
fi
exit 0
