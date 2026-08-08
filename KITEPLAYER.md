# KitePlayer PLANMAXXING

This document is the complete build plan for KitePlayer. It is written for the agent that will
implement the library. Follow it top to bottom. Decisions made here are settled. Do not reopen them
unless the code proves them wrong, and if you must deviate, record the deviation and the reason in
`JUDGEMENTCALLS.md`.

Written 2026-08-08 against Kotlin 2.4.10, AGP 9.2.1, Compose Multiplatform 1.12.0-beta02,
coroutines 1.11.0, atomicfu 0.31.0, Dokka 2.2.0, vanniktech publish 0.36.0, FFmpeg 8.0
(see `../KiteVersions/versions.master.toml`).

---

## 1. What KitePlayer is

A media player engine written in Kotlin for Kotlin Multiplatform. It reads a file or a URL, decodes
it, keeps audio and video in sync, draws frames on the screen, plays sound, shows subtitles, seeks,
and reports what it is doing. The engine itself is pure Kotlin in `commonMain`. Only the last
centimetre is platform code: the audio device, the GPU surface, and the hardware decoder.

The one-sentence identity: **KitePlayer is mpv's reliability with ExoPlayer's ergonomics, written in
Kotlin, on top of KiteCodec.**

What a consumer writes:

```kotlin
implementation("io.github.yuroyami:kiteplayer:0.0.1")
```

Plus the KiteCodec Gradle plugin, which supplies the FFmpeg binaries. That is the whole integration.
No CMake. No NDK toolchain files. No Xcode framework embedding step. No per-ABI build variants
written by hand. No JNI glue in the consumer's repository.

### 1.1 Why this library should exist

Today a Kotlin Multiplatform developer who wants video playback has three options, and all three are
bad.

| Option | What goes wrong |
|---|---|
| Wrap the platform player per target (ExoPlayer, AVPlayer, MediaFoundation, GStreamer) | Four different APIs, four different bug sets, four different format support tables. Behaviour differs per platform in ways the app must special-case. Codec coverage is whatever the OS decided. |
| Wrap libmpv or libVLC | Both are excellent players and painful dependencies. You write build scripts per platform, cross-compile the native library per ABI, embed it, then talk to it through a C API. This is the exact work that motivated this library. |
| Use a thin Kotlin wrapper library from the ecosystem | These wrap the platform players, so they inherit problem one, and they usually cover only two targets. |

The gap is a player where the **behaviour** is defined once in Kotlin and is therefore identical on
every target, and where the **integration cost** is one dependency line.

### 1.2 What "the engine is pure Kotlin" means, precisely

FFmpeg does not play media. It reads containers, decodes codecs, filters frames, and writes
containers. Everything between "here is a decoded frame" and "the user sees smooth video with
matching sound" is the player's own work, and it is all logic:

- Reading packets ahead of the decoders, and deciding how far ahead.
- Bounded queues per stream, with backpressure, byte limits and duration limits.
- The master clock, and the choice of what drives it.
- Deciding when each video frame should be shown, and whether to drop it or show it twice.
- The seek state machine, including seeks that arrive while a seek is running.
- Track discovery and selection, and format changes in the middle of a stream.
- Subtitle timing, and which cues are visible for the frame about to be drawn.
- Buffering policy, rebuffering, and end of stream.
- Error recovery: a codec that fails to open, timestamps that are missing or go backwards, a stream
  that appears late, a device that disappears.

That list is the player. It is roughly 70 percent of the work and close to 100 percent of the bugs
in every player that exists. It is also pure computation with no platform dependency, so it belongs
in `commonMain` and can be unit tested on the JVM in milliseconds with a fake clock.

Three things cannot be Kotlin, because no API exists that spans platforms:

| Platform layer | Why it must be per target |
|---|---|
| Audio output | The OS owns the audio device. CoreAudio, AAudio, ALSA or PulseAudio, WASAPI, WebAudio. |
| Video presentation | The GPU is reached through Metal, Vulkan, OpenGL, D3D11 or WebGPU. |
| Hardware decode | VideoToolbox, MediaCodec, VAAPI, D3D11VA, NVDEC. Each hands back a different surface type. |

These three are behind small interfaces (section 9). Each one is a few hundred lines per platform,
not a few thousand, because all policy lives in the engine and the platform code only obeys.

### 1.3 What is honestly not in scope

- KitePlayer is not a codec. Decoding is KiteCodec's job, and through it, FFmpeg's.
- KitePlayer is not a UI. `kiteplayer-compose` gives a surface composable and a default control bar,
  and that is deliberately thin. Applications own their own UI.
- KitePlayer is not a streaming server, a downloader, or a DRM client. Widevine and FairPlay are out
  of scope and will stay out: they require signed platform components that cannot be reimplemented.
- KitePlayer does not aim to beat mpv on picture quality in version 0.1. mpv has libplacebo behind
  it, with tone mapping, high quality scalers and interpolation. Section 13 defines the quality tiers
  and says plainly where each tier lands.

---

## 2. Positioning inside the family

| Library | Covers | Does not cover |
|---|---|---|
| KitePlayer (this) | Playback: sync, timing, seeking, tracks, subtitles, audio and video output, the player API | Codecs, containers, muxing, transcoding |
| KiteCodec | FFmpeg through cinterop: demux, decode, filter, encode, mux, transcode, remux | Playback, output devices, sync, JVM and web targets today |
| KiteAudio | Pure-Kotlin audio codecs, containers, tags and PCM tools on every target including JVM, JS and WASM | Video, hardware playback, capture |
| KiteImage | Pure-Kotlin image decode and encode | Anything moving |

KitePlayer depends on KiteCodec. It does not depend on KiteAudio, but section 12.6 defines an
optional path where KiteAudio decodes audio on targets that KiteCodec cannot reach yet. That path is
what lets the web target play sound before an FFmpeg web build exists.

---

## 3. Family rules that bind this repository

Read these before writing anything.

1. `../KITE.md`. Documentation rules, binding for README, docs pages and KDoc. The rules that bite
   most often: no em dashes anywhere, no porting-journey narration in public documents, provenance
   gets exactly one mention plus the License section, and every claim must be true of committed
   code.
2. `../_kite-docs/STYLE.md`. The section-by-section README template.
3. `../KitePDF/`. The structural template for this repository. KitePDF is the only Kite library that
   already splits into an engine module, a platform renderer module and a Compose module, which is
   exactly KitePlayer's shape. When unsure how to lay out Gradle, CI, docs, Dokka or ABI dumps, copy
   what KitePDF does.
4. `../KiteVersions/versions.master.toml`. Version numbers come from here through the sync script.

Hard rules from the repository owner, non-negotiable:

- Never create a git branch. Work on the default branch.
- Never add a Co-Authored-By trailer to a commit.
- No em dashes in any file, including internal documents and code comments.
- Do not fabricate capabilities in README or POM. `POM_DESCRIPTION` must describe only what is
  implemented at publish time.
- Latest dependency versions are fine, including alpha and beta, matching the master toml.
- Record every judgement call taken without asking in `JUDGEMENTCALLS.md`.

---

## 4. Legal discipline: reference sources

KitePlayer ships under Apache-2.0. This section decides what may be copied, what may only be read,
and what must never be opened. It is the most important section in the plan, because getting it
wrong makes the artifact unshippable rather than merely buggy.

### 4.1 The rule

Designs, algorithms, numeric thresholds, state machine shapes and behavioural rules are facts. They
may be learned from any source and restated. Source code text is expression. It is copyrighted, and
translating it into Kotlin produces a derivative work that inherits the original licence.

So: **read for architecture, never transliterate.**

### 4.2 The three categories

**STUDY ONLY (copyleft: read to understand the design, never copy code text):**

| Reference | Licence | Studied for |
|---|---|---|
| mpv | GPL-2-or-later (parts LGPL-2.1-or-later) | Playloop and state ownership, seek machinery, hwdec framework, VO abstraction, client API design, subtitle pipeline, audio output driver interface |
| VLC | GPL-2-or-later (LGPL-2.1-or-later core) | Clock design, es_out, decoder pacing, and a long list of things not to do |
| QMPlay2 | LGPL-3 | Proof that a player built directly on libav\* works, and its thread and module layout |
| ffplay (FFmpeg fftools) | LGPL-2.1-or-later | The canonical clock, packet queue serial trick, frame queue and sync thresholds |
| libplacebo | LGPL-2.1-or-later | What a correct video render pipeline must do, stage by stage |

Clones live in `vendor/` and are gitignored. If a KitePlayer source file ever reads like a
transliteration of one of these, that file is a defect. Rewrite it from this plan.

**MAY LINK (not copied, linked at runtime, obligations documented):**

| Library | Licence | Role |
|---|---|---|
| FFmpeg libav\* | LGPL-2.1-or-later, or GPL-3 with the GPL flavour | All demuxing and decoding, through KiteCodec |
| libass | ISC | Full ASS subtitle rendering, optional module (section 14) |
| FreeType, HarfBuzz, FriBidi | FTL or GPL-2 dual, MIT, LGPL-2.1 | libass dependencies |

Linking is what KiteCodec already does and it is fine. The licence of the FFmpeg build a consumer
links decides that consumer's obligations, and KiteCodec's Gradle plugin already forces that choice
to be explicit.

**MAY PORT (permissive, direct translation allowed with NOTICE credit):**

| Reference | Licence | Would be used for |
|---|---|---|
| Android Media3 and ExoPlayer | Apache-2.0 | API shape, and specific algorithms if useful, for example its subtitle parsers and its audio timestamp poller |
| Chromium media (if consulted) | BSD-3 | Renderer algorithms, timestamp handling |
| dav1d, libvpx | BSD-2 or BSD-3 | Only if a pure-Kotlin decoder is ever wanted, which it is not for v0.1 |

Media3 is the one large permissive player codebase in existence. It is Apache-2.0, the same licence
as KitePlayer, so it may be translated directly with credit in NOTICE. Use it for the parts where it
is genuinely good: the `Player` API surface, `TrackSelection`, and its subtitle parsers.

### 4.3 Record keeping

`reference/REFERENCES.md` records every reference consulted, with the exact commit hash and the
licence text as of that commit, matching what KiteImage and KiteAudio do. Every ported file names its
permissive source in a header comment. No study-only source is ever named in a code comment as the
origin of an implementation, because that would assert a derivation that does not exist.

---

## 5. What "v0.1 promises everything" means

The owner's instruction is that v0.1 targets every KMP target. That is the right ambition and it is
the reason the architecture puts all policy in `commonMain`. It also collides with a fact: FFmpeg is
a native library, and reaching a target means producing FFmpeg binaries for that target and a way to
call them. That work is real and it is not evenly distributed.

This section is the honest map. Every target keeps a full specification in this plan. The order of
work is chosen so that each step unlocks the largest number of targets.

### 5.1 The reach problem, stated once

KiteCodec today binds FFmpeg through cinterop, which is a Kotlin/Native-only mechanism. So:

| Target family | How KiteCodec is reached | State today |
|---|---|---|
| macOS, Linux, Windows native, iOS | cinterop, direct | Works for macOS arm64, Linux x64, Windows x64. iOS has never been built. |
| Android application, JVM desktop | needs a JNI bridge over the same C helper layer | Does not exist |
| js, wasmJs | needs an Emscripten FFmpeg build, or a different decode backend entirely | Does not exist |

The `androidNativeArm64` and friends klibs that KiteCodec publishes are a trap for a player. A normal
Android application is Kotlin/JVM and cannot depend on them. Section 15 specifies the JNI bridge that
fixes this, and it is the single highest-value piece of work in the whole plan, because one bridge
unlocks Android and JVM desktop at the same time.

### 5.2 Target matrix and what each one needs

| Target | Decode | Audio out | Video out | Hardware decode | Extra work needed |
|---|---|---|---|---|---|
| macOS arm64 (native) | KiteCodec cinterop, works today | CoreAudio AudioUnit | Metal via CAMetalLayer | VideoToolbox | none beyond the engine |
| iOS arm64 and simulator | KiteCodec cinterop, needs an FFmpeg iOS build | CoreAudio AudioUnit, same code as macOS | Metal, same code as macOS | VideoToolbox, same code as macOS | FFmpeg cross-build for the iOS SDKs |
| Android (JVM, arm64 and arm32 and x64) | KiteCodec through the JNI bridge | AAudio, with AudioTrack fallback | Vulkan or OpenGL ES on a SurfaceView | MediaCodec, needs `av_jni_set_java_vm` | JNI bridge, AAR packaging |
| JVM desktop (macOS, Linux, Windows) | KiteCodec through the JNI bridge | CoreAudio, PulseAudio or ALSA, WASAPI | OpenGL through a Compose surface, or Vulkan | VideoToolbox, VAAPI, D3D11VA | JNI bridge, native library packaging in the jar |
| Linux x64 (native) | cinterop, works today | PulseAudio, ALSA fallback | Vulkan, OpenGL fallback | VAAPI | audio and video backends |
| Windows x64 (native) | cinterop, works today | WASAPI | D3D11 | D3D11VA | audio and video backends |
| wasmJs | not FFmpeg. WebCodecs for decode, KiteAudio for some audio | WebAudio | WebGL2 or WebGPU on a canvas | the browser does it | a second decode backend behind the same SPI |
| js | same as wasmJs | WebAudio | WebGL2 | the browser does it | same |

The engine module compiles for every one of these today, including js and wasmJs, because it has no
platform dependency at all (section 7.4). What differs per target is which backend modules exist.
That is the property that makes "one API everywhere" structurally true rather than a promise.

### 5.3 Order of work, and why

1. **Engine in `commonMain`, with a fake clock, fake sink and fake source, fully unit tested.**
   No platform work. This is where correctness is decided, and it is testable on the JVM in
   milliseconds.
