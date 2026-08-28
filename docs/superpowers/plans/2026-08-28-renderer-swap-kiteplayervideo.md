# Live Renderer Swap + KitePlayerVideo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task in a single session (this owner forbids multi-agent execution on Fable 5;
> do NOT use subagent-driven-development here). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One public composable, `KitePlayerVideo(player, path)`, that hosts either the
native-view path or the true-Compose path and can swap between them while media is playing,
backed by engine-level renderer-swap semantics that make the swap correct on every platform.

**Architecture:** Three layers. (1) `kiteplayer-core` learns identity-checked detach, records
which renderer the active video decoder is coupled to, and reacts to a renderer replacement by
rebuilding the video path (coupled decoder) or repainting one frame (paused swap). (2)
`kiteplayer-view`'s shared `PlayerViewBinding` moves to identity-checked detach so a stale view
teardown can never kill a newer renderer. (3) A new `:kiteplayer-compose-ui` module publishes
`KitePlayerVideo` + `KiteRenderPath`, driven by ordinary Compose recomposition.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-coroutines-test scripted
harness (`CoreHarness`), Kotlin ABI validation (`updateKotlinAbi` / `checkKotlinAbi`).

**Spec:** This document, section "Spec" below. No external spec file exists.

## Global Constraints

- Repo: `/Users/macbook/StudioProjects/#Kite/KitePlayer`. Branch: `main`. Never create a branch.
- The repo has unrelated dirty files (KPKMP-*.md, gradle.properties, libs.versions.toml, api
  dumps). Do not touch, revert, or commit them. Stage only files this plan names.
- Commits: normal prose, neutral tone, NO "Co-Authored-By" trailer, no em dashes.
- No em dashes anywhere in code comments, KDoc, or markdown. Hyphens are fine.
- KDoc and comments: short (1-2 lines unless truly needed), plain language, matches the repo's
  existing voice.
- `explicitApi()` is on in every library module: every public declaration needs explicit
  visibility and explicit return types.
- All PlaybackCore session state is actor-confined. New core code runs only inside the actor
  (command handlers, handler-pass functions). Never touch `session`, `pendingRenderer`,
  `pendingSelections`, `pendingSeek` from outside.
- After any public API change in a module, run `./gradlew :<module>:updateKotlinAbi` and commit
  the dump together with the change. `./gradlew :<module>:checkKotlinAbi` must pass.
- Test with `./gradlew :<module>:jvmTest` (commonTest runs under the jvm target). iOS/Android
  compile checks: `compileKotlinIosArm64`, and for Android KMP modules
  `compileAndroidMain` (fall back to `assemble` for the sample app).
- Kotlin version is recent (2.2.x era in libs.versions.toml): `Enum.entries`, guard conditions
  in `when`, etc. are available. Do not add new dependencies.

---

## Spec

### What exists today (verified against sources, 2026-08-28)

- `KitePlayer.attachRenderer(renderer)` (KitePlayer.kt:545) replaces the attached renderer at
  any time, including while playing. `PlaybackCore.setRenderer` (PlaybackCore.kt:1456) parks the
  video scheduler behind a fence, swaps `session.renderer.delegate` (an `AttachableRenderer`,
  PlaybackWorkers.kt:200), re-tells scale/adjustments/quality/transform, and sets
  `pendingRenderer`. Refusal path: scheduler did not quiesce within `QUIESCE_DEADLINE` (2s).
- `detachRenderer()` nulls the delegate unconditionally. Playback continues headless.
- Decoder selection (`createVideoDecoder`, PlaybackCore.kt ~2037) tries
  `pendingRenderer?.videoDecoderFactories()` first when eligible; a winner is recorded as
  `VideoDecoderOrigin.Renderer` in `OpenSession.videoDecoderOrigin` (PlaybackCore.kt:5897).
  `VideoRenderer.videoDecoderFactories()` KDoc: "Attaching a renderer after open does not
  reconfigure the active decoder."
- `handleTrackChanges` (PlaybackCore.kt:2796) is the one rebuild carrier: it drains
  `pendingSelections`, tears the session down, `buildSession(...)` (re-running decoder selection
  against the CURRENT `pendingRenderer`), repositions with a queued precise seek to the captured
  position, restores `playRequested`. The actor pass runs `drainCommands` then
  `handleTrackChanges` (PlaybackCore.kt:623-624), so a selection queued inside a command handler
  rebuilds in the same pass.
- `queueSeek(request, reply)` (PlaybackCore.kt:4051) accepts a null reply;
  `SetPreservePitch` already uses `queueSeek(SeekRequest(SeekTarget.Absolute(currentPosition()),
  SeekMode.Precise), null)` as an internal re-anchor.
- `PlayerViewBinding` (kiteplayer-view commonMain) drives both platform views with
  `detach: (P) -> Unit`; both views pass `player.detachRenderer()` (identity-blind).
- `kiteplayer-compose-interop` publishes `KitePlayerSurface(player, modifier)`;
  `kiteplayer-compose-video` publishes `KiteVideo(state, modifier)`, `KiteVideoState` with
  `public val renderer: VideoRenderer`, common `rememberKiteVideoState()` and Android
  `rememberKiteVideoState(window: Window)` (ImageBitmaps.android.kt:63).
- Test infra: `CoreHarness` (kiteplayer-core commonTest) with `RecordingRenderer(accepts,
  decoderFactories)`, `RecordingVideoDecoderFactory`, `ScriptedVideoDecoder`,
  `harness.backend.openCalls`, `harness.core.snapshots.value.status`, virtual time via
  `harness.run(duration)`.

### Required behavior (the feature)

1. **Identity-checked detach.** `KitePlayer.detachRenderer(expected)` detaches only while
   `expected` is still the attached renderer; a stale call is a completed no-op. The existing
   no-arg `detachRenderer()` stays unconditional.
