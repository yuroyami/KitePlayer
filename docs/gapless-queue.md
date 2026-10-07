# Gapless queue playback

A queue plays its items one after another. This page describes how the player moves from one
item to the next without a silence between them, and when it opens the next item from scratch
instead. A repeat of one item follows its own end the same way, as [Repeat](#repeat) describes.

Terms used on this page:

- The **current item** is the queue item that plays now.
- The **next item** is the item that `next()` would open. It follows the play order, so it
  follows shuffle, and it wraps from the last item to the first under `LoopMode.All`. With
  `QueueConfig.reshuffleEachLap`, the wrap goes to the first item of a freshly drawn order
  instead, which never begins with the item that ended the lap (#488). It is drawn once, when the
  preload first asks, so the item preloaded is the item that plays. Under a repeat of the current
  item, the next item is the next pass of the current item.
- The **ring** is the audio buffer between the engine and the audio device. It holds at least
  200 ms of sound.
- A **feeder** is the engine worker that converts decoded audio and writes it into the ring.
  Each item has its own feeder.
- An **epoch** counts the seeks of a player. Work that carries an older epoch is dropped.

## Configuration

`PlayerConfig.queue` is a `QueueConfig`:

```kotlin
public data class QueueConfig(
    val preloadNext: Duration = 5.seconds,
    val gapless: Boolean = true,
    val reshuffleEachLap: Boolean = false,
    val onItemFailure: QueueItemFailure = QueueItemFailure.Stop,
    val crossfade: Duration = Duration.ZERO,
)
```

- `preloadNext` is how long before the end of the current item the next item opens. Zero turns
  preloading off, and with it the gapless handoff.
- `gapless` turns the handoff on. False keeps the old path for every item: the device stops at
  the end of an item, and the next item opens from scratch. With `gapless` false the player
  preloads nothing.
- `crossfade` overlaps the end of an item with the start of the next one, as
  [Crossfade](#crossfade) describes. Zero, the default, keeps the gapless join.
- `onItemFailure` says what happens when the next item cannot be opened at all, from its
  preload or from scratch. `Stop` leaves the player in `Failed` on that item. `Skip` warns
  `PlaybackWarning.QueueItemSkipped`, lists the item in `PlayerSnapshot.failedQueueItems` and
  opens the item after it, in the direction the queue was going; it stops in `Failed` when the
  play order runs out or every item has failed in a row (#487).

`PlayerSnapshot.preloadedIndex` is the queue position of the next item once it is open and its
queues fill in the background. Under a repeat it is the position of the current item, and outside
queue playback a repeat publishes none. It is null at all other times.

## Preload

The player preloads the next item when all of these are true:

- The current item plays or is paused, and no seek is in progress.
- The position of the current item is within `preloadNext` of its duration, or of B when an A-B
  loop's B is inside the item.
- A next item exists, or the current item repeats, which an armed A-B loop makes it do. The sleep
  timer is not `SleepTimer.EndOfItem`.
- The current item is seekable, its duration is known, and it is not a still image.
- The current item still has decoded sound to write into the ring. Under an A-B loop whose B is
  inside the item, the turn that plays was given its end at B instead, as below.

The preload opens the source and the decoders of the next item on a coroutine that does not block
the session actor. It creates no audio device. It aligns the queues and the decoders of the item
to the epoch the player is at, and then starts the demux and decode workers of the item. The first
decoded audio buffers and the first video frames wait in the queues of the item. The feeder and
the video schedule of the next item do not run yet.

A next item with a clip or a start position starts where it asks to (#456). The preload moves its
source to the keyframe at or before that place, and its lanes drop what comes before it, so its
first sample and its first picture are the ones there, as on a precise seek.

A renderer can supply its own video decoders, as the Android renderers do. Such a decoder draws
into the renderer's surface, which the current item holds until the swap. So for a next item with
a video stream that is not cover art, the preload leaves that decoder for the swap.

A preload changes nothing that the caller can see except `preloadedIndex`. It emits no event.
Warnings that the backend raises while the item opens are held back and delivered when the item
becomes the current one.

These actions drop a preload and release everything it opened:

- `stop`, `open`, `openQueue`, `previous` and `close`
- every queue edit, `setShuffle` and `restoreQueueOrder`
- `setLoop`, `setSleepTimer` and `setAbLoop`
- a seek and `stepFrame`. A change of speed or of the pitch law is not a seek and keeps the preload
- a track selection, `setVideoEnabled` and a renderer attach or detach
- a video decoder recovery and a failure of the current item

The item then opens the old way at the end of the current item. A preload that the caller dropped
warns nothing, because the caller asked for the action. When the conditions above hold again, the
player preloads again.

`next` keeps a preload whose queues fill: the preloaded item becomes the current one without a
second open, and it gets an audio device of its own, as `next` always gives. During the handoff
itself, `next` drops the preload like the actions above.

## Handoff

The handoff starts when the feeder of the current item has written its last decoded sample into
the ring and the next item is primed, which means that every selected stream of it has enough
data. At that moment the ring still holds the end of the current item.

1. The feeder of the current item is parked.
2. The ReplayGain of the next item is set on the audio path.
3. The feeder of the next item starts. It writes into the same ring, directly after the last
   sample of the current item. The conversion stages (the channel mix, the rate conversion, the
   tempo stage and the equaliser) carry their state across the join, so the join has no seam.
4. The audio path records where the first sample of the next item sits in the ring. The samples
   of the next item continue the timestamps of the ring, so the ring opens no new timestamp
   segment.

The device is not paused, stopped or drained. The current item keeps its video schedule, so its
last frames present on time.

An audio tap gets blocks as they are written into the ring. So it gets every block of the current
item, then a discontinuity at the handoff, and then the next item's blocks under a new generation.
The published audio clock keeps the current item's generation until the swap. A tap that follows
generations, such as the audio visualiser, can therefore draw nothing for the last ring depth of
the current item.

## Swap

When the device plays the first sample of the next item, the audio clock holds at the end of the
current item until the session actor sees the crossing. The actor then swaps the items:

1. `PlayerEvent.Ended` fires for the current item.
2. The video and subtitle lanes, the decoders and the source of the current item close. The audio
   device and the ring stay with the next item.
3. The next item becomes the current item. `media` and `queueIndex` move to it, the audio clock
   reads its own timestamps, and its video schedule starts. A video decoder that the preload left
   for the swap is created first, from the renderer's factories. Until its first frame the picture
   holds the old item's last frame. When no decoder takes the stream, the item drops its video
   track with `TrackDeselected`, as an open does.
4. `PlayerEvent.Opened` fires for it. `PlayerEvent.AudioFormatChanged` does not fire, because the
   device format does not change.

The status does not change: a playing player stays Playing, and the play request stays true. The
position after the swap is the position of the new item, within one audio buffer of its start.
Video frames of the old item that were still queued at the crossing are dropped.

## Repeat

`LoopMode.One` repeats the current item, and so does `LoopMode.All` with a queue of one item or
with media opened on its own. The next pass of the item takes the road a next queue item takes
(#467):

- The preload opens the item again, at its start, which for an item with a clip is the clip's
  start, with the tracks that play now: the video, audio
  and subtitle streams the viewer chose and the video decoder the item came to. It reads none of
  the item's external subtitle files again. A start position applies to the first pass only, as it
  did when a repeat sought back to zero.
- The handoff is the one above, and so is the swap, with these differences. `PlayerEvent.Ended`
  fires, as at each turn of a repeat that seeks back, and `Opened` does not, because the item
  stays the current one. `media` and `queueIndex` do not change. The external subtitle files, both
  subtitle selections and the reports made once for each item, such as `FirstFrameRendered`,
  carry on. `VideoSizeChanged` does not fire again.
- The status stays `Playing` and the device never stops. The position falls back to the start
  of the item at the swap, markers fire again on each pass, and a chapter change fires when the
  position returns to an earlier chapter.

`setLoop`, a seek and the other actions in the list above drop the next pass as they drop a next
item. `next` on a repeating queue of one opens the item afresh rather than from the preload,
because the preload carries none of its external subtitle files.

When the next pass cannot follow this way, the repeat falls back to the old path: the item ends,
the status goes through `Ended` and `Buffering`, and the player seeks back to the start. It warns
`GaplessFallback` for the reasons below, with the current item's own queue position, or -1 outside
queue playback. A source that cannot seek repeats in neither way.

### A-B loop

An armed A-B loop owns the end of the item, as it did when it sought back: its next pass starts at
A, and follows B when B is inside the item, or else the end of the item. The next pass takes the
road of a repeat's, with these differences (#467):

- The preload opens the item at A as a precise seek lands there. The source moves to the keyframe
  at or before A, the video lane of the pass drops the pictures before A, and its feeder cuts the
  sound at A to the sample.
- Each turn of the loop is told where it stops before its first sample is written: at a seek, when
  its pass opens, and when the loop is armed, moved or cleared. The turn that plays stops at B. Its
  feeder writes the sound before B to the sample and holds the rest of the buffer that crosses B
  unwritten, and its video lane holds the pictures at or after B, so nothing from past B is heard
  or shown. Once the sound before B is all in the ring, the next pass takes the ring, and its first
  sample at A follows the last one before B. A section shorter than the ring stops at B the same
  way. The swap at such a B fires nothing, as the seek back to A fired nothing; at the end of the
  item `Ended` fires, as before.
- The preload starts when the turn that plays is within `preloadNext` of B. A turn that starts too
  near B for a pass to open in time, less than a second before it or less than the whole section
  when that is shorter, is given no end: it plays on past B and goes back by the seek, as every
  turn did before passes, and the turns after it start at A with the whole section ahead of them.
  So a seek that lands near B, or a B set just ahead of the position, costs that one turn. Such a
  turn opens no pass, because the seek back to A drops every pass.
- A turn whose sound already reached past B when it was given its end, which only a B set within a
  ring's depth ahead of the position can cause, goes back by the seek the same way.
- No turn is given an end when no pass can follow it: with the gapless handoff off, with the sleep
  timer at `SleepTimer.EndOfItem`, after a fallback, and for a source that cannot seek. The turns
  ask the same rule the preload follows, so none waits at B for a pass that never opens. Each turn
  then goes back by the seek, and a source that cannot seek plays on past B.
- Clearing the loop or a fallback lifts the end of the turn that plays, which carries on past B
  from the sound its feeder held, with nothing lost. Moving B gives the turn the new B as its end in
  the same way, unless the new B is too near or already behind its sound. When the next pass had
  already taken the ring, the ring is cleared with it, and the turn carries on from where its sound
  was heard, by a precise seek; its end stands until that seek lands, so nothing from past B is
  heard or shown before it. An audio track chosen while the turn waits at B drops the pass, and the
  turn's next pass opens with the new track.

A fallback warns as a repeat's does, and the turns go back by the seek while the item stays open.

## Crossfade

Planned contract for #434. The implementation and the generated ABI follow in a separate change.

`QueueConfig.crossfade` is a length, zero by default. Above zero, the end of one queue item and the
start of the next sound together for that long, the first fading out while the second fades in.
Zero keeps the gapless join above, sample after sample. It is at most 30 seconds.

`MediaItem.runsIntoNext` is false by default. True says the item's sound runs into the next one's,
as the tracks of an album can, and the queue joins those two gapless whatever the crossfade says.

**When it applies.** The fade replaces the handoff above only when all of these hold. Otherwise
the join is the gapless one, with nothing warned, because a missing fade is not a failure.

- `gapless` is on and `preloadNext` is above zero, as for any handoff.
- The current item does not run into the next one, by `runsIntoNext`.
- The next item is the next queue item. A repeat's next pass and an A-B loop's join gapless.
- Neither item shows a picture. Cover art is not a picture here. Items with pictures come later.
- The current item's length is stated rather than guessed (#422), because the fade starts that
  long before it.
- The two items decode to the same sample rate and channel count, as the handoff needs anyway.
- The next item is primed when the fade is due to start. A next item still opening keeps the
  gapless join for this pair.

The fade is shortened to half of the shorter item when an item is shorter than twice its length.

**When it starts.** The preload starts `crossfade` plus two seconds before the end, or
`preloadNext` before it, whichever is earlier. The fade starts `crossfade` before the current
item's end, its clip's end for a clipped item. The first sample of the next item sounds together
with the current item's sample at that moment.

**How the two mix.** Before the ring, because the ring has one producer. The current item's feeder
takes the next item's decoded sound, trimmed to its start position or clip as a feeder would trim
it, and adds it to the current item's samples before they enter the shared conversion stages: the
channel mix, the rate conversion, the tempo stage and the equaliser. So a speed change during a
fade keeps it, and the volume, the mute and the balance apply to the mix. Each item's ReplayGain
applies to its own share. The curves are equal power: the current item is scaled by
cos(pi/2 x) and the next by sin(pi/2 x), with x running from 0 to 1 over the fade, so two unrelated
sounds keep their loudness through it. Where the current item runs past the planned end, its
share is silent; where it ends early, the next item's feeder finishes the fade-in on its own.

**The defined point.** The next item becomes the current one at the end of the fade, when the last
sample of the current item is heard. The current item's sound comes from its own reads until then,
so it stays the current item, and the swap above runs as it does for a gapless join: `Ended` fires,
then `Opened`, `media` and `queueIndex` move, the lock screen and the subtitles follow, and the
position reads the next item's time, the fade's length into it. Until then the position, the
clock and the taps are the current item's, and the taps hear the mix.

**Actions during a fade.** A seek, `stop`, `open`, `previous` and `close` end it with the preload,
as they end a handoff. `next` opens the next item from its start rather than from the preload,
whose start the fade has used. Every other action that drops a preload stops the next item's
share at once, and the current item finishes its fade-out to its end, after which the next item
opens the old way. A pause keeps the fade, and play resumes it.

**Tests.** Two 20 second tones of different pitch in a queue with a 5 second crossfade: the queue
lasts 35 seconds, both pitches sound during the overlap, the loudness stays within a set bound
through it, and the position moves to the second item at the end of the fade, 5 seconds into it.
With the crossfade at zero, the device hears exactly the first item's samples followed by the
second's, as it does today. An item that runs into the next, a next item with a picture, a seek
and `next` during a fade, a queue edit during a fade, a pause during a fade, a speed change during
a fade, and a next item that is not primed in time each have a test.

## Items with no sound

An item with video and no selected audio track has no ring to join, so its picture times the join
instead (#524). This covers a repeat, an A-B loop and a queue whose items are all silent:

- The preload opens and primes the next item or pass as for an item with sound.
- The video lane of a pass that stops at B holds the first picture at or after B, and that marks
  every picture before B as handed to the schedule.
- The video schedule publishes the picture on screen and the wall time its slot ends, which is
  when the picture after it is due. A last picture has none after it to measure against, so its
  slot is the duration it carries, or the one the schedule settled on.
- Once the last picture of the item, or the last before B, is on screen, the next item becomes the
  current one, as at a swap, while the player plays. Its schedule waits for the end of that slot
  and shows its first picture there, so the pictures keep one frame period between them across the
  join and the status stays `Playing`. The position is the start of the new pass until its first
  picture shows.
- While paused, nothing swaps, and the join happens when play resumes.
- A renderer that decodes its own video gets the next item's decoder at the swap, as with sound,
  so its last picture stays on screen until that decoder gives its first one.

## Parts of one file

When the next item is the next part of the current item's file, as the tracks of an album in one
file with a cue sheet are, it plays on the current item's reads and opens nothing (#456). The next
item is such a part when it is the same item in every field but its clip, its start position and
its title, artist and album, its clip starts exactly where the current item's ends, and it has no
start position of its own.

- The join is armed before the reads reach the current item's end: when the item's workers start,
  after each seek and on every pass. The reads then go on past the end, through the same decoders,
  so a lossy file joins without the seam a second open leaves. A run of such parts reads on to the
  end of the run.
- The sound of the next item follows in the ring, and its pictures follow on the video lane. The
  items move when the sound heard crosses the end, or the picture shown does for an item with no
  sound: `Ended` fires for the current item and `Opened` for the next, `media` and `queueIndex`
  move, the length and the chapters become the next item's, and the position counts from its start.
  The device, the tracks and every choice the viewer made carry on, and the status stays `Playing`.
  `preloadedIndex` stays null, because nothing opens.
- The join follows the rules of a preload: it is not armed with `gapless` off or `preloadNext` at
  zero, under `LoopMode.One`, an armed A-B loop or `SleepTimer.EndOfItem`, or while the next item
  is preloading. Whenever the next item stops being such a part, by a queue edit, a shuffle, a loop
  or a timer, the join is withdrawn and the current item ends exactly at its end. If the next item's
  sound is already in the ring by then, the player goes back to the sound heard by a precise seek,
  and the item still ends there. The next item then opens the old way.

## Fallbacks

When the handoff cannot run, the player warns `PlaybackWarning.GaplessFallback` with the queue
position of the next item and the reason. It then plays the old way: the device drains and
stops, `Ended` fires, and the next item opens with a device of its own. These are the reasons:

- The preload failed to open, or a worker of the preload failed.
- The preload was still opening or priming when the current item had written all its sound and
  the ring held less than 40 ms of it. Until then the player waits for the next item.
- One of the two items has a selected audio track and the other has none.
- The current item has no selected audio track and no picture to time the join, or its picture
  was turned off during the handoff.
- The current item has no selected audio track, and the preload was still opening or priming
  when the slot of its last picture ended.
- The sample rate or the channel count of the next item differs from the format that the device
  was opened for.

When the reason is the audio of the next item, its format or a missing track, the preload stays
and the next item opens from it, without a second open of its source. For the other reasons the
next item opens from scratch. A repeat's next pass is released for every reason, because its old
path seeks back rather than opening anything.

After a fallback the player does not try the same two items again while the current item stays
open.

## Actions during the handoff

Between the handoff and the swap, the device plays the end of the current item, and the sound of
the next item follows in the ring. Pause and play, the volume, the mute, the balance, the
equaliser and the subtitle settings keep the handoff.

Every action that drops a preload also cancels a handoff that has started:

1. The feeder of the next item is parked.
2. The device stops and the ring is cleared. The current item loses at most one ring depth of its
   end.
3. The preload is released.
4. The action runs against the current item, which is at its end. When the next pass was an A-B
   loop's at a B inside the item, the current pass is not at its end: it carries on from where its
   sound was heard, by a precise seek.

## How the tests check it

- The harness plays two scripted items of three seconds with a scripted device that logs every
  call. With the defaults, the device sees one open and one start, no stop, pause or drain
  between the items, and every sample of both items. `Ended` fires before `Opened`, and the
  position after the swap starts within one buffer of zero. With `gapless` false, the old
  sequence is pinned.
- Each fallback has its own test.
- With real media, the same lossless file plays twice in a queue with no underrun across the join
  and no stop on the CoreAudio sink. The desktop JVM line and Android's AudioTrack pass the same
  check: one open, and no stop, pause or drain between the items.
- On the CI emulator, a video queue on the Compose GPU path plays on one AudioTrack, and the
  renderer shows the second item's pictures from its new decoder.
- A scripted item of four seconds plays for 18 seconds under `LoopMode.One`. The device sees one
  open and one start and nothing else, the status never leaves `Playing`, the position wraps four
  times, `Ended` fires four times and `Opened` once. With the loop turned off during a pass, every
  sample of each pass is heard and the last pass ends as an item ends. The chosen audio track, a
  chosen container subtitle over the automatic one and an external subtitle file read once all
  carry on across the join.
- The same item loops from 1 s to 3 s for 10 seconds. The device sees one open and one start and
  nothing else, the status never leaves `Playing`, the position wraps four times and `Ended` never
  fires. With the loop cleared during a pass, the device has heard each section once, cut at A and
  B to within a few samples of what a precise seek to A plays. A loop from 1 s with no B, or with
  a B past the end, wraps at the end of the item and fires `Ended`. With video, no picture at or
  after B shows, and none before A after a wrap. Each of these has a test of its own: a seek
  inside the section, a seek past B, a section of a quarter second, a B between two decoded
  buffers, a seek inside a section shorter than the ring, a section set behind the sound already
  written, a turn that starts too near B, a pass still opening when the sound reaches B, the loop
  cleared while the sound waits at B and after the next pass took the ring, an audio track chosen
  at B, the player's own seek while the sound waits at B, a B moved during a pass, the end-of-item
  sleep timer, the gapless handoff turned off, and a loop armed before a source that cannot seek
  opens.
- A scripted item of four seconds with video and no audio plays for 18 seconds under
  `LoopMode.One`: the status never leaves `Playing`, the position wraps four times, each pass shows
  every picture once, and every picture's target time is one frame period after the one before,
  across each join too. The same holds for a loop from 1 s to 3 s, with no picture at or after B
  and none before A after a wrap, and for a queue of two silent items. A pause on the last picture
  joins on resume, a silent item followed by one with sound falls back, and so do a preload still
  opening when the pictures run out and a silent item whose picture is off.
