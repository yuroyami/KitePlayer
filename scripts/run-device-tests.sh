#!/usr/bin/env bash
#
# Runs the Android device tests of the KitePlayer libraries on a connected device or emulator.
#
# The Kotlin Toolchain has no instrumented tests. verification/device-tests is a Gradle build that
# compiles the shared test folders and <library>/android-device-test of each library against the
# staged artifacts of this checkout, then runs the test APKs on the device that adb sees.
# With more than one device connected, set ANDROID_SERIAL to the serial of the one to use.
# Otherwise the tests install on every device.
#
#   scripts/run-device-tests.sh                          # stage, then run every device test set
#   scripts/run-device-tests.sh :ffmpeg:connectedAndroidDeviceTest
#   scripts/run-device-tests.sh --no-stage TASK...       # reuse the staged artifacts
#
# Gradle arguments pass through, for example
# -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.github.yuroyami.kiteplayer.NeedsPushedMedia.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stage=true
if [ "${1:-}" = "--no-stage" ]; then stage=false; shift; fi
# connectedCheck runs the device tests of the libraries and of the sample app.
[ $# -gt 0 ] || set -- connectedCheck
version="$(sed -n 's/^    version:[[:space:]]*//p' "$ROOT/library.module-template.yaml" | tr -d '\r')"

if [ "$stage" = true ]; then
    "$ROOT/scripts/stage-verification-maven.sh" --with-dependencies \
        kiteplayer-io kiteplayer-output kiteplayer-ffmpeg kiteplayer-libass kiteplayer-compose-video \
        kiteplayer-compose kiteplayer-compose-interop kiteplayer-audioviz
fi
[ -d "$ROOT/build/verification-maven" ] || { echo "Nothing staged. Run without --no-stage first." >&2; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)}}"
[ -n "$sdk" ] || { echo "Set ANDROID_HOME, or sdk.dir in local.properties." >&2; exit 2; }
ANDROID_HOME="$sdk" "$ROOT/verification/gradlew" -p "$ROOT/verification/device-tests" --no-daemon --console=plain \
    "-PkiteplayerVersion=$version" "-PverificationRepository=$ROOT/build/verification-maven" "$@"