2. **Coupled-swap rebuild.** When `attachRenderer(new)` replaces a renderer and the active video
   decoder's origin is `Renderer` with a coupled renderer other than `new`, the engine rebuilds
   the video path against `new` at the current position (via the existing track-change rebuild),
   preserving play/pause. Guards: skip when a video selection is already pending (it will rebuild
   anyway); on an unseekable source warn `CommandRefused("attachRenderer", ...)` and skip (an
   in-place rebuild cannot reposition there).
3. **Re-attach same renderer is free.** Detach then attach of the SAME renderer object must not
   rebuild (its coupled decoder is still valid).
4. **Paused repaint.** A swap that does not rebuild, while not playing, repaints one frame on the
   new renderer via an internal precise seek to the current position (skipped when a seek is
   already pending or the media has no video stream).
5. **View binding safety.** `PlayerViewBinding` detach carries the renderer and the views use
   `detachRenderer(expected = renderer)`.
6. **`KitePlayerVideo`.** New module `:kiteplayer-compose-ui` (android, iosArm64,
   iosSimulatorArm64, jvm) with `public enum class KiteRenderPath { Auto, NativeView,
   ComposeCanvas }` and `@Composable public fun KitePlayerVideo(player, modifier, path,
   onEffectivePath)`. `Auto` resolves to NativeView on Android/iOS and ComposeCanvas on JVM; JVM
   coerces NativeView to ComposeCanvas. Path change = recomposition swap: old branch detaches
   (identity-checked), new branch attaches after one frame. The player is never owned by the
   composable.
7. **Sample demo.** `kiteplayer-sample-android` gains a fourth demo Activity with a runtime
   toggle between the two paths over one playing clip.
8. **Docs.** README module bullets/table gain `kiteplayer-compose-ui`; `attachRenderer` and
   `videoDecoderFactories` KDoc updated to the new semantics.

### Non-goals (do NOT implement in this plan)

- Tier-1 Android `MediaCodec.setOutputSurface` retarget (gapless coupled swap). Separate spike.
- Upgrading a backend decoder to a coupled one when a renderer attaches after a headless open.
- Synkplay app wiring (different repo), web targets, version bump / publishing.

---

### Task 1: Identity-checked detach in the core

**Files:**
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`
  (command class ~line 6476, suspend fn ~line 806, handler ~line 1290)
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt` (~line 549)
- Test: `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt`

**Interfaces:**
- Consumes: existing `CoreCommand.DetachRenderer`, `pendingRenderer`, `setRenderer`.
- Produces: `KitePlayer.detachRenderer(expected: VideoRenderer)` (public, fire-and-forget),
  `PlaybackCore.detachRenderer(expected: VideoRenderer? = null)` (suspend),
  `CoreCommand.DetachRenderer(expected: VideoRenderer?, reply)`. Tasks 4 and 5 call the facade
  overload as `player.detachRenderer(expected = renderer)`.

- [ ] **Step 1: Write the failing tests** (append inside the test class in PlaybackCoreTest.kt,
  next to the existing renderer test at ~line 1170; reuse the file's existing imports)

```kotlin
@Test
fun `a stale identity detach is a no-op and the newer renderer keeps its frames`() = runTest {
    val harness = CoreHarness(this, renderer = null)
    harness.open()
    harness.core.play()
    val old = RecordingRenderer()
    harness.core.attachRenderer(old)
    harness.run(200.milliseconds)

    val new = RecordingRenderer()
    harness.core.attachRenderer(new)
    harness.core.detachRenderer(expected = old)
    harness.run(300.milliseconds)

    assertTrue(new.count > 0, "a stale detach must not remove the newer renderer")
    harness.close()
}

@Test
fun `a matching identity detach detaches and fences`() = runTest {
    val harness = CoreHarness(this, renderer = null)
    harness.open()
    harness.core.play()
    val renderer = RecordingRenderer()
    harness.core.attachRenderer(renderer)
    harness.run(200.milliseconds)

    harness.core.detachRenderer(expected = renderer)
    val atDetach = renderer.count
    harness.run(300.milliseconds)
    assertEquals(atDetach, renderer.count, "a matching identity detach stops submissions")
    harness.close()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :kiteplayer-core:jvmTest --tests "*PlaybackCoreTest*" 2>&1 | tail -30`
Expected: compilation FAILS ("no value passed for parameter" / unresolved `expected`), because
`detachRenderer` takes no argument yet.

- [ ] **Step 3: Implement**

In `PlaybackCore.kt`, replace the command class (~line 6476):

```kotlin
    class DetachRenderer(
        val expected: VideoRenderer?,
        val reply: CompletableDeferred<Unit>,
    ) : CoreCommand("detachRenderer", reply)
```

Replace the suspend fn (~line 806):

```kotlin
    /** Returns only once no submission to the renderer being detached is outstanding. */
    suspend fun detachRenderer(expected: VideoRenderer? = null) {
        val reply = CompletableDeferred<Unit>()
        send(CoreCommand.DetachRenderer(expected, reply))
        awaitReply(reply)
    }
```

Replace the handler branch (~line 1290):

```kotlin
            is CoreCommand.DetachRenderer -> {
                if (command.expected != null && pendingRenderer !== command.expected) {
                    // Stale: something newer is attached, so nothing of the caller's is left to undo.
                    command.reply.complete(Unit)
                } else if (setRenderer(null)) {
                    command.reply.complete(Unit)
                } else {
                    val reason = "the video scheduler did not quiesce within $QUIESCE_DEADLINE"
                    warn(PlaybackWarning.CommandRefused("detachRenderer", reason))
                    command.reply.completeExceptionally(
                        IllegalStateException("renderer detach aborted: $reason"),
                    )
                }
            }
```

In `KitePlayer.kt`, after the existing no-arg `detachRenderer()` (~line 550), change the no-arg
body to pass `null` and add the overload:

```kotlin
    /** Detaches the current renderer. Playback continues without a picture. See [attachRenderer]. */
    public fun detachRenderer() {
        core.post(CoreCommand.DetachRenderer(null, CompletableDeferred()))
    }

    /**
     * Detaches [expected] only while it is still the attached renderer; a stale call is a no-op.
     * This is the safe form for presentation code whose teardown can race a newer attach.
     */
    public fun detachRenderer(expected: VideoRenderer) {
        core.post(CoreCommand.DetachRenderer(expected, CompletableDeferred()))
    }
```

Add `import io.github.yuroyami.kiteplayer.spi.VideoRenderer` to KitePlayer.kt if not present.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kiteplayer-core:jvmTest --tests "*PlaybackCoreTest*" 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass (the whole class, not only the new ones).

- [ ] **Step 5: Update the ABI dump and check**

Run: `./gradlew :kiteplayer-core:updateKotlinAbi :kiteplayer-core:checkKotlinAbi`
Expected: check passes; `kiteplayer-core/api/` shows only the added `detachRenderer` overload.
Note: `kiteplayer-core/api/jvm/kiteplayer-core.api` is ALREADY dirty from unrelated work. Inspect
`git diff` and stage the file anyway (the dump must match the code); mention the pre-existing
drift in the commit body if the diff contains hunks you did not cause.

- [ ] **Step 6: Commit**

```bash
git add kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt kiteplayer-core/api
git commit -m "core: identity-checked detachRenderer(expected)

A stale detach (the attached renderer is no longer the caller's) completes
as a no-op instead of removing whatever is attached now. Groundwork for
live renderer swapping, where teardown of the old presentation can race
the attach of the new one."
```

---

### Task 2: Coupled-swap rebuild and paused repaint in the core

**Files:**
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`
  (OpenSession ~line 5891, buildSession construction ~line 1988, AttachRenderer handler ~line 1277,
  new private fun near setRenderer ~line 1456)
- Test: `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt`

**Interfaces:**
- Consumes: `queueSelection(kind, track, reply)`, `queueSeek(request, reply)`, `pendingSelections`,
  `pendingSeek`, `playRequested`, `currentPosition(): Pts`, `pendingRenderer`,
  `VideoDecoderOrigin`, `TrackId`, `SeekRequest`/`SeekTarget.Absolute`/`SeekMode.Precise`,
  `PlaybackWarning.CommandRefused`.
- Produces: `OpenSession.coupledRenderer: VideoRenderer?`; swap follow-up behavior that Tasks 5-7
  rely on (a path flip in `KitePlayerVideo` recovers the picture without app-side code).

- [ ] **Step 1: Write the failing tests** (append in PlaybackCoreTest.kt; the helpers
  `RecordingVideoDecoderFactory`, `ScriptedVideoDecoder`, `ScriptedVideoDecoderStatus` are
  already in this file / commonTest)

```kotlin
private fun coupledFactory(script: MediaScript, ledger: LeakLedger): RecordingVideoDecoderFactory =
    RecordingVideoDecoderFactory { _, _ ->
        ScriptedVideoDecoder(
            script = script,
            ledger = ledger,
            faults = FaultPlan.None,
            hardwareStatus = ScriptedVideoDecoderStatus(HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec)),
        )
    }

