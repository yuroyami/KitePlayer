# Cancellation and bounded waits

This document is the design for issue #30 and the work under it: #31 (an open that hangs), #32 (a
read that stalls), #33 (network waits) and the recovery scope of #96. It says how a cancellation
reaches a read, where each limit lives and what each limit covers.

The rule is simple. Every wait for media bytes has a limit. A stop, a close or a cancelled `open()`
call reaches every read that KitePlayer performs itself.

## Terms

- **The actor** is the one coroutine in `PlaybackCore` that owns all session state.
- **The demux lane** is the single thread that opens the container and reads packets.
- **A reader** is a `MediaIo`: the item's own `io`, or one that a resolver supplied, such as the
  HTTP reader of `kiteplayer-network`.
- **The blocking bridge** is `BlockingMediaIo` in `kiteplayer-ffmpeg`. It hands a reader to FFmpeg,
  which calls it synchronously on the demux lane.
- **The URL fallback** is the road for an item that has no reader: FFmpeg opens the URI with its
  own protocols.

## How a cancellation reaches an open

FFmpeg's interrupt belongs to the source that an open returns. While the open runs, that source
does not exist, so there is nothing to call. Two changes solve this for every open that reads
through a reader.

1. The engine runs the backend open as a job on the demux lane. The actor keeps reading its
   mailbox while it waits. A stop or a close cancels the job. A cancelled `open()` call does the
   same, because cancelling that call queues a stop.
2. The blocking bridge is the handle. It exists before FFmpeg's open does. Every read and every seek
   of the bridge runs as a child of one job that the bridge owns. `BlockingMediaIo.interrupt()`
   cancels that job. The read in flight then resumes with a cancellation, the bridge reports an
   I/O error to FFmpeg, and every later read fails at once.
3. The backend links the two. When the open's coroutine is cancelled, the bridge is interrupted,
   FFmpeg fails the open, and the backend throws a `CancellationException`, not a media error.
4. After the open, `PlayerMediaSource.interrupt()` interrupts the bridge as well as FFmpeg's own
   flag. KiteFFmpeg reads that flag before each call into the bridge, never while the bridge waits,
   so the flag alone cannot end a read that waits inside a reader.

After it cancels an open, the engine waits for the open to end. It does not leave the open behind:
the demux lane and the reader belong to the open until it returns, and a close under a read is the
hazard in the last section. The open that a stop preempted answers its caller with the same
`IllegalStateException` that a preempted open gives today.

What this promises, and what it does not:

- An open that reads through a reader ends as soon as the reader's read resumes with the
  cancellation. The readers that wait for bytes, the HTTP reader and `PipedMediaIo`, suspend and
  resume at once. `MediaIo` now asks every reader to suspend cancellably. A reader that blocks its
  thread, such as `MediaIo.ofStream` over an `InputStream` that stops answering, cannot be reached.
- An open through the URL fallback cannot be stopped yet. The fix needs an interrupt that exists
  before the open returns, which is yuroyami/KiteFFmpeg#62. Until then a stop waits for such an
  open to end, and the URL fallback's read timeout limits that wait.
- FFmpeg's HLS demuxer does not copy the interrupt callback to two kinds of child context. Only a
  patch in KiteFFmpeg can fix that, and yuroyami/KiteFFmpeg#62 records it.
- The web build does not block. Its bridge refuses a reader that suspends, so an open there never
  waits for bytes.

## Where the stall timeout lives

`BufferPolicy.stallTimeout` is the stall timeout. The default is 30 seconds, and
`Duration.INFINITE` turns it off.

The engine owns the stall timeout, not the reader. Only the engine sees every source: FFmpeg's own
protocols, a reader, and a test's scripted source. And only the engine can end a session.

- The count runs while a source call waits: a packet read on the demux lane, a backend open that
  reads through a reader, or a read of an external subtitle file.
- Progress starts the count again. Progress is a packet from the source, or bytes from the reader.
  So a slow source that still delivers is never a stall.
- At the limit, the engine interrupts the source, ends the session and fails with
  `PlaybackError.SourceStalled`. The error names the source and how long it waited.
- An external subtitle file whose reader stalls is skipped with `SubtitleSourceUnreadable`, like
  any other unreadable subtitle file.
- A source whose `interrupt()` returns false cannot be stopped. The engine keeps waiting, as it does
  for a container seek on such a source.
- A paused player whose buffers are full reads nothing, so it never stalls.
- For media that the URL fallback reads, the engine sees progress for each packet, not for each
  byte.

## Network waits

`kiteplayer-network` takes an `HttpReaderPolicy` in `KtorMediaIo.open` and in
`KtorMediaIoResolver`. The automatic provider uses the defaults. To change them, install a
`KtorMediaIoResolver` with your own policy as `NetworkConfig.ioResolver`.

