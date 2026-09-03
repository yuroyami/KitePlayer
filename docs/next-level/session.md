# Program S: session, queue, platform

Read `README.md` in this directory first. Facts verified against the tree: the queue is
`PlayerSnapshot.queue` plus `queueIndex` (`PlayerState.kt:74-76`) and the only mutators are
internal (`PlaybackCore.kt:182-183`, `1215-1221`); there is no add, remove, move, clear or shuffle.
The queue advances only from the `Ended` status (`PlaybackCore.kt:3906-3918`) after the audio
device is paused (`3843-3845`), so "gapless" in the release notes is not what the tree does.
There are no markers, no sleep timer, no state export, no media session on any platform, no audio
focus or interruption handling, no lifecycle handling in either view, and no accessibility
semantics anywhere. `KiteLog` is the only process-wide mutable singleton; the Apple audio
session lease is reference-counted by design.

`core` means `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/`; `mobile` means
`kiteplayer-mobile/src/`.

---

### S1 Queue editing. LANDED 2026-09-03

Three things the plan did not say, all about the cursor rather than the list:

- **A plain `open` is a queue of one, and the first edit writes it down.** The plan said this in
  one clause and it turned out to be the difference between a usable API and a useless one:
  refusing an edit there would make an application call `openQueue` with the item it is already
  playing just to add a second one, which reopens what is on screen for nothing. Materialising a
  queue of one changes no behaviour anywhere else, because every other queue rule already treats a
  size of one and a size of zero the same way.
- **Removing the playing LAST item has to leave the queue alone.** The plan said only "with
  nothing left to play it stops". Emptying the queue as well would throw away items the person
  never removed. The cursor rests on the last item still there, which also happens to be the
  same expression that puts it back to -1 when the queue really is empty.
- **A move needs the cursor computed, not cased.** The four cases in the first draft were right
  by accident. The rule underneath is two steps: the removal pulls the playing item back when it
  sat behind the moved one, then the insert pushes it along when the moved one lands at or in
  front of it. Writing those two steps is shorter than the cases and provable line by line.

The falsification pass was the point here. Ten mutations, one per rule the cursor follows, each
turning a different named test red. Two of the rules had exactly one test covering them, which is
how a rule quietly stops being enforced.

### S1, as planned. Size M, Tier 1

**Why.** `openQueue` is the only way to shape a queue (`KitePlayer.kt:423`). A music app adds,
removes and reorders while playing.

**Depends on:** nothing.

**Files.** Modify `core/KitePlayer.kt`, `core/internal/PlaybackCore.kt` (a `CoreCommand.QueueEdit`
family, dispatched beside `QueueNext` at line 1224). Tests `core/commonTest/.../QueueEditTest.kt`
on the harness.

**Contract.**

```kotlin
/** Inserts [items] at [index], or at the end when null. The playing item keeps playing; [PlayerSnapshot.queueIndex] moves if the insert is before it. */
public suspend fun addToQueue(items: List<MediaItem>, index: Int? = null)
public suspend fun addToQueue(item: MediaItem, index: Int? = null): Unit = addToQueue(listOf(item), index)

/** Removes the item at [index]. Removing the playing item opens the next one, wrapping per [LoopMode]; with nothing left to play it stops. */
public suspend fun removeFromQueue(index: Int)

/** Moves the item at [from] to sit at [to]. The playing item stays the playing item wherever it lands. */
public suspend fun moveInQueue(from: Int, to: Int)

/** Removes every item except the playing one. */
public suspend fun clearQueue()
```

All four throw `IllegalArgumentException` for an index outside the queue and `IllegalStateException`
when no queue is open (a plain `open` is a queue of one and is allowed). Every edit is one actor
command; the snapshot publishes the new `queue` and `queueIndex` atomically with it.

