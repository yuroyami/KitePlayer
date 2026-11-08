# KitePlayer and KiteFFmpeg: Codex (GPT-6) cosmic audit

Date: 2026-11-08. Model: **GPT-6, running as Codex**, with six reviewers using the inherited model. A more specific model identifier is not exposed. `CODEX_GPT-6_AUDIT.md` preserves the existing `GPT-6_CODEX_AUDIT.md` from a separate review.

## Assessment and evidence boundaries

The inspected implementation does not yet establish universal platform parity, fault-free lifecycle behavior or performance superiority over mpv, VLC, Media3 and AVPlayer. Concrete discrepancies remain in ownership/cancellation, external-audio resolution, subtitle timing/resource growth, cache identity, rendering boundaries and consumer APIs. Existing tests are substantial but miss the demonstrated combinations.

This is a correctness, reliability, performance, API and distribution audit, as requested. It is not a cybersecurity assessment. Memory ownership and bounds are reviewed for application correctness; priorities are repair priorities. There are **31 numbered findings**, including independently reconfirmed defects and an acknowledged provider-dependent limitation, plus three separately identified engine corroborations. Hypotheses, opportunities and unmeasured areas are separated. This broad review is not a proof of perfection or an every-line verification.

| Component | Audited revision/artifact |
| --- | --- |
| KitePlayer source | `d5bb627e0ed35e0b2e3176689fa737a12bad9f94` |
| Main synchronized for this review | `dda5dce51e2f08fc19b1f3fc03574053853dac03`; adds two audit files only |
| KiteFFmpeg source | `f79ba0864c8697f91ef5e3509b46b115afc7dcd7` |
| Published consumer artifact sampled | KitePlayer core JVM `0.3.0` |
| Player codec dependency | KiteFFmpeg `0.5.1`; sibling source is not assumed identical to every released payload |
| Main JVM validation | Toolchain `0.13.0`, Kotlin `2.4.21`, coroutines `1.11.0`, JDK 21, Linux x64 |

Code links use immutable source pins. Executed probes distinguish actual classes, extracted code, test doubles and native helpers. P1 means substantial lifecycle/resource/availability failures; P2 narrower correctness/integration failures; P3 lower-impact documentation, test-oracle or extreme numerical-contract defects. Confidence is separate from priority. No release-performance or device claim follows from debug JVM tests.

Inventory: KitePlayer has 1,718 Kotlin/C/C++/Objective-C source/test files totaling 324,789 lines; KiteFFmpeg has 513 totaling 100,878, excluding build output/provisioned native libraries. These are inventory counts, not reviewed-line counts. Lane sections state actual coverage.

Tracker checks covered the latest 100 issue bodies per repository, targeted open/closed searches and closest bodies/comments. This was not exhaustive historical deduplication. Earlier reports were consulted after initial investigation to identify overlap. No-match searches do not guarantee novelty. Only this report changes the repository; no issue, PR, implementation or release changes are included. The user's explicit main/push instruction supersedes the normal issue-first/no-push convention. Earlier reports are preserved.

## Finding index