2. **macOS arm64 end to end.** The only target where KiteCodec, an audio API, a GPU API and a
   hardware decoder are all reachable and verifiable on the development machine today. First proof
   that the engine plays real media correctly.
3. **KiteCodec playback changes** (section 16), landed as they are needed by step 2.
4. **JNI bridge** (section 15). Unlocks Android and JVM desktop together.
5. **Android**, then **JVM desktop**. Same bridge, different output backends.
6. **iOS**. Engine and backends are already written by step 2 because Apple shares CoreAudio and
   Metal. The work is the FFmpeg cross-build.
7. **Linux and Windows native.** Audio and video backends.
8. **Web.** A second decode backend behind the same SPI.

Steps 1 to 3 are the ones that decide whether the library is any good. Steps 4 to 8 are packaging
and platform plumbing, which is tedious but has a known shape.

---

## 6. Repository and module layout

Modelled on KitePDF, which already separates engine, platform renderer and Compose UI.

```
KitePlayer/
  KITEPLAYER.md            this plan
  JUDGEMENTCALLS.md        decisions taken without asking
  PROGRESS.md              what is done, what is not, updated as work lands
  README.md                written last, and only about what exists
  reference/REFERENCES.md  every reference consulted, with commit and licence
  scripts/testmedia.sh     regenerates the test clips
  vendor/                  reference clones, gitignored
  testmedia/               generated clips, gitignored

  kiteplayer-core/         the engine. commonMain only. every target.
  kiteplayer-ffmpeg/       KiteCodec-backed decode and demux backend
  kiteplayer-output/       audio sinks and video renderers, per platform
  kiteplayer-subtitles/    pure-Kotlin subtitle parsing and layout
  kiteplayer-libass/       optional libass renderer, cinterop and JNI
  kiteplayer/              umbrella artifact: core + ffmpeg + output + subtitles
  kiteplayer-compose/      Compose Multiplatform surface and default controls
  kiteplayer-sample/       CLI player and a Compose demo application
```

### 6.1 What each module may depend on

| Module | Depends on | Targets |
|---|---|---|
| `kiteplayer-core` | kotlinx-coroutines, kotlinx-atomicfu. Nothing else. | all, including js and wasmJs |
| `kiteplayer-subtitles` | `kiteplayer-core` | all |
| `kiteplayer-ffmpeg` | `kiteplayer-core`, `kitecodec-core` | Kotlin/Native targets now, JVM after the bridge |
| `kiteplayer-output` | `kiteplayer-core` | per platform, one source set each |
| `kiteplayer-libass` | `kiteplayer-core` | native and JVM, optional |
| `kiteplayer` | the four above | all |
| `kiteplayer-compose` | `kiteplayer`, Compose Multiplatform | Android, JVM, iOS, macOS, js, wasmJs |
| `kiteplayer-sample` | `kiteplayer-compose` | macOS arm64 first |

The dependency arrow never points into `kiteplayer-core`. The core defines interfaces and the
backends implement them. This is what keeps the engine testable without any native code, and what
makes a second decode backend (WebCodecs, KiteAudio, a platform decoder) a matter of implementing
three interfaces.

### 6.2 Rule on module size

A source file that passes roughly 500 lines is a signal that it holds more than one responsibility.
The engine is built from small units with named boundaries, because the failure mode of every player
in existence is one enormous file where playback state, timing and I/O are tangled. ffplay is one
file of about 3900 lines. mpv splits the same work across a directory and is far easier to reason
about. KitePlayer follows mpv, harder.

---

## 7. Architecture: ownership, threading and state

This is the section that decides whether KitePlayer is stable. Everything else is detail.

### 7.1 The single rule

**One coroutine owns all mutable playback state. Everything else sends it messages and reads
immutable snapshots.**

mpv is the most reliable player in wide use, and the reason is not clever algorithms. It is that
`MPContext` is mutated only by the core thread, and every other thread communicates through queues.
Races cannot happen in state that only one thread writes. VLC took the other route, with state spread
across modules behind locks, and spent years on synchronisation bugs, ending in a full clock rewrite
for version 4.0.

KitePlayer expresses this in Kotlin as one actor:

```
             commands (Channel)                       snapshots (StateFlow)
 caller  ------------------------->  PlaybackCore  ------------------------->  caller
                                     (one coroutine,
                                      owns all state)
                                          |
              +---------------------------+---------------------------+
              |               |                       |              |
        DemuxPump      AudioDecoder            VideoDecoder     SubtitleDecoder
      (own thread)     (own thread)            (own thread)      (own thread)
              |               |                       |              |
        PacketQueues  ->  PcmQueue  -> AudioSink   FrameQueue -> VideoScheduler -> Renderer
```

`PlaybackCore` never blocks. It never does I/O. It never decodes. It receives commands, receives
status reports from the workers, updates its state, decides what the workers should do next, and
publishes a snapshot. Every worker is a plain coroutine on a dedicated dispatcher, holding no shared
mutable state beyond the queues, which are the only concurrent objects in the design.

### 7.2 What lives where

| Component | Thread | Owns | Never does |
|---|---|---|---|
| `PlaybackCore` | one core coroutine | `PlaybackState`, generation counter, sync policy, seek state machine, buffering decisions, track selection | I/O, decode, blocking |
| `DemuxPump` | one dispatcher thread | the demuxer cursor, the read position | decode, present |
| `AudioDecoder` | one dispatcher thread | its decoder context | demux, present |
| `VideoDecoder` | one dispatcher thread | its decoder context, hardware device context | demux, audio |
| `SubtitleDecoder` | shares the video dispatcher | its decoder context | anything expensive |
| `VideoScheduler` | one dispatcher thread, or the renderer's thread | the frame queue read side, the presentation decision | decode, demux |
| `AudioSink` | platform callback thread | the device | any player logic |

Only three object types cross thread boundaries: bounded queues, atomics, and immutable snapshots.
There is no lock in the engine other than the ones inside the queues.

### 7.3 Generations: the mechanism that makes seeking correct

Every packet, every frame, every queue and the clock carry an integer `generation`. `PlaybackCore`
bumps it on every seek and every stream reconfiguration. Any item whose generation is not the current
one is discarded without being decoded or shown, wherever it is found.

This is the single most valuable idea in ffplay, where it is called `serial`. It replaces a whole
class of impossible-to-reason-about flush handshakes with one integer comparison. Without it, a seek
means coordinating a flush across four threads and hoping nothing in flight slips through. With it, a
seek is: bump the integer, flush the queues, tell the decoders to flush, reset the clock. Anything
that slips through is filtered by comparison at the next hop.

The engine's seek correctness tests (section 18) all work by asserting that no frame from a previous
generation is ever presented.

### 7.4 The core has no platform dependency at all

`kiteplayer-core` uses `kotlin.time.TimeSource.Monotonic` for time, `kotlinx.coroutines` for
concurrency, and `kotlinx.atomicfu` for atomics. There is no `expect`/`actual` declaration in it.
This is deliberate and has three consequences:

1. It compiles for every KMP target that exists, today, including js and wasmJs.
2. Its entire behaviour is unit testable on the JVM, with a virtual clock, in milliseconds. Player
   bugs are timing bugs, and timing bugs are only reliably testable when the clock is a parameter.
3. A new platform is reached by implementing interfaces, not by adding an `actual` to the engine.

The engine takes its clock as a parameter:

```kotlin
public interface MonotonicClock {
    /** Nanoseconds from an arbitrary fixed origin. Must never go backwards. */
    public fun nanos(): Long
}
```

Production passes the real one. Tests pass one they control. Every timing test in this library is
deterministic because of this one interface.

### 7.5 Kotlin/Native memory model note

The engine runs multi-threaded on Kotlin/Native, which is allowed by the current memory model. The
rules the implementation follows:

- Shared mutable state exists only inside queue implementations and atomics, and is protected there.
- No `@ThreadLocal`, no freezing, no `@SharedImmutable`. Those belong to the old memory model.
- Native pointers wrapped by KiteCodec are moved between threads as opaque handles, never
  dereferenced from two threads at once. Ownership transfers with the handle, and the queues define
  where the transfer happens.
- `Dispatchers.Default` is not used for the worker threads. Each worker gets a single-thread
  dispatcher so that its decoder context is always touched by the same thread, which is what
  libavcodec expects.

---

## 8. The public API

Two layers. A small typed surface that covers what 95 percent of applications need, and one typed
escape hatch for the rest. No stringly-typed property bag, no event queue the caller must drain, no
callback that must not block, no thread the caller must be on.

### 8.1 What libmpv gets wrong, and what this API does instead

libmpv is the API this library is competing with, so the differences are deliberate.

| libmpv | Cost to the embedder | KitePlayer |
|---|---|---|
| Properties are strings with a runtime format tag | Typos compile fine and fail at runtime. No IDE completion. Every value needs manual parsing. | Typed state snapshot, typed commands. Wrong names do not compile. |
| The client must call `mpv_wait_event` and drain, or memory grows | Every embedder writes an event pump thread, and forgetting it is a slow leak | `StateFlow` and `SharedFlow`. Collect them or do not. Nothing accumulates. |
| Property change events coalesce, so one event does not mean one change | Embedders write wrong incremental logic and only find out on slow machines | State is a snapshot, not a delta. There is nothing to accumulate incorrectly. |
| Errors are negative integers with a separate lookup | Error handling degrades to logging a number | Sealed `PlaybackError` hierarchy with the details in fields |
| Diagnostics arrive as log lines to be parsed | Applications regex-match mpv's log to build a stats overlay | `stats: StateFlow<PlaybackStats>` with typed fields |
| The render API demands the caller's GL context on a specific thread, with `advanced_control` subtleties | Hard to embed correctly, and the failure is a deadlock | The renderer owns its own thread and the contract is stated in section 9.4 |
| Two-phase init, `mpv_create` then option setting then `mpv_initialize` | Easy to set an option too late and have it silently ignored | Immutable `PlayerConfig` passed at construction. Options that cannot change at runtime are not settable at runtime. |

The one thing libmpv gets right and this API keeps: **commands are asynchronous and the core never
blocks on the caller.** Every suspending function here suspends the caller's coroutine, not the
engine.

### 8.2 The surface

```kotlin
public interface KitePlayer : AutoCloseable {

    /** Everything that changes rarely. Emits on change, never on a timer. */
    public val state: StateFlow<PlayerSnapshot>

    /** Position and buffered ranges, sampled on a timer while playing. Default every 200 ms. */
    public val progress: StateFlow<Progress>

    /** Diagnostics for an overlay or a bug report. Sampled once a second. */
    public val stats: StateFlow<PlaybackStats>

    /** Things that happened, as opposed to things that are. Replay of 0, no accumulation. */
    public val events: SharedFlow<PlayerEvent>

    /**
     * The current playback position, computed from the master clock at the moment of the call.
     * Cheap, non-suspending, allocation free. Use this from a render loop or a seek bar drag.
     * [progress] exists for the common case where a timer is what you wanted anyway.
     */
    public fun position(): Duration

    /** Loads [media] and returns when the first frame is decoded and ready to present. */
    public suspend fun open(media: MediaItem)

    public fun play()
    public fun pause()

    /** Returns when the seek has completed and the target frame is ready. */
    public suspend fun seek(to: Duration, mode: SeekMode = SeekMode.Precise)

    /** Fire and forget. Coalesces with any seek already queued. Use this while dragging. */
    public fun seekLater(to: Duration, mode: SeekMode = SeekMode.KeyframeThenRefine)

    /** Stops playback, releases the source, returns to Idle. The player stays usable. */
    public suspend fun stop()

    public fun setSpeed(speed: Double)
    public fun setVolume(volume: Float)
    public fun setMuted(muted: Boolean)
    public fun setLoop(mode: LoopMode)

    /** Pass null to disable the given kind of track. */
    public suspend fun selectTrack(kind: TrackKind, track: TrackId?)

    /** Adds an external subtitle file or stream and returns its track id. */
    public suspend fun addSubtitle(source: SubtitleSource): TrackId

    /** A libavfilter chain applied to video before presentation. Null removes it. */
    public suspend fun setVideoFilter(spec: String?)

    /** A libavfilter chain applied to audio before output. Null removes it. */
    public suspend fun setAudioFilter(spec: String?)

    /** Where video is drawn. Attaching or detaching is safe at any time, including while playing. */
    public fun attachRenderer(renderer: VideoRenderer)
    public fun detachRenderer()

    /** The typed escape hatch. Anything not on this interface goes through here. */
    public suspend fun <T> execute(command: PlayerCommand<T>): T

    override fun close()

    public companion object {
        public fun create(config: PlayerConfig = PlayerConfig()): KitePlayer
    }
}
```

### 8.3 The state types

```kotlin
public data class PlayerSnapshot(
    val status: PlaybackStatus,
    val media: MediaItem?,
    /** Null when the duration is genuinely unknown, for example a live stream. */
    val duration: Duration?,
    val videoSize: VideoSize?,
    val tracks: Tracks,
    val speed: Double,
    val volume: Float,
    val muted: Boolean,
    val loop: LoopMode,
    /** Set only when status is Failed. */
    val error: PlaybackError?,
    /** Increments on every seek and every stream reconfiguration. Section 7.3. */
    val generation: Long,
)

public enum class PlaybackStatus { Idle, Opening, Buffering, Playing, Paused, Ended, Failed }

public data class Progress(
    val position: Duration,
    /** How far ahead of [position] the demuxer has read, as a duration of media. */
    val bufferedAhead: Duration,
    /** Contiguous ranges held in the read cache. One entry for a simple linear read. */
    val bufferedRanges: List<ClosedRange<Duration>>,
)

public data class PlaybackStats(
    val decodedVideoFrames: Long,
    val presentedFrames: Long,
    val droppedFrames: Long,
    val repeatedFrames: Long,
    /** Audio clock minus video clock, at the last presented frame. Positive means video is late. */
    val avDrift: Duration,
    val videoDecodeFps: Double,
    val videoQueueDepth: Duration,
    val audioQueueDepth: Duration,
    /** What the audio sink reports as not yet audible. */
    val audioLatency: Duration,
    val hardwareDecode: HwdecStatus,
    val containerBitrate: Long?,
    val syncMode: SyncMode,
)
```

