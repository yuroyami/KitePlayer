#!/usr/bin/env bash
#
# Builds the iOS sample app, installs it on a simulator and runs its smoke mode.
#
# The smoke mode (--s1b-smoke) opens the clip bundled in the app, makes a precise seek, plays to
# the end and closes the player. Then it writes Documents/s1b-smoke.json and exits. This script
# checks that record: nine keys, the seek landed, playback ended, frames were decoded, submitted
# and presented, the layer held a picture, and the teardown completed.
#
#   ./scripts/ios-sample-smoke.sh                     # the newest available iPhone simulator
#   ./scripts/ios-sample-smoke.sh --simulator UDID    # a named simulator
#
# Needs testmedia/sync1080p30.mp4 (run ./scripts/testmedia.sh), Xcode and jq. CI runs the same
# script in the iOS sample job.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUNDLE_ID="io.github.yuroyami.kiteplayer.sample.ios"
RESULT_WAIT_SECONDS=120

simulator=""
while [ $# -gt 0 ]; do
    case "$1" in
        --simulator)
            [ $# -ge 2 ] || { echo "usage: $0 [--simulator UDID]" >&2; exit 2; }
            simulator="$2"
            shift 2
            ;;
        *) echo "usage: $0 [--simulator UDID]" >&2; exit 2 ;;
    esac
done

[ -f "$ROOT/testmedia/sync1080p30.mp4" ] || {
    echo "testmedia/sync1080p30.mp4 is missing. Run ./scripts/testmedia.sh first." >&2
    exit 2
}

if [ -z "$simulator" ]; then
    # The newest iOS runtime, then its last iPhone in simctl's order.
    simulator=$(xcrun simctl list devices available -j | jq -r '
        [.devices | to_entries[] | select(.key | test("SimRuntime.iOS-"))
            | .key as $runtime | .value[] | select(.name | startswith("iPhone"))
            | {runtime: ($runtime | sub(".*iOS-"; "") | split("-") | map(tonumber)), udid}]
        | sort_by(.runtime) | last | .udid // empty')
    [ -n "$simulator" ] || { echo "No available iPhone simulator." >&2; exit 2; }
fi
echo "== Simulator $simulator"
xcrun simctl boot "$simulator" 2>/dev/null || true
xcrun simctl bootstatus "$simulator" -b

# The Kotlin Toolchain builds the Kotlin framework and runs xcodebuild on kiteplayer-sample-ios/module.xcodeproj.
echo "== Building the app"
(cd "$ROOT" && KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin build -m kiteplayer-sample-ios -p iosSimulatorArm64)
# The toolchain's xcodebuild run writes the app to bin/<configuration>-<sdk>/ of its task folder.
APP=$(find "$ROOT/build/tasks" -maxdepth 5 -path '*_kiteplayer-sample-ios_*IosSimulatorArm64*/bin/Debug-iphonesimulator/*.app' -type d | head -1)
[ -n "$APP" ] || { echo "FAIL: the build produced no .app under build/tasks" >&2; exit 1; }
echo "== $APP"

# Uninstalling first also removes the result of an earlier run from the app's data container.
echo "== Installing and launching with --s1b-smoke"
xcrun simctl uninstall "$simulator" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$simulator" "$APP"
xcrun simctl launch --terminate-running-process "$simulator" "$BUNDLE_ID" --s1b-smoke

data=$(xcrun simctl get_app_container "$simulator" "$BUNDLE_ID" data)
result="$data/Documents/s1b-smoke.json"
waited=0
while [ ! -s "$result" ] && [ "$waited" -lt "$RESULT_WAIT_SECONDS" ]; do
    sleep 1
    waited=$((waited + 1))
done
[ -s "$result" ] || { echo "FAIL: no smoke result after $RESULT_WAIT_SECONDS seconds" >&2; exit 1; }

echo "== The smoke result, after $waited seconds"
cat "$result"
echo
# The app writes the record to a temporary file and renames it, so a partial record never exists.
if jq -e '
    (keys | sort) == [
        "audioUnderruns", "decodedFrames", "layerImage", "presentedFrames",
        "seekLanded", "seekRequested", "submittedFrames", "teardownCompleted", "terminalState"
    ] and .seekRequested == true and .seekLanded == true and .terminalState == "Ended" and
    (.decodedFrames | type) == "number" and .decodedFrames > 0 and
    (.submittedFrames | type) == "number" and .submittedFrames > 0 and
    (.presentedFrames | type) == "number" and .presentedFrames > 0 and
    .layerImage == true and
    (.audioUnderruns | type) == "number" and .audioUnderruns >= 0 and
    .teardownCompleted == true
' "$result" > /dev/null; then
    echo "ios-sample-smoke.sh: PASS"
else
    echo "ios-sample-smoke.sh: FAIL, the record above does not meet the smoke contract"
    exit 1
fi