**Tests.** Harness, three scripted items open at index 1: `addToQueue(x, 0)` gives `queueIndex == 2`
and the same media playing; `moveInQueue(2, 0)` gives `queueIndex == 0`; `removeFromQueue(0)` while
playing index 0 opens the former index 1 (the backend's open count rises by one, `Opened` fires);
removing the last remaining item stops (`Idle`); `clearQueue` leaves one item at index 0; each
bad index throws. `next()` after an insert follows the new order.

**Commit.** `core: the queue can be edited while it plays`

---

### S2 Shuffle. LANDED 2026-09-03

The design held. What did not hold was the testing, twice, and both are worth keeping:

- **A seeded shuffle can come back unshuffled.** Four items land in list order once in every
  twenty-four seeds, and the first seed picked here did exactly that. Three cases that walk a
  shuffled order were therefore walking the list order and proving nothing: a mutation that made
  the natural advance ignore the order entirely passed all of them. Every such case now asserts
  the order is actually shuffled before it walks it.
- **"Somewhere after the current item" is not the same as "after the current item".** The first
  placement test only checked that an added item did not jump ahead of the one playing. Appending
  every add to the very end satisfies that perfectly and is still wrong. It now adds four items at
  once and checks that at least one of the ones already queued still plays after one of them.

The advance case was also read once at the end of the run, which meant guessing how many items had
gone by; it guessed wrong and failed against working code. It samples the whole path now.

### S2, as planned. Size S, Tier 1

**Why.** The parity map says shuffle "does not exist today". It does not.

**Depends on:** S1 (the same command family and snapshot fields).

**Files.** Modify `core/KitePlayer.kt`, `core/PlayerState.kt`, `core/internal/PlaybackCore.kt`
(`next`, `previous`, `handleQueueAdvance`, `jumpQueue` walk the order). Tests `ShuffleTest.kt`.

**Contract.**

```kotlin
/** Plays the queue in a shuffled order. The playing item becomes first in it. [seed] makes the order reproducible. */
public fun setShuffle(enabled: Boolean, seed: Long? = null)

// PlayerSnapshot
val shuffle: Boolean = false,
/** Indices into [queue] in play order. Identity when shuffle is off. */
val queueOrder: List<Int> = emptyList(),
```

`next`, `previous`, the advance at `Ended` and `LoopMode.All`'s wrap all walk `queueOrder`. Edits
from S1 keep the order valid: an inserted item is placed at a random position after the current
one; a removed item leaves the order. Turning shuffle off restores index order with the playing
item staying the playing item.

**Tests.** Five items, seed 7: `queueOrder` starts with the current index and is a permutation;
`next()` five times visits every item once and wraps under `LoopMode.All`; seed 7 twice gives the
same order; off again gives `[0,1,2,3,4]`.

**Commit.** `core: shuffle, as an order over the queue rather than a reorder of it`

---

### S3 Preload the next item, and make the handoff gapless. Size L, NEEDS-DESIGN, Tier 2

**Why.** At the end of an item the engine emits `Ended`, pauses the audio device, then opens the
next item from scratch (`PlaybackCore.kt:3843-3850`, `3906-3918`). That is a silent gap of an
open plus a first frame, on every track change of an album. The release notes call it gapless.

**Depends on:** nothing. Design commit first (rule 13), then the code. Read `handleQueueAdvance`,
`runOpen`, `AudioPlayback`'s open, stop and drain, and `GOTCHAS.md` section 6 (epochs, the ring
freed only after the feeder is joined) before writing the design.

**Files.** Modify `core/PlayerConfig.kt` (`QueueConfig`), `core/internal/PlaybackCore.kt`,
`core/AudioPlayback.kt` (a second source can take over the ring), `core/PlayerState.kt`
(`preloadedIndex`). Tests `GaplessHandoffTest.kt` on the harness with the scripted sink
recording every call it receives.

**Contract.**

```kotlin
public data class QueueConfig(
    /** Open and prime the next item this long before the current one ends. Zero disables preloading. */
    val preloadNext: Duration = 5.seconds,
    /** Hand the audio device from one item to the next without stopping it. Needs [preloadNext]. */
    val gapless: Boolean = true,
)
// PlayerConfig gains: val queue: QueueConfig = QueueConfig()
// PlayerSnapshot gains: val preloadedIndex: Int? = null
```

**The design the commit must state.**
1. Preload: when `position >= duration - preloadNext`, the next index is known and no pending
   session exists, the engine asks the backend to `open` the next item on a separate worker,
   builds its decoders, decodes the first video frame and the first audio buffers into that
   session's own queues, and holds it as `pending`. A pending session that fails is dropped with a
   typed warning `PreloadFailed(index, error)` and the old path runs at `Ended`.
