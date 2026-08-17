# KV-5: the desktop upload path, measured

Register item **W-05** in KPKMP.md section 17.13, which is KV-5 in 17.9: *"desktop upload path:
one upload per frame; desktop bandwidth makes this cheap; measured anyway."*

This file replaces that assumption with numbers. Short version: the upload is **not** cheap. It
costs about **11.6 ms of CPU per 1080p frame**, and **80% of that is the CPU YUV to RGBA
conversion**, which is exactly the last-resort fallback 17.9's law 2 warns about. It still keeps
up at 1080p30 with room to spare, and the Compose modifiers cost close to nothing.

## The machine

| | |
|---|---|
| Host | macOS 26.6.1, arm64, 8 CPUs |
| JDK | Oracle Java HotSpot 64-Bit Server VM 21.0.9 |
| Heap | default, max 4096 MiB |
| Compose Multiplatform | 1.12.0-rc01, Skiko 0.150.1 |
| Clip | `testmedia/sync1080p30.mp4`, H.264 1920x1080, 30/1 fps, 300 frames, 10.0 s |
| Decode | software (`hardwareDecode=Software` reported by the engine) |
| Date | 2026-08-17 |

**Honest caveat about this host.** The machine was NOT idle. Another build was running on it
throughout, and the report records a host load average of 5.8 to 9.2 on 8 cores in every phase.
The measurement is therefore a *loaded desktop* number, not a best case. It is reported that way
rather than repeated until it looked better.

## Method

The sample application plays the clip on loop and alternates two phases:

1. **no modifiers**: `KiteVideo(state, Modifier.fillMaxSize())`.
2. **compose modifiers**: the same call plus `graphicsLayer { rotationZ; scaleX; scaleY; alpha }`
   and `clip(RoundedCornerShape(28.dp))`, with rotation and scale animated by an infinite
   transition.

Phases alternate rather than run back to back, and the pair repeats 4 times, because this host
drifts and only an interleaved order can separate a modifier cost from that drift. Each phase
collects **320 published frames** (the register asks for at least 300), after a 60 frame warm-up.

**What each number is.**

- **upload total**: the CPU cost of one published frame on the renderer's worker thread, from
  before the first pixel is read to after the `ImageBitmap` exists. This is the same window
  `KiteVideoFrameCost` averages; the run's own cross-check line confirms the two agree.
  - **convert**: `SoftwareConverter.toRgba`, which is KiteCodec's JNI plane copy plus the pure
    Kotlin per-pixel YUV to RGBA loop in `Conversions.kt`.
  - **image build**: `Image.makeRaster(...).toComposeImageBitmap()`, which copies the bytes again
    into Skia storage.
- **draw inner**: KiteVideo's own draw, timed by a `drawWithContent` placed inside the modifier
  chain. Runs once per published frame in both phases.
- **draw outer**: the whole decorated node, timed by a `drawWithContent` placed outside the
  chain. With modifiers on it runs only about 9 times a second, because a `graphicsLayer` replays
  its cached content instead of re-entering the outer draw modifier. Both counters are reported so
  that difference is visible in the data rather than argued about in prose. It is a Compose layer
  behaviour, not a stutter: `draw inner` proves the picture is redrawn once per frame either way.
- **dropped**: `KiteVideoState.supersededFrames` (a frame replaced in the renderer's slot before
  it could be converted) and `failedFrames`, plus the engine's own `droppedFramesLate` and
  `droppedFramesDecode`. Engine counters are sampled on the 1 s stats interval, so their per-phase
  deltas are quantised to that.
- **draw phase timings do not include GPU time.** They are display-list record time on the AWT
  thread. Nothing here can see the Metal composite; the steady 59.7 to 60.0 UI fps is the only
  evidence offered that it fits in the frame budget.

**Instrumentation reused rather than rebuilt.** `KiteVideoFrameCost` and `FrameCostTracker`
(commonMain, landed by expansion 17.4.6 A1 for Android) stay the authority for count, mean and
worst, and `presentedFrames` / `supersededFrames` / `failedFrames` are the existing renderer
counters. The only thing added is `KiteVideoUploadProfiler` in the module's **jvmMain**, because a
p95 needs a distribution and the four-number tracker deliberately keeps none. It records the same
window, split in two halves, and costs one volatile read per frame when no run is active.

