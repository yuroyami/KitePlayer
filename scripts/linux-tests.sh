#!/usr/bin/env bash
# Runs KitePlayer's Kotlin/Native test binaries on real Linux, from a macOS host.
#
# Why this exists: a macOS host can link the Linux test binaries but cannot run them. This script
# links them with the Kotlin Toolchain and EXECUTES them in a Linux container.
#
#   ./scripts/linux-tests.sh                # linuxArm64, native speed on Apple silicon
#   ./scripts/linux-tests.sh linuxX64       # linuxX64, emulated, slow
#
# mingwX64 is NOT here on purpose: a PE binary needs Windows, and this phase records Windows as a
# link claim rather than pretending otherwise.
set -euo pipefail

TARGET="${1:-linuxArm64}"
case "$TARGET" in
  linuxArm64) PLATFORM=linux/arm64; LINK_TASK=linkLinuxArm64TestDebug ;;
  linuxX64)   PLATFORM=linux/amd64; LINK_TASK=linkLinuxX64TestDebug ;;
  *) echo "usage: $0 [linuxArm64|linuxX64]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${KITE_LINUX_IMAGE:-debian:bookworm-slim}"
MODULES=(kiteplayer-core kiteplayer-subtitles kiteplayer-io kiteplayer-ffmpeg kiteplayer)

# Docker Desktop's credential helper blocks on the login keychain in a headless session, which
# hangs every pull. An empty config skips it; these are public images and need no credentials.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

# The published kiteffmpeg klibs carry FFmpeg for both Linux targets, so the link needs nothing from
# the sibling KiteFFmpeg checkout.
for module in "${MODULES[@]}"; do
  echo "== linking :$module:$LINK_TASK"
  (cd "$ROOT" && KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin task ":$module:$LINK_TASK")
done

status=0
for module in "${MODULES[@]}"; do
  binary=$(cd "$ROOT" && find "build/tasks/_${module}_$LINK_TASK" -maxdepth 1 -name '*.kexe' -type f | head -1)
  [ -n "$binary" ] || { echo "MISSING the test binary of $module"; status=1; continue; }
  echo "== running $binary on $PLATFORM"
  # TMPDIR is unset in a bare container and the suites refuse to guess. The media path is passed
  # explicitly, as scripts/test-env.sh does on the host. The suites run in their module folder.
  if docker run --rm --platform "$PLATFORM" -e TMPDIR=/tmp -e KITEPLAYER_TESTMEDIA=/w/testmedia \
      -v "$ROOT:/w" -w "/w/$module" "$IMAGE" "/w/$binary" | tail -3; then
    :
  else
    status=1
  fi
done

echo "linux-tests.sh: target $TARGET, exit $status"
exit $status
