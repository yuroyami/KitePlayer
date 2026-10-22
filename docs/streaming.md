# Network streams

HTTP and HTTPS work as soon as `kiteplayer-network` is on the classpath, and every standard entry
point includes it. You do not build a resolver or a Ktor client. This page describes how the player
reads HTTP, HLS, DASH and radio streams, and how it recovers when the network drops.

## HTTP

- Android and the JVM use OkHttp with the platform trust store, and Apple uses NSURLSession.
- `MediaItem.headers` reach whichever transport the player selects.
- Your app declares the `INTERNET` permission. Your app's own policy decides about cleartext
  HTTP. Also declare `ACCESS_NETWORK_STATE`, which Android grants at install, and the provider of
  `kiteplayer-network` that keeps the application context. The
  [module contract](module-contract.md) shows the manifest lines. With these, a player that waits
  for the network hears at once when the network comes back.
- When the network stays down longer than the reader's reconnects, the item fails. With
  `NetworkConfig.recovery = NetworkRecovery()`, the player waits for the network instead, for up to
  `maxWait`, and `PlayerSnapshot.reconnecting` says so. When the network comes back, the player
  opens the item again where it was, or at the live edge. `NetworkRecovery.status` tells the player
  that the network is back, and by default the network module watches the platform for it.
  Recovery is off by default.