| ID | Priority | Owner | Finding |
| --- | --- | --- | --- |
| [ENG-1](#finding-eng-1) | P1 | KitePlayer | Cancelling an audio scan can close its native source while its interrupt callback is still running |
| [NF1](#finding-nf1) | P1 | KiteFFmpeg | synchronous decoded-frame flows can leak an owned frame at cancellation |
| [NF2](#finding-nf2) | P1 | KiteFFmpeg | JNI caption decoding can re-enter Java while a primitive array is critical |
| [PLATFORM-1](#finding-platform-1) | P1 | KitePlayer | Nonfinite decoded PCM terminates the desktop writer without a failure transition |
| [SUB-1](#finding-sub-1) | P1 | KitePlayer | malformed enhanced LRC lyrics create quadratic scanning and garbage |
| [SUB-2](#finding-sub-2) | P1 | KitePlayer | TTML timed runs bypass output budgets and amplify work and retained text |
| [A1](#finding-a1) | P2 | KitePlayer | cancelling a worker `open` cancels only the page's wait and leaves the media opening or open |
| [A2](#finding-a2) | P2 | KitePlayer | relative linked-file URLs are not resolved before crossing into the worker |
| [A3](#finding-a3) | P2 | KitePlayer | Java cannot enable growing-file playback because its item builder has no growth member |
| [A4](#finding-a4) | P2 | KitePlayer | the README's ordinary track-selection sample does not compile against the advertised release |
| [ENG-2](#finding-eng-2) | P2 | KitePlayer | Player inspection and audio scans omit resolver-backed external audio that playback successfully opens |
| [ENG-3](#finding-eng-3) | P2 | KitePlayer | Decoded-audio handoff is not safe against prompt cancellation after a failed park |
| [NET-1](#finding-net-1) | P2 | KitePlayer | Apple's public segment-store byte-array methods pass unchecked lengths to native memory |
| [NET-2](#finding-net-2) | P2 | KitePlayer | a client-injected noncredential Vary header is cached under the wrong identity |
| [NET-3](#finding-net-3) | P2 | KitePlayer | canceled HTTP cache opens can abandon a transferred entry holder |
| [NET-4](#finding-net-4) | P2 | KitePlayer | DASH redirected-base handling is also wrong on automatic live refresh |
| [NET-5](#finding-net-5) | P2 | KitePlayer | AES-128 encrypted HLS TTML subtitles reach the converter before decryption |
| [NF3](#finding-nf3) | P2 | KiteFFmpeg | a byte-source close failure replaces the error that made open fail |
| [NF4](#finding-nf4) | P2 | KiteFFmpeg | batch remux/transcode cancellation is not wired to the blocking FFmpeg open/read |
| [PLATFORM-2](#finding-platform-2) | P2 | KitePlayer | The line-drain phase still escapes the engine's cancellation deadline |
| [PLATFORM-3](#finding-platform-3) | P2 | KitePlayer | Sample-buffer software plane copies can write past a native pixel buffer |
| [PLATFORM-4](#finding-platform-4) | P2 | KitePlayer | Concurrent fallback head reads can count one Android 32-bit wrap twice |
| [SUB-3](#finding-sub-3) | P2 | KitePlayer | TTML `end` overrides `dur` instead of taking the earlier bound |
| [SUB-4](#finding-sub-4) | P2 | KitePlayer | TTML paragraphs with a duration implied by their spans are discarded |
| [SUB-5](#finding-sub-5) | P2 | KitePlayer | CEA-708 Delay expires only when more bytes arrive for that service |
| [SUB-6](#finding-sub-6) | P2 | KitePlayer | streamed ASS events remain in libass for the whole session |
| [SUB-7](#finding-sub-7) | P2 | KitePlayer | releasing the player's display-awake hold erases a hold the host app already set |
| [DOC-1](#finding-doc-1) | P3 | KitePlayer | NOTICE directs native consumers to license-text directories that no longer exist |
| [NF5](#finding-nf5) | P3 | KiteFFmpeg | subtitle timestamp addition has native signed-overflow undefined behavior |
| [PLATFORM-5](#finding-platform-5) | P3 | KitePlayer | Saturating frame-to-time helpers still overflow in the final remainder addition |
| [QA-1](#finding-qa-1) | P3 | KitePlayer | a subtitle positioning assertion includes shadow padding in the text anchor |

## Validation

| Check | Result |
| --- | --- |
| Core JVM | 1,616 passed |
| Subtitles JVM | 187 passed |
| I/O JVM | 87 passed |
| Adaptive JVM | 191 passed |
| Network JVM | 167 passed |
| View JVM | 94 passed |
| Output JVM | 210 found: 207 passed, 1 failed (QA-1), 2 aborted because no audio device |
| Focused output rerun | 31 found: 30 passed, same 1 failed |
| Core/subtitle/I/O ABI and kitert coupling | Passed; current baseline, not previous-release compatibility proof |
| Dependency hygiene and test-folder/job inventory | Passed; job declarations are not CI execution |
| C source discipline | 18 checks passed |
| Portable player C, plain and UBSan | 12 suites/186 cases per variant passed; 7 allocation properties per variant unobservable |
| Focused upstream C | 26 suites/397 functional cases passed; 74 properties partial |

The seven main JVM suites found **2,552 tests: 2,549 passed, one failed and two aborted**, excluding reruns and standalone probes. Commands:

```sh
./kotlin test -p jvm -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io
./kotlin test -p jvm -m kiteplayer-adaptive -m kiteplayer-network -m kiteplayer-output -m kiteplayer-view
./kotlin test -p jvm -m kiteplayer-output --include-classes='*DesktopSubtitleRasterizerTest'
./kotlin check kitertCoupling abi -m kiteplayer-core -m kiteplayer-subtitles -m kiteplayer-io
./scripts/check-dependency-hygiene.sh
python3 scripts/check-test-coverage.py
kiteplayer-rt/native/scripts/source-discipline.sh
```

The checksum-verified wrapper used its provisioned JRE 25, writable `/workspace/.cache`, inherited proxy settings passed to Java and the system Java CA store, with TLS enabled. Project tests used configured JDK 21. The initial wrapper launch on JDK 21 failed on `UseCompactObjectHeaders`; provisioning its specified runtime resolved that environment problem.

`./scripts/check-gate.sh tier1` was attempted and refused because the maintained aggregate requires macOS arm64. The render audit also lacked `/usr/bin/clang`. Portable GCC checks do not replace Apple CoreAudio, Mach-O allocation accounting, Apple sanitizers or supervised device checks. **The complete required gate did not pass.**

`./scripts/testmedia.sh` with installed FFmpeg **7.1.5-0+deb13u1** stopped at line 261: `colors-nv12.mkv` read back `chroma_location=unspecified` instead of `center`. No final fixture manifest was produced; the chained FFmpeg/player media suites did not start. Partial fixtures were not treated as a complete format matrix. Refusing an invalid color fixture is appropriate; this is a validation blocker, not evidence of broken playback. Device, browser/Wasm, Apple/Windows Native and complete audiovisual format matrices were not run here.

## Cross-module design conclusions

The actor, worker handshake, interrupt callback, coroutine handoff and native close need consistent ownership. ENG-1, ENG-3, NET-3 and NF1 show that cancellation does not prove callbacks have retired or owned results reached consumers. An explicit handoff/retirement mechanism would address this recurring failure better than unrelated cleanup patches.

Policy diverges across playback versus inspection, page versus worker, initial versus refreshed DASH, and encrypted media versus TTML conversion. ENG-2, A1/A2, NET-4 and NET-5 are concrete consequences. PlaybackCore's 17,351 lines make these relationships difficult to inspect, but decomposition matters only if it preserves executable state/lifetime contracts; file size alone is not a defect.

Input-size ceilings do not bound expanded output or retained history. SUB-1/SUB-2 concern work below existing limits; SUB-6 concerns a native history unaffected by Kotlin pruning. Budget work, output cues/text and retained streaming state independently. Preserve whole-document random access when adding streaming pruning.



## Engine audit lane

Source: KitePlayer d5bb627e0ed35e0b2e3176689fa737a12bad9f94, unchanged in current main dda5dce51e2f08fc19b1f3fc03574053853dac03. Related KiteFFmpeg source f79ba0864c8697f91ef5e3509b46b115afc7dcd7. Findings below belong to KitePlayer. No repository source was modified.

<a id="finding-eng-1"></a>

### ENG-1: P1: Cancelling an audio scan can close its native source while its interrupt callback is still running

**Evidence: reproduced using current compiled repository classes and the repository's ScriptedBackend, with deterministic cross-thread barriers.** No actual native use-after-free crash is claimed.

Locations:

- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:35-42](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt#L35) installs a child Job completion handler which calls `session.source.interrupt()` on the cancelling thread.
- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:134-136](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt#L134) calls `link?.complete()` and immediately `session.close()` in the scan's finally block. `complete()` does not wait for a completion handler already executing on another thread.
- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/MediaSource.kt:224-236](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/MediaSource.kt#L224) explicitly forbids interrupt concurrent with or after backend session close.
- [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:462-468](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt#L462) forwards to KiteFFmpeg's interrupt and its I/O bridge.
- Upstream [kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:748-752](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt#L748) deliberately calls `ffkmp_fmt_interrupt(ctx)` without the state lock, relying on that contract; the JVM implementation at `src@jvmAndAndroid/.../MediaSource.jvm.kt:495-500` documents that handle lookup has no lease and concurrent close could free the context mid-call.

Trigger: run public `scanAudio` or `KitePlayer.scanAudio` in a worker coroutine, cancel it from another thread at the end of a decoder/sink/read operation. Its interrupt callback starts, then the scan notices cancellation or otherwise exits and reaches finally before that callback returns. `link.complete()` neither retracts nor fences the in-flight callback, so close overlaps it. The comment claiming that the link is detached before close is stronger than the mechanism.

Impact: native lifetime contract violation. The C context may be freed while the interrupt operation accesses it; on JVM the handle lookup does not itself retain the native object. A long blocking interrupt is not required for the underlying race; the probe lengthens the window to make the ordering deterministic. Application-defined backends are affected too. This is a cross-platform engine ownership bug and should be fixed in KitePlayer, without requiring KiteFFmpeg to know its caller.

Reproduction: `/tmp/kite-engine-probes/ScanInterruptProbe.kt`, included below. It runs the actual unchanged scan implementation and backend test fixture, wraps only `source.interrupt()` and session close to observe ordering, and uses CountDownLatch to hold an interrupt while the scan leaves a sink call. Observed output:

```text
backendCloseOverlappedSourceInterrupt=true
```

The ordinary scan cancellation test ([kiteplayer-core/test/io/github/yuroyami/kiteplayer/AudioScanTest.kt:69-90](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/test/io/github/yuroyami/kiteplayer/AudioScanTest.kt#L69)) checks resource totals after a suspended sink, on a shared test scheduler. It cannot expose two threads executing interrupt and close simultaneously. The parent-linked cancellation was introduced for #411; the primary reviewer read its closed issue body and empty comment list. The recorded requirement to remove the link before close is not met for an already-running callback. No exhaustive novelty claim is made.

Fix direction: introduce an explicit fence/lease for the interrupt callback, invalidate future callbacks and wait for every callback that has entered before closing the session, under non-cancellable cleanup. Merely disposing the handler or calling Job.complete is not a proof that an already-running handler has returned. Add this deterministic ordering regression, then native stress/sanitizer coverage.

<a id="finding-eng-2"></a>

### ENG-2: P2: Player inspection and audio scans omit resolver-backed external audio that playback successfully opens

**Evidence: reproduced end to end through actual current PlaybackCore methods and repository CoreHarness/ScriptedBackend classes.** The transport is a deterministic custom MediaIoResolver rather than an external server.

Locations:

- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1768-1794](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L1768) resolves only the main media item's reader before invoking `scanMediaAudio` or `inspectMedia`.
- `internal/AudioScanning.kt:35` and [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:17038-17043](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L17038) invoke `openWithExternalAudio(backend, media)` without a resolver.
- `internal/ExternalAudio.kt:59-63` defaults that resolver to `{ null }`; `:76-81` hands each external input directly to the backend without transport when it is null; `:92-96` turns the resulting input-open failure into a held warning and skips that input.
- Playback's equivalent path at [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:4939-4945](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L4939) explicitly supplies `resolveMediaIo(input, config.network)`, so it succeeds for the same item.
- [kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1545-1572](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt#L1545) promises inspection reaches media as open does and scanning uses playback's reader rules; [kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaInspection.kt:9-13](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaInspection.kt#L9) says it contains what open would publish.

Trigger: a local/main file with `externalAudio = listOf(AudioSource("https://.../dub.flac"))`, where the external address requires the configured/automatic resolver. Playback includes the dub and allows its track selection. Inspecting the same item silently omits the dub; scanning the external TrackId fails because the track is absent. Without a selected TrackId, scans can instead analyze a different main-file track. This also affects custom resolver schemes.

The scan/inspection session never installs a warning sink, so the wrapper's AudioSourceUnreadable warning is not delivered to the player's event/history stream.

Reproduction: `/tmp/kite-engine-probes/ExternalResolverProbe.kt`, included below. Observed:

```text
playbackTracks=[1, 1048576]
inspectionTracks=[1] resolverCalls=[scripted://main]
scanFailure=track 1048576 is not an audio track of this media resolverCalls=[scripted://main]
```

Counterevidence: `AudioScanTest.aPlayerScanReadsThroughThePlayersResolverWithoutThePlaybackCache` tests the main HTTPS item only. `ExternalAudioTest` verifies external tracks in inspection using a backend that understands scripted URIs by itself. Neither combines external input with resolver-only transport.

Fix direction: pass the same resolver policy for every external input in the player-owned inspection/scanning helpers, keeping the free top-level backend-only API's documented no-resolution contract separate. Preserve per-origin header filtering and per-session reader ownership. Test that the same item has the same external track IDs in playback/inspection and can scan its selected external track.

<a id="finding-eng-3"></a>

### ENG-3: P2: Decoded-audio handoff is not safe against prompt cancellation after a failed park

**Evidence: deterministic coroutine primitive reproduction using pinned kotlinx.coroutines 1.11.0, plus inspected production call chain. Not a whole-player reproduction.** A second audit lane independently checked the quiescence qualification.

Locations in `PlaybackCore.kt`:

- `:15824`: `decodedAudio = Channel<AudioBuffer>(capacity = 4)`, with no `onUndeliveredElement` callback.
- `:14859-14881`: producer `select { decodedAudio.onSend(buffer) { true } ... }` keeps `ownsBuffer=true` until select returns, and closes it in finally if select throws.
- `:15003-15008`, `:15029`: consumer selects `onReceive` before entering the acquired buffer's try/finally.
- `:13055-13057`: release tries worker quiescence but ignores a false result, then cancels jobs; `:13075-13078` drains only buffers still present in the channel.

Pinned upstream documentation explicitly says both selected operations can have transferred the element before select throws CancellationException: [Channel.kt 1.11.0 onSend](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/common/src/channels/Channel.kt#L192) and [onReceive](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/common/src/channels/Channel.kt#L510).

Trigger: a send/receive has selected but its continuation is delayed on a worker dispatcher past its quiesce deadline. Release gets false from park, cancels the worker, and the queued continuation resumes with prompt cancellation. Internal-core parent cancellation provides another route; that parent parameter is not exposed by the normal public player factory. Ordinary successful quiescence parks both workers before cancelling and protects the normal close path, so this is specifically a timeout/cancellation robustness defect, not a claim that every seek/stop races.

Receive side: the buffer has left the channel but no local owner reaches its finally, leaking it and leaving audioInFlight accounting unbalanced. Send side: the buffer may already be in the channel while the sender closes it, after which the consumer/cleanup may use or close it again. An idempotent specific buffer close can mask a double free, but does not repair premature release while the buffer remains queued.

Reproduction: `/tmp/kite-engine-probes/PromptCancellationProbe.kt`, included below, using a dispatcher that holds the selected continuation until after cancellation. The channel capacity and ownership pattern are the production ones. Observed:

```text
receive: delivered=false queued=false closes=0
send: sentStillQueued=true closes=2
```

Fix direction: make ownership transfer non-suspending (as FrameQueue/PacketQueue already do), with suspension only on an advisory signal, or design channel undelivered-element ownership so producer and consumer cannot both release the same native resource. A callback alone must be integrated with the producer's finally; do not add independent double-close paths. Add cancellation at both selected-but-not-resumed boundaries and assert one close and zero outstanding buffers.

### Independently confirmed findings already present in earlier audit files

These were found from source before consulting the existing reports and are corroborations, not additional unique findings:

1. Existing GPT-6 report E3: latest-setting coalescing retains every displaced command and copies the entire history into a fresh ArrayList (`CoreCommand.takeOver`, PlaybackCore16797-16802). Quadratic cumulative copying and unbounded retained replies remain despite the one-mailbox-token test.
2. Existing GPT-6 report E2: releaseSession is NonCancellable but the local release helper rethrows callback-thrown CancellationException at13015-13016, skipping unrelated owned resources.
3. Existing Fable F-026: launchWorker distinguishes stray callback cancellation at13792-13798 and enqueues it, then handleWorkerOutcome drops every CancellationException at13144. Source call chain confirmed; no new dynamic probe run for this duplication.

### Coverage and limits

Reviewed actor mailbox/admission/command replies, open and resource rollback, backend/reader acquisition, stop/close/finalization, preload construction/adoption/drop/handoff, seek/cancellation excerpts, audio decode/feed handoffs, VideoPlayback handoff excerpts, Worker park/release handshake, FrameQueue and PacketQueue ownership, MediaClock and audio timeline arithmetic, external-audio composition, scan/inspection, and relevant regression tests. This is not a complete line-by-line read of PlaybackCore's 17,351 lines. Source spans inspected in detail include177-240,1690-1810,2340-2618,2690-3265,3935-4540,4770-4985,10500-10860,11345-11450,12460-13190,13190-13328,13780-13810,14240-14420,14720-15165,1530-1555,15400-15560,15824,16370-16450,16765-16832,17030-17115. Read full ExternalAudio.kt, AudioScanning.kt, PlaybackWorkers.kt, MediaClock.kt, AudioTimeline.kt, FrameQueue.kt; PacketQueue through trimming and additional searches. Read relevant public contracts and selected existing tests.

No physical audio device, Kotlin/Native binary, Android/iOS session, hardware decoder, release performance benchmark, or comparison measurement against mpv/VLC was executed in this lane. The probes establish semantic/lifetime defects, not nanosecond performance or probability estimates. Counterexamples and healthy quiescence behavior were explicitly considered.

Reproduction environment: OpenJDK21.0.13 on Linux; repository core and core-test artifacts freshly built by the root agent with repository Kotlin2.4.21. Small probe entrypoints were compiled using standalone Kotlin2.4.10 and linked to those artifacts; coroutines1.11.0 and atomicfu0.33.0 match repository pins. The channel-only probe uses the standalone2.4.10 stdlib. All scripts live outside the repository.

### Reproduction code: scan interrupt retirement

```kotlin
package io.github.yuroyami.kiteplayer
import io.github.yuroyami.kiteplayer.spi.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main() = runBlocking {
    val sinkEntered = CountDownLatch(1)
    val releaseSink = CountDownLatch(1)
    val interruptEntered = CountDownLatch(1)
    val releaseInterrupt = CountDownLatch(1)
    val closeEntered = CountDownLatch(1)
    val interruptRunning = AtomicBoolean(false)
    val overlapped = AtomicBoolean(false)
    val originalBackend = ScriptedBackend(MediaScript(durationUs = 4_000_000), LeakLedger())
    val backend = object : MediaBackend {
        override suspend fun open(media: MediaItem): BackendSession {
            val original = originalBackend.open(media)
            return object : BackendSession by original {
                override val source = object : PlayerMediaSource by original.source {
                    override fun interrupt(): Boolean {
                        interruptRunning.set(true)
                        interruptEntered.countDown()
                        check(releaseInterrupt.await(5, TimeUnit.SECONDS))
                        interruptRunning.set(false)
                        return true
                    }
                }
                override fun close() {
                    overlapped.set(interruptRunning.get())
                    original.close()
                    closeEntered.countDown()
                }
            }
        }
    }
    val scan = launch(Dispatchers.Default) {
        scanAudio(MediaItem("scripted://audio"), backend) { _, _, _, _ ->
            sinkEntered.countDown()
            check(releaseSink.await(5, TimeUnit.SECONDS))
        }
    }
    check(sinkEntered.await(5, TimeUnit.SECONDS))
    val canceller = Thread { scan.cancel() }.also { it.start() }
    check(interruptEntered.await(5, TimeUnit.SECONDS))
    releaseSink.countDown()
    check(closeEntered.await(5, TimeUnit.SECONDS))
    releaseInterrupt.countDown()
    canceller.join()
    scan.join()
    println("backendCloseOverlappedSourceInterrupt=${overlapped.get()}")
    check(overlapped.get())
}
```

### Reproduction code: external audio resolver

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.yuroyami.kiteplayer
import io.github.yuroyami.kiteplayer.internal.EXTERNAL_AUDIO_STREAM_BASE
import kotlinx.coroutines.test.runTest

fun main() = runTest {
    val calls = mutableListOf<String>()
    val externalUri = "https://audio.example.test/dub.flac"
    fun reader() = object : MediaIo {
        override val size: Long = 0
        override val seekable = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int) = -1
        override suspend fun seek(position: Long) {}
        override fun close() {}
    }
    val harness = CoreHarness(this, config = PlayerConfig(network = NetworkConfig(
        ioResolver = MediaIoResolver { uri -> calls += uri; if (uri == externalUri) reader() else null },
    )))
    harness.backend.scriptFor = { opened ->
        if (opened.uri == externalUri) {
            check(opened.io != null) { "external URI requires the configured transport" }
            MediaScript(hasVideo = false)
        } else null
    }
    val item = MediaItem("scripted://main", externalAudio = listOf(AudioSource(externalUri)))
    harness.attachRenderer()
    harness.core.open(item)
    val playbackTracks = harness.core.snapshots.value.tracks.audio.map { it.id.value }
    harness.core.stop()
    calls.clear()
    val inspected = harness.core.inspect(item).tracks.audio.map { it.id.value }
    val inspectCalls = calls.toList()
    calls.clear()
    val scanFailure = runCatching {
        harness.core.scanAudio(item, TrackId(EXTERNAL_AUDIO_STREAM_BASE), null) { _, _, _, _ -> }
    }.exceptionOrNull()
    println("playbackTracks=$playbackTracks")
    println("inspectionTracks=$inspected resolverCalls=$inspectCalls")
    println("scanFailure=${scanFailure?.message} resolverCalls=$calls")
    check(EXTERNAL_AUDIO_STREAM_BASE in playbackTracks)
    check(EXTERNAL_AUDIO_STREAM_BASE !in inspected)
    check(scanFailure is IllegalArgumentException)
    harness.close()
}
```

### Reproduction code: selected audio handoffs

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext

private class HeldDispatcher : CoroutineDispatcher() {
    val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}
private class Buffer { var closes = 0; fun close() { closes++ } }
fun main() {
    val dispatcher = HeldDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val receiveChannel = Channel<Buffer>(capacity = 4)
    val received = Buffer()
    var delivered = false
    val receiver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val buffer = select<Buffer> { receiveChannel.onReceive { it } }
        try { delivered = true } finally { buffer.close() }
    }
    check(receiveChannel.trySend(received).isSuccess)
    receiver.cancel()
    dispatcher.drain()
    println("receive: delivered=$delivered queued=${receiveChannel.tryReceive().isSuccess} closes=${received.closes}")
    check(!delivered && received.closes == 0)

    val sendChannel = Channel<Buffer>(capacity = 4)
    repeat(4) { check(sendChannel.trySend(Buffer()).isSuccess) }
    val sent = Buffer()
    val sender = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var ownsBuffer = true
        try {
            val accepted = select<Boolean> { sendChannel.onSend(sent) { true } }
            if (accepted) ownsBuffer = false
        } finally { if (ownsBuffer) sent.close() }
    }
    check(sendChannel.tryReceive().isSuccess)
    sender.cancel()
    dispatcher.drain()
    var sentStillQueued = false
    repeat(4) {
        val buffer = sendChannel.tryReceive().getOrThrow()
        if (buffer === sent) sentStillQueued = true
        buffer.close()
    }
    println("send: sentStillQueued=$sentStillQueued closes=${sent.closes}")
    check(sentStillQueued && sent.closes == 2)
    scope.cancel()
}
```

### Commands used for the two exact-core probes

The root agent first built the repository's JVM core and tests without changing them. The following compiles only the additional entrypoint outside the repo. Set `probe` to `ScanInterruptProbe` or `ExternalResolverProbe`. Paths are this audit environment's paths, not consumer setup requirements.

```python
from pathlib import Path
import subprocess
probe = 'ScanInterruptProbe'
root = Path('/workspace/KitePlayer/build/artifacts/CompiledJvmArtifact')
main = root / 'kiteplayer-corejvm/kotlin-output'
test = root / 'kiteplayer-corejvmTest/kotlin-output'
jars = []
for pattern in (
    '**/kotlin-stdlib-2.4.21.jar', '**/kotlin-test-2.4.21.jar',
    '**/atomicfu-jvm-0.33.0.jar', '**/kotlinx-coroutines-core-jvm-1.11.0.jar',
    '**/kotlinx-coroutines-test-jvm-1.11.0.jar',
):
    jars += list(Path('/workspace/.cache/kotlin/.m2.cache').glob(pattern))
cp = ':'.join(map(str, [main, test] + jars))
cli = '/workspace/.cache/kotlin-bootstrap/kotlin-cli-0.13.0'
compiler = f'{cli}/extra/jars/kotlin-compiler-2.4.10.jar:{cli}/lib/*:{cli}/extra/jars/*'
output = f'/tmp/kite-engine-probes/{probe}.jar'
subprocess.run([
    'java', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', cp,
    f'-Xfriend-paths={main},{test}', '-d', output,
    f'/tmp/kite-engine-probes/{probe}.kt',
], check=True)
subprocess.run([
    'java', '-cp', output + ':' + cp,
    f'io.github.yuroyami.kiteplayer.{probe}Kt',
], check=True)
```

Compiler classpath order is significant: placing all CLI lib jars first produced a NoSuchFieldError from incompatible metadata classes before the probe compiled. Putting the explicit compiler jar first fixed that environment-only issue; it is not a repository defect.


## Platform output audit findings

Source baseline: KitePlayer `d5bb627e0ed35e0b2e3176689fa737a12bad9f94`. Repository source was not edited. The subsequent audit-only history does not change these source locations. All findings below are owned by KitePlayer. KiteFFmpeg should preserve decoded float values rather than silently imposing a player's output policy.

<a id="finding-platform-1"></a>

### PLATFORM-1: Nonfinite decoded PCM terminates the desktop writer without a failure transition (P1, independently reproduced)

This is an independent reconfirmation of AV-1 already present in `audit/GPT-6_CODEX_AUDIT.md`, not a second defect. The current-session probe compiled the unedited `DesktopAudioSink.kt`, `SourceDataLineDriver.kt`, and `DesktopMonotonicClock.kt` against the freshly built actual `kiteplayer-core-jvm.jar`, using Kotlin 2.4.21 and kotlinx.coroutines 1.11.0. A fake line driver and callback supplying NaN isolate the device boundary. The probe caught the writer's uncaught exception and then inspected its running flag.

Observed:

```text
NaN writer crash=java.lang.IllegalArgumentException: Cannot round NaN value.
NaN writerRun after death=true
```

Evidence: [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:492](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt#L492) calls `Float.roundToInt`, which throws for NaN. `writerLoop` at 381-475 has no outer exception handler; only a nonpositive return from `d.write` sets `writerFailed=true`, clears `writerRun`, and emits DeviceLost. The packing failure happens before that write. `start` at 204-210 then returns immediately because `writerRun` remains true. Decoded input reaches it through [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:515-553](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt#L515), whose Float.fromBits and Double.fromBits preserve nonfinite IEEE PCM values; the identity DSP/ring path does not sanitize them.

A malformed/custom SPI callback is sufficient for the executed sink test. Source tracing supports float-PCM input reachability; this lane did not execute a full player with the pinned codec binary or a physical audio device. The prior AV-1 report also describes an independent system-FFmpeg fixture, which should retain its original evidence qualification.

Fix: establish and test a finite-sample policy before recursive DSP and at device conversion; convert any writer exception into a reliable terminal/recoverable state and event. A cast-only guard leaves EQ/resampler histories exposed to NaN contamination. Regression: one NaN surrounded by finite audio, active EQ, same-rate and resampled paths, plus a driver/render exception that cannot leave start inert.

Probe files: `/tmp/kite-platform-probes/DesktopSinkProbe.kt`, `/tmp/kite-platform-probes/desktop.jar`. This is exact sink-source execution with a fake driver, not a device test or timing benchmark. Search `repo:yuroyami/KitePlayer "NaN" "audio"` returned #213 about memento validation, not decoded PCM; `repo:yuroyami/KiteFFmpeg "NaN"` returned display-matrix metadata issues, not this output failure.

<a id="finding-platform-2"></a>

### PLATFORM-2: The line-drain phase still escapes the engine's cancellation deadline (P2, known residual of #222, reproduced)

Closed [#222](https://github.com/yuroyami/KitePlayer/issues/222) and its [closing comment](https://github.com/yuroyami/KitePlayer/issues/222#issuecomment-5842438431) were read. The closing comment explicitly acknowledges that `SourceDataLine.drain()` remains blocking and says OpenJDK's implementation gives up when the frame position stops moving. This is an existing acknowledged residual, not a claim that its fixed writer-join regression returned.

`DesktopAudioSink.drain` uses cancellable polling for writer exit, then directly invokes `d.drain()` at [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:291](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt#L291). Production [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt:205](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt#L205) delegates this to `line.drain()`. This phase has no suspension point or cancellation hook. The engine invokes it through [kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:963](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt#L963), inside [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:9209](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L9209)'s `withTimeoutOrNull(DRAIN_DEADLINE)`. A third-party Java Sound provider or stalled provider that keeps drain blocked can therefore hold the actor past its explicit deadline. Even a provider that eventually completes can cause the operation to report success after the caller's nominal timeout.

The same exact-source desktop probe as PLATFORM-1 opened the sink, used a driver with no writer to wait for, and held the driver's `drain` on a latch. Another thread released it after 500 ms. The drain was wrapped in `withTimeoutOrNull(100)`. Observed:

```text
drain deadline=100ms elapsed=513ms completed=true
```

This proves that the second phase bypasses the coroutine bound. It does not prove the default OpenJDK provider hangs on a real device. The short elapsed value is only a controlled cancellation observation, not a release-performance claim. Existing [kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSinkTest.kt:593-608](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSinkTest.kt#L593) blocks `write`, which exercises the repaired first phase and cannot detect this residual.

Fix: implement a bounded, cancellable played-position wait rather than invoking an unbounded blocking provider drain on the actor, or isolate it with a provider stop/close cancellation mechanism whose own lifetime is fenced. Add a distinct test whose writer has already exited but whose `drain` remains blocked, asserting both the deadline and incomplete-drain result.

<a id="finding-platform-3"></a>

### PLATFORM-3: Sample-buffer software plane copies can write past a native pixel buffer (P2, source-proven, custom resolver trigger)

Public `MetalPicture.SoftwarePlanes` validates positive picture dimensions and a nonempty plane list at [kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:59-67](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt#L59). Each `Plane` validates its own `bytesPerRow * rows` against its ByteArray at 70-83. No relationship is checked between the plane's row count and the picture's declared height.

`SampleBufferVideoRenderer.pixelBufferFor` at [kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/SampleBufferVideoRenderer.kt:406-411](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/SampleBufferVideoRenderer.kt#L406) accepts this public resolver result and calls `copyIntoPixelBuffer`. That function allocates a native CVPixelBuffer using **picture** width/height (583-589), then passes only its base address and bytes-per-row to `copyRows` (599-606). `copyRows` iterates **plane.rows** (629-638), without obtaining or bounding the destination height. `interleaveChroma` likewise iterates `min(u.rows, v.rows)` (644-658), with no destination-plane height. `runCatching` around the native writes cannot catch a native memory overwrite.

Concrete accepted input: a resolver returns `SoftwarePlanes(width=1, height=1, format=Bgra, planes=listOf(Plane(ByteArray(4_000_000), bytesPerRow=4, rows=1_000_000)))`. Every public constructor check succeeds. CoreVideo is asked for a one-row image, but the helper attempts one million writes to consecutive destination rows. A more ordinary backend with padded **source** rows is also permitted by these constructors; the copier must copy only the image's logical rows. A short source plane is the inverse problem: partial image rows can be reported successfully filled.

The analogous Metal texture upload is correctly bounded: [kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:1035-1062](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt#L1035) validates the source against texture dimensions and uploads only texture.height. Thus this is specific to the sample-buffer copy path, not a blanket claim that all Apple renderer uploads lack validation. The trigger is a custom resolver supplying inconsistent plane geometry. Default FFmpeg outputs were not shown to create it; this can corrupt native memory rather than fail cleanly. No Apple runtime or ASan reproduction was available in this Linux lane.

Fix: determine required plane count, row byte width, and logical rows from the picture format and dimensions, validate all source planes and lengths with checked arithmetic before any native write, and bound every copy by the destination's actual plane height and stride. Reject malformed shapes atomically. Add excess-row, short-row, missing-plane and stride tests for BGRA/NV12/YUV420P under Apple ASan; valid source padding must remain supported. Tracker searches for `"SoftwarePlanes"` and plane/rows found no matching item; #475/#476 concern separate texture lifetime/cache ownership defects already repaired.

<a id="finding-platform-4"></a>

### PLATFORM-4: Concurrent fallback head reads can count one Android 32-bit wrap twice (P2, extracted-method reproduction)

`AudioTrackSink.extendedHead` reads `d.playbackHeadPosition()` **before** acquiring `headLock` at [kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:565-572](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt#L565). The writer calls it for deadline estimation at 518, and the actor calls it through `latencyNanos` -> `newestPlayedPosition` at 576-582 when no valid AudioTimestamp is available. Both can sample and commit in a different order. The timestamp path correctly reads the device inside the lock at 533-538; the fallback does not.

Deterministic interleaving at the first counter wrap:

1. Stored `headLastRaw=0xFFFFFFFE`, base=0.
2. Writer samples `0xFFFFFFFF`, then stalls before acquiring the lock.
3. Latency reader samples 0 and commits it: base becomes `2^32`, last=0.
4. Writer commits its stale `0xFFFFFFFF`: last becomes `0xFFFFFFFF`, answer becomes `2^33-1`.
5. Next genuine sample 1 is mistaken for another wrap: base becomes `2^33`, answer becomes `2^33+1`, although the true position is `2^32+1`.

The false played position then exceeds submitted frames, so fallback queue depth/latency clamps to zero and the callback deadline omits the real device queue. The wrong base persists until reset or a trustworthy timestamp path takes over. At 48 kHz the wrap is about 24.85 hours of continuous AudioTrack playback, so this is a long-run race on devices using the fallback, not an ordinary startup failure.

Executed `/tmp/kite-platform-probes/HeadProbe.kt`: the complete `extendedHead` method was extracted from the actual source, with only visibility changed and its fields/driver supplied by a minimal class. Latches reorder the device reads. Kotlin 2.4.21 output:

```text
first post-wrap read=4294967296
stale pre-wrap read after post-wrap commit=8589934591
next post-wrap read=8589934593 expected=4294967297
```

This tests the exact arithmetic and lock placement, not Android AudioTrack or full player execution. Existing timestamp/ordinary wrap tests do not force this fallback read/commit reordering. No matching tracker issue appeared for `"wrap" "AudioTrack"`.

Fix: take `headLock` before reading the device and keep it through wrap-state update, matching `readTimestamp`; keep reset under the same synchronization. Regression should force a stale pre-wrap sample to return after a post-wrap sample and check that the base advances once.

<a id="finding-platform-5"></a>

### PLATFORM-5: Saturating frame-to-time helpers still overflow in the final remainder addition (P3, UBSan reproduced)

[kiteplayer-rt/native/src/kite_rt_render.c:65-69](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-rt/native/src/kite_rt_render.c#L65) bounds `whole * 1000000` but does not bound the following `+ rest * 1000000 / rate`. The matching common helper at [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioRingHandle.kt:179-186](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioRingHandle.kt#L179) has the same hole. At the largest allowed whole-second value the multiplication fits while a positive fractional remainder can exceed the remaining headroom; the mirrored negative boundary fails too.

Compiled the exact shipped `kite_rt_render.c` with GCC's undefined-behavior sanitizer and called:

```c
kprt_frames_to_micros(INT64_C(442721857769035200), 48000);
```

Observed nonzero exit with:

```text
kiteplayer-rt/native/src/kite_rt_render.c:69:28: runtime error: signed integer overflow: 9223372036854000000 + 900000 cannot be represented in type 'long int'
```

Expected under the documented saturation contract is INT64_MAX. Kotlin deterministically wraps while C invokes undefined behavior. The source's comments claim both implementations saturate, and the current rescale suite tests INT64_MAX at rate 1 and small ordinary values, missing this fractional edge.

This requires hundreds of thousands of years of accumulated 48 kHz frames and is not a practical playback-duration failure; assign P3 full-range arithmetic correctness. It matters to the exported C helper's explicit full-range contract and the requested obscure numeric audit.

Fix: apply checked/saturating addition to the two terms after validating the multiplication; add just-below/at/above positive and negative thresholds over several rates and compare against an independent wide-integer oracle. Files `/tmp/kite-platform-probes/rescale.c` and binary `/tmp/kite-platform-probes/rescale`. Build command, from repository root:

```sh
gcc -std=c11 -O1 -fsanitize=undefined -fno-sanitize-recover=undefined -I kiteplayer-rt/native/include -I kiteplayer-rt/native/src /tmp/kite-platform-probes/rescale.c kiteplayer-rt/native/src/kite_rt_render.c -o /tmp/kite-platform-probes/rescale
/tmp/kite-platform-probes/rescale
```

### Coverage and counterevidence

Read substantial source in desktop/Android/web audio sinks and driver seams, Apple CoreAudio lifecycle and shared session policy, C ring/render implementation and numeric tests, Android MediaCodec admission/frame-release paths, Apple software plane upload and sample-buffer/Metal rendering, headroom refresh, view renderer attach ownership, and selected Compose renderer lifecycle. This is targeted source review, not exhaustive verification of every line or platform.

Counterevidence retained: known BT.2020 constant-luminance and ICtCp approximations are explicitly warned by `KiteFFmpegSource.warnIfColorIsApproximated` and tested, so they are a capability/fidelity limitation rather than a newly discovered silent conversion bug. Metal software texture reuse now has a bounded retired-set pool, unlike old #475. MetalPictureReader now closes its composer, unlike old #476. The Apple device callback is C and does not enter Kotlin; no claim that its real-time thread executes Kotlin or allocates was made. CoreAudio uncertain teardown intentionally retains rather than freeing resources under an unproven callback; that safety retention was not mislabeled as an accidental leak. Display headroom reads are dispatched to the main queue, so their UIKit getters are not claimed to run arbitrarily off-main.

No physical audio/video device, Android emulator, Apple simulator, real Windows/Linux sound card, GPU, HDR display, release throughput benchmark, allocation-pressure profile, or end-to-end browser audio test was executed in this lane. No nanosecond performance claim follows from source inspection. The only timings above demonstrate a deliberately blocked cancellation path; broad portable C-suite results belong to the root's separate verification log.

### Cross-review supplied to engine lane

- The decodedAudio select cancellation ownership defect is protected by a normal successful worker quiesce. Production reachability must be qualified to cancelled parent scope or a teardown whose quiesce times out: PlaybackCore13055 ignores false, then13056 cancels outstanding jobs. A selected-but-undispatched receive can lose its buffer; selected send can leave a still-queued buffer closed in the sender's finally. Primitive coroutine repro proves the ownership mechanism, not that every close/seek leaks.
- AudioScanning cancellation interrupt/close overlap is confirmed by source: child Job completion callback at41-42 invokes source.interrupt, while finally135 completes (without fencing an already-running handler) and136 closes the session. This contradicts MediaSource.kt228-231's no-overlap lifetime contract. Engine's exact-source latches prove overlap. Treat native use-after-free as the resulting lifetime risk, not an executed native crash.


## Network, adaptive streaming and I/O audit

This lane reviewed KitePlayer's code at `d5bb627e0ed35e0b2e3176689fa737a12bad9f94`. The later main update added audit documents without changing the reviewed implementations. Findings belong in KitePlayer; none below requires KiteFFmpeg to know about KitePlayer. Severity describes repair priority within the demonstrated trigger.

<a id="finding-net-1"></a>

### NET-1 (P2): Apple's public segment-store byte-array methods pass unchecked lengths to native memory

**Evidence:** source-proven native boundary, with an executed common-store forwarding probe. No Apple device or native heap-corruption test was run.

**Locations:** [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt:341-350](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt#L341) and `:370-382`; [kiteplayer-network/src@apple/io/github/yuroyami/kiteplayer/network/StoreFiles.apple.kt:89-96](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src@apple/io/github/yuroyami/kiteplayer/network/StoreFiles.apple.kt#L89) and `:102-112`; public contracts in [kiteplayer-core/src/io/github/yuroyami/kiteplayer/SegmentStore.kt:65-81](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/SegmentStore.kt#L65).

`SegmentStoreEntry.read(position, into, offset, length)` and `SegmentStoreWriter.write(from, offset, length)` do not check that the complete slice lies in the array. The Apple adapter pins the array, obtains `addressOf(offset)`, and gives `length` directly to `fread`/`fwrite`. A valid starting pointer is not proof that the following bytes belong to the array. For example, use a 4,096-byte stored span, `ByteArray(1)`, offset zero, and length 4,096: read overwrites beyond the array; write reads beyond it. This is reachable through public Kotlin APIs without calling unsafe interop directly. It requires an invalid slice supplied by a consumer; this audit did not find a normal network path that produces that invalid slice and Apple execution was not performed. The JVM adapter's array checks do not protect Apple.

The probe used the real common store and an observing storage adapter instead of invoking unsafe syscalls. Both invalid slices reached the adapter unchanged. No documented precondition transfers memory-safety responsibility to consumers; the public signatures are ordinary `ByteArray` APIs. Even with such documentation, a checked Kotlin-to-C boundary is preferable.

**Repair:** enforce `offset >= 0`, `length >= 0`, and `offset <= array.size - length` before the storage call, including zero-length cases. Use subtraction so the validation itself cannot overflow. Add identical JVM/Apple contract tests for negative arguments, end offsets, overlong slices and `Int.MAX_VALUE`; assert rejection before any file operation. The existing `kiteplayer-io` read adapters already use `requireReadSlice`.

**Tracker overlap:** parent search found #547 as the segment-store design/implementation issue. Its body and all three comments were read. They cover HTTP cache behavior, not the unchecked native slices. The search was targeted, not an exhaustive proof of novelty.

<a id="finding-net-2"></a>

### NET-2 (P2): a client-injected noncredential Vary header is cached under the wrong identity

**Evidence:** reproduced with the compiled repository JVM classes, Ktor 3.6.0/OkHttp, an actual local HTTP server, and a real file-backed segment store. A separate common-source probe and an independent reviewer's predicate controls corroborated it. This was a reader-level integration test, not full audiovisual playback.

**Locations:** [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:307-319](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt#L307) and `:1052-1081`, particularly `:1074`; [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt:58-71](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt#L58), `:86-90`, and `:199-234`.

Use `KtorMediaIoResolver(client = HttpClient { defaultRequest { header("X-Device", "phone") } }, segmentStore = store)` with no explicit resolver/item X-Device header. A segment answers `Vary: X-Device`, `Cache-Control: max-age=600`, and body for the phone representation. `storeAnswer` sees the actual sent headers, but `sentVaried` compares them to the explicit map only when the varied name is Authorization or Cookie. For X-Device it is false. The entry name is therefore computed with `x-device=` rather than `x-device=phone`. A second resolver sharing the store and injecting `X-Device: tablet` from its client pipeline finds the same fresh entry before any request runs and receives the phone bytes. Accept-Language or another client-injected varied header has the same issue.

The real HTTP probe returned `phone` for both phone and tablet clients while the server recorded only one segment request. Supplying X-Device explicitly to the resolver returned `tablet` and made the second network request, the passing control. The server provided a representation-specific ETag. The source comment says implicitly added varied headers are not stored because the next request cannot be matched, but the predicate implements that rule for only two headers. The executed probe passed an actual-sent X-Device value to the verbatim `storeAnswer` function, wrote `PHON`, reopened through `SegmentReuse.plan` with the same empty explicit map, and obtained `PHON` from a fresh entry. It also checked that the entry name differs from the name computed with the actually sent header.

**Impact:** the wrong rendition can be returned while the server correctly declares `Vary`; fresh hits bypass the server until expiry. This does not require a cache-key hash collision. Explicit item/resolver headers are handled correctly and are the useful control.

**Repair:** conservatively bypass persistence when any varied actual request header differs from what can be reproduced at lookup, not just credential names. Alternatively build a request identity after the client pipeline and use the same identity for lookups and writes. Do not fetch a fresh entry before deciding how implicit headers affect identity. Add two clients with distinct injected X-Device values, a counting server, and the same shared store; both must receive their own body. Keep explicit-header and matching-header controls.

**Tracker overlap:** #547 and its comments were read. The closed defect covers 304 revalidation and changes to Vary. Its original acceptance requires all relevant request variation. This is a separate initial 200/206 response identity defect; it does not allege that the fixed 304 defect remains. The implementation comment acknowledges implicit Cookie/Authorization handling but does not address arbitrary implicit varied headers.

<a id="finding-net-3"></a>

### NET-3 (P2): canceled HTTP cache opens can abandon a transferred entry holder

**Evidence:** source-supported ownership/cancellation schedule; a deterministic handoff probe uses the real `StorePlan`/store classes and the same Deferred handoff pattern, not a real Ktor request.

**Locations:** [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:819-831](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt#L819), `:850-860`, `:900-909`, `:930-940`; [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt:259-274](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt#L259); [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt:229-260](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt#L229).

The producer's header handler calls `storeAnswer`, which calls `plan.spans()`. That clears `StorePlan.entry` and transfers the held entry into `StoredSpans`. The producer then completes the `Probe` Deferred with that resource. If the caller is canceled after completion but before `probe.await()` resumes, coroutines' prompt cancellation prevents delivery. The open catch cancels its separate scope and possibly the HTTP client. `openStored` then calls `plan.abandon()`, but that plan no longer owns the entry. Neither path closes the `StoredSpans` inside the completed Probe.

The same ownership transfer occurs for 304 responses. The directory store maintains an explicit holder count, so garbage collection of the undelivered Kotlin wrapper does not release it: eviction skips the resource indefinitely, and `clear()` marks it removed without deleting its bytes because its holder never closes. Repeated canceled opens can defeat eviction for the affected entries until store/process restart.

**Repair:** make the producer-to-caller handoff cancellation-safe, closing an answer's stored spans when completion/delivery loses ownership. Canceling the producer alone is insufficient after completion. Keep an explicit owner until delivery is acknowledged, or use a handoff primitive with an undelivered-resource cleanup path. Test cancellation after headers/store transfer and before await delivery, then `clear` and fill the quota with another entry; no orphaned holder should remain. Also test timeout and 304 races.

**Tracker overlap:** no novelty claim beyond the parent's targeted SegmentStore search. #547's reviewed comments do not discuss this handoff.

<a id="finding-net-4"></a>

### NET-4 (P2): DASH redirected-base handling is also wrong on automatic live refresh

**Evidence:** source-proven call chain, no real redirected HTTP playback run. This independently confirms and extends N2 in the preexisting `audit/GPT-6_CODEX_AUDIT.md`; do not count the explicit-API portion as a newly discovered defect.

**Locations:** [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt:84-105](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt#L84), `:168-171`, `:239-259`; [kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashDoor.kt:176-189](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashDoor.kt#L176).

The explicit API's fetch returns bytes without the final response location, then parses relative references against the original requested URL. The automatic first-open path correctly uses `io.location`, but its live `refetch` closure does not: it closes the returned reader, retaining only the bytes, then calls `manifest(body, url, ...)` with the pre-redirect request URL. Consequently a stream that initially works can fail after the server redirects a later MPD refresh into another directory or origin.

For the automatic case, initially serve `https://media.test/live/manifest.mpd` as a valid dynamic MPD. At the next update, redirect that URL to `/event/live/manifest.mpd`, whose segment template is `s-$Number$.m4s` and whose segments exist only under `/event/live/`. Refreshed plans resolve them under `/live/`, requesting the wrong media. RFC 3986 section 5.1.3 uses the retrieval URI after redirects as the base. Relative `Location` and clock references also depend on the manifest's proper base.

**Repair:** carry final retrieval location with manifest bytes on explicit fetch and every automatic refetch, parse against that location, and keep original request/origin-policy anchors separate. Add a successful initial automatic open followed by a same-origin directory redirect on refresh and assert the actual fetched segment URL. Keep the initial automatic redirect case as a control. The earlier report's statement that the automatic path is correct needs the qualification "on initial detection".

<a id="finding-net-5"></a>

### NET-5 (P2): AES-128 encrypted HLS TTML subtitles reach the converter before decryption

**Evidence:** reproduced through the compiled repository's HlsTtmlMediaIo and real TTML/MP4 converters. The existing Mp4Bytes fixture built a valid stpp initialization and fragment; Java AES/CBC/PKCS5Padding encrypted that same fragment. Clear conversion yielded `Secret cue`; encrypted conversion retained EXT-X-KEY, never fetched the key, and failed `IllegalArgumentException: not a well formed TTML document: expected '<' at offset 0`. The upstream media transport was an in-memory fixture, not HTTP or full FFmpeg playback.

**Locations:** [kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/HlsTtml.kt:82-110](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/HlsTtml.kt#L82), `:127-136`, `:151-158`; [kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIoResolverProvider.kt:41-44](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIoResolverProvider.kt#L41); downstream decryption order is visible in KiteFFmpeg [native/kitecodec-c/src/helpers_format.c:1234-1251](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_format.c#L1234).

An HLS master naming `stpp` subtitles is wrapped by `HlsTtmlMediaIo`. Its rendition-playlist rewrite handles maps and byte ranges but leaves `EXT-X-KEY` unchanged. Its recorded `Segment` has no key, IV, or sequence information. When FFmpeg requests a synthetic `.vtt` segment, `cuesOf` obtains raw bytes from the upstream HTTP reader and parses them as MP4 or TTML before returning any reader. For a valid AES-128 encrypted subtitle segment these are ciphertext. The converter cannot recover the TTML without the key; `TtmlParser.read` rejects the non-XML data.

Native FFmpeg's generic AES bridge does not rescue this composition: it first calls the Kotlin opener and only then decrypts the reader it receives. The Kotlin opener has already tried to convert ciphertext and failed. Even an upstream that supplied plaintext would leave a stale AES-128 tag instructing FFmpeg to decrypt the converted WebVTT again. [docs/streaming.md:55](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/docs/streaming.md#L55) combines AES-128 and separate subtitle renditions without this limitation. This is a KitePlayer conversion-layer defect, not a reason for independent KiteFFmpeg to implement knowledge of the player's TTML proxy.

**Repair:** carry encryption state through the rendition rewrite and decrypt original media and encrypted maps before subtitle conversion, then remove the encryption directive from the plaintext synthetic rendition. A shared general-purpose AES helper can remain independent of player-specific conversion. Test clear TTML and full-segment AES-128 encrypted stpp with explicit and sequence-derived IVs, key rotation and encrypted initialization. Alternatively explicitly refuse and document this unsupported combination until wired end to end.

**Tracker overlap:** #439's body and its empty comment list were read; it covers adding TTML-in-HLS conversion, not encryption composition. Parent targeted search did not identify this combination. No full issue-history novelty guarantee is claimed.

### Executed common-source probes

The probes compiled the checked-in `SegmentStore.kt`, `SegmentReuse.kt`, `SegmentFreshness.kt`, and `MemoryStoreFiles.kt` directly. `FileSegmentStore.kt` was copied to `/tmp` with only its `expect platformStoreFiles` declaration removed so a stub platform binding could be supplied; its store implementation was unchanged. `factsOf` and `storeAnswer` were copied verbatim from `KtorMediaIo.kt`. HTTP headers/response objects and the warning type were minimal stubs. Atomicfu's monitor wrapper was replaced with JVM `synchronized`. The buffer-bound probe used a storage spy that records the invalid slice and safely allocates scratch bytes instead of touching native memory. These are unit-level policy and forwarding checks, not packaged-library or Apple execution.

Compiler: Kotlin 2.4.10 from the existing bootstrap, rather than the repository's pinned 2.4.21. Runtime: installed Zulu JRE 25; coroutines 1.11.0 for the handoff check. These compiler differences do not prove repository compilation. No dependency or repository source was changed.

Repro command: `python3 /tmp/kite-network-probes/build.py`. The driver and generated source are available in that directory during this audit; the following appendix embeds them for the permanent report.

Initial observed output:

```text
IMPLICIT_VARY_RETURNED=PHON
IMPLICIT_VARY_STORED_UNDER_EMPTY_HEADER=true
OUT_OF_BOUNDS_READ_REACHES_STORAGE=true
OUT_OF_BOUNDS_WRITE_REACHES_STORAGE=true
CANCELED_HANDOFF_BYTES_LEFT_AFTER_CLEAR=4
```

### Coverage and limitations

Read complete or substantial control paths of KtorMediaIo, RedirectRule, SegmentReuse, SegmentFreshness, FileSegmentStore and Apple/JVM adapters; DashDoor, DashManifest planning, DashHlsMediaIo, SegmentIndex, DashFailover and HlsTtml; JVM/Android/Apple/POSIX file and stream adapters; relevant redirect, store, timing and TTML tests; public MediaIo/SegmentStore contracts and streaming claims. Reviewed #547 body and all comments and #439 body/comments, plus parent's targeted tracker results. After independent initial inspection, the prior audit was read to avoid claiming rediscovery as novelty.

Positive mechanisms seen include bounded network pipes, read/connect timeouts, preservation of caller cancellation in network retries, per-origin item headers for related opens, explicit stale-source/entity checks, store failure fallback, map/URL/XML budgets, live playlist retention and synchronized store indexing. These observations are not blanket correctness guarantees.

The preexisting report's live SegmentList index mismatch, missing list inheritance, expired-timeline work bound, and temporary-write disk-budget risk were seen in the reviewed source, but were not independently rerun in this lane and are not restated as new executed findings. Device TLS/socket timing, Android/iOS Native lifecycle, a real Apple slice test, full FFmpeg encrypted subtitle playback, HTTP handoff cancellation, browser persistent caches, release throughput/tail latency and all codec/protocol combinations remain unqualified by this lane. The parent report separately records broader test execution.

### Reproduction appendix: complete probe builder

The final handoff test starts a suspended consumer undispatched, transfers `moving.spans()` into a Deferred, cancels the consumer before its queued continuation runs, and then calls `clear()`. The four original bytes remain because the transferred entry was never released. This reproduces the ownership mechanics with the actual store classes; the HTTP producer/consumer boundary itself is represented by a small coroutine harness.

```python
from pathlib import Path
import subprocess
r=Path('/workspace/KitePlayer')
p=Path('/tmp/kite-network-probes')
p.mkdir(parents=True, exist_ok=True)
b=Path('/workspace/.cache/kotlin-bootstrap/kotlin-cli-0.13.0')
(p/'Locks.kt').write_text('''package kotlinx.atomicfu.locks
open class SynchronizedObject
inline fun <T> synchronized(lock: SynchronizedObject, block: () -> T): T = kotlin.synchronized(lock, block)
''')
(p/'Core.kt').write_text('''package io.github.yuroyami.kiteplayer
sealed interface PlaybackWarning { data class SegmentStoreFailed(val message:String): PlaybackWarning }
''')
(p/'Http.kt').write_text('''package io.ktor.http
object HttpHeaders {
 const val Authorization="Authorization"; const val Cookie="Cookie"; const val CacheControl="Cache-Control"; const val Vary="Vary"; const val ETag="ETag"; const val LastModified="Last-Modified"; const val Date="Date"; const val Expires="Expires"; const val Age="Age"
}
class Headers(private val values: Map<String,String>) {
 operator fun get(name:String):String?=values.entries.firstOrNull { it.key.equals(name,true) }?.value
 fun getAll(name:String):List<String>?=get(name)?.let { listOf(it) }
}
''')
(p/'Response.kt').write_text('''package io.ktor.client.statement
import io.ktor.http.Headers
class HttpResponse(val headers:Headers,val call:Call)
class Call(val request:Request)
class Request(val headers:Headers)
''')
(p/'Network.kt').write_text('''package io.github.yuroyami.kiteplayer.network
internal fun basicLogin(uri:String):String?=null
internal fun platformStoreFiles():StoreFiles?=null
''')
s=(r/'kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt').read_text()
a=s.index('private fun factsOf('); z=s.index('/**\n * The headers of the requests that a reader',a)
(p/'Answer.kt').write_text('''package io.github.yuroyami.kiteplayer.network
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.HttpResponse
'''+s[a:z]+'''\ninternal fun answer(plan:StorePlan, response:HttpResponse, explicit:Map<String,String>)=storeAnswer(plan,response,explicit,"https://cdn.test/seg.ts",4L,true,"video/mp2t",true,100L)
''')
(p/'Probe.kt').write_text('''package io.github.yuroyami.kiteplayer.network
import io.ktor.http.Headers
import io.ktor.client.statement.*
import kotlinx.coroutines.*

fun main() {
 val files=MemoryStoreFiles()
 val store=directorySegmentStore(files,"/cache",100000)!!
 val reuse=SegmentReuse(store) { 100L }
 val uri="https://cdn.test/seg.ts"
 val explicit=emptyMap<String,String>()
 val plan=reuse.plan(uri,explicit) {}!!
 val response=HttpResponse(Headers(mapOf("Vary" to "X-Device","Cache-Control" to "max-age=600","ETag" to "\\\"v1\\\"")),Call(Request(Headers(mapOf("X-Device" to "phone")))))
 val spans=answer(plan,response,explicit)!!
 spans.wrote(0,"PHON".encodeToByteArray(),0,4)
 spans.close()
 val next=reuse.plan(uri,explicit) {}!!
 check(next.fresh()!=null)
 val bytes=ByteArray(4)
 check(next.spans()!!.read(0,bytes,0,4)==4)
 println("IMPLICIT_VARY_RETURNED="+bytes.decodeToString())
 val actualName=reuse.entryName(uri,explicit,listOf("x-device"))
 check(actualName != reuse.entryName(uri,mapOf("X-Device" to "phone"),listOf("x-device")))
 println("IMPLICIT_VARY_STORED_UNDER_EMPTY_HEADER=true")

 var forwardedRead=false
 var forwardedWrite=false
 val backing=MemoryStoreFiles()
 val spy=object:StoreFiles by backing {
   override fun read(path:String,position:Long,into:ByteArray,offset:Int,length:Int):Int { forwardedRead=length>into.size-offset; return length }
   override fun create(path:String):StoreFileSink {
     val sink=backing.create(path)
     return object:StoreFileSink { override fun write(from:ByteArray,offset:Int,length:Int) { if(length>from.size-offset) { forwardedWrite=true; sink.write(ByteArray(length),0,length) } else sink.write(from,offset,length) }; override fun close()=sink.close() }
   }
 }
 val unsafe=directorySegmentStore(spy,"/bounds",100000)!!
 val entry=unsafe.open("probe")
 entry.write(0).use { w -> w.write(ByteArray(1),0,4096); w.publish() }
 entry.read(0,ByteArray(1),0,4096)
 check(forwardedWrite && forwardedRead)
 println("OUT_OF_BOUNDS_READ_REACHES_STORAGE=$forwardedRead")
 println("OUT_OF_BOUNDS_WRITE_REACHES_STORAGE=$forwardedWrite")
 runBlocking {
   val backing2=MemoryStoreFiles()
   val store2=directorySegmentStore(backing2,"/handoff",100000)!!
   val reuse2=SegmentReuse(store2) { 100L }
   val original=reuse2.plan(uri,explicit) {}!!
   answer(original,response,explicit)!!.apply { wrote(0,"PHON".encodeToByteArray(),0,4); close() }
   val moving=reuse2.plan(uri,explicit) {}!!
   val deferred=CompletableDeferred<StoredSpans>()
   val receiver=launch(start=CoroutineStart.UNDISPATCHED) {
     try { deferred.await().close() } catch (cancelled:CancellationException) { moving.abandon(); throw cancelled }
   }
   deferred.complete(moving.spans()!!)
   receiver.cancel()
   receiver.join()
   store2.clear()
   check(store2.sizeBytes==4L)
   println("CANCELED_HANDOFF_BYTES_LEFT_AFTER_CLEAR=${store2.sizeBytes}")
 }

}
''')
(p/'FileSegmentStore.kt').write_text((r/'kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt').read_text().replace('internal expect fun platformStoreFiles(): StoreFiles?', '// Platform binding supplied by probe'))
sources=[r/'kiteplayer-core/src/io/github/yuroyami/kiteplayer/SegmentStore.kt',r/'kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentFreshness.kt',r/'kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt',r/'kiteplayer-network/test/io/github/yuroyami/kiteplayer/network/MemoryStoreFiles.kt']+list(p.glob('*.kt'))
cmd=['/workspace/.cache/kotlin-bootstrap/zulu25.32.21-ca-jre25.0.2-linux_x64/zulu25.32.21-ca-jre25.0.2-linux_x64/bin/java','-cp',f'{b}/extra/jars/kotlin-compiler-2.4.10.jar:{b}/lib/kotlin-stdlib-2.4.10.jar:{b}/lib/kotlin-reflect-2.3.20-RC2.jar:{b}/lib/kotlinx-coroutines-core-jvm-1.11.0.jar:{b}/extra/jars/annotations-26.0.2.jar','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',f'{b}/lib/kotlin-stdlib-2.4.10.jar:{b}/lib/kotlinx-coroutines-core-jvm-1.11.0.jar','-d',str(p/'probe.jar')]+list(map(str,sources))
subprocess.run(cmd,check=True)
subprocess.run(['/workspace/.cache/kotlin-bootstrap/zulu25.32.21-ca-jre25.0.2-linux_x64/zulu25.32.21-ca-jre25.0.2-linux_x64/bin/java','-cp',f'{p}/probe.jar:{b}/lib/kotlin-stdlib-2.4.10.jar:{b}/lib/kotlinx-coroutines-core-jvm-1.11.0.jar','io.github.yuroyami.kiteplayer.network.ProbeKt'],check=True)

```

### Reader integration validation with the pinned JVM build

Create `/tmp/kite-network-runtime` and save the Kotlin driver below as `RuntimeProbe.kt` there before running its compiler script. Cache and checkout paths identify this session; adjust them to your matching local setup.

After the parent built the project, a separate driver compiled with Kotlin 2.4.21 against those existing class outputs and the resolved Ktor 3.6.0/OkHttp dependency set. It did not rebuild or change the project. Coroutines 1.11.0 was placed before any unrelated older compiler dependency on the standalone runtime classpath. A first overly broad classpath accidentally loaded coroutines 1.8.0 and failed linkage; the pinned-classpath run below succeeded. That setup error was not a library finding. The HLS fixture uses a clear initialization map before the AES key tag and encrypts only the following media fragment, with an explicit zero IV.

Command: `python3 /tmp/kite-network-runtime/run.py`. Observed output:

```text
HTTP_VARY_FIRST=phone SECOND=phone SEGMENT_REQUESTS=1
HTTP_EXPLICIT_CONTROL=tablet SEGMENT_REQUESTS=2
CLEAR_STPP_CONVERSION=true
ENCRYPTED_TTML_RETAINS_KEY=true
ENCRYPTED_STPP_CONVERSION=FAILED IllegalArgumentException: not a well formed TTML document: expected '<' at offset 0
CONVERTER_FETCHED_KEY=false
```

Driver:

```kotlin
package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private suspend fun MediaIo.bytes():ByteArray {
 val out=java.io.ByteArrayOutputStream(); val b=ByteArray(1024)
 while(true) { val n=read(b,0,b.size); if(n<0)break; if(n>0)out.write(b,0,n) }
 return out.toByteArray()
}
private class Memory(override val location:String, val data:ByteArray, val files:Map<String,ByteArray>):MediaIo {
 var at=0; val opened=ArrayList<String>()
 override val size:Long get()=data.size.toLong()
 override val seekable:Boolean get()=true
 override suspend fun read(into:ByteArray,offset:Int,length:Int):Int { if(at==data.size)return -1;val n=minOf(length,data.size-at);data.copyInto(into,offset,at,at+n);at+=n;return n }
 override suspend fun seek(position:Long){at=position.toInt()}
 override suspend fun openRelated(uri:String):MediaIo? { opened+=uri;return files[uri]?.let{Memory(uri,it,files)} }
 override fun close(){}
}
fun main()=runBlocking {
 val count=AtomicInteger()
 val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
 server.createContext("/") { exchange ->
   val seg=exchange.requestURI.path=="/seg.ts"
   val body=if(seg) {count.incrementAndGet();exchange.requestHeaders.getFirst("X-Device").encodeToByteArray() } else "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n".encodeToByteArray()
   if(seg){exchange.responseHeaders.add("Vary","X-Device");exchange.responseHeaders.add("Cache-Control","max-age=600");exchange.responseHeaders.add("ETag","\"${exchange.requestHeaders.getFirst("X-Device")}\"")}
   exchange.responseHeaders.add("Content-Type",if(seg) "video/mp2t" else "application/vnd.apple.mpegurl")
   exchange.responseHeaders.add("Content-Range","bytes 0-${body.size-1}/${body.size}")
   exchange.sendResponseHeaders(206,body.size.toLong());exchange.responseBody.use{it.write(body)}
 }
 server.start()
 val root="http://127.0.0.1:${server.address.port}"
 val store=fileSegmentStore(Files.createTempDirectory("kite-vary-").toString(),100000)!!
 try {
   suspend fun play(device:String, explicit:Boolean=false):String {
     val client=HttpClient { defaultRequest { header("X-Device",device) } }
     val resolver=KtorMediaIoResolver(client=client,headers=if(explicit)mapOf("X-Device" to device)else emptyMap(),segmentStore=store)
     return try { resolver.resolve("$root/list.m3u8")!!.use { list -> list.bytes();list.openRelated("$root/seg.ts")!!.use { it.bytes().decodeToString() } } }
     finally { resolver.close();client.close() }
   }
   val first=play("phone");val second=play("tablet")
   println("HTTP_VARY_FIRST=$first SECOND=$second SEGMENT_REQUESTS=${count.get()}")
   check(first=="phone" && second=="phone" && count.get()==1)
   val control=play("tablet",explicit=true);check(control=="tablet" && count.get()==2);println("HTTP_EXPLICIT_CONTROL=$control SEGMENT_REQUESTS=${count.get()}")
 } finally {store.close();server.stop(0)}

 val base="https://cdn.test/subs/"
 val init=Mp4Bytes.init(trackId=1,timescale=1000,handler="subt",sampleEntry="stpp")
 val plain=Mp4Bytes.segment(1,0,listOf(Sample(2000,"<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0s\" end=\"1s\">Secret cue</p></div></body></tt>".encodeToByteArray())))
 val key=ByteArray(16){it.toByte()};val iv=ByteArray(16)
 val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))}.doFinal(plain)
 suspend fun convert(encrypted:Boolean):Pair<String,Memory> {
   val list=buildString { append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI=\"init.mp4\"\n");if(encrypted)append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000000\n");append("#EXTINF:2,\nseg.m4s\n#EXT-X-ENDLIST\n") }
   val files=mapOf(base+"list.m3u8" to list.encodeToByteArray(),base+"init.mp4" to init,base+"seg.m4s" to if(encrypted)cipher else plain,base+"key.bin" to key)
   val upstream=Memory(base+"master.m3u8",ByteArray(0),files)
   val reader=HlsTtmlMediaIo(upstream,setOf(base+"list.m3u8"))
   val rewritten=reader.openRelated(base+"list.m3u8")!!.use{it.bytes().decodeToString()}
   if(encrypted)println("ENCRYPTED_TTML_RETAINS_KEY=${rewritten.contains("#EXT-X-KEY")}")
   val synthetic=rewritten.lines().first {it.startsWith("https://")}
   val result=try{reader.openRelated(synthetic)!!.use{it.bytes().decodeToString()}}catch(e:Exception){"FAILED ${e::class.simpleName}: ${e.message}"}
   return result to upstream
 }
 val (clearResult,_)=convert(false);check(clearResult.contains("Secret cue"));println("CLEAR_STPP_CONVERSION=true")
 val (encryptedResult,used)=convert(true);println("ENCRYPTED_STPP_CONVERSION=$encryptedResult");println("CONVERTER_FETCHED_KEY=${used.opened.any{it.endsWith("key.bin")}}")
 check(encryptedResult.startsWith("FAILED") && used.opened.none{it.endsWith("key.bin")})
}

```

Compilation and run script:

```python
from pathlib import Path
import subprocess
p=Path('/tmp/kite-network-runtime'); r=Path('/workspace/KitePlayer'); b=Path('/workspace/.cache/kotlin-bootstrap/kotlin-cli-0.13.0'); m=Path('/workspace/.cache/kotlin/.m2.cache')
java='/workspace/.cache/kotlin-bootstrap/zulu25.32.21-ca-jre25.0.2-linux_x64/zulu25.32.21-ca-jre25.0.2-linux_x64/bin/java'
outputs=[str(f/'kotlin-output') for f in (r/'build/artifacts/CompiledJvmArtifact').iterdir() if (f/'kotlin-output').exists()]
stdlib=m/'org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar'
cp=':'.join([str(stdlib),str(m/'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar')]+outputs+[str(f) for f in m.rglob('*.jar')])
compiler=':'.join([str(m/'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.jar'),str(stdlib),str(b/'lib/kotlin-reflect-2.3.20-RC2.jar'),str(b/'lib/kotlinx-coroutines-core-jvm-1.11.0.jar'),str(b/'extra/jars/annotations-26.0.2.jar')])
subprocess.run([java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-Xfriend-paths='+','.join(outputs),'-d',str(p/'runtime.jar'),str(p/'RuntimeProbe.kt')],check=True)
subprocess.run([java,'-cp',str(p/'runtime.jar')+':'+cp,'io.github.yuroyami.kiteplayer.network.RuntimeProbeKt'],check=True)

```


## Native codec and interop lane

All finding paths in this section belong to **KiteFFmpeg**, at `f79ba0864c8697f91ef5e3509b46b115afc7dcd7`. Fix these upstream and consume a corrected artifact in KitePlayer. The independent binding should not gain player-specific dependencies to repair them.

<a id="finding-nf1"></a>

### NF1. P1: synchronous decoded-frame flows can leak an owned frame at cancellation

**Evidence:** independently reconfirmed source ownership defect; existing KiteFFmpeg issue [#199](https://github.com/yuroyami/KiteFFmpeg/issues/199), also documented by the pre-existing audit KF-2. No novelty claim and no new native heap measurement in this lane.

**Locations:** [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:182-186](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt#L182), [kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:347-348](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt#L347); related Web producer [kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/MediaSource.wasmJs.kt:335-356](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/MediaSource.wasmJs.kt#L335). Ownership/counterevidence: [kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/FrameFlow.kt:57-62](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/FrameFlow.kt#L57) and `AsyncMediaRuntime.kt`'s `AsyncDecodeFlow` explicitly avoid the same builder hazard.

The synchronous producers use the standard `flow` builder and call `emit(it.copy())`. `copy()` allocates an independently owned native reference. SafeCollector checks cancellation before calling the downstream collector. If cancellation is raised during the native read/decode/clone, the clone exists when `emit` throws but no collector has received it. Cleanup closes the temporary callback wrapper and decoder, not this independent clone. Neither GC nor closing the original decoded frame frees the clone's reference.

`bufferFrames` does not repair a value rejected by its upstream SafeCollector before its channel sees it. Its own comment accurately describes this failure mode, but only its consumer side is fixed. The async producer implements `Flow` directly and transfers ownership at the direct collector invocation.

**Regression:** block a decode just before a produced frame is delivered, cancel the collector, release the block, and assert the native frame-handle/live-allocation count returns to baseline. Repeat with `bufferFrames`, with its producer suspended/canceled upstream, and on JVM and Native. A plain `take(1)` test alone misses the before-delivery cancellation window. Repair the producer handoff with the same explicit ownership discipline as the async producer; do not blindly close a frame on every downstream exception because `first`/`take` can throw after ownership transferred.

<a id="finding-nf2"></a>

### NF2. P1: JNI caption decoding can re-enter Java while a primitive array is critical

**Evidence:** independently reconfirmed source/API-contract violation; existing KiteFFmpeg issue [#207](https://github.com/yuroyami/KiteFFmpeg/issues/207), pre-existing audit KF-1. No JVM crash or real-device deadlock was induced in this lane.

**Locations:** [native/kitecodec-jni/kj_codec.c:326-352](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_codec.c#L326), [native/kitecodec-jni/kj_util.c:346-361](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_util.c#L346), [native/kitecodec-c/src/helpers_subtitle.c:124-136](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_subtitle.c#L124), and [native/kitecodec-jni/kj_abi.c:42-72](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-jni/kj_abi.c#L42).

`kj_caption_decode` sends a Java byte array through `kj_bytes_read_in_place`. That utility holds `GetPrimitiveArrayCritical` across its callback, and the caption callback calls the FFmpeg decoder. FFmpeg can log malformed captions; the installed log bridge then constructs Java strings and calls the Kotlin log method before `ReleasePrimitiveArrayCritical`. Those JNI calls are forbidden inside the critical region. The other uses that only copy memory do not establish that this decode callback is safe.

**Impact and regression:** caption input plus an enabled log sink can violate VM critical-region constraints and interfere with GC or runtime progress. Use `-Xcheck:jni` with logging enabled and caption bytes that make eia_608 log; verify no forbidden call occurs while critical. Copy caption bytes out before decoding, or use a noncritical array access appropriate to a call that allocates/logs. Leave short bounded memory-copy uses separate.

<a id="finding-nf3"></a>

### NF3. P2: a byte-source close failure replaces the error that made open fail

**Evidence:** exact source control flow, no integrated current-source JVM reproduction completed. This is not an assertion that all cleanup failures are swallowed everywhere: normal `MediaSource.close()` already uses `CloseFailures`, and the Native initial custom-open branch already adds a secondary close failure as suppressed.

**Locations:** JVM [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:703-706,726-730](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt#L703); Native [kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:951-964,990-993](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt#L951). JVM adapter cleanup is [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/JniByteIo.kt:145-156](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/JniByteIo.kt#L145).

**Trigger:** supply a `MediaByteSource` whose `seekable` or `size` getter throws a recognizable primary exception and whose `close()` throws a second exception; call `MediaSource.open(io)` on JVM. The opening catch invokes `adapter.closeSource()` directly before `throw error`. The close exception exits that catch, so the caller receives only the close error and the primary failure is absent from cause/suppressed. The same order masks FFmpeg probing failures on JVM. On Native, `assembleMediaSource` calls `unwind()` before constructing or rethrowing the discovery/metadata error; a throwing source close masks it there too.

**Impact:** applications lose the typed decode/open error and the original transport cause exactly in a multi-failure recovery case. Native and JVM differ even for initial getter failure because Native's earlier open cleanup does preserve suppression.

**Fix/regression:** retain the original failure, run all independent cleanup, and add cleanup failures as suppressed. Capture `ioState.takeFailure()` before cleanup where necessary. Test both pre-context getter failure and post-context stream-discovery failure with a throwing close; assert primary identity/type, close count of one, and suppressed cleanup failure.

<a id="finding-nf4"></a>

### NF4. P2: batch remux/transcode cancellation is not wired to the blocking FFmpeg open/read

**Evidence:** source-supported high-level cancellation gap; no current-source stalled-server reproduction completed. Separate from the prior audit's encoder-tail cancellation finding.

**Locations:** [kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/TranscodeDispatch.kt:18-36](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/TranscodeDispatch.kt#L18), [kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaEnds.kt:13-17](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaEnds.kt#L13), JVM [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:65-82](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt#L65), [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:117-147](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt#L117), [kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:252-255](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt#L252). Native batch implementations use the same common `InputEnd` and `runTranscode` route. `OpenInterrupt.kt` and `AsyncLane.kt` demonstrate an available interruption mechanism and a different async implementation that does use it.

**Trigger:** call `Remuxer.remux("http://127.0.0.1:<port>/stall", output)` or the corresponding transcode against a server that accepts the connection/request and never sends the response; cancel its coroutine after acceptance. `inputAt` calls `MediaSource.open` without an `OpenInterrupt`, and no cancellation callback calls `MediaSource.interrupt`. `withContext(Dispatchers.IO)` moves the blocking call but does not interrupt it. `ensureActive` in the pump only executes between reads, so the same problem occurs after open when a network read stalls. `cancelAndJoin` or a surrounding `withTimeout` cannot finish until the native operation itself returns. Specifying a network timeout manually limits the wait but does not wire cancellation.

**Impact:** abandoned batch work retains its coroutine, IO thread, native context and transport while the remote endpoint stalls. This finding concerns library-controlled FFmpeg network waits. A caller-provided synchronous `MediaByteSource.read` that ignores cancellation has an additional, documented blocking limitation; do not promise an arbitrary callback can be forcibly unwound.

**Fix/regression:** bind an `OpenInterrupt` before each native open and keep it alive until the source is closed; on cancellation raise it without freeing a context still executing. Pair cancellation with cleanup/join so resource handoff remains safe. Cover no-progress and progress-callback overloads, initial open, probe, later reads and the second input open that video-spec inheritance performs. Use a local stalled HTTP server and release its connection after the bounded observation so the regression never leaves an immortal test worker.

<a id="finding-nf5"></a>

### NF5. P3: subtitle timestamp addition has native signed-overflow undefined behavior

**Evidence:** reproduced with GCC UBSan using the unchanged production `helpers_subtitle.c` and the provisioned FFmpeg headers/static libraries. This is a boundary-level synthetic `AVSubtitle` test; no crafted media file or player crash is claimed.

**Location:** [native/kitecodec-c/src/helpers_subtitle.c:139-149](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/helpers_subtitle.c#L139), specifically lines 144 and 147.

`ffkmp_subtitle_times` checks the no-timestamp sentinel but adds unsigned millisecond offsets converted to microseconds to a signed 64-bit base without checking the sum. For `pts = INT64_MAX - 500`, `start_display_time = 1`, and `end_display_time = 2`, it returns success and negative wrapped start/end values, and UBSan reports two signed integer overflows. The multiplication alone fits in 64 bits; the addition is the defect. Public caption APIs accept a `Long` timestamp, and demuxed subtitle timestamps are input-derived, but reachability of this exact synthetic combination through a shipping container/decoder remains unmeasured.

Exact driver:

```c
#include "kitecodec_helpers.h"
#include <libavcodec/avcodec.h>
#include <stdint.h>
#include <stdio.h>
#include <limits.h>
int main(void) {
    AVSubtitle s = {0};
    s.pts = INT64_MAX - 500;
    s.start_display_time = 1;
    s.end_display_time = 2;
    int64_t start = 0, end = 0;
    int rc = ffkmp_subtitle_times(&s, &start, &end);
    printf("rc=%d start=%lld end=%lld\n", rc, (long long)start, (long long)end);
}
```

Observed output:

```text
rc=0 start=-9223372036854775309 end=-9223372036854774309
helpers_subtitle.c:144:24: runtime error: signed integer overflow: 9223372036854775307 + 1000 cannot be represented in type 'long int'
helpers_subtitle.c:147:26: runtime error: signed integer overflow: 9223372036854775307 + 2000 cannot be represented in type 'long int'
```

Compile the driver plus the actual `native/kitecodec-c/src/helpers_subtitle.c` with `gcc -std=c11 -O1 -g -fsanitize=undefined -ffunction-sections -fdata-sections`, the C public/include and `native-libs/lgpl/linux-x64/include` include directories, and `-Wl,--gc-sections`; link the matching tree's FFmpeg libraries, dav1d, zlib, libm, pthread and libdl. The normal ABI object supplies constructors' identity gate if not discarded. The run used `/tmp/kite-native-probes/subtitle_time_overflow.c` and saved output to `subtitle_time_overflow.log`.

**Fix/regression:** validate `base <= INT64_MAX - offset` before addition and define a typed invalid-data/unknown-time policy; test the boundary just below, at, and above representability, along with `AV_NOPTS_VALUE` and `UINT32_MAX` indefinite duration.

### Executed native checks and limits

The provisioned tree is `native-libs/lgpl/linux-x64`. Its patch manifest records all fifteen source patches, including HLS custom IO, redirected media/chapters and the first-packet audio probe patch. This lane compiled all 18 unchanged C production translation units with GCC `-std=c11 -O2 -g` against that tree, and linked unchanged repository test sources to those objects. The `helpers_format` unit was recompiled with the repository's `KC_TESTING` define for the keyframe-seek observation seam.

**26 unique suites, 397 functional cases passed:** swr (5), subtitle (6), input_io (5), output_io (4), ownership (45), convert (29), display (12), nested_io (37), interrupt (5), hdr (6), dovi (12), rotation (16), crop (4), spherical (21), subtitle_convert (2), language (12), editions (15), keyframe_seek (13), packet_presentation (8), duration_origin (3), programs (4), pause (2), io_tags (8), tag_changes (7), ts_copy (2), rescale (114).

**74 cases explicitly report an unobserved property**, chiefly allocation counts: the existing harness expects a Mach-O allocation interposer. A separate temporary zero-counter `kc_alloc_snapshot` adapter made that unavailability explicit, so this is functional coverage, not a leak pass or the required macOS gate. Allocation-related assertions report `partial` through the original harness. No leak absence claim is made.

`test_buffers` reaches case 26 and fails building an eleven-input filter graph with FFmpeg filter-not-found, consistent with the documented reduced-profile omission; do not report that as a code regression. Initial manual builds also needed `_DEFAULT_SOURCE` for POSIX clock/usleep declarations, the build-directory macro for patch-manifest checks, and `KC_TESTING` for the seek observation symbol; the corrected builds above passed. No repository source was changed to make tests pass.

Fresh UBSan was used for NF5, not for a full fuzz/ASan/TSan campaign. Static FFmpeg dependencies themselves were not rebuilt with sanitizer instrumentation. Device-specific MediaCodec, VideoToolbox, D3D11, Apple memory/ARC interaction, Wasm/JS bridge execution and release performance were not tested here. The C tests do not prove published artifacts contain these exact freshly compiled helpers.

### Deliberate limitations and unresolved leads, not additional confirmed defects

- BT.2020 constant-luminance and ICtCp are approximated, not fully color-managed: KitePlayer's [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1459-1492](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt#L1459) explicitly warns with `ColorApproximated`; `ColorPolicyTest.kt:112+` asserts this. [kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:456](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt#L456) and upstream [native/kitecodec-c/src/kc_convert.h:48-49](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/native/kitecodec-c/src/kc_convert.h#L48) map CL to NCL deliberately. Record this in capability/fidelity comparison rather than call it an undisclosed regression.
- Swscale contexts are retained per thread by a pthread key and freed by its destructor. Ordinary retention is not a proven leak. A `pthread_setspecific` allocation-failure path and installation of the FFmpeg global logging callback merit fault-injection review; no observed failure is claimed here.
- The C log threshold and callback are separate atomics, and Kotlin/Native's `setNativeLogSink` assigns its Kotlin sink separately. That cannot establish the comment's coherent old-or-new-pair guarantee. No deterministic native misdelivery or TSan report was produced; this remains the pre-existing audit's diagnostic-hardening lead.
- `helpers_format.c`'s seek lookahead bounds payload bytes, not packet count or total allocation footprint. Very many tiny or zero-payload packets can amplify retained object overhead; a representative container/decoder reproduction and measured retained-allocation growth are still needed before reporting an end-to-end resource-budget defect.
- This lane inspected the JNI handle generation table and object-level JVM locks, common async ownership lanes, Native StableRef custom IO setup/unwind, FFmpeg send/receive/backpressure, source recording selection, resampling, patched metadata/chapters and matrix fallback paths. It did not certify every API/ABI entry point, every native allocation failure, every FFmpeg upstream defect, all codec/protocol availability, or legal compliance of every delivered binary.


## Subtitle, libass, and UI lane

All owned-code references below are KitePlayer at `d5bb627e0ed35e0b2e3176689fa737a12bad9f94`. The later main tip adds audit prose only. Findings belong in KitePlayer, including the libass integration finding: KiteFFmpeg supplies the independent libass dependency and should not acquire player-specific retention policy.

<a id="finding-sub-1"></a>

### SUB-1 (P1, high confidence): malformed enhanced LRC lyrics create quadratic scanning and garbage

**Where:** [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/LrcParser.kt:152-170](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/LrcParser.kt#L152), called at `:76`; [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:624-655,676-760](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L624) and `:16262` for external loading and its 16 MiB ceiling.

**Trigger:** `LrcParser.parse("[00:01.00]" + "<".repeat(n) + ">")`. The valid timestamp reaches `withoutWordStamps`. Each `<` searches the entire remaining suffix for `>`, copies that suffix into a new String, splits/parses it as a time, rejects it, and advances only one character. With no final `>` the repeated search alone is still quadratic. The 100,000-cue limit does not bound a single line.

**Observed:** freshly compiled, unmodified production parser files, OpenJDK 21, pinned compiler/stdlib 2.4.21, one allocation-count sample after ten small warmup calls:

| `<` count | Output text characters | Thread-allocated bytes |
|---:|---:|---:|
| 2,000 | 2,001 | 2,285,352 |
| 4,000 | 4,001 | 8,569,408 |
| 8,000 | 8,001 | 33,137,352 |
| 16,000 | 16,001 | 129,902,960 |

The single-thread allocation counter includes the whole parse; these are total allocations, not retained heap. They demonstrate the scaling, not release latency or a device-specific memory-exhaustion threshold. This approximately 16 KB input is far below the external-file budget. A remote subtitle or LRC metadata can consume CPU and create heavy GC pressure; the synchronous scanner has no cancellation check. Moving it to the parse lane under #594 protects the actor but does not bound this work.

**Fix/test direction:** scan only the grammar and maximum length of a potential word timestamp, or retain a forward-only closing-delimiter cursor; never copy an unbounded candidate before validating it. Add character-read/copy-budget tests for many `<` with and without a final `>`, comparable to existing `TextScanCostTest` coverage for SubRip/WebVTT. Preserve literal invalid markup.

**Overlap:** independently found, then corroborated against `CLAUDE_FABLE_5_1_AUDIT.md` F-056. Read #443 and its empty comments, and #594 plus its closing comment. #443 adds LRC; #594 is dispatcher placement, not this complexity defect. A `LRC quadratic` search returned no issue matches. Bounded searches are not a claim of exhaustive issue novelty.

<a id="finding-sub-2"></a>

### SUB-2 (P1, high confidence): TTML timed runs bypass output budgets and amplify work and retained text

**Where:** [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt:130,163-180,319-325](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt#L130); XML input limits at `subtitle/xml/XmlMini.kt:63-90`.

**Trigger:** one paragraph ending at `(n+1)s`, containing `n` spans `<span begin='is'>x</span>` for `i=0..n-1`. All spans share their inherited paragraph end, so every later interval holds a longer prefix. The parser builds every edge interval, filters the entire runs list for each, then concatenates equal-style text using immutable Strings.

**Observed:** actual source, same JVM probe:

| spans | Input characters | Output cues | Retained text characters | Thread-allocated bytes |
|---:|---:|---:|---:|---:|
| 100 | 2,689 | 100 | 5,050 | 3,694,672 |
| 200 | 5,389 | 200 | 20,100 | 17,127,816 |
| 400 | 10,789 | 400 | 80,200 | 89,207,968 |

`n(n+1)/2` retained text characters are unavoidable under the present fully materialized output representation, while repeated equal-style concatenation adds further transient allocation. The paragraph counter counts one for this entire expansion; it does not cap emitted cues, emitted text, or work. XML's 8 MiB and 262,144-element caps permit substantially larger inputs. No timing benchmark or exact crash threshold is claimed.

**Fix/test direction:** enforce independent output-cue, output-text, and work budgets, and use an edge sweep plus bounded mutable text assembly. For dense valid documents consider a timed-run representation rather than duplicating each prefix. Verify cancellation responsiveness as well as output bounds.

**Overlap:** independently reached before reading prior audits; corroborates existing GPT-6 report API-1 and Claude F-057. Read #408 body/comments: its named-style graph recursion was fixed, and is a different phase. #492's timed-span correction creates the present expansion but its tests cover small documents. This is not an assertion that the old #408 fix failed.

<a id="finding-sub-3"></a>

### SUB-3 (P2, high confidence): TTML `end` overrides `dur` instead of taking the earlier bound

**Where:** [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt:131-135](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt#L131) for body/div/p and `:233-238` for spans; region variant `:264-265` uses the same precedence.

**Trigger and observed output:** valid TTML with `<p begin='1s' dur='2s' end='10s'>x</p>` emits `1000000..10000000:x`. A paragraph ending at 10s containing `<span begin='1s' dur='2s' end='10s'>x</span>` emits the same interval. Both should end at 3s. The actual source probe demonstrates this on the JVM.

[TTML1 section 10.2.3](https://www.w3.org/TR/ttml1/#timing-attribute-dur), fetched during the audit, explicitly states that the active duration when both attributes appear is the lesser of `dur` and `end - begin`. The code uses Elvis fallback, so a present `end` prevents reading `dur` entirely. Ancestor clipping cannot repair the overlong interval when the ancestor lasts beyond 3s. Standalone TTML and the HLS/DASH converter share this parser.

**Fix/test direction:** calculate both finite endpoints, then intersect them and the parent interval. Apply one checked-time rule to containers, spans, and regions. Test either endpoint being earlier, zero duration, inherited parent limits, and nested sequential containers. #492's body and all four comments were reviewed; it asks for correct resolved intervals, and this is residual timing work. #628's subframe-rate correction is present and unrelated.

<a id="finding-sub-4"></a>

### SUB-4 (P2, high confidence): TTML paragraphs with a duration implied by their spans are discarded

**Where:** [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt:139-143,163-165](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt#L139).

**Trigger:** a valid document containing `<p><span begin='1s' end='2s'>x</span></p>` under an untimed body/div. The exact-source probe returns no cues. `walk` reaches `p` with `end == null`; `paragraph` immediately returns and never discovers the timed child. In contrast, `walk` derives an implicit end from child elements for ordinary div containers.

[TTML1 section 10.4](https://www.w3.org/TR/ttml1/#timing-time-intervals), fetched during the audit, explicitly includes `p` and non-text-only `span` in container implicit-duration semantics. This example has a finite 2s child endpoint and should produce `x` at [1s,2s). Plain text with an indefinite duration is a related case, but is not needed for this finite reproducer.

**Fix/test direction:** resolve the timing tree, including paragraph and nested-span containers, before flattening it into cues. Add this finite case, a sequential sibling after it, and ancestor clipping. Also preserve the distinct inline-sequence rule: the probe `<p dur='4s' timeContainer='seq'><span dur='1s'>one</span><span dur='2s'>two</span></p>` currently produces `onetwo` at [0,1) and `two` at [1,2), instead of `one` [0,1), `two` [1,3). That sequence gap corroborates prior GPT-6 API-2 and is not a new finding here.

**Overlap:** residual #492 timing contract. Claude F-178 discusses missing indefinite ends; this finite child-implied case narrows the same paragraph early-return defect and provides executed evidence.

<a id="finding-sub-5"></a>

### SUB-5 (P2, high confidence): CEA-708 Delay expires only when more bytes arrive for that service

**Where:** [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/Cea708ServiceReader.kt:58-70,103-111](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/Cea708ServiceReader.kt#L58); [kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/Cea708DecoderFactory.kt:39-50](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/Cea708DecoderFactory.kt#L39); [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:14516-14525](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L14516); `internal/DtvccServices.kt:93-115`.

**Trigger:** define a hidden window containing `LATER` at 0s; at 1s send DLY=10 (one second) followed by DSW for that window; send no further commands for the service while the video continues. The display command belongs at 2s. `Cea708ServiceReader` checks `delayEnd` only inside `read`. The production adapter calls `read` only for a non-null timestamped packet. The video path offers packets only for services carrying nonempty bytes, so ordinary video frames, null padding, or another service's traffic do not advance this decoder. `send(null)` at EOF returns immediately, and `receive()` only drains already-created cues.

**Observed:** exact reader source emitted no cue for the delayed command. A subsequent read at 30s emitted a cue backdated to 2s. No subsequent read means no delayed display at all. This parser experiment plus the source call chain establishes the missing scheduling mechanism; an end-to-end broadcast/device playback was not executed.

**Test gap:** [kiteplayer-subtitles/test/io/github/yuroyami/kiteplayer/subtitle/Cea708ServiceReaderTest.kt:200-208](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-subtitles/test/io/github/yuroyami/kiteplayer/subtitle/Cea708ServiceReaderTest.kt#L200) manually calls `read(ByteArray(0), 1_500_000)` and `read(ByteArray(0), 2_500_000)`. The test verifies a clock-tick contract the production service adapter never supplies. Backdating the eventual cue cannot make the viewer see it during the already-passed interval.

**Fix/test direction:** expose the next decoder deadline to the subtitle scheduler and advance it on media time, or explicitly feed clock ticks for active services even when no data arrives. Handle DelayCancel/Reset before the deadline, pause/speed/seek, and final video EOF. A full player test with two seconds of caption commands followed by caption-free video should assert visibility at 2s without sending synthetic service data.

**Overlap:** reviewed closed #678 and its closing comment; searched both repositories for related delay work. No specific expiry-without-packets issue found by that bounded search. This is a residual integration defect in the implemented 708 capability, not a request to implement 708 afresh.

<a id="finding-sub-6"></a>

### SUB-6 (P2, high confidence): streamed ASS events remain in libass for the whole session

**Where:** [kiteplayer-libass/native/src/kite_ass.h:136-142,161-169,319-326](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-libass/native/src/kite_ass.h#L136); [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:8350-8352,8694-8709,8915-8918](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt#L8350); `internal/SubtitleTypesetting.kt:111-130`.

**Trigger:** play a long or live ASS/SSA track without seeking or switching tracks. Every decoded event is copied into libass via `ass_process_chunk`. The engine prunes its Kotlin cue table after its history window, but it never tells libass to prune the second copy. `ass_flush_events` is only used on explicit clear; track destruction occurs at track/session replacement or close. Whole-script rendering legitimately needs complete history; streamed container events do not.

The independent dependency is pinned to libass 0.17.5 by KiteFFmpeg [build-plugins/native-build/src/BuildAssChainTask.kt:544,566](https://github.com/yuroyami/KiteFFmpeg/blob/f79ba0864c8697f91ef5e3509b46b115afc7dcd7/build-plugins/native-build/src/BuildAssChainTask.kt#L544). In that exact version:

- [ass.h](https://github.com/libass/libass/blob/0.17.5/libass/ass.h) documents `ass_configure_prune` as **disabled by default (no removal of events from memory)**.
- [ass.c:1790](https://github.com/libass/libass/blob/0.17.5/libass/ass.c#L1790) initializes `prune_delay = -1`.
- [ass_render.c:3382](https://github.com/libass/libass/blob/0.17.5/libass/ass_render.c#L3382) scans `track->n_events` at each render, and `:3439-3440` prunes only when configured.

These references were downloaded and inspected during this audit. KitePlayer calls neither `ass_configure_prune` nor `ass_prune_events`. Thus native retained text/events and per-render event-selection work grow with session history, even when only one short cue is active. This is retained session state, not a post-close leak or an assertion that libass violates its API.

**Executed evidence:** compiled the actual unmodified `kite_ass.h` in a small C program against the installed system libass **0.17.3**, using its matching reference headers. Fed 10,000 distinct 500 ms events, rendering beyond expiry every 1,000 events. Output: `libass=0x01703000 retained_expired_events=10000 last_time_ms=9999999`. This is a demonstration with the installed system version, not an execution of the packaged 0.17.5 artifact; the pinned 0.17.5 source establishes the same default and scan behavior. No wall-clock or memory-byte benchmark claimed.

**Fix/test direction:** enable a bounded prune delay for `openTrack`, coordinated with the engine's retained history and seek refeeding; keep it disabled for `openDocument`, which cannot recreate pruned events. Test long streaming runs, backwards seek, duplicate ReadOrder handling, delayed overlays, and whole-document random access. This fix belongs in KitePlayer's driver; KiteFFmpeg need not know a player's history policy. Bounded searches for `libass prune` in both issue trackers returned no matches; neither existing audit report contained this finding.

<a id="finding-sub-7"></a>

### SUB-7 (P2, high confidence): releasing the player's display-awake hold erases a hold the host app already set

**Where:** [kiteplayer-compose-ui/src@android/io/github/yuroyami/kiteplayer/compose/HoldDisplayAwake.android.kt:25-37](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-compose-ui/src@android/io/github/yuroyami/kiteplayer/compose/HoldDisplayAwake.android.kt#L25); `src@ios/.../HoldDisplayAwake.ios.kt:26-36`; [kiteplayer-view/src@ios/io/github/yuroyami/kiteplayer/view/IdleTimerHolds.kt:21-31](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-view/src@ios/io/github/yuroyami/kiteplayer/view/IdleTimerHolds.kt#L21).

**Trigger:** the embedding app sets `UIApplication.sharedApplication.idleTimerDisabled = true` for navigation, a presentation, or another persistent experience. A visible KitePlayer video acquires its first hold and later pauses or detaches. The shared player counter returns to zero and unconditionally writes `false`, losing the preexisting app policy. On Android Compose the same happens when the host sets `LocalView.current.keepScreenOn = true` before the player uses that shared view. The counter correctly coordinates multiple KitePlayer holders, but records only the count, not the initial flag. No app writes during the player's hold are necessary to reproduce the overwrite.

**Impact:** after unrelated media UI pauses/disposes, the embedding app unexpectedly permits auto-lock. This is a deterministic ownership conflict in source; no Android/iOS device execution was available in this lane.

**Fix/test direction:** at minimum preserve the preexisting flag on the first hold and restore it on the last release; centralize the iOS implementation so Compose and native views share the complete state. Document cooperative ownership if other code changes the same Boolean while held, since no private counter can observe arbitrary external ownership. Test initial true and initial false, two players with different release order, and Compose/native combinations. The opt-out parameter is a workaround, not preservation under the default API.

**Overlap:** read #238 and both implementation comments; they cover acquiring/releasing awake state, the player-only shared count, and multiple platforms, without preserving an initial external hold. The prior Claude report notes duplicate iOS implementations and test gaps, but this finding is the concrete preexisting-state loss, not code duplication by itself.

### Lane validation, capabilities, and limits

- Read completely: `TtmlParser`, `LrcParser`, `WebVttParser`, `TimingLine`, `LibassRenderer`, `LibassTypesetter`, JVM/Native `LibassEngine`, `ControlsSource`, `CueIndex`, `DtvccServices`, and the Android/iOS display-awake holders. Read substantive sections of ASS parsing, CEA-708 reader, XML reader, common libass driver and Wasm bridge, subtitle typesetting, seek controls, audioviz state lifecycle, and related tests. Inspected the cited production paths and test assumptions. This is not an every-line audit of the audioviz drawing catalog, every UI renderer, or generated East Asian mapping tables.
- Actual standalone Kotlin probes compile unmodified model/parser source and execute; compiler/stdlib **2.4.21**, matching the repository pin, on OpenJDK 21. An initial 2.4.10 bootstrap run was repeated successfully under the pin; the results below are from the pinned run. Use results for the demonstrated semantics/complexity, not a release-build or device-latency claim. The main agent separately runs the project suite. Native probe uses system libass0.17.3 as disclosed.
- The parser exposes substantial TTML region/style behavior, but sequential/implicit timing and activity constraints need differential conformance work. Full WebVTT regions, vertical writing, numbered-line placement, and karaoke are not equivalent to the existing partial cue model. These are capability boundaries/opportunities unless an explicit supported contract is violated.
- The common C libass driver does share packing/color semantics across JNI, Native and Wasm. Lifecycle confinement is documented and outer close guards exist. No proved double free was found in the inspected paths. The unchecked unpack arithmetic is not counted as a bounds defect because the owned producer enforces bounds before producing that internal buffer.
- Compose's command scope intentionally survives leaving the screen to avoid cancelling a next-item open. This was not mislabeled as a leak merely because it differs from `rememberCoroutineScope`.
- No device accessibility audit, HDR screenshot review, audio-visual synchronization benchmark, physical-device battery measurement, or release-runtime nanosecond performance measurement was performed by this lane. No parity claim against mpv/VLC/Media3 follows from these probes.

### Reproducible Kotlin probe

Save the following as `SubtitleProbe.kt`, compile it together with the production sources listed below, and run `io.github.yuroyami.kiteplayer.subtitle.SubtitleProbeKt`. The temporary artifacts were stored under `/tmp/kite-subtitle-probes/`; their full text is retained here so the audit does not depend on that directory.

```kotlin
package io.github.yuroyami.kiteplayer.subtitle

import java.lang.management.ManagementFactory

fun main() {
    fun tt(body: String) = "<tt xmlns='http://www.w3.org/ns/ttml' xml:lang='en'><body><div>$body</div></body></tt>"
    fun result(name: String, body: String) = println("$name=" + TtmlParser.read(tt(body)).joinToString { "${it.startMicros}..${it.endMicros}:${it.plainText}" })
    result("end-and-dur", "<p begin='1s' dur='2s' end='10s'>x</p>")
    result("span-end-and-dur", "<p end='10s'><span begin='1s' dur='2s' end='10s'>x</span></p>")
    result("implicit-p-duration", "<p><span begin='1s' end='2s'>x</span></p>")
    result("sequential-spans", "<p dur='4s' timeContainer='seq'><span dur='1s'>one</span><span dur='2s'>two</span></p>")
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    bean.isThreadAllocatedMemoryEnabled = true
    val id = Thread.currentThread().threadId()
    repeat(10) { LrcParser.parse("[00:01.00]" + "<".repeat(1000) + ">") }
    for (n in listOf(2000, 4000, 8000, 16000)) {
        val input = "[00:01.00]" + "<".repeat(n) + ">"
        val before = bean.getThreadAllocatedBytes(id)
        val out = LrcParser.parse(input)
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("lrc n=$n outputChars=${out.single().plainText.length} allocatedBytes=$allocated")
    }
    for (n in listOf(100, 200, 400)) {
        val input = tt("<p end='${n + 1}s'>" + (0 until n).joinToString("") { "<span begin='${it}s'>x</span>" } + "</p>")
        val before = bean.getThreadAllocatedBytes(id)
        val out = TtmlParser.read(input)
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("ttml n=$n inputChars=${input.length} cues=${out.size} outputChars=${out.sumOf { it.plainText.length }} allocatedBytes=$allocated")
    }
    val reader = Cea708ServiceReader()
    val defineHidden = byteArrayOf(0x98.toByte(), 0, 0xDA.toByte(), 50, 0x71, 31, 9)
    println("cea-initial=" + reader.read(defineHidden + "LATER".encodeToByteArray(), 0).size)
    println("cea-delay=" + reader.read(byteArrayOf(0x8D.toByte(), 10, 0x89.toByte(), 1), 1_000_000).size)
    println("cea-next-read=" + reader.read(byteArrayOf(0), 30_000_000).joinToString { "${it.startMicros}..${it.endMicros}:${it.plainText}" })
}
```

Exact compile/run command used, from `/workspace/KitePlayer`:

```bash
#!/bin/bash
set -eu
cd /workspace/KitePlayer
AUDIT_KOTLIN=/workspace/.cache/kotlin-bootstrap/kotlin-cli-0.13.0
java -cp "/workspace/.cache/kotlin/.m2.cache/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.jar:$AUDIT_KOTLIN/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "/workspace/.cache/kotlin/.m2.cache/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar" -opt-in=io.github.yuroyami.kiteplayer.KitePlayerInternalApi -d /tmp/kite-subtitle-probes/probes.jar kiteplayer-core/src/io/github/yuroyami/kiteplayer/InternalApi.kt kiteplayer-core/src/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TimingLine.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/LrcParser.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/TtmlParser.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/xml/XmlMini.kt kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/Cea708ServiceReader.kt /tmp/kite-subtitle-probes/SubtitleProbe.kt
java -Xmx256m -cp "/tmp/kite-subtitle-probes/probes.jar:/workspace/.cache/kotlin/.m2.cache/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar" io.github.yuroyami.kiteplayer.subtitle.SubtitleProbeKt
```

Observed stdout:

```text
end-and-dur=1000000..10000000:x
span-end-and-dur=1000000..10000000:x
implicit-p-duration=
sequential-spans=0..1000000:onetwo, 1000000..2000000:two
lrc n=2000 outputChars=2001 allocatedBytes=2285352
lrc n=4000 outputChars=4001 allocatedBytes=8569408
lrc n=8000 outputChars=8001 allocatedBytes=33137352
lrc n=16000 outputChars=16001 allocatedBytes=129902960
ttml n=100 inputChars=2689 cues=100 outputChars=5050 allocatedBytes=3694672
ttml n=200 inputChars=5389 cues=200 outputChars=20100 allocatedBytes=17127816
ttml n=400 inputChars=10789 cues=400 outputChars=80200 allocatedBytes=89207968
cea-initial=0
cea-delay=0
cea-next-read=2000000..9223372036854775807:LATER
```

### Reproducible ASS retention probe

Download `ass.h` and `ass_types.h` from `https://raw.githubusercontent.com/libass/libass/0.17.3/libass/` into `/tmp/kite-subtitle-probes/include/ass/` (these matched the installed test library), save the following as `ass-retention.c`:

```c
#include <stdio.h>
#include "/workspace/KitePlayer/kiteplayer-libass/native/src/kite_ass.h"
int main(void) {
    kite_ass *self = kite_ass_open();
    if (!self) return 2;
    const char *header = "[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,DejaVu Sans,24,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,1,2,10,10,10,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
    if (!kite_ass_open_track(self, header, strlen(header))) return 3;
    kite_ass_set_frame(self, 640, 360, 640, 360, 0, 0, 0, 0, 1.0, 1.0);
    const unsigned char *out = NULL; int size = 0;
    for (int i = 0; i < 10000; ++i) {
        char event[100];
        int n = snprintf(event, sizeof(event), "%d,0,Default,,0,0,0,,x", i);
        kite_ass_add_event(self, event, n, i * 1000LL, 500);
        if (i % 1000 == 999) kite_ass_render(self, i * 1000LL + 999, &out, &size);
    }
    printf("libass=0x%08x retained_expired_events=%d last_time_ms=%d\n", ass_library_version(), self->track->n_events, 9999999);
    kite_ass_close(self);
}
```

```bash
gcc -std=c11 -O2 -I/tmp/kite-subtitle-probes/include /tmp/kite-subtitle-probes/ass-retention.c /usr/lib/x86_64-linux-gnu/libass.so.9 -o /tmp/kite-subtitle-probes/ass-retention
/tmp/kite-subtitle-probes/ass-retention
```

Output: `libass=0x01703000 retained_expired_events=10000 last_time_ms=9999999`.


## API, worker facade, publication and documentation lane

Source baseline: KitePlayer `d5bb627e0ed35e0b2e3176689fa737a12bad9f94`. Read-only audit; no repository edits. The subsequently fetched audit-only commits do not alter the cited production sources. All findings belong in KitePlayer, not KiteFFmpeg.

<a id="finding-a1"></a>

### A1. P2: cancelling a worker `open` cancels only the page's wait and leaves the media opening or open

**Evidence: source-proven; high confidence. No browser execution performed for this finding.**

- [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:504-511](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt#L504) creates a parentless `CompletableDeferred`, retains it in `pending`, posts a command and awaits it. There is no `finally`/cancellation callback to remove it or signal the worker.
- [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:91-100](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt#L91) launches every received command in the independent runtime scope; no id-to-Job ownership is retained.
- [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:36-64](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt#L36) has Init, Call, Close and Send messages, with no command cancellation protocol.
- This differs from the facade's promised same meaning ([kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:40-55](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt#L40)) and the explicit core contract: cancelling `open` leaves Idle ([kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:145-150](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt#L145)). The Java facade's regression test specifically asserts cancellation returns an opening player to Idle; there is no equivalent worker test.

**Trigger:** start `worker.open(slowUrl)` in a coroutine, cancel that coroutine or time it out before the reply, then try to open a replacement item. The cancelled caller returns, but the worker keeps opening the old item. When the old request finishes, its player becomes Paused instead of Idle; another open is refused because replacement requires stop. If play intent had already been posted, the abandoned item can begin playing. Until a reply or worker close/death, the page also retains that pending entry. Repeated cancelled commands against a stuck worker accumulate entries.

**Why the explanation survives counterchecks:** `CompletableDeferred.await()` cancellation does not cancel an independent deferred or a different worker's coroutine. `Failure.of(CancellationException)` handles a cancellation that already occurred in the worker; it cannot transmit a page cancellation in the opposite direction. Startup does have cancellation cleanup, and close has an intentional separate policy, but neither covers normal `call`.

**Fix direction:** add id-scoped cancellation to the page/worker protocol and a worker job registry; clean `pending` when a caller exits. Preserve the core's distinction between cancelling an open and cancelling a close wait. A synchronous worker XHR still cannot process a cancel message while it is blocked, so either document that bounded delay accurately or redesign the transport's interruption path. Merely deleting `pending` would fix retention but not abandoned work or state.

**Regression acceptance:** cancel an open before a delayed server answers; after it answers, verify no Opened/Playing transition for the cancelled item, eventual Idle, and successful subsequent open. Cover cancellation before post, after post, after the worker produced a reply but before the page caller resumed, and during worker failure/close. As a control, cancellation after open has already returned must not retroactively undo it. The test must inspect worker state, not only the page coroutine's CancellationException.

<a id="finding-a2"></a>

### A2. P2: relative linked-file URLs are not resolved before crossing into the worker

**Evidence: source-proven; high confidence. Independently found, then cross-referenced to prior `CLAUDE_FABLE_5_1_AUDIT.md` F-191. This corroborates that finding rather than claiming a new unique defect.**

[kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:491-498](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt#L491) resolves the main URI, external subtitle URIs and external audio URIs with `pageAddress`, but leaves `linkedFiles` and `thumbnails` unchanged. [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:483-486,1408-1413](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt#L483) faithfully transports those unnormalized values. [kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/LinkedFiles.kt:47-53](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/LinkedFiles.kt#L47) preserves each linked URI verbatim, and [kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:25-27](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt#L25) accepts only http/https/blob schemes. A relative linked URI therefore never becomes an HTTP request through that resolver.

**Trigger:** a page at `https://example.test/app/index.html` opens `MediaItem("episode.mkv", linkedFiles = listOf(LinkedFile("opening.mkv")))` through a worker located under `/assets/`. The main item becomes `https://example.test/app/episode.mkv`, while the linked file remains `opening.mkv`; the worker resolver declines it, the FFmpeg local-file fallback cannot find the HTTP resource, and the ordered edition omits the referenced section with `EditionPartLeftOut`.

The thumbnail URL has the same normalization omission, but `thumbnailAt` is explicitly absent from this worker facade, so the immediate end-user reproduction should target linked Matroska segments. This avoids overstating the thumbnail impact.

**Fix direction:** normalize every address-bearing nested source with the same page-base rule before transport; keep the original item identity map intact. Test a non-root document base URI, a worker in another directory, relative linked files and authenticated same-origin headers. Confirm the shared opening actually contributes to the resulting timeline rather than only checking encode/decode equality.

<a id="finding-a3"></a>

### A3. P2: Java cannot enable growing-file playback because its item builder has no growth member

**Evidence: source and published JVM ABI inspection; high confidence.**

[kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaItemBuilder.kt:21-39,201-223](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaItemBuilder.kt#L21) has no storage, setter or constructor forwarding for `MediaItem.growth`; [kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaItem.kt:162-167](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaItem.kt#L162) defines the feature switch and defaults it to null. The current committed JVM ABI ([kiteplayer-core/api/jvm/kiteplayer-core.api:1073-1109](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/api/jvm/kiteplayer-core.api#L1073)) confirms there is no growth member. Java cannot use the ordinary MediaItem constructor/copy as a workaround: their Duration parameter makes constructors synthetic and the copy method JVM-name-mangled. The builder's own KDoc explains why Java needs it. `FileGrowth.ofMillis` already exists, but there is no public Java-callable way to attach the value to an item.

**Trigger/impact:** a Java-only Android or desktop app wants to play a recording still being written using the documented builder. `new MediaItemBuilder(path).growth(FileGrowth.ofMillis(30000)).build()` does not compile, and using the available builder leaves growth null, so playback observes ordinary end-of-file semantics. Kotlin applications can express the same feature directly; Java cannot without adding Kotlin glue or reflection.

**Published check:** downloaded the actual Central artifact `https://repo.maven.apache.org/maven2/io/github/yuroyami/kiteplayer-core-jvm/0.3.0/kiteplayer-core-jvm-0.3.0.jar`, SHA-256 `4e50a57bf5d56f0b3fd98c993b6b1b6c8da14d9dcfc2541cea6b9af8f9e049af`. Parsed its class method tables: MediaItemBuilder has no growth method; FileGrowth has public static `ofMillis(J)`; MediaItem has no public nonsynthetic constructor and its copy method is `copy-5xgIhBA`. The same gap remains in current source. This was class-file inspection, not an end-to-end growing-file run.

**Fix direction:** add `growth(FileGrowth?)` to the builder and forward it from `build`; add one Java compilation/runtime assertion that the built item retains its FileGrowth. A future builder coverage check should compare writable public item fields with intentional documented exclusions; the existing `JavaFacadeCoverageTest` only checks suspending method names, so it cannot detect this gap.

<a id="finding-a4"></a>

### A4. P2: the README's ordinary track-selection sample does not compile against the advertised release

**Evidence: dynamically reproduced compile failure against Maven Central 0.3.0; high confidence.**

[README.md:69-81](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/README.md#L69) tells readers to install 0.3.0 and identifies three particular unreleased areas. The normal controls sample at [README.md:288-292](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/README.md#L288) then calls `TrackInfo.isLanguage("fr")` without marking it unreleased. That method was added under `CHANGELOG.md`'s Unreleased section (around lines 62-65); it is present at current [kiteplayer-core/src/io/github/yuroyami/kiteplayer/Tracks.kt:101](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-core/src/io/github/yuroyami/kiteplayer/Tracks.kt#L101), but absent from the released TrackInfo class.

**Reproduction executed:** downloaded the actual Central JAR and SHA above, then compiled this extraction of the README expression:

```kotlin
import io.github.yuroyami.kiteplayer.TrackInfo
fun french(tracks: List<TrackInfo>): TrackInfo? =
    tracks.firstOrNull { it.isLanguage("fr") }
```

The available Kotlin 2.4.10 JVM compiler, with the released core JAR and its standard library on the classpath, exited 1 with `error: unresolved reference 'isLanguage'`. No other compilation error was reported. This proves the missing API independently of the source changelog. It does not claim to have compiled every README block or the full sample apps.

**Why existing verification misses it:** [verification/central-consumer/src/commonMain/kotlin/consumer/Reach.kt:7-12](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/verification/central-consumer/src/commonMain/kotlin/consumer/Reach.kt#L7) references only the names of KitePlayerPlatform, KiteVideoState and AudioVizState. It does not compile the controls example. `scripts/verify-central-consumer.sh` verifies dependency lines/resolution and backend availability, which is useful but insufficient to establish sample correctness.

**Fix direction:** either show a release-compatible language-selection expression until the next release, or explicitly mark this helper/example as main-only alongside the three existing exceptions. Compile actual extracted README snippets against the advertised release and separately against main. Also review unmarked CEA-708 service wording: the README now describes it, but the changelog places that feature under Unreleased. That is another manifestation of the release/main boundary, not counted as a separate defect here.

### Release and capability assessment

The public Maven metadata fetched during this audit lists latest/release `0.3.0`, lastUpdated `20261009024906`. Current source is intentionally ahead; the publishing template still says 0.3.0 and must be advanced before a future publication. No publishing command was invoked. A current source implementation is not evidence that Central 0.3.0 ships it.

| Area | What source/release evidence supports | Limit of this audit |
| --- | --- | --- |
| Android, Apple and JVM player | Standard module selects core, FFmpeg, output, network, and native view adapters; public README documents installation requirements | No device or release APK/framework playback was run by this lane |
| Browser main-thread player | wasmJs variant; codec/libass side modules; only already-fetched/custom synchronously readable media on the page | Not desktop codec parity; browser format exclusions are explicitly documented |
| Browser worker | Network playback with synchronous range requests, a separate worker module, canvas and page audio; explicit list of absent APIs and default-only PlayerConfig | A1 and A2 demonstrate remaining facade parity gaps; no network continuity benchmark performed |
| Linux/Windows Native | Core/FFmpeg compile targets; no shipped default output or HTTPS, default construction unavailable | Acknowledged product boundary, not a newly discovered defect |
| Java | CompletableFuture facade, listeners, millisecond helpers, item/config builders | Growing-file opt-in missing (A3); Java fixture covers only selected journeys |
| Swift/ObjC | Kotlin/Native klibs, exported KDoc and extensive @Throws annotations; app supplies framework integration | No shipped XCFramework/Swift package was verified; no generated header, Swift compiler or ARC stress test run |
| Matroska linked editions, spherical video, Compose web | Main source and Unreleased changelog; README marks these unreleased | Must not be scored as shipped 0.3.0 capabilities |
| Language matching, CEA-708 services | Main implementation and Unreleased changelog | Language helper is definitely absent from 0.3.0 artifact (A4); CEA-708 release absence inferred from changelog, not decoded from an artifact here |
| DRM | Intentionally unsupported; typed refusal appears in current code/docs | Not parity with platform DRM playback; typed refusal itself does not add decryption capability |
| Recording/transcoding | Player exposes recording; worker excludes it; FFmpeg is an independent capability layer | An upstream encoder/muxer does not establish a complete player editing/export workflow |

### Competitor comparison: verified documentation, not performance measurements

Official pages below were fetched successfully on 2026-11-08, except the mpv manual returned HTTP 403; its official GitHub README was read through the GitHub connector instead. These are documentation-level comparisons, not pinned binary conformance runs. No version-independent performance ranking is justified.

| Reference | Documented useful comparison | Implication for KitePlayer |
| --- | --- | --- |
| [mpv official README](https://github.com/mpv-player/mpv/blob/master/README.md) | Broad file/codec/subtitle support; FFmpeg, libass and libplacebo dependencies; shader-based main video output; configurable build and hardware support | Compare identical media and explicit GPU/hardware/scaling configurations. Sharing FFmpeg/libass does not prove identical build features, defaults, subtitle integration or rendering fidelity |
| [VLC feature list](https://www.videolan.org/vlc/features.html) | Broad container/codec/subtitle matrix, hardware decoding, GPU zero-copy and software fallback, desktop/mobile platforms | Use as a corpus checklist, then distinguish upstream format availability from working transport, decoder, sink and renderer combinations in every KitePlayer target |
| [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats) | HLS/DASH support tables; decoder support depends on the Android platform by default; Widevine/PlayReady/ClearKey constraints listed explicitly | Android DRM playback is a genuine capability difference. Device decoder coverage and startup/seek/power should be compared on the same physical devices and streams, including software fallback |
| [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer.md), [FairPlay Streaming](https://developer.apple.com/streaming/fps/) | Apple local/remote media and HLS, AVQueuePlayer, AVKit/AVPlayerLayer integration; FairPlay protects delivery over HLS | Compare Apple media/session/output integration and protected HLS separately from arbitrary FFmpeg file support. Broad FFmpeg formats and native protected streaming solve different user needs |

A valid parity campaign should pin each competitor binary/build configuration, use the same media bytes, device, output surface and hardware decoder policy, and report successful open/decode/audio/video/subtitle output separately. Startup latency, seek landing/time, dropped frames, A/V drift, peak/steady memory, power and error recovery need release builds and repeated samples with uncertainty. None was measured here, so this audit does not substantiate “faster than mpv/VLC/Media3/AVPlayer.”

### Packaging and CI: inspected safeguards and unresolved evidence

- Main build is Kotlin Toolchain/Amper, not a Gradle library build. The standalone Gradle consumers are the correct place to test consumer metadata and variant resolution; Gradle configuration-cache claims about the main build would be misplaced.
- `library.module-template.yaml` enables explicit API, signing, sources, Maven metadata and native exported KDoc. Maven Local is publish-only (`resolve: false`), which limits accidental sibling-byte leakage.
- [build-plugins/abi-check/src/abiTasks.kt:103-115](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/build-plugins/abi-check/src/abiTasks.kt#L103) compares current built dumps to committed dumps. This catches unreviewed drift but is not an independent previous-release compatibility analysis once dumps are updated. The observed MediaItem constructor/copy descriptor change from the 0.3.0 JAR is explicitly disclosed by the Unreleased linked-edition changelog; it is not reported as an undisclosed defect.
- Publication readiness checks POM metadata and dependencies on unpublished siblings. Release workflow gates upload on CI, builds all native payloads, stages web ZIPs/SBOM, and compares repeated local publication checksums for attestation. This is source inspection only: no Maven Central deployment, signature verification, reproducibility rebuild or license-law compliance opinion was performed.
- CI declares macOS native/JVM, Apple simulator/device compilation, Linux JVM/Android host, Android emulator, Linux Native, Windows Native and wasm/JS jobs. Such declarations are not proof that this exact SHA passed. The test coverage script is a textual module/platform inventory, not execution evidence; alias folders and actual runner outcomes still require log review.
- Central consumer workflow runs only on macOS ([.github/workflows/central-consumer.yml:26-29](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/.github/workflows/central-consumer.yml#L26)), while its comment still says only macOS native payload is published. That comment is stale relative to the README's four desktop payloads; more importantly, a macOS consumer pass cannot certify loading the released Linux/Windows payloads. This is recorded as coverage, not a reproduced packaging defect.
- Android native keep rules and ABI packaging declarations were inspected but no release R8 APK, 16 KiB page-size loader test, or device ABI matrix was executed by this lane. Apple static framework linker/privacy requirements were read but not linked in Xcode. No claim of passing these checks is made.

### Opportunities, kept separate from defects

1. High reach, low/moderate cost: compile the real release-facing README examples, rather than only touching a type from each module. A4 is an immediate concrete payoff.
2. High reach, moderate cost: generate a versioned capability manifest from published artifacts and executable smoke fixtures, separating API available, payload present and end-to-end playback proven.
3. High reach, moderate cost: broaden Java API coverage beyond matching suspend method names to constructibility of public values and option parity. Swift requires its own real framework consumer rather than extrapolating from Kotlin/Native compilation.
4. High cost, product-dependent: DRM and remote playback/casting can broaden reach, but current explicit exclusions should remain honest; these are product choices, not defects merely because competitors offer them.

Coverage included public facade/worker protocol/runtime, builder and relevant public core contracts, JVM ABI tables and actual published class files, README and Unreleased changelog, module templates, key publication/ABI/Dokka build code, workflow/test runner/consumer definitions and public competitor documentation. This lane did not inspect every public declaration or every platform artifact, run the full toolchain build, verify all README links/snippets, compile Swift/ObjC/JS consumers, or measure any performance figure.


## Test reliability and documentation findings

<a id="finding-qa-1"></a>

### QA-1 (P3): a subtitle positioning assertion includes shadow padding in the text anchor

Owner: KitePlayer. Evidence: full JVM output suite and isolated class rerun both fail, plus source proof of an incorrect test oracle.

[kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizerTest.kt:218](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/test@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizerTest.kt#L218) expects `320 - image.bitmap.width / 2`. The default cue has a one-pixel shadow, explicitly described by the same test file at lines 37-42. [kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt:137](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt#L137) allocates `imageWidth = width + shadow.pad + 2 * boxPad`, while lines 165-171 place the text box and let extra shadow pixels hang outside it. [kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CuePlacement.kt:17-18,33-38](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/kiteplayer-output/src/io/github/yuroyami/kiteplayer/output/CuePlacement.kt#L17) explicitly excludes shadow and box padding from the text dimensions used for the anchor.

On this Linux host the result was `expected:<304> but was:<305>`, twice. A text width of 31 becomes a bitmap width of 32; the implementation correctly computes `320 - 31 / 2 = 305`, while the test computes `320 - 32 / 2 = 304`. Font metrics can change width parity, hiding the mistake elsewhere. The vertical assertion immediately below already subtracts shadowPad. This is a test reliability defect, not evidence that the correct text anchor should shift left.

Repair: compute expected placement from the text extent after excluding padding, or render without shadow in this positioning test. Add a separate assertion that turning shadow on preserves the text anchor. Check both odd and even extents. Reproduction: `./kotlin test -p jvm -m kiteplayer-output --include-classes='*DesktopSubtitleRasterizerTest'`; 30 of 31 pass and this assertion fails. Targeted tracker search did not identify a corresponding assertion issue; no novelty guarantee is made.

<a id="finding-doc-1"></a>

### DOC-1 (P3): NOTICE directs native consumers to license-text directories that no longer exist

Owner: KitePlayer. Evidence: exact file existence checks on the audited source.

[NOTICE:33-35,62-64,87-89](https://github.com/yuroyami/KitePlayer/blob/d5bb627e0ed35e0b2e3176689fa737a12bad9f94/NOTICE#L33) tells native consumers to obtain third-party license texts from these paths:

| Documented directory | Actual directory |
| --- | --- |
| `kiteplayer-libass/src/jvmMain/resources/META-INF/licenses/kiteplayer-libass/` | `kiteplayer-libass/resources@jvm/META-INF/licenses/kiteplayer-libass/` |
| `kiteplayer-audioviz/src/jvmMain/resources/META-INF/licenses/kiteplayer-audioviz/` | `kiteplayer-audioviz/resources@jvm/META-INF/licenses/kiteplayer-audioviz/` |
| `kiteplayer-output/src/androidMain/resources/META-INF/licenses/kiteplayer-output/` | `kiteplayer-output/resources@android/META-INF/licenses/kiteplayer-output/` |

All three documented directories are absent; the actual directories contain the expected texts. The README explicitly directs native consumers to NOTICE, so these stale pre-toolchain paths break that setup journey. This finding concerns instructions, not a claim that all artifacts lack notices or that the project violates a license. Update the paths and add a check for local source references in NOTICE. Targeted tracker search returned earlier attribution work such as #248/#250, without establishing this path-migration issue's novelty.

## Remaining qualification and performance plan

No release-build nanosecond measurements, whole-player power measurements, physical-device synchronization runs or comparative player benchmarks were completed. Allocation counts and controlled timeout observations in this report establish their narrow mechanism, not a performance ranking. Native helpers compiled with optimization are still not a release-player benchmark.

| Lane | Required next evidence before claiming broad parity |
| --- | --- |
| Lifecycle and Kotlin memory model | Exact ownership tests at open/seek/stop/close, callback retirement and selected-result delivery; JVM and Native repetitions; Native GC/StableRef/ARC churn; JS/Wasm event-loop cancellation |
| Real-time audio | Physical CoreAudio/AudioTrack/Java Sound runs at 128/256/512-frame buffers, underruns and callback p99/p99.9; GC pressure, device removal and route changes; gapless/crossfade with mixed rates/channels |
| Video and color | Device-specific MediaCodec/VideoToolbox/D3D11 plus fallback; decoded planes through displayed pixels; rotation/crop/stride/chroma siting, HDR metadata/headroom and tone-map golden fixtures |
| Timeline and streams | Seek plus rate/pitch/delay/loop, subtitles plus seek/EOF, HLS key rotation plus conversion/cache/ranges, DASH refresh/redirect/Period transitions, repeated interruptions and recovery |
| API and distribution | Exact published Maven metadata and native payloads per target, release R8 Android consumer, 16 KiB page-size device loading, real Swift/ObjC framework consumer, Java/JS snippets, previous-release source/binary comparison |
| Provenance and licensing | Rebuild each distributed native flavor from its pinned manifest, verify archive/patch hashes and notices, test relinking material for the actual distribution; inventory alone is insufficient |
| Tests and docs | Compile actual release-facing snippets, verify links/local references, negative controls for fixture-dependent tests, read executed CI counts and skips rather than only task declarations |

A useful release-performance campaign should pin the competitor binary/version/build flags and the exact codec/output path. Use identical media bytes, device, OS, display, audio buffer, hardware-decoder policy and renderer quality. Measure cold and warm open-to-first-audio/frame, seek-to-correct-frame, steady A/V drift, dropped frames, underruns, CPU/GPU time, peak/steady memory and energy. Include audio-only, 1080p/4K SDR/HDR, ASS-heavy material, live HLS/DASH, throttled/reconnecting transport, and repeated create/close cycles. Do not equate an upstream FFmpeg decoder name with working player output.

For microbenchmarks, separate dispatch/boundary/boxing costs from the complete pipeline. Use a proper benchmark harness, consume results to prevent elimination, warm the JIT until stable, repeat across fresh processes, and report distributions with confidence intervals. Inspect generated bytecode/native code only to explain measured costs. For end-to-end runs, use at least five fresh-process repetitions and enough events to estimate the stated percentile; report sample count, warmup, uncertainty and clock source. At 48 kHz, 256 audio frames allow about 5.33 ms per callback, so rare tail delays matter more than an isolated function-call average.

Prioritize user-reachable ownership, cancellation and incorrect-output findings before micro-optimizations. Then close the combination gaps, formalize versioned platform/capability evidence and compile real consumer examples. Optional features such as DRM, casting, additional Linux hardware paths and display interpolation remain product decisions with reach/cost tradeoffs, not defects simply because another library offers them.

## Additional provenance observation

Runtime iteration of the provisioned Linux x64 static FFmpeg tree reported version 9.0.2, LGPL-2.1-or-later, 507 decoders, 359 demuxers, 18 muxers and 14 encoders. Input protocols were `data, fd, file, http, pipe, rtmp, rtp, tcp, udp`; HTTPS was absent. The configuration intentionally disables default encoders/muxers/filters/protocols and reenables a selected list. This is evidence for that provisioned tree only, not every Central payload or every platform. In KitePlayer, the configured network resolver supplies transport that plain FFmpeg does not. Broad upstream format availability, available platform payloads and end-to-end player support must stay separate in the capability matrix.

## Review limits retained

The completed lane reports were reviewed for duplicate claims and evidence strength. Before commit, main advanced to `16069bb1` with one additional audit file only; the audited production code is unchanged. Follow-up upstream JVM probes for NF3/NF4 were not completed; both remain source-supported. A later lead concerning TTML decoration-style expansion was not completed or promoted into the numbered findings. Platform performance, GC behavior and legality of every delivered artifact remain unqualified. Several reviewer sessions were interrupted by automatic safeguards; their completed evidence is retained, and no interrupted follow-up is reported as executed.



## Complete platform probe sources

These temporary drivers are embedded so the findings remain reviewable without this workspace. Compile the Kotlin driver in the stated package together with the unmodified sink sources for PLATFORM-1/2, or compile HeadProbe alone for PLATFORM-4. The driver supplies the line/clock scheduling seam; no device is modeled as tested.

Create `/tmp/kite-platform-probes` and save the blocks below under their named files. The following compilation recipe uses the pinned compiler and dependencies after the main project build; adjust paths for another checkout. The C compile command is in PLATFORM-5.

```sh
AUDIT_M2=/workspace/.cache/kotlin/.m2.cache
AUDIT_BOOT=/workspace/.cache/kotlin-bootstrap/kotlin-cli-0.13.0
AUDIT_COMPILER="$AUDIT_M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/kotlin-compiler-embeddable-2.4.21.jar"
AUDIT_CP="$AUDIT_M2/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.jar:$AUDIT_M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar:/workspace/KitePlayer/build/tasks/_kiteplayer-core_jarJvm/kiteplayer-core-jvm.jar"
AUDIT_SINK=/workspace/KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output
java -cp "$AUDIT_COMPILER:$AUDIT_BOOT/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$AUDIT_CP" -d /tmp/kite-platform-probes/desktop.jar "$AUDIT_SINK/DesktopAudioSink.kt" "$AUDIT_SINK/SourceDataLineDriver.kt" "$AUDIT_SINK/DesktopMonotonicClock.kt" /tmp/kite-platform-probes/DesktopSinkProbe.kt
java -cp "/tmp/kite-platform-probes/desktop.jar:$AUDIT_CP" io.github.yuroyami.kiteplayer.output.DesktopSinkProbeKt
java -cp "$AUDIT_COMPILER:$AUDIT_BOOT/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$AUDIT_CP" -d /tmp/kite-platform-probes/head.jar /tmp/kite-platform-probes/HeadProbe.kt
java -cp "/tmp/kite-platform-probes/head.jar:$AUDIT_CP" HeadProbeKt
```

### DesktopSinkProbe.kt

```kotlin
package io.github.yuroyami.kiteplayer.output
import io.github.yuroyami.kiteplayer.spi.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private class ProbeDriver : SourceDataLineDriver {
    override val bufferSizeBytes = 2048
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun open() {}
    override fun onWriterThreadStart() {}
    override fun start() {}
    override fun stop() {}
    override fun drain() { entered.countDown(); release.await() }
    override fun flush() {}
    override fun write(source: ByteArray, offsetBytes: Int, sizeBytes: Int) = sizeBytes
    override fun longFramePosition() = 0L
    override fun available() = 2048
    override fun close() {}
}
fun main() = runBlocking {
    val fmt = AudioFormat(48000, 2, SampleFormat.F32)
    val driver = ProbeDriver()
    val sink = DesktopAudioSink(SourceDataLineDriverFactory { driver }, DesktopMonotonicClock)
    sink.open(fmt) { _, _, _ -> 0 }
    val releaseThread = Thread {
        check(driver.entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(500)
        driver.release.countDown()
    }.apply { start() }
    val started = System.nanoTime()
    val completed = withTimeoutOrNull(100) { sink.drain(); true }
    val elapsed = (System.nanoTime() - started) / 1_000_000
    println("drain deadline=100ms elapsed=${elapsed}ms completed=$completed")
    check(elapsed >= 450) { "blocking drain unexpectedly honored timeout" }
    sink.close()
    releaseThread.join()

    val crash = AtomicReference<Throwable?>()
    val crashed = CountDownLatch(1)
    val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> crash.set(failure); crashed.countDown() }
    try {
        val nanDriver = ProbeDriver()
        val nanSink = DesktopAudioSink(SourceDataLineDriverFactory { nanDriver }, DesktopMonotonicClock)
        val samples = FloatArray(1024) { Float.NaN }
        nanSink.open(fmt) { destination, frames, _ ->
            destination.writeInterleaved(samples, 0, 0, frames)
            frames
        }
        nanSink.start()
        check(crashed.await(2, TimeUnit.SECONDS))
        println("NaN writer crash=${crash.get()}")
        val field = nanSink.javaClass.getDeclaredField("writerRun").apply { isAccessible = true }
        println("NaN writerRun after death=${field.getBoolean(nanSink)}")
        check(field.getBoolean(nanSink))
        nanSink.close()
    } finally { Thread.setDefaultUncaughtExceptionHandler(oldHandler) }
}
```

### HeadProbe.kt

```kotlin
import java.util.concurrent.CountDownLatch
private fun interface AudioTrackDriver { fun playbackHeadPosition(): Int }
private class HeadProbe {
    private val headLock = Any()
    private var headLastRaw = 0xFFFF_FFFEL
    private var headWrapBase = 0L
    fun extendedHead(d: AudioTrackDriver): Long {
        val raw = d.playbackHeadPosition().toLong() and 0xFFFF_FFFFL
        synchronized(headLock) {
            if (raw < headLastRaw && headLastRaw - raw > 0x8000_0000L) {
                headWrapBase += 1L shl 32
            }
            headLastRaw = raw
            return headWrapBase + raw
        }
    }

}
fun main() {
    val probe = HeadProbe()
    val sampled = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stale = Thread {
        val answer = probe.extendedHead { sampled.countDown(); release.await(); -1 }
        println("stale pre-wrap read after post-wrap commit=$answer")
    }.apply { start() }
    sampled.await()
    println("first post-wrap read=" + probe.extendedHead { 0 })
    release.countDown()
    stale.join()
    val fresh = probe.extendedHead { 1 }
    println("next post-wrap read=$fresh expected=4294967297")
    check(fresh == 8589934593L)
}
```

### rescale.c

```c
#include <stdio.h>
#include "kite_rt.h"
int main(void) {
    int64_t f = INT64_C(442721857769035200);
    printf("frames=%lld rate=48000 result=%lld\n", (long long)f,
        (long long)kprt_frames_to_micros(f, 48000));
}
```