| Field | Default | What it limits |
|---|---|---|
| `connectTimeout` | 10 s | The connection and the response headers of one request. A seek makes a new request, so this limits a seek too. |
| `readTimeout` | 10 s | The wait for the next bytes of a response that has started. |
| `maxReconnects` | 5 | The reconnects that one read may make before it fails. |
| `initialBackoff` | 0.5 s | The wait before the first reconnect. Each later wait is twice as long. |
| `maxBackoff` | 4 s | The longest wait between two reconnects. |

The reader enforces these limits itself, with coroutine timeouts. So they work the same with every
Ktor engine, and with a client that the application supplies.

## Recovery from a dropped connection

This is the recovery scope that #96 records.

1. A failed read, a read timeout or a response that ends before the declared size makes the HTTP
   reader reconnect.
2. The reconnect is a `Range` request at the byte that the reader had reached. The reader checks
   that the answer starts at that byte.
3. Before each reconnect, the reader reports `PlaybackWarning.SourceReconnecting` through
   `MediaIo.setWarningSink`. The engine installs its warning reporter on every reader.
4. The count of reconnects belongs to one read, so a connection that drops now and then never
   uses it up.
5. A reader fails as before when it cannot resume: the server does not support ranges and bytes
   were already read, the server refuses the request or answers from a different byte, or the
   reconnects are used up.

The stall timeout limits all of this. When no bytes arrive for that long, the engine ends the
session. Entity tags, conditional requests and adaptive bitrate are not part of this scope.

## What each limit covers

| Wait | Limit | Result |
|---|---|---|
| A backend open that reads through a reader | A stop, a close or a cancelled `open()`: at once. No progress for the stall timeout. | The open is cancelled. A stall fails with `SourceStalled`. |
| A backend open over http or tcp through the URL fallback | FFmpeg's 5 s connect limit, and a 10 s read timeout on each read | FFmpeg fails the open. |
| A packet read during playback | No progress for the stall timeout | `SourceStalled` |
| A read of an external subtitle file | No bytes for the stall timeout | The file is skipped with a warning. |
| A container seek | 10 s, then the interrupt and a 2 s grace | Unchanged. The session fails with `SourceUnavailable`. |
| The first fill after an open | 10 s | Unchanged. `StartupIncomplete` |
| One HTTP request: connection and headers | `connectTimeout` | A reconnect during a read. A failure at open. |
| One HTTP response: the next bytes | `readTimeout` | A reconnect |
| A close | 10 s | Unchanged. `RuntimeCompromised` |

## The URL fallback

An item takes the URL fallback when it has no reader and no resolver answers for its URI. FFmpeg
then opens the URI with the protocols of the build: file, fd, pipe, data, http and tcp.

- Every http and tcp read waits at most 10 seconds. The backend sets FFmpeg's `rw_timeout` option,
  in microseconds, unless the item sets `rw_timeout` in `MediaItem.openOptions`. FFmpeg limits the
  connection itself to 5 seconds.
- The URL fallback has no https, because the build has no TLS. It does not reconnect, and it
  reports no warning. It cannot be stopped during the open until yuroyami/KiteFFmpeg#62.
- The HTTP reader of `kiteplayer-network` is the recommended road for http and https. Every
  standard entry point includes it, so the URL fallback carries http only when that module is
  absent or `NetworkConfig.autoResolve` is false.

## The blocking bridge, and the hazard it carries

The hazard list of #30 calls this "the blocking web reader". That is a copying mistake. The hazard
belongs to `BlockingMediaIo` on the JVM, Android and native targets, which blocks the demux lane's
thread in `runBlocking` for each read. The web version does not block at all.

The reasoning, which must stay true: blocking there is safe only because nothing that closes the
reader runs on the demux lane while a read can be in flight. The engine closes a session's source
from the actor or from the release lane, after the demux worker has ended. It closes an abandoned
open only after that open's job has ended. The cancellation above keeps both true, because the
engine waits for a cancelled open to end before anything closes the reader. Check this reasoning
again before you change the bridge, the open or the teardown.

## Tests

- `kiteplayer-core`: virtual-time tests with a scripted source for a stall during playback, a
  stalled open, a stalled subtitle file, a slow source that is not a stall, a stop that preempts
  an open whose reader hangs, and a reader's warning reaching the player.
- `kiteplayer-ffmpeg`: on the JVM with real FFmpeg, a cancelled open whose reader hangs while
  FFmpeg discovers the streams ends with a `CancellationException`, and `interrupt()` ends a
  packet read that waits for bytes. The URL fallback's options are checked for each scheme.
- `kiteplayer-network`: against a local server, the connect and read timeouts, a resumed read after
  a stalled response and after a dropped connection, and a server that refuses a range.
