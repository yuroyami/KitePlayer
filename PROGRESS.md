# Progress

What is built, what is proven, and what is not. Written 2026-08-08.

The rule for this file: a row says "works" only when something can be run to check it. Everything
else says what it actually is.

## Milestones

Milestones are defined in KITEPLAYER.md section 19.

| # | Milestone | State |
|---|---|---|
| M0 | Repository scaffolded to family standard | done, with gaps listed below |
| M1 | Engine core with fake everything | partly done: the timing core is complete and tested, the core loop is not written |
| M2 | KiteCodec playback changes | not started. Specified in KITEPLAYER.md section 16 |
| M3 | Audio only playback on macOS | **done and verified** |
| M4 | Video playback, tier 0 renderer | not started |
| M5 | Metal renderer and VideoToolbox hardware decode | not started |
| M6 | Subtitles | parsing started, no rendering |
| M7 | Compose surface and sample application | not started |
| M8 | JNI bridge, then Android, then JVM desktop | not started. Specified in KITEPLAYER.md section 15 |

## What runs

```bash
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
./kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe testmedia/sync1080p30.mp4
```

That decodes a file's AAC track through KiteCodec, plays it through CoreAudio, and prints the
position from the audio clock. Measured on this machine:

| Measurement | Result |
|---|---|
| 10 second clip, start to finish | 469 chunks, 480 256 sample frames, 0 underruns |
| Drift over 3 minutes of continuous playback | 0 ms, measured against the monotonic clock |
| Buffer occupancy while playing | steady at 200 ms |
| Missing file, and a file that is not media | one sentence each, no stack trace |
| A file with no audio track | reported and refused cleanly |

The zero drift figure is the one that matters. The audio clock is not counted from samples submitted:
it is anchored to the instant CoreAudio says a specific frame becomes audible. That is why it tracks
real time exactly instead of accumulating error, and it is the difference between this design and one
that estimates the device latency.

## Tests

157 tests, no failures.

| Module and target | Tests | Covers |
|---|---|---|
| `kiteplayer-core`, JVM | 75 | the whole timing core, in virtual time |
| `kiteplayer-core`, macOS arm64 | 75 | the same 75, compiled natively |
| `kiteplayer-output`, macOS arm64 | 7 | the real audio device |

The engine's 75 tests running identically on the JVM and on Kotlin/Native is the first evidence for
the claim the library is built on: the behaviour is defined once and is the same everywhere.

The seven device tests are worth calling out. They open the real default output, play a tone, and
assert that the deadline CoreAudio reports lands slightly ahead of now on the engine's own clock. If
those two used different time bases, that assertion would fail by a wide margin, and nothing else in
the player would be trustworthy.

```bash
./gradlew :kiteplayer-core:jvmTest :kiteplayer-core:macosArm64Test :kiteplayer-output:macosArm64Test
```

## Module state

| Module | State |
|---|---|
| `kiteplayer-core` | timing core complete and tested: typed timestamps and generations, the clock, the synchronisation law, the frame duration estimator, the packet and frame queues, the audio ring, the seek request merge, the audio playback path. The public API surface, the four service interfaces and the state types are written. The core loop that ties them together is not. |
| `kiteplayer-output` | CoreAudio sink, complete and tested against hardware. No video renderer yet. |
| `kiteplayer-ffmpeg` | audio only, one pass, no seeking, because that is what KiteCodec currently allows. See below. |
| `kiteplayer-subtitles` | SubRip parser with the tolerances real files need. No layout and no rendering yet. |
| `kiteplayer-sample` | a CLI that plays a file's audio. |
| `kiteplayer`, `kiteplayer-compose`, `kiteplayer-libass` | not created. Listed in `settings.gradle.kts` as comments so the intended module graph is visible. |

## What is deliberately not done yet

**Video.** Not started, and it is blocked rather than skipped. KiteCodec's `Frame` exposes pixels only
through `copyPlanesToByteArray`, which is a full copy of every frame: 3.11 MB for 1080p and 24.9 MB
for 4K 10-bit, so 187 MB/s to 1.5 GB/s at 60 fps, plus an allocation per frame. A renderer needs the
plane pointers, and for hardware decoding it needs the surface handle. KITEPLAYER.md section 16.1
specifies the change. Building a video path on the copy would mean writing something that has to be
thrown away.

**Seeking.** Specified in full, tested at the level of request merging and generation filtering, and
not wired, because KiteCodec rejects a seek while a decode pass is running. Section 16.3.

**The core loop.** The design is settled in KITEPLAYER.md section 12.1: level-triggered handlers, one
per concern, called in a documented order every iteration. Every piece it coordinates exists and is
tested. Writing it before the KiteCodec changes land would mean writing it against an API that is
about to change shape.

**Everything except macOS.** The engine compiles for every target Kotlin supports, including js and
wasmJs, because it has no platform dependency at all. What does not exist is a backend for those
targets. Android and JVM desktop additionally need the JNI bridge in KiteCodec, described in
KITEPLAYER.md section 15.

## Known gaps in what is built

| Gap | Detail |
|---|---|
| No ABI dump committed | `abiValidation` is configured on every module. The baseline has not been generated, so nothing catches an accidental signature change yet. |
| No CI | No workflow file. The family's other repositories have one, and this should copy KitePDF's. |
| No docs site | `mkdocs.yml` and the `docs/` page set are not written. |
| No README beyond the minimum | Written against what exists today, and it will need rewriting at every milestone. |
| Audio is limited to two channels | `FFmpegAudioReader` takes the first two channels of a surround stream rather than downmixing. Proper downmixing needs the channel layout and a correct matrix, and belongs in the engine's filter chain. |
| The FFmpeg backend cannot seek or decode video | Both are KiteCodec limits, not design choices. Sections 16.1 and 16.3. |
| `kiteplayer-ffmpeg` resolves KiteCodec from a local publication | KiteCodec is not on Maven Central. Run `./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true` in the KiteCodec checkout first. |

## Next, in order

1. KiteCodec section 16.0, the opt-in low-level surface. Nothing else can proceed without it.
2. KiteCodec sections 16.3 and 16.4: split demuxing from decoding, and expose the decoder flush.
3. KiteCodec section 16.2: put the colour metadata and the frame duration on `FrameInfo`. A renderer
   cannot be correct without the matrix and the range.
4. The core loop, against the new KiteCodec shape.
5. The tier 0 software renderer, and the first video frame on screen.
6. Metal, then VideoToolbox.
