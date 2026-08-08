# Progress

What is built, what is proven, and what is not. Written 2026-08-08.

The rule for this file: a row says "works" only when something can be run to check it. Everything
else says what it actually is.

## Milestones

Milestones are defined in KITEPLAYER.md section 19.

| # | Milestone | State |
|---|---|---|
| M0 | Repository scaffolded to family standard | done, with gaps listed below |
| M1 | Engine core with fake everything | partly done: the timing core and both playback paths are complete and tested, the core loop is not written |
| M2 | KiteCodec playback changes | **done** for the items M3 and M4 need: sections 16.0, 16.2, 16.3, 16.4, 16.5 and part of 16.1 |
| M3 | Audio only playback on macOS | **done and verified** |
| M4 | Video playback with audio in sync, tier 0 renderer, on screen | **done and verified**, including a window |
| M5 | Metal renderer and VideoToolbox hardware decode | not started |
| M6 | Subtitles | parsing started, no rendering |
| M7 | Compose surface and sample application | not started |
| M8 | JNI bridge, then Android, then JVM desktop | not started. Specified in KITEPLAYER.md section 15 |

## What runs

```bash
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
./kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe testmedia/sync1080p30.mp4
```

That demuxes and decodes video and audio as independent stages through KiteCodec, plays the audio
through CoreAudio, schedules the video against the audio clock, and reports what happened. Measured
on this machine:

| Clip | Result |
|---|---|
| 1080p30 h264 with AAC, 10 s | 300 frames decoded, 300 presented, 0 dropped, 0 repeated, 0 underruns, a/v drift steady at 20 ms |
| 720p 59.94 fps, non-integer rate | 480 frames, 0 dropped, 0 repeated, a/v drift 18 ms |
| 4K HEVC 10-bit, no audio, video is the master clock | 180 frames, 0 dropped |
| 640x360 in a window (`--window`) | 200 presented, 66 drawn, 134 superseded by newer frames, 0 underruns, drift 15 ms |
| Audio only, 3 minute soak | 0 ms clock drift, 0 underruns, buffer steady at 200 ms |
| Missing file, a file that is not media, a file with no audio | one sentence each, no stack trace |

The a/v drift sitting at a steady 20 ms and not being corrected is the right behaviour, not a
tolerance being missed. It is inside the 40 ms floor from section 10.5, and correcting inside that
floor is what makes a picture oscillate between early and late.

The zero clock drift figure is the one that matters. The audio clock is not counted from samples
submitted: it is anchored to the instant CoreAudio says a specific frame becomes audible. That is why
it tracks real time exactly instead of accumulating error, and it is the difference between this design
and one that estimates the device latency.

Colour correctness is checked against FFmpeg's own output rather than by eye. Three clips, BT.709,
BT.601 and 10-bit, are decoded through the engine, converted by the tier 0 software path, and compared
per pixel against what the `ffmpeg` command line produces. The mean component error is under 2 units
of 255. That test found a real defect while it was being written: the studio-range chroma scale was
missing, which left every colour about 14 percent undersaturated, and nothing about watching playback
would have revealed it.

## Tests

163 tests, no failures.

| Module and target | Tests | Covers |
|---|---|---|
| `kiteplayer-core`, JVM | 75 | the whole timing core, in virtual time |
| `kiteplayer-core`, macOS arm64 | 75 | the same 75, compiled natively |
| `kiteplayer-output`, macOS arm64 | 7 | the real audio device |
| `kiteplayer-ffmpeg`, macOS arm64 | 6 | real decode, and colour against FFmpeg's own output |

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
| `kiteplayer-ffmpeg` | the real source and decoders: independent per-stream decoding, packet-level demuxing, zero-copy plane access, and the tier 0 software colour conversion. Seeking is implemented in the source but not yet driven by anything. |
| `kiteplayer-subtitles` | SubRip parser with the tolerances real files need. No layout and no rendering yet. |
| `kiteplayer-sample` | a CLI that plays a file's audio. |
| `kiteplayer`, `kiteplayer-compose`, `kiteplayer-libass` | not created. Listed in `settings.gradle.kts` as comments so the intended module graph is visible. |

## What is deliberately not done yet

**A renderer fast enough for 1080p on screen.** There is a window, and it draws real frames: verified
visually against the burned-in timecode of a test pattern. But it is the tier 0 path, converting on the
CPU and building a Core Graphics image per frame, so at 1080p it draws about 9 frames of every 300 and
reports the rest as superseded.

That is the designed behaviour rather than a failure, and the distinction matters. A slow renderer no
longer degrades anything else: with the window open on a 1080p clip, the engine still presents 300 of
300 frames on schedule with zero dropped and zero audio underruns. The renderer names itself as the
bottleneck through its own counter. A Metal renderer that uploads planes as textures and converts in a
shader is the fix, and it is the next platform piece.

**Hardware decode.** The C plumbing for it is specified in KITEPLAYER.md section 16.6 and not written.
Software 4K HEVC works on this machine, and would not on a phone.

**Subtitles on screen.** SubRip parses, and the cue model and the overlay interface exist. Nothing lays
cues out or rasterises them yet.

**Seeking.** Implemented in the source and in the request merging, and not yet driven end to end,
because that belongs to the core loop below.

**The core loop.** The design is settled in KITEPLAYER.md section 12.1: level-triggered handlers, one
per concern, called in a documented order every iteration. Every piece it coordinates now exists and
is tested, and the sample wires them by hand to prove they fit. Turning that wiring into
`PlaybackCore`, with the generation plumbing and the seek state machine attached, is the next engine
piece.

**Everything except macOS.** The engine compiles for every target Kotlin supports, including js and
wasmJs, because it has no platform dependency at all. What does not exist is a backend for those
targets. Android and JVM desktop additionally need the JNI bridge in KiteCodec, described in
KITEPLAYER.md section 15.

## Known gaps in what is built

| Gap | Detail |
|---|---|
| Android and web are compiled but not exercised | Every target compiles, and the engine's tests run on the JVM and on macOS arm64. Nothing runs the engine on Android, js or wasmJs, because no backend exists for them yet. |
| No ABI dump committed | `abiValidation` is configured on every module. The baseline has not been generated, so nothing catches an accidental signature change yet. |
| No CI | No workflow file. The family's other repositories have one, and this should copy KitePDF's. |
| No docs site | `mkdocs.yml` and the `docs/` page set are not written. |
| No README beyond the minimum | Written against what exists today, and it will need rewriting at every milestone. |
| Audio is limited to two channels | `FFmpegAudioReader` takes the first two channels of a surround stream rather than downmixing. Proper downmixing needs the channel layout and a correct matrix, and belongs in the engine's filter chain. |
| Two KiteCodec Gradle plugin tests fail | `kitecodecDslConfiguredAfterKotlinBlockIsSeenByTasks` and `missingLicenseChoiceFailsConfigurationWithInstructions`. Both failed before any change here, verified by stashing. Their assertions look stricter than the output they check. |
| `kiteplayer-ffmpeg` resolves KiteCodec from a local publication | KiteCodec is not on Maven Central. Run `./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true` in the KiteCodec checkout first. |

## Next, in order

1. KiteCodec section 16.0, the opt-in low-level surface. Nothing else can proceed without it.
2. KiteCodec sections 16.3 and 16.4: split demuxing from decoding, and expose the decoder flush.
3. KiteCodec section 16.2: put the colour metadata and the frame duration on `FrameInfo`. A renderer
   cannot be correct without the matrix and the range.
4. The core loop, against the new KiteCodec shape.
5. The tier 0 software renderer, and the first video frame on screen.
6. Metal, then VideoToolbox.