@Test
fun `replacing a coupled renderer rebuilds the video path against the new one`() = runTest {
    val script = MediaScript()
    val ledger = LeakLedger()
    val factoryA = coupledFactory(script, ledger)
    val rendererA = RecordingRenderer(decoderFactories = listOf(factoryA))
    val harness = CoreHarness(
        scope = this,
        script = script,
        ledger = ledger,
        config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
        renderer = rendererA,
    )
    harness.openWithRenderer()
    harness.core.play()
    harness.run(300.milliseconds)
    assertEquals(1, harness.backend.openCalls)
    assertEquals(1, factoryA.createCount)

    val factoryB = coupledFactory(script, ledger)
    val rendererB = RecordingRenderer(decoderFactories = listOf(factoryB))
    harness.core.attachRenderer(rendererB)
    harness.run(500.milliseconds)

    assertEquals(2, harness.backend.openCalls, "the coupled swap reopened the source exactly once")
    assertEquals(1, factoryB.createCount, "the rebuild selected the new renderer's decoder")
    assertTrue(rendererB.count > 0, "the new renderer receives frames after the rebuild")
    assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
    harness.close()
}

@Test
fun `reattaching the same coupled renderer does not rebuild`() = runTest {
    val script = MediaScript()
    val ledger = LeakLedger()
    val factory = coupledFactory(script, ledger)
    val renderer = RecordingRenderer(decoderFactories = listOf(factory))
    val harness = CoreHarness(
        scope = this,
        script = script,
        ledger = ledger,
        config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
        renderer = renderer,
    )
    harness.openWithRenderer()
    harness.core.play()
    harness.run(200.milliseconds)

    harness.core.detachRenderer(expected = renderer)
    harness.core.attachRenderer(renderer)
    harness.run(300.milliseconds)

    assertEquals(1, harness.backend.openCalls, "the same coupled renderer must not force a reopen")
    assertEquals(1, factory.createCount)
    harness.close()
}

@Test
fun `a backend-origin swap does not rebuild and keeps frames flowing`() = runTest {
    val harness = CoreHarness(this, renderer = null)
    harness.open()
    harness.core.play()
    val first = RecordingRenderer()
    harness.core.attachRenderer(first)
    harness.run(200.milliseconds)

    val second = RecordingRenderer()
    harness.core.attachRenderer(second)
    harness.run(300.milliseconds)

    assertEquals(1, harness.backend.openCalls, "portable frames need no reopen")
    assertTrue(second.count > 0, "the second renderer receives frames immediately")
    harness.close()
}

@Test
fun `a paused swap repaints one frame on the new renderer`() = runTest {
    val harness = CoreHarness(this, renderer = null)
    harness.open()
    val first = RecordingRenderer()
    harness.core.attachRenderer(first)
    harness.core.play()
    harness.run(300.milliseconds)
    harness.core.pause()
    harness.run(100.milliseconds)

    val second = RecordingRenderer()
    harness.core.attachRenderer(second)
    harness.run(500.milliseconds)

    assertTrue(second.count > 0, "the paused swap must hand the new renderer a picture")
    assertEquals(1, harness.backend.openCalls, "the repaint is a seek, not a reopen")
    assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
    harness.close()
}

