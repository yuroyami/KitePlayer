# KitePlayer and KiteFFmpeg: GPT-6 (Codex) audit

Date: 2026-11-08. Model identity supplied to this session: **GPT-6**, running as **Codex**. Parallel reviewers used the same inherited model. The session does not expose a more specific model build identifier.

## Assessment

The inspected implementation does not yet justify universal platform parity, stability under every condition, or performance superiority over mpv, VLC, Media3 and AVPlayer. There are concrete correctness and ownership failures, including defects reproduced against the actual engine, parser, Wasm bridge and owned C code. The existing JVM tests are valuable and numerous, but their passing results do not cover these triggers. Conversely, this audit does not establish that ordinary playback is generally unreliable, and it does not treat every missing feature as a defect.

This is a broad, adversarial, evidence-led audit, not a proof of perfection or an exhaustive verification of every source line, platform interleaving, media format and device. It distinguishes executed reproductions, source-proven failures, unresolved risks, and opportunities. No nanosecond timings, release-build comparative speed claims, native race detections, or device qualification results are fabricated. Those require the measurements and platform work described below.

Only this report is proposed for commit. No implementation, dependency, test, lockfile or release artifact is changed; no GitHub issue is created. The user explicitly requested a report on main and authorized pushing it, superseding the repository's normal issue-first/no-push conventions for this task. Other audit reports are preserved and were not used as evidence for this independent review. Publishing the environment or the libraries is outside this task.

## Revisions and evidence rules

| Component | Audited revision | Meaning |
| --- | --- | --- |
| KitePlayer application source | `d5bb627e0ed35e0b2e3176689fa737a12bad9f94` | The source snapshot restored into this task. |
| KitePlayer main at review start | `b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92` | Adds only another audit report; application source is identical to d5bb627e. |
| KiteFFmpeg source | `f79ba0864c8697f91ef5e3509b46b115afc7dcd7` | Independently audited upstream library. |
| Published artifacts sampled | KitePlayer `0.3.0`, KiteFFmpeg `0.5.1` | Maven Central artifact evidence is identified separately below. It does not prove the whole current source tree has shipped. |
| Native libraries used in focused C probes | `ffmpeg-n9.0.2-r3`, LGPL Linux x64 | Release ZIP SHA-256 and all 15 checked-in patch hashes verified. |

Paths in each lane are relative to its named owning repository. Line numbers refer to these immutable source snapshots. For a durable source link, use `https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/<path>#L<line>` or `https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/<path>#L<line>`. Ellipses in narrative paths identify an abbreviated package; the corresponding exact filenames and reproduction source lists remove ambiguity.

Severity describes the demonstrated trigger and impact: P1 is a substantial ownership, availability or data-loss failure; P2 is a narrower correctness, interoperability or resource-control failure; P3 is a lower-impact documentation/tooling defect. Severity is not an exploitability score. A pathological document triggering excessive work is not automatically a code-execution vulnerability. Source proof is not described as an executed device failure.

The review searched downloaded bodies/titles of **690 KitePlayer issues and 205 KiteFFmpeg issues**, including open and closed entries, and read comments for the closest matches. Findings linked to existing issues are confirmations, narrower residuals or regressions, not claims of novel discoveries. "No match found" is bounded by that search and the inspected comments. No issue was posted. A closed tracker entry is not accepted as proof a particular call path is correct, and a failing remote CI run is not accepted as proof of a deterministic playback bug.

## Reproduction and validation boundaries

The current host is Linux x64 with JDK 21.0.13 and Kotlin Toolchain 0.13.0. Repository suites use the pinned Kotlin 2.4.21. Some isolated probes initially used the wrapper-bundled compiler 2.4.10; those are labeled. Correctness probes are not release-performance benchmarks. Temporary drivers and minimal stubs are explicitly identified; none replace the behavior being tested. Drivers embedded in the appendices keep the important evidence reviewable without relying on this machine's temporary files.

The wrapper initially hit read-only/default-cache and JVM proxy routing problems. Tests were run after placing caches under `/workspace/.cache`, passing the existing HTTP(S) proxy to the JVM and using the system CA trust store. TLS and wrapper/artifact checksums remained enabled. These were environment setup problems, not library defects. No environment draft changes were made during this audit.


## Finding index and repair order

There are **30 defect entries** in this index, including known defects, narrower residuals, and validation/documentation defects. Additional risks and rejected hypotheses are explicitly labeled and are not counted as demonstrated defects.

The detailed entries below contain the trigger, affected owner, source locations, evidence strength, counterevidence, tracker relationship and recommended regression check. New means no matching defect found in the bounded tracker search, not a guarantee nobody has reported it elsewhere.

| ID | Priority | Owner | Finding | Evidence / tracker relationship |
| --- | --- | --- | --- | --- |
| E1 | P1 | KitePlayer | Preload teardown runs before terminal close deadline | Actual engine fault injection; residual #473/#590 |
| E2 | P1 | KitePlayer | Callback cancellation skips independent cleanup | Actual engine fault injection; residual #472/#589 |
| E3 | P2 | KitePlayer | Setting coalescing copies retained history quadratically | Exact-method probe and source complexity; residual #483 |
| E5 | P2 | KitePlayer | Failed seek keeps reporting its unlanded target | Actual engine fault injection; extension of #255 |
| N1 | P1 | KitePlayer | Live DASH SegmentList maps current timing to expired URLs | Executed parser; new |
| N2 | P1 | KitePlayer | Explicit DASH API loses redirect's final resolution base | Source call chain; new |
| N3 | P2 | KitePlayer | Period-level and partial list/base inheritance missing | Executed parser + source; residual #232 |
| N4 | P2 | KitePlayer | Thumbnail tile-count multiplication overflows | Executed parser/writer; new |
| N6 | P1 | KitePlayer | Tiny live timeline induces billions of expired-entry iterations | Exact source + bounded timeout; new |
| API-1 | P1 | KitePlayer | TTML timed-run expansion defeats output/work budgets | Executed pinned parser; new distinct from #408/#594 |
| API-2 | P2 | KitePlayer | Inline sequential TTML timing ignored | Executed pinned parser; related #492 |
| API-3 | P2 | KitePlayer | Retired web session clears the current owner's controls | Actual Wasm bridge + modeled browser object; new |
| API-4 | P2 | KitePlayer | Timed TTML region does not limit text visibility | Executed parser/selector; new |
| AV-1 | P1 | KitePlayer | NaN PCM kills desktop writer, leaving restart inert | Actual C ring + actual sink/seam; new |
| AV-2 | P2 | KitePlayer | Unity gain crossing abruptly changes transfer curve | Actual C waveform samples; attach to #15 |
| KF-1 | P1 | KiteFFmpeg | JNI caption decode calls Java within critical region | Source contract violation; known open #207 |
| KF-2 | P1 | KiteFFmpeg | Cancellation before frame delivery leaks clone | Source ownership proof; known open #199 |
| KF-3 | P1 | KiteFFmpeg | Poisoned pre-header sink close can truncate output | Source error path; known open #216 |
| KF-4 | P2 | KiteFFmpeg | Pixel conversion ignores encoder destination colour | Actual helper differential + source; known open #195 |
| KF-5 | P2 | KiteFFmpeg | Canceled transcode frees encoders before drain | Source ordering proof; known open #202 |
| KF-6 | P2 | KiteFFmpeg | AES reader accepts malformed PKCS#7 padding | Actual production C reader; new |
| DIST-1 | P1 | KiteFFmpeg | Cold native setup does not provision link sysroot | Exact-head CI log + source; no match found |
| DIST-2 | P1 | KiteFFmpeg | Parallel native setup consumes incomplete extraction | Exact-head CI log + source; no match found |
| DIST-3 | P2 | KitePlayer | External Central consumer probes a runtime-only type | Exact-head CI + published metadata; harness defect |
| DIST-4 | P2 | KitePlayer | Invalid KDoc links block docs and release gate | Exact-head CI + source; regression related #272 |
| DIST-5 | P3 | KitePlayer | Coverage checker accepts commented-out tests | Executed checker negative control; relates #637 |
| DIST-6 | P3 | KitePlayer | NOTICE points at deleted licence-resource paths | Source-path checks; bundled licence absence not alleged |
| VIZ-1 | P2 | KitePlayer | Song-map allocation scales with timestamp gaps | Actual storage kernels, constrained-heap probes; new |
| VIZ-2 | P2 | KitePlayer | Corrupt map counts allocate before payload validation | Actual extracted codec, constrained-heap probe; new |
| VAL-1 | P3 | KitePlayer | Raster test centers shadow bitmap rather than text | Two isolated failures + actual rasterizer probe; harness defect |

Address actual resource/data-loss failures and bounded-work gaps first, repair fresh-cache/release verification in parallel, then close timing/colour/session correctness gaps with targeted regressions. Fix KF/DIST upstream entries in KiteFFmpeg and consume a corrected artifact in KitePlayer; do not create codec-specific workarounds or couple upstream build logic to the player.

The additional audioviz entries and the rasterizer test observation below are part of this report too. Risks such as temporary-cache disk use, seek-job ownership and default-driver drain are explicitly separated from demonstrated defects.

## Tests executed in this task

`./kotlin test -p jvm -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io -m kiteplayer-adaptive` completed with exit **0**. The current run's XML reports show:

| Module | Tests executed | Passed | Failed | Skipped/aborted |
| --- | ---: | ---: | ---: | ---: |
| kiteplayer-core | 1616 | 1616 | 0 | 0 |
| kiteplayer-subtitles | 187 | 187 | 0 | 0 |
| kiteplayer-io | 87 | 87 | 0 | 0 |
| kiteplayer-adaptive | 191 | 191 | 0 | 0 |

`./kotlin test -p jvm -m kiteplayer-output -m kiteplayer-network -m kiteplayer-view` completed with exit **1**, attributable to the output module:

| Module | Tests started | Passed | Failed | Assumption-aborted |
| --- | ---: | ---: | ---: | ---: |
| kiteplayer-output | 210 | 207 | 1 | 2 |
| kiteplayer-network | 167 | 167 | 0 | 0 |
| kiteplayer-view | 94 | 94 | 0 | 0 |

The output failure is `DesktopSubtitleRasterizerTest.a positioned cue's bitmap is its text extent, anchored on the authored point`: expected x=304, actual x=305. See the diagnostic observation below; this result is not silently converted to a pass. Two `DesktopAudioOutputDeviceTest` tests abort by assumption because this host has no eligible output device. These do not validate device listing/opening. Aggregate across the two commands: **2552 started, 2549 passed, 1 failed, 2 assumption-aborted**. Focused fault probes are additional experiments, not added to these suite totals. JUnit's runner labels aborted tests separately; XML uses skipped for these assumptions.

`./scripts/check-dependency-hygiene.sh` passed. The documentation-only change selects **Tier 1** under CONTRIBUTING.md. `./scripts/check-gate.sh tier1` was attempted and exits **2** with `The local gate requires a macOS arm64 host; CI covers other hosts.` It is not claimed to have passed. Running the Linux suites is useful independent evidence and does not impersonate this missing host gate. No source/ABI changes select Tier 2 or Tier 3 for this report.

No complete KiteFFmpeg Kotlin suite, end-to-end transcode, native sanitizer/fuzzer campaign, physical-device suite, or competitor benchmark was run in this task. Historical CI results and artifact checks below are explicitly separate from current-instance execution.



# Engine, concurrency and lifecycle


Source reviewed: KitePlayer d5bb627e0ed35e0b2e3176689fa737a12bad9f94, identical application source at updated main b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92. No source or tracked test changes made by this lane. Full-module suite results are recorded in the validation table above. Additional standalone friend-path probes execute the current built core and its existing test harness without modifying tracked files.

## E1. P1: Close releases a prepared preload before entering its reporting deadline

Owner: KitePlayer. Classification: actual-core synthetic fault-injection reproduction plus source proof. No device release measured.

Paths at source pin:

- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2919](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L2919): execute calls `dropPending(null)` before dispatching the command when dropsPreload is true.
- Same file `:11417-11426`: dropsPreload explicitly includes `CoreCommand.Close`, Stop, Seek and other control changes.
- Same file `:11346-11384`: dropPending releases an already prepared session, or the successful result of a completed build, through `releaseSession` inline. Handed-off branch calls takeRingBack, whose `:11401` also releases inline.
- Same file `:12502-12598`: only subsequently can runClose transfer preload ownership to ClosingGraph and start awaitRelease's deadline.
- Same file `:12889-12893`: teardownSession also calls dropPending before releaseWithinDeadline.

Trigger: enable normal gapless playback, queue two short items, play until `state.preloadedIndex` reports the next item, and close while releasing that next item's backend session/decoder/device blocks. A custom PlayerBackend with close gated by a bounded latch suffices; a wedged native close is the production failure this resilience contract promises to handle. Before handoff, the prepared session has no live sink but still owns native decoder/source resources whose close can block. After handoff more resources exist.

Trace: Close is enqueued -> execute -> dropPending -> inline NonCancellable releaseSession -> blocking close. `runClose` never runs, its close deadline never starts, the actor cannot settle terminalCloseResult, and closeAndAwait suspends indefinitely. The active item's graph remains installed, as it was not detached yet. A timeout around the caller does not stop this detached NonCancellable release.

Counterevidence checked: runClose itself carefully contains takePreloadForClose/ClosingGraph under one deadline. That code does not protect a preload already consumed by execute. CommandTruthTest's stop/close wedge tests and CloseDeadlineJvmTest's recording test cover other paths. GaplessQueueTest tests preload stop/close cleanup without a blocked release, so ordinary green cleanup does not refute this trigger.