- In a browser, a player on the page's own thread cannot play network media, because a read cannot
  wait there. `KitePlayerWorker` plays it from a web worker (see the
  [web setup](../README.md#web-setup)). It reads a single file with range requests, and it plays
  HLS and DASH too. On the page's thread, fetch the file and play it from memory with
  `MediaItem.from(MediaIo.ofBytes(bytes), label = name)`.

## HLS

HLS plays through the same transport. An address that ends in `.m3u8`, an HLS content type from
the server, or `formatHint = "hls"` marks a playlist. When none of those does, the first bytes do:
a playlist starts with `#EXTM3U`, so one behind an address with no extension, sent as text or as
bytes, plays too.

- A master playlist plays one variant: the one that `DemuxPolicy.variant` names, or else the one
  with the highest bitrate within `DemuxPolicy.maxBitrate` and `DemuxPolicy.maxVideoHeight`.
  `Tracks.variants` lists the variants, and `KitePlayer.selectVariant` plays another one from the
  current position.
- The choice follows the screen. Under `HdrPolicy.Auto`, an HDR version plays on a display that
  shows HDR as HDR, and the SDR one plays elsewhere. The player does not choose a variant larger
  than the smallest one that fills the view, so a phone does not fetch 4K. The cap rises when the
  view grows. The player reads the display and the view from the attached renderer at each open
  and each step. Set `DemuxPolicy.fit` to choose the cap yourself, or `VariantFit()` for no cap.
  A DASH manifest's transfer characteristics property counts as HLS's `VIDEO-RANGE`.
- The player steps down when the stream reads slower than it plays, or when playback has waited
  4 s for data. `PlaybackWarning.VariantLowered` says so.
- The player steps up when the network carries one and a half times the bitrate of the next higher
  variant and the buffer holds its target. The network reader measures that rate on its downloads,
  and reports it through `MediaIo.networkBitsPerSecond`. With a reader of your own that answers
  null, the player never steps up.
- A step up waits 30 s after a step down, and twice as long after each step up that did not last,
  up to 5 minutes.
- A variant that you selected stays. A step up never passes `DemuxPolicy.maxBitrate`,
  `maxVideoHeight` or the fit, and never moves between SDR and HDR.
- MPEG-TS and fMP4 segments, byte ranges, AES-128 keys, separate audio and subtitle renditions, and
  live playlists play. A finished playlist can seek. A rendition's `NAME` is its track's title,
  and its `DEFAULT`, `FORCED` and accessibility `CHARACTERISTICS` set the track's flags.
- The player downloads only the audio rendition that you hear, so a stream with six languages does
  not pay for five that nobody hears. A switch to another language reads it from the moment
  playing: the picture and the old language go on until the new one is there, usually within a
  second, and the reads go back once over what they had read ahead. A file switches instantly.
- The player skips a segment that it cannot read, and `PlaybackWarning.SegmentSkipped` says so.
  When the last segments of a stream fail, the stream ends with `PlaybackError.SourceUnavailable`.
- `MediaItem.headers` go only to the scheme, host and port of the item's own address, because a
  playlist can name segments on any server.
- Your own `MediaIo` can serve HLS too: report the address it read in `location`, and open the
  addresses the playlist names in `openRelated`.

### How a stream changes variant

- A stream changes variant in place. The stream stays open, and the next segment that the player
  has not read yet comes from the new variant. The picture changes quality with no pause, once the
  media that the player already read has played. A live stream changes variant this way too. This
  needs variants that share their segment boundaries and their codec, as the HLS specification
  asks.
- MPEG-TS segments change as they are. fMP4 segments change when their pictures are H.264, HEVC,
  AV1 or VP9: the player writes each fragment again for the initialization the stream began with.
  H.264 and HEVC fragments get the variant's own parameter sets. AV1 and VP9 need nothing added,
  because their key frames state the picture size. The player decrypts AES-128 fMP4 segments
  itself, so each variant may have its own key.
- WebM segments in VP8, VP9 or AV1 change as they are when the variants' headers use the same
  timestamp scale, which packagers do. Where only the track numbers differ, the player writes the
  first header's numbers into each cluster.
- A DASH manifest changes the same way, one of several Periods too.
- Any other change opens the stream again, so the picture holds for a moment: a variant of another
  codec, profile, bit depth or dynamic range, and variants that do not share their segment
  boundaries. An automatic step also opens the stream again where the stream cannot change in
  place.

### Playlist variables and the time of day

- Playlist variables play: `EXT-X-DEFINE` by `NAME` and `VALUE`, by `IMPORT` from the master
  playlist, and by `QUERYPARAM` from the playlist's own address, so a token in the master
  playlist's address reaches every variant, segment and key that names it. A playlist behind a
  redirect takes its `QUERYPARAM` and its relative addresses from the address it was redirected
  to. A reference to a variable that nothing defined fails the open, and the error names it.
- `EXT-X-PROGRAM-DATE-TIME` gives each position the time of day it was broadcast, in milliseconds
  since 1970 UTC. `Progress.timeOfDayMillis` publishes it for the position, and
  `firstTimeOfDayMillis` and `lastTimeOfDayMillis` for the first and last moments that the
  playlist lists (the last of a live playlist is its edge). `KitePlayer.timeOfDayAt` and
  `positionAtTimeOfDay` map both ways, `seekToTimeOfDay` goes to one in a stream that can seek,
  and `timeOfDayClock` makes two players on one live stream follow the same broadcast moment. A
  DASH manifest's `availabilityStartTime` gives the same.

## Radio

### Song titles

A Shoutcast or Icecast station names each song as it starts, and the player shows it when it is
heard rather than when it is read, seconds ahead. The network reader asks for the titles, takes
the title blocks out of the bytes, and reads a title in windows-1251 or another legacy table as
the player reads a subtitle file. A chained Ogg's next song and the other tags a stream changes
while it plays arrive the same way.

- `PlayerSnapshot.metadata` holds the song as `StreamTitle`, beside the station's `icy-name`.
- The media session shows the song as the title and the station on the artist line.
- `setItemDetails` replaces the playing item's title, artist and album without opening it again,
  for a station that publishes its song list somewhere else. Each call and each new song from the
  station replaces the last one.
- A reader of your own reports tags through `MediaIo.takeTags`.

### Lists of streams

A radio station's link is often a list that names its stream rather than the stream itself, and
it plays as it is. The player recognizes a PLS file by its `[playlist]` first line, an
`audio/x-scpls` type or a `.pls` address. It recognizes an M3U list by the same marks as an HLS
playlist, but with no `#EXT-X-` tag in it.

- The first stream on the list that opens plays, so the backups a station lists after its main
  stream take over when that one is down. When none opens, the player reports the last failure.
- The stream's title is the one the list gives it, `TitleN` in a PLS file and the `#EXTINF` text
  in an M3U list, unless the stream names itself.
- The streams open through the list's reader, on its client, so a `MediaIo` of your own serves
  them through `openRelated`, as for HLS. The player follows a list that names another list, three
  levels deep at most.
- A station's server closes a listener's connection now and then, after a long pause, when its
  encoder restarts or when a load balancer moves the listener. A stream with no length and no
  ranges that carries Shoutcast or Icecast `icy-` headers connects again and goes on from the live
  edge, with `PlaybackWarning.SourceReconnecting` each time. The stream ends only when the station
  still answers 404 or 410 after the last reconnect. Without those headers, the stream ends where
  the server stops, because a media server that encodes a song as it sends it answers the same
  way, and asking it again would play the song again.

## DASH

A DASH address plays as it is, through the same transport, with no call to make. The player
recognizes a manifest by its `application/dash+xml` type, by a path that ends in `.mpd`, or, when
the server sends it as text, XML or bytes, by its root element.

- A manifest whose picture and sound are fragmented MP4, MPEG-TS or WebM plays through the HLS
  path: each video representation is a variant, each audio set an audio rendition, and each
  subtitle set a subtitle rendition. FFmpeg's HLS reader reads WebM segments although the HLS
  specification names only the other two. Separate sets play together, a finished presentation
  seeks, and a live one plays from its live edge and fetches the manifest again after each update
  period.
- A live manifest counts its window on the time of day that its `UTCTiming` names, by `direct`,
  `http-xsdate`, `http-iso` or `http-head`. Without an answer, the player uses the device's clock
  and writes a line in the log. A refresh follows the manifest's `Location`. In a browser,
  `http-head` answers only from a server that exposes its `Date` header.
- An audio or subtitle track takes its set's `Label` as its title, `main` sound is the default,
  `forced-subtitle` is a forced track, and the player marks `caption` and `description` as
  accessibility tracks.
- Segment templates, numbered or with a timeline, segment lists, and single files all play: an
  MP4 file through its segment index (`sidx`), and a WebM file through its `Cues`.
- Subtitle sets of WebVTT play as they are. The player serves sets of TTML, and of TTML or WebVTT
  in MP4 segments (`stpp`, `wvtt`), as WebVTT, with their text, line breaks, italic, bold and
  underline, but not their placement.
- A manifest of several Periods, as ad insertion and chapters stitch them, plays as one
  presentation. Each later Period gives each track the set with the same `id`, at the same place
  or in the same language, and the representation nearest its bandwidth. Time runs on across each
  boundary although each Period's media time starts again. An fMP4 Period of another picture size
  decodes at its own size, because its H.264 or HEVC parameter sets travel with its keyframes, and
  a live manifest that a refresh gives a new Period plays on into it. The player skips an fMP4
  Period in another codec than the first.
- `MediaItem.headers` go to the manifest and to the segments of its own scheme, host and port, as
  for HLS.
- `Dash.mediaItemFor` builds the item yourself, for a client of your own, a `DashUrlPolicy` other
  than the default, other size ceilings or other wait limits (`HttpReaderPolicy`). `Dash.manifest`
  reads a manifest without playing it.

## Segments on disk

A player downloads the segments of an HLS or DASH presentation again at each open, unless it has a
segment store. [Segment store](segment-cache.md) describes what the store keeps and when the player
uses a stored segment.

## Which transport wins

The item's own `io` source wins, then a resolver that you set in `NetworkConfig.ioResolver`, then
the automatic provider. `NetworkConfig.autoResolve = false` turns the automatic provider off. No
HTTP client exists until network media opens, and the reader that created one closes it.
