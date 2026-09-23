# Local iOS sample host

This Xcode app is a local, private proof host for the static `KitePlayerSample` Kotlin framework. It
contains no decoder or platform media path of its own. Swift creates the controller exported as
`SampleViewControllerKt.sampleViewController()` and UIKit hosts it. The Xcode target uses the separate
Swift module name `KitePlayerSampleHost`, so importing the framework module named `KitePlayerSample`
is unambiguous.

The app opens on the shared Compose screen from `kiteplayer-sample-shared`: the audio visualiser,
playing the sample song, or the song named by `kiteplayer.sample.song` in the root
`local.properties`. The build phase copies the song into the bundle as `sample-song`.
`--s1b-smoke`, `--scenario` and `--uikit` start the
UIKit host instead, which presents through the `KitePlayerUIView` owned by `kiteplayer-view` with
no Compose involved.
`Info.plist` sets `CADisableMinimumFrameDurationOnPhone`, which Compose's view controller needs.
`kiteplayer-phone` is only the deprecated 0.0.2 source-migration umbrella and is not part of this
sample.

Nothing here is an installation or distribution path. KiteFFmpeg and its FFmpeg trees are local,
there is no CocoaPods or downloaded framework, the framework is linked statically and is not embedded,
and no artifact is publicly published. A simulator run does not prove the app works on a physical
iPhone. The unsigned device build below proves linking only; it does not install or run.

Run every command from the KitePlayer repository root. The Xcode build phase runs Gradle offline,
links the debug simulator framework for `iphonesimulator` and the arm64 framework of the build's
configuration for `iphoneos`, rejects every other platform, and copies in the song.

KiteFFmpeg resolves from Maven Central, so no local publication step is needed.

## Build and run the smoke on a simulator

```bash
./scripts/testmedia.sh
./scripts/ios-sample-smoke.sh
```

The script picks the newest available iPhone simulator; pass `--simulator UDID` to name one. It
links the framework, builds the app, installs it, launches it with `--s1b-smoke` and waits for
`Documents/s1b-smoke.json`. The smoke opens the bundled clip, makes a precise seek, plays to the end
and closes the player. CI runs the same script in its iOS job.

The result must contain exactly nine keys, and the script checks each one: the seek landed,
playback ended, frames were decoded, submitted and presented, and the layer held a picture.
`teardownCompleted` is true only after the awaited player teardown, final healthy Idle state and
synchronous renderer close have all completed. The app writes a temporary file, flushes and closes
it, and atomically replaces `s1b-smoke.json`, so the check never accepts a partial record.

## Link the unsigned device app

```bash
./gradlew :kiteplayer-sample:linkReleaseFrameworkIosArm64 \
  -Pkiteffmpeg.ffmpeg.localRoot="$PWD/../KiteFFmpeg/native-libs" --rerun-tasks
xcodebuild \
  -project kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj \
  -scheme KitePlayerSample -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath kiteplayer-sample/iosApp/build/DeviceDerivedData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

The bundle identifier is `io.github.yuroyami.kiteplayer.sample.ios`. The project copies the generated
`testmedia/sync1080p30.mp4` into the app bundle. Generate that fixture before either build. A missing
local FFmpeg tree or local KiteFFmpeg publication is an error; this host never downloads a substitute.
