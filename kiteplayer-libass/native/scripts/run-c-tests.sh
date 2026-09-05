#!/usr/bin/env bash
#
# Build and run the host C test suites for :kiteplayer-libass.
#
# Usage:  ./scripts/run-c-tests.sh [plain|asan]
#         Default is plain. asan adds -fsanitize=address,undefined.
#
# WHY THIS BUILDS AND RUNS IN ONE STEP, unlike kiteplayer-rt's pair of scripts. That module splits
# them so a gate cannot pass on a stale binary. Here there is exactly one suite and it rebuilds
# every time, which removes the stale hazard rather than guarding against it.
#
# WHY IT BORROWS kiteplayer-rt's harness. harness.h forbids sharing a harness across REPOSITORIES,
# because KiteFFmpeg is a public binding and KitePlayer is a private player. Inside this repository
# there is no such boundary: this is the same build layer, the same compiler flags, and the same
# `kt_` prefix, so a second copy would only be a second thing to keep in step.
#
# TWO SUITES, ONE OPTIONAL. test_pack_limits needs neither jni.h nor libass: it covers the
# packed-buffer size arithmetic, which lives in src/libass_pack_limits.h precisely so it can be
# proven without a renderer present. test_kite_ass drives the shared driver in src/kite_ass.h
# against a REAL libass and asserts pixels, so it runs only where one is installed (Homebrew on
# the macOS host) and says SKIPPED loudly otherwise, never silently.
#
set -euo pipefail

VARIANT="${1:-plain}"
case "$VARIANT" in
    plain|asan) ;;
    *) echo "run-c-tests.sh: unknown variant '$VARIANT', expected plain or asan" >&2; exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RT_TESTS="$(cd "$ROOT/../../kiteplayer-rt/native/tests" && pwd)"

CC="${KPLA_CC:-/usr/bin/clang}"
[ -x "$CC" ] || { echo "run-c-tests.sh: no compiler at $CC" >&2; exit 1; }

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
case "$VARIANT" in
    plain) VARIANT_FLAGS="-O2" ;;
    asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
esac

OUT="$ROOT/build/$VARIANT"
rm -rf "$OUT"
mkdir -p "$OUT/bin"

SUITES="test_pack_limits"
LIBASS_PREFIX="${KPLA_LIBASS_PREFIX:-/opt/homebrew}"
if [ -f "$LIBASS_PREFIX/include/ass/ass.h" ] && [ -e "$LIBASS_PREFIX/lib/libass.dylib" ]; then
    SUITES="$SUITES test_kite_ass"
else
    echo "run-c-tests.sh: SKIPPED test_kite_ass (no libass under $LIBASS_PREFIX; brew install libass)"
fi

echo "run-c-tests.sh: variant $VARIANT"
echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  harness    $RT_TESTS"

# The interposer is a dylib for the same reason kiteplayer-rt makes it one: a Mach-O
# __DATA,__interpose section is only honoured when dyld sees it in a loaded image. This suite
# asserts nothing about allocation, but the harness probes the counters at suite_begin, so the
# symbols have to resolve.
# shellcheck disable=SC2086
"$CC" $BASE_FLAGS $VARIANT_FLAGS -dynamiclib -I "$RT_TESTS" \
    -install_name "@rpath/libkprt_interpose_alloc.dylib" \
    "$RT_TESTS/interpose_alloc.c" -o "$OUT/bin/libkprt_interpose_alloc.dylib"

FAILED=0
for suite in $SUITES; do
    echo "  cc  $suite"
    SUITE_FLAGS=""
    if [ "$suite" = test_kite_ass ]; then
        SUITE_FLAGS="-I$LIBASS_PREFIX/include -L$LIBASS_PREFIX/lib -lass -Wl,-rpath,$LIBASS_PREFIX/lib"
    fi
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS \
        -I "$RT_TESTS" -I "$ROOT/src" -I "$ROOT/tests" \
        -o "$OUT/bin/$suite" \
        "$ROOT/tests/$suite.c" "$RT_TESTS/harness.c" \
        "$OUT/bin/libkprt_interpose_alloc.dylib" -Wl,-rpath,"$OUT/bin" $SUITE_FLAGS
done

export ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"

for suite in $SUITES; do
    echo
    if ! "$OUT/bin/$suite"; then FAILED=1; fi
done

echo
if [ "$FAILED" -ne 0 ]; then
    echo "run-c-tests.sh: FAILED in variant $VARIANT"
    exit 1
fi
echo "run-c-tests.sh: all suites passed in variant $VARIANT"