Tracker: known residual of closed [#473](https://github.com/yuroyami/KitePlayer/issues/473), which explicitly names preload release before deadline and asks for preload blocking tests. Also related #590 (comment says fixed in 379cd793); that fix wraps only the active detached session, while dropPending still bypasses it. Read bodies and comments 2026-11-08. This should be reopened/extended, not filed as an unrelated discovery.

Remedy: special-case terminal Close before any generic preload drop, letting runClose own the entire preload graph; route nonterminal drop releases through the same independently owned bounded release mechanism. Keep the incoming/active audio ownership order intact. Regression: hold next-session release beyond closeDeadline with a self-releasing latch, assert RuntimeCompromised by deadline and exactly-once cleanup after release, for prepared/unadopted/handed-off preloads and Stop followed by Close.

## E2. P1: A callback-thrown CancellationException escapes the release ledger and abandons unrelated resources

Owner: KitePlayer. Classification: actual-core JVM reproduction on pinned Kotlin 2.4.21; no device resources opened.

`PlaybackCore.kt:13005-13018`: releaseSession is explicitly NonCancellable, yet the local release helper rethrows CancellationException instead of recording it. `:13035-13048` invokes the renderer overlay withdrawal and device stop through this helper before source interrupt, worker cancellation/join, decoder closes, queued-packet disposal and backendSession.close (`:13052-13102`).

Trigger: a custom VideoRenderer's suspending setOverlay(empty) throws CancellationException (including a timeout exception from an internal withTimeout), after subtitles were published. Its coroutine need not be cancelled: this is the exact distinction already recognized by throwIfOwnCancellation for actor/workers. A sink stop or decoder close throwing the same exception opens later variants of the same hole.

Result: exception exits the NonCancellable release before all following releases. awaitRelease catches/reports the failure through releaseReporting and terminal close becomes RuntimeCompromised, but it never schedules the skipped cleanup. Hence worker jobs, decoders and backend session can remain live with no remaining owner trying to release them. For stop, releaseWithinDeadline rethrows the cancellation and loop recovery also fails to reacquire the already-detached graph. Unlike a true blocked native call that is intentionally retained until it returns, this callback has already returned by throwing; continuing independent release is possible.

Counterevidence: SubtitleDrawFailureTest injects IllegalStateException specifically for overlay withdrawal; that takes the generic Throwable ledger branch and does clean up. StrayCancellationTest covers read/factory/decode/selectStreams, not teardown callbacks. A NonCancellable context does not prevent user code from explicitly throwing CancellationException. It only prevents cancellation of the coroutine context.

Tracker: residual combination of closed [#472](https://github.com/yuroyami/KitePlayer/issues/472) and [#589](https://github.com/yuroyami/KitePlayer/issues/589). Their full bodies/comments were read. Do not report their old broader failures as still wholly unfixed; this is the remaining cancellation subclass in the newly added cleanup ledger.

Remedy: in detached-graph NonCancellable cleanup, collect callback-thrown CancellationException like other callback failures, or rethrow only verified cancellation of the owned cleanup context. Continue worker ownership fences and all independent resource releases. Extend SubtitleDrawFailureTest's withdrawalFailure from IllegalStateException to CancellationException and assert backend close count 1 and ledger.liveCount 0 for stop/close/rebuild.

## E3. P2: Latest-setting coalescing has quadratic copying and still retains an unbounded number of commands

Owner: KitePlayer. Classification: exact source algorithm plus isolated exact-method JVM probe, pinned Kotlin 2.4.21; no production release performance benchmark claimed.

`PlaybackCore.kt:2492-2503` enqueues one ApplyLatest token, using latestLock. `CoreCommand.takeOver` at `:16797-16802` creates a new ArrayList for the NEW command, copies all `older.replaced` entries into it, clears the old list, then appends older. Every replacement repeats that full copy while holding the shared mailbox lock. `answerReplacedWithThis` only discards this history when the actor takes and answers the newest command.

With N same-kind setters while the actor is unavailable, the exact number of previous-history references copied by addAll is `(N-1)*(N-2)/2`, and N commands/deferred replies remain strongly retained despite waitingInMailbox reporting one. 10,000 updates copy 49,985,001 references; 100,000 updates copy 4,999,850,001 references, before ArrayList growth copies and allocations. This is algorithmic cost, not a timing estimate.

Reachable from ordinary public fire-and-forget `KitePlayer.setVolume` (`KitePlayer.kt:381-387`) and most settings: each creates a fresh CompletableDeferred and posts a CoreCommand even though no caller will await that reply. During slow open/seek or another actor wait, a control publisher can issue updates without suspension or admission refusal. Setter cost therefore grows with previous backlog, UI threads can stall in the lock, and the supposed mailbox memory bound does not bound retained commands. Awaited internal setters also retain necessary replies, but need not copy them quadratically.

Counterevidence: LatestSettingReplyJvmTest serially awaits each core.setVolume from two threads; it tests exactly-once replies, not a large backlog. Existing 10,000-change mailbox test checks token count/last setting/all replies, which this implementation meets while paying quadratic cost. No claim of a measured nanosecond regression, memory exhaustion threshold, or malicious-media-only trigger.

Tracker: residual of closed [#483](https://github.com/yuroyami/KitePlayer/issues/483). Read full body and comment; the comment claims one command per kind and bounded requests but explicitly leaves real-time load unmeasured. Do not file duplicate broad mailbox-unbounded issue; call out replacement-history structure specifically.

Remedy: distinguish fire-and-forget settings from awaited replies, discard superseded no-wait settings immediately, and transfer ownership of one existing waiter list in O(1) rather than copying it. Define a bound/admission policy for retained awaited replies separately from token count. Test allocated bytes/copy count or scaling with actor deliberately held, then release and verify all awaited replies exactly once.

## Rejected candidate E4: supposed MediaClock failure after 106 days was disproved

A direct probe compiled the actual MediaClock.kt, Pts.kt (including Generation), MonotonicClock.kt and /tmp/kite-engine-probe/ClockProbe.kt under standalone Kotlin 2.4.10. It returned:

```
anchorUs=1000000 readUs=1000000 identity=true
anchorUs=9223372036854775 readUs=9223372036854775 identity=true
anchorUs=9223372036854776 readUs=-9223372036854775 identity=false
anchorUs=9244800000000 readUs=9244800000000 identity=true
```

The absolute media microseconds-to-nanoseconds conversion does overflow, but only above approximately 292 YEARS, not 106 days. The 107-day control succeeds; the much larger numerical boundary does not establish a representative long-session failure. Do not list this as a long-running-playback defect. At most, the extreme finite public Pts range and internal clock range differ; no representative user impact established. The signed-Long boundary is a low-priority numerical range qualification, not a shipping media capability failure.

## E5. P2: A seek that fails mid-operation leaves position() permanently reporting its unlanded target

Owner: KitePlayer. Classification: actual-core JVM reproduction on pinned Kotlin 2.4.21 plus source trace.

`PlaybackCore.kt:2072-2080` installs maskedSeekTargetMicros before sending a seek. `:12143-12157` handles interrupted/failed native seek by teardownSession(), fail(error), then resolveSeekReplies(Rejected). Neither detachSession (`:12866-12887`) nor teardownSession (`:12889-12893`) nor resolveSeekReplies (`:11991-11996`) clears the mask. `handlePlaybackTime` (`:7865-7873`) returns at once when the session is null, before the normal mask clear. `position()` (`:2341-2344`) always prefers a set mask.

Trigger: a normal open/playing file followed by seek whose backend fails (InterruptSeamTest already provides seekWedges and a supported interrupt). The physical target never lands and session fails. After receiving Rejected (public seek throws IllegalStateException), position() still returns the requested target on every call until stop/open/close. This masks the last known progress and contradicts the mask's declared lifetime, which is only queued/in-flight requests. The same issue can arise in exceptions handled by handleQueuedSeek's other teardown branches.

Tracker: residual extension of closed [#255](https://github.com/yuroyami/KitePlayer/issues/255), whose fix comment and RefusedSeekMaskTest cover seeks refused on an ALREADY Idle/Failed player. They do not cover an accepted seek that destroys its session during execution. No duplicate issue posting done.

Remedy: withdraw mask on terminal seek settlement with no pending successor, or on detachment/failure, preserving the last actual published position. Add position assertions to InterruptSeamTest and seek-fault/recovery-failure tests, including a newer pending target so clearing cannot erase a valid successor's mask.

## E6. P2 risk: native-seek timeout grace does not retain/join the in-flight seek job as a session resource

Owner: KitePlayer. Classification: source-supported lifecycle risk. Requires an interrupt-capable backend whose call does not actually finish within grace; no concurrent-close or native use-after-free demonstrated.

`boundedContainerSeek` (`PlaybackCore.kt:4836-4869`) and `runSeek` (`:12129-12157`) create `scope.async(dispatchers.demux)` seek jobs outside `session.jobs`. After interrupt returns true but the grace wait expires, they synthesize Result.failure while those jobs can still run. Teardown cancels/joins only `session.jobs` (`:13054-13056`) then calls backendSession.close (`:13102`), never joining those seek jobs explicitly. On the real blocking demux lane, ordinary worker joins/decoder context scheduling can keep release blocked behind the seek and the release deadline at least reports a wedge. But a custom cooperative suspend seek, or a start seek before worker launch, need not supply that accidental fence: backend close can happen before the seek returns. Source SPI says interrupt is the only permitted concurrent source call and resources must not close underneath active work.

Do not escalate this into a confirmed built-in FFmpeg use-after-free without a concrete target probe. Improvement: make seek jobs part of the ownership graph and prohibit source close before they complete; allow the actor's reporting deadline to expire without abandoning that ownership.

Tracker: #591 fixed the missing initial/rebuild seek deadlines; its requested helper now exists. The remaining outstanding-job ownership is a narrower question. Existing InterruptSeamTest's interrupt opens its gate promptly, so it never exercises this grace-expired branch.

## Coverage and important non-findings

Read/traced actor admission and reply completion, command legality, runLoop recovery, open/preemption, cross-context acquisition rollback, user/native seek, worker park/release handshakes, stop/close finalization, preload creation/adoption/drop, packet and frame queues, MediaClock/SyncLaw, and matching scripted tests. Read relevant engine invariants in CLAUDE.md. Findings above are common engine code, so they apply across threaded targets; no device-specific crash is claimed.

The engine does have meaningful ownership defenses: worker handshakes before destructive mutation, generation-tagged queues, late packet close, non-cancellable detached graph release, reply settlement, independent close finalizer, audio/video queue bounds, explicit overload rejection for ordered public requests, per-pass actor fairness, acquireAcrossContext rollback, and separate cancellation-vs-callback-failure treatment in worker launch. These should be preserved while fixing remaining holes. No generic claim that all shared plain fields are automatically racy was made: most are lane confined, or used under worker quiescence.

Not established: real audio/video deadlines or competitor performance, Apple/Android hardware behavior, release-build warmup/tail latency, actual long-running playback, or malicious-media exploitability. CaptureMoment has worker reads of some actor-owned plain subtitle fields, but a concrete wrong-picture reproduction is not established, so not promoted to a defect. PacketQueue numeric edge cases and per-packet cache scan cost deserve follow-up; no independent proof of a real-file failure was obtained.


## Executed probe evidence and reproduction

Probes below compile against current `kiteplayer-corejvm` and `kiteplayer-corejvmTest` outputs after the repository suite compiled them, with the pinned compiler 2.4.21, stdlib 2.4.21 and project-resolved test/coroutine dependencies. They are bounded synthetic tests, not release workload benchmarks. They open no real audio/video devices.

E2 output (exit 0):
```
closeFailure=PlaybackException backendCloseCount=0 liveHandles=366
```

E5 output (exit 0):
```
before=190.333ms result=Rejected(reason=the container seek did not complete: the scripted seek was interrupted) status=Failed positionAfter=2s progressAfter=2s
positionStillMaskedAfter1s=2s
```

E3 exact CoreCommand-method extraction output (exit 0):
```
queuedSettings=10000 retainedReplacedCommands=9999 waitingTokens=1
allRepliesCompleted=true
```

E1 uses reflection ONLY to install a delegated `BackendSession.close` fault on the prepared preload; normal engine APIs create and preload the queue first. The injected close sleeps 500ms and then calls the actual scripted close. The independent release lane is explicitly named `probe-independent-release`. The close itself runs on main, demonstrating it bypasses that lane and blocks the actor before the deadline mechanism. Initial stronger assertion that terminal close would eventually report success was false: it later reported PlaybackException, since the active graph release can overrun its very short virtual deadline. That assertion was removed; the finding claims delay-before-deadline, never missing eventual error reporting. All latches/blocks are bounded and eventual synthetic cleanup is checked.

### CleanupProbe.kt

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.yuroyami.kiteplayer
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
fun main() {
 runTest(timeout=10.seconds) {
  val script=MediaScript(durationUs=6_000_000,subtitleCues=listOf(SubtitleCue.Text(500_000,2_500_000,listOf(StyledSpan("first")))))
  val harness=CoreHarness(this,script=script)
  harness.openWithRenderer()
  harness.core.play()
  harness.run(800.milliseconds)
  val renderer=checkNotNull(harness.renderer)
  check(renderer.overlays.last()?.images?.size==1)
  renderer.withdrawalFailure=CancellationException("stray audit cancellation")
  val session=harness.session
  val result=runCatching { harness.core.closeAndAwait() }
  println("closeFailure=${result.exceptionOrNull()?.javaClass?.simpleName} backendCloseCount=${session.closeCount} liveHandles=${harness.ledger.liveCount}")
  check(session.closeCount==0)
  check(harness.ledger.liveCount>0)
  harness.stopDevice()
 }
}
```

### SeekMaskProbe.kt

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.yuroyami.kiteplayer
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
fun main()=runTest(timeout=20.seconds) {
 val faults=FaultPlan().apply { seekWedges=true }
 val harness=CoreHarness(this,script=MediaScript(durationUs=5_000_000),faults=faults)
 harness.openWithRenderer()
 harness.core.play()
 harness.run(200.milliseconds)
 val before=harness.core.position()
 val result=harness.core.seek(Pts(2_000_000),SeekMode.Precise)
 println("before=$before result=$result status=${harness.core.snapshots.value.status} positionAfter=${harness.core.position()} progressAfter=${harness.core.progress.value.position}")
 check(harness.core.snapshots.value.status==PlaybackStatus.Failed)
 check(harness.core.position()==2.seconds)
 harness.run(1.seconds)
 check(harness.core.position()==2.seconds)
 println("positionStillMaskedAfter1s=${harness.core.position()}")
 harness.close()
}
```

### PreloadCloseProbe.kt

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.yuroyami.kiteplayer
import io.github.yuroyami.kiteplayer.spi.BackendSession
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
fun main() {
 val releaseLane=Executors.newSingleThreadExecutor { r-> Thread(r,"probe-independent-release").apply{isDaemon=true} }.asCoroutineDispatcher()
 try {
 runTest(timeout=10.seconds) {
  val harness=CoreHarness(this,script=MediaScript(durationUs=3_000_000,hasVideo=false),releaseDispatcher=releaseLane,closeDeadline=10.milliseconds)
  harness.core.openQueue(listOf(MediaItem("scripted://first"),MediaItem("scripted://second")),0)
  harness.core.play()
  repeat(600) {
   if(harness.core.snapshots.value.preloadedIndex!=1) harness.run(5.milliseconds)
  }
  check(harness.core.snapshots.value.preloadedIndex==1)
  val next=checkNotNull(field(harness.core,"pendingNext"))
  val prepared=checkNotNull(field(next,"prepared"))
  val incoming=checkNotNull(field(prepared,"session"))
  val original=field(incoming,"backendSession") as BackendSession
  var callbackThread=""
  // Fault injection only: backend close blocks for a bounded 500 ms, then cleans up normally.
  val wrapped=object:BackendSession by original {
   override fun close() { callbackThread=Thread.currentThread().name; Thread.sleep(500); original.close() }
  }
  incoming.javaClass.getDeclaredField("backendSession").apply{isAccessible=true}.set(incoming,wrapped)
  val started=System.nanoTime()
  val result=runCatching{harness.core.closeAndAwait()}
  val elapsed=(System.nanoTime()-started)/1_000_000
  println("closeDeadlineMs=10 callbackBlockMs=500 closeFailure=${result.exceptionOrNull()?.javaClass?.simpleName} elapsedWallMs=$elapsed callbackThread=$callbackThread")
  check(elapsed>=450)
  check(callbackThread!="probe-independent-release")
  val until=System.nanoTime()+2_000_000_000L
  while(harness.ledger.liveCount>0 && System.nanoTime()<until) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){kotlinx.coroutines.delay(5)}
  check(harness.ledger.liveCount==0)
  harness.stopDevice()
 }
 } finally {releaseLane.close()}
}
```

### ExtractedCoreCommand.kt

```kotlin
package io.github.yuroyami.kiteplayer.internal
import kotlinx.coroutines.CompletableDeferred
internal sealed class CoreCommand(val name: String, private val deferred: CompletableDeferred<*>) {

    fun fail(cause: Throwable) {
        deferred.completeExceptionally(cause)
    }

    /** True while this command holds a place in the bound on waiting requests (#483). */
    var counted: Boolean = false

    /** The settings of this kind that this one replaced while they waited, oldest first (#483). */
    private var replaced: ArrayList<CoreCommand>? = null

    /** Takes [older]'s place, and with it the calls [older] had replaced. Under the mailbox's lock. */
    fun takeOver(older: CoreCommand) {
        val list = replaced ?: ArrayList<CoreCommand>().also { replaced = it }
        older.replaced?.let(list::addAll)
        older.replaced = null
        list += older
    }

    /**
     * Answers every setting this one replaced when this one is answered, and the same way (#483):
     * this value is what they set, so they applied, or failed, then. One handler for them all, so a
     * long run of replaced calls is answered in a loop and not a chain.
     */
    @Suppress("UNCHECKED_CAST")
    fun answerReplacedWithThis() {
        val list = replaced ?: return
        replaced = null
        deferred.invokeOnCompletion { cause ->
            for (older in list) {
                if (cause == null) (older.deferred as CompletableDeferred<Unit>).complete(Unit) else older.deferred.completeExceptionally(cause)
            }
        }
    }

    class SetVolume(val value: Float, val reply: CompletableDeferred<Unit>) : CoreCommand("setVolume", reply)
}
```

### CoalescingProbe.kt

```kotlin
package io.github.yuroyami.kiteplayer.internal
import kotlinx.coroutines.CompletableDeferred
fun main() {
 var newest=CoreCommand.SetVolume(0.5f,CompletableDeferred())
 val all=mutableListOf(newest)
 repeat(9999) {
  val command=CoreCommand.SetVolume(0.75f,CompletableDeferred())
  command.takeOver(newest)
  newest=command
  all.add(command)
 }
 val history=CoreCommand::class.java.getDeclaredField("replaced").apply { isAccessible=true }.get(newest) as ArrayList<*>
 println("queuedSettings=10000 retainedReplacedCommands=${history.size} waitingTokens=1")
 check(history.size==9999)
 newest.answerReplacedWithThis()
 newest.reply.complete(Unit)
 check(all.all { it.reply.isCompleted })
 println("allRepliesCompleted=true")
}
```

### Reproduce compilation against built current core/test classes

The below uses dependency artifacts the repository toolchain has already resolved. It does not fetch an alternative compiler or change sources. Replace the source filename/main class for each driver. The exact-method extraction probe can omit friend-path flags.

```python
import glob, subprocess
cache = "/workspace/.cache/java-home/.cache/JetBrains/Kotlin/.m2.cache"
std = cache + "/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar"
compiler = cache + "/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.jar"
extra = "/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars/"
compiler_cp = ":".join([compiler, std] + [extra + name for name in [
    "kotlin-reflect-2.3.20-RC2.jar", "kotlin-script-runtime-2.4.10.jar",
    "annotations-26.0.2.jar", "kotlinx-coroutines-core-jvm-1.10.2.jar"]])
outputs = glob.glob("/workspace/KitePlayer/build/artifacts/CompiledJvmArtifact/*/kotlin-output")
runtime_cp = ":".join([std] + outputs + glob.glob(cache + "/**/*.jar", recursive=True))
subprocess.run(["java", "-cp", compiler_cp,
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
    "-classpath", runtime_cp, "-Xfriend-paths=" + ",".join(outputs),
    "-d", "/tmp/probe.jar", "/tmp/CleanupProbe.kt"], check=True)
subprocess.run(["java", "-cp", "/tmp/probe.jar:" + runtime_cp,
    "io.github.yuroyami.kiteplayer.CleanupProbeKt"], check=True, timeout=30)
```

E1 corrected probe output (exit 0; all synthetic handles subsequently released):
```
closeDeadlineMs=10 callbackBlockMs=500 closeFailure=PlaybackException elapsedWallMs=525 callbackThread=main
```


# Adaptive streaming, network and IO


Reviewed KitePlayer source at d5bb627e0ed35e0b2e3176689fa737a12bad9f94 (main later acquired a documentation-only commit). No repository source edits. No GitHub issues created. Findings below belong in KitePlayer, not KiteFFmpeg.

## N1 (P1): live SegmentList timelines pair current timing with expired segment URLs

Evidence: **reproduced directly from unchanged parser source on JVM**. [kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt:836-856](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt#L836) calls `expandTimeline(... window ...)`, whose `:990-992` only returns segments still in the window. It then indexes that filtered result with the position in the *unfiltered* `list.segments` URL list. `TimelineSegment.index` already preserves the original index, but this caller ignores it.

Minimal MPD, fetched at `https://cdn.test/live/movie.mpd`, with `nowMicros=8_000_000`:

```xml
<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" timeShiftBufferDepth="PT4S">
 <Period start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4">
  <Representation id="v" bandwidth="1"><SegmentList timescale="1">
   <Initialization sourceURL="init.mp4"/>
   <SegmentTimeline><S t="0" d="2" r="3"/></SegmentTimeline>
   <SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/>
   <SegmentURL media="s2.m4s"/><SegmentURL media="s3.m4s"/>
  </SegmentList></Representation>
 </AdaptationSet></Period>
</MPD>
```

Call `DashManifestParser.timedPlan(manifest, period, representation, DashUrlPolicy.Default, 8_000_000L)` in the same module. Actual exact-source output: `s0.m4s:4000000:1, s1.m4s:6000000:2`. Required: `s2.m4s:4000000:3, s3.m4s:6000000:4`. Once old entries leave the window, playback requests expired media or replays old content while advertising the new timestamps; generated HLS media sequences are wrong too. A static list does not expose this, which is why static SegmentList tests are insufficient. Live tests inspected primarily use templates.

Fix direction: join surviving timeline entries to `list.segments[entry.index]`, keeping original segment numbers, or retain all timing positions and filter only after URLs are paired. Add a live list test with expired prefix, then advance clock and compare exact URLs/numbers/times. Related tracker #406 concerns adaptation-set rebinding, a distinct index space; no matching live-list issue was found in the downloaded 690 player issue bodies/titles.

## N2 (P1): explicit DASH API discards the final response URL after a redirect

Evidence: **source-proved call chain, not an end-to-end network reproduction**. [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt:70-106](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt#L70) (`fetchBounded`) reads and returns only body bytes; `:168-171` passes them to `DashDoor.manifest(body, mpdUrl, ...)` with the original requested URL. That becomes the resolver base in `DashManifestParser.parse`. `Dash.mediaItemFor` calls `manifest` at `:239`, so its segment plans inherit the bad base. A live refetch uses the same path.

Reproducer: serve `/entry/movie.mpd` as `302 Location: /cdn/movie.mpd`; `/cdn/movie.mpd` returns a valid MPD with `<SegmentTemplate media="s-$Number$.m4s" ...>` or `<BaseURL>movie.mp4</BaseURL>`. Only `/cdn/s-1.m4s` (or `/cdn/movie.mp4`) exists. `Dash.manifest` resolves `/entry/s-1.m4s` instead of `/cdn/s-1.m4s`. Cross-host redirects under the deliberately permissive default can additionally resolve resources on the wrong host. RFC 3986 section 5.1.3 requires the retrieval URI after redirects to anchor relative references.

Counterevidence and scope: the automatic path is correct: `DashDoor.readerIfManifest`, `DashDoor.kt:172-181`, uses `io.location`, which Ktor records from `response.call.request.url`. Thus passing a URL directly to the player and passing the same URL through `Dash.mediaItemFor` can differ. Existing `DashRedirectTest.sameOriginRefusesAManifestRedirectToAnotherOrigin` confirms that the redirected document parses but asserts no resulting media URL; its fixture uses `/collect`, hiding the directory-resolution defect.

Fix direction: return `(bytes, finalUrl)` from manifest fetches (or a result type with that data); parse against final URL, while retaining original request URL separately for labels and an explicitly defined security-policy anchor. Add same-origin directory redirect and cross-origin default-policy redirect tests that actually open the resolved relative segment. Closest existing #546 reports worker redirects working; its comments were read and concern the worker/automatic path, not this explicit Ktor API defect.

## N3 (P2): Period-level SegmentList and partial list/base inheritance remain broken

Evidence: **Period-level list absence reproduced from unchanged parser source; partial inheritance source-proved**. `DashManifest.kt:354-375` reads Period SegmentTemplate and SegmentBase but never Period SegmentList. `:439` selects `rep.child("SegmentList") ?: setSegmentList` whole, and `:479` selects SegmentBase whole, so child elements do not inherit individual attributes/children from their parent declarations.

Minimal Period-level fixture:

```xml
<MPD mediaPresentationDuration="PT4S"><Period>
 <SegmentList timescale="1" duration="2"><Initialization sourceURL="init.mp4"/>
  <SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/>
 </SegmentList>
 <AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v" bandwidth="1"/></AdaptationSet>
</Period></MPD>
```

Parsed representation's `segmentList` is **null**, not the inherited list. As `DashDoor.route` sees no addressing it falls back to a single-file route; with the unchanged BaseURL equal to the MPD it refuses "names no media". A valid DASH hierarchy therefore cannot play. A representation `<SegmentList><SegmentURL .../></SegmentList>` overriding a parent list's URLs also loses parent timescale/duration/initialization. ISO/IEC 23009-1 section 5.3.9.1 is already the project's cited hierarchical contract.

Tracker status: overlaps the **remaining** hierarchy scope of closed #232; do not present it as a newly unknown problem. Read its completion comment: it explicitly says "SegmentList and SegmentBase inheritance is unchanged: a Representation still takes the AdaptationSet's SegmentList whole when it has none of its own." The fix handled templates only; the original issue body explicitly mentions all three addressing forms. Report this as incomplete documented hierarchy support, with a direct newly reproduced Period-level case.

Fix direction: model list/base levels the way `TemplateLevel` models templates, merge attributes and the relevant child elements at every supported level, and resolve URLs against the representation's final BaseURL. Test Period inheritance, lower-level partial overrides, and byte-range initialization.

## N4 (P2): a positive thumbnail grid can overflow to zero and abort playlist creation

Evidence: **reproduced from unchanged parser plus HLS writer source**. `DashManifest.kt:528-536` accepts independently positive `Int` columns/rows without bounding their product. `DashHls.kt:295` evaluates `nominal / (grid.columns * grid.rows)` using an `Int` product. A manifest-supplied `65536x65536` multiplies to zero and throws `ArithmeticException: / by zero`. Other large positive layouts become negative and write negative tile durations.

Minimal input: static four-second MPD, one image/jpeg adaptation set, `<EssentialProperty schemeIdUri="http://dashif.org/thumbnail_tile" value="65536x65536"/>`, one representation, and `<SegmentTemplate media="i-$Number$.jpg" timescale="1" duration="2"/>`. Parse, build `timedPlan`, then call `DashHls.mediaPlaylist(plan, live=false, images=representation)`; actual result is `/ by zero` before any image data is requested. This is malformed-input robustness, not a claim that a real image contains billions of valid tiles or that the entire process always terminates: the caller may catch the arithmetic exception. It is not a native memory-corruption finding.

Fix direction: compute product in `Long`, reject unreasonable tile count/dimensions and a duration that cannot express one tile before generating a playlist, with a domain-specific refusal. Add overflow and budget cases. No corresponding grid-overflow issue was found in the downloaded tracker; #626 was a different, fixed, initialization-range allocation bug and its comment was checked.

## N5 (P2 design/capacity risk, not a violated published-quota contract): in-progress store writes have no disk budget

[kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt:370-383](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt#L370) writes every byte to `.tmp`; the first quota decision occurs only at `publish`, `:396-401`. `StoredSpans.wrote` does not chunk or cap the span. A finished HLS playlist can name a large byte-range resource that is read continuously; all read bytes accumulate in a temporary file, even for `fileSegmentStore(maxBytes=1 MiB)`. Several players add several independent uncounted files. The application can exhaust free disk even while `sizeBytes` reports zero and playback otherwise needs only bounded RAM.

This is source-proved, not measured by filling a real disk. [docs/segment-cache.md:128](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/docs/segment-cache.md#L128) and the design comment in #547 explicitly define `maxBytes` to count **published spans only**, so do not call the published-byte accounting itself incorrect or claim it is a regression. The design is still missing a bound on temporary storage and merits a separate capacity requirement. This is a risk rather than a confirmed contract violation. Recommendation: reserve quota for writers incrementally, cap span chunks, and stop persisting as soon as no room exists while allowing network playback to continue. A test should compare all on-disk temporary+published bytes under simultaneous writers, not just `sizeBytes` after publish.

## Exact-source probe and execution evidence

Files: `/tmp/network-audit/Probe.kt` contains fixtures/checks for N1, N3 and N4; `/tmp/network-audit/Annotation.kt` is a no-behavior stub annotation and `/tmp/network-audit/Exception.kt` supplies only the trivial unsupported exception class. Parser, XML implementation, and HLS writer are compiled **directly from checkout**, unchanged. The stub does not replace any audited behavior. This is focused JVM semantic evidence, not a full player or multiplatform qualification.

Compiler/runtime: wrapper-bundled Kotlin compiler **2.4.10**, JDK **21.0.13**, Linux x64; the repository's intended Kotlin version is newer, so report this exact tool version. Actual command:

```bash
java -cp '/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars/kotlin-compiler-2.4.10.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars/*:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/*' \
 org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
 -classpath /workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar \
 -d /tmp/network-audit/probe.jar \
 /tmp/network-audit/Annotation.kt /tmp/network-audit/Exception.kt /tmp/network-audit/Probe.kt \
 /workspace/KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt \
 /workspace/KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHls.kt \
 /workspace/KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/xml/XmlMini.kt \
 /workspace/KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/xml/XmlMini.kt
java -cp /tmp/network-audit/probe.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar \
 io.github.yuroyami.kiteplayer.network.dash.ProbeKt
```

Observed:

```text
LIVE_LIST=https://cdn.test/live/s0.m4s:4000000:1, https://cdn.test/live/s1.m4s:6000000:2
PERIOD_LIST=null
GRID_ERROR=java.lang.ArithmeticException: / by zero
```

## Coverage and limits

Read complete source or key control paths of: DashManifest, DashDoor, DashHls, DashHlsMediaIo, DashPeriods, DashLiveClock, SegmentIndex, WebmIndex, XML adapter, KtorMediaIo, redirect rules, FileSegmentStore, SegmentReuse, freshness (first 220 lines), HlsTtml (first 240 lines), InputStreamMediaIo, ReopeningStreamMediaIo, PosixFileMediaIo, JVM store operations; examined relevant parser, timing, live, redirect, store tests. No existing audit report was used.

Positive mechanisms inspected: bounded manifest XML/URL budgets; Long arithmetic checked in template expansion; typed changed-range/entity refusal; bounded body pipes with timeout; per-origin item header scoping for related requests; cancellation explicitly preserved by live refresh; live cache classification; two-playlist live address retention. These were inspected, not independently proven complete.

Did not run full platform suites, native socket timing, real HTTP redirected-player playback, native storage adapters, long disk fill, RTSP camera sessions, or release performance workloads. RTSP/RTMP/UDP handling is largely in `kiteplayer-ffmpeg/ItemSources.kt` and native FFmpeg, outside this selected transport lane. No nanosecond or throughput claims are made.

## N6 (P1): a tiny live timeline can expand billions of expired entries without a work budget or cancellation point

Evidence: **exact-source probe starts and is externally stopped after three seconds (timeout exit 124); source proves the unbounded work**. `DashManifest.kt:972` truncates each timeline duration to microseconds. For `timescale="1000000000"`, `d="1"`, the result is zero. The expired-prefix fast-forward at `:982` is disabled by `durationMicros > 0`. The loop at `:974-996` then advances one nanosecond per iteration; the output limit at `:991` is tested only when an entry survives the window. There is no examined-entry budget or coroutine cancellation check.

Minimal MPD:

```xml
<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" timeShiftBufferDepth="PT4S">
 <Period start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4">
  <Representation id="v" bandwidth="1">
   <SegmentTemplate timescale="1000000000" media="s-$Number$.m4s">
    <SegmentTimeline><S t="0" d="1" r="999999999999"/></SegmentTimeline>
   </SegmentTemplate>
  </Representation>
 </AdaptationSet></Period>
</MPD>
```

Call the same `timedPlan(... nowMicros=8_000_000)` as N1. It takes **more than four billion iterations** before the first segment whose rounded end exceeds the four-second expired prefix can even be considered; a default 120-second window and larger `nowMicros` can drive this much higher. MAX_PLAN_URLS does not bound the expired prefix because no URL is produced. This defeats the advertised resource-bounded manifest processing; a valid HTTP timeout does not interrupt a synchronous CPU loop. It can pin the demux/worker lane for a duration controlled by untrusted manifest fields. The sub-microsecond segment geometry is pathological input, not ordinary media, and should be rejected before expansion.

Executed with the same compiled checkout source:

```bash
timeout 3s java -cp /tmp/network-audit/probe.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar \
 io.github.yuroyami.kiteplayer.network.dash.ProbeKt spin
```

Actual output: `SPIN_START`, then external timeout exit **124**, with no `SPIN_END`. This is a bounded failure demonstration, not a throughput benchmark; it does not estimate nanoseconds/iteration. Probe `spin` derives the fixture above from N1 by replacing SegmentList with SegmentTemplate, timescale/d/r; extra unused SegmentURL children are ignored by the template parser and do not affect the loop. The literal minimal fixture above has the same audited fields.

Fix direction: reject nonpositive rescaled durations, skip expired segments using exact timescale arithmetic (not rounded per-segment microseconds), and impose an examined-entry/work budget in addition to accepted-URL count. Make long loops cancellation-cooperative. Add a test that this input refuses within bounded work; do not merely assert a timeout after letting it churn. No matching issue was found in the downloaded player issue bodies/titles (closest #474 concerns fMP4 sample counts; #626 concerns bytes allocated by an index read).


# Public integration, subtitles, Compose and OS sessions


Audited owning source at KitePlayer `d5bb627e0ed35e0b2e3176689fa737a12bad9f94` (the checkout was subsequently advanced to `b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92`; source unchanged). No implementation changes made.

## API-1: A small TTML document expands to quadratic retained text and unbounded parser work

- Owner: KitePlayer, `kiteplayer-subtitles`, not KiteFFmpeg.
- Severity: P1 for accepting untrusted subtitle documents; resource exhaustion. Demonstrated expansion; no host OOM or production exploit was attempted.
- Confidence: exact owning sources compiled and executed with pinned Kotlin 2.4.21 on the JVM.
- Locations: [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt:163-180](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt#L163) (one cue per edge interval and a full runs scan), `:319-325` (immutable-string concatenation per adjacent equal-style run), `:130` / `:164` (budget counts paragraphs, not emitted cues or text); `subtitle/xml/XmlMini.kt:72-90` (8 MiB / 262,144 element budgets). Consumer path: `kiteplayer-ffmpeg/.../KiteFFmpegMediaBackend.kt:56-60`, `kiteplayer-core/.../internal/PlaybackCore.kt:689-691,733` (parse lane, synchronous parser call).

One valid paragraph can contain N same-style one-letter spans with begins `0s`, `1s`, ... and every end inherited from the paragraph. The reader emits N cue states containing respectively 1, 2, ..., N letters. This retains N(N+1)/2 text characters, beyond the input size; it also rescans N runs for each of N time intervals. Adjacent equal styles are joined with `previous.text + run.text`, so the progressively growing example additionally copies a growing string once per run per output cue. Counting only input XML or paragraph count does not bound either generated output or CPU work. Cancelling the surrounding coroutine does not interrupt these pure loops.

Exact-source pinned-compiler outputs:

```text
n=500 bytes=13475 cues=500 retainedChars=125250
n=1000 bytes=26976 cues=1000 retainedChars=500500
n=2000 bytes=54976 cues=2000 retainedChars=2001000
```

The measured run also printed elapsed milliseconds; they are omitted here because these are cold diagnostic invocations under concurrent audit load, not release performance benchmarks. The output counts alone establish amplification. Structurally, N=200,000 is still under the default XML length/element ceilings (5,888,978 ASCII bytes, about 5.62 MiB) and would request 200,000 output cue states with 20,000,100,000 text characters if it completed. This is a bound derived from the code, not a memory allocation that was performed. Even the documented first-100,000-paragraph ceiling does not constrain it because this is one paragraph.

Fix direction: enforce total generated cue/text/work budgets, fail with a deterministic typed refusal before excessive output, poll cancellation where possible, and use a sweep of begin/end events plus shared run structures instead of fully rematerializing every visible prefix. Use a builder for a same-style output run. An event sweep alone does not bound unavoidable output expansion in the current fully materialized model.

Counterevidence: #408 was a different exponential named-style-resolution bug, already fixed with memoization and reference budgets; it does not guard timed-run expansion. #594 moved parsing off the actor lane, which improves control responsiveness but neither bounds work nor releases a cancelled parser. Existing TTML timed-span tests exercise correctness on a few spans, not generated-output budgets. Both issue bodies and #492 close comments were reviewed; this specific trigger was not found there.

Regression acceptance: a compact paragraph with many distinct span begin times gets a bounded refusal or shared bounded representation; cancellation does not leave parse workers spending unbounded time. Count generated characters/work rather than assert a wall-clock deadline.

## API-2: Sequential time containment stops at paragraphs and is ignored for spans

- Owner: KitePlayer `kiteplayer-subtitles`.
- Severity: P2, valid TTML/DFXP subtitles show the wrong text at the wrong times.
- Confidence: reproduced with unmodified parser sources, pinned Kotlin 2.4.21 JVM.
- Locations: `TtmlParser.kt:139-143` returns for `p` before reading `timeContainer`; `:222-245` handles every span independently relative to the same parent begin and never reads its `timeContainer`.

Input:

```xml
<tt xmlns="http://www.w3.org/ns/ttml"><body>
<p begin="0s" dur="3s" timeContainer="seq"><span dur="1s">A</span><span dur="1s">B</span><span dur="1s">C</span></p>
</body></tt>
```

Expected: `A` at [0,1), `B` at [1,2), `C` at [2,3). Actual parser result:

```text
0..1000000:ABC
```

The same sequential rule is absent on nested `span` containers. [TTML1 10.2.4](https://www.w3.org/TR/ttml1/#timing-attribute-timeContainer) explicitly says time-container semantics applies to `body`, `div`, `p`, `region`, and `span`; this is ordinary TTML1, not a TTML2 optional feature. Section 7.1.5 explicitly permits `timeContainer` on `p`. The parser's KDoc promises that children of a sequential element follow one another without qualifying the element type.

Fix direction: resolve temporal intervals consistently for block and inline containers before generating cue states; use the preceding child's resolved end as the next sequential child's origin, retain ancestor clipping, and handle implicit durations. Add paragraph and nested-span seq cases, with parallel controls.

Tracker relationship: related follow-up to closed #492, whose timed-span and nested-div fixes are present and whose closure comments were read. This finding is not a claim those exact tests still fail. Its distinct remaining trigger is sequential inline containment; do not file a duplicate of the already fixed parallel timed-span defect.

## API-3: Closing an older browser media session erases the newer session's controls

- Owner: KitePlayer `kiteplayer` Wasm platform adapter.
- Severity: P2, cross-instance lifecycle/OS-control failure.
- Confidence: actual unmodified `WebMediaSessionBridge.kt` compiled to Wasm with Kotlin 2.4.21 and executed under Node 24.22.1 against a fake browser-shaped JS session. The full player and browser overlay were not instantiated.
- Locations: [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/session/WebMediaSession.kt:64-79](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/session/WebMediaSession.kt#L64) (every wrapper publishes independently), `:100-105` (unconditional clear on every close); `WebMediaSessionBridge.kt:56-61` (clear erases every global action and metadata), `:65-75` (per-bridge offered cache does not notice another instance erased actions).

Both default wrappers receive the same `navigator.mediaSession`. Create session A, then session B, then close A as the old player/screen is disposed. A clears B's action handlers, position state, and metadata. B retains its own `offered` set, so its next progress update does not reinstall the handlers. Metadata likewise stays absent until B's metadata changes, because its own mirror considers it already published. Before disposal, independently arriving state changes from both wrappers can mix one player's metadata with another player's buttons/playback state.

Actual bridge probe output, followed by a failed assertion and exit status 1:

```text
after B installs: playHandler=true
after A clear: playHandler=false
after B next position: playHandler=false
IllegalStateException: closing old A must not remove B's play action
```

Fix direction: add page/session-object-scoped ownership with explicit takeover/retirement, as `AppleNowPlaying` already does for Apple's process-wide singleton; only the current owner may clear or publish. Make close idempotent. The injected-session seam must scope ownership by object identity so independent fake sessions or independent test frames do not compete. Tests should exercise two owners, both close orders, a retained stale callback, and a second close of A after B took over.

Counterevidence: Apple's implementation explicitly retires the previous owner and does not clear a newer owner's card (`src@apple/.../AppleNowPlaying.kt`). Existing `WebMediaSessionBridgeTest` validates one bridge at a time, so its clear test confirms the clearing primitive but does not test ownership. Browser support limitation (Web Audio may not activate an OS overlay) is already documented and does not make the singleton mutation safe. No matching open/closed tracker entry was found in the downloaded issue bodies/titles; #77 is the original implemented media-session feature.

## API-4: Timed TTML regions constrain the background but not their text

- Owner: KitePlayer `kiteplayer-subtitles`.
- Severity: P2, valid TTML region content is visible outside its allowed interval.
- Confidence: reproduced with exact parser and `CueSelector` source, pinned Kotlin 2.4.21 JVM.
- Locations: `TtmlParser.kt:163-179` gets the region but never intersects its activity interval with paragraph/run intervals; `:259-266` parses the region begin/end; `:102-109` uses those times only for an empty background cue. `kiteplayer-core/.../subtitle/SubtitleCue.kt:202-219` stores no temporal region interval that could allow a later renderer to repair this.

A paragraph at [0s,10s) targeted to a region active only at [5s,8s) is emitted at [0s,10s). Through the actual cue selector it is already visible at 0s. [TTML1 9.1.2](https://www.w3.org/TR/ttml1/#layout-vocabulary-region) states that a temporally inactive region must not produce visible marks, and that the region's simple duration scopes content targeted into it. This is separate from inline sequence timing: even a single plain parallel paragraph is wrong.

```xml
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling">
<head><layout><region xml:id="timed" begin="5s" end="8s" tts:origin="0% 0%" tts:extent="100% 100%"/></layout></head>
<body><p begin="0s" end="10s" region="timed">X</p></body></tt>
```

Actual output:

```text
0..10000000:X:region=timed
visible at0s=X
```

Expected: X is visible only [5s,8s). Use a transparent region (the default) to avoid confusing the text failure with any empty background cue.

Fix direction: resolve region activity and intersect it with the active output intervals of content flowed to that region, without changing the source content's local time origin. Keep background activity distinct from text activity. Tests must check text before/during/after region activity, including `dur`, unknown-end and nested-span cases. The current `aBackgroundThatAlwaysShowsIsActiveForTheRegionsTime` checks only the empty background cue; it does not assert text in a timed region. This is related to the completed #492 region implementation, not a duplicate of open #705's adaptive transport placement problem; it reproduces on a standalone TTML document.

Reproduction: add the exact owning `kiteplayer-core/src/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt` to the JVM source list below; replace the driver with the following and run `RegionProbeKt`:

```kotlin
import io.github.yuroyami.kiteplayer.subtitle.*
fun main() {
    val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling"><head><layout><region xml:id="timed" begin="5s" end="8s" tts:origin="0% 0%" tts:extent="100% 100%"/></layout></head><body><p begin="0s" end="10s" region="timed">X</p></body></tt>"""
    val cues = TtmlParser.parse(xml)
    println(cues.joinToString { "${it.startMicros}..${it.endMicros}:${it.plainText}:region=${it.layout.region?.id}" })
    println("visible at0s=${CueSelector.activeAt(cues, 0).joinToString { (it as SubtitleCue.Text).plainText }}")
}
```

## Reproduction sources and exact compiler setup

All code is in the checked-out tree except these small drivers. The reproducible test command must name the compiler classpath; the audit used the already bootstrapped toolchain distributions, not a new dependency or changed project build. `EXTRA=/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars`, `M2=/workspace/.cache/java-home/.cache/JetBrains/Kotlin/.m2.cache`. Compiler JVM classpath:

```
$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.jar
$M2/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar
$EXTRA/kotlin-reflect-2.3.20-RC2.jar
$EXTRA/kotlin-script-runtime-2.4.10.jar
$EXTRA/annotations-26.0.2.jar
$EXTRA/kotlinx-coroutines-core-jvm-1.10.2.jar
```

Join with `:` as `$COMPILER_CP`. `$STDLIB` is the pinned JVM stdlib2.4.21 jar above. Kotlin compiler options: `java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$STDLIB" -opt-in=io.github.yuroyami.kiteplayer.KitePlayerInternalApi -d /tmp/ttml-probe.jar <source list> /tmp/TtmlProbe.kt`. Source list relative to `/workspace/KitePlayer`:

```
kiteplayer-core/src/io/github/yuroyami/kiteplayer/InternalApi.kt
kiteplayer-core/src/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt
kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/xml/XmlMini.kt
kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt
kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt
kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TimingLine.kt
```

TtmlProbe.kt:

```kotlin
import io.github.yuroyami.kiteplayer.subtitle.*
fun tt(body: String) = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body>$body</body></tt>"
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        val input = tt("<p begin=\"0s\" dur=\"3s\" timeContainer=\"seq\"><span dur=\"1s\">A</span><span dur=\"1s\">B</span><span dur=\"1s\">C</span></p>")
        println(TtmlParser.parse(input).joinToString { "${it.startMicros}..${it.endMicros}:${it.plainText}" })
    } else {
        val n = args[0].toInt()
        val xml = tt(buildString {
            append("<p begin=\"0s\" end=\"${n + 1}s\">")
            repeat(n) { append("<span begin=\"${it}s\">x</span>") }
            append("</p>")
        })
        val cues = TtmlParser.parse(xml)
        println("n=$n bytes=${xml.length} cues=${cues.size} retainedChars=${cues.sumOf { it.plainText.length.toLong() }}")
    }
}
```

Run separately and preserve each status: `java -Xmx256m -cp "/tmp/ttml-probe.jar:$STDLIB" TtmlProbeKt`, then the same with argument `500`, `1000`, `2000`. No benchmark conclusion is drawn from the elapsed time.

For the bridge probe, compile the unmodified `kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/session/WebMediaSessionBridge.kt`, plus `SessionModel.kt` containing the exact `MediaSessionState`, `MediaSessionPhase`, `MediaSessionMetadata` declarations and `offersPrevious` getter copied from the same checkout's `MediaSessionState.kt` (data-only declarations, no platform behavior replaced), plus this driver:

```kotlin
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package io.github.yuroyami.kiteplayer.session
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.seconds
fun main() {
    val session = fakeSession()
    val a = WebMediaSessionBridge(session)
    val b = WebMediaSessionBridge(session)
    a.onAction("play") { _, _ -> println("A") }
    b.onAction("play") { _, _ -> println("B") }
    val state = MediaSessionState(MediaSessionPhase.Playing, 1.seconds, 60.seconds,
        1.0, true, false, "B", null, null, false, false)
    a.publishPlayback(state)
    b.publishPlayback(state)
    println("after B installs: playHandler=${hasHandler(session)}")
    a.clear()
    println("after A clear: playHandler=${hasHandler(session)}")
    b.publishPlayback(state.copy(position = 2.seconds))
    println("after B next position: playHandler=${hasHandler(session)}")
    check(hasHandler(session)) { "closing old A must not remove B's play action" }
}
@JsFun("() => ({ metadata: null, playbackState:'none', handlers: {}, setPositionState(p) {}, setActionHandler(n,h) {this.handlers[n]=h;} })")
private external fun fakeSession(): JsAny
@JsFun("s => typeof s.handlers.play === 'function'")
private external fun hasHandler(s: JsAny): Boolean
```

Wasm stdlib is `$M2/org/jetbrains/kotlin/kotlin-stdlib-wasm-js/2.4.21/kotlin-stdlib-wasm-js-2.4.21.klib` as `$WASM_STDLIB`. Compile and link as separate commands:

```bash
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.js.K2JSCompiler -Xwasm -Xir-produce-klib-file -ir-output-dir /tmp/bridge-klib -ir-output-name bridgeprobe -module-kind es -libraries "$WASM_STDLIB" <WebMediaSessionBridge.kt> /tmp/SessionModel.kt /tmp/BridgeProbe.kt
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.js.K2JSCompiler -Xwasm -Xir-produce-js -Xinclude=/tmp/bridge-klib/bridgeprobe.klib -ir-output-dir /tmp/bridge-linked -ir-output-name bridgeprobe -module-kind es -libraries "$WASM_STDLIB"
node /tmp/bridge-linked/bridgeprobe.mjs
```

Compiler emits a deprecation warning recommending `KotlinWasmCompiler`; this invocation currently works. Final Node command intentionally fails the ownership assertion. The actual run used `/tmp/kite-api-audit/wasm` and `wasm-linked` output directories; exact owning bridge was compiled without edits.

## Coverage, counterevidence and limitations

Read: public subtitle cue/style/index APIs; SubRip, WebVTT, LRC, XML and TTML parsing; external subtitle parse routing and limits; Compose remembered-player ownership, controls source/state/seek/preview/lift, default-control semantics/focus; Android and Apple background lifecycle; Android audio-focus setup; Android, Apple and browser media-session ownership/metadata/commands; remembered display-awake holds. Existing tests and related tracker entries were consulted. Compose controls include accessibility semantics, focus indicators and timeout recommendations, so there is no blanket finding that accessibility is absent. `rememberKitePlayer` uses `RememberObserver` to close abandoned compositions as well as forgotten ones; no generic remembered-player leak is claimed.

No Android/iOS/macOS device runs, screenshot/accessibility tree assertions, Java/Swift consumer compilations, published artifact inspection, release benchmarking, real native rendering, or platform graphics/HDR verification were performed in this lane. Source inspection alone does not establish timing, nanosecond overhead, device stability, or renderer parity. Concrete potential design tradeoffs (e.g. single owner of an OS-wide session) are separated from the demonstrated missing ownership in the web implementation.


# Audio, DSP, native output and rendering


Reviewed KitePlayer commit `b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92` (source unchanged from the checkout before the audit-only commit). No repository source edits. Findings below belong in **KitePlayer**, not KiteFFmpeg: a codec wrapper should be allowed to expose the decoded IEEE float samples faithfully, while a player must handle them before DSP/device output.

## AV-1  -  Nonfinite decoded PCM kills the desktop writer and leaves it marked running

**Severity: high (P1)** for robustness against input-controlled decoded audio. A single NaN in an IEEE float audio file can kill the desktop audio thread, omit the device-failure event, and make a subsequent `start()` do nothing. Scope confirmed for desktop JVM; consequences on native/browser float-output devices were not measured.

**Trigger and chain.** Play a float-PCM WAV containing a NaN sample, with the ordinary equal-rate stereo/mono path. IEEE float formats can carry nonfinite bit patterns; this does not require an invalid allocation or a malicious custom sink.

1. [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:515](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt#L515) decodes every sample directly into the engine array. Lines 551–552 use `Float.fromBits(...)` / `Double.fromBits(...).toFloat()`, retaining NaNs. `KiteFFmpegSource.kt:1944` calls this conversion from the actual `AudioBuffer.copyInterleaved` playback path.
2. [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt:400](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt#L400) aliases the input for the identity mixer; the normal processing chain through line 459 does not remove nonfinite samples. The limiter is not a sanitizer: [kiteplayer-rt/native/src/kite_rt_render.c:304](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-rt/native/src/kite_rt_render.c#L304) and `:358` compare absolute sample magnitudes using `>`; every comparison against NaN is false. The compiled native ring demonstrably emits NaN.
3. [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:403](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt#L403) renders a block and line 409 packs it. At line 492 `(blockBuffer[i] * 32768f).roundToInt()` throws `IllegalArgumentException: Cannot round NaN value.`
4. `writerLoop()` (lines 381–475) has no outer catch/finally. Its recovery transition and `DeviceLost` event at lines 431–437 are reached only when `d.write` returns a nonpositive count. A packing exception exits the thread while `writerRun` stays true and `writerFailed` stays false. `start()` calls recovery (which returns because the failed flag is false) and returns immediately at line 208 because the running flag is still true.

This is also a stateful DSP issue: with an active EQ band, [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/EqualizerStage.kt:116](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/EqualizerStage.kt#L116) calculates a NaN output and stores it in recursive history at lines 121–124. Later finite samples cannot recover that channel until the state is reset. Only adding a final PCM16 cast guard would leave this contamination in place.

**Executed reproductions.** All files were under `/tmp`; no project tests or source were modified.

- A 1,024-sample float payload containing exactly one NaN at index 128 was encoded to `/tmp/av-nan.wav` with system FFmpeg 7.1.5, decoded back to f32le, and inspected using Python `struct`/`math.isfinite`: `FFmpeg decoded samples: 1024 nonfinite: 1 sample128: nan`. This establishes the input shape and an independent decoder's behavior; it is not a run against the project's pinned FFmpeg build.
- `/tmp/av-ring-repro.c` links the repository's exact `kite_rt_ring.c` and `kite_rt_render.c`. It creates a ring, writes NaNs, renders eight frames, and prints `NaN output finite=0`.
- The exact unedited `DesktopAudioSink.kt` was compiled with a minimal fake driver/API seam and the bundled Kotlin 2.4.10 compiler, then run with a render callback returning NaNs. The seam supplies the existing interfaces and a driver accepting any write; it does not replace `writerLoop`, `packBlock`, or lifecycle methods. Output:

```text
actual sink writer error=IllegalArgumentException: Cannot round NaN value.; writerRun=true; renders=1; writes=0
after start retry: renders=1; writes=0
```

The driver cannot be blamed for a failed write: no write occurred. The second line is after calling `start()` again. The check waits on the uncaught-exception handler rather than assuming a time delay killed the thread. Reproduction sources are `/tmp/av-kotlin/{Types.kt,Spi.kt,Drivers.kt,SinkCheck.kt}`; compiled classes are in `/tmp/av-kotlin/sink-out`.

**Counterevidence and limits.** User-supplied gain/volume parameters are validated for finiteness; this is decoded audio, not an invalid volume setter. Android's PCM16 path uses a different conversion and this exception is not asserted there. No full player with the pinned codec engine, physical audio device, browser, or Apple/Android runtime was executed. The source chain, actual native ring, actual desktop sink, and system-codec fixture were checked separately. Ordinary finite audio is unaffected. The matching-issue search found no NaN/nonfinite PCM issue; closed #260 covers integer rounding fidelity, not this failure.

**Fix direction and regression.** Establish a finite-sample policy at the player's decoded-audio boundary before resamplers and recursive DSP: reject the corrupt frame with the documented player error or replace nonfinite values with silence, with bounded diagnostics. Defend the output boundary too. Give writer-thread exceptions a reliable failure transition and an event in catch/finally so an exception from rendering, packing, or the driver cannot leave the sink running without a writer. Regression should play an IEEE float WAV with one NaN followed by finite audio, check finite output/recovery through an active EQ, and verify a writer exception is reported and cannot make retry inert. This does not require changing KiteFFmpeg to falsify decoded bit patterns.

## AV-2  -  Crossing volume 1 abruptly changes the transfer curve despite the smooth gain ramp

**Severity: medium (P2).** This is an exact waveform discontinuity in both ring implementations for supported opt-in volume boost. Physical audibility was not measured. The same threshold also makes a sufficiently small increase above unity make loud in-range samples quieter.

**Trigger.** Set `AudioConfig.volumeCeiling` above 1 (its default is 1; [kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerConfig.kt:401](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerConfig.kt#L401)). While non-silent audio is playing near full scale, move volume from 1 to just above 1 or back. Android's device-gain split retains boost in the player's ring too (`AudioPlayback.kt:1145`–1163), so the branch is not desktop-only.

**Evidence.** The ring documentation says its gain walk prevents waveform steps (`internal/KotlinAudioRing.kt:136`–138). But the transfer function changes discretely at gain 1:

- [kiteplayer-rt/native/src/kite_rt_render.c:479](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-rt/native/src/kite_rt_render.c#L479)–489 leaves in-range samples unchanged at gain 1; any gain above 1 applies `kprt_soft_clip`.
- The ramp has the same branch per frame at lines 494–514, particularly `if (gain > 1.0f)` at line 506.
- `kprt_soft_clip` has a 0.75 knee (`:224`–236), so it changes samples below full scale. For input 0.9, output at gain 1 is 0.9; as gain approaches 1 from above it tends to 0.84375. The gap is 0.05625 full scale even if the gain change is arbitrarily small.
- The Kotlin twin is the same: [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:579](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt#L579)–600 and `:877`–885. Matching implementations and their differential tests therefore do not detect this semantic defect.

**Executed reproduction.** `/tmp/av-ring-repro.c` creates the actual native ring at 48 kHz, one channel, 2,048-frame capacity, writes 1,024 samples at 0.9, renders eight samples at unity, calls `kprt_ring_set_gain(ring, 1.0001f)`, and renders eight more. Compiled from the checkout's current C sources, it prints:

```text
unity last=0.899999976
boost first=0.843785167 delta=-0.056214809
```

The constant input isolates the transfer-function discontinuity; no claim that this is an acoustic measurement or normal program material. A sine wave near its peak has the same threshold behavior. This is a much larger step than the configured tiny gain increment and has the opposite sign.

**Tests and tracker status.** **Known validation area, now with deterministic code-level evidence:** open [KitePlayer #15](https://github.com/yuroyami/KitePlayer/issues/15) already requires no click while moving volume from 1 to 2 and back. It is titled “NEEDS TESTING ONLY,” but the counterexample is reproducible without speakers. Closed [#504](https://github.com/yuroyami/KitePlayer/issues/504) explicitly explains why the 0.75-knee soft clip must not be applied at unity; its peak limiter avoids changing ordinary in-range audio but does not make this threshold continuous. [kiteplayer-rt/native/tests/test_ring_gain_boost.c:200](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-rt/native/tests/test_ring_gain_boost.c#L200)–245 tests the fold only while the gain is above unity, bounds full-scale output, and verifies exact multiplication at the tail. It does not bound the adjacent-sample step when crossing unity. This should be attached to/reconciled with #15 rather than posted as an unrelated duplicate.

**Fix direction.** Make the transfer continuous with respect to gain while preserving exact unity samples, for example by retaining the peak limiter for boosted audio or smoothly introducing a limiting curve rather than abruptly enabling the 0.75-knee curve. Add a waveform-continuity/monotonic-level test for crossings in both directions around 1 with samples above the knee. Merely checking that all samples stay inside full scale, or reimplementing the same clipping formula as an oracle, does not assert the promised property.

## Residual known risk, not a new demonstrated defect

`DesktopAudioSink.drain()` still calls blocking `d.drain()` at line 291 after its cancellable writer wait, so a driver whose own drain blocks can block the coroutine timeout. However closed [#222](https://github.com/yuroyami/KitePlayer/issues/222)'s resolution comment explicitly acknowledges this remaining call and states OpenJDK stops draining when frame position stops advancing. Its regression fake blocks `write`, not `drain`. No default driver hang was reproduced here, so this is an existing platform/driver portability question, not evidence that the closed issue's current default path remains broken. Android routed-away blocking-write behavior is also explicitly outstanding in that issue's comment.

## Coverage and limits

Reviewed native ring/render and gain/limiter implementation, Kotlin ring parity, AudioPlayback gain and drain handoff, AudioPipeline ordering and aliasing, equalizer/channel mix/resampler/tempo boundaries; desktop SourceDataLine lifecycle and wire conversion; Android AudioTrack/PCM16 and MediaCodec buffer ownership; web AudioWorklet/worker/canvas paths; CoreAudio ownership/session leasing; Metal frame retention, bounded pools, fences, redraw, HDR/color and picture-readback paths. No confirmed native memory-ownership bug emerged from the reviewed reference/pool/quiescence patterns. This is not proof that all those paths are correct.

The environment had no Apple/Android device execution, actual audio-device checks, or browser media integration runs. No release performance measurements, allocation profiling, subjective listening, HDR monitor comparison, codec/hardware capability matrix, or nanosecond timing claims were made. Native C and targeted JVM experiments establish the two concrete mechanisms above; the complete repository/platform suite is recorded in the combined validation table.

## Minimal C reproduction source

Build with the repository native include directory, `kite_rt_ring.c`, `kite_rt_render.c`, and `-lm` (tested with system gcc). The program is also stored at `/tmp/av-ring-repro.c`.

```c
#include <stdio.h>
#include <math.h>
#include "kite_rt.h"
static void fill(kprt_ring *r, int n, float value) {
    kprt_ring_write_window w;
    int got = kprt_ring_begin_write(r, n, &w);
    for (int i = 0; i < w.first_frames; i++) w.first[i] = value;
    for (int i = 0; i < w.second_frames; i++) w.second[i] = value;
    kprt_ring_commit_write(r, got, 0, 0);
}
int main(void) {
    kprt_ring *r = kprt_ring_create(48000, 1, 2048);
    float out[1024];
    fill(r, 1024, .9f);
    kprt_ring_render(r, out, 8, 0);
    printf("unity last=%.9f\n", out[7]);
    kprt_ring_set_gain(r, 1.0001f);
    kprt_ring_render(r, out, 8, 0);
    printf("boost first=%.9f delta=%.9f\n", out[0], out[0] - .9f);
    kprt_ring_destroy(r);
    r = kprt_ring_create(48000, 1, 2048);
    fill(r, 1024, NAN);
    kprt_ring_render(r, out, 8, 0);
    printf("NaN output finite=%d\n", isfinite(out[0]));
    kprt_ring_destroy(r);
}
```


# Upstream codec engine contracts


Audited `/workspace/KiteFFmpeg` at `f79ba0864c8697f91ef5e3509b46b115afc7dcd7`. Findings belong upstream in KiteFFmpeg; KitePlayer should consume the corrected artifact rather than add codec-library-specific workarounds. No tracked files were changed in this lane.

## KF-1: JNI caption decoding can reenter Java while a primitive array is pinned [P1, known open #207]

- **Locations:** [native/kitecodec-jni/kj_codec.c:330-350](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_codec.c#L330); [native/kitecodec-jni/kj_util.c:346-360](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_util.c#L346); [native/kitecodec-jni/kj_abi.c:41-65](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_abi.c#L41); [native/kitecodec-c/src/helpers_subtitle.c:130-144](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_subtitle.c#L130); [native/kitecodec-c/src/helpers_log.c:64-84](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_log.c#L64).
- **Trigger:** JVM/Android `ClosedCaptionDecoder.decode` with a log sink installed and caption bytes that cause FFmpeg's eia_608 decoder to log, such as a caption row exceeding its width. KitePlayer's FFmpeg logging setup makes this an integration concern, not merely a standalone diagnostic edge.
- **Proof:** `kj_caption_decode` calls `kj_bytes_read_in_place`; that helper enters `GetPrimitiveArrayCritical`, then invokes `kj_caption_bytes_in`, which calls the decoder. The decoder can synchronously reach `kc_log_forward`, `kj_log_forward`, `ExceptionOccurred`, string allocation and `CallStaticVoidMethod` before `ReleasePrimitiveArrayCritical`. The comment claiming no Java calls happen in that region is contradicted by this call chain. JNI's critical-region contract forbids those calls. See Oracle JNI specification, Functions / GetPrimitiveArrayCritical, and FFmpeg `libavcodec/ccaption_dec.c` for warning sites.
- **Impact:** Android CheckJNI can abort; ordinary runtimes may stall GC or deadlock when application logging allocates or waits while inside the region. A device abort was **not reproduced** in this audit; the forbidden reentry is established by source. Do not present an actual ART abort or timing measurement as executed evidence.
- **Counterevidence/limits:** Plain array-to-buffer copies inside the same helper are not inherently wrong. This decoder is different because it can call the installed application logger. Native/Wasm do not use JNI.
- **Fix direction/test:** Copy caption bytes with `GetByteArrayRegion`, release JNI memory, then decode. Exercise malformed/overlong captions with a Warning sink under `-Xcheck:jni` and ART CheckJNI; check that the warning reaches the sink without critical-region diagnostics.
- Tracker checked: https://github.com/yuroyami/KiteFFmpeg/issues/207, OPEN with no comments at audit time.

## KF-2: Cancellation before a decode-flow handoff leaks the cloned frame [P1, known open #199]

- **Locations:** [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:182-188](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt#L182); native twin `MediaSource.native.kt:345-348`; Wasm twin `MediaSource.wasmJs.kt:356`; comparison: [kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/FrameFlow.kt:44-93](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/FrameFlow.kt#L44).
- **Trigger:** A decode flow is canceled by another thread while native decoding is running, after the pump's per-packet cancellation check but before its next emission.
- **Proof:** `flow { ... onFrame = { emit(it.copy()) } }` evaluates `Frame.copy()` first. The flow builder's SafeCollector checks cancellation before calling the downstream collector. If that check throws, the newly owned clone is never delivered and there is no cleanup for it. The reusable landing frame is closed by demux cleanup, but it is a different owner. `FrameBufferFlow` explicitly documents and avoids this same pre-delivery leak hazard. Thus telling callers to use `bufferFrames` does not fix the producer's initial emission.
- **Impact:** Repeated canceled previews retain native frames and their buffers; hardware frames may retain surfaces. Exact leak size depends on format, strides, shared ownership, and whether the frame is software or hardware. No process-RSS growth or per-cancel byte count was measured here. Native/JVM have an ordinary cross-thread cancellation window. Wasm's single thread makes that window narrower; do not claim an identical web interleaving without a web-specific trigger.
- **Counterevidence:** `take` and `first` usually throw from downstream after receiving the value, so blindly closing on *every* `emit` exception can double-close or prematurely invalidate a delivered frame. Transcoder's callback path does not use this flow builder.
- **Fix/test:** A direct `Flow` implementation with explicit before-delivery ownership accounting, matching FrameBufferFlow, and a deterministic cancellation-handoff test. Retain the distinction between a collector that never received the frame and one that received it then failed.
- Tracker checked: https://github.com/yuroyami/KiteFFmpeg/issues/199, OPEN with no comments.

## KF-3: Closing a poisoned sink can truncate an existing output file [P1, known open #216]

- **Locations:** [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt:58-80](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt#L58) (`checkOpen`/`poison`), `:419-447` (`ensureHeaderWritten`), `:527-534` (close); native twin in `MediaSink.native.kt`.
- **Trigger:** Open a sink targeting an existing file, successfully add one stream, then fail configuring another after `avformat_new_stream` has mutated the muxer. Let `use` close the sink while unwinding the setup exception.
- **Proof:** The poison guard correctly rejects subsequent ordinary operations, but `close` sees `declaredStreams > 0` and an unwritten header, so it calls `settleOrigin`/`ensureHeaderWritten`. That method deliberately skips `checkOpen` to allow close-time flushing, but consequently also skips `failure`. It executes `avio_open` against the output path before checking `avformat_write_header`. The original file is truncated even if the malformed stream table makes header writing fail. Cleanup turned a pre-write setup refusal into destructive output mutation.
- **Impact:** Existing output contents are lost during error cleanup; the caller cannot recover them by catching the stream-setup error. A partial stream alone may not increment `declaredStreams`, hence the importance of successfully adding one stream first.
- **Counterevidence:** The same-file guard on a copy's source and destination prevents a different data-loss case, not this one. A header already written is a distinct case where best-effort trailer cleanup can be appropriate.
- **Fix/test:** If the sink is poisoned before its header opened the output, free it and discard held packets without creating/opening an output. Separate closing-state exemption from failure-state exemption. A test must write sentinel bytes to the destination, inject the post-new-stream failure after one successful stream, close, and byte-compare the sentinel.
- Tracker checked: https://github.com/yuroyami/KiteFFmpeg/issues/216, OPEN with no comments. Source proof, not an executed Kotlin failure-injection test here.

## KF-4: Encoder conversion ignores the encoder's declared colour [P2, measured helper + source chain, known open #195]

- **Locations:** [native/kitecodec-c/src/kc_convert.h:107-117,132-155](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/kc_convert.h#L107); [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt:715-718](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt#L715) and its encoder-colour setup near `:145-149`; native `MediaSink.native.kt` conversion twin.
- **Trigger:** Encode a frame whose pixel format differs from the encoder format, while requesting a target matrix/range different from the converter's source-derived values. Typical case: generated RGB/RGBA video with an explicit limited BT.709 encoder declaration.
- **Proof:** Encoder colour is written into the codec context, but `conversionFor` passes only source-frame and destination pixel format to the helper. The helper uses a source-derived coefficient table for BOTH sides of `sws_setColorspaceDetails` and keeps source range for any YUV destination. It cannot possibly honor the destination encoder declaration because it never receives it.
- **Executed experiment:** `/tmp/audit-convert.c` included the exact checked-out `kc_convert.h` and linked the checksum-verified Linux FFmpeg n9.0.2 static `libswscale.a`/`libavutil.a`. A 4x4 RGB24 solid-red source tagged full-range RGB converted to YUV420P gave `Y=76 U=84 V=255 range=2 matrix=0`. An explicit swscale conversion with limited BT.709 destination gave `Y=63 U=102 V=240`. Process exit 0. This is a helper-level differential probe, **not** an executed end-to-end encoded file or screen measurement.
- **Impact:** A muxed stream can declare one encoding while its samples were produced using another; decoders then render incorrect colours. This is distinct from a renderer's approximate display-quality conversion.
- **Fix/test:** Pass destination matrix/range from the encoder context, configure source and destination separately, and stamp output tags to match actual output. Encode generated colour patches under BT.601/709/2020 and full/limited settings, decode them, and compare actual patches with declared intent.
- Tracker checked: https://github.com/yuroyami/KiteFFmpeg/issues/195, OPEN with no comments.

## KF-5: Canceled transcodes free delayed encoders before the sink can drain them [P2, known open #202]

- **Locations:** [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:372-390](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt#L372); native `Transcoder.native.kt:401-423`; JVM `MediaSink.jvm.kt:721-726` and encoder close; [docs/concurrency.md:79](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/docs/concurrency.md#L79).
- **Trigger:** Cancel or otherwise fail a transcode after a delayed encoder accepted input but before normal completion. AAC alone can hold samples even without video lookahead.
- **Proof:** The normal path drains graphs and encoders, but cancellation bypasses it. The inner finally closes/frees the encoders before the outer `output.open().use` closes the sink. Sink close tries to finish its cores, but `finish` is intentionally a no-op when the codec context is zero/closed. Encoder-held data has already been freed. The promise that canceled output still flushes encoders is therefore false.
- **Impact:** The short, potentially valid output ends before all already-submitted content. Do not claim a measured 21 ms loss or any fixed frame count; delay is encoder/configuration dependent and was not measured here.
- **Fix/test:** Give the sink responsibility for finishing/freeing still-open cores on this exceptional path, or explicitly document a discard policy instead. If preserving the stated contract, cancellation tests must compare the last input sample/frame submitted with the muxed output extent, not merely check output validity and fewer frames.
- Tracker checked: https://github.com/yuroyami/KiteFFmpeg/issues/202, OPEN with no comments.

## KF-6: The custom AES-128 HLS reader accepts invalid PKCS#7 tails [P2, new candidate, directly reproduced]

- **Locations:** [native/kitecodec-c/src/helpers_format.c:1117-1149](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_format.c#L1117), particularly `:1140-1143`; selection by encrypted nested URL at `:1234-1243` and installation of the read callback at `:1280-1283`.
- **Trigger:** A block-aligned encrypted HLS segment decrypts to invalid padding: last byte 0 or >16, or last byte says N but the preceding N-1 bytes disagree. This can result from truncated/replaced ciphertext or an incorrect key/IV while media bytes before the tail remain parseable.
- **Proof:** The code removes `pad` bytes if the last byte is between 1 and 16, without checking the other padding bytes. When the final byte is outside that range it passes the whole final block as media and returns clean EOF. RFC 8216 section 5.2 requires AES-128 CBC with PKCS7 padding; PKCS#7 padding consists of N copies of N, including an entire padding block for a block-aligned plaintext (RFC 5652 section 6.3).
- **Executed experiment:** `/tmp/audit-aes.c` includes the actual `helpers_format.c` translation unit and calls its static production `kc_io_crypt_read`. It encrypts one block using the pinned `libavutil.a`, then decrypts through a normal incremental source callback and its interrupt cell. Results:

```text
tail=02 02 cipher_bytes=16 first=14 next=-541478725
tail=ff 02 cipher_bytes=16 first=14 next=-541478725
tail=41 00 cipher_bytes=16 first=16 next=-541478725
tail=02 02 cipher_bytes=15 first=-1094995529 next=-1094995529
AVERROR_INVALIDDATA=-1094995529 EOF=-541478725
```

The valid control succeeds, the non-block-aligned control rejects, but both malformed-padding controls succeed with clean EOF. No padding check was disabled in this probe. This is direct evidence for this owned byte reader, not an end-to-end playlist playback run.

- **Impact:** Corruption at the encrypted segment boundary can be silently accepted or trailing media silently removed instead of producing the invalid-data failure the reader already produces for a partial ciphertext block. Do **not** call this an authentication bypass: HLS AES-CBC provides no general authenticity guarantee, and valid padding alone does not authenticate content.
- **Fix/test:** Before exposing the final block, require pad in 1..16 and all final pad bytes equal pad; report AVERROR_INVALIDDATA otherwise. Add valid padding lengths 1..16 and invalid lengths, mismatching bytes, empty/partial ciphertext, and arbitrary caller-read chunk boundaries. Keep already-delivered prefixes and late failure semantics documented.
- Tracker dedup: searched all downloaded open/closed issue bodies/titles in both repositories for PKCS, padding, AES/CBC and crypt_read. No matching defect found; related encrypted-HLS work is not this validation bug. No issue posted.

## Coverage and exclusions

Read the owned C format/I/O and custom AES reader; codec/frame/resampler/subtitle/hardware helpers; JNI conversion/critical-region/handle-table paths; JVM/native MediaSource and MediaSink; batch transcoder/remuxer and cancellation/handoff helpers; stream table and descriptor identity; web memory bridge, loader and display converter. Ran two focused C differential/negative-input probes against the installed pinned Linux libraries. No Kotlin build, release benchmark, device test, browser playback, long RSS run or sanitizer campaign was executed in this lane. Those limits must remain in the combined report.

Counterexamples deliberately excluded: Resampler is explicitly not thread safe, MediaSource interrupt explicitly forbids racing close, blocking transcode network reads are explicitly documented as noninterruptible, and Frame.ofAudio explicitly documents its eight-channel construction cap. These are not newly discovered implementation defects. `Packet.send` explicitly documents that source-scoped packet identity is missing; evaluate it as a future safety improvement rather than falsely claiming its current guard promises full cross-source isolation. The raw Wasm byte-by-byte copy cost is already tracked in #209 and the display-specific `WebRgbaConverter` has a bulk-copy path; do not infer that the main display renderer necessarily crosses one byte at a time.

The seek probe (`kc_look`) and late-stream HeldPackets budgets account payload bytes rather than complete packet/side-data/handle overhead. This is a hardening lead requiring a demonstrated accepted packet stream before severity can be assigned; it is **not** included as a confirmed memory-exhaustion finding.

## Exact compile/link commands for executed probes

The following commands used the system GCC, the checked-out owned source, and the previously SHA-256-verified Linux n9.0.2 static FFmpeg libraries. Save the embedded probe sources as `/tmp/audit-aes.c` and `/tmp/audit-convert.c` first. `--gc-sections` on the AES probe drops the unrelated functions from the included production format translation unit; it does not alter the called reader.

```bash
gcc -std=c11 -O2 -ffunction-sections -fdata-sections \
  -I /workspace/KiteFFmpeg/native/kitecodec-c/include \
  -I /workspace/KiteFFmpeg/native-libs/lgpl/linux-x64/include \
  /tmp/audit-aes.c \
  /workspace/KiteFFmpeg/native-libs/lgpl/linux-x64/lib/libavutil.a \
  -Wl,--gc-sections -lm -lpthread -o /tmp/audit-aes
/tmp/audit-aes

gcc -std=c11 -O2 \
  -I /workspace/KiteFFmpeg/native-libs/lgpl/linux-x64/include \
  /tmp/audit-convert.c \
  /workspace/KiteFFmpeg/native-libs/lgpl/linux-x64/lib/libswscale.a \
  /workspace/KiteFFmpeg/native-libs/lgpl/linux-x64/lib/libavutil.a \
  -lm -lpthread -o /tmp/audit-convert
/tmp/audit-convert
```

Both commands and probes completed with exit status 0. These compile focused probes at `-O2`; they are not release-build application benchmarks.

## Second pass: diagnostics, globals and cross-instance state

Reviewed `FFmpegLog.kt`, native `Internals.kt` log installation, JVM `Internals.jvm.kt` initialization and `@Synchronized setLogSink`, JNI `kj_abi.c` log forwarding, C `helpers_log.c` captures/thresholds, web `WebLog.kt`, and async runtime logging documentation. The global sink conflict with KitePlayer is already covered by [KitePlayer #670](https://github.com/yuroyami/KitePlayer/issues/670), not a new codec-session data isolation defect. JVM log installation is serialized; native log installation uses a volatile Kotlin sink plus separate atomic C threshold/target stores. That native update is not one coherent state transaction and merits a deterministic concurrent-setter test, but no runtime misdelivery was reproduced here, so it remains a diagnostic hardening lead rather than a seventh confirmed finding. The C comment promising observers always see an old or new pair overstates what two independent atomic variables establish.

Error captures are thread-local and the synchronous `withLoggedReason` deliberately forbids suspension, while host async engines own a real thread and web async lanes own one codec module. This is useful counterevidence against attributing ordinary coroutine migration to capture mixing. Captures omit decoder-worker-thread errors by their documented design; no false cross-session attribution was demonstrated. The web asynchronous runtime's module is intentionally silenced and is not reached by the page-global sink; `docs/async-byte-io.md` explicitly documents this limitation, so it was not mislabeled as an undocumented regression. The JNI caption decode reentry remains the concrete global-logging safety defect demonstrated by the source chain in KF-1.


# Build, artifacts, documentation and capability evidence


Source inspected: KitePlayer b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92 (source same as d5bb627e0ed35e0b2e3176689fa737a12bad9f94; later commit is another audit document), KiteFFmpeg f79ba0864c8697f91ef5e3509b46b115afc7dcd7. Checks below run/read on 2026-11-08. No source edits, builds, publishes or issue writes performed by this lane.

## Primary findings

### DIST-1 (P1, high confidence, KiteFFmpeg): cold-cache native builds cannot provision the sysroot because the warmup only produces a klib

Locations: [KiteFFmpeg/build-plugins/native-build/src/ToolchainLocations.kt:72-91](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/build-plugins/native-build/src/ToolchainLocations.kt#L72), particularly 79-83; callers `build-plugins/kiteffmpeg-native/src/nativeTasks.kt:55` and `build-plugins/native-build/src/Bakes.kt:98`.

Trigger: a clean Linux/Windows/Android build machine lacks the target sysroot in KONAN_DATA_DIR/dependencies. `ensureKonanSysroot` runs a package-only source through `konanc -target ... -produce library`, then unconditionally requires the linker sysroot to exist. Kotlin/Native 2.4.21 can produce that klib without installing LLVM or the target linker/sysroot. The compiler succeeds and the code throws its own missing-sysroot exception. Every first-use native build through that path is blocked until some unrelated program compilation has warmed the cache. This is a build defect, not a runtime codec defect.

Observed evidence: GitHub CI run https://github.com/yuroyami/KiteFFmpeg/actions/runs/40196126318 on exact audited SHA. Linux job https://github.com/yuroyami/KiteFFmpeg/actions/runs/40196126318/job/121177219128 at 2026-10-25 13:08:52-59 UTC downloads konanc, reports warmup for linux_x64, then fails `Kotlin/Native did not download x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/x86_64-unknown-linux-gnu/sysroot ...`. Android arm64 job https://github.com/yuroyami/KiteFFmpeg/actions/runs/40196126318/job/121177219055 at 13:07:54-13:08:02 UTC fails the same way for target-toolchain-2-linux-android_ndk/sysroot. Read-only reproduction: `gh api repos/yuroyami/KiteFFmpeg/actions/jobs/121177219128/logs` and corresponding Android job. The previous prebuilt download's SHA-256 and 15 patch checks pass before the Linux build fails.

Counterevidence: builds with populated caches may pass; this does not establish a problem in already-published 0.5.1 binaries. KitePlayer has independently fixed this exact class of setup issue: [build-plugins/build-support/src/Konan.kt:63-89](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/build-plugins/build-support/src/Konan.kt#L63) explains why klib and `-Xcheck-dependencies` do not provision 2.4.21 and compiles `fun main(){}` as a program under a setup lock. This is useful corroboration, not a reason to make independent KiteFFmpeg depend on KitePlayer.

Fix direction: provision a trivial program (or another operation authoritatively requiring target linker dependencies), serialize setup, and assert actual LLVM + sysroot after completion. Test from an isolated empty KONAN_DATA_DIR on Linux and Android; also exercise warm reuse. Do not weaken the existing missing-path checks.

Tracker: no match found in title/body search of all 205 downloaded KiteFFmpeg issues for requireKonanc/ToolchainLocations/ensureKonanSysroot; Additional targeted comment searches for sysroot/requireKonanc returned no matching issue.

### DIST-2 (P1, high confidence, KiteFFmpeg): concurrent native tasks extract and consume one incomplete Kotlin/Native distribution

Locations: [KiteFFmpeg/build-plugins/native-build/src/ToolchainLocations.kt:24-32](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/build-plugins/native-build/src/ToolchainLocations.kt#L24) (findKonanc accepts executable existence), `:39-62` (requireKonanc check/download/extract with no lock or completion marker).

Trigger: a cold multicore build starts several platform C/JNI tasks before toolchain provisioning. Each sees no konanc and extracts the same host distribution into the same KONAN_DATA_DIR. A task can discover `bin/konanc` before its jar files have been written; multiple tar processes write the same files. This causes corrupted/incomplete first-use setup, cascading build failures, duplicate large downloads, and cache-dependent reproducibility.

Observed evidence: same exact-head CI run 40196126318, ratchets job https://github.com/yuroyami/KiteFFmpeg/actions/runs/40196126318/job/121177219085. At 2026-10-25 13:14:31-32 UTC ffmpegInteropAndroidNativeX64, jniWindowsX64 and ffmpegInteropLinuxX64 all announce download of the SAME macOS 2.4.21 distribution. At 13:14:56-59 interop tasks report `Can't create ...: File exists`, including `konan/lib/kotlin-native-compiler-embeddable.jar`, and tar exits nonzero. Meanwhile jniWindowsX64 runs konanc and reports `ClassNotFoundException: org.jetbrains.kotlin.cli.utilities.MainKt`. The log's stack points to requireKonanc(ToolchainLocations.kt:57). Read-only reproduction: `gh api repos/yuroyami/KiteFFmpeg/actions/jobs/121177219085/logs`.

Counterevidence: this is not inferred merely from a missing `synchronized`; the concurrent extraction and half-installed compiler are in a real run on the audited SHA. Unique temp archive filenames only isolate downloads, not their shared extraction destination. Warm caches mask it. KitePlayer's separate setup implementation has process/classloader and file locking; no equivalent exists here.

Fix direction: one setup lock across threads, plugin classloaders and processes; extract into staging and atomically expose a verified complete distribution, or at minimum hold the lock through extraction and dependency provisioning and recheck after acquisition. `findKonanc` must not equate launcher presence to completed installation. A cold parallel native/JNI build plus deliberate concurrent first use is the regression test.

Tracker: no matching title/body search result; Additional targeted comment searches for sysroot/requireKonanc returned no matching issue. Separate from DIST-1 because changing output kind does not fix extraction races, and locking does not make a klib fetch link dependencies.

### DIST-3 (P2, high confidence, KitePlayer): Central-consumer probe cannot compile because it selects a runtime-only nested module's symbol

Locations: [verification/central-consumer/src/commonMain/kotlin/consumer/Reach.kt:5,10](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/verification/central-consumer/src/commonMain/kotlin/consumer/Reach.kt#L5); [verification/central-consumer/build.gradle.kts:21-23](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/verification/central-consumer/build.gradle.kts#L21); [kiteplayer-compose-ui/module.yaml:22-24](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-compose-ui/module.yaml#L22).

The README install-line validation intentionally compiles an external consumer using only `kiteplayer`, `kiteplayer-compose`, `kiteplayer-audioviz`. The probe imports `KiteVideoState`, which belongs to `kiteplayer-compose-video`. That module is an implementation dependency of compose-ui. It is available at runtime, not on the transitive consumer compile API. Thus the verification project is invalid even when the documented install lines resolve correctly. The build fails before running the native backend probe or the script's version/FFmpeg checks, removing the promised post-release external-consumer signal.

Observed: https://github.com/yuroyami/KitePlayer/actions/runs/41453181048/job/125303178208 on d5bb627e, 2026-11-02 13:29:27-28 UTC: `Reach.kt:5:46 Unresolved reference 'KiteVideoState'` and line 10 on both compileKotlinJvm and compileAndroidMain. `gh api repos/yuroyami/KitePlayer/actions/jobs/125303178208/logs` retrieves logs. Direct Maven Central inspection independently confirms `kiteplayer-compose-ui-jvm/0.3.0/kiteplayer-compose-ui-jvm-0.3.0.module` omits compose-video from jvmApiElements but lists it in jvmRuntimeElements; `kiteplayer-compose-video-jvm-0.3.0.jar` DOES contain KiteVideoState.class. This is not a missing class/artifact and not proof that README installation itself fails.

Fix direction: probe a direct exported compose API (e.g. the public rememberKitePlayer factory) instead of requiring an unrelated module's visibility to be widened. Keep central-only resolution and empty Gradle cache. The repaired external consumer should compile both JVM/Android, execute runJvmProbe, and reach every downstream assertion.

Tracker: #196 is related earlier consumer/fixture version drift but not this symbol-visibility defect; no direct matching title/body found. All source and published artifact coordinates above are exact.

### DIST-4 (P2, high confidence, KitePlayer): misplaced KDoc breaks documentation generation and the release CI dependency

Location: [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsSwitching.kt:698-699](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsSwitching.kt#L698).

The KDoc `/** [upstream] from [offset] on for [length] bytes ... */` is attached to seekOrClose(opened, offset), which has no upstream or length. Dokka resolves those as invalid links. Because the Dokka plugin deliberately treats warnings as errors, `./kotlin do dokka` fails and no new API reference is produced. CI's macos-host API-reference step fails, and publish.yml:107-114 requires that CI before any upload.

Observed: exact-source-sha Docs run https://github.com/yuroyami/KitePlayer/actions/runs/41391828215/job/125104696221, 2026-11-02 05:07:06 UTC: two warnings `Couldn't resolve link: [upstream]` and `[length]` at HlsSwitching.kt:698; Dokka exits 1 with warningCount=2/errorCount=0. Same head's CI 41391828310 macos-host job fails `API reference`. Source remains identical on audited b026b7e. Command: `gh api repos/yuroyami/KitePlayer/actions/jobs/125104696221/logs`.

Fix direction: replace the helper's comment with its actual seek/close contract or move appropriate slice documentation to SliceMediaIo. Keep warning escalation. Verify exact `./kotlin do dokka -m kiteplayer-ffmpeg`, then aggregate docs and required CI.

Tracker: related closed #272 covered earlier missing modules and 43 KDoc links, but this exact current regression is separately demonstrated. Do not reopen the old broad claim as though all its fixes were absent.

## Additional confirmed validation/documentation weaknesses

### DIST-5 (P3, demonstrated harness defect): the test-coverage checker counts comments and block-wide cross products as executed tests

Location: [KitePlayer/scripts/check-test-coverage.py:59-73](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/scripts/check-test-coverage.py#L59), especially 63,65,68-69; `:76-85` additionally credits web-runner lists without checking whether CI invokes that runner.

`covered_by_ci` scans whole run blocks for literal substrings, then multiplies every mentioned module by every mentioned platform. It does not parse commands or remove comments. A run block containing only `# ./kotlin test -m kiteplayer-core -p jvm` and `true` satisfies the checker for a real test@jvm folder. This was executed against the actual checker with ROOT overridden to an isolated temporary tree: output `every test folder ... is named by a job`, return 0, computed pair `(kiteplayer-core,jvm)`; zero test command exists. Reproducer stored locally at /tmp/audit-dist/coverage_probe.py; the fixture is embedded in the reproduction appendix.

This proves the coverage gate can give false assurance after a CI edit. It does NOT prove that some particular current test folder has been skipped: current check itself passes, and actual current execution must be judged from reports. Fix by extracting structured test declarations shared by runner and checker, or at least parsing executable statements per invocation and testing negative controls for commented commands, disabled steps and different module/platform commands. Existing tracker #637 addressed missing test folders and motivated this checker; this is a weakness in the protection added for it.

### DIST-6 (P3, confirmed): NOTICE directs native consumers to deleted licence-resource locations

Locations: [KitePlayer/NOTICE:33-35,62-64,87-89](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/NOTICE#L33). Paths still use `kiteplayer-libass/src/jvmMain/resources/...`, `kiteplayer-audioviz/src/jvmMain/resources/...`, and `kiteplayer-output/src/androidMain/resources/...`; all three are absent in the audited tree after the Kotlin Toolchain layout migration. Actual corresponding resources exist under `resources@jvm` / `resources@android`. This matters because NOTICE explicitly tells native-klib consumers these repository paths are where the licence texts can be obtained. Correct references and validate them as part of source-layout moves. Do not describe this as evidence that the release jars have missing licences: direct inspection verified FFmpeg LGPL/THIRD-PARTY texts and all four libass-chain licence texts in the published Android AAR classes.jar and JVM libass jar; libiconv text is also in the latter. No legal conclusion about compliance follows from this path error.

## Conditional hardening observations (not promoted to demonstrated release defects)

- The libass Android build task only refuses missing JNI output if KITEPLAYER_LIBASS_REQUIRE_ANDROID_JNI=true ([build-plugins/libass-android-natives/src/androidNatives.kt:33-52](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/build-plugins/libass-android-natives/src/androidNatives.kt#L33)), but no repository workflow or script sets that variable; publish readiness sets the separate ALL_HOST_JNI flag only. A publisher missing an NDK can obtain an otherwise publishable empty Android-native jar. The normal CI sample-APK step DOES inspect all three libass ABI entries, and hosted release runners normally have an NDK, so there is no observed shipped-empty-jar claim. Hardening would set the required flag for every release and inspect the exact release native jar before upload.
- native-sbom.sh is run in publish.yml:140-143 BEFORE cold-cache native toolchain provisioning. Its MinGW runtime lookup can record NOASSERTION for GCC/libiconv and skip the NOTICE libiconv version cross-check (native-sbom.sh:123-150), even though the subsequent build downloads and statically bundles those runtimes. Re-run SBOM after required toolchains have been provisioned if exact runtime provenance is a release requirement. This behavior is partly documented in the script, so do not call it a fabricated hidden security vulnerability.
- FFmpeg's actual CI at the audited head is not green: run 40196126318 failed 11 jobs. The cold setup defects above explain specific captured failures, not all failures. Native test behavior cannot be inferred from a job that failed before any tests ran.
- KitePlayer exact-source macOS JVM run 41391828310/job/125104696703 had ONE failed LivePlaybackTest RTMP sync assertion: one sample -42.534083 ms versus allowed -40..60 ms, 2026-11-02 05:17:35 UTC. This is a measured failed test, not a proven deterministic sync bug. The repository explicitly warns that wall-clock media tests are load-sensitive; isolated reruns and a release/hardware benchmark are required before attributing cause. Other near-bound samples should not be rewritten as a catastrophic universal sync failure.

## Artifact checks actually completed

All downloads were HTTPS from repo.maven.apache.org, with no source/dependency edits and no execution of downloaded code.

- Downloaded root `.module` metadata for kiteplayer 0.3.0, kiteffmpeg 0.5.1, libass 0.3.0; inspected platform availability rather than inferring publish support from source folders.
- Downloaded `kiteplayer-libass-jvm-0.3.0.jar`: all four libass JNI resource entries present (macOS arm64, Linux x64/arm64, Windows x64), plus bundled licences including GNU libiconv. Presence is NOT successful dlopen/render evidence.
- Downloaded `kiteffmpeg-android-0.5.1.aar` and `kiteplayer-libass-android-0.3.0.aar`: both contain arm64-v8a, armeabi-v7a and x86_64 JNI libraries.
- Extracted native libraries and ran `readelf -lW`: every arm64/x86_64 PT_LOAD entry of both projects has p_align 0x4000 (16 KiB). arm32 uses 0x1000, which is expected for these 32-bit binaries, not a 64-bit Android 16KiB failure.
- Both sampled Android AAR metadata files declare minCompileSdk=37/minCompileMinorSdk=2, consistent with README SDK requirement. Checked `kiteplayer-output-android-0.3.0.aar` and FFmpeg AAR metadata.
- Sampled licence files in libass and FFmpeg published artifacts are present. This is not an exhaustive legal review, static-relink procedure validation, or proof every native klib consumer satisfies LGPL obligations.
- New source companion artifacts `kiteffmpeg-android-natives:0.5.1` and `kiteplayer-libass-android-natives:0.3.0` return 404 in Central, but the releases inspected predate that source migration and carry JNI directly inside their AARs. Therefore these 404s were rejected as findings against old releases.

## Capability and evidence matrix to integrate

Legend: 'source + docs' means implementation/configuration inspected, not playback executed here. A dependency on FFmpeg does not imply every FFmpeg build feature exists end to end. Published versions and main diverge; README itself marks editions, spherical video and Compose/web as unreleased.

| Capability | KitePlayer/KiteFFmpeg evidence and boundary | Comparison relevance |
| --- | --- | --- |
| Native/JVM format breadth | Native recipe enables dependency-free decoder/demuxer/parser classes + dav1d; output/muxer/filter/protocol lists are constrained. Web build is explicitly reduced and has no AV1 decoder ([KiteFFmpeg/docs/platforms.md:45-62](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/docs/platforms.md#L45)). | mpv/VLC desktop format breadth is the relevant corpus baseline. 'FFmpeg can decode it' alone does not prove player track selection, timestamping, output or installed profile can play it. |
| HLS and DASH | Standard player includes network/adaptive stack; browser network playback uses worker. DRM-protected DASH/HLS refusal is documented; software HTTPS is supplied by player transport, not embedded FFmpeg TLS. | Media3/AVPlayer are useful platform playback and protected-content references. KitePlayer's explicit DRM lack prevents universal interchangeability for subscription-media apps; this is an opportunity/constraint, not an undocumented bug. |
| Hardware video | Apple VideoToolbox, Android MediaCodec paths; Windows D3D11 source/config but no real GPU execution claim; Linux software. | Match CPU, device/driver, profile/level, power and frame-copy metrics when comparing. mpv's libplacebo/Vulkan/VAAPI paths cannot be assumed present simply because decode API exists. |
| HDR / color | README supports HDR only for specific native paths and otherwise tone maps. CPU Dolby Vision conversion has documented 1080p/4K limits. | Compare the SAME renderer path, pixel format, display transfer, reference tone mapping and actual HDR display; a synthetic decode benchmark proves none of these. |
| ASS and subtitles | Native libass chain is statically bundled; Android/JVM artifacts sampled. Web format and embedded image-caption limitations documented. | Use mpv/VLC/libass-rendered golden images with fonts and timing pinned. Merely recognizing subtitle codec IDs is not parity. |
| OS/session/lifecycle | Android and Apple integrations exist in source; physical-device checks are explicitly separate. Browser audio requires user activation. | AVPlayer/Media3 session/interruption/background behavior requires real OS/device journeys. Linux container results cannot certify these. |
| Public integration | Kotlin common API + JNI/cinterop/wasm, Java 11 classfile target with JDK21 compiler. Central metadata sampled; external consumer harness currently broken (DIST-3). | Kotlin-first is not equivalent to polished Java/Swift/JS ergonomics. The audited artifacts do not establish Swift package/export behavior or binary compatibility against every prior release. |
| Performance | Targeted JVM median regression gates use 3 warmups/7 samples and 10x historical bounds, not competitive player benchmarks; drawing benchmark is opt-in. | No release end-to-end mpv/VLC/Media3/AVPlayer comparison, uncertainty intervals, power metrics or device results produced in this lane. Universal faster/perfect claims remain unestablished. |

Official comparison references fetched read-only 2026-11-08: https://mpv.io/manual/stable/ (manual includes libplacebo/Vulkan/render and filter behavior), https://developer.android.com/media/media3/exoplayer/supported-formats (format/HLS/DASH/DRM hardware constraints), https://www.videolan.org/vlc/features.html (broad VLC feature list). Apple https://developer.apple.com/documentation/avfoundation/avplayer returns a JS-driven shell here; no detailed Apple capability claims were independently extracted. No competitor binary was executed or benchmarked.

## Useful opportunity priorities, separate from defects

1. Restore independent fresh-cache build and external-consumer gates first (DIST-1..4); otherwise API and platform assurance regress without reliable release signal.
2. Define a versioned platform/renderer/codec capability artifact from measured journeys (decode, surface display, audio output, seek, subtitle, close), including 'not tested' and explicit absent features. Use README limitations as initial truthful boundaries.
3. Competitive benchmark programme: release artifacts, fixed content corpus, exact machine and device/OS/driver versions, decode+present vs decode-only split, wall-clock startup/seek, p50/p95/p99 lateness, dropped frames, memory peak/retention, copies, power/thermal state, confidence intervals. Nanosecond-level claims require controlled microbenchmarks plus end-to-end relevance, not a generic dispatch-cost estimate.
4. Assess DRM, Linux GPU acceleration and software encoder/filter breadth by actual consumer demand. Several are already tracked opportunities (#221 SRT, #219 encoders, #222 filters in KiteFFmpeg); do not file them as new defects or silently import mandatory native libraries.

## Coverage limits

Read build/distribution/workflows/metadata/trust/notice paths and relevant docs; inspected historical exact-head CI logs and selected immutable Maven binaries. Did not execute Apple/Android/Windows/browser player binaries or full builds; no release performance or full licensing/legal certification. Did not claim every README/KDoc/sample/link/ABI release was exhaustively checked. Existing source tests and CI have significant valuable coverage, including real decode Linux JVM and many emulator checks, but a job's green/failed label must be separated from tests that actually ran. Current coverage script PASS and failed historical jobs are different kinds of evidence.

## Platform rows (source/artifact support distinct from executed evidence)

| Platform / target | Standard player outputs and important limits | Distribution inspected | Actual exact-source historical CI evidence |
| --- | --- | --- | --- |
| Android JVM, arm64/arm32/x86_64 | Native view + Compose, AudioTrack/AAudio-related paths in source; MediaCodec hardware policies; HDR in native KitePlayerView, not arbitrary Compose path; DRM unsupported | Published player libass 0.3.0 + FFmpeg 0.5.1 AARs have all three ABI JNI libs; 64-bit 16KiB segment alignment passes | Player d5bb CI: Android host Linux job and x86_64 emulator device-tests job success. Not physical-device, arm32 runtime or HDR-display evidence |
| iOS arm64 + arm64 simulator | Audio/video, VideoToolbox/native view, Compose; real-device interruption/background/display behavior unexecuted here | Root Central player metadata names iosArm64/iosSimulatorArm64; no player iosX64. FFmpeg independently publishes iosX64 too | Player d5bb iOS simulator device-free suites success; simulator restrictions explicitly exclude CoreAudio/video hardware suites; no device result here |
| macOS native ARM64 | CoreAudio/native view/VideoToolbox/HDR source paths; Compose desktop uses JVM instead | Central player metadata names macosArm64; FFmpeg also publishes macosX64 independently | Player d5bb native ratchets job failed at Dokka, so later native suites in that job must not be claimed passed merely because earlier compilation passed |
| macOS ARM64 JVM desktop | Java audio/native AWT view/Compose; renderer-path limitations documented | FFmpeg/libass arm64 JVM entries present; no Intel-mac bundled JNI entry in sampled libass jar | Player d5bb macOS JVM suite had RTMP timing failure; CoreAudio/render device specifics cannot be inferred from JVM presence |
| Linux JVM x64/ARM64 | Software video decode, JVM audio and AWT/Compose; no advertised native Linux GPU decode or HDR display pass-through | Libass Linux x64/ARM64 entries present; FFmpeg published metadata inspected, not JNI load here | Player d5bb Linux x64 JVM + Android-host job SUCCESS, including real-media JVM modules. This is affirmative evidence against a claim that no Linux JVM decode job exists; open #639 may be stale/partially addressed |
| Windows JVM x64 | D3D11 code path has no advertised physical-GPU validation; JVM audio and AWT/Compose | Libass Windows x64 JNI present; not loaded here | Player native-Windows job is not Windows JVM/GPU playback evidence. Separate runtime load/render/long-session tests still required |
| Linux Native x64/ARM64 + Windows Native x64 | Standard KitePlayer() unavailable without custom OutputBackend; no built-in audio/HTTPS, despite core/backend libraries compiling | Central metadata has these native variants; source README explicitly discloses missing output/transport | Player d5bb Linux native x64, ARM64 execution and Windows native jobs SUCCESS. These test library paths, not a complete built-in media application |
| Web Wasm | Main-thread local bytes vs worker network distinction; browser user activation for audio; reduced codecs/no AV1; reduced image/caption support; Compose/web only on main and unreleased | Root metadata has wasmJs variants; external FFmpeg/web module and libass/web module need serving beside app | Player d5bb Node+headless-browser Wasm job SUCCESS. This is not Safari/mobile/audio-device or all-browser evidence |
| Kotlin/JS | Standard player unavailable; pure engine utilities compile/test | Root metadata has JS variant; FFmpeg JS documented Unsupported media placeholder | Player d5bb Kotlin/JS Node job SUCCESS; no contradiction with unavailable full player |
| tvOS/watchOS/Android Native | Core/io/subtitle/rt building blocks, not full default player distribution | Source platform lists inspected, no whole-player artifact claim | Player d5bb tvOS/watchOS simulator jobs and Android-native emulator subsets success; no physical wearable/TV test |

Job status sources above: Player https://github.com/yuroyami/KitePlayer/actions/runs/41391828310 at d5bb627e, retrieved with `gh run view 41391828310 -R yuroyami/KitePlayer --json jobs`. Do not confuse queued run 42499650444 at audit-only b026b7e with an executed result. FFmpeg https://github.com/yuroyami/KiteFFmpeg/actions/runs/40196126318 at f79ba08 has 11 failed jobs and the corpus replay job success; later scheduled Fuzz runs succeeded, e.g. 42467889945, but fuzz success is not full platform CI success. Report these statuses as historical exact-source CI evidence, not tests rerun in the present Linux environment. Logs are available read-only through job logs endpoint; the ordinary `gh run --log-failed` returned empty in this session.

ABI caveat: committed API dumps and build checks guard the repository-selected JVM, Android and klib sets, but this lane did not compare every published release tag or instantiate already-compiled Java/Kotlin/Swift callers against main. Re-exported interfaces and Kotlin default-parameter/data-class signatures need binary-compatibility fixtures, not only a newly compiled sample. Metadata sample showed deliberate implementation-vs-API edges; do not call those missing APIs without examining the public signatures and documented install surface.


# Audioviz input budgets


Repository: yuroyami/KitePlayer; code at d5bb627e0ed35e0b2e3176689fa737a12bad9f94 (main now also has doc-only b026b7eb41ab7ccb8ca989e6b9e7d4eaa9d9fc92). This pass is independent of the existing audit report. No repository edits. No visual taste findings. These two candidates concern malformed metadata or corrupt local cache inputs; neither establishes an ordinary-song failure or a remotely reachable exploit.

## VIZ-1. P2: song-map memory follows timestamp span, without a span or allocation budget

Owner: KitePlayer, `kiteplayer-audioviz`.

Evidence:

- [kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongMapBuilder.kt:57-71](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongMapBuilder.kt#L57): a discontinuity restarts the analyser but intentionally retains the map accumulated so far.
- Same file `:74-82`: any timestamped, ready programme reading in the owned interval is passed to `record`.
- Same file `:95-103`: a timestamp becomes a dense 100 ms curve index after unchecked subtraction and narrowing to Int. Every gap cell is filled by a loop; capacity doubles until that index exists. No examined-cell, timestamp-span or byte budget exists.
- Same file `:271-279`: merging independently scanned parts also creates `FloatArray(size)` and `BooleanArray(size)` for the entire span between their first and last timestamps. Two tiny parts with a huge gap still allocate for the gap.
- `SpectrumAnalyzer.kt:378-389`: the actual scan reading's programme reference is the input start timestamp plus a sample-count offset, so a new analyser after a source timestamp jump does not normalize the jump out.
- `SongScan.kt:318-325`: sequential production scanning feeds the builder directly from decoded PTS. `:223-228` catches `Exception`; JVM `OutOfMemoryError` is not one. The ranged-scan checks prove seam continuity, not a memory budget.

Trigger: a scannable local or explicitly permitted custom/network item whose decoded audio timestamps jump by a large interval. The default sequential builder owns the full Long interval. This does not require retaining the decoded audio: the retained curve itself expands across the timestamp hole. A sparse or malformed source can therefore demand memory unrelated to the number of PCM samples decoded. A large legitimate gap has the same storage property.

Standalone exact-kernel reproduction, Kotlin 2.4.10, Linux JDK 21.0.13, `-Xmx64m`:

1. Use the unmodified `SongMapBuilder.kt` and `SongMap.kt` from the repository, with inert definitions for unrelated analyser/type dependencies.
2. Invoke its private `record(Long, Double)` by reflection for `(0L, -20.0)` then `(2_000_000_000_000L, -20.0)`. The second reading is at 20,000,000 curve cells, about 23 days after the first.
3. Actual output:

```text
SECOND_READING_PTS=2000000000000 INDEX=20000000
RECORD_FAILURE=java.lang.OutOfMemoryError: Java heap space
```

A second reproduction uses no reflection and calls the unchanged `mergeSongMapParts`: make two `SongMapPart`s containing one float each, one at 0 and the other at 2,000,000,000,000 us, with covered end equal to start + 100,000. Actual:

```text
PARTS=2 ACTUAL_CURVE_VALUES=2 PTS_SPAN=2000000000000
MERGE_OUT_OF_MEMORY=Java heap space
```

The latter requests 20,000,001 floats before the boolean coverage array. This is a storage calculation, not a measured performance estimate. Both probes isolate the vulnerable storage operations; they do not exercise a real decoder, a real full analyser, or an app uncaught-exception handler. The production connection is source-proven above. No claim that all such failures kill every platform's process.

Expected: reject a pathological scan/map as an ordinary scan failure before allocating, or represent discontinuous coverage sparsely and enforce a configured byte/work budget. Compute span/index in checked Long arithmetic before narrowing. Apply the same policy to both `record` and the merge, and preserve cancellation during any long filling work. Catching OOM is not a substitute for a budget.

Counterevidence and scope: the queue and live timeline are bounded; the normal 100 ms curve of a four-minute song is small. This finding is specific to the separately retained song map. #336 (closed) already corrected event timing after ordinary timestamp jumps and its fix restarts the analyser; this candidate is the retained curve's gap expansion. #356 (closed) bounds installed structural events but does not bound curve storage. Their issue comments, fetched with `gh issue view ... --json number,title,comments`, were empty. No matching timestamp-memory-budget issue found in the 690-entry downloaded issue corpus. Do not classify the old #336 or #356 symptom as newly found.

## VIZ-2. P2: corrupt map counts allocate their declared arrays before validating fixed-width payload availability

Owner: KitePlayer, `kiteplayer-audioviz`.

Evidence:

- `SongMapStore.kt:256-260`: `ByteReader.count()` only checks `0 <= value <= data.size`, the total file length in bytes. It does not compare against remaining bytes or the record width.
- `:160-170`: structure, keys and curve each allocate an array of that count before any entries are decoded. Fixed stored widths are 32, 28 and 4 bytes respectively. Even an obviously impossible count passes the current guard and first requests all its array slots.
- `:175-179`: catches malformed numeric/index reads but does not prevent allocation failure.
- `SongScan.kt:237-252`: decode happens inside `offWorker`, whose failure boundary catches `Exception`, not JVM `OutOfMemoryError`.
- `src@jvm/.../SongMapFiles.jvm.kt:10-12` and the identical Android read have no per-file map-size cap. iOS `SongMapFiles.ios.kt:34-40` only caps at Int.MAX_VALUE before the full ByteArray allocation. Local persistence is a cache, but malformed bytes from a custom `SongMapStore` also reach the same decoder.

Minimal byte recipe: a 16,777,216-byte zero-filled array; little-endian magic `0x4B534D31` at offset 0, current `SongMap.VERSION` (=2) at offset 4, and integer 16,777,216 at offset 30 (the structural count). Everything else is zero. The first structural count is already impossible: after reading it, fewer than 16 MiB remain, and 16,777,216 records would need 536,870,912 bytes. It nevertheless passes `count()` and attempts the reference array first.

Probe uses the repository's entire codec implementation unchanged, mechanically extracted from `private const val MAGIC` to EOF, together with unchanged `SongMap.kt`. On Linux JDK 21.0.13, Kotlin compiler 2.4.10, `java -Xmx64m`:

```text
CACHE_INPUT_BYTES=16777216 DECLARED_STRUCTURE_COUNT=16777216
DECODE_OUT_OF_MEMORY=Java heap space
```

Expected: reject this as a null/cache miss using remaining-byte and element-width checks before any allocation. Use explicit maximum encoded map bytes and decoded entries/bytes as a separate policy. A correct `remaining / elementWidth` check avoids this impossible array request; a file-size cap also prevents the initial unrestricted read from dominating memory.

Counterevidence and scope: the current count check does stop a tiny file from declaring Int.MAX_VALUE entries, and numeric values are range-checked by `usable()`. This is allocation amplification from a larger corrupt cache, not arbitrary allocation from a four-byte input. The default source is an app-controlled local cache, and no hostile cache writer or remote ingress is assumed. #324 (closed) and #346 (closed) were about accepted NaN/out-of-range values, now checked; this count/remaining-width issue is distinct. #324 has no comments. No matching issue found in the downloaded corpus. No full mobile/native allocation experiment was run.

## Reproduction materials and command

Temporary probe files are at `/tmp/audioviz-audit/` and deliberately do not change the repository. `Codec.kt` is a literal extraction of the codec section of `SongMapStore.kt`. `Core.kt`, `Format.kt` and `Stub.kt` define only dependency names for compilation. Stub `SpectrumAnalyzer.feed` is inert and is not used by any probe. No correctness claim for the actual FFT or detector follows from these probes.

Compiler command used:

```sh
java -cp '/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars/kotlin-compiler-2.4.10.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/extra/jars/*:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath /workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar -d /tmp/audioviz-audit/probe.jar /tmp/audioviz-audit/Core.kt /tmp/audioviz-audit/Format.kt /tmp/audioviz-audit/Stub.kt /tmp/audioviz-audit/Codec.kt /tmp/audioviz-audit/Probe.kt /workspace/KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongMapBuilder.kt /workspace/KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongMap.kt
java -Xmx64m -cp /tmp/audioviz-audit/probe.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar io.github.yuroyami.kiteplayer.audioviz.ProbeKt count
java -Xmx64m -cp /tmp/audioviz-audit/probe.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar io.github.yuroyami.kiteplayer.audioviz.ProbeKt gap
java -Xmx64m -cp /tmp/audioviz-audit/probe.jar:/workspace/.cache/kotlin/kotlin-cli-0.13.0/lib/kotlin-stdlib-2.4.10.jar io.github.yuroyami.kiteplayer.audioviz.ProbeKt merge
```

The compiler is the bootstrapped 2.4.10 tool; the full pinned Kotlin Toolchain audioviz release variant was not built or tested. Heap is deliberately constrained to make the allocation request observable in an isolated JVM; no exact-device memory requirement is inferred from it.

## Coverage and limits

Read or inspected the ingestion/store call chains in AudioPcmQueue, AudioVizFeed, SongScan, SongMapBuilder, SongMapStore, all three platform SongMapFiles actuals, SongMap, SongMapEvents, MapVerification and the relevant SpectrumAnalyzer feed/onScan paths. Inspected SongMapCodecTest and the test inventory. Reviewed nearby issue bodies #285, #289, #322-324, #330, #336-339, #346, #356, #657, #659, #671-672, and closest issue comments as noted above.

Positive mechanisms visible in the inspected paths: preallocated SPSC PCM slots with an ownership-preserving release; whole-block prevalidation; queue time/storage bounds; sanitisation of PCM; per-instance analyser state; generation/revision checks around publication; cancellation preserved separately from ordinary scan/store errors; global scan limiter; bounded worker count and cache entry count; atomic cache list publication; numeric cache validation; bounded installed structural events; current map version 2 invalidating old maps; no display-frame creation in the scan callback path after #657; atomic replacement and temporary-file cleanup in current JVM/Android stores; iOS write close-result handling.

This was a bounded second pass. It did not qualify key/beat/section recognition accuracy, real-time latency, audio quality, GPU/render workload, a full audioviz Gradle build, Native GC behaviour, physical Android/iOS devices, corruption fuzzing across the entire format, or long song/device memory measurements. #683 already tracks the unmeasured phone latency/cost gap; do not present that as a newly found defect or invent nanosecond timings. No additional established ordinary-input defect found in this pass.


# VAL-1: current rasterizer test diagnostic


Conclusion: this is a test-oracle defect, not demonstrated incorrect text placement. The horizontal assertion measures the shadow-expanded bitmap while the implementation and the test's own setup contract anchor the text box. Font metrics determine whether the parity mistake becomes visible. The failure is deterministic on this Linux/AWT environment; it is not explained by audit load or timing.

The actual compiled test `io.github.yuroyami.kiteplayer.output.DesktopSubtitleRasterizerTest#a positioned cue's bitmap is its text extent, anchored on the authored point` was selected by exact method name in two fresh JVM processes. Both runs reported exactly 1 test found, started and failed, 0 skipped/aborted/successful, exit status 1. Both failed at `DesktopSubtitleRasterizerTest.kt:218` with expected x=304, actual x=305. Run durations 462 ms and 377 ms are runner diagnostics, not performance results.

Evidence from the actual compiled rasterizer, using a friend-path Kotlin2.4.21 driver and the same `pos` cue/640x360 viewport/BottomCenter/positionX=.5/positionY=.5 as the test:

```text
shadowOffset=0.0 x=305 y=150 bitmap=31x30 testExpectedX=305
shadowOffset=1.0 x=305 y=150 bitmap=32x31 testExpectedX=304
shadowOffset=-1.0 x=304 y=149 bitmap=32x31 testExpectedX=304
exit 0
```

A positive 1px shadow grows a 31px text box to a 32px bitmap, but the text stays at x=305 as intended. The assertion computes `320 - image.bitmap.width / 2`, yielding304 for the expanded32px bitmap; the actual text anchor computes `320 - 31 / 2`, yielding305. For a negative shadow the bitmap origin shifts left/up while the text remains fixed, which is why its bitmap x=304 does match the accidental bitmap-based assertion. This confirms the distinction rather than merely accepting the implementation's arithmetic.

Source chain:

- [kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizerTest.kt:38-43](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizerTest.kt#L38) explicitly says the default shadow adds1px on both axes and placement still measures the TEXT box. `:218` forgets to subtract it horizontally; `:220-222` does subtract shadowPad vertically.
- [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt:123-138](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt#L123) computes text width, then imageWidth=`width + shadow.pad + 2*boxPad`. `:165-170` calls cueOrigin with text width/height and subtracts only shadow.origin/boxPad from the bitmap origin.
- [kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CuePlacement.kt:34-40](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CuePlacement.kt#L34) centers the TEXT width on the authored anchor.
- [kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CueShadow.kt:8-16,40-47](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CueShadow.kt#L8) explains the invariant: adding a shadow must not move the text, positive offsets grow right/down, negative offsets additionally shift bitmap origin.

The test should assert the text-box anchor separately from bitmap growth, preferably across both odd and even text widths and both shadow signs. Do not change the rasterizer to center the shadow-grown bitmap just to turn this test green, since that would move text when shadow settings change. No fixes made.

## Isolated actual test invocation

`CP` comprised every existing `build/artifacts/CompiledJvmArtifact/*/kotlin-output` directory plus these resolved runtime jars from `/workspace/.cache/java-home/.cache/JetBrains/Kotlin/.m2.cache`: kotlin-stdlib2.4.21, kotlin-test2.4.21, kotlin-test-junit52.4.21, kotlinx-coroutines-core-jvm1.11.0, kotlinx-coroutines-test-jvm1.11.0 and atomicfu-jvm0.33.0. JUnit standalone supplies its own engines and platform APIs. Use the exact method string, not a class selector:

```bash
java -Djava.awt.headless=true -jar /workspace/.cache/java-home/.cache/JetBrains/Kotlin/download.cache/81ad195086-junit-platform-console-standalone-6.1.3.jar execute --class-path "$CP" --select-method "io.github.yuroyami.kiteplayer.output.DesktopSubtitleRasterizerTest#a positioned cue's bitmap is its text extent, anchored on the authored point" --details=summary --disable-ansi-colors
```

The two fresh-process logs are `/tmp/kite-api-audit/raster-isolated-1.log` and `raster-isolated-2.log`; these are incidental retained files, not needed to reproduce the invocation.

## Friend driver

Compile with pinned Kotlin2.4.21, the same runtime/artifact classpath above, and `-Xfriend-paths=/workspace/KitePlayer/build/artifacts/CompiledJvmArtifact/kiteplayer-outputjvm/kotlin-output`, output to a temporary jar. It uses the already compiled actual rasterizer; no raster logic is copied:

```kotlin
package io.github.yuroyami.kiteplayer.output
import io.github.yuroyami.kiteplayer.subtitle.*
fun main() {
    for (offset in listOf(0f, 1f, -1f)) {
        val cue = SubtitleCue.Text(0, 1_000_000,
            listOf(StyledSpan("pos", CueStyle(shadowOffsetPx = offset))),
            CueLayout(alignment = CueAlignment.BottomCenter, positionX = .5f, positionY = .5f))
        val image = DesktopSubtitleRasterizer().rasterize(listOf(cue), 640, 360, 1f, 1f).single()
        println("shadowOffset=$offset x=${image.x} y=${image.y} bitmap=${image.bitmap.width}x${image.bitmap.height} testExpectedX=${320-image.bitmap.width/2}")
    }
}
```

This evidence diagnoses the one failing assertion only. It does not establish image fidelity on Android, Apple, the web, other installed fonts, or all outline clipping cases. The original output-suite result must remain reported as 207 passed, 2 assumption-aborted, 1 failed, rather than described as fully green.


# Additional reproduction drivers

These drivers are diagnostic experiments, not proposed test-suite additions. Assertions in some intentionally confirm the observed faulty behavior; a zero exit there is reproduction success, not a fixed-library pass. The Wasm ownership probe deliberately fails a required-property assertion. Use the source pins above.


## DASH driver (N1/N3/N4/N6)

```kotlin
package io.github.yuroyami.kiteplayer.network.dash
fun main(args: Array<String>) {
 val mpd = """<MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" timeShiftBufferDepth="PT4S"><Period start="PT0S"><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v" bandwidth="1"><SegmentList timescale="1"><Initialization sourceURL="init.mp4"/><SegmentTimeline><S t="0" d="2" r="3"/></SegmentTimeline><SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/><SegmentURL media="s2.m4s"/><SegmentURL media="s3.m4s"/></SegmentList></Representation></AdaptationSet></Period></MPD>"""
 if (args.firstOrNull() == "spin") {
  val hostile=mpd.replace("timescale=\"1\"", "timescale=\"1000000000\"").replace("d=\"2\" r=\"3\"", "d=\"1\" r=\"999999999999\"").replace("<SegmentList", "<SegmentTemplate media=\"s-${'$'}Number${'$'}.m4s\"").replace("</SegmentList>","</SegmentTemplate>")
  val parsed=DashManifestParser.parse(hostile,"https://cdn.test/spin.mpd")
  val p=parsed.periods.single(); val r=p.adaptationSets.single().representations.single()
  println("SPIN_START")
  DashManifestParser.timedPlan(parsed,p,r,DashUrlPolicy.Default,8_000_000L)
  println("SPIN_END")
  return
 }
 val manifest = DashManifestParser.parse(mpd,"https://cdn.test/live/movie.mpd")
 val period=manifest.periods.single(); val rep=period.adaptationSets.single().representations.single()
 val plan=DashManifestParser.timedPlan(manifest,period,rep,DashUrlPolicy.Default,8_000_000L)!!
 println("LIVE_LIST="+plan.segments.joinToString { "${it.url}:${it.startMicros}:${it.number}" })
 check(plan.segments.map { it.url.substringAfterLast('/') } == listOf("s0.m4s","s1.m4s"))
 val parentList="""<MPD mediaPresentationDuration="PT4S"><Period><SegmentList timescale="1" duration="2"><Initialization sourceURL="init.mp4"/><SegmentURL media="s0.m4s"/><SegmentURL media="s1.m4s"/></SegmentList><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v" bandwidth="1"/></AdaptationSet></Period></MPD>"""
 val inherited=DashManifestParser.parse(parentList,"https://cdn.test/vod/movie.mpd")
 val inheritedRep=inherited.periods.single().adaptationSets.single().representations.single()
 println("PERIOD_LIST="+inheritedRep.segmentList)
 val gridMpd="""<MPD mediaPresentationDuration="PT4S"><Period><AdaptationSet contentType="image" mimeType="image/jpeg"><EssentialProperty schemeIdUri="http://dashif.org/thumbnail_tile" value="65536x65536"/><Representation id="i" bandwidth="1" width="65536" height="65536"><SegmentTemplate media="i-${'$'}Number${'$'}.jpg" timescale="1" duration="2"/></Representation></AdaptationSet></Period></MPD>"""
 val grid=DashManifestParser.parse(gridMpd,"https://cdn.test/grid.mpd"); val gp=grid.periods.single(); val gr=gp.adaptationSets.single().representations.single()
 val gridPlan=DashManifestParser.timedPlan(grid,gp,gr,DashUrlPolicy.Default,null)!!
 try { println(DashHls.mediaPlaylist(gridPlan,live=false,images=gr)); error("Expected divide by zero") } catch(e: ArithmeticException){println("GRID_ERROR="+e)}
}

```


## DASH probe annotation stub

```kotlin
package io.github.yuroyami.kiteplayer
annotation class KitePlayerInternalApi

```


## DASH probe exception stub

```kotlin
package io.github.yuroyami.kiteplayer.network.dash
class DashUnsupportedException(message: String): IllegalArgumentException(message)

```


## AES final-block reader (KF-6)

```c
#include <stdio.h>
#include <string.h>
#include "/workspace/KiteFFmpeg/native/kitecodec-c/src/helpers_format.c"
typedef struct { unsigned char data[16]; int at, size; } Bytes;
static int read_bytes(void *opaque, uint8_t *dst, int want) { Bytes *b=opaque; if (b->at==b->size) return KC_IO_EOF; int n=b->size-b->at; if(n>want)n=want; memcpy(dst,b->data+b->at,n);b->at+=n;return n; }
static int probe(int penultimate, int last, int cipher_size) {
 unsigned char key[16]={0}, iv[16]={0}, raw[16], out[32];
 memset(raw,'A',16); raw[14]=penultimate; raw[15]=last;
 Bytes bytes={.size=cipher_size}; struct AVAES *enc=av_aes_alloc(); av_aes_init(enc,key,128,0); av_aes_crypt(enc,bytes.data,raw,1,iv,0); av_free(enc);
 int cell=0;kc_io_bridge parent={0};parent.cell=&cell;parent.opener.read_fn=read_bytes;
 kc_io_nested nested={0};nested.parent=&parent;nested.source=&bytes;nested.aes=av_aes_alloc();av_aes_init(nested.aes,key,128,1);nested.cipher=av_malloc(KC_CRYPT_BUFFER);nested.plain=av_malloc(KC_CRYPT_BUFFER);
 int n=kc_io_crypt_read(&nested,out,sizeof out); int next=kc_io_crypt_read(&nested,out,sizeof out);
 printf("tail=%02x %02x cipher_bytes=%d first=%d next=%d\n",penultimate,last,cipher_size,n,next);
 av_free(nested.aes);av_free(nested.cipher);av_free(nested.plain);return n;
}
int main(void) {
 int valid=probe(2,2,16);int bad_tail=probe(255,2,16);int zero_pad=probe(65,0,16);int truncated=probe(2,2,15);
 printf("AVERROR_INVALIDDATA=%d EOF=%d\n",AVERROR_INVALIDDATA,AVERROR_EOF);
 return valid==14&&bad_tail==14&&zero_pad==16&&truncated==AVERROR_INVALIDDATA?0:1;
}

```


## Destination-colour differential (KF-4)

```c
#include <stdio.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
#include "/workspace/KiteFFmpeg/native/kitecodec-c/src/kc_convert.h"
int main(void) {
 AVFrame *src=av_frame_alloc();src->format=AV_PIX_FMT_RGB24;src->width=src->height=4;src->colorspace=AVCOL_SPC_RGB;src->color_range=AVCOL_RANGE_JPEG;av_frame_get_buffer(src,0);
 for(int y=0;y<4;y++)for(int x=0;x<4;x++){src->data[0][y*src->linesize[0]+3*x]=255;src->data[0][y*src->linesize[0]+3*x+1]=0;src->data[0][y*src->linesize[0]+3*x+2]=0;}
 AVFrame *got=kc_sws_convert(src,AV_PIX_FMT_YUV420P,KC_SWS_EXACT);
 AVFrame *want=av_frame_alloc();want->format=AV_PIX_FMT_YUV420P;want->width=want->height=4;av_frame_get_buffer(want,0);
 struct SwsContext *c=sws_getContext(4,4,AV_PIX_FMT_RGB24,4,4,AV_PIX_FMT_YUV420P,KC_SWS_EXACT,NULL,NULL,NULL);
 const int *co=sws_getCoefficients(SWS_CS_ITU709);sws_setColorspaceDetails(c,co,1,co,0,0,1<<16,1<<16);sws_scale(c,(const uint8_t* const*)src->data,src->linesize,0,4,want->data,want->linesize);
 printf("helper_rgb_to_yuv: Y=%d U=%d V=%d range=%d matrix=%d\n",got->data[0][0],got->data[1][0],got->data[2][0],got->color_range,got->colorspace);
 printf("explicit_limited_bt709: Y=%d U=%d V=%d\n",want->data[0][0],want->data[1][0],want->data[2][0]);
 sws_freeContext(c);av_frame_free(&src);av_frame_free(&got);av_frame_free(&want);return 0;
}

```


## Coverage checker negative control (DIST-5)

```python
import importlib.util, pathlib, tempfile
path='/workspace/KitePlayer/scripts/check-test-coverage.py'
spec=importlib.util.spec_from_file_location('coverage_checker',path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory() as d:
 root=pathlib.Path(d)
 (root/'kiteplayer-core/test@jvm').mkdir(parents=True)
 (root/'kiteplayer-core/test@jvm/RequiredTest.kt').write_text('class RequiredTest {}')
 (root/'.github/workflows').mkdir(parents=True)
 (root/'.github/workflows/ci.yml').write_text('''jobs:
  unused:
    runs-on: ubuntu-latest
    steps:
      - run: |
          # ./kotlin test -m kiteplayer-core -p jvm
          true
''')
 (root/'scripts/web-tests').mkdir(parents=True)
 (root/'scripts/web-tests/run.py').write_text('')
 m.ROOT=root
 print('checker return:',m.main())
 print('computed covered pairs:',sorted(m.covered_by_ci()))

```

## Audioviz probe driver and compilation stubs

The storage kernel and map type come from the source pin. The analyzer stub is deliberately inert; these experiments test storage, not detector behavior. Save each block using its filename under /tmp/audioviz-audit. Generate Codec.kt by retaining the original package/imports needed by the codec plus the literal section from `private const val MAGIC` through end of SongMapStore.kt; do not change its decoding functions.

### Core.kt

```kotlin
package io.github.yuroyami.kiteplayer
class Pts(val micros: Long)
class TrackId(val value: Int)

```

### Format.kt

```kotlin
package io.github.yuroyami.kiteplayer.spi
class AudioFormat(val channels: Int, val sampleRate: Int)

```

### Stub.kt

```kotlin
package io.github.yuroyami.kiteplayer.audioviz
import io.github.yuroyami.kiteplayer.spi.AudioFormat
annotation class AudioVizAuthoringApi
enum class AudioEventKind { Onset, LowTransient, BodyTransient, HighTransient, EnergyRise, SectionBoundary, Drop, Breakdown }
class AudioDetection(val kind: AudioEventKind, val ptsMicros: Long, val availableMicros: Long, val strength: Float, val confidence: Float, val surprise: Float)
enum class KeyMode { Major, Minor }
class KeyEstimate(val tonic: Int, val mode: KeyMode, val confidence: Float)
class AudioDetections(val values: Array<AudioDetection>) { val size: Int get() = values.size; operator fun get(index: Int) = values[index] }
class ScanReading {
 var hasTimestamp = false; var ptsMicros = 0L; var programmeReferenceMicros: Long? = null
 var meanSquare: Double? = null; var programmeReady = false; var digitalSilence = false
 var structure: AudioDetections? = null; var key: KeyEstimate? = null; var chroma = FloatArray(12)
}
class SpectrumAnalyzer(val sampleRate: Int) {
 var onScan: ((ScanReading) -> Unit)? = null
 fun feed(interleaved: FloatArray, frames: Int, format: AudioFormat, pts: Long) {}
}
object KeyTracker { fun correlation(profile: FloatArray, tonic: Int, mode: KeyMode): Double = 0.0 }

```

### Probe.kt

```kotlin
package io.github.yuroyami.kiteplayer.audioviz
import io.github.yuroyami.kiteplayer.TrackId
import java.lang.reflect.InvocationTargetException
fun main(args: Array<String>) {
 if (args.single() == "count") {
  val bytes = ByteArray(16 * 1024 * 1024)
  fun int(at: Int, value: Int) { repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
  int(0, 0x4B534D31); int(4, SongMap.VERSION); int(30, bytes.size)
  println("CACHE_INPUT_BYTES=" + bytes.size + " DECLARED_STRUCTURE_COUNT=" + bytes.size)
  try { println("DECODE=" + decodeSongMap(bytes)) } catch (oom: OutOfMemoryError) { println("DECODE_OUT_OF_MEMORY=" + oom.message) }
 } else if (args.single() == "merge") {
  fun part(at: Long) = SongMapPart(at + 100_000L, IntArray(SongMapBuilder.HISTOGRAM_BINS), 0, floatArrayOf(-20f), at, emptyList(), emptyList())
  println("PARTS=2 ACTUAL_CURVE_VALUES=2 PTS_SPAN=2000000000000")
  try { println("MERGE=" + mergeSongMapParts(listOf(part(0L), part(2_000_000_000_000L)), TrackId(0), true)) } catch (oom: OutOfMemoryError) { println("MERGE_OUT_OF_MEMORY=" + oom.message) }
 } else if (args.single() == "gap") {
  val builder = SongMapBuilder(TrackId(0))
  val record = SongMapBuilder::class.java.getDeclaredMethod("record", java.lang.Long.TYPE, java.lang.Double.TYPE).apply { isAccessible = true }
  record.invoke(builder, 0L, -20.0)
  val at = 2_000_000_000_000L
  println("SECOND_READING_PTS=" + at + " INDEX=" + at / SongMap.CURVE_STEP_MICROS)
  try { record.invoke(builder, at, -20.0); println("RECORDED") } catch (e: InvocationTargetException) { println("RECORD_FAILURE=" + e.cause) }
 }
}

```

## Remaining platform qualification work

The existing ownership fences, bounded queues, format guards, source-version checks and tests are meaningful safeguards. This audit identifies specific holes in them; replacing the architecture wholesale is not supported by the evidence. Resolve each finding with a regression that would fail on the audited revision, then rerun the affected platform and integration gates. A Kotlin-only fix can still need native and Wasm checks when cancellation, numeric conversion, atomics or allocation behavior changes.

For performance, use published release builds and a pinned corpus spanning startup, seek, sustained decode/present, audio-only, dense subtitles, network stalls, rapid configuration changes, and repeated open/seek/close. Record warmup, sample size, device/OS/compiler/driver, clock source, queue depth, allocations/retained bytes, p50/p95/p99 latency, deadline misses, and uncertainty. Measure boundary crossings separately only when they explain end-to-end cost. Include low-memory, thermal and slow-storage conditions; do not promote these correctness probes or diagnostic JVM medians into nanosecond or competitor-performance claims.

Physical-device journeys remain necessary for Android focus/service/process recreation and 32-bit ABI behavior; Apple interruptions, ARC/native lifetime, HDR and route changes; Windows GPU/JNI/device behavior; Linux real output devices; and supported browser engines including mobile activation, workers and Wasm. Add compiled-against-previous-release consumers for JVM/Java and klib/Swift exposure before claiming binary compatibility. No complete audit of upstream FFmpeg internals, every container/codec specification, every published ABI, or every library documentation link was completed here.
