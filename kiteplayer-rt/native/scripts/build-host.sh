#!/usr/bin/env bash
#
# Build the host test binaries for the kiteplayer-rt real-time audio ring.
#
# There is no make, no cmake and no ninja here, and that is deliberate:
# cmake is not installed on the proving machine, and GNU make starts a comment at an unescaped
# '#' while both repositories live under a path containing '#Kite'. Driving clang directly is the
# only form that is both available and safe under this path, and it is proven to work here.
#
# Usage:  ./scripts/build-host.sh <variant>
#         variant is one of: plain asan tsan
#
# Variants, per plan section 15.3. ASan and TSan are mutually exclusive, which is why there are
# three of them rather than one:
#
#   plain  -O2, no runtime instrumentation. The compile-fidelity and correctness variant. It is
#          also the ONLY variant in which the allocation interposer works, so it is the variant
#          that carries the allocation evidence (LeakSanitizer is not supported on macOS arm64).
#   asan   -fsanitize=address,undefined -fno-omit-frame-pointer -O1. Catches an out-of-bounds ring
#          index or a wrap arithmetic mistake at the byte where it happens.
#   tsan   -fsanitize=thread -O1. This is the variant that earns its keep: the ring is two
#          seqlocks and two lock-free counters, and a seqlock written with plain or `volatile`
#          fields instead of C11 atomics is a real data race that TSan reports. That is the whole
#          reason src/kite_rt_ring_internal.h makes every cross-thread field _Atomic.
#
# Unlike KiteFFmpeg's equivalent script this one needs no FFmpeg, no pkg-config and no third party
# anything: the ring includes <stdint.h>, <stdlib.h>, <string.h> and <stdatomic.h> and nothing
# else, which is what lets it compile for every Kotlin/Native target KitePlayer declares.
#
# Environment:
#   KPRT_CC   compiler to use, default /usr/bin/clang
#   KPRT_AR   archiver to use, default /usr/bin/ar
#
# Outputs, all under build/<variant>/ which is gitignored:
#   lib/libkiteplayerrt_host.a       the ring
#   lib/libkprt_interpose_alloc.dylib  the allocation interposer
#   bin/test_*                       one binary per test suite
#
set -euo pipefail

VARIANT="${1:-}"
case "$VARIANT" in
    plain|asan|tsan) ;;
    "") echo "build-host.sh: usage: $0 <plain|asan|tsan>" >&2; exit 2 ;;
    *)  echo "build-host.sh: unknown variant '$VARIANT', expected plain, asan or tsan" >&2
        exit 2 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

CC="${KPRT_CC:-/usr/bin/clang}"
AR="${KPRT_AR:-/usr/bin/ar}"
[ -x "$CC" ] || { echo "build-host.sh: no compiler at $CC" >&2; exit 1; }
[ -x "$AR" ] || { echo "build-host.sh: no archiver at $AR" >&2; exit 1; }

BASE_FLAGS="-std=c11 -Wall -Wextra -Werror -Werror=vla -g"
case "$VARIANT" in
    plain) VARIANT_FLAGS="-O2" ;;
    asan)  VARIANT_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -O1" ;;
    tsan)  VARIANT_FLAGS="-fsanitize=thread -O1" ;;
esac

OUT="$ROOT/build/$VARIANT"
OBJ="$OUT/obj"
LIB="$OUT/lib"
BIN="$OUT/bin"
rm -rf "$OUT"
mkdir -p "$OBJ" "$LIB" "$BIN"

RING_LIB="$LIB/libkiteplayerrt_host.a"
INTERPOSE_LIB="$LIB/libkprt_interpose_alloc.dylib"

# The suites. Keep this list and run-c-tests.sh in agreement.
#
# B1.7 built the first six, over the ring. B1.8 adds the two that cover the device callback:
# test_sink_callback drives the shipped callback body five million times with no device at all and
# also compiles a host-only seam around the actual CoreAudio entry point for malformed buffer-list
# fixtures. KPRT_TESTING is never set by CompileKiteRtTask, so that seam cannot enter a shipped
# archive. test_sink_timebase checks this library's host tick arithmetic against CoreAudio's own
# conversion rather than trusting either.
TESTS="test_ring_rescale test_ring_basic test_ring_silence test_ring_bounded test_ring_threads test_ring_alloc test_sink_callback test_sink_timebase"

