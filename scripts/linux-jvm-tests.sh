#!/usr/bin/env bash
# Runs :kiteplayer-ffmpeg's OWN jvm test suite on real Linux, from a macOS host.
#
# Why this and not a probe: an earlier run proved the jar's Linux JNI library LOADS (identity acceptable,
# h264 and hevc present). Nothing had DECODED on Linux through the JVM path, while macOS runs all
# 27 rows of the 17.5 matrix. A hand-written decode probe would be a second, weaker definition of
# "plays all formats", so this ships the real suite instead: same FormatMatrixTest, other kernel.
#
#   ./scripts/linux-jvm-tests.sh                 # linux/arm64, native speed on Apple silicon
#   ./scripts/linux-jvm-tests.sh linux/amd64     # emulated, slow
#   ./scripts/linux-jvm-tests.sh linux/arm64 --falsify   # must FAIL: truncated JNI library
#
# Honest bound: a container has no audio device, so this proves DECODE only. The desktop
# javax.sound.sampled sink stays proved against a fake device seam, exactly as W.3 recorded.
set -euo pipefail

PLATFORM="${1:-linux/arm64}"
MODE="${2:-real}"
case "$PLATFORM" in linux/arm64|linux/amd64) ;; *) echo "usage: $0 [linux/arm64|linux/amd64] [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="/tmp/kite-linux-jvm-tests-$(echo "$PLATFORM" | tr / -)${MODE#real}.log"
IMAGE="${KITE_JDK_IMAGE:-eclipse-temurin:21-jdk}"
TEST_CLASSES="$ROOT/kiteplayer-ffmpeg/build/classes/kotlin/jvm/test"

# Gradle owns the classpath and the container has no Gradle, so ask for it here.
echo "== asking Gradle for the jvm test runtime classpath"
CP="$("$ROOT/gradlew" -q -p "$ROOT" :kiteplayer-ffmpeg:printJvmTestRuntimeClasspath | tail -1)"
[ -n "$CP" ] || { echo "empty classpath" >&2; exit 1; }

# The classpath is mounted at its OWN absolute paths so no rewriting can get it wrong. That only
# works while every entry sits under one of these roots, so check rather than hope.
offenders=""
IFS=':' read -r -a entries <<< "$CP"
for entry in "${entries[@]}"; do
  case "$entry" in
    "$ROOT"/*|"$HOME"/.gradle/*|"$HOME"/.m2/*) ;;
    *) offenders="$offenders$entry"$'\n' ;;
  esac
done
[ -z "$offenders" ] || { printf 'classpath entries outside the mounted roots:\n%s' "$offenders" >&2; exit 1; }

# Every compiled JUnit test class, discovered rather than listed, so a new suite is picked up.
CLASSES=()
while IFS= read -r class; do CLASSES+=("$class"); done < <(cd "$TEST_CLASSES" && \
  find . -name '*Test.class' ! -name '*$*' | sed -e 's|^\./||' -e 's|\.class$||' -e 's|/|.|g' | sort)
[ "${#CLASSES[@]}" -gt 0 ] || { echo "no test classes under $TEST_CLASSES" >&2; exit 1; }
printf '%s\n' "${CLASSES[@]}" | grep -qx 'io.github.yuroyami.kiteplayer.ffmpeg.FormatMatrixTest' \
  || { echo "FormatMatrixTest is not in the discovered set, so this run would prove nothing" >&2; exit 1; }
echo "== ${#CLASSES[@]} test classes discovered"

# Docker Desktop's credential helper blocks on the login keychain in a headless session.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

JVM_ARGS=()
MOUNTS=(-v "$ROOT:$ROOT:ro" -v "$HOME/.gradle:$HOME/.gradle:ro" -v "$HOME/.m2:$HOME/.m2:ro")
if [ "$MODE" = "--falsify" ]; then
  # A truncated library staged where -Dkiteffmpeg.jni.path wins over the jar bundle. The run must
  # FAIL, not skip: a suite that reports success without a working decoder proves nothing.
  FAKE=$(mktemp -d)
  trap 'rm -rf "$FAKE"' EXIT
  head -c 4096 /dev/zero > "$FAKE/libkitecodec_jni.so"
  JVM_ARGS+=("-Dkiteffmpeg.jni.path=$FAKE/libkitecodec_jni.so")
  MOUNTS+=(-v "$FAKE:$FAKE:ro")
  echo "== FALSIFICATION arm: truncated JNI library at $FAKE"
fi

# NEVER mount at /lib: that shadows the container's own libc and loader, and every binary then
# fails with "no such file or directory", which reads like a missing file and is not.
# --entrypoint java because the image's default entrypoint script is not always present.
set +e
docker run --rm --platform "$PLATFORM" --entrypoint java \
  -e TMPDIR=/tmp -e KITEPLAYER_TESTMEDIA="$ROOT/testmedia" "${MOUNTS[@]}" \
  -w /tmp "$IMAGE" ${JVM_ARGS[@]+"${JVM_ARGS[@]}"} -cp "$CP" org.junit.runner.JUnitCore "${CLASSES[@]}" \
  > "$LOG" 2>&1
status=$?
set -e
grep -E '^(OK|Tests run|FAILURES)' "$LOG" || tail -20 "$LOG"
matrix=$(grep -c '^MATRIX ' "$LOG" || true)

if [ "$MODE" = "--falsify" ]; then
  if [ "$status" -eq 0 ]; then
    echo "FALSIFICATION FAILED: the suite passed with a truncated JNI library" >&2
    exit 1
  fi
  # A JVM that died before running anything would also be non-zero, and that is not the proof.
  grep -qE '^Tests run: [1-9]' "$LOG" \
    || { echo "FALSIFICATION INCONCLUSIVE: no test ran at all" >&2; exit 1; }
  echo "linux-jvm-tests.sh: falsification arm failed as required (exit $status), full log $LOG"
  exit 0
fi

echo "linux-jvm-tests.sh: platform $PLATFORM, matrix rows printed $matrix, exit $status"
echo "full log: $LOG"
exit $status