## Running it

```bash
# The demo: a window, play/pause, a seek bar, and the modifier toggle.
./gradlew :kiteplayer-sample-desktop:run

# The measurement through Gradle.
./gradlew :kiteplayer-sample-desktop:run \
  -Pkiteplayer.sample.measure -Pkiteplayer.sample.frames=320 -Pkiteplayer.sample.repeats=4

# The measurement with no Gradle daemon in the picture, which is how the numbers below were taken.
CP=$(./gradlew -q :kiteplayer-sample-desktop:printRunClasspath)
java -cp "$CP" \
  -Dkiteplayer.sample.measure=true \
  -Dkiteplayer.sample.frames=320 \
  -Dkiteplayer.sample.repeats=4 \
  -Dkiteplayer.sample.media="$PWD/testmedia/sync1080p30.mp4" \
  io.github.yuroyami.kiteplayer.sample.desktop.MainKt
```

## The numbers

Rounds 3 and 4 are the answer. Round 1 is HotSpot still compiling the conversion loop (18.4 ms
mean, 109 ms max) and round 2 is still settling; by round 3 the numbers are stable and repeat.

### Per-frame upload cost, milliseconds

| Phase | n | mean | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| no modifiers, round 3 | 320 | 11.718 | 11.821 | 13.256 | 16.106 | 25.116 |
| no modifiers, round 4 | 320 | 11.547 | 11.733 | 12.932 | 15.429 | 19.980 |
| compose modifiers, round 3 | 320 | 10.431 | 10.173 | 11.818 | 14.489 | 38.726 |
| compose modifiers, round 4 | 320 | 11.780 | 11.826 | 12.984 | 19.347 | 39.759 |

**Headline: mean 11.6 ms, p95 13.1 ms, with and without modifiers alike.** The modifiers move the
mean by less than the round-to-round drift, which is what physics predicts: the upload runs on the
renderer's worker thread and the modifiers run in the UI's draw and composite.

### Where the 11.6 ms goes

| Half | round 3 mean | round 4 mean | share |
|---|---|---|---|
| convert (JNI plane copy + Kotlin YUV to RGBA) | 9.478 ms | 9.323 ms | about 81% |
| image build (Skia raster + Compose wrapper) | 2.239 ms | 2.224 ms | about 19% |

### Dropped frames, per 320 published frames

| Phase | renderer superseded | renderer failed | engine late | engine decode |
|---|---|---|---|---|
| no modifiers, round 3 | 1 | 0 | 3 | 0 |
| no modifiers, round 4 | 0 | 0 | 0 | 0 |
| compose modifiers, round 3 | 1 | 0 | 2 | 0 |
| compose modifiers, round 4 | 1 | 0 | 1 | 0 |

Across all 8 phases (2560 published frames) the worst phase dropped 6 frames and no phase failed
one. **The modifiers do not cause drops.**

### What the modifiers actually cost

| Phase | draw inner n | draw inner mean | draw inner p95 | UI frame rate |
|---|---|---|---|---|
| no modifiers, round 3 | 365 | 0.019 ms | 0.038 ms | 59.7 fps |
| compose modifiers, round 3 | 320 | 0.032 ms | 0.055 ms | 60.0 fps |
| no modifiers, round 4 | 364 | 0.022 ms | 0.042 ms | 60.0 fps |
| compose modifiers, round 4 | 319 | 0.025 ms | 0.053 ms | 59.9 fps |

About **10 microseconds per frame** more CPU in the draw, and the window holds 60 fps either way.
The GPU cost of the layer, the alpha and the rounded clip is real but invisible to this
instrument; it is not large enough to cost a single vsync at this size.

### Publish rate

Every phase publishes about 28.7 fps against a 30 fps source. That is the loop restart, not a
drop: the clip is 10 s long, so each phase crosses a `LoopMode.One` boundary once or twice and each
boundary costs a fraction of a second. `decoded` and `published` track each other in every phase.
Checked rather than assumed: a 60 frame phase, short enough to fit inside one pass of the clip,
publishes 60 frames in 1.99 s, which is 30.1 fps with zero drops.

