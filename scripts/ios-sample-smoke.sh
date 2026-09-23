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
PROJECT="$ROOT/kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj"
DERIVED="$ROOT/kiteplayer-sample/iosApp/build/DerivedData"
APP="$DERIVED/Build/Products/Debug-iphonesimulator/KitePlayerSample.app"
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

# The Xcode build phase runs Gradle offline, so the framework is linked here first, online.
echo "== Linking the sample framework"
"$ROOT/gradlew" -p "$ROOT" --console=plain :kiteplayer-sample:linkDebugFrameworkIosSimulatorArm64

echo "== Building the app"
xcodebuild -project "$PROJECT" -scheme KitePlayerSample -configuration Debug \
    -destination "platform=iOS Simulator,id=$simulator" -derivedDataPath "$DERIVED" \
    CODE_SIGNING_ALLOWED=NO build -quiet

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
