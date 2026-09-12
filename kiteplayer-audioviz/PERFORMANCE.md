# Audio visualization performance

The normal finishing pass renders the scene once and shares its pixels across the
colour fringe and bloom. Skia uses a filtered bloom pyramid at half, quarter,
eighth and sixteenth resolution. These levels contain about one third of a
full-resolution image in total, instead of four full-resolution images. Small
highlights are filtered between reductions so they still contribute to the halo.
Android 13 and newer share the source through a RenderEffect graph. Earlier
Android versions retain the compatible layer implementation.

Crossfades, wipes, irises and strobe transitions mask layers on the destination
canvas. Full-screen shaders therefore remain on the GPU during these transitions.
Drawings with trails still maintain CPU feedback images. A full-screen shader
snapshot used to seed another drawing's history is limited to 256 pixels on its
longer side; this avoids a full-resolution CPU shader frame during the handoff.

Feedback uses an adaptive pixel budget by default, including when an author uses
VisualizerSurface without supplying RenderQuality. A severe stall reduces the
next frame's area immediately, aiming for 8 ms of CPU feedback work. Moderate
overruns still require five consecutive slow frames. Dynamic feedback can drop
below quarter scale on a large or slow device. The scene's foreground and ground
stay at full resolution. To require a fixed feedback resolution, explicitly pass
`RenderQuality(scale = 1f, dynamic = false)`.

Spectrum, waveform, palette and history textures are uploaded in batches with
reused conversion storage. Feedback canvases are reused and temporary Skia image
and shader wrappers are released after the native builder retains them. Disabling
post-processing skips its layer and texture setup.

Equaliser computes each history row's two colours once per frame and reuses them
across its 48 columns. Its colour-rule endpoints also skip colour-space blending.

## Measurements

Measured on the development Mac on 2026-09-12. These are observations, not a
universal speedup or an Android/iPhone device claim.

The CPU benchmark includes Compose rendering and the finishing pass at 640x360.
Before the change, enabling the default finishing pass increased median frame
time by 7.20x for Alchemy and 7.07x for Twist. After the change those multipliers
were 1.25x and 1.35x. Other builds were active, so compare the paired measurements
within each run rather than treating absolute times across runs as a controlled
hardware benchmark. The Alchemy benchmark deliberately applies the default
finishing pass; Alchemy's own preset normally disables it.

The visible Metal window rendered at 1920x1144 with the default finishing pass.
Each drawing ran for 180 frames, with the first 60 excluded from the statistics:

| Drawing | Median frame interval | 95th percentile | Median CPU scene preparation |
| --- | ---: | ---: | ---: |
| Alchemy | 16.67 ms | 18.50 ms | 0.36 ms |
| Twist | 16.67 ms | 16.83 ms | 8.82 ms |
| Flow Field | 16.67 ms | 18.19 ms | 8.27 ms |
| Cathedral | 16.67 ms | 18.56 ms | 0.27 ms |

With feedback fixed at its original resolution, Twist took 103.37 ms of CPU
scene preparation and a 108.70 ms median frame interval in the same window.
Adaptive feedback reduced CPU preparation about 12x and sustained roughly 60 fps.
That gain includes reducing trail resolution. Its final quality multiplier was
0.45; Flow Field used 0.20. These multiply each drawing's existing buffer scale.
The unadapted Flow Field run did not finish its 180 frames within 90 seconds.

The existing drawing-cost survey passed every drawing and ground guard with the
other build work stopped. Equaliser's fastest batch fell from 24.79 ms to 10.88 ms
per frame, using the same geometry and buffer resolution. These are CPU raster
measurements with a 1280x720 foreground canvas and the preset's existing feedback
scale; they are separate from the visible-window measurements above.

## Reproduce

Run performance measurements alone with the machine idle. The desktop task opens
a temporary window; keep it visible. Timing assertions do not run in the ordinary
unit-test gate.

```sh
./gradlew :kiteplayer-audioviz:audiovizBenchmark
./gradlew :kiteplayer-audioviz:audiovizDesktopBenchmark
./gradlew :kiteplayer-audioviz:audiovizSurvey --tests '*DrawCostTest' --tests '*PostSheetTest'
```

The CPU benchmark reports minimum, median and 95th percentile. The desktop
benchmark reports the actual graphics backend, physical canvas size, frame
cadence, CPU preparation and feedback scale. Frame cadence includes scheduling
and display pacing; it is not a GPU execution timer. A CPU raster benchmark alone
cannot establish interactive GPU performance.

## Validation

Platform Kotlin and the module build file changed, selecting the repository's
Tier 2 gate. The macOS, iOS simulator, JVM, web Node, native sanitizer, Linux native,
Windows link, Android/iOS compile and sample playback checks passed. Audioviz ran
124 JVM tests and 97 iOS simulator tests. The severe-stall regression test also
failed as intended when immediate recovery was temporarily disabled.

The Linux JVM step failed 19 of 91 tests because the published KiteFFmpeg
dependency lacks its linux-arm64 `kitecodec_jni` library, the existing limitation
documented in `CLAUDE.md`. The remaining gate steps were resumed and passed.
Physical Android/iPhone performance and browser execution were not measured.