### Garbage collector

Warm phases run 76 to 84 collections in 11 s, costing 45 to 64 ms. The upload path allocates a
fresh plane buffer (about 3.1 MB) and a fresh RGBA buffer (8.29 MB) per frame, so it is generating
roughly **340 MB/s of garbage** at 1080p30. That allocation rate, not the copy bandwidth, is why
the numbers blow up when the host is busy: the two contaminated phases in this dataset are also
the ones with GC and load spikes.

## What this says for the register

1. **KV-5's "desktop bandwidth makes this cheap" is wrong as stated, and the reason is law 2, not
   bandwidth.** The copy is cheap; the *conversion* is not. A pure Kotlin per-pixel YUV to RGBA
   loop over 2.07 million pixels costs about 9.4 ms on this machine. KV-2's YUV image path is
   therefore not a nicety on desktop, it is the single change that would return most of a CPU
   core at 1080p30, and it matters more at 4K where this path would not keep up at all.
2. **KiteVideo on Compose Desktop is viable today.** 11.6 ms per frame at 30 fps is about 35% of
   one core out of eight, and the run held 60 fps with 0 to 6 dropped frames per 320.
3. **17.9's flagship claim holds on desktop.** Clip, alpha, rotation and scale apply to the video
   pixels, at a measured cost of roughly 10 microseconds of CPU per frame and no dropped frames.
   The demo shows it live; the toggle switches it in place.
4. **Not measured here, still open.** GPU composite time (no Metal instrumentation on this path),
   4K, an idle host, Linux and Windows, and the `canDrawCommitFencedFrames = true` claim in
   `ImageBitmaps.jvm.kt`, which is inert today because the desktop pool never marks a frame as
   needing a fence, but is still the wrong thing to declare. W-05's fix text asks for that
   correction and this work did not make it.

## The raw run

Verbatim stdout of the run the tables above come from.