`status` deserves a note. `Buffering` is a distinct status rather than a boolean on `Playing`, because
every application shows a spinner for it and every application gets the boolean version wrong. A
player is `Buffering` when the user has asked for playback and the engine cannot supply it. It is
`Paused` when the user asked for that. The two are never confused.

### 8.4 Errors and warnings

```kotlin
public sealed class PlaybackError {
    public abstract val message: String

    public data class SourceUnavailable(val uri: String, val cause: Throwable?) : PlaybackError()
    public data class NotMedia(val uri: String) : PlaybackError()
    public data class NoPlayableStream(val streams: List<StreamInfo>) : PlaybackError()
    public data class DecoderUnavailable(val codec: String, val kind: TrackKind) : PlaybackError()
    public data class DecoderFailed(val codec: String, val detail: String) : PlaybackError()
    public data class AudioDeviceUnavailable(val detail: String) : PlaybackError()
    public data class RendererFailed(val detail: String) : PlaybackError()
    public data class Internal(val detail: String, val cause: Throwable?) : PlaybackError()
}

public sealed class PlaybackWarning {
    /** A hardware decoder was requested and refused. Playback continues in software. */
    public data class HardwareDecodeUnavailable(val codec: String, val reason: String) : PlaybackWarning()
    /** Frames are being dropped to keep up. */
    public data class FrameDropping(val droppedInLastSecond: Int) : PlaybackWarning()
    /** The audio device restarted or changed. Playback recovered. */
    public data class AudioDeviceChanged(val detail: String) : PlaybackWarning()
    /** Timestamps in the stream are broken and the engine is compensating. */
    public data class BadTimestamps(val detail: String) : PlaybackWarning()
    /** A subtitle track could not be decoded and was disabled. */
    public data class SubtitleTrackFailed(val track: TrackId, val detail: String) : PlaybackWarning()
}
```

The rule that keeps this honest: **a warning never stops playback, and an error always does.** If the
engine can continue, it emits a warning and continues. There is no third category, and no silent
degradation. A user watching a film must never have playback stop because a subtitle track was
malformed, and a developer must never have to discover a silent software-decode fallback by noticing
the fan.

### 8.5 Media items and custom I/O

```kotlin
public data class MediaItem(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val externalSubtitles: List<SubtitleSource> = emptyList(),
    val startPosition: Duration? = null,
    /** Supply this to read the media through your own code instead of FFmpeg's protocols. */
    val io: MediaIo? = null,
)

/**
 * Reads media bytes from anywhere Kotlin can reach: an HTTP client, KiteTorrent, an encrypted
 * store, an Android content URI, an in-memory buffer. Implementations are called from the demux
 * thread only, one call at a time, so they do not need to be thread safe.
 */
public interface MediaIo : AutoCloseable {
    /** Total size in bytes, or null when unknown, for example a live stream. */
    public val size: Long?
    /** True when [seek] is supported. A non-seekable source disables seeking in the player. */
    public val seekable: Boolean
    /** Reads into [into] at [offset] for at most [length] bytes. Returns bytes read, or -1 at end. */
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int
    public suspend fun seek(position: Long)
}
```

`MediaIo` matters more than it looks. It is the difference between a player that plays files and a
player that plays whatever the application already has. It is also how the Android content URI
problem is solved without special-casing Android in the engine, and how KiteTorrent becomes a video
source in about thirty lines. KiteCodec must grow an `AVIOContext` path for it (section 16.5).

### 8.6 Seek modes

```kotlin
public enum class SeekMode {
    /** Land on the nearest keyframe at or before the target. Fastest, and not frame accurate. */
    Keyframe,
    /** Decode forward from the keyframe and land exactly. Slower, always accurate. */
    Precise,
    /**
     * Present the keyframe immediately, then refine to the exact frame in the background.
     * This is what a seek bar drag should use: the picture responds at once and settles correct.
     */
    KeyframeThenRefine,
}
```

`KeyframeThenRefine` is not something mpv or ExoPlayer expose, and it is the single most visible
quality-of-life difference in a scrubbing UI. It is cheap to implement once generations exist,
because it is a keyframe seek followed by a precise seek at the same target within the same
generation.

### 8.7 Configuration

```kotlin
public data class PlayerConfig(
    val syncMode: SyncMode = SyncMode.Auto,
    val hardwareDecode: HwdecPolicy = HwdecPolicy.Auto,
    val buffer: BufferPolicy = BufferPolicy.Default,
    val frameDrop: FrameDropPolicy = FrameDropPolicy.LateOnly,
    val audio: AudioConfig = AudioConfig(),
    val subtitles: SubtitleConfig = SubtitleConfig(),
    val progressInterval: Duration = 200.milliseconds,
    val statsInterval: Duration = 1.seconds,
    val logger: PlayerLogger? = null,
    /** Overrides for testing and for platforms with no default backend. */
    val backends: Backends = Backends.Default,
)

public enum class SyncMode {
    /** Audio drives the clock when there is audio, video otherwise. The right default. */
    Auto,
    AudioMaster,
    VideoMaster,
    /** A wall clock drives playback and audio is resampled to follow it. For display sync later. */
    ExternalMaster,
}

public sealed class HwdecPolicy {
    /** Try hardware, fall back to software silently apart from a warning. */
    public object Auto : HwdecPolicy()
    /** Never use hardware decoding. */
    public object Off : HwdecPolicy()
    /** Fail to open rather than fall back. For applications that must not melt a battery. */
    public object Require : HwdecPolicy()
    /** Only these, in this order. */
    public data class Prefer(val order: List<HwdecKind>) : HwdecPolicy()
}
```

Config is immutable and passed once. Anything genuinely runtime-changeable is a method on the player.
This removes libmpv's whole class of "the option was set after initialize and silently ignored" bugs.

### 8.8 The escape hatch

```kotlin
public sealed interface PlayerCommand<out T> {
    public data class GetChapters(val unused: Unit = Unit) : PlayerCommand<List<Chapter>>
    public data class ScreenshotToBytes(val format: ImageFormat) : PlayerCommand<ByteArray>
    public data class SetAudioDelay(val delay: Duration) : PlayerCommand<Unit>
    public data class SetSubtitleDelay(val delay: Duration) : PlayerCommand<Unit>
    public data class GetStreamMetadata(val track: TrackId) : PlayerCommand<Map<String, String>>
    public data class SetEqualizer(val bands: List<Float>) : PlayerCommand<Unit>
    // grows without breaking the KitePlayer interface
}
```

New capability is a new `PlayerCommand` subtype, which is a source-compatible and
binary-compatible addition. The main interface stays small and the library stays extensible. This is
the same extensibility libmpv buys with stringly-typed properties, without paying in type safety.

---

## 9. The service provider interfaces

These four interfaces are the entire boundary between the engine and the outside world. A new
platform, a new decoder, or a test double is an implementation of them and nothing else.

### 9.1 The source: demuxing

```kotlin
public interface MediaSourceFactory {
    public suspend fun open(media: MediaItem): PlayerMediaSource
}

public interface PlayerMediaSource : AutoCloseable {
    public val streams: List<PlayerStreamInfo>
    public val durationMicros: Long?
    public val seekable: Boolean
    public val metadata: Map<String, String>
    public val chapters: List<Chapter>

    /** Reads the next packet from any selected stream. Null means end of file. */
    public suspend fun readPacket(): PlayerPacket?

    /** Selects which streams [readPacket] returns. Packets for other streams are discarded. */
    public fun selectStreams(indices: Set<Int>)

    /**
     * Seeks the read cursor to at or before [micros], on a keyframe boundary.
     * The caller flushes queues and decoders. This call only moves the cursor.
     */
    public suspend fun seek(micros: Long)
}

public interface PlayerPacket : AutoCloseable {
    public val streamIndex: Int
    public val ptsMicros: Long           // NOPTS when absent
    public val dtsMicros: Long
    public val durationMicros: Long
    public val isKeyframe: Boolean
}
```

The engine, not the source, decides when to seek, how much to read ahead, and what to discard. The
source is a cursor over packets and nothing more. That is what makes the WebCodecs backend, the
KiteAudio backend and the fake test backend all possible behind the same interface.

### 9.2 Decoders

```kotlin
public interface VideoDecoderFactory {
    /** Null when this factory cannot handle the stream. The engine then tries the next one. */
    public suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder?
}

public interface VideoDecoder : AutoCloseable {
    public val hardware: HwdecStatus

    /** Accepts a packet. Returns false when the decoder is full and the caller must drain first. */
    public suspend fun send(packet: PlayerPacket?): Boolean   // null packet signals end of stream

    /** Returns the next decoded frame, or null when more input is needed. */
    public suspend fun receive(): VideoFrame?

    /** Discards all internal state. Called after every seek, before new packets arrive. */
    public suspend fun flush()
}

public interface AudioDecoder : AutoCloseable {
    public val outputFormat: AudioFormat
    public suspend fun send(packet: PlayerPacket?): Boolean
    public suspend fun receive(): AudioBuffer?
    public suspend fun flush()
}
```

The send and receive shape mirrors what libavcodec actually does, including the case where one packet
yields zero frames or several. Any decoder that works differently, for example a browser
`VideoDecoder`, adapts to this shape easily. The reverse is not true, which is why this shape was
chosen.

### 9.3 Frames: the zero-copy contract

```kotlin
/**
 * A decoded video frame. The pixels are NOT in Kotlin memory. This is a handle to whatever the
 * decoder produced: an AVFrame, a CVPixelBuffer, a MediaCodec output buffer index, a VASurface.
 *
 * Ownership: whoever receives a frame must close it. A closed frame is invalid. Frames may be
 * moved between threads. They must not be used from two threads at once.
 */
public interface VideoFrame : AutoCloseable {
    public val ptsMicros: Long
    public val durationMicros: Long
    public val width: Int
    public val height: Int
    public val pixelFormat: PlayerPixelFormat
    public val colorSpace: ColorSpaceInfo
    /** Set when the frame lives in GPU or hardware memory and must be handled by a matching renderer. */
    public val hardware: HwSurfaceKind?
    /** Increments when the decoder output configuration changed. Section 7.3. */
    public val generation: Long
}
```

This interface deliberately has no way to read pixels. Reading pixels is a renderer's job, and a
renderer is matched to the decoder that produced the frame, so it knows how. A 1080p frame in
`yuv420p` is 3.1 MB. Copying it into a Kotlin `ByteArray` at 60 frames per second is 186 MB per
second of pure waste, plus an allocation per frame. KiteCodec's current `copyPlanesToByteArray` is
exactly that path, which is correct for thumbnails and wrong for playback. Section 16.1 specifies
what KiteCodec must add.

An escape hatch exists for the cases that genuinely need pixels, for example a screenshot or a
software renderer of last resort:

```kotlin
public interface SoftwareReadableFrame : VideoFrame {
    public val planeCount: Int
    public fun planeStride(index: Int): Int
    /** Copies plane [index] into [into]. This is a real copy and it is not for the render path. */
    public fun copyPlane(index: Int, into: ByteArray, offset: Int)
}
```

### 9.4 The audio sink

The audio sink is the most safety-critical interface in the library, for two reasons. It runs on a
real-time thread that must never block, and the master clock is derived from what it reports.

**The model is pull, everywhere.** The sink asks the engine for samples. It does not accept them.
Platforms split into two groups, and standardising on pull is what keeps the clock one shape:

| Platform group | Native shape | How it is reached |
|---|---|---|
| CoreAudio AudioUnit, AAudio with a data callback, WASAPI event driven, WebAudio | pull, the device calls you | directly |
| ALSA read-write mode, PulseAudio, JVM `SourceDataLine`, Android `AudioTrack` blocking write | push, you write and it blocks | one writer coroutine on a dedicated dispatcher turns "free frames available" into a pull |

```kotlin
public interface AudioSink : AutoCloseable {

    /**
     * Opens the device. The returned format is what the device actually accepted, which may
     * differ from [request] in sample rate, sample format or channel layout. The engine rebuilds
     * its resampler to match.
     *
     * A sink must never resample, remix or change tempo. Those belong to the engine's filter
     * chain, where they are testable and where their latency is known.
     */
    public fun open(request: AudioFormat, render: AudioRenderCallback): NegotiatedAudioFormat

    public fun start()

    /** Stops and discards everything unplayed. This is the seek path. */
    public fun stop()

    /** Plays out what is already queued, then stops. This is the end-of-file path. */
    public fun drain()

    /**
     * Pauses without discarding. Returns false when the platform cannot do this, in which case
     * the engine emulates a pause with [stop] and accepts the small restart cost.
     */
    public fun setPaused(paused: Boolean): Boolean

    /** The device's own buffer size in frames. Sizes the engine's soft queue. */
    public val deviceBufferFrames: Int

    /**
     * Nanoseconds of audio handed over but not yet audible, including everything inside the OS
     * and the hardware.
     *
     * For a pull sink: max(0, lastDeadlineNanos - now) plus the engine's own queued frames.
     * For a push sink: what the platform reports, plus the engine's own queued frames.
     */
    public fun delayNanos(): Long

    /** How much the engine may trust [delayNanos]. See the table below. */
    public val latencyQuality: LatencyQuality

    /** Device loss, underrun, format change. The engine reacts; the sink only reports. */
    public val events: Flow<AudioSinkEvent>
}

/**
 * Called by the device on its own real-time thread.
 *
 * Contract, and every clause matters:
 * - Not a suspending function. There is no coroutine on this thread.
 * - Must not allocate, must not take a contended lock, must not log, must not throw.
 * - Reads from a preallocated single-producer single-consumer ring with a try-lock. On
 *   contention it writes silence and returns, rather than blocking the device.
 * - [deadlineNanos] is when the LAST frame of this buffer becomes audible, on the engine's
 *   monotonic clock. It is what the audio clock is anchored to.
 *
 * @return the number of frames written. Fewer than [frames] means the rest is silence.
 */
public fun interface AudioRenderCallback {
    public fun onRender(destination: AudioFrameBuffers, frames: Int, deadlineNanos: Long): Int
}

public enum class LatencyQuality {
    /** The platform reports a real measured figure. Normal sync tolerances apply. */
    Exact,

    /** A figure that needs low-pass filtering before use. Tolerances widen. */
    Estimated,

    /** No usable figure. The engine counts frames and applies a configured nominal latency. */
    Unreliable,
}
```