@Test
fun `a coupled swap on an unseekable source warns and stays put`() = runTest {
    val script = MediaScript(seekable = false)
    val ledger = LeakLedger()
    val factoryA = coupledFactory(script, ledger)
    val rendererA = RecordingRenderer(decoderFactories = listOf(factoryA))
    val harness = CoreHarness(
        scope = this,
        script = script,
        ledger = ledger,
        config = PlayerConfig(hardwareDecode = HwdecPolicy.Require),
        renderer = rendererA,
    )
    harness.openWithRenderer()
    harness.core.play()
    harness.run(200.milliseconds)

    val rendererB = RecordingRenderer(decoderFactories = listOf(coupledFactory(script, ledger)))
    harness.core.attachRenderer(rendererB)
    harness.run(300.milliseconds)

    assertEquals(1, harness.backend.openCalls, "an unseekable source must not be reopened")
    val refused = harness.events
        .filterIsInstance<PlayerEvent.Warning>()
        .map { it.warning }
        .filterIsInstance<PlaybackWarning.CommandRefused>()
        .filter { it.member == "attachRenderer" }
    assertTrue(refused.isNotEmpty(), "the impossible rebuild must be said out loud")
    harness.close()
}
```

Adjust imports at the top of the test file if needed (`HwdecStatus`, `HwdecKind`, `HwdecPolicy`,
`PlaybackStatus`, `PlayerEvent`, `PlaybackWarning` are already used elsewhere in this file; add
any that are not).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :kiteplayer-core:jvmTest --tests "*PlaybackCoreTest*" 2>&1 | tail -40`
Expected: the five new tests FAIL on assertions (openCalls stays 1 where 2 is expected, second
renderer count stays 0 in the paused test, no warning in the unseekable test). Pre-existing
tests still pass.

- [ ] **Step 3: Implement**

3a. `OpenSession` (~line 5897), add one field directly after `videoDecoderOrigin`:

```kotlin
        val videoDecoderOrigin: VideoDecoderOrigin?,
        /** The renderer whose factory made [videoDecoder], null for a backend decoder. */
        val coupledRenderer: VideoRenderer?,
```

3b. `buildSession`'s `return OpenSession(` (~line 1988), add the matching argument directly
after `videoDecoderOrigin = ...`:

```kotlin
                videoDecoderOrigin = if (videoStream == null) null else selectedVideoDecoder?.origin,
                coupledRenderer = if (videoStream != null && selectedVideoDecoder?.origin == VideoDecoderOrigin.Renderer) {
                    pendingRenderer
                } else {
                    null
                },
```

If other `OpenSession(` construction sites exist (search for `OpenSession(` first), give each
the same argument derived the same way; as of this writing line 1988 is the only one.

3c. The `AttachRenderer` handler (~line 1277) captures the previous renderer and runs the
follow-up after a successful swap:

```kotlin
            is CoreCommand.AttachRenderer -> {
                val previous = pendingRenderer
                if (setRenderer(command.renderer)) {
                    command.reply.complete(Unit)
                    rendererSwapFollowUp(previous, command.renderer)
                } else {
                    // Warned as well as thrown (audit F-API1): the facade's fire-and-forget form
                    // discards the reply, and a refused attach with no trace is a permanently
                    // black surface nothing explains.
                    val reason = "the video scheduler did not quiesce within $QUIESCE_DEADLINE"
                    warn(PlaybackWarning.CommandRefused("attachRenderer", reason))
                    command.reply.completeExceptionally(
                        IllegalStateException("renderer attach aborted: $reason"),
                    )
                }
            }
```

3d. New private fun, placed directly after `setRenderer` (~line 1485):

```kotlin
    /**
     * What a successful renderer replacement still owes the picture. A decoder coupled to the
     * replaced renderer decodes into that renderer's dead surface, so the video path is rebuilt
     * against the new one through the ordinary track-change rebuild (same pass, position kept,
     * play state kept). An uncoupled swap while not playing repaints once by precise seek,
     * because a parked scheduler presents nothing on its own.
     */
    private fun rendererSwapFollowUp(previous: VideoRenderer?, attached: VideoRenderer) {
        val active = session ?: return
        if (previous === attached) return
        if (active.videoStream == null) return
        val coupledElsewhere = active.videoDecoderOrigin == VideoDecoderOrigin.Renderer &&
            active.coupledRenderer !== attached
        if (coupledElsewhere) {
            // A waiting video selection already carries the rebuild; queueing another for the
            // same kind would supersede the caller's.
            if (TrackKind.Video in pendingSelections) return
            if (!active.source.seekable) {
                warn(
                    PlaybackWarning.CommandRefused(
                        "attachRenderer",
                        "the active video decoder is coupled to the replaced renderer and this " +
                            "source cannot seek, so the picture cannot follow the new renderer " +
                            "until the media is reopened",
                    ),
                )
                return
            }
            queueSelection(TrackKind.Video, TrackId(active.videoStream.index), CompletableDeferred())
            return
        }
        if (!playRequested && pendingSeek == null) {
            queueSeek(SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise), null)
        }
    }
```

Notes for the implementer:
- `active.videoStream` is `val` on `OpenSession`, so the smart cast to non-null after the
  `== null` return is fine; if the compiler disagrees, bind `val stream = active.videoStream ?:
  return` at the top and use `stream.index`.
- `pendingSelections` is keyed by `TrackKind` (see `queueSelection`); `in` works on its keys via
  `containsKey`. If it is a `MutableMap`, write `pendingSelections.containsKey(TrackKind.Video)`.