# Frameworks for the link.
#
# The ring needs none of this; the device unit and the timebase suite do. AudioToolbox carries the
# AudioUnit entry points and CoreAudio carries AudioGetCurrentHostTime and
# AudioConvertHostTimeToNanos, which test_sink_timebase uses as the reference implementation it
# compares this library against. They are named on every link rather than only where they are needed,
# because a static archive member is pulled in by whatever references it and guessing which suite
# does that is how a build breaks a month later.
FRAMEWORKS="-framework AudioToolbox -framework CoreAudio -framework CoreFoundation"

echo "build-host.sh: variant $VARIANT"
echo "  compiler   $CC ($("$CC" --version | head -1))"
echo "  archiver   $AR"
echo "  flags      $BASE_FLAGS $VARIANT_FLAGS"
echo "  output     $OUT"

compile() {
    # compile <source> <object> [extra flags...]
    local source="$1" object="$2"
    shift 2
    echo "  cc  $(basename "$source")"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS \
        -I "$ROOT/include" -I "$ROOT/src" -I "$ROOT/tests" \
        "$@" -c "$source" -o "$object"
}

# 1. The ring itself. Because it includes its own public header, compiling it is the proof that
#    every declaration matches its definition, and -Werror makes a mismatch a hard failure rather
#    than a warning nobody reads.
#
#    -fvisibility=hidden matches CompileKiteRtTask's flag set, so the host archive carries the same
#    exported set as the shipped one. It does not affect static linking, so the test binaries still
#    resolve every function they call.
RING_SOURCES="$(find "$ROOT/src" -maxdepth 1 -name '*.c' | sort)"
[ -n "$RING_SOURCES" ] || { echo "build-host.sh: no .c sources under $ROOT/src" >&2; exit 1; }
RING_OBJECTS=""
for source in $RING_SOURCES; do
    object="$OBJ/$(basename "${source%.c}").o"
    compile "$source" "$object" -fvisibility=hidden -DKPRT_TESTING
    RING_OBJECTS="$RING_OBJECTS $object"
done
rm -f "$RING_LIB"
# shellcheck disable=SC2086
"$AR" rcs "$RING_LIB" $RING_OBJECTS
echo "  ar  $(basename "$RING_LIB") ($(echo $RING_SOURCES | wc -w | tr -d ' ') units)"

# 2. The allocation interposer. It has to be a dynamic library: a Mach-O __DATA,__interpose section
#    is only honoured by dyld when it arrives in a loaded image, and linking the same object
#    straight into the executable was measured to count exactly zero.
echo "  cc  interpose_alloc.c (dylib)"
# shellcheck disable=SC2086
"$CC" $BASE_FLAGS $VARIANT_FLAGS -dynamiclib \
    -I "$ROOT/tests" \
    -install_name "@rpath/$(basename "$INTERPOSE_LIB")" \
    "$ROOT/tests/interpose_alloc.c" -o "$INTERPOSE_LIB"

# 3. The harness.
compile "$ROOT/tests/harness.c" "$OBJ/harness.o"

# 4. One binary per suite. Every binary links the interposer, so the harness can report whether
#    allocation accounting is live without any test having to care which variant it is under.
for test in $TESTS; do
    source="$ROOT/tests/$test.c"
    [ -f "$source" ] || { echo "build-host.sh: missing test source $source" >&2; exit 1; }
    compile "$source" "$OBJ/$test.o" -DKPRT_TESTING
    echo "  ld  $test"
    # shellcheck disable=SC2086
    "$CC" $BASE_FLAGS $VARIANT_FLAGS -o "$BIN/$test" \
        "$OBJ/$test.o" "$OBJ/harness.o" "$RING_LIB" "$INTERPOSE_LIB" \
        -Wl,-rpath,"$LIB" -lpthread $FRAMEWORKS
done

echo "build-host.sh: variant $VARIANT built $(echo $TESTS | wc -w | tr -d ' ') binaries in $BIN"
