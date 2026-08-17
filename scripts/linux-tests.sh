#!/usr/bin/env bash
# Runs KitePlayer's Kotlin/Native test binaries on real Linux, from a macOS host.
#
# Why this exists: Gradle creates linuxX64Test / linuxArm64Test but permanently disables them on a
# macOS host, so a gate that names those tasks is green by definition. Kotlin/Native CROSS-LINKS the
# binaries here; this script is what actually EXECUTES them, in a Linux container. Register item
# W-11, KPKMP.md 17.13.
#
#   ./scripts/linux-tests.sh                # linuxArm64, native speed on Apple silicon
#   ./scripts/linux-tests.sh linuxX64       # linuxX64, emulated, slow
#
# mingwX64 is NOT here on purpose: a PE binary needs Windows, and this phase records Windows as a
# link claim rather than pretending otherwise.
set -euo pipefail

TARGET="${1:-linuxArm64}"
case "$TARGET" in
  linuxArm64) PLATFORM=linux/arm64; LINK_SUFFIX=LinuxArm64 ;;
  linuxX64)   PLATFORM=linux/amd64; LINK_SUFFIX=LinuxX64 ;;
  *) echo "usage: $0 [linuxArm64|linuxX64]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${KITE_LINUX_IMAGE:-debian:bookworm-slim}"
MODULES=(kiteplayer-core kiteplayer-subtitles)

# Docker Desktop's credential helper blocks on the login keychain in a headless session, which
# hangs every pull. An empty config skips it; these are public images and need no credentials.
DOCKER_CONFIG="${DOCKER_CONFIG:-$(mktemp -d)}"
[ -f "$DOCKER_CONFIG/config.json" ] || echo '{}' > "$DOCKER_CONFIG/config.json"
export DOCKER_CONFIG

GRADLE_TASKS=()
for module in "${MODULES[@]}"; do
  GRADLE_TASKS+=(":$module:linkDebugTest$LINK_SUFFIX")
done
echo "== linking ${GRADLE_TASKS[*]}"
"$ROOT/gradlew" -p "$ROOT" "${GRADLE_TASKS[@]}"

status=0
for module in "${MODULES[@]}"; do
  binary="$module/build/bin/$TARGET/debugTest/test.kexe"
  [ -f "$ROOT/$binary" ] || { echo "MISSING $binary"; status=1; continue; }
  echo "== running $binary on $PLATFORM"
  if docker run --rm --platform "$PLATFORM" -v "$ROOT:/w" -w /w "$IMAGE" "./$binary" | tail -3; then
    :
  else
    status=1
  fi
done

echo "linux-tests.sh: target $TARGET, exit $status"
exit $status