2. Handoff at `Ended`, when `pending` matches the next index: the current session's video and
   subtitle lanes retire as today; the audio lane does NOT call `pause` or `stop` on the sink; the
   pending session's audio feeder becomes the ring's writer at the exact sample where the old
   feeder stopped (the ring is one object owned by the player for the session pair; the feeder is
   what changes). The media clock re-anchors at the boundary: the new item's position is zero at
   the ring position where its first sample was written. `Ended` fires for the old item, `Opened`
   for the new, in that order, and `playRequested` stays true.
3. Epoch: the pending session's components are aligned to the epoch the world is at when the
   swap happens (the second-open lesson in `GOTCHAS.md`), not the one at preload time.
4. Anything that cannot hold (unseekable current item, a different sample format the ring cannot
   take without renegotiation, a pending open still running at `Ended`) falls back to today's
   path, warns typed, and the test for each fallback is named below.

**Tests.** Harness with two scripted items of 3 s each and a scripted sink that logs every
`open`, `start`, `stop`, `pause`, `drain` and every submitted buffer: under the default config the
sink sees exactly one `open` and one `start`, no `stop` or `pause` between items, the submitted
sample stream has no discontinuity (a counter of frames submitted equals the sum of both items'
frames), events are `Ended` then `Opened`, and `progress.value.position` after the swap starts
within one buffer of zero. `gapless = false`: today's behaviour, pinned. Preload failure: the
scripted backend refuses the second open once; the old path runs and `PreloadFailed` warns. Real
media on `macosArm64Test`: `audio-flac.flac` twice in a queue, `audioUnderruns == 0` across the
boundary and no `stop` on the CoreAudio sink (instrument the sink's event flow).

**Commit lines.** `design: the next queue item preloads, and the audio device is handed over, not stopped`
then `core: gapless queue handoff, the ring keeps running across items`. Q10 fixes the release
wording; do not touch it here.

---

### S4 Markers, and chapter navigation. Size S, Tier 1

**Why.** Synkplay-style sync, ad cue points, lyric lines and "skip intro" all want "tell me when
we pass this position". Chapters exist (`chapterAt`, `seekToChapter`) but there is no next or
previous.

**Depends on:** nothing.

**Files.** Modify `core/KitePlayer.kt`, `core/PlayerEvent.kt`, `core/PlayerState.kt`,
`core/internal/PlaybackCore.kt` (the progress tick). Tests `MarkersTest.kt`, `ChapterNavigationTest.kt`.

**Contract.**

```kotlin
public data class Marker(val position: Duration, val id: String)

/** Positions to announce. Sorted by the engine. Replaced wholesale; empty clears. */
public fun setMarkers(markers: List<Marker>)
// PlayerEvent
/** Playback crossed [marker] while advancing. A seek that lands past a marker does not fire it. */
public data class MarkerReached(val marker: Marker) : PlayerEvent
// PlayerSnapshot
val markers: List<Marker> = emptyList(),

/** Seeks to the start of the chapter after the current one. Does nothing at the last chapter. */
public suspend fun nextChapter()
/** Seeks to the start of the current chapter, or of the previous one when less than 3 seconds in. */
public suspend fun previousChapter()
```

Firing rule: on every progress tick while `Playing`, markers with `lastTickPosition < position <= now`
fire in order; a seek sets `lastTickPosition` to the landing without firing. Each marker fires at
most once per pass; a loop or a backward seek re-arms the ones behind the new position.

**Tests.** Harness: markers at 1 s and 2 s, play 3 s: two events in order; seek to 5 s then play:
none; seek back to 0 and play: both again. `previousChapter` at 2 s into chapter 2 goes to
chapter 2's start; at 1 s in, to chapter 1's start; `nextChapter` at the last chapter is a
no-op and does not throw.

**Commit.** `core: markers that fire on crossing, and next and previous chapter`

---

### S5 State export and restore. Size M, Tier 1

**Why.** Every app rebuilds "resume where I was" by hand: queue, index, position, speed, volume,
track choices. The engine knows all of it and can hand it out as one value.

**Depends on:** S2 (`shuffle` field). Without it, omit that field and add it there.

**Files.** Create `core/PlayerMemento.kt`. Modify `core/KitePlayer.kt`. Tests `MementoTest.kt`.

**Contract.**

```kotlin
/**
 * Everything needed to come back to where playback was. A value the app stores; no serialisation
 * is imposed. [MediaItem.io] factories cannot be stored by anyone, so [asProperties] drops them and
 * an item that needs one must be rebuilt by the app before [KitePlayer.restore].
 */
public data class PlayerMemento(
    val queue: List<MediaItem>,
    val queueIndex: Int,
    val position: Duration,
    val speed: Double,
    val preservePitch: Boolean,
    val volume: Float,
    val muted: Boolean,
    val loop: LoopMode,
    val shuffle: Boolean,
    val subtitleDelay: Duration,
    val audioDelay: Duration,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesOff: Boolean,
) {
    /** Flat string pairs, version-stamped, for apps that store key-value text. URIs only for the queue. */
    public fun asProperties(): Map<String, String>
    public companion object {
        public fun fromProperties(properties: Map<String, String>): PlayerMemento
        public const val FORMAT_VERSION: Int = 1
    }
}

public fun memento(): PlayerMemento
/** [openQueue] at the memento's index, seeks to its position, applies every setting, then picks tracks by language. Ends paused. */
public suspend fun restore(memento: PlayerMemento)
```

`asProperties` keys: `version`, `queue.size`, `queue.N.uri`, `queue.N.formatHint`, `queue.N.startPosition`,
and one key per scalar; headers and raw options are stored too (`queue.N.header.Name`,
`queue.N.option.key`) because they are strings; `io`, `externalSubtitles` and `videoFilter` are not
and the KDoc says so.

**Tests.** Round trip: `memento()` after a scripted queue at index 1, 12 s in, speed 1.5, volume
0.4, muted, `LoopMode.All`, audio track "jpn": `restore` on a fresh player of the same config
reaches the same snapshot fields (position within one frame, `selectedAudio`'s language "jpn").
`asProperties` then `fromProperties` equals the original for a memento whose items carry no
factories. A properties map with `version=2` throws `IllegalArgumentException`.

**Commit.** `core: a memento of where playback was, and a restore that takes it back there`

---

### S6 Interruptions, audio focus, and headphones coming out. Size M, Tier 2 (device proof)

**Why.** A call comes in, another app plays, the headphones unplug: today nothing pauses, ducks or
resumes. The sinks only report `DeviceLost` (`spi/AudioSink.kt:186`). Android has no
`requestAudioFocus` anywhere, iOS observes no interruption notification.

**Depends on:** nothing.

**Files.** Create `core/audio/InterruptionPolicy.kt` (public), `core/internal/InterruptionMachine.kt`
(pure), `mobile/androidMain/.../AndroidInterruptionGuard.kt`, `mobile/iosMain/.../AppleInterruptionGuard.kt`,
`mobile/commonMain/.../InterruptionGuard.kt` (expect, plus `attachInterruptionHandling`). Modify
`core/PlayerConfig.kt` (`AudioConfig.interruptions`). Tests `InterruptionMachineTest.kt` (core),
host tests for the Android guard's focus request mapping.

**Contract.**

```kotlin
public data class InterruptionPolicy(
    /** Pause on a permanent loss of the audio route or focus. */
    val pauseOnLoss: Boolean = true,
    /** On a transient loss that allows ducking, lower the volume to [duckVolume] instead of pausing. */
    val duckOnTransient: Boolean = true,
    val duckVolume: Float = 0.2f,
    /** Resume after a transient loss ends, if this player was the one that paused. */
    val resumeAfterTransient: Boolean = true,
    /** Pause when the route becomes noisy: wired headphones unplugged, Bluetooth gone. */
    val pauseWhenBecomingNoisy: Boolean = true,
)
// AudioConfig gains: val interruptions: InterruptionPolicy = InterruptionPolicy()

internal enum class InterruptionEvent { Loss, LossTransient, LossTransientCanDuck, Gain, BecomingNoisy }
internal sealed interface InterruptionAction { data object Pause; data object Resume; data class Duck(val volume: Float); data object Unduck; data object None }
internal class InterruptionMachine(private val policy: InterruptionPolicy) {
    fun on(event: InterruptionEvent, playing: Boolean): InterruptionAction
}

// mobile, common
/** Wires the platform's focus, interruption and route signals to [player] under its policy. Returns the handle to close. */
public fun KitePlayerPlatform.attachInterruptionHandling(player: KitePlayer, context: PlatformContext): AutoCloseable
```

`PlatformContext` is `android.content.Context` on Android and `Unit` on Apple (an `expect class`
already exists in `kiteplayer-mobile` if `KitePlayerPlatform` takes one; reuse it, else add it).
Android: request focus (`AudioFocusRequest`, `AUDIOFOCUS_GAIN`, `AudioAttributes` usage media)
when the status becomes `Playing`, abandon on `Idle` and on close; the listener feeds the machine;
a `BroadcastReceiver` on `ACTION_AUDIO_BECOMING_NOISY` feeds `BecomingNoisy`. iOS:
`AVAudioSessionInterruptionNotification` (`Began` is `Loss` or `LossTransient` by the
`AVAudioSessionInterruptionTypeKey`; `Ended` with `ShouldResume` is `Gain`) and
`AVAudioSessionRouteChangeNotification` with reason `OldDeviceUnavailable` is `BecomingNoisy`.
The machine remembers `pausedByUs` and `duckedByUs` so a user pause is never resumed by a focus gain.

**Tests.** Machine: every event under every policy combination, including `Gain` after a user
pause producing `None`. Android host: the guard with a fake `AudioManager` seam requests focus
on `Playing` exactly once and abandons on `Idle`. Device: DEVICE-DAY step 24, an incoming call and
a headphone unplug on both platforms.

**Commit.** `mobile: interruptions, audio focus and noisy routes are handled under one policy`

---

### S7 Media session and now playing. Size M, Tier 2 (device proof)

**Why.** Lock screen controls, headset buttons, Android Auto and CarPlay entry, the iOS Now
Playing card: all absent. Every consumer rebuilds the mapping from a snapshot to
`PlaybackState` and `MPNowPlayingInfo`, and gets it slightly wrong.

**Depends on:** S1 (skip commands assume a queue API), S6 (focus).
**[owner]** decision: lives in `kiteplayer-mobile` (recommended: it already depends on everything
and is the "start here" module) or in a new `kiteplayer-session` module.

**Files.** Create `mobile/commonMain/.../MediaSessionState.kt` (pure mapping),
`mobile/androidMain/.../KitePlayerMediaSession.android.kt`, `mobile/iosMain/.../KitePlayerMediaSession.ios.kt`,
`mobile/commonMain/.../KitePlayerMediaSession.kt` (expect). Tests: host tests for the mapping.

**Contract.**

```kotlin
/** What the platform's session should show and accept, derived from one snapshot. Pure. */
public data class MediaSessionState(
    val playing: Boolean, val position: Duration, val duration: Duration?, val speed: Double,
    val title: String?, val artist: String?, val album: String?, val hasNext: Boolean, val hasPrevious: Boolean,
)
public fun PlayerSnapshot.toMediaSessionState(progress: Progress): MediaSessionState

/** Mirrors [player] into the platform session and routes its commands back. Close it with the player. */
public expect class KitePlayerMediaSession(player: KitePlayer, context: PlatformContext) : AutoCloseable {
    /** Android: the token an app's notification needs. Apple: null. */
    public val platformToken: Any?
    /** Optional artwork the app supplies; the session shows it. */
    public fun setArtwork(rgba: ByteArray?, width: Int, height: Int)
}
```

Android: `android.media.session.MediaSession(context, "KitePlayer")`, `setPlaybackState` from
the state (actions PLAY, PAUSE, SEEK_TO, SKIP_TO_NEXT, SKIP_TO_PREVIOUS, SET_PLAYBACK_SPEED on
API 29), `setMetadata` (`METADATA_KEY_TITLE`, `ARTIST`, `ALBUM`, `DURATION`, `ART` from the
artwork), `setCallback` routing to the player, `isActive = true`; `release()` on close. The
foreground service and the notification stay the app's, and the KDoc says so with the two lines
of `MediaStyle` an app needs. iOS: `MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo` with
title, artist, album, duration, elapsed, rate; `MPRemoteCommandCenter` targets for play, pause,
toggle, next, previous, change position, skip 15 s both ways.

**Tests.** Mapping: a paused snapshot at 12 s of 60 s with two-item queue at index 0 gives
`playing = false`, `hasNext = true`, `hasPrevious = false`. Android host: a fake session seam
records `setPlaybackState` calls; play then pause produce two states with the right flags.
Device: DEVICE-DAY step 25, lock-screen controls on both platforms.

**Commit.** `mobile: a media session that mirrors the player and routes the platform's commands back`

---

### S8 Background policy. Size M, Tier 2 (device proof)

**Why.** Neither view knows when the app leaves the foreground (`KitePlayerView.kt` has no
lifecycle override, `KitePlayerUIView.kt:185-193` reacts only to the window). Video keeps
decoding for a screen nobody sees.

**Depends on:** A5 (parking video in place), S6 (the guard pattern).

**Files.** Create `core/BackgroundPolicy.kt`, `mobile/androidMain/.../AndroidBackgroundGuard.kt`,
`mobile/iosMain/.../AppleBackgroundGuard.kt`, `mobile/commonMain/.../attachBackgroundHandling`.
Modify `core/PlayerConfig.kt`.

**Contract.**

```kotlin
public enum class BackgroundPolicy {
    /** Keep playing; park video decoding while backgrounded and resume it on return. */
    ContinueAudio,
    /** Pause on background, resume on foreground only if this policy paused it. */
    PauseAll,
    /** Do nothing. */
    Ignore,
}
// PlayerConfig gains: val background: BackgroundPolicy = BackgroundPolicy.ContinueAudio
public fun KitePlayerPlatform.attachBackgroundHandling(player: KitePlayer, context: PlatformContext): AutoCloseable
```

Android: `Application.registerActivityLifecycleCallbacks` counting started activities; zero
means background. No `androidx.lifecycle` dependency. The KDoc says that continuing audio in the
background on Android also needs the app's foreground service. iOS:
`UIApplicationDidEnterBackgroundNotification` and `UIApplicationWillEnterForegroundNotification`.

**Tests.** Host: a fake lifecycle seam driving background then foreground under each policy
produces `setVideoEnabled(false)` then `(true)`, or `pause()` then `play()`, or nothing. Device:
DEVICE-DAY step 26.

**Commit.** `mobile: a background policy, audio continues with video parked`

---

### S9 Two players at once. Size S, Tier 2

**Why.** A preview thumbnail beside the main picture, a picture-in-picture second stream, a
crossfade someday: all need two players in one process. Nothing in the engine is a singleton
except `KiteLog`, and the Apple session lease is reference-counted, so it should work. Nobody
has proven it.

**Depends on:** nothing.

**Files.** Create `kiteplayer-mobile/src/jvmTest/.../TwoPlayersTest.kt` (mirror
`DesktopPlaybackTest.kt`), `kiteplayer-output/src/macosArm64Test/.../TwoCoreAudioSinksTest.kt`.

**Tests.** Two `KitePlayerPlatform.createOrNull()` players open `sync1080p30.mp4` and
`truevfr720.mp4`, both play, both positions pass 1 s within 20 s, closing one does not stop the
other (the second's position keeps advancing for another second), both close clean. macOS: two
CoreAudio sinks open and start concurrently; the session lease count returns to zero after both
close.

**Steps.** Write the tests; if they pass, the item is the tests plus a README sentence ("more
than one player per process is supported") and it is done. If they fail, the failure is the item:
fix it under the same commit with the test as its red.

**Commit.** `core: two players in one process, proven`

---

### S10 Accessibility semantics on the views. Size S, Tier 2 (screen reader proof on device)

**Why.** No `contentDescription`, no `accessibilityLabel`, no Compose semantics anywhere in the
views. A screen reader sees an unlabelled rectangle.

**Depends on:** T2 for announcing cues (optional).

**Files.** Modify `kiteplayer-view/src/androidMain/.../KitePlayerView.kt`, `kiteplayer-view/src/iosMain/.../KitePlayerUIView.kt`,
`kiteplayer-compose-ui/src/commonMain/.../KitePlayerVideo.kt`.

**Contract.** Android: `contentDescription` defaults to "Video"; `stateDescription` (API 30) is
"Playing, 1:23 of 4:56" or "Paused, ..." updated from the snapshot; `importantForAccessibility`
yes. iOS: `isAccessibilityElement = true`, `accessibilityLabel = "Video"`, `accessibilityValue`
with the same state text, `accessibilityTraits` includes `UIAccessibilityTraitUpdatesFrequently`.
Compose: `Modifier.semantics { contentDescription = "Video"; stateDescription = ... }` on the
surface. The state text is one pure function `accessibilityStateText(snapshot, progress)` in
`kiteplayer-view` commonMain, tested on the host.

**Tests.** The pure function for playing, paused, unknown duration, and a live stream. Device:
DEVICE-DAY step 27, TalkBack and VoiceOver read the video.

**Commit.** `view: the video announces itself and its state to screen readers`
