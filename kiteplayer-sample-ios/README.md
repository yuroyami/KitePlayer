# iOS sample app

A small iOS app module of the Kotlin Toolchain. Its Kotlin code builds into the `KotlinModules`
framework, and `src/AppDelegate.swift` imports that framework. The app has no media code of its
own. Swift creates the view controller that `SampleViewControllerKt.sampleViewController()`
exports, and UIKit shows it.

This app is for local testing only. It is not published, and it is not a way to install
KitePlayer: there is no CocoaPods setup and no downloadable framework. The framework is linked
statically, not embedded.

## What it shows

The app opens on the shared Compose screen from `kiteplayer-sample-shared`, the audio visualiser.
It plays the songs in `kiteplayer-sample-shared/media`, or only the song set as
`kiteplayer.sample.song` in the root `local.properties`. The build phase copies up to nine songs
into the app bundle, named `sample-song`, then `sample-song-2` up to `sample-song-9`.

These launch arguments start a UIKit screen instead, which shows video through `KitePlayerUIView`
from `kiteplayer-view` without Compose:

- `--s1b-smoke` runs the smoke test described below.
- `--scenario a.mkv b.mp4` plays clips from the app's Documents folder, in order.
- `--uikit` opens the UIKit screen.

`Info.plist` sets `CADisableMinimumFrameDurationOnPhone`, which Compose's view controller needs.

## Before you build

- Run every command from the KitePlayer repository root.
- Run `./scripts/testmedia.sh` once. The app bundle gets a copy of `testmedia/sync1080p30.mp4`.
- The Kotlin Toolchain writes `module.xcodeproj` on the first build. A build phase of that
  project copies the clip and the songs into the app bundle.

KiteFFmpeg comes from Maven Central with FFmpeg inside, so you need no local FFmpeg build.

```bash
./kotlin build -m kiteplayer-sample-ios -p iosSimulatorArm64
./kotlin run -m kiteplayer-sample-ios -p iosSimulatorArm64
```

## Run the smoke test on a simulator

```bash
./scripts/testmedia.sh
./scripts/ios-sample-smoke.sh
```

The script picks the newest iPhone simulator. Pass `--simulator UDID` to choose one. It links the
framework, builds and installs the app, launches it with `--s1b-smoke`, and waits for
`Documents/s1b-smoke.json`. CI runs the same script in its iOS job.

The smoke test opens the bundled clip, makes a precise seek, plays to the end and closes the
player. Its result has exactly nine keys, and the script checks each one: the seek landed,
playback ended, frames were decoded, submitted and presented, and the layer showed a picture.
`teardownCompleted` is true only after the player has closed, reached a healthy Idle state, and
closed its renderer. The app writes the result to a temporary file and then renames it, so the
script never reads half a result.

A simulator run does not show that the app works on a real iPhone.

## Link the app for a device, without signing

```bash
./kotlin build -m kiteplayer-sample-ios -p iosArm64 --variant release
```

The Xcode project sets no development team, so the toolchain builds the app with
`CODE_SIGNING_ALLOWED=NO`. This only checks that the app links for a device. It does not install
or run anything. The bundle identifier is `io.github.yuroyami.kiteplayer.sample.ios`.