Which platform lands in which quality band, from what each API actually provides:

| Quality | Platforms |
|---|---|
| `Exact` | WASAPI (`IAudioClock` plus QPC), CoreAudio AUHAL (`AudioTimeStamp` plus the summed device latency properties), PipeWire (`pw_time`), AAudio (`getTimestamp`), ALSA without the rate plugin |
| `Estimated` | PulseAudio, Android `AudioTrack` after roughly 20 stable `getTimestamp` samples, JACK |
| `Unreliable` | `AudioTrack` before stabilisation, IEC61937 passthrough, OpenSL ES (a constant from init), WebAudio (`baseLatency` plus `outputLatency`, quantised to 128-frame render quanta and sometimes reported as zero) |

The engine's response to each band is specified in section 10.6. What matters here is that the
uncertainty is declared in the type, so it cannot be forgotten. Every player that hard-codes a
latency guess ships a fixed audio delay that nothing ever corrects, and ffplay is the proof: it
assumes the device has exactly two buffer periods, which is wrong on most backends and produces
a constant offset of tens to hundreds of milliseconds.

**The soft queue.** Between the engine's filter chain and the sink sits one ring buffer, owned by
the engine, sized `max(deviceBufferFrames, 0.2 * sampleRate)` frames. It exists so that the
device's period size is invisible to the rest of the player, and it supplies the second term of
`delayNanos`. It is the only lock-free structure in the engine, it is single producer single
consumer, and the render callback is the only code that touches its read side.

**Never in the callback.** Resampling, channel remixing, tempo change, A/V correction, format
negotiation and allocation all happen in the engine, on a normal coroutine. ffplay does the
opposite: `audio_decode_frame`, `synchronize_audio`, `swr_init` and `av_fast_malloc` all run inside
the device callback, and the busy-wait workaround in its Windows path is the visible scar from it.

### 9.5 The video renderer

```kotlin
/**
 * Draws frames. The renderer owns its thread and its GPU context. The engine never touches a
 * graphics API and never assumes it is on any particular thread.
 */
public interface VideoRenderer : AutoCloseable {
    /** Hardware surface kinds this renderer can present without a download. */
    public fun supportedHardwareSurfaces(): Set<HwSurfaceKind>

    /**
     * Presents [frame] at [targetNanos] on the engine's monotonic clock. The renderer takes
     * ownership of the frame and closes it, including on failure. Returning false means the frame
     * was not presented, and the engine counts it as dropped.
     */
    public suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean

    /** Display refresh interval in nanoseconds, or null when the platform does not report it. */
    public fun vsyncIntervalNanos(): Long?

    public fun setViewport(width: Int, height: Int, scale: Float)

    /** Composited above the video. Replaced wholesale, not diffed. */
    public suspend fun setOverlay(overlay: SubtitleOverlay?)

    public val events: Flow<RendererEvent>
}
```

The renderer contract is the piece libmpv makes hardest, so the rules are stated plainly:

1. The engine calls `present` from the video scheduler coroutine. The renderer may hand the work
   to its own thread and return immediately, or do it inline. Either is correct.
2. The renderer owns the frame from the moment `present` is called, including on failure.
3. The renderer must never call back into the player synchronously from inside `present`.
4. `vsyncIntervalNanos` is advisory. Returning null costs smoothness on a high refresh display
   and nothing else.
5. Losing a surface is an event, not an exception. Playback continues with frames dropped until a
   surface returns, because audio should keep playing when a window is minimised.
6. **A renderer may be attached and detached at any time, including during playback.** Video
   decoding does not depend on a renderer existing. libmpv requires its render context to exist
   before playback starts, and aborts the process if it is freed in the wrong order. That
   constraint is not reproduced here.
7. The engine never reconfigures a sink or renderer that still owns a displayed frame. It asks
   first and waits, because reconfiguring destroys the current frame and produces either a black
   flash or a use-after-free on every resolution change in an adaptive stream.

---

## 10. The clock and audio/video synchronisation

This is the part users perceive directly. Get it wrong and the picture stutters, lips do not match
speech, or playback slowly drifts out over ten minutes. Get it right and nobody notices, which is
the goal.

### 10.1 Timestamps are typed

Every timestamp and every interval in the engine is a value class or a `Duration`. Never a bare
number.

```kotlin
@JvmInline public value class Pts(public val micros: Long) : Comparable<Pts>
@JvmInline public value class Generation(public val value: Long) : Comparable<Generation>
```

This is not decoration. The single largest source of subtle bugs in mpv's own timing code is that
`delay`, `time_frame`, `video_pts`, `hrseek_pts`, `display_sync_error` and the speed factors are
all naked doubles in mixed units, some of them silently divided by playback speed and some not.
Making the unit part of the type turns that class of bug into a compile error.

### 10.2 The clock object

A clock answers one question: what media timestamp is now. It does not tick and it does not run a
timer.

```kotlin
internal class MediaClock(private val monotonic: MonotonicClock) {
    var speed: Double
    var paused: Boolean
    val generation: Generation

    fun set(pts: Pts, generation: Generation, atSystemNanos: Long = monotonic.nanos())
    fun invalidate()

    /** Null when this clock has no valid reading. Callers must handle it. */
    fun nowOrNull(): Pts?
    fun snapshot(): ClockSnapshot
}
```

Two design points, both deliberate.

**The clock stores a drift, not a base timestamp plus a timer.** Reading is then one addition:

```
mediaNanos = driftNanos + systemNanos - (systemNanos - lastSetNanos) * (1.0 - speed)
```

The trailing term is what makes playback speed work without re-anchoring on every change. At speed
1.0 it is zero. At speed 2.0 it subtracts the extra media time the faster rate has consumed. One
formula covers every speed, and there is no separate scaled accumulator to drift.

**An invalid clock reads as null, not as a sentinel.** ffplay uses `NaN` for this, and it works
only because every single use site has an `isnan` guard. A missing guard fails silently, because
every comparison against `NaN` is false. `Pts?` makes the compiler demand the guard. This is the
one place where copying ffplay exactly would be a mistake.

A clock is invalid in three normal situations: after a seek and before the first frame arrives, at
the start of a stream, and when its generation has been superseded. All three are routine, not
errors.

### 10.3 Which clock is master

| Condition | Master |
|---|---|
| An audio track is selected and its sink reports `Exact` or `Estimated` latency | audio |
| An audio track is selected and the sink reports `Unreliable` | audio, on a counted clock, with widened tolerances (10.6) |
| No audio track, or audio disabled, or the track is cover art | video |
| `SyncMode.ExternalMaster` | a wall clock, with audio resampled to follow it |

Audio is the default master because the ear detects a discontinuity in sound far more readily than
the eye detects a duplicated video frame. So the audio path runs undisturbed and video is adjusted
to match it.

The audio clock is derived, not counted:

```
audioClockMicros = ptsOfLastFrameHandedToTheSink - speed * delayNanos() / 1000
```

Two details that are easy to miss and expensive to get wrong. The sink's delay is device time, so
it must be multiplied by playback speed to become media time. And the anchor instant is the
callback's deadline, not the moment the engine happened to notice, which is why
`AudioRenderCallback` receives `deadlineNanos` at all.

### 10.4 Frame duration is measured, not assumed

The duration of a frame is needed before it can be scheduled, and the obvious method is wrong.

Matroska rounds timestamps to whole milliseconds, and some muxers overshoot by another millisecond
then compensate in the opposite direction. A 23.976 fps file therefore yields alternating
durations of 41 ms and 42 ms. Feed that into a scheduler on a 60 Hz display and the frame counts
alternate 2, 3, 2, 3, which is visible judder that looks exactly like a renderer bug.

The rule:

1. Prefer the next frame's timestamp minus this one's.
2. Reject the result when the two frames are from different generations, when it is not positive,
   or when it exceeds `maxFrameDuration`.
3. `maxFrameDuration` is 10 seconds for containers that declare timestamp discontinuities are
   possible, MPEG-TS above all, and 3600 seconds otherwise.
4. On rejection, fall back to the nominal duration from the container's declared frame rate.
5. Average recent durations that agree within 3.1 ms, and snap to `1 / containerFps` once 16 or
   more samples agree. This is what removes the millisecond rounding.

### 10.5 Deciding when to present a frame

One iteration of the video scheduler:

```
1. If no frame is queued, request a wake-up and return.
2. Discard frames whose generation is not current.
3. lastShown = the frame on screen; next = the frame at the read cursor.
4. nominalDelay = duration of lastShown, by the rules of 10.4.
5. If the master clock is not the video clock, and both clocks read non-null:
      diff = videoClock - masterClock
      syncThreshold = nominalDelay.coerceIn(40.ms, 100.ms)
      if |diff| < maxFrameDuration:                       // note: not the 10 s constant
          diff <= -syncThreshold                       -> delay = max(0, nominalDelay + diff)
          diff >=  syncThreshold && nominalDelay > 100.ms -> delay = nominalDelay + diff
          diff >=  syncThreshold                       -> delay = nominalDelay * 2
          otherwise                                    -> delay = nominalDelay
   else delay = nominalDelay
6. targetNanos = frameTimer + delay
7. If now < targetNanos, request a wake-up at targetNanos and return.
8. frameTimer += delay. If delay > 0 and now - frameTimer > 100 ms, set frameTimer = now.
9. Present, and set the video clock from the presented frame's timestamp.
10. Late drop: if another frame is queued and its own target has also passed, and the policy
    allows it, drop without presenting and count it.
```

Step 8's reset is small and matters a lot. Without it, any stall (a slow seek, a suspended
process, a filter graph rebuild) leaves `frameTimer` far in the past, and the player then presents
a burst of frames trying to catch up, which the viewer sees as a fast-forward glitch.

Step 5's guard uses `maxFrameDuration` and not the 10 second no-sync constant. For a normal
container that means correction is effectively always on, and only discontinuity-prone containers
get the wider guard. Getting this backwards disables sync correction on ordinary files.

Constants, and why each one:

| Constant | Value | Reason |
|---|---|---|
| minimum sync threshold | 40 ms | Below this the correction is smaller than one frame at 24 fps and only causes oscillation. |
| maximum sync threshold | 100 ms | Above this the picture visibly lags the sound. |
| frame duplication threshold | 100 ms | For frames longer than this, extending is better than repeating. |
| frame timer resync | 100 ms | The burst-catch-up guard of step 8. |
| no-sync threshold | 10 s | Used by the audio correction and the early drop, not by step 5. |
| maximum frame duration | 10 s or 3600 s | Container dependent, see 10.4. |
| timestamp unrounding window | 3.1 ms | Matroska millisecond rounding, see 10.4. |

These values are the residue of a decade of bug reports against ffplay and mpv. Port them
verbatim first. Tune later, with evidence.

### 10.6 Frame drop policy

```kotlin
public enum class FrameDropPolicy { Never, LateOnly, LateAndDecode }
```

`LateOnly` is the default and drops at presentation. `LateAndDecode` also drops before decoding,
which is what makes 4K viable on weak hardware. The decode-side drop reads the master clock
through an atomic snapshot, never a lock, and never drops a keyframe, because dropping a keyframe
corrupts everything that follows it.

### 10.7 When the audio sink cannot be trusted

| Sink reports | Engine behaviour |
|---|---|
| `Exact` | Normal thresholds. |
| `Estimated` | The delay figure is low-pass filtered before use. Sync thresholds widen. A single sample can never trigger a resync. Drift is corrected by adjusting video, never by a hard jump. |
| `Unreliable` | The audio clock becomes frames written divided by sample rate, minus a configured nominal latency. A warning is emitted once. Values that are obviously wrong, for example a reported latency above two seconds, are rejected outright. |

What must never happen is the engine believing a wrong latency figure, because then video is
confidently held at the wrong time and nothing detects it.

### 10.8 Audio correction when audio is not master

In `VideoMaster` mode the audio must be stretched or squeezed to follow the video clock. The rules:

- Measure `diff` between the audio clock and the master clock each buffer.
- Maintain an exponential average with coefficient `0.01 ^ (1/20)`, about 0.79433, so a sample's
  weight decays to one percent after twenty buffers.
- Gate correction on the average exceeding a threshold tied to the device buffer duration.
- Size the correction **from the average, not from the instantaneous difference.** ffplay uses the
  raw value here and a single noisy measurement can command its full ten percent stretch. That is
  a defect, not a design choice.
- Bound the total correction. For a resampler-based correction, ten percent. For a display-sync
  style continuous correction, 0.125 percent total with a per-frame slew of one tenth of that,
  which is inaudible.