- Do NOT suspend here; every call used is a plain function posting work the same actor pass
  picks up (`handleTrackChanges` runs right after `drainCommands`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kiteplayer-core:jvmTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, whole module green (the full suite, not only PlaybackCoreTest, to
prove no regression in seek/track/recovery tests).

If `a paused swap repaints one frame` fails with count 0: the queued precise seek landed but the
scheduler did not present while paused. Investigate `handleQueuedSeek`'s landing path before
changing anything; the recovery reopen proves a landed frame is normally presented
(`reportFirstFrame`). Fix belongs in the follow-up fun (for example, only queue the repaint seek
when `status != PlaybackStatus.Idle`), never in the scheduler.

- [ ] **Step 5: ABI check (no public change expected)**

Run: `./gradlew :kiteplayer-core:checkKotlinAbi`
Expected: passes with no dump change (everything in this task is internal).

- [ ] **Step 6: Commit**

```bash
git add kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt
git commit -m "core: renderer swaps rebuild coupled decoders and repaint paused frames

OpenSession now records which renderer its decoder is coupled to. Replacing
that renderer queues the ordinary video-track rebuild against the new one,
same position, same play state. An uncoupled swap while paused repaints one
frame by precise seek. Unseekable sources warn instead of rebuilding."
```

---

### Task 3: Public contract docs for the new semantics

**Files:**
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt`
  (attachRenderer KDoc, ~line 533)
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt`
  (videoDecoderFactories KDoc, ~line 31)

**Interfaces:**
- Consumes: behavior implemented in Tasks 1-2.
- Produces: documentation only; no signature changes.

- [ ] **Step 1: Rewrite the `attachRenderer` KDoc**

Replace the existing KDoc block above `public fun attachRenderer` with:

```kotlin
    /**
     * Attaches a renderer, or replaces the one attached. Legal at any time, including while playing.
     *
     * Video decoding never depends on a renderer existing. With none attached the schedule still paces
     * and releases frames, counting them as `PlaybackStats.headlessFrames`, so detaching costs the picture
     * and nothing else.
     *
     * Replacing a renderer the active video decoder is coupled to (its factory came from that
     * renderer) rebuilds the video path against the new renderer at the current position, keeping
     * the play state; on a source that cannot seek the rebuild is refused with a
     * `PlaybackWarning.CommandRefused` and playback continues without a usable picture.
     * Re-attaching the same renderer object never rebuilds. A replacement while not playing
     * repaints one frame so the new renderer is not blank.
     *
     * The call returns as soon as the request is queued. The engine parks its scheduler before it swaps
     * renderers, so no submission for the old one is outstanding once the swap has happened, but this call
     * does not wait for that moment. A caller that must know its renderer is idle closes the player, or
     * relies on the renderer's own close being safe against a submission in flight, which the one in
     * `kiteplayer-output` is.
     */
```

- [ ] **Step 2: Update the `videoDecoderFactories` KDoc**

In `VideoRenderer.kt`, replace the sentence "Attaching a renderer after open does not
reconfigure the active decoder." with:

```kotlin
     * When this renderer is attached before open, these candidates are tried before the media
     * backend's factories. Attaching a renderer after open keeps the active decoder, with one
     * exception: replacing the renderer the active decoder is coupled to rebuilds the video path
     * against the replacement (see KitePlayer.attachRenderer).
```

- [ ] **Step 3: Compile and check ABI**

Run: `./gradlew :kiteplayer-core:compileKotlinJvm :kiteplayer-core:checkKotlinAbi`
Expected: BUILD SUCCESSFUL, no dump change.

- [ ] **Step 4: Commit**

```bash
git add kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt
git commit -m "core: document the renderer swap contract"
```

---

### Task 4: Identity-checked detach in PlayerViewBinding and both views

**Files:**
- Modify: `kiteplayer-view/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/view/PlayerViewBinding.kt`
- Modify: `kiteplayer-view/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/view/KitePlayerView.kt` (~line 90)
- Modify: `kiteplayer-view/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/view/KitePlayerUIView.kt` (~line 88)
- Test: `kiteplayer-view/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/view/PlayerViewBindingTest.kt`

**Interfaces:**
- Consumes: `KitePlayer.detachRenderer(expected)` from Task 1.
- Produces: `PlayerViewBinding` constructor parameter `detach: (P, R) -> Unit` (internal class;
  no ABI impact). Both views detach identity-checked.

- [ ] **Step 1: Update the test recorders to the new shape (this is the failing test)**

In `PlayerViewBindingTest.kt`, change the factory (~line 14):

```kotlin
    private fun binding(rendererNeedsSurface: Boolean = true) = PlayerViewBinding<String, Int>(
        createRenderer = { (nextRenderer++).also { log += "create $it" } },
        attach = { player, renderer -> log += "attach $player $renderer" },
        detach = { player, renderer -> log += "detach $player $renderer" },
        close = { renderer -> log += "close $renderer" },
        rendererNeedsSurface = rendererNeedsSurface,
    )
```

Then update every expected log literal in the file from `"detach a"` style to `"detach a 0"`
style (the renderer id the same assertion's `create`/`attach` lines use). Grep the file for
`"detach ` and fix each occurrence to name the renderer that was dropped.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :kiteplayer-view:jvmTest 2>&1 | tail -20`
Expected: compilation FAILS (lambda arity) until the binding changes.

- [ ] **Step 3: Implement**

`PlayerViewBinding.kt`:

```kotlin
    private val detach: (P, R) -> Unit,
```

and in `dropRenderer()`:

```kotlin
        try {
            player?.let { detach(it, dropped) }
        } catch (detachFailure: Throwable) {
```

(the surrounding failure-suppression logic stays exactly as it is).

`KitePlayerView.kt` (~line 90):

```kotlin
        detach = { player, renderer ->
            try {
                player.detachRenderer(expected = renderer)
            } catch (_: IllegalStateException) {
                // The ordinary teardown order is close-the-player-then-clear-the-view, and a
                // closed player refuses every command, including this one. Closing already
                // detached everything, so there is nothing left to undo. Found by the S1.e.2
                // smoke's teardownCompleted key; the same latent throw existed here.
            }
        },
```

`KitePlayerUIView.kt` (~line 88): the same change with its own existing comment kept.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :kiteplayer-view:jvmTest :kiteplayer-view:compileKotlinIosArm64 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL. Also compile the Android side:
`./gradlew :kiteplayer-view:compileAndroidMain 2>&1 | tail -5` (if that task name does not
exist, `./gradlew :kiteplayer-view:build -x test` is the fallback).

- [ ] **Step 5: ABI check**

Run: `./gradlew :kiteplayer-view:checkKotlinAbi`
Expected: passes, no dump change (`PlayerViewBinding` is internal; the view lambdas are private).

- [ ] **Step 6: Commit**

```bash
git add kiteplayer-view/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/view/PlayerViewBinding.kt kiteplayer-view/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/view/KitePlayerView.kt kiteplayer-view/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/view/KitePlayerUIView.kt kiteplayer-view/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/view/PlayerViewBindingTest.kt
git commit -m "view: binding detach carries the renderer and detaches identity-checked

A view generation being dropped can no longer remove a renderer someone
newer attached to the same player."
```

---

### Task 5: New module :kiteplayer-compose-ui with KitePlayerVideo

**Files:**
- Modify: `settings.gradle.kts` (after the `:kiteplayer-compose-video` include, line 90)
- Create: `kiteplayer-compose-ui/build.gradle.kts`
- Create: `kiteplayer-compose-ui/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KiteRenderPath.kt`
- Create: `kiteplayer-compose-ui/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KitePlayerVideo.kt`
- Create: `kiteplayer-compose-ui/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/compose/KitePlayerVideo.android.kt`
- Create: `kiteplayer-compose-ui/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/compose/KitePlayerVideo.ios.kt`
- Create: `kiteplayer-compose-ui/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/compose/KitePlayerVideo.jvm.kt`
- Test: `kiteplayer-compose-ui/src/jvmTest/kotlin/io/github/yuroyami/kiteplayer/compose/RenderPathResolutionTest.kt`

**Interfaces:**
- Consumes: `KitePlayerSurface(player, modifier)` (compose-interop), `KiteVideo(state, modifier)`
  + `KiteVideoState.renderer` + `rememberKiteVideoState()` / `rememberKiteVideoState(window)`
  (compose-video), `KitePlayer.attachRenderer` / `detachRenderer(expected)` (core, Task 1).
- Produces: `public enum class KiteRenderPath { Auto, NativeView, ComposeCanvas }` and
  `@Composable public fun KitePlayerVideo(player: KitePlayer?, modifier: Modifier,
  path: KiteRenderPath, onEffectivePath: ((KiteRenderPath) -> Unit)?)`. Task 6 consumes both.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include(":kiteplayer-compose-video")` (line 90):

```kotlin
include(":kiteplayer-compose-ui")
```

- [ ] **Step 2: Write the build file** (`kiteplayer-compose-ui/build.gradle.kts`)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-compose-ui is the runtime-choice layer: one KitePlayerVideo composable hosting
 * either the native-view path (compose-interop) or the true Compose path (compose-video), and
 * able to swap between them while media plays. Consumers wanting exactly one path keep
 * depending on that path's module directly. No web targets: neither underlying path has a real
 * web playback surface yet.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    jvm()

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose.ui"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-compose-interop"))
            api(project(":kiteplayer-compose-video"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
```

- [ ] **Step 3: Write the failing test** (`RenderPathResolutionTest.kt` in jvmTest)

```kotlin
package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPathResolutionTest {

    @Test
    fun jvmCoercesEveryRequestToComposeCanvas() {
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.Auto))
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.NativeView))
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.ComposeCanvas))
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `./gradlew :kiteplayer-compose-ui:jvmTest 2>&1 | tail -15`
Expected: compilation FAILS, `KiteRenderPath` / `resolveRenderPath` unresolved.

