# Local iOS sample host

This Xcode app is a local, private proof host for the static `KitePlayerSample` Kotlin framework. It
contains no decoder or platform media path of its own. Swift creates the controller exported as
`SampleViewControllerKt.sampleViewController()` and UIKit hosts it. The Xcode target uses the separate
Swift module name `KitePlayerSampleHost`, so importing the framework module named `KitePlayerSample`
is unambiguous.

The Kotlin sample consumes `kiteplayer-mobile` for the default iOS backend stack and presents through
the `KitePlayerUIView` owned by `kiteplayer-view`. Compose is not involved. `kiteplayer-phone` is only
the deprecated 0.0.2 source-migration umbrella and is not part of this sample.

Nothing here is an installation or distribution path. KiteCodec and its FFmpeg trees are local,
there is no CocoaPods or downloaded framework, the framework is linked statically and is not embedded,
and no artifact is publicly published. The simulator result is not physical-iPhone qualification or
T3-Full support. The unsigned device build below proves linking only; it does not install or run.

Run every command from the KitePlayer repository root. The Xcode build phase resolves
`../KiteCodec/native-libs` to an absolute path, passes it as `kitecodec.ffmpeg.localRoot`, runs Gradle
offline, maps `iphonesimulator` to the debug simulator framework and `iphoneos` to the release arm64
framework, and rejects every other platform.

Prepare the private phone-target publication first when it is not already present in Maven Local:

```bash
cd ../KiteCodec
./gradlew publishToMavenLocal -Pkitecodec.applePhoneTargetsOnly=true
cd ../KitePlayer
```

## Build and run the named simulator

```bash
./scripts/testmedia.sh
xcrun simctl shutdown 5DBA149A-E990-4197-8A7D-31E97658B568 >/dev/null 2>&1 || :
xcrun simctl boot 5DBA149A-E990-4197-8A7D-31E97658B568
xcrun simctl bootstatus 5DBA149A-E990-4197-8A7D-31E97658B568 -b
xcodebuild \
  -project kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj \
  -scheme KitePlayerSample -configuration Debug \
  -destination 'platform=iOS Simulator,id=5DBA149A-E990-4197-8A7D-31E97658B568' \
  -derivedDataPath kiteplayer-sample/iosApp/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO build
xcrun simctl uninstall 5DBA149A-E990-4197-8A7D-31E97658B568 \
  io.github.yuroyami.kiteplayer.sample.ios || :
xcrun simctl install 5DBA149A-E990-4197-8A7D-31E97658B568 \
  kiteplayer-sample/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/KitePlayerSample.app
xcrun simctl launch --terminate-running-process \
  5DBA149A-E990-4197-8A7D-31E97658B568 \
  io.github.yuroyami.kiteplayer.sample.ios --s1b-smoke

S1B_DATA="$(xcrun simctl get_app_container \
  5DBA149A-E990-4197-8A7D-31E97658B568 \
  io.github.yuroyami.kiteplayer.sample.ios data)"
S1B_RESULT="$S1B_DATA/Documents/s1b-smoke.json"
S1B_TRIES=0
while [ ! -s "$S1B_RESULT" ] && [ "$S1B_TRIES" -lt 60 ]; do
  sleep 1
  S1B_TRIES=$((S1B_TRIES + 1))
done
test -s "$S1B_RESULT"
/usr/bin/jq -e '
  (keys | sort) == [
    "audioUnderruns", "decodedFrames", "layerImage", "presentedFrames",
    "seekLanded", "seekRequested", "submittedFrames", "teardownCompleted", "terminalState"
  ] and .seekRequested == true and
  .seekLanded == true and
  .terminalState == "Ended" and
  (.decodedFrames | type) == "number" and .decodedFrames > 0 and
  (.submittedFrames | type) == "number" and .submittedFrames > 0 and
  (.presentedFrames | type) == "number" and .presentedFrames > 0 and
  .layerImage == true and
  (.audioUnderruns | type) == "number" and .audioUnderruns >= 0 and
  .teardownCompleted == true
' "$S1B_RESULT"
```

The result must contain exactly those nine keys. `teardownCompleted` is true only after the awaited
player teardown, final healthy Idle state and synchronous renderer close have all completed. The app
writes a temporary file, flushes and closes it, and atomically replaces `s1b-smoke.json`, so this check
never accepts a partial record.

## Link the unsigned device app

```bash
./gradlew :kiteplayer-sample:linkReleaseFrameworkIosArm64 \
  -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs" --rerun-tasks
xcodebuild \
  -project kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj \
  -scheme KitePlayerSample -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath kiteplayer-sample/iosApp/build/DeviceDerivedData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

The bundle identifier is `io.github.yuroyami.kiteplayer.sample.ios`. The project copies the generated
`testmedia/sync1080p30.mp4` into the app bundle. Generate that fixture before either build. A missing
local FFmpeg tree or local KiteCodec publication is an error; this host never downloads a substitute.