- Apply it in the resampler, never by dropping or duplicating samples.

### 10.9 Speed change with pitch preserved

Playback at 1.5x must not raise the pitch. The algorithm is WSOLA, and its shape is well
established: a periodic Hann window of about 12 ms with 50 percent overlap, a 40 ms search
interval, a coarse search by decimation followed by quadratic interpolation and a small full
search, an exclusion interval around the previous match to avoid pathological repetition, and a
straight-copy fast path when the speed is close to 1.0. Outside roughly 0.25x to 8x, mute.

The filter reports its own latency in frames so that timestamp correction stays exact. This lives
in the engine's filter chain, in `commonMain`, operating on planar float buffers.

---

## 11. State machines, queues and buffering

### 11.1 Per-stream status, not booleans

Each stream carries an ordered status:

```kotlin
internal enum class StreamStatus { Syncing, Ready, Playing, Draining, Eof }
```

`Syncing` means the stream is filling after a start or a seek. `Ready` means it has enough to
start. `Playing` is self-explanatory. `Draining` means the decoder is finished but buffers are
still being played out. `Eof` means finished.

Playback starts only when **every** selected stream has reached at least `Ready`, and then a
single `restartComplete` latch flips. This one rule prevents three separate bugs that are
otherwise guaranteed: audio starting before the first video frame is visible, video playing
silently after a seek, and audio being clipped at the start of every seek.

The ordering is used in comparisons, and `when` expressions over it are exhaustive, so adding a
state is a compile error at every decision point rather than a silent fall-through.

### 11.2 End of stream is not one flag

There are six distinct conditions, and collapsing them produces either a premature stop or a
hang:

| Condition | Meaning |
|---|---|
| demuxer end | no more packets will be read |
| audio decoder end | the audio decoder has been drained |
| video decoder end | the video decoder has been drained |
| draining | decoders done, sink still playing out |
| sink drained | the audio device has played everything |
| keep open | the engine holds the last frame instead of stopping |

Playback ends only when every selected stream is at `Eof`, every queue is empty, and the audio
sink has drained. And it never ends while paused with a frame on screen, because otherwise pausing
on the final frame stops the player.

End of stream is signalled in band, as a null packet per stream, exactly as libavcodec expects for
its drain protocol. An in-band signal cannot overtake real data and needs no side channel. The
per-decoder "finished" marker holds a generation, not a boolean, so a seek arriving during a drain
cleanly cancels it rather than leaving the pipeline permanently finished.

### 11.3 The queues

| Queue | Holds | Bound | Default |
|---|---|---|---|
| `PacketQueue` per stream | compressed packets | count, bytes, media duration | ready at 25 packets or 1.0 s buffered; soft cap 5 s |
| `FrameQueue` video | decoded frames or hardware surfaces | count | 4 pending plus 1 retained as shown |
| audio soft ring | PCM ready for the device | frames | `max(deviceBufferFrames, 0.2 * sampleRate)` |
| subtitle cue list | parsed cues | count and lookahead | 64 active, 5 s lookahead |
| total read cache | all packet queues | bytes and duration | 32 MB, 30 s |

The retained shown frame is not optional. It serves three purposes at once: redrawing on resize or
unpause without re-decoding, measuring the duration of the frame currently on screen, and
guaranteeing the lifetime of a buffer a zero-copy consumer may still be reading.

Four pending frames rather than ffplay's two, because two leaves no slack for a decode hiccup and
makes every hiccup a visible drop. It is bounded above by the hardware decoder's surface pool,
which on Android MediaCodec can be as few as four buffers in total, so the value is configurable
and the backend may lower it.

### 11.4 Backpressure without deadlock

The naive design stalls the demux pump when any queue is full. That deadlocks on badly interleaved
files: the video queue fills while audio is empty, the pump stops, the audio decoder starves, the
audio clock stops, and the video is never consumed. Every player hits this once.

The rule:

- A per-stream soft limit means "this stream is well buffered" and only feeds the buffering state.
- The pump stalls only on the **total** cap, in bytes and in seconds of the least-buffered stream.
- So a full video queue never stops the pump while audio starves.
- If the total cap is reached while a stream still starves, the file is pathologically interleaved.
  Emit a warning and drop from the tail of the over-buffered stream. A gap in one stream beats a
  frozen player.

### 11.5 Buffering, and why it takes two signals

| Decision | Rule |
|---|---|
| Playback may start | every selected stream at `Ready` or `Eof`, and the initial fill happened while paused |
| Enter `Buffering` | a remembered demuxer underrun **and** a current output underrun |
| Leave `Buffering` | the start rule is satisfied again |

The two-signal rule matters. An output underrun alone can be caused by slow decoding, so acting on
it makes the buffering indicator flicker on every decode hiccup. The demuxer underrun flag is
sticky until the cache fully recovers.

The initial fill must happen with the clock paused. Filling while unpaused drops the first audio
chunk on every seek in a network stream.

### 11.6 Timestamps in the wild

Real files break every assumption. The rules, each of which exists because files like this are
common:

| Situation | Rule |
|---|---|
| No presentation timestamp on a video frame | Use the decoder's best-effort estimate. If still absent, synthesise from the previous frame plus the nominal duration. |
| Presentation timestamps unusable but decode timestamps fine | Switch the whole stream to decode timestamps once the count of presentation problems exceeds the count of decode problems. |
| No timestamp at all on audio | Synthesise from a running counter advanced by each frame's sample count. |
| A jump larger than 0.1 s in audio | Warn once. |
| A jump of 5 s or more | Treat as a stream reset: full pipeline reset at the new position. |
| Container declares discontinuities possible | Tolerate jumps up to 5 s without a reset, and use the 10 s `maxFrameDuration`. |
| Negative start time | Normalise once at the source boundary. The engine's timeline always starts at zero. |

### 11.7 Cover art, still images and sparse streams

An audio file with embedded album art has a video stream of exactly one frame. A slideshow has a
video stream with multi-second frames. Neither must be A/V synced, and treating them as normal
video produces a player that hangs at the end of every MP3.

| Kind | Handling |
|---|---|
| cover art | forced logical end of stream, no precise seek on that stream, never the sync master |
| sparse or still image | a configured display duration, default 5 s, and a different clock choice |
| a video stream that looks like a single image | detected only when it is the first frame and its timestamp is zero, because misclassifying is worse than missing it |

### 11.8 Live and unbounded sources

- `duration` is null and `seekable` is false, so a UI can hide the seek bar.
- The read cache keeps a bounded back buffer, default 20 s, so a small backward seek works.
- Falling behind the live edge by more than a configured amount, default 10 s, drops to the newest
  keyframe and warns, rather than accumulating latency.
- Latency control for a network source measures **seconds of buffered media**, never packet
  counts. ffplay's servo uses packet counts of 2 and 10, which mean wildly different latencies for
  4K video and for low-bitrate audio.

---

## 12. The core loop and the playback pipeline

### 12.1 The loop is level-triggered

`PlaybackCore` is one coroutine on a dedicated single-thread dispatcher. Each iteration:

```kotlin
private suspend fun tick() {
    var wakeAt = Long.MAX_VALUE                 // reset every iteration
    drainCommands()                             // typed commands with a reply Deferred
    handleTrackChanges()
    handleAudioFill()
    handleVideoWrite()
    handlePlaybackRestart()                     // the Ready rendezvous of 11.1
    handlePlaybackTime()
    handleBuffering()
    handleSubtitles()
    handleEof()
    handleLoop()
    handleQueuedSeek()                          // exactly one seek per iteration
    publishSnapshot()
    awaitWork(wakeAt)                           // select on the command channel with a timeout
}
```

Every handler is small, takes no arguments, and is safe to call on every iteration. They are
**level-triggered**: each one reads current state, decides, and possibly asks for an earlier
wake-up. None of them is a transition hook.

This is the single most valuable idea in mpv's design, and it is worth stating why. With
edge-triggered transitions, a condition that becomes true while the player is in the wrong state
is lost forever, and the player wedges. With level-triggered handlers, the condition is simply
noticed on the next pass. No sequence of user actions can produce a stuck state, which is most of
what "stable" means in a player.

The order of the handlers is load-bearing, so it is asserted by a test rather than left in a
comment. mpv's own source notes that looping assumes the queued seek runs before the next decode,
with nothing enforcing it.

**Waking up.** `wakeAt` starts at infinity each iteration and handlers may only lower it. The loop
then waits on the command channel with that timeout. A command that arrives just before the wait
is buffered by the channel rather than lost, which gives for free what mpv needs a sticky
interrupt flag to achieve. No polling, no busy-wait, no lost wake-ups.

**No foreign thread ever touches core state.** Every external interaction is a message with a
reply. libmpv offers the opposite, a lock that parks the core thread so a caller can mutate its
state directly, and pays for it with a documented set of rules about recursive locking and calling
from callbacks. That mechanism is not reproduced.

### 12.2 The loading gate

A single `playbackInitialized` flag guards every handler and every command that touches a decode
chain. It exists because the loop runs during loading, so that commands and state reads keep
working while a file is half open. mpv tests the equivalent flag in around thirty command handlers
because it was added after the fact. Here it is a precondition on the command dispatcher, checked
once.

### 12.3 Open

1. `Open(media)` arrives. Status becomes `Opening`.
2. `MediaSourceFactory.open` runs on the demux dispatcher and lists streams.
3. Track selection picks the default streams: first video, audio by language preference or the
   container default, subtitles only if configured on.
4. Decoders are created from a candidate list. Hardware first when the policy allows, falling back
   to the next candidate on failure, with a `HardwareDecodeUnavailable` warning. A stream whose
   decoders all fail is deselected and playback continues without it. Only when nothing playable
   remains does `open` fail.
5. The audio sink is opened, and its returned format drives the resampler.
6. The pump fills queues while the clock stays paused, until every stream reaches `Ready`.
7. The first video frame is presented, with the clock still stopped.
8. `open` returns. Status is `Paused`.

Returning with the first frame visible and the clock stopped gives an application a poster frame
with no extra API, and makes `play()` instant instead of being the moment the real work starts.

### 12.4 Seek

The request is an immutable value with a pure merge function, so coalescing is unit testable:

```kotlin
internal data class SeekRequest(
    val target: SeekTarget,          // Absolute(Pts), Relative(Duration), Factor(Double), FrameStep(Int)
    val mode: SeekMode,
    val generation: Generation,
) {
    fun merge(next: SeekRequest): SeekRequest
}
```

Merge rules: relative offsets accumulate and take the higher precision of the two; an absolute
target absorbs any pending relative offsets; a frame step or factor target overwrites.

Execution, in this order, and the order is not negotiable:

1. Increment the generation. Publish `Buffering` with the new generation.
2. Tell the pump to stop reading, and wait for its acknowledgement. This is what stops packets of
   the old generation arriving after the flush.
3. Clear every packet queue, the frame queue, and the audio ring.
4. Flush every decoder. After the queues are cleared, never before.
5. Stop the audio sink, discarding what it holds.
6. Invalidate all clocks.
7. Seek the source to the keyframe at or before the target.
8. Restart the pump and the decoders under the new generation.
9. For `Precise` and `KeyframeThenRefine`, discard decoded frames more than 5 ms before the
   target. For `Keyframe`, accept the first frame.
10. Set the clock from the first accepted frame, present it, restore the previous play state, emit
    `SeekCompleted(generation)`.

**Coalescing needs two rules, not one.** Within 0.3 s of the previous seek, a new request waits
until a frame from the previous seek has actually been shown, otherwise holding an arrow key
freezes the picture completely. And a precise seek also waits for `restartComplete`, otherwise a
seek past the end has its end-of-file result overwritten by the next seek and playback never
terminates. mpv needs both halves and so does this.

### 12.5 Overshoot handling

`av_seek_frame` with the backward flag is documented to land at or before the target, but for
containers without an index, MPEG-TS above all, it resolves the seek by byte position and can land
after it. KiteCodec's current answer is to always aim 5 seconds early, which is right for
thumbnails and unacceptable for a player, because every seek then decodes 5 seconds of throwaway
video.

The replacement is a retry ladder: aim at the target; if the first frame is later than the target,
retry with a backoff of 500 ms, then 2 s, then 8 s, then accept. Almost every seek costs one
attempt, and only broken containers pay more.

### 12.6 Format changes mid-stream

When a decoder reports a different output configuration:

1. The frame carries a new generation, so nothing stale is presented.
2. The sink or renderer is reconfigured, **after** checking that it does not still own a displayed
   frame. If it does, wait for it.
3. The relevant event is emitted.
4. The clock is not reset, because the timeline did not change.

Not resetting the clock is the difference between a resolution change nobody notices and one that
produces an audible gap.

### 12.7 Pause and resume

Pause freezes all clocks and pauses the sink, keeping the last frame on screen. It drops no
buffered data, and it does not stop the demux pump, so a paused player keeps buffering.

On resume, `frameTimer` is shifted forward by `now - videoClock.lastSetNanos` and the clock is
re-anchored at its frozen value, so the clock and the presentation schedule move by exactly the
same amount. This two-line relationship is the non-obvious part, and getting it wrong shows as a
skipped or repeated frame on every unpause.

### 12.8 A second decode backend, for the web

The SPI in section 9 contains no FFmpeg, which is what allows a completely different backend where
FFmpeg cannot go yet:

| Web piece | Implementation |
|---|---|
| `PlayerMediaSource` | a pure-Kotlin MP4 and WebM demuxer, or KiteAudio's container readers for audio-only |
| `VideoDecoder` | the browser's WebCodecs `VideoDecoder`, wrapped |
| `AudioDecoder` | the browser's `AudioDecoder`, or KiteAudio |
| `AudioSink` | WebAudio, reporting `Unreliable` latency |
| `VideoRenderer` | WebGL2 on a canvas |