- [ ] **Step 5: Write the sources**

`KiteRenderPath.kt` (commonMain):

```kotlin
package io.github.yuroyami.kiteplayer.compose

/**
 * Which rendering product [KitePlayerVideo] hosts.
 *
 * [Auto] picks the platform's sustained-playback default: the native view on Android and iOS,
 * the Compose canvas on JVM desktop, which has no native video view. [NativeView] is the
 * platform-compositor path from kiteplayer-compose-interop; [ComposeCanvas] is the true Compose
 * primitive from kiteplayer-compose-video. A platform that cannot honour a request coerces it
 * and reports what actually runs through KitePlayerVideo's onEffectivePath.
 */
public enum class KiteRenderPath { Auto, NativeView, ComposeCanvas }
```

`KitePlayerVideo.kt` (commonMain):

```kotlin
package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer

/**
 * One video composable over both rendering products, switchable while media plays.
 *
 * [path] is a request. When it changes, the old presentation leaves composition and detaches
 * identity-checked, then the new one attaches to the same running [player]; the engine keeps
 * playing through the swap and rebuilds a coupled decoder by itself (see
 * KitePlayer.attachRenderer). [onEffectivePath] reports what actually runs, which differs from
 * [path] where a platform cannot honour it (JVM has no native video view). The player is never
 * owned here: opening media, playing, seeking and closing stay the caller's, exactly like
 * [KitePlayerSurface].
 */
@Composable
public fun KitePlayerVideo(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    path: KiteRenderPath = KiteRenderPath.Auto,
    onEffectivePath: ((KiteRenderPath) -> Unit)? = null,
) {
    val effective = resolveRenderPath(path)
    val currentOnEffectivePath by rememberUpdatedState(onEffectivePath)
    SideEffect { currentOnEffectivePath?.invoke(effective) }
    key(effective) {
        when (effective) {
            KiteRenderPath.NativeView -> KitePlayerSurface(player = player, modifier = modifier)
            KiteRenderPath.ComposeCanvas -> ComposeCanvasVideo(player = player, modifier = modifier)
            KiteRenderPath.Auto -> error("resolveRenderPath must never return Auto")
        }
    }
}

/** Resolves [requested] to the path this platform runs. Never returns [KiteRenderPath.Auto]. */
internal expect fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath

/** The platform's best [KiteVideoState]: window-bound GPU on Android, portable elsewhere. */
@Composable
internal expect fun rememberPlatformKiteVideoState(): KiteVideoState

@Composable
private fun ComposeCanvasVideo(player: KitePlayer?, modifier: Modifier) {
    val videoState = rememberPlatformKiteVideoState()

    KiteVideo(state = videoState, modifier = modifier)

    LaunchedEffect(player, videoState) {
        val currentPlayer = player ?: return@LaunchedEffect
        // One frame so KiteVideo has laid out and, on Android, bound its GPU path to the window.
        withFrameNanos { }
        currentPlayer.attachRenderer(videoState.renderer)
    }
    DisposableEffect(player, videoState) {
        onDispose {
            try {
                player?.detachRenderer(expected = videoState.renderer)
            } catch (_: IllegalStateException) {
                // A closed player refuses every command; closing already detached everything.
            }
        }
    }
}
```