```
=== KV-5 desktop upload measurement (KPKMP 17.13 W-05) ===
host          Mac OS X 26.6.1 aarch64, 8 cpus
jdk           Java HotSpot(TM) 64-Bit Server VM 21.0.9 (Oracle Corporation)
heap          max=4096 MiB
clip          /Users/macbook/StudioProjects/#Kite/KitePlayer/testmedia/sync1080p30.mp4
video         VideoSize(width=1920, height=1080, pixelAspectNumerator=1, pixelAspectDenominator=1)
target        320 published frames per phase, 4 round(s)

PHASE no modifiers, round 1
  window        11.14 s, 320 published frames (28.7 fps), 669 UI frames (60.0 fps)
  upload total   n=319   mean=18.398   p50=16.365   p95=32.788   p99=58.516   max=109.117  (ms)
    convert      n=319   mean=13.975   p50=12.244   p95=27.142   p99=53.827   max=102.690  (ms)
    image build  n=319   mean=4.423    p50=4.040    p95=7.949    p99=13.543   max=19.665  (ms)
  draw outer     n=363   mean=0.089    p50=0.068    p95=0.157    p99=0.311    max=1.016  (ms)
  draw inner     n=363   mean=0.084    p50=0.064    p95=0.151    p99=0.293    max=0.998  (ms)
  dropped       renderer superseded=6 renderer failed=0 engine late=1 engine decode=0 (decoded=353)
  machine       7 gc collections, 50 ms in gc, host load average 9.1

PHASE compose modifiers, round 1
  window        11.23 s, 320 published frames (28.5 fps), 673 UI frames (59.9 fps)
  upload total   n=320   mean=14.065   p50=12.365   p95=23.852   p99=39.523   max=69.181  (ms)
    convert      n=320   mean=10.604   p50=9.147    p95=18.306   p99=28.359   max=59.746  (ms)
    image build  n=320   mean=3.461    p50=2.867    p95=5.565    p99=10.970   max=50.664  (ms)
  draw outer     n=103   mean=0.009    p50=0.006    p95=0.014    p99=0.098    max=0.113  (ms)
  draw inner     n=319   mean=0.072    p50=0.048    p95=0.127    p99=0.195    max=4.540  (ms)
  dropped       renderer superseded=4 renderer failed=0 engine late=4 engine decode=0 (decoded=323)
  machine       8 gc collections, 68 ms in gc, host load average 9.2

PHASE no modifiers, round 2
  window        11.08 s, 320 published frames (28.9 fps), 665 UI frames (60.0 fps)
  upload total   n=320   mean=9.905    p50=9.678    p95=11.037   p99=14.028   max=18.831  (ms)
    convert      n=320   mean=7.867    p50=7.617    p95=9.008    p99=11.340   max=15.599  (ms)
    image build  n=320   mean=2.039    p50=2.003    p95=2.243    p99=3.232    max=3.925  (ms)
  draw outer     n=368   mean=0.028    p50=0.023    p95=0.047    p99=0.069    max=0.702  (ms)
  draw inner     n=368   mean=0.026    p50=0.021    p95=0.044    p99=0.066    max=0.695  (ms)
  dropped       renderer superseded=0 renderer failed=0 engine late=0 engine decode=0 (decoded=318)
  machine       80 gc collections, 47 ms in gc, host load average 8.3

PHASE compose modifiers, round 2
  window        11.00 s, 320 published frames (29.1 fps), 659 UI frames (59.9 fps)
  upload total   n=320   mean=12.100   p50=10.635   p95=18.082   p99=30.925   max=56.558  (ms)
    convert      n=320   mean=9.583    p50=8.338    p95=14.799   p99=26.470   max=53.962  (ms)
    image build  n=320   mean=2.517    p50=2.226    p95=3.967    p99=6.566    max=11.462  (ms)
  draw outer     n=100   mean=0.003    p50=0.003    p95=0.006    p99=0.008    max=0.010  (ms)
  draw inner     n=319   mean=0.040    p50=0.035    p95=0.073    p99=0.121    max=0.154  (ms)
  dropped       renderer superseded=2 renderer failed=0 engine late=1 engine decode=0 (decoded=329)
  machine       52 gc collections, 90 ms in gc, host load average 7.9

PHASE no modifiers, round 3
  window        11.17 s, 320 published frames (28.7 fps), 667 UI frames (59.7 fps)
  upload total   n=320   mean=11.718   p50=11.821   p95=13.256   p99=16.106   max=25.116  (ms)
    convert      n=320   mean=9.478    p50=9.554    p95=10.821   p99=13.224   max=21.506  (ms)
    image build  n=320   mean=2.239    p50=2.202    p95=2.614    p99=2.972    max=3.610  (ms)
  draw outer     n=365   mean=0.020    p50=0.016    p95=0.040    p99=0.048    max=0.133  (ms)
  draw inner     n=365   mean=0.019    p50=0.015    p95=0.038    p99=0.046    max=0.132  (ms)
  dropped       renderer superseded=1 renderer failed=0 engine late=3 engine decode=0 (decoded=321)
  machine       76 gc collections, 55 ms in gc, host load average 6.7

PHASE compose modifiers, round 3
  window        11.11 s, 320 published frames (28.8 fps), 666 UI frames (60.0 fps)
  upload total   n=320   mean=10.431   p50=10.173   p95=11.818   p99=14.489   max=38.726  (ms)
    convert      n=320   mean=8.297    p50=7.965    p95=9.737    p99=11.980   max=35.492  (ms)
    image build  n=320   mean=2.134    p50=2.083    p95=2.657    p99=2.995    max=3.789  (ms)
  draw outer     n=100   mean=0.003    p50=0.002    p95=0.004    p99=0.012    max=0.019  (ms)
  draw inner     n=320   mean=0.032    p50=0.026    p95=0.055    p99=0.068    max=0.102  (ms)
  dropped       renderer superseded=1 renderer failed=0 engine late=2 engine decode=0 (decoded=320)
  machine       80 gc collections, 57 ms in gc, host load average 7.5

PHASE no modifiers, round 4
  window        10.93 s, 320 published frames (29.3 fps), 656 UI frames (60.0 fps)
  upload total   n=320   mean=11.547   p50=11.733   p95=12.932   p99=15.429   max=19.980  (ms)
    convert      n=320   mean=9.323    p50=9.514    p95=10.634   p99=11.018   max=17.252  (ms)
    image build  n=320   mean=2.224    p50=2.163    p95=2.481    p99=3.815    max=10.504  (ms)
  draw outer     n=364   mean=0.023    p50=0.017    p95=0.044    p99=0.052    max=0.339  (ms)
  draw inner     n=364   mean=0.022    p50=0.016    p95=0.042    p99=0.050    max=0.337  (ms)
  dropped       renderer superseded=0 renderer failed=0 engine late=0 engine decode=0 (decoded=323)
  machine       84 gc collections, 64 ms in gc, host load average 7.0

PHASE compose modifiers, round 4
  window        11.15 s, 320 published frames (28.7 fps), 668 UI frames (59.9 fps)
  upload total   n=320   mean=11.780   p50=11.826   p95=12.984   p99=19.347   max=39.759  (ms)
    convert      n=320   mean=9.545    p50=9.567    p95=10.692   p99=16.100   max=35.601  (ms)
    image build  n=320   mean=2.235    p50=2.221    p95=2.544    p99=3.007    max=4.158  (ms)
  draw outer     n=100   mean=0.002    p50=0.002    p95=0.005    p99=0.007    max=0.012  (ms)
  draw inner     n=319   mean=0.025    p50=0.019    p95=0.053    p99=0.063    max=0.065  (ms)
  dropped       renderer superseded=1 renderer failed=0 engine late=1 engine decode=0 (decoded=318)
  machine       80 gc collections, 45 ms in gc, host load average 6.0

cross-check   KiteVideoFrameCost over the whole run: samples=3041 mean=12.827 ms worst=109.143 ms
=== end ===
```

