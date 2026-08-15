# kiteplayer-sample-android

One comparison APK with a small launcher and three deliberately separate playback Activities:

- `MainActivity`: direct native View, inflating
  `io.github.yuroyami.kiteplayer.view.KitePlayerView` from `activity_main.xml`.
- `ComposeInteropActivity`: `KitePlayerSurface`, which hosts that same native View through
  `AndroidView` and installs the mobile renderer adapter.
- `ComposeVideoActivity`: `rememberKiteVideoState(window)` plus `KiteVideo`, with the picture drawn
  as Compose content and the API 31+ Window-bound GPU path available.

Each Activity owns a separate player but shares the sample-only controls and media preparation.
That keeps renderer attachment and teardown visible instead of hiding three different ownership
models behind one generic sample abstraction.

The direct-XML Activity remains the measured assembly/smoke proof. Its view owns the whole Surface
lifecycle; the Activity owns no `SurfaceHolder`, renderer, or `Surface`. Explicitly launching
`.MainActivity --ez s1c_smoke true` still writes the same eleven-key JSON oracle. Debug-signed
release exists solely so the local `run-as` oracle can read that result; R8 still runs.

The app depends directly on `:kiteplayer-mobile`, `:kiteplayer-compose-interop`, and
`:kiteplayer-compose-video` because demonstrating the product split is its purpose. It is sample
glue, not a recommendation that ordinary applications need all three presentation artifacts.