The engine, the sync, the seeking and the whole public API are then identical to every other
target. That is the part of "one API everywhere" a wrapper library structurally cannot promise.
## 13. Video rendering

### 13.1 What "correct" means before "pretty"

A renderer that gets any of the following wrong produces visibly wrong output, and no amount of
scaling quality compensates.

| Must be right | What goes wrong when it is not |
|---|---|
| Stride, meaning the row pitch of each plane | The image skews diagonally. This is the single most common first bug in a new renderer, because `linesize` is almost never equal to width. |
| Colour matrix: BT.601 versus BT.709 versus BT.2020 | Colours shift. Faces go green or magenta. Most visible on saturated reds. |
| Range: limited (16 to 235) versus full (0 to 255) | Blacks turn grey and whites clip, or contrast is crushed. |
| Chroma plane size and siting | Colour bleeds half a pixel, worst on sharp coloured edges. |
| Bit depth handling for 10-bit formats | The image is dark and noisy, or the high bits are dropped. |
| Alpha handling of the subtitle overlay | Subtitle edges get dark halos when alpha is not premultiplied consistently. |

All six are metadata questions, answered by fields already on the frame. `ColorSpaceInfo` on
`VideoFrame` carries matrix, primaries, transfer, range and chroma location, and the renderer is
required to honour all five or to state which it ignores.

### 13.2 Two renderer tiers, and why the boring one comes first

**Tier 0, the software path.** Convert the frame to RGBA and hand it to whatever the platform can
already draw: a Compose `ImageBitmap`, a Skia surface, a canvas. The conversion uses
libswscale, which KiteCodec already links and already wraps in `FilterGraph`.

This is slow. At 1080p it costs roughly 3 to 6 ms per frame of CPU, and it cannot use hardware decoded
surfaces without downloading them first. It is also correct, portable to every target including js
and wasmJs, and implementable in an afternoon. It is the reference against which every GPU renderer
is checked for correctness, and it is the fallback when a GPU path fails.

Tier 0 is not a placeholder to be deleted. It ships.

**Tier 1, the GPU path.** Upload planes as textures, convert in a fragment shader, present with a
target time. Per platform:

| Platform | API | Software frames | Hardware frames |
|---|---|---|---|
| macOS, iOS | Metal on a `CAMetalLayer` | one texture per plane, `MTLBuffer` upload with row pitch | `CVMetalTextureCache` over the `CVPixelBuffer`, two textures for NV12, no copy |
| Android | OpenGL ES 3.0 or Vulkan on a `SurfaceView` | one texture per plane | see 13.4, the MediaCodec path avoids the renderer entirely |
| JVM desktop | OpenGL 3.3 through a native surface | one texture per plane | VideoToolbox, VAAPI or D3D11 interop per host OS |
| Linux native | OpenGL through EGL, Vulkan later | one texture per plane | VAAPI surface to EGL image through dmabuf |
| Windows native | D3D11 | one texture per plane | `ID3D11Texture2D` as a shader resource, no copy |
| js, wasmJs | WebGL2 on a canvas | one texture per plane | the browser's own `VideoFrame` drawn directly |

The shader is the same everywhere apart from language: sample the planes, apply the 3 by 3 matrix
selected by the frame's colour space, apply the range offset, write RGB. About 40 lines. Everything
beyond that (better scalers, dithering, tone mapping) is a later quality tier and is listed in section
13.6.

### 13.3 Presentation timing

The scheduler gives the renderer a target time on the monotonic clock. The renderer's job is to make
the frame appear as close to that time as the platform allows.

| Platform | Mechanism |
|---|---|
| Metal | `MTLDrawable.present(at:)`, which takes an absolute host time |
| Android | `eglPresentationTimeANDROID`, or `SurfaceControl.Transaction.setFrameTimeline` on newer versions |
| Vulkan | `VK_GOOGLE_display_timing` when present, otherwise present immediately |
| OpenGL desktop | swap with vsync on, present immediately, and report the vsync interval so the scheduler can align |
| WebGL | `requestAnimationFrame`, which sets the rhythm rather than accepting a target |

Where the platform accepts an absolute presentation time, use it, because it removes a whole class of
jitter. Where it does not, present immediately and report `vsyncIntervalNanos` so the scheduler can
choose the frame closest to the next refresh. Reporting null is allowed and only costs smoothness.

### 13.4 The Android MediaCodec shortcut

This deserves its own note because it changes the architecture on Android.

When FFmpeg decodes with `h264_mediacodec`, it produces frames in `AV_PIX_FMT_MEDIACODEC`. Those
frames do not contain pixels. They hold a reference to a MediaCodec output buffer, and the way to
display one is to release it with the render flag set, which makes MediaCodec draw it directly into
the `Surface` the codec was configured with.

So on Android the fastest correct path involves no GL code, no shader and no texture: configure the
codec with the `SurfaceView`'s surface, then have the renderer call the release-with-render function
at the scheduled time. Presentation timing is set on the surface separately.

Two consequences the plan must respect:

1. The number of frames the player may hold is bounded by the codec's output buffer count, which is
   small, often 4 to 8. The video frame queue limit of 3 in section 11.1 already respects this. A
   player that holds more will stall the decoder, and this failure looks like random freezing.
2. Frames rendered this way cannot be read back, screenshotted, or filtered. When an application asks
   for any of those, the engine must fall back to a texture path or to software decode, and say so
   through a warning.

### 13.5 Subtitle and overlay compositing

The renderer receives a `SubtitleOverlay`, which is a small set of RGBA bitmaps with positions, in the
video's own coordinate space. It composites them after the colour conversion, in display space, with
premultiplied alpha.

Subtitles are not scaled with the video. They are laid out for the output size, which is why layout
happens per viewport change and not per frame. The overlay object carries a content hash so the
renderer can skip re-uploading an unchanged overlay, which is the common case since subtitles change
about once per second and frames arrive 60 times per second.

### 13.6 Quality tiers beyond correct

Stated so that the README never over-claims.

| Tier | Adds | Status in v0.1 |
|---|---|---|
| 0 | Correct colours, correct geometry, bilinear scaling | yes |
| 1 | Bicubic or Lanczos scaling, dithering to 8-bit output, correct chroma siting | planned |
| 2 | HDR: PQ and HLG transfer, static tone mapping to SDR | not in v0.1 |
| 3 | Dynamic tone mapping with peak detection, display sync with frame interpolation | not planned |

mpv with libplacebo is at tier 3. KitePlayer at tier 0 is a correct picture, and the README says
exactly that and nothing more.

---

## 14. Subtitles

### 14.1 The two kinds, and one model

| Kind | Formats | How it arrives |
|---|---|---|
| Text | SRT, WebVTT, SSA and ASS, SubViewer, MicroDVD, and the text side of MOV timed text | Parsed into cues with styling |
| Bitmap | DVD VOBSUB, PGS from Blu-ray, DVB subtitles, and closed captions | Decoded into paletted images with positions |

Both reduce to one model:

```kotlin
public sealed interface SubtitleCue {
    public val startMicros: Long
    public val endMicros: Long

    public data class Text(
        override val startMicros: Long,
        override val endMicros: Long,
        val spans: List<StyledSpan>,
        val layout: CueLayout,
    ) : SubtitleCue

    public data class Bitmap(
        override val startMicros: Long,
        override val endMicros: Long,
        val regions: List<BitmapRegion>,
    ) : SubtitleCue
}
```

Unifying at the cue level, not at the rendering level, is the right seam. mpv unifies by converting
everything into ASS and rendering with libass, which is elegant but forces libass into the dependency
set for a plain SRT file. KitePlayer keeps the model neutral so that a plain SRT never needs libass,
FreeType, HarfBuzz or FriBidi.

### 14.2 Layout in Kotlin, rasterisation on the platform

A pure-Kotlin subtitle renderer that rasterises glyphs itself would need font file parsing, glyph
outline rasterisation, hinting, complex script shaping for Arabic and Indic scripts, and bidirectional
text ordering. That is FreeType plus HarfBuzz plus FriBidi, and it is tens of thousands of lines that
have taken twenty years to get right.

So KitePlayer does not rasterise text. It computes layout and asks the platform to draw it:

```kotlin
public interface TextRasterizer {
    public fun measure(spans: List<StyledSpan>, maxWidthPx: Int): TextMetrics
    public fun rasterize(spans: List<StyledSpan>, maxWidthPx: Int): RgbaBitmap
}
```

Every target already has a good text engine: CoreText on Apple, `android.graphics.Paint` on Android,
Skia through Compose Multiplatform on desktop and web, and the browser's canvas on js. Each gives
correct shaping and bidi for free, in a few dozen lines of adapter code.

The division of labour is: Kotlin decides what the text says, what style it has, and where the box
goes. The platform decides what the glyphs look like.

### 14.3 What the pure-Kotlin path honestly covers

| ASS feature | Pure Kotlin path | libass module |
|---|---|---|
| Dialogue with styles: font, size, bold, italic, underline, colour, outline, shadow | yes | yes |
| Alignment, margins, line wrapping | yes | yes |
| Explicit positioning (`\pos`), simple line breaks | yes | yes |
| Fade (`\fad`), simple alpha | yes | yes |
| Karaoke timing (`\k`, `\kf`) | no | yes |
| Transforms (`\t`), rotation (`\frx` and friends), scaling | no | yes |
| Vector drawing and clipping (`\p`, `\clip`, `\iclip`) | no | yes |
| Collision resolution between overlapping events | simple stacking only | full |
| Embedded fonts from Matroska attachments | no | yes |

So the honest claim, and the exact wording the README may use: **dialogue subtitles render in pure
Kotlin on every target. Styled signs, karaoke and typesetting need the optional libass module.**
Anything stronger than that is a false claim, and section 3 forbids false claims.

A credible full ASS renderer in Kotlin is a project in its own right: the tag set is large, the
semantics are defined by libass's behaviour rather than by a specification, and the test corpus is
the entire fansubbing world. It is a plausible KitePlayer 2.0 goal and it is not a v0.1 goal.

### 14.4 The libass module

`kiteplayer-libass` binds libass through cinterop on Kotlin/Native and through the same generated JNI
approach as KiteCodec on the JVM. It is a separate artifact because it drags in FreeType, HarfBuzz and
FriBidi, and many applications will never need it.

Interface into the engine is one implementation of `SubtitleRenderer`, so the engine does not know
which renderer it has. Font provision is the only awkward part: libass wants a font provider, and
`ass_set_fonts` behaves differently with fontconfig, CoreText and DirectWrite. The module supplies a
directory of fonts plus the platform provider, and extracts Matroska font attachments into a
temporary directory, which is what every player does.

### 14.5 Timing

Subtitle timing is computed against the video clock at presentation time, not at decode time. The
engine holds a sorted cue list, keeps a lookahead window of 5 seconds, and for each presented frame
selects the cues whose window contains the frame's timestamp plus the configured subtitle delay.

Three cases that break naive implementations, and the rule for each:

| Case | Rule |
|---|---|
| Overlapping cues | All active cues are shown, stacked from the bottom margin upward in start order. |
| Zero duration or missing end time | The cue lasts until the next cue starts, bounded to 5 seconds. |
| Cues arriving out of order, common in Matroska | The cue list is sorted on insert, not assumed sorted. |

### 14.6 What KiteCodec must add

KiteCodec today decodes video and audio into `Frame` objects. Subtitles are not `AVFrame` data, they
are `AVSubtitle` with rectangles, so there is no path for them at all today. Section 16.10 lists the
addition.

---

## 15. The JNI bridge: reaching Android and JVM desktop

This is the highest-leverage single piece of work in the plan. Android and JVM desktop together are
the majority of the addressable market for a player, and both are Kotlin/JVM, and Kotlin/JVM cannot
use cinterop. One bridge unlocks both.

### 15.1 What exists and what is missing

KiteCodec's real C surface is not libav\* directly. It is a layer of 141 `static inline` helper
functions in `kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`, after the separator, in a 764
line file. They exist because macros, varargs and 128-bit rescale arithmetic do not survive cinterop.
Kotlin/Native compiles that C inline. There is no header file and no shared library.

| Piece | State |
|---|---|
| The helper layer as C source | exists, inside a `.def` file |
| A real header for it | missing |
| A shared library exposing it | missing |
| JNI entry points | missing |
| Kotlin `external fun` declarations | missing |
| AAR packaging with all Android ABIs | missing |
| Jar packaging with desktop natives | missing |

### 15.2 The design: one header, two generated bindings

```
ffkmp.h                      the 141 helpers, lifted out of the .def, the single source of truth
   |
   +-- ffmpeg.def includes it              -> Kotlin/Native, unchanged behaviour
   |
   +-- codegen reads it
         +-- ffkmp_jni.c                   -> libkitecodec.so / .dylib / .dll
         +-- FfkmpNative.kt                -> Kotlin/JVM external declarations
```

Generating both sides from one header is what makes "one API, every target" structurally true instead
of a promise maintained by hand. A helper added for the native path appears on the JVM path
automatically, and the two can never drift.

The generator is a small Kotlin script in `buildSrc`. It parses the subset of C that the header uses,
which is deliberately simple: no varargs, no unions, no nested structs by value. Where a helper does
not fit that subset, it is hand-written in a companion file and the generator skips it by annotation.

### 15.3 The performance rules, which are not optional

