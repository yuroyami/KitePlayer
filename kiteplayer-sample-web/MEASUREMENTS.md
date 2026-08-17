# KV-6: what a 1080p frame costs on wasm

Register item X-01 (KPKMP.md 17.14), the stop gate S6 is entered through. Measured 2026-08-17 in
Chromium via the in-app browser pane, on the same Apple silicon laptop as every other number in
this project.

Run it with:

```bash
./gradlew :kiteplayer-sample-web:wasmJsBrowserDistribution
```

then serve `kiteplayer-sample-web/build/dist/wasmJs/productionExecutable` and open it. The numbers
go to the browser console.

## The result

**The naive path fails by 5 to 7 times. The platform is not the reason.**

| step | Kotlin/wasm through Skiko | the browser's own equivalent |
|---|---|---|
| YUV to RGBA, 1080p, one thread | 50 to 87 ms | 15.6 ms in plain JS |
| get 8.3 MB onto a drawable | 107 to 153 ms | 1.4 ms via `putImageData` |
| draw an already-resident image | 0.15 to 0.17 ms | n/a |
| **naive total** | **about 160 to 240 ms, so 4 to 6 fps** | **about 17 ms, so 58 fps** |

The 30 fps budget is 33.3 ms.

## The three findings, in the order they matter

**1. Drawing is free, so Compose is not the problem.** An already-resident 1080p image blits in
0.17 ms. Whatever is wrong here, "Compose cannot draw video on the web" is not it.

**2. The upload is a per-byte copy, and the size ladder proves it rather than suggesting it.**
`Image.makeRaster` over a Kotlin `ByteArray`, then `toComposeImageBitmap`:

| size | bytes | mean | per byte |
|---|---|---|---|
| 480x270 | 518 KB | 37 to 62 ms | 71 to 120 ns |
| 960x540 | 2.07 MB | 38 to 40 ms | 18.4 to 19.5 ns |
| 1920x1080 | 8.29 MB | 107 to 153 ms | 13.0 to 18.5 ns |

Per byte is flat from 2 MB upward, which is what a bulk copy looks like and not what a fixed
setup cost looks like. It works out to roughly 55 to 85 MB/s. A `memcpy` on this machine is three
orders of magnitude faster, so this is not memory bandwidth: it is the crossing between the Kotlin
GC heap, where a `ByteArray` lives, and Skia's own linear memory. The 480x270 row costs more per
byte than the larger two because at half a megabyte the fixed part still shows.

**3. The Kotlin per-pixel loop is slower than the same loop in JavaScript.** 50 to 87 ms against
15.6 ms for a line-for-line JS mirror measured in the same page. Some of that gap is real work the
JS version avoids, because `Uint8ClampedArray` clamps in hardware where the Kotlin path calls
`coerceIn`, but not a factor of five of it. This is worth knowing before anyone ports a per-pixel
loop to wasm expecting desktop arithmetic to carry over.

## What this does NOT say

- **No end-to-end frame rate was measured.** The frame loop needs `requestAnimationFrame`, and the
  browser pane used here is hidden, so the clock never ticks. The probe reports
  `NOT MEASURED, the frame clock never ticked` rather than a number, and the totals above are the
  sum of separately measured parts, which excludes the compositor exactly as KV-5's desktop
  measurement excluded the GPU composite.
- **No decoded frame was involved.** The frame is synthetic. That is honest for conversion cost,
  which does not depend on pixel values, and would not be honest for a decode measurement.
- **No fix is proven.** That a Skiko path exists which avoids the Kotlin heap crossing is the
  obvious next question and it is NOT answered here. It is X-11's first job and it is a named
  risk, not a settled plan.
- **The host was not idle.** Gradle and webpack ran during some samples, which is why every number
  is a range. Load average was between 2.7 and 3.3 for most of them. One convert sample came in at
  10.15 ms against a typical 50 to 87, and it is reported as the outlier it is rather than quoted
  as the number, because nothing distinguishes it except the machine being briefly quiet.
- **Cold matters and is separate.** A first load pays wasm tier-up: convert measured 49.6 ms cold
  against runs that later settled lower on the same build. Startup cost is a real number for a web
  player and nobody has budgeted it yet.

## What it means for S6

The stage continues, but two register items are now constrained rather than open:

- **X-09** cannot convert with a Kotlin per-pixel loop on wasm. FFmpeg's own `sws_scale` is already
  being compiled for wasm by X-02 and lives on the correct side of the memory boundary.
- **X-11** cannot build a Skia raster from a Kotlin `ByteArray` per frame. The pixels must reach
  the drawable without crossing the Kotlin GC heap, and proving such a path exists is the first
  thing X-11 does, before any renderer is written.