`KitePlayerVideo.android.kt` (androidMain):

```kotlin
package io.github.yuroyami.kiteplayer.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.Auto, KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

/** The window unlocks the API 31+ GPU path; without an Activity the software state still works. */
@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState {
    val context = LocalView.current.context
    val activity = generateSequence<Context>(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()
    return if (activity != null) rememberKiteVideoState(activity.window) else rememberKiteVideoState()
}
```

`KitePlayerVideo.ios.kt` (iosMain):

```kotlin
package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.Auto, KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
```

`KitePlayerVideo.jvm.kt` (jvmMain):

```kotlin
package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/** JVM has no native video view: the interop surface draws an empty box there, so it coerces. */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath =
    KiteRenderPath.ComposeCanvas

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
```

- [ ] **Step 6: Run to verify it passes, and compile every target**

Run: `./gradlew :kiteplayer-compose-ui:jvmTest :kiteplayer-compose-ui:compileKotlinIosArm64 :kiteplayer-compose-ui:compileKotlinIosSimulatorArm64 2>&1 | tail -15`
Then the Android target: `./gradlew :kiteplayer-compose-ui:compileAndroidMain 2>&1 | tail -5`
(fallback `./gradlew :kiteplayer-compose-ui:build -x test`).
Expected: BUILD SUCCESSFUL everywhere.

- [ ] **Step 7: Create the ABI dumps**

Run: `./gradlew :kiteplayer-compose-ui:updateKotlinAbi :kiteplayer-compose-ui:checkKotlinAbi`
Expected: dumps created under `kiteplayer-compose-ui/api/`, check passes.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts kiteplayer-compose-ui
git commit -m "compose-ui: KitePlayerVideo, one composable over both rendering paths

KiteRenderPath.Auto resolves per platform (native view on mobile, Compose
canvas on JVM). Changing the path recomposes the presentation over the same
running player: identity-checked detach of the old, one-frame-late attach
of the new, and the engine's swap follow-up does the rest."
```

---

### Task 6: Sample demo with a live path toggle

**Files:**
- Modify: `kiteplayer-sample-android/build.gradle.kts` (dependency block, after line 61)
- Create: `kiteplayer-sample-android/src/main/kotlin/io/github/yuroyami/kiteplayer/sample/android/ComposeSwapActivity.kt`
- Modify: `kiteplayer-sample-android/src/main/AndroidManifest.xml`
- Modify: `kiteplayer-sample-android/src/main/kotlin/io/github/yuroyami/kiteplayer/sample/android/SampleLauncherActivity.kt`
- Modify: `kiteplayer-sample-android/src/main/res/layout/activity_launcher.xml`
- Modify: `kiteplayer-sample-android/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `KitePlayerVideo`, `KiteRenderPath` (Task 5), `SampleController` (existing:
  `controller.player`, `openNormally(renderer: VideoRenderer? = null)`, `shutdown()`),
  `SampleComposeScreen(title, detail, controller, outputStats, video)` (existing, see
  ComposeVideoActivity.kt for the call shape).
- Produces: nothing later tasks consume; this is the manual acceptance vehicle.

- [ ] **Step 1: Add the module dependency**

In `kiteplayer-sample-android/build.gradle.kts` after
`implementation(project(":kiteplayer-compose-video"))` (line 61):

```kotlin
    implementation(project(":kiteplayer-compose-ui"))
```

- [ ] **Step 2: Add the strings** (in `res/values/strings.xml`, after `demo_compose_native_detail`)

```xml
    <string name="demo_compose_swap">Live renderer swap</string>
    <string name="demo_compose_swap_detail">KitePlayerVideo switches between the native view and the Compose canvas while the clip keeps playing.</string>
```

- [ ] **Step 3: Write the Activity** (`ComposeSwapActivity.kt`)

```kotlin
package io.github.yuroyami.kiteplayer.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath

/** The live-swap demo: one clip, one player, the presentation flips underneath it. */
internal class ComposeSwapActivity : ComponentActivity() {
    private lateinit var controller: SampleController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)

        setContent {
            var path by remember { mutableStateOf(KiteRenderPath.NativeView) }
            var effective by remember { mutableStateOf("effective=?") }

            SampleComposeScreen(
                title = getString(R.string.demo_compose_swap),
                detail = getString(R.string.demo_compose_swap_detail),
                controller = controller,
                outputStats = effective,
                video = {
                    KitePlayerVideo(
                        player = controller.player,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp)),
                        path = path,
                        onEffectivePath = { effective = "effective=$it" },
                    )
                },
            )
            Button(onClick = {
                path = if (path == KiteRenderPath.NativeView) {
                    KiteRenderPath.ComposeCanvas
                } else {
                    KiteRenderPath.NativeView
                }
            }) {
                Text("Swap renderer (now: $path)")
            }

            LaunchedEffect(Unit) {
                // Two frames so the initial path's renderer is attached before decoder selection.
                withFrameNanos { }
                withFrameNanos { }
                controller.openNormally()
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }
}
```

Adjust to the repo's reality while implementing: if the sample does not use material3, render
the button with the same widget style `SampleComposeScreen` uses internally (open
`SampleComposeUi.kt` and reuse its button composable, placing the swap button in whatever slot
that screen offers; if it offers none, add the button inside the `video` slot above the video in
a `Column`). Do not add a new dependency for one button. `ComposeVideoActivity.onDestroy` shows
whether shutdown is handled by the screen or the activity; mirror it.

