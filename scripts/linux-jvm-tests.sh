#!/usr/bin/env bash
# Runs kiteplayer-ffmpeg's OWN jvm test suite on real Linux, from a macOS host.
#
# Why this and not a probe: an earlier run proved the jar's Linux JNI library LOADS (identity acceptable,
# h264 and hevc present). Nothing had DECODED on Linux through the JVM path, while macOS runs all
# 27 rows of the format matrix. A hand-written decode probe would be a second, weaker definition of
# "plays all formats", so this ships the real suite instead: same FormatMatrixTest, other kernel.
#
#   ./scripts/linux-jvm-tests.sh                 # linux/arm64, native speed on Apple silicon
#   ./scripts/linux-jvm-tests.sh linux/amd64     # emulated, slow
#   ./scripts/linux-jvm-tests.sh linux/arm64 --falsify   # must FAIL: truncated JNI library
#
# The container runs `./kotlin test` on a copy of the sources, so the classpath is the one the
# Kotlin Toolchain uses on the host. A Docker volume keeps the toolchain cache between runs.
#
# Honest bound: a container has no audio device, so this proves DECODE only. The desktop
# javax.sound.sampled sink stays proved against a fake device seam, exactly as recorded when that sink was built.
set -euo pipefail

PLATFORM="${1:-linux/arm64}"
MODE="${2:-real}"
case "$PLATFORM" in linux/arm64|linux/amd64) ;; *) echo "usage: $0 [linux/arm64|linux/amd64] [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="/tmp/kite-linux-jvm-tests-$(echo "$PLATFORM" | tr / -)${MODE#real}.log"
IMAGE="${KITE_JDK_IMAGE:-eclipse-temurin:21-jdk}"
CACHE_VOLUME="kiteplayer-toolchain-cache-$(echo "$PLATFORM" | tr / -)"

# A copy of what the build reads: the root build files and every module folder, without outputs.
# The container then cannot write into this checkout's build folder.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$ROOT/project.yaml" "$ROOT/kotlin" "$ROOT/libs.versions.toml" "$ROOT"/*.module-template.yaml "$WORK/"
modules=$(awk '/^modules:/ { inside = 1; next } /^[^[:space:]-]/ { inside = 0 } inside && /^  - / { print $2 }' "$ROOT/project.yaml")
for module in $modules; do
  mkdir -p "$WORK/$module"
  rsync -a --exclude /build/ "$ROOT/$module/" "$WORK/$module/"
done
[ -f "$WORK/kiteplayer-ffmpeg/module.yaml" ] || { echo "kiteplayer-ffmpeg was not copied" >&2; exit 1; }

# Docker Desktop's credential helper blocks on the login keychain in a headless session.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

TEST_ARGS=()
MOUNTS=(-v "$WORK:/w" -v "$ROOT/testmedia:/w/testmedia:ro" -v "$CACHE_VOLUME:/root/.cache/JetBrains/Kotlin")
if [ "$MODE" = "--falsify" ]; then
  # A truncated library staged where -Dkiteffmpeg.jni.path wins over the jar bundle. The run must
  # FAIL, not skip: a suite that reports success without a working decoder proves nothing.
  FAKE=$(mktemp -d)
  trap 'rm -rf "$WORK" "$FAKE"' EXIT
  head -c 4096 /dev/zero > "$FAKE/libkitecodec_jni.so"
  TEST_ARGS+=("--jvm-args=-Dkiteffmpeg.jni.path=/fake/libkitecodec_jni.so")
  MOUNTS+=(-v "$FAKE:/fake:ro")
  echo "== FALSIFICATION arm: truncated JNI library at $FAKE"
fi

# NEVER mount at /lib: that shadows the container's own libc and loader, and every binary then
# fails with "no such file or directory", which reads like a missing file and is not.
set +e
docker run --rm --platform "$PLATFORM" --entrypoint /bin/sh \
  -e TMPDIR=/tmp -e KITEPLAYER_TESTMEDIA=/w/testmedia -e KOTLIN_CLI_NO_WELCOME_BANNER=1 "${MOUNTS[@]}" \
  -w /w "$IMAGE" -c 'exec ./kotlin test -m kiteplayer-ffmpeg -p jvm "$@"' sh ${TEST_ARGS[@]+"${TEST_ARGS[@]}"} \
  > "$LOG" 2>&1
status=$?
set -e
grep -E -i 'tests? (passed|failed)|FAILED|BUILD' "$LOG" | tail -5 || tail -20 "$LOG"
matrix=$(grep -c 'MATRIX ' "$LOG" || true)
grep -q 'FormatMatrixTest' "$LOG" \
  || { echo "FormatMatrixTest does not appear in the log, so this run would prove nothing" >&2; exit 1; }

if [ "$MODE" = "--falsify" ]; then
  if [ "$status" -eq 0 ]; then
    echo "FALSIFICATION FAILED: the suite passed with a truncated JNI library" >&2
    exit 1
  fi
  echo "linux-jvm-tests.sh: falsification arm failed as required (exit $status), full log $LOG"
  exit 0
fi

echo "linux-jvm-tests.sh: platform $PLATFORM, matrix rows printed $matrix, exit $status"
echo "full log: $LOG"
exit $status
