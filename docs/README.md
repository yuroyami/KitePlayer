# KitePlayer documentation

The [README](../README.md) covers installation and everyday use. The guides under "Using
KitePlayer" go into more detail, and the other guides describe contracts that the code implements
today. The API reference is the KDoc. `./kotlin do dokka` writes each module's pages, then
`./kotlin do dokkaSite` merges them into `build/dokka/html`.

## Using KitePlayer

- [Controlling the player](player-api.md): every call by area, the flows, seeking, audio devices
  and screen readers.
- [Opening media](media-sources.md): local files, bytes, picked files, files that are still
  growing, item settings, and a separate audio file that plays with the video.
- [Subtitles](subtitles.md): the formats, how the player chooses a track, and how it styles the
  text.
- [Network streams](streaming.md): HTTP, HLS, DASH and radio, variant changes, and recovery when
  the network drops.
- [Background playback](background-playback.md): the Android media notification, and what each
  other platform does in the background.

## Building on KitePlayer

- [Module contract](module-contract.md): which module to depend on, what each entry point brings
  in, and how the network transport and the libass typesetter are found.
- [SPI cookbook](spi-cookbook.md): a complete custom backend, from the media source to a decoder.
  The SPI is the service interface in `kiteplayer-core`'s `spi` package.
- [Cancellation and bounded waits](cancellation-and-bounded-waits.md): how a cancellation reaches a
  read, and which limit bounds each wait.
- [Subtitle placement](subtitle-placement.md): where subtitles land, on every renderer and with
  both subtitle engines.
- [TTML regions](subtitle-regions.md): how TTML and DFXP files are read, the regions their text
  flows in, and what is not drawn.
- [Gapless queue playback](gapless-queue.md): how a queue moves to its next item without a
  silence, and when it opens the item from scratch instead.
- [Segment store](segment-cache.md): keeping HLS and DASH segments on disk between player
  lifetimes, what is stored, and when a stored segment is used.
- [Dimming flashing video](video-flash-guard.md): the flash guard, the rule it counts flashes by,
  and which renderers carry it so far.
- [Matroska editions and linked files](matroska-editions.md): how an ordered edition plays as one
  timeline, and how its parts in other files are found.
- [360 degree video](spherical-video.md): how a 360 degree video plays as a view the viewer turns,
  the call that turns it, and which renderers draw it.

## Audio visualiser

`kiteplayer-audioviz` draws pictures from the music of a file that has no video. The standard
states the goals, and each contract below covers one part of it.

- [Audio visualiser standard](audioviz-standard.md): the goals and the requirements.
- [Timing and ownership](audioviz-timing-api.md): the clocks and the workers.
- [Calibrated audio features](audioviz-feature-api.md): the spectrum and the level measurements.
- [Onset method](audioviz-onset-method.md): the maths of the onset detector.
- [Audio events](audioviz-events-api.md): how detected events reach the drawings.
- [Rhythm evidence and motion](audioviz-rhythm-api.md): the pulse tracker and what it does not
  claim.
- [Musical structure and key](audioviz-structure-api.md): section changes and the key estimate.
- [Song scan and song map](audioviz-song-scan-api.md): the background decode of the whole song.
- [Drawing mappings](audioviz-mapping-api.md): which audio values move which parts of a drawing.
- [Rendering, motion and flash limits](audioviz-rendering-api.md): trails, the flash policy and
  the viewer's settings.

## Records

[`records/`](records/) holds dated records: the verification logs of past releases and a
measurement report. They describe the code at their date, and nobody keeps them current.