The cross-check line is `KiteVideoFrameCost`, the existing commonMain instrument, over all 3041
frames of the run including both warm-up phases and the JIT-cold first round. Its 12.827 ms mean
sits between the cold rounds and the warm ones, which is what it should be if the new profiler and
the old tracker are measuring the same window.

## W-14's decisive experiment: is SkSL faster than the scalar loop?

Run 2026-08-17, same machine as above, `ShaderBenchmark.kt` in this module. One JVM, identical
synthetic 1080p yuv420p planes, 10 warmup iterations then 60 timed, both paths BT.709 limited
range. Rerun it with:

```
java -cp "$(./gradlew -q :kiteplayer-sample-desktop:printRunClasspath)" \
     io.github.yuroyami.kiteplayer.sample.desktop.ShaderBenchmarkKt
```

| path | mean | p95 |
|---|---|---|
| scalar Kotlin loop (a faithful mirror of `convertPlanarYuv`) | **5.56 ms** | 5.60 ms |
| Skia SkSL into a RASTER surface, including readback | **37.56 ms** | 37.98 ms |

**The shader is 6.7 times SLOWER.** Skia's raster backend runs SkSL through its CPU interpreter,
which is far slower than straight-line Kotlin over a byte array.

What this does and does not settle:

- It KILLS the cheap variant outright: running the shader into a raster surface inside the existing
  `convert` seam, which would have needed no pipeline change. That idea is measured and rejected.
- It does NOT settle the real design, where the shader draws to a GPU-backed surface in the draw
  phase and the pixels never come back to the CPU. That path cannot be measured without first
  making the shared frame-pipeline change W-14's amendment describes, which is the point: the
  cheap probe was supposed to tell us whether that investment is worth starting, and it says the
  answer does not come for free.
- It surfaced a THIRD option nobody had costed. The mirror runs at 5.56 ms while W.4 measured the
  real `SoftwareConverter.toRgba` at about 9.4 ms on the same size of frame. The mirror is not the
  real function (it handles one format, and it reuses its output buffer instead of allocating 8.3
  MB per call), so the gap is not a like-for-like claim. But it is large enough to be worth its own
  measurement: making the existing scalar path cheaper may buy more than a shader would, with no
  architecture change, and it would help Android too.
