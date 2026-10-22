#!/usr/bin/env bash
#
# Runs the Kotlin/Native tests of one module with exclusions by test NAME.
#
# `./kotlin test --exclude-classes` drops whole classes only. Some suites hold one or two tests that
# need hardware a CI runner or the iOS simulator does not have, next to tests that do pass there.
# This script links the test binary with the Kotlin Toolchain and runs it with its own
# `--ktest_filter`, so it drops exactly those tests. A dropped test reports as not run.
# A skip inside the test would report PASSED for an assertion nobody made.
#
#   scripts/run-native-tests.sh -m kiteplayer-output -p macosArm64 --exclude-set no-audio-hardware
#   scripts/run-native-tests.sh -m kiteplayer-output -p iosSimulatorArm64 --exclude-set simulator-audio
#   scripts/run-native-tests.sh -m kiteplayer-core -p macosArm64 --exclude '*SomeTest.a test name'
#
# Platforms: macosArm64, linuxX64 or linuxArm64 on a Linux host, and the iOS, tvOS and watchOS
# simulators. A simulator binary runs through `xcrun simctl spawn --standalone` on a shut-down
# simulator of its family, or through `xcrun simctl spawn` on a booted one. `./kotlin test` runs iOS
# simulator tests on a booted simulator only, so tvOS and watchOS simulator tests need this script
# even without exclusions.
# Run it from anywhere; it sources scripts/test-env.sh for the clip and transcript paths.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

module=""
platform=""
excludes=()

# The named exclusion sets. Each pattern is `*<Class>` or `*<Class>.<test name>`.
exclude_set() {
    case "$1" in
        # One assertion in CoreAudioSinkTest needs real audio hardware. A GitHub macOS runner opens
        # CoreAudio and passes every other test of kiteplayer-output. Its virtual output device lags
        # where real hardware leads: the anchor measured -451.7 ms against a window of -5 to +500 ms.
        no-audio-hardware)
            excludes+=("*CoreAudioSinkTest.the anchor the device publishes is in the near future on the engine clock")
            ;;
        # The iOS simulator cannot open an audio device ("opening the audio device failed" from a
        # simctl-spawned process). The same suites pass on macosArm64 and on a device. Two classes
        # need a device in every test. CoreAudioSinkTest loses nine tests and keeps five.
        simulator-audio)
            excludes+=("*CoreAudioSinkRealTimeTest.*" "*CoreAudioSinkIosTest.*")
            local name
            for name in \
                "a tone plays and the device consumes it at real time speed" \
                "latency is reported as a plausible positive figure while playing" \
                "pause keeps buffered audio and resume consumes it again" \
                "an unfed device is handed silence rather than stalling" \
                "the anchor the device publishes is in the near future on the engine clock" \
                "the callback body stays well inside the device period" \
                "a failed open hands back everything it created" \
                "the managed session lease surrounds successful and failed C ownership" \
                "session activation precedes C creation and application-managed makes no call"; do
                excludes+=("*CoreAudioSinkTest.$name")
            done
            ;;
        *) echo "unknown exclusion set: $1" >&2; exit 2 ;;
    esac
}

while [ $# -gt 0 ]; do
    case "$1" in
        -m) module="$2"; shift 2 ;;
        -p) platform="$2"; shift 2 ;;
        --exclude) excludes+=("$2"); shift 2 ;;
        --exclude-set) exclude_set "$2"; shift 2 ;;
        *) echo "usage: $0 -m MODULE -p PLATFORM [--exclude PATTERN]... [--exclude-set NAME]..." >&2; exit 2 ;;
    esac
done
[ -n "$module" ] && [ -n "$platform" ] || { echo "usage: $0 -m MODULE -p PLATFORM ..." >&2; exit 2; }

# shellcheck source=test-env.sh
. "$ROOT/scripts/test-env.sh"

task="link$(printf '%s' "${platform:0:1}" | tr '[:lower:]' '[:upper:]')${platform:1}TestDebug"
KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin task ":$module:$task"
binary=$(find "build/tasks/_${module}_${task}" -maxdepth 1 -name '*.kexe' -type f | head -1)
[ -n "$binary" ] || { echo "no test binary in build/tasks/_${module}_${task}" >&2; exit 1; }
binary="$ROOT/$binary"

filter="*"
if [ ${#excludes[@]} -gt 0 ]; then
    # The filter is `positive-negative`, so a "-" inside a test name becomes the "?" wildcard.
    negative=$(IFS=:; echo "${excludes[*]}" | sed 's/-/?/g')
    filter="*-$negative"
fi
echo "== $module $platform: --ktest_filter=$filter"

cd "$ROOT/$module"
case "$platform" in
    iosSimulator*|tvosSimulator*|watchosSimulator*)
        case "$platform" in
            ios*) family="iPhone" ;;
            tvos*) family="Apple TV" ;;
            watchos*) family="Apple Watch" ;;
        esac
        # A shut-down device of the family, spawned standalone, as the Kotlin Gradle plugin ran them.
        # On a booted device UIScreen reports the screen's frame rate, which a test expects to be absent.
        device=$(xcrun simctl list devices available | grep -E "^[[:space:]]+$family" |
            sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Shutdown).*/\1/p' | head -1)
        if [ -n "$device" ]; then
            exec xcrun simctl spawn --standalone "$device" "$binary" "--ktest_filter=$filter"
        fi
        # Else the first booted device of the family.
        device=$(xcrun simctl list devices | grep -E "^[[:space:]]+$family" |
            sed -n 's/.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -1)
        [ -n "$device" ] || { echo "no available $family simulator" >&2; exit 1; }
        exec xcrun simctl spawn "$device" "$binary" "--ktest_filter=$filter"
        ;;
    *)
        exec "$binary" "--ktest_filter=$filter"
        ;;
esac
