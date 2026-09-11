# kiteplayer-sample-android

One comparison APK. It opens on `VisualizerActivity`: the shared visualiser screen, playing the song
set as `kiteplayer.sample.song` in the root `local.properties`. Without one it plays the test clip as
video and says how to set one up. The song is copied into the APK's assets at build time and never
committed. Its Other samples button opens a small launcher with four deliberately separate playback
Activities:

- `MainActivity`: direct native View, inflating
  `io.github.yuroyami.kiteplayer.view.KitePlayerView` from `activity_main.xml`.
- `ComposeInteropActivity`: `KitePlayerSurface`, which hosts that same native View through
  `AndroidView` and installs the mobile renderer adapter.
- `ComposeVideoActivity`: `rememberKiteVideoState(window)` plus `KiteVideo`, with the picture drawn
  as Compose content and the API 31+ Window-bound GPU path available.
- `ComposeSwapActivity`: `KitePlayerVideo`, with a button that swaps between the native view and
  the Compose drawn path while it plays.

Each Activity owns a separate player but shares the sample-only controls and media preparation.
That keeps renderer attachment and teardown visible instead of hiding three different ownership
models behind one generic sample abstraction.

The direct-XML Activity remains the measured assembly/smoke proof. Its view owns the whole Surface
lifecycle; the Activity owns no `SurfaceHolder`, renderer, or `Surface`. Explicitly launching
`.MainActivity --ez s1c_smoke true` still writes the same eleven-key JSON oracle. Debug-signed
release exists solely so the local `run-as` oracle can read that result, and it does not run R8.

The app depends on `:kiteplayer-compose`, which carries both presentation paths, and on
`:kiteplayer-sample-shared` for the front screen. Demonstrating the product split is its purpose;
it is sample glue, not a recommendation that ordinary applications need every presentation artifact.
