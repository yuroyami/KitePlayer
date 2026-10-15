# What a 1080p frame costs on wasm

The web stop gate measurement. Measured in
Chromium via the in-app browser pane, on the same Apple silicon laptop as every other number in
this project.

Run it with:

```bash
./gradlew :kiteplayer-sample-web:wasmJsBrowserDistribution
```

then serve `kiteplayer-sample-web/build/dist/wasmJs/productionExecutable` and open it with
`?probe`; without it the page is the player of the last section. The numbers go to the browser
console. The build copies the codec module beside the page, and the 1080p house clip as
`clip.mp4` when `scripts/testmedia.sh` has made it.

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
  sum of separately measured parts, which excludes the compositor exactly as the desktop
  measurement excluded the GPU composite.
- **No decoded frame was involved.** The frame is synthetic. That is honest for conversion cost,
  which does not depend on pixel values, and would not be honest for a decode measurement.
- **No fix is proven.** That a Skiko path exists which avoids the Kotlin heap crossing is the
  obvious next question and it is NOT answered here. It is the web renderer's first job and it is a named
  risk, not a settled plan.
- **The host was not idle.** Gradle and webpack ran during some samples, which is why every number
  is a range. Load average was between 2.7 and 3.3 for most of them. One convert sample came in at
  10.15 ms against a typical 50 to 87, and it is reported as the outlier it is rather than quoted
  as the number, because nothing distinguishes it except the machine being briefly quiet.
- **Cold matters and is separate.** A first load pays wasm tier-up: convert measured 49.6 ms cold
  against runs that later settled lower on the same build. Startup cost is a real number for a web
  player and nobody has budgeted it yet.

## What it means for the web port

The stage continues, but two pieces of work are now constrained rather than open:

- **The web converter** cannot convert with a Kotlin per-pixel loop on wasm. FFmpeg's own `sws_scale`
  is already compiled for wasm with the codec build and lives on the correct side of the memory boundary.
- **The web renderer** cannot build a Skia raster from a Kotlin `ByteArray` per frame. The pixels must reach
  the drawable without crossing the Kotlin GC heap, and proving such a path exists is the first
  thing the renderer work does, before any renderer is written.

## The Compose path, measured (#125)

Compose 1.13.0-alpha02 lets a page draw WebGL content inside Compose's own WebGL2 context, through
its experimental `rememberWebGLRenderTarget`. `KiteVideo` uses it: the codec module converts each
frame to RGBA in C and copies it into one browser `Uint8Array`, one `texSubImage2D` uploads that
array into a texture of the context, and one shader pass places and scales the picture into a render
target the size of the node, which Compose draws as ordinary content. No `ByteArray` holds a frame
anywhere on that path.

Measured in a cloud container, not on the laptop of the tables above: 4 cores of an Intel Xeon at
2.8 GHz, headless Chromium 1194 driven by Playwright, and no GPU, so WebGL2 ran on SwiftShader, which
does in software what a GPU does. Treat every number here as this machine's, and the ratios as the
finding.

Open the page plain to play `./clip.mp4`, the 1080p house clip, with the library's controls over
the picture; open it with `?probe` for the stop gate, now with the upload ladder beside the raster
one; add `?shape` to clip the playing picture to a rounded rectangle at 70 percent alpha.

### Playing the 1080p house clip

About ten seconds of `sync1080p30.mp4` through `KiteVideo`, play pressed through the controls:

| step, per frame shown | mean | p95 |
|---|---|---|
| onto the drawable: the `texSubImage2D` of 8.3 MB | 1.89 ms | 2.50 ms |
| the same, read inside the renderer in a second run | 1.60 ms | 2.10 ms |
| convert to RGBA in the codec module, with the copy out | 7.75 ms | 10.40 ms |
| both together, `KiteVideoState.frameCost` | 9.37 to 10.04 ms | |

The first frame costs more: 41 ms to convert and 14 ms to upload, while the texture is allocated
and the wasm tiers up. The upload's worst single frame was 38.5 ms in one run. The page reports the
first row itself by timing every large `texSubImage2D` the browser runs; the second row came from a
log inside the renderer that is not in the tree.

So the cost of getting a frame onto the drawable went from 107 to 153 ms on the laptop, and 287 to
353 ms on this machine, to about 2 ms here. The conversion before it is the one the web canvas
already pays.

### The probe's ladder

`?probe` uploads a synthetic frame on every display frame, three warmups and fifteen samples per
size, beside the raster build it replaces. Three runs, the 1080p rows:

| step | run 1 | run 2 | run 3 |
|---|---|---|---|
| raster build from a Kotlin `ByteArray` | 286.91 ms | 353.27 ms | 314.13 ms |
| WebGL upload from a browser array | 20.78 ms | 19.17 ms | 20.87 ms |

Two earlier runs, taken while a Gradle build ran beside them, read 6.2 and 7.4 ms for the upload.
Back to back at the display's rate, 8.3 MB a frame is more than SwiftShader converts before the next
upload, so the call waits on the software GPU, and this row measures SwiftShader's throughput as
much as the call. During playback the uploads come at the clip's 30 a second with a conversion
between them, and the same call measured 1.89 ms. A machine with a GPU is where the probe's row
means what it says.

### What this does NOT say

- **Not measured on a GPU.** The numbers that count are taken on real hardware, the laptop above
  first. SwiftShader makes uploads and draws slower than a GPU does, not faster.
- **Not a frame rate.** In both playback runs more than half of the decoded frames were superseded
  before a display frame took them. On one thread with SwiftShader drawing a 1280 by 720 page, the
  page did not keep up with 30 frames a second, and nothing here says how much of that is decoding,
  the conversion, or the software GPU.
- **The conversion is still the larger cost.** About 7.8 ms of a 33.3 ms frame here. Uploading the
  YUV planes and converting in the shader would remove it, and needs KiteFFmpeg to expose a decoded
  frame's planes on the web, which its public API does not do today.