- [ ] **Step 4: Register the Activity** (AndroidManifest.xml, after the ComposeVideoActivity entry)

```xml
        <activity
            android:name=".ComposeSwapActivity"
            android:exported="false"
            android:label="@string/demo_compose_swap"
            android:configChanges="orientation|screenSize" />
```

- [ ] **Step 5: Add the launcher entry**

In `res/layout/activity_launcher.xml`, duplicate the `compose_native` Button element, changing
the id to `@+id/compose_swap` and the text to `@string/demo_compose_swap` (keep every other
attribute identical to the sibling). In `SampleLauncherActivity.kt` after the `compose_native`
listener:

```kotlin
        findViewById<Button>(R.id.compose_swap).setOnClickListener {
            open(ComposeSwapActivity::class.java)
        }
```

- [ ] **Step 6: Build the sample**

Run: `./gradlew :kiteplayer-sample-android:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Device smoke (only if an emulator is bootable on this machine)**

Install and open the demo, tap Swap during playback both directions, expect: playback position
keeps advancing across the swap, picture returns within about a second (coupled rebuild), a
paused swap still shows a frame. If no emulator is available, note that in the commit body; the
scripted core tests carry the correctness claim.

- [ ] **Step 8: Commit**

```bash
git add kiteplayer-sample-android
git commit -m "sample-android: live renderer swap demo

Fourth demo Activity: KitePlayerVideo with a runtime KiteRenderPath toggle
over one playing clip."
```

---

### Task 7: README and module docs

**Files:**
- Modify: `README.md` (the "Use kiteplayer-compose-interop when..." bullet list ~line 540, and
  the module table ~lines 558-572)

**Interfaces:**
- Consumes: everything shipped above.
- Produces: docs only.

- [ ] **Step 1: Add the chooser bullet**

After the `kiteplayer-compose-video` bullet (~line 544):

```markdown
- Use `kiteplayer-compose-ui` when the rendering model should be a runtime choice: its
  `KitePlayerVideo(path = ...)` hosts either product and swaps between them while media plays,
  leaning on the engine's coupled-decoder rebuild. It re-exports both underlying modules.
```

- [ ] **Step 2: Add the module table row** (after the `kiteplayer-compose-video` row)

```markdown
| `kiteplayer-compose-ui` | `KitePlayerVideo` + `KiteRenderPath`, the runtime-choice layer over `kiteplayer-compose-interop` and `kiteplayer-compose-video`; a path change swaps the presentation over the running player, with identity-checked detach and the engine's coupled-decoder rebuild keeping position and play state | Android, iOS arm64, iOS simulator arm64 and JVM (JVM coerces NativeView to the Compose canvas) |
```

- [ ] **Step 3: Mention the swap semantics where attachRenderer is described**

Search README.md for `attachRenderer`; if the renderer contract is described anywhere, append
one sentence: "Since the renderer-swap work, replacing the renderer a coupled decoder belongs to
rebuilds the video path at the current position; re-attaching the same renderer is free." If the
README never mentions it, skip this step.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: kiteplayer-compose-ui and the renderer swap contract"
```

---

### Task 8: Full verification pass

**Files:** none created; this task only runs checks.

- [ ] **Step 1: Full JVM test sweep over every touched module**

Run: `./gradlew :kiteplayer-core:jvmTest :kiteplayer-view:jvmTest :kiteplayer-compose-ui:jvmTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: ABI gates**

Run: `./gradlew :kiteplayer-core:checkKotlinAbi :kiteplayer-view:checkKotlinAbi :kiteplayer-compose-ui:checkKotlinAbi 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 3: Cross-target compile**

Run: `./gradlew :kiteplayer-core:compileKotlinIosArm64 :kiteplayer-view:compileKotlinIosArm64 :kiteplayer-compose-interop:compileKotlinIosArm64 :kiteplayer-compose-video:compileKotlinIosArm64 :kiteplayer-compose-ui:compileKotlinIosArm64 :kiteplayer-sample-android:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm nothing unrelated is staged**

Run: `git status --porcelain`
Expected: only the pre-existing dirty files (KPKMP-*.md, gradle.properties,
gradle/libs.versions.toml, and any api dump drift that predates this work) remain unstaged.
Do not commit them.

- [ ] **Step 5: Report**

Summarize to the owner: tests run, files changed per commit, the two follow-ups that are
deliberately NOT in this plan (Tier-1 `MediaCodec.setOutputSurface` retarget spike; Synkplay
wiring: replace its `KiteSwappablePresentation` idea and its debug-only "Kite Compose" engine
with `KitePlayerVideo(path = pref)` once a KitePlayer version carrying this feature is
published to mavenLocal).

---

## Self-review notes (already applied)

- Spec point 2's "same pass" claim is honest: `drainCommands` runs before `handleTrackChanges`
  in the actor's fixed handler order, so a selection queued by the attach handler rebuilds in
  the same pass.
- Spec point 3 is carried by `coupledRenderer` identity, not by the previous delegate: after
  detach-then-reattach of the same renderer, the previous delegate is null but
  `coupledRenderer === attached`, so no rebuild fires. Test two of Task 2 pins this.
- The internal rebuild request deliberately reuses `queueSelection` so a racing user selection
  supersedes it by the existing rules; the `TrackKind.Video in pendingSelections` guard keeps
  the internal request from superseding a user's.
- Type consistency: `detachRenderer(expected)` (Tasks 1, 4, 5), `coupledRenderer` (Task 2),
  `resolveRenderPath` / `rememberPlatformKiteVideoState` / `KiteRenderPath` /
  `KitePlayerVideo` (Tasks 5, 6) are named identically everywhere they appear.
- Known soft spots called out inline rather than hidden: paused-repaint presentation behavior
  (Task 2 Step 4 fallback note), sample UI widget availability (Task 6 Step 3 note), Android
  compile task name variants (Tasks 4, 5).
