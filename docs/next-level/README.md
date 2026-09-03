# Next level: sixty items, executable without this conversation

> **For whoever executes this, human or AI.** Read `GOTCHAS.md` sections 1 and 3 first. Every
> item below was written against the tree as it stood when this program was drafted: each one
> names the files it touches, quotes the contract it adds, names the test that must go red
> first, the gate tier, and the commit line. Where an item cannot be finished on a laptop, it
> says so and names the owner step.

## 1. How to run this unattended

- **One executor per repository at a time.** KitePlayer and KiteFFmpeg are two repositories, so
  two executors may run in parallel, one in each. Never two in one tree: every task touches
  `MASTER_PLAN.md`, and half of them touch `KitePlayer.kt`.
- **Never create a branch.** Everything lands on `main`, committed locally, never pushed. The
  owner pushes.
- **Order inside a program file is the order.** Items with a `Depends on` line wait for what they
  name. Across files, the index in section 3 gives a safe global order.
- **Each item is one commit** unless it says otherwise. The commit message uses the item's plain
  name, never its number. No trailers of any kind.
- **Red first, always.** Write the named failing test, run it, watch it fail at the predicted
  line, implement, watch it pass, then break the fix and watch it fail again. A test never seen
  red proves nothing.
- **Public API changes** run `./gradlew updateKotlinAbi` in the same commit (KitePlayer) or
  `./gradlew apiDump -Pkiteffmpeg.hostTargetsOnly=true` (KiteFFmpeg). Both `explicitApi()`.
- **`MASTER_PLAN.md` loses the item's row (under DOABLES, or wherever it sits) in the same commit** that lands it. Half done
  is reduced in place with the remainder named.
- **Gate tier by changed path**, `GOTCHAS.md` section 3. Prose is Tier 1. A platform source set,
  a `build.gradle.kts`, or anything under `native/` is Tier 2. `kite_rt_render.c` or the ring
  handoff is Tier 3, which means everything except the supervised device run, and that run is
  the owner's.
- **No new dependency, plugin, GitHub Action or toolchain bump without an owner decision.** The
  items that need one say `[owner]` and name the alternative that needs none.
- **No em dashes in any file.** No register codes in shipped files. Plain words.
- **KDoc stays short.** One or two lines on a simple thing.
- **When the tree contradicts an item, stop and report.** Do not improvise the item back into
  truth. The sweep that grounded these items was done once; the tree moves.

## 2. The programs

| File | Program | Items |
|---|---|---|
| `audio.md` | Volume boost with a soft limiter, session id, ReplayGain, balance, EQ, loudness scan, audio-only playback, click-free speed change, sleep timer | A1 to A9 |
| `video.md` | Backward frame step, refresh-rate awareness, a real frame-presented signal, auto-deinterlace, gamma, PNG snapshots, snapshots with subtitles, secure surface, PiP on both platforms, typed filters | V1 to V11 |
| `session.md` | Queue mutation, shuffle, preload and real gapless, markers and chapter navigation, state export, interruptions and audio focus, media session, background policy, two players at once, accessibility | S1 to S10 |
| `subtitles.md` | Style override, a cue flow for apps, a secondary track, subtitle sources through the byte doors, a faster cue selector, one overlay geometry law | T1 to T6 |
| `observability.md` | IO throughput, frame timing percentiles, trace export, a structured log sink | O1 to O4 |
| `kiteffmpeg.md` | swresample, the small C entry points, the missing filters in the recipe, DSL honesty, public packet writes, record while playing, probe, thumbnails, waveforms | K1 to K9 |
| `quality.md` | Android emulator in CI, iOS simulator smoke, the API site, size ratchet, a perf gate, parser fuzzing, dependency automation, release notes, sample pickers, a doc-truth sweep, conformance report | Q1 to Q11 |

Sixty items. Sizes: S under half a session, M up to two, L two to five.

## 3. Global order

Two lanes, one per repository. Inside a lane, top to bottom. An item marked `after X` waits for X
in the other lane, which means: it waits for the owner to PUBLISH the KiteFFmpeg version carrying
X and for the pin in `gradle/libs.versions.toml` to move. Nothing else crosses the lanes.

**KiteFFmpeg lane:** K4, K2, K3, K5, K7 (the library half), K1. Then publish (owner). K8 and K9
live in KitePlayer's `kiteplayer-ffmpeg` module and are in the other lane.

**KitePlayer lane, no cross-lane wait:** Q10, Q7, Q6, Q3, Q8, O4, O1, O2, T5, T2, A2, A3, A4, S4,
S2, S1, S5, V8, V9, V11, V6, K8, A7, A9, A8, K9, T1, T3, V2, V3, V5, A1, A6, A5, S6, S8, S7, S9,
S10, T6, T4, V1, O3, V7, S3, Q4, Q5, Q11, Q9, Q1, Q2, V10.

**KitePlayer lane, after the KiteFFmpeg publish:** K6 (after K5), V4 (after K2 and K3), K7's
player half (after K7), O1's bitrate half (after K2), K1's player half (after K1).

The doors expansion (`docs/media-input-doors.md`) is a prerequisite for T4, Q9 and K8's `io`
route. Run it first, or those three items wait. Where a test in this program uses
`MediaIo.ofBytes` or `MediaItem.from` before the doors have landed, a ten-line in-memory
`MediaIo` inside the test does the same job.

## 4. Owner decisions this program needs

Each is named again inside its item. Recommended answers stated; the executor does not guess.

- **A1**: the supervised device run after the limiter lands. Everything else is the executor's.
- **K1**: publishing the KiteFFmpeg version that carries swresample and the small C entry points.
- **Q1**: allowing `reactivecircus/android-emulator-runner` in CI. Alternative: none; without it,
  device tests stay on DEVICE-DAY.
- **Q5**: `kotlinx-benchmark` as a dependency, or the dependency-free perf gate the item
  describes by default.
- **S9**: whether the media session lives in `kiteplayer-mobile` (recommended) or a new module.
- **V10**: iOS picture in picture is a design act with an owner device at the end; the item
  ships the layer path and stops at the device proof.

## 5. What every item's block means

```
### <letter><number> <plain name>. Size <S, M or L>, Tier <1, 2 or 3> (owner steps named here)
Why: one paragraph.
Depends on: other items, or "nothing".
Files: create / modify, exact paths.
Contract: the public or SPI code that changes, exact.
Tests: red first, names and the assertion that fails.
Steps: numbered, terse.
Gate: the commands.
Commit: the message line.
```

Nothing in a block is optional. A step that tells you to handle something it does not name is a
bug in this document; report it rather than inventing the missing part.
