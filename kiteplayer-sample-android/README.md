# kiteplayer-sample-android

An Android app that shows each way to put KitePlayer video on screen, side by side.

## The screens

The app opens on `VisualizerActivity`, the shared visualiser screen. It plays the songs in
`kiteplayer-sample-shared/media`, which the build copies into the APK's assets. To use one song of
your own instead, set `kiteplayer.sample.song` in the root `local.properties`, or set the
`KITEPLAYER_SAMPLE_SONG` environment variable to its path.

The **Other samples** button opens a launcher with four playback screens. Each one shows a
different way to present video:

| Screen | What it uses |
| --- | --- |
| `MainActivity` | The native view, `KitePlayerView`, inflated from `activity_main.xml` |
| `ComposeInteropActivity` | `KitePlayerSurface`, which hosts the same native view in Compose through `AndroidView` |
| `ComposeVideoActivity` | `KiteVideo` with `rememberKiteVideoState(window)`, which draws the video as Compose content. The GPU path bound to the window is available from API 31. |
| `ComposeSwapActivity` | `KitePlayerVideo`, with a button that switches between the native view and the Compose path during playback |

Two more buttons open `MainActivity` with other input:

- **Play a file you pick** opens the system file picker and plays the result through
  `MediaIo.ofUri`.
- **Play the bundled clip from assets** plays a clip from the APK's assets through
  `MediaIo.ofAsset`. The build copies it from `testmedia/sync1080p30.mp4`, so run
  `scripts/testmedia.sh` first.

Each screen owns its own player. The view screen uses the view's default controls, and the Compose
screens use `KitePlayerControls`. Keeping the screens separate shows how each one attaches and
releases its renderer.

## Smoke test

Launch `MainActivity` with `--ez s1c_smoke true` to run a short smoke test. It writes its result as
a JSON object with eleven keys to `files/s1c-smoke.json` in the app's private storage.

## Release builds

`./kotlin build -m kiteplayer-sample-android --variant release` makes a release build. It is not
debuggable, and R8 shrinks it. Without a key, the release APK is not signed and does not install.
The smoke test reads its result through `run-as`, so run it on the debug build.

To sign the release build with your own key:

1. Write `kiteplayer-sample-android/keystore.properties` with `storeFile`, `storePassword`,
   `keyAlias` and `keyPassword`. Git ignores this file.
2. Add `signing: { enabled: true }` under `settings.android` in
   `kiteplayer-sample-android/module.yaml`.

Signing stays off in the committed `module.yaml`. With signing on and no `keystore.properties`,
every `./kotlin` command prints a warning.

## Dependencies

The app depends on `:kiteplayer-compose`, which carries both ways to show video, and on
`:kiteplayer-sample-shared` for the visualiser screen. An ordinary app does not need every one of
these presentation paths. This sample uses them all only to compare them.