| Rule | Reason |
|---|---|
| Pointers cross as `jlong`, never as objects | A JNI static call with primitive arguments costs roughly 10 to 20 ns. That is noise at a few hundred calls per frame. |
| Pixels never cross | A 1080p frame is 3.1 MB. At 60 fps that is 186 MB per second of copying, plus one allocation per frame. |
| Frames stay native, and travel as a handle | `VideoFrame` in section 9.3 is already defined as a handle for this reason. |
| GPU upload happens on the native side | The JVM never sees the pixels, so there is nothing to copy. |
| No `GetStringUTFChars` in a per-frame path | String conversion allocates. Strings belong to open and probe, not to decode. |
| `JNI_OnLoad` calls `av_jni_set_java_vm` | FFmpeg's MediaCodec wrapper requires the `JavaVM` before the first hardware codec opens. This is the only place it can be obtained, and it is the reason the `androidNative` klibs can never do hardware decode. |

### 15.4 Why not Panama

The Foreign Function and Memory API would remove the C glue entirely, and `jextract` would generate
the bindings. It is not usable here:

| Route | Verdict |
|---|---|
| JNI | Works on Android and on every desktop JVM. The only route that covers both. |
| Panama | Requires JDK 22 or newer, and **Android does not support it at all**. Choosing it means maintaining two separate JVM paths. |
| JNA | Reflection-based dispatch, hundreds of nanoseconds per call. Acceptable for opening a file, fatal per frame. |

This machine runs JDK 21, so Panama is not even available for the desktop half today. JNI it is.

### 15.5 Packaging

| Target | Artifact | Contents |
|---|---|---|
| Android | AAR | `jniLibs/arm64-v8a`, `armeabi-v7a`, `x86_64`, each with `libkitecodec.so` and the FFmpeg shared libraries, or one statically linked `libkitecodec.so` |
| JVM desktop | jar with a native resource tree | `natives/macos-arm64`, `macos-x64`, `linux-x64`, `linux-arm64`, `windows-x64`, extracted to a cache directory on first load |

Static linking of FFmpeg into a single `libkitecodec.so` is preferred on Android, because it halves
the number of files, avoids `System.loadLibrary` ordering problems, and lets the linker drop unused
code. It also has a licence consequence that the Gradle plugin already forces the consumer to
acknowledge, and the LGPL relinking obligation must be documented in `docs/licensing.md`.

### 15.6 Where this work belongs

The bridge belongs in KiteCodec, not in KitePlayer. It is a KiteCodec capability that KitePlayer
consumes, and a transcoding application would want it just as much. Concretely it adds a
`kitecodec-jvm` module and a `jvm` and `android` target to KiteCodec, and KitePlayer's
`kiteplayer-ffmpeg` module then simply gains two more source sets.

---

## 16. What KiteCodec must change

KiteCodec is 3 200 lines of Kotlin over a cinterop definition carrying 141 `static inline` C helpers
prefixed `ffkmp_`. It is built for batch transcoding: open, run through once, write out. A player
needs the same libraries driven differently, and several of its current choices are right for
transcoding and wrong for playback.

Every item names the file, the current behaviour, why it blocks playback, and a change that does not
break the existing `Transcoder` and `Remuxer` API. Ranked: **BLOCKER** means no player without it,
**MAJOR** means the player works badly or only on some targets, **NICE** means quality.

What KiteCodec already gets right, and which must survive every change below: `sendAndDrain` is
correct about `EAGAIN` (it drains, then resends the same packet, and never drops one) and tolerates
`AVERROR_INVALIDDATA` on both send and receive, so a mid-group-of-pictures seek into MPEG-TS does not
abort the file. `MediaSink` forces strictly monotonic output timestamps and re-reads the stream time
base per packet because `avformat_write_header` rewrites it. Those are hard-won and correct.

### 16.0 The visibility wall (BLOCKER, and the first decision)

Everything a player needs is `internal`: `Frame.nativeFrame`, `Frame.streamTimeBase`,
`Frame.streamIndex`, `MediaSource.demuxRouted`, `seekForDecode`, `toRelativeMicros`, `codecparOf`,
`FrameOps`, `withPacket`, `avError`, `check0`, `FFErrors`, `EncoderCore`, `StopDemux`. KiteCodec's
`settings.gradle.kts` declares no friend or associate compilation, so a separate module reaches none
of it.

So before any technical work: **KiteCodec grows a deliberate low-level surface.**

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Low-level KiteCodec API. Native pointers, manual lifetimes, no stability promise.",
)
public annotation class KiteCodecLowLevelApi
```

Everything the player needs becomes `public` behind that annotation. The alternative, putting
KitePlayer's FFmpeg backend inside `kitecodec-core`, is rejected: it would tie a player's release
cadence to a codec binding's, and it would put coroutine-heavy playback code in a module whose
selling point is that it is a thin binding.

The annotation is also the honest signal. These are raw pointers with manual lifetimes. A consumer
should have to write down that they accept that.

### 16.1 Frames cannot be read without copying them (BLOCKER)

`Frame.kt` states that the native pointer is intentionally not exposed, and offers
`copyPlanesToByteArray()` as the only pixel access. `Frame.native.kt` implements it as a mandatory
double copy of the whole frame. There is not even an internal plane-pointer accessor.

| Format and size | Bytes per frame | At 60 fps |
|---|---|---|
| 1080p `yuv420p` or `nv12` | 3.11 MB | 187 MB/s |
| 4K `yuv420p` | 12.4 MB | 746 MB/s |
| 4K `yuv420p10le` | 24.9 MB | 1.49 GB/s |

Plus one fresh `ByteArray` per frame. The copy exceeds a phone's memory bandwidth budget and is
pointless, because the destination is a GPU texture that could be filled from the original pointer.

**Change.** Under `@KiteCodecLowLevelApi`, a scoped plane accessor and a hardware surface accessor:

```kotlin
public fun <R> Frame.withPlanes(block: (planes: List<CPointer<UByteVar>>, strides: List<Int>) -> R): R
public val Frame.hardwareSurface: COpaquePointer?
```

Scoped rather than a property, so a pointer cannot outlive its frame by accident. The existing copy
method stays for thumbnails.

### 16.2 FrameInfo and StreamInfo are too thin to render or to build a track menu (BLOCKER)

`FrameInfo` is built from nine `ffkmp_` accessor calls and carries no duration, no keyframe flag, no
picture type, no sample aspect ratio, and **none of the colour metadata**: no range, no matrix, no
primaries, no transfer function, no chroma location.

That last group is not cosmetic. Without the matrix, a renderer guesses between BT.601, BT.709 and
BT.2020 and shifts every hue when it guesses wrong. Without the range flag, blacks turn grey and
whites clip. Both are already in the frame; only the accessor is missing. `ffkmp_frame_duration`
exists in the definition file and is never called.

`StreamInfo` is missing disposition (default, forced, hearing impaired, visual impaired), the
rotation display matrix, the real frame rate, and the per-stream start time. Without disposition, a
subtitle track menu cannot mark forced subtitles or auto-select them. Without the rotation matrix,
every video shot on a phone plays sideways.

**Change.** Extend both structures. This is the cheapest large win in the whole list: the helpers
either exist already or are one line each.

### 16.3 Only one decode pass may run, and seeking during it is rejected (BLOCKER)

`MediaSource.native.kt` guards decoding with a `SynchronizedObject` over two booleans, `closed` and
`demuxing`. `beginDemux` throws when a second decode flow starts. `seekMicros` throws when
`demuxing` is true. `close` throws when `demuxing` is true.

So a player cannot decode audio and video as independent consumers, cannot seek while playing, and
cannot tear down under cancellation without racing the flag. Mutual exclusion is used where
backpressure is needed. The flow's `emit` suspension point makes demux, decode and consumption one
serialised chain, so a slow consumer stalls the demuxer.

**Change.** Split demuxing from decoding, which is what libavformat and libavcodec already are:

```kotlin
@KiteCodecLowLevelApi
public interface PacketReader : AutoCloseable {
    public val streams: List<StreamInfo>
    public suspend fun read(): Packet?                 // null at end of file
    public fun selectStreams(indices: Set<Int>)        // sets AVStream.discard for the rest
    public suspend fun seek(micros: Long, flags: SeekFlags)
}

@KiteCodecLowLevelApi
public interface StreamDecoder : AutoCloseable {       // one per stream, driven independently
    public suspend fun send(packet: Packet?): Boolean
    public suspend fun receive(): Frame?
    public suspend fun flush()                         // avcodec_flush_buffers
}
```

`decodedFrames`, `decodeStreams`, `extractFrame`, `Transcoder` and `Remuxer` are then reimplemented
on top of these, unchanged in behaviour. `demuxRouted` stays as the internal engine of the batch
API. Nothing existing breaks.

### 16.4 `avcodec_flush_buffers` does not exist anywhere (BLOCKER)

A repository-wide search finds zero occurrences. There is therefore no correct way to reset a
decoder after a seek: the only option today is to destroy and recreate the codec context, which
re-parses extradata and costs milliseconds on every seek.

**Change.** One helper, and `StreamDecoder.flush` from 16.3 calls it.

### 16.5 The seek path is one call with one flag (MAJOR)

`ffkmp_fmt_seek_micros` calls `av_seek_frame(ctx, -1, target, AVSEEK_FLAG_BACKWARD)`. There is no
`avformat_seek_file`, no minimum and maximum timestamp window, no byte seek, and no
`AVSEEK_FLAG_ANY`. `seekForDecode` compensates for indexless containers by aiming
`DECODE_SEEK_BACKOFF_MICROS`, which is 5 000 000 microseconds, before the target.

Five seconds of throwaway decoding is invisible for a thumbnail and unacceptable per seek in a
player.

**Change.** Expose `avformat_seek_file` with the min and max window, and the flag set. The retry
ladder of section 12.5 then lives in the player. Keep the current behaviour inside `extractFrame`.

### 16.6 There is no hardware decoding, in the binding or in the binary (BLOCKER on mobile)

Confirmed absent: `av_hwdevice_ctx_create`, `get_format`, `hw_frames_ctx`,
`av_hwframe_transfer_data`. And the vendored build makes it impossible even if the binding existed:
`BuildFFmpegTask.kt` configures `--disable-everything` plus a whitelist, which disables the hardware
acceleration list as well, and `--enable-videotoolbox` there enables only the two VideoToolbox
**encoders**. Android's binary does contain `h264_mediacodec` and `hevc_mediacodec`, but
`ffkmp_find_decoder_by_id` can only ever return the software decoder.

**Change**, in three parts:

1. Build: add the `--enable-hwaccel=` entries per target, and select MediaCodec decoders by name
   rather than by codec id on Android.
2. Binding: `av_hwdevice_ctx_create`, `AVCodecContext.hw_device_ctx`, and the `get_format` callback.
   That callback is the awkward part, because Kotlin/Native's `staticCFunction` cannot capture state.
   The shim goes in the definition file: a static C function that reads the wanted pixel format from
   `AVCodecContext.opaque` and returns it when the offered list contains it, plus
   `ffkmp_dec_set_hw_format(AVCodecContext*, enum AVPixelFormat)` to store it and install the
   callback. About fifteen lines of C, and no Kotlin callback crosses the boundary.
3. Frames: surface `hw_frames_ctx` so 16.1's `hardwareSurface` can return the `CVPixelBuffer`, the
   `AVMediaCodecBuffer`, the VA surface or the D3D11 texture.

Fallback policy stays in the player. KiteCodec's job is to report honestly whether the hardware
decoder opened.

### 16.7 There is no custom I/O, and no way to interrupt a blocking read (BLOCKER)

No `avio_alloc_context`, no `AVIOContext`, no `avformat_alloc_context`, and no
`AVIOInterruptCB`. Two consequences. Media can only be read through FFmpeg's own protocol handlers,
so an application cannot supply an Android `content://` URI, its own authenticated HTTP client,
KiteTorrent, an encrypted store or an in-memory buffer. And because the format context is only ever
created inside `avformat_open_input`, **a blocking network read cannot be cancelled**, which is a
hang rather than an inconvenience.

**Change.** Static C read, write and seek callbacks that trampoline through a context pointer, plus
an interrupt callback wired to a flag the player sets on cancellation. The Kotlin side is the
`MediaIo` interface of section 8.5, called from the demux worker only, one call at a time, so it
needs no locking.

### 16.8 No demuxer or decoder options can be passed (BLOCKER for network, MAJOR otherwise)

`avformat_open_input` and `avformat_find_stream_info` are both called with a NULL `AVDictionary`.
There is no `probesize`, no `scan_all_pmts`, no timeout, no `user_agent`, no `headers`, no
`thread_count`, and no low-delay flag.

`MediaItem.headers` in section 8.5 cannot be implemented at all until this changes, and a player
without request headers cannot play most authenticated streams. `thread_count` matters separately: a
player wants frame-level threading for video and low delay for audio, and the defaults give neither.

**Change.** An options map on open, and on decoder creation.

### 16.9 Subtitles cannot be decoded, or even demuxed (MAJOR)

`demuxRouted` rejects non-audio and non-video streams outright. There is no `AVSubtitle` binding and
no `avcodec_decode_subtitle2`. The vendored build enables zero subtitle decoders and zero subtitle
demuxers.

**Change.** A `SubtitleDecoder` returning a typed result, plus the build entries:

```kotlin
public sealed interface DecodedSubtitle {
    public data class Text(val startMicros: Long, val endMicros: Long, val ass: String) : DecodedSubtitle
    public data class Bitmap(val startMicros: Long, val endMicros: Long, val rects: List<SubtitleRect>) : DecodedSubtitle
}
```

libavcodec already converts every text subtitle format it supports into ASS event lines, so the text
case is one string and KitePlayer's own parser handles the rest.

### 16.10 swresample is linked and never used (MAJOR)

`-lswresample` is on the link line and the header is in the cinterop set, but there is no `swr_*`
call anywhere. Audio format conversion goes through a filter graph instead, which cannot report
`swr_get_delay` and cannot do `swr_set_compensation`. Channel layouts are always defaulted, never
preserved.

