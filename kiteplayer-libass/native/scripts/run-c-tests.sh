#!/usr/bin/env bash
#
# Build and run the C test suites for :kiteplayer-libass.
#
# Usage:  ./scripts/run-c-tests.sh [plain|asan|wasm32]
#         Default is plain. asan adds -fsanitize=address,undefined. wasm32 builds the suites that
#         need no libass with emcc, under the same sanitizers, and runs them with node.
#
# WHY THIS BUILDS AND RUNS IN ONE STEP, unlike kiteplayer-rt's pair of scripts. That module splits
# them so a gate cannot pass on a stale binary. Here every suite rebuilds on every run, which
# removes the stale hazard rather than guarding against it.
#
# WHY IT BORROWS kiteplayer-rt's harness. harness.h forbids sharing a harness across REPOSITORIES,
# because KiteFFmpeg is a public binding and KitePlayer is a private player. Inside this repository
# there is no such boundary: this is the same build layer, the same compiler flags, and the same
# `kt_` prefix, so a second copy would only be a second thing to keep in step.
#
# THREE SUITES, ONE OPTIONAL. test_pack_limits and test_font_names need neither jni.h nor libass.
# They cover the packed-buffer size arithmetic in src/libass_pack_limits.h and the family name
# reader in src/kite_font_name.h, each of which lives in its own header precisely so it can be
# proven without a renderer present. test_kite_ass drives the shared driver in src/kite_ass.h
# against a REAL libass and asserts pixels, so it runs only where one is installed (Homebrew on
# the macOS host) and says SKIPPED loudly otherwise, never silently.
#
# WHY A wasm32 VARIANT. size_t is 32 bits wide on wasm32, as on the armeabi-v7a and x86 Android
# ABIs, and node runs the result on any host. Both header-only suites are about offsets and sizes
# near the top of a size_t, so this variant runs them where 32-bit arithmetic is real rather than
# emulated. emcc comes from `brew install emscripten`, or from KPLA_EMCC; node from KPLA_NODE.
#
set -euo pipefail

VARIANT="${1:-plain}"
case "$VARIANT" in
    plain|asan|wasm32) ;;
    *) echo "run-c-tests.sh: unknown variant '$VARIANT', expected plain, asan or wasm32" >&2; exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RT_TESTS="$(cd "$ROOT/../../kiteplayer-rt/native/tests" && pwd)"

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"

OUT="$ROOT/build/$VARIANT"
rm -rf "$OUT"
mkdir -p "$OUT/bin"

export ASAN_OPTIONS="detect_leaks=0:abort_on_error=1:print_stacktrace=1:strict_string_checks=1"
export UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1"

FAILED=0
if [ "$VARIANT" = wasm32 ]; then
    EMCC="${KPLA_EMCC:-emcc}"
    NODE="${KPLA_NODE:-node}"
    command -v "$EMCC" >/dev/null || { echo "run-c-tests.sh: no emcc at '$EMCC' (brew install emscripten)" >&2; exit 1; }
    command -v "$NODE" >/dev/null || { echo "run-c-tests.sh: no node at '$NODE'" >&2; exit 1; }
    SUITES="test_pack_limits test_font_names"

    echo "run-c-tests.sh: variant $VARIANT"
    echo "  compiler   $EMCC ($("$EMCC" --version | head -1))"
    echo "  runner     $NODE ($("$NODE" --version))"
    echo "  harness    $RT_TESTS"

    # node passes no environment into the module, so the sanitizers are made to stop on the first
    # report at compile time. EXIT_RUNTIME turns the suite's exit status into node's.
    for suite in $SUITES; do
        echo "  emcc  $suite"
        # shellcheck disable=SC2086
        "$EMCC" $BASE_FLAGS -O1 -fsanitize=address,undefined -fno-sanitize-recover=all \
            -sEXIT_RUNTIME=1 \
            -I "$RT_TESTS" -I "$ROOT/src" -I "$ROOT/tests" \
            -o "$OUT/bin/$suite.js" \
            "$ROOT/tests/$suite.c" "$RT_TESTS/harness.c" "$ROOT/tests/no_alloc_counts.c"
    done

    for suite in $SUITES; do
        echo
        if ! "$NODE" "$OUT/bin/$suite.js"; then FAILED=1; fi
    done
else
    CC="${KPLA_CC:-/usr/bin/clang}"
    [ -x "$CC" ] || { echo "run-c-tests.sh: no compiler at $CC" >&2; exit 1; }

    case "$VARIANT" in
        plain) VARIANT_FLAGS="-O2" ;;
        asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
    esac

    SUITES="test_pack_limits test_font_names"
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
    # __DATA,__interpose section is only honoured when dyld sees it in a loaded image. These suites
    # assert nothing about allocation, but the harness probes the counters at suite_begin, so the
    # symbols have to resolve.
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -dynamiclib -I "$RT_TESTS" \
        -install_name "@rpath/libkprt_interpose_alloc.dylib" \
        "$RT_TESTS/interpose_alloc.c" -o "$OUT/bin/libkprt_interpose_alloc.dylib"

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

    for suite in $SUITES; do
        echo
        if ! "$OUT/bin/$suite"; then FAILED=1; fi
    done
fi

echo
if [ "$FAILED" -ne 0 ]; then
    echo "run-c-tests.sh: FAILED in variant $VARIANT"
    exit 1
fi
echo "run-c-tests.sh: all suites passed in variant $VARIANT"
