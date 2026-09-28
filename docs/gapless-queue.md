# Gapless queue playback

A queue plays its items one after another. This page describes how the player moves from one
item to the next without a silence between them, and when it opens the next item from scratch
instead.

Terms used on this page:

- The **current item** is the queue item that plays now.
- The **next item** is the item that `next()` would open. It follows the play order, so it
  follows shuffle, and it wraps from the last item to the first under `LoopMode.All`.
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
)
```

- `preloadNext` is how long before the end of the current item the next item opens. Zero turns
  preloading off, and with it the gapless handoff.
- `gapless` turns the handoff on. False keeps the old path for every item: the device stops at
  the end of an item, and the next item opens from scratch. With `gapless` false the player
  preloads nothing.

`PlayerSnapshot.preloadedIndex` is the queue position of the next item once it is open and its
queues fill in the background. It is null at all other times.

## Preload

The player preloads the next item when all of these are true:

- The current item plays or is paused, and no seek is in progress.
- The position of the current item is within `preloadNext` of its duration.
- A next item exists. `LoopMode` is not `One`, no A-B loop is set, and the sleep timer is not
  `SleepTimer.EndOfItem`.
- The current item is seekable, its duration is known, and it is not a still image.
- The current item still has decoded sound to write into the ring.

The preload opens the source and the decoders of the next item on a coroutine that does not block
the session actor. It creates no audio device. It aligns the queues and the decoders of the item
to the epoch the player is at, and then starts the demux and decode workers of the item. The first
decoded audio buffers and the first video frames wait in the queues of the item. The feeder and
the video schedule of the next item do not run yet.

A renderer can supply its own video decoders, as the Android renderers do. Such a decoder draws
into the renderer's surface, which the current item holds until the swap. So for a next item with
a video stream that is not cover art, the preload leaves that decoder for the swap.

A preload changes nothing that the caller can see except `preloadedIndex`. It emits no event.
Warnings that the backend raises while the item opens are held back and delivered when the item
becomes the current one.

These actions drop a preload and release everything it opened:

- `stop`, `open`, `openQueue`, `next`, `previous` and `close`
- every queue edit, `setShuffle` and `restoreQueueOrder`
- `setLoop`, `setSleepTimer` and `setAbLoop`
- a seek, which includes a change of speed or of the pitch law, and `stepFrame`
- a track selection, `setVideoEnabled` and a renderer attach or detach
- a video decoder recovery and a failure of the current item

The item then opens the old way at the end of the current item. A preload that the caller dropped
warns nothing, because the caller asked for the action. When the conditions above hold again, the
player preloads again.

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

## Fallbacks

When the handoff cannot run, the player warns `PlaybackWarning.GaplessFallback` with the queue
position of the next item and the reason. It then plays the old way: the device drains and
stops, `Ended` fires, and the next item opens from scratch. These are the reasons:

- The preload failed to open, or a worker of the preload failed.
- The preload was still opening or priming when the current item had written all its sound and
  the ring held less than 40 ms of it. Until then the player waits for the next item.
- The current item or the next item has no selected audio track.
- The sample rate or the channel count of the next item differs from the format that the device
  was opened for.
- The next item has a start position.

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
4. The action runs against the current item, which is at its end.

## How the tests check it

- The harness plays two scripted items of three seconds with a scripted device that logs every
  call. With the defaults, the device sees one open and one start, no stop, pause or drain
  between the items, and every sample of both items. `Ended` fires before `Opened`, and the
  position after the swap starts within one buffer of zero. With `gapless` false, the old
  sequence is pinned.
- Each fallback has its own test.
- With real media, the same lossless file plays twice in a queue with no underrun across the join
  and no stop on the CoreAudio sink.