Both missing calls are load-bearing for a player. `swr_get_delay` is part of the audio clock, and
`swr_set_compensation` is how the drift correction of section 10.8 is applied.

**Change.** A public `Resampler` with format conversion, channel layout preservation, the delay
query and the compensation call.

### 16.11 The vendored FFmpeg cannot open https, hls or dash (MAJOR)

The profile whitelists exactly the protocols `file`, `pipe`, `data`, `http` and `tcp`. No TLS
backend, so no https. No HLS or DASH demuxer.

**Change**, choosing the option with no extra dependency wherever one exists:

| Target | Flag | Extra dependency |
|---|---|---|
| macOS, iOS | `--enable-securetransport` | none, it is in the SDK |
| Android | `--enable-mbedtls` | one small static library to cross-build |
| Linux | `--enable-gnutls` or `--enable-openssl` | usually already present |
| Windows | `--enable-schannel` | none, it is in the OS |

Apple and Windows are free. HLS and DASH additionally need their demuxers enabled and depend on the
`https` protocol being present.

### 16.12 Timestamp normalisation needs review (MAJOR)

`toRelativeMicros` and `toAbsoluteMicros` are the only two places the container origin is applied,
which is good discipline. Both are `internal`, so a player cannot call them, which 16.0 fixes. The
model must then be checked against a negative container start time, per-stream start offsets that
differ from the container's, MPEG-TS programme clock wraparound after about 26.5 hours, and
mid-stream discontinuities. The engine's rule from section 11.6 is that its timeline starts at zero
and normalisation happens exactly once, here.

### 16.13 There is no JVM target (BLOCKER for Android and JVM desktop)

Section 15 specifies the bridge. It belongs in KiteCodec, as a `kitecodec-jvm` module plus `jvm` and
`android` targets. Of the 141 helpers, the ones needing hand-written JNI rather than generated code
are those returning pointers, the one using a thread-local error buffer, and the callback installers
from 16.6 and 16.7. The rest are mechanical.

### 16.14 Smaller items (NICE)

| Item | Why it matters |
|---|---|
| `AVStream.discard` for unselected streams | libavformat skips them instead of the player discarding them after the fact |
| Carry the packet byte position through the decoder | byte-accurate progress when timestamps are broken |
| Rotation display matrix on the stream | otherwise phone video plays sideways |
| Reuse a filter graph across frames of one format | live video filters otherwise rebuild the graph on every reconfiguration |
| Bitstream filter API | listed as absent in the README; playback rarely needs it |

### 16.15 Order of work in KiteCodec

1. **16.0**, the opt-in low-level surface. Nothing else can be used without it.
2. **16.3 and 16.4** together, since they are one refactor.
3. **16.2**, which the renderer and the track menu both need immediately.
4. **16.1**, which the renderer needs to avoid the copy.
5. **16.5**, one call once 16.3 lands.
6. **16.10**, needed by the audio path.
7. **16.8**, then **16.7**, then **16.6**, then **16.9**, then **16.11**, then **16.13**.

Steps 1 to 6 are what milestones M3 and M4 depend on. Every one of them is additive, and KiteCodec's
53 tests must still pass after each.
## 17. Constants to port verbatim

These values are the empirical residue of a decade of bug reports against ffplay and mpv. A
reimplementation that treats them as arbitrary regresses in ways that only show up on real content,
on slow hardware, or after twenty minutes of playback. Port them, then tune with evidence.

| Constant | Value | Used for |
|---|---|---|
| minimum sync threshold | 40 ms | lower clamp on the video correction tolerance |
| maximum sync threshold | 100 ms | upper clamp, and the frame timer resync limit |
| frame duplication threshold | 100 ms | above this, extend a frame instead of repeating it |
| no-sync threshold | 10 s | audio correction reset, early frame drop guard |
| maximum frame duration | 3600 s normal, 10 s discontinuity-prone | frame duration sanity ceiling |
| timestamp unrounding window | 3.1 ms | Matroska millisecond rounding |
| frame rate snap agreement | 16 samples | when to trust the container's declared rate |
| audio correction average | coefficient `0.01 ^ (1/20)` over 20 buffers | drift measurement |
| audio correction bound, resampler | 10 percent | maximum sample stretch |
| audio correction bound, continuous | 0.125 percent total, one tenth per frame slew | inaudible drift correction |
| A/V difference low-pass | 1.0 s time constant, 10 s recovery | display-sync correction |
| seek coalescing window | 0.3 s | guarantees a frame is shown between held-key seeks |
| post-seek redraw suppression | 0.1 s | avoids a flash of the wrong frame |
| precise seek tolerance | 5 ms | how close counts as landing on the target |
| stream ready threshold | 25 packets or 1.0 s of media | per-stream start gate |
| audio soft buffer | `max(deviceBufferFrames, 0.2 * sampleRate)` frames | decouples device period from the engine |
| readahead target | 1.0 s | demuxer lookahead |
| total read cache | 32 MB, 30 s | memory bound and latency bound |
| live back buffer | 20 s | how far back a live stream may seek |
| core loop floor | 50 ms | maximum sleep when nothing else asks sooner |
| still image display duration | 5.0 s | sparse video streams |
| video frame queue | 4 pending plus 1 shown | scheduling slack, bounded by the hardware surface pool |

---

## 18. Testing: how this library earns the word "stable"

mpv is trusted because it has been used by millions of people for a decade. KitePlayer does not have a
decade. What it has instead is an engine with no platform dependency and a clock that is a parameter,
and that combination allows a kind of testing no existing player can do.

### 17.1 Tier 1: engine unit tests in virtual time

Every timing rule in sections 10 to 12 is a pure function of state, and every one gets a table-driven
test in `commonTest`, running on the JVM in milliseconds.

| Under test | Test shape |
|---|---|
| Clock arithmetic | Set at a known system time, advance the fake clock, assert the media time. Repeat at speed 0.5, 1.0, 2.0, and across pause and resume. |
| Master clock selection | Every combination of audio present, sink latency quality and configured sync mode. |
| Presentation decision | A golden table of (masterClock, videoClock, frameDuration) to (delay, action), covering late, early, very early, and beyond the resync threshold. |
| Queue limits | Fill to soft limit, assert buffering state. Fill to hard limit, assert the demux pump stalls. |
| The interleaving deadlock | A source that emits 5 s of video before any audio. Assert playback still starts and the pump never stalls with audio empty. |
| Seek coalescing | Twenty seek requests in one virtual millisecond. Assert exactly one flush cycle and one `SeekCompleted`. |
| Generation filtering | Inject frames from a previous generation at every hop. Assert none is ever presented. |
| Buffering thresholds | Start, rebuffer, resume, and end of stream, each at its exact boundary. |
| End of stream | Assert the last audio buffer is played before `Ended`, not when the demuxer reports end. |
| Broken timestamps | NOPTS, timestamps going backwards, a negative start time, a 26-hour jump, MPEG-TS wraparound. Assert playback continues and a `BadTimestamps` warning is emitted. |
| Format change mid-stream | Resolution and sample rate change. Assert the clock does not reset and the correct event is emitted. |

### 17.2 Tier 2: deterministic simulation

This is the tier that matters, and it is only possible because of the architecture in section 7.4.

An entire playback session runs in `kotlinx-coroutines-test` virtual time, with a scripted fake
source, a fake sink that reports a configurable latency, and a fake renderer that records what it was
asked to present and when. A seeded pseudo-random schedule decides the order in which the workers get
to run, and injects faults: a decoder that returns an error on frame 300, a sink that underruns, a
renderer that loses its surface, a source that returns a short read.

Then the run asserts invariants rather than outcomes:

| Invariant | Why it is the right thing to assert |
|---|---|
| No frame from a superseded generation is ever presented | This is the definition of seek correctness. |
| Within a generation, presented timestamps are non-decreasing | Catches reordering bugs that a human would perceive as stutter. |
| Every frame handed to the engine is eventually closed exactly once | Leak and double-free detection, at test speed, without a profiler. |
| The session always reaches a terminal state within a bounded amount of virtual time | Deadlock detection. A hang becomes a failing test in 50 ms instead of a bug report six months later. |
| Measured drift between the audio clock and presented video stays inside the configured tolerance | The user-visible property, asserted directly. |
| Status transitions follow the declared state machine, with no illegal edge | Catches the whole class of "it went from Idle to Playing without Opening" bugs. |

Ten thousand seeded runs are cheap because virtual time costs nothing. A failing seed is a permanent
regression test, checked in with a name.

This is what "learn from mpv's mistakes" means in practice. mpv cannot do this, because its core is C
with real threads and a real clock. Building the engine as pure Kotlin with an injected clock is not
architectural purity for its own sake. It buys this.

### 17.3 Tier 3: real media, on real platforms

Runs in `nativeTest` on macOS arm64 first, then every platform as it comes up. Clips are generated by
`scripts/testmedia.sh`, so there are no binary fixtures in the repository.

| Test | Pass condition |
|---|---|
| Decode the whole 10 s clip | Frame count matches `ffprobe`, timestamps monotonic, no leaks |
| 100 random seeks | Every landed frame is within one frame duration of the target |
| 30 minute soak on `soak30min.mp4` | Final drift under 40 ms, resident memory flat within 5 percent, zero unclosed frames |
| Hardware decode on `hevc4k10.mp4` | VideoToolbox is actually selected, and output matches software decode above 40 dB PSNR |
| Subtitle timing on `subbed.mkv` | Both cues appear and disappear within 50 ms of their declared times |
| Non-integer frame rate on `vfr720p60.mp4` | No accumulated drift over 8 s at 59.94 fps |
| Truncated and corrupted files | A `PlaybackError` is surfaced. No crash, no hang, no silent stall. |

The `ffmpeg` and `ffprobe` command line tools are used as oracles, which is the same approach KiteAudio
takes. Using the binaries is allowed. Reading their source for implementation guidance is governed by
section 4.

### 17.4 Tier 4: renderer correctness by golden image

Colour matrix and stride bugs are silent: the picture appears, and it is subtly wrong. So the renderer
gets golden image tests. Decode a known frame, render it through tier 0 and through tier 1, and compare
both against a checked-in reference produced by the `ffmpeg` CLI, with a per-pixel tolerance.

Cases that must each have a golden: BT.601 limited, BT.709 limited, BT.709 full, BT.2020 10-bit, NV12,
and a frame whose stride is deliberately larger than its width.

---

## 19. Milestones

Each milestone has an exit criterion that can be checked by running something, not by reading code.

| # | Milestone | Exit criterion |
|---|---|---|
| M0 | Repository scaffolded to family standard | `./gradlew build` passes. Module graph matches section 6. ABI dump generated. |
| M1 | Engine core with fake everything | Tier 1 and tier 2 tests pass. 10 000 simulation seeds green. No platform code exists yet. |
| M2 | KiteCodec playback changes landed | KiteCodec exposes zero-copy frames, concurrent per-stream decode, live seek with flush, and subtitle decode. Its own tests still pass. |
| M3 | Audio only playback on macOS | `sync1080p30.mp4` plays its audio to the speakers, correct pitch, correct duration, seek works, no drift over 10 minutes. |
| M4 | Video playback, tier 0 renderer, macOS | The same clip plays with picture and sound in sync. Drift under 40 ms over 30 minutes. Colours match a golden image. |
| M5 | Tier 1 Metal renderer and VideoToolbox hardware decode | `hevc4k10.mp4` plays at 30 fps with CPU use below 25 percent of one core. Zero-copy path confirmed by the absence of a download step in the stats. |
| M6 | Subtitles | `subbed.mkv` shows both cues at the right time, laid out by the Kotlin engine and rasterised by CoreText. |
| M7 | Compose surface and sample application | A Compose Multiplatform window plays the clip with a working seek bar, play and pause, track selection and a stats overlay. |
| M8 | JNI bridge, then Android, then JVM desktop | The same sample application, unchanged apart from the platform entry point, plays the same clip on an Android device and on the JVM. |

M0 to M7 are the work of this repository. M8 is mostly KiteCodec work and packaging.

### 19.1 What "usable" means, precisely

The word the owner used is "workable". This is the bar, and every item is checkable:

1. Opens a local file and plays video with sound in sync.
2. Play, pause, seek, speed change and volume all work and are not perceptibly laggy.
3. Seeking is frame accurate in `Precise` mode and instant in `KeyframeThenRefine` mode.
4. Nothing leaks over a 30 minute soak.
5. Errors surface as typed values, and no failure mode is a hang.
6. The API compiles from `commonMain` and the sample proves it.

Anything beyond that list is quality, not usability, and belongs to a later version.

---

## 20. Documentation and publishing

Follows KitePDF exactly. The details worth stating here:

- The README is written last, and it describes only what is committed. Section 3 forbids anything else.
  The KiteCodec README is the model: it opens with a working code sample, it states its blockers in a
  blockquote near the top, and it has a `Limits` table that is honest to the point of discomfort.
- `POM_DESCRIPTION` outlives every README, so it describes exactly the shipped capability set at
  publish time, per target.
- `docs/` gets a page per task, not a page per class: getting started, playback, seeking, tracks,
  subtitles, audio output, video rendering, hardware decode, custom I/O, platform support, licensing,
  troubleshooting.
- Dokka v2, aggregated, with the family's `dokka-templates` and `api-theme`.
- `explicitApi()` is on. The klib and JVM ABI dumps are committed and CI fails on an unapproved change.
  KiteCodec has the validator configured with no baseline and CI does not run it. That is a gap
  KitePlayer does not repeat.
- The licence table in `docs/licensing.md` states the consequence of each FFmpeg flavour, and the
  additional consequence of the optional libass module.
