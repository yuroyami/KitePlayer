# Subtitles

This page describes the subtitle formats that KitePlayer reads, how the player chooses a track, and
how it styles the text. [Subtitle placement](subtitle-placement.md) describes where the text lands
on each renderer, and [TTML regions](subtitle-regions.md) describes how TTML text flows.

## Formats

- SubRip, WebVTT and SubStation Alpha, from the container or from an external file. External files
  load at open or in the middle of playback.
- An external file can also be TTML or DFXP, or LRC lyrics, which the Kotlin parsers read, or any
  of SAMI, MicroDVD, SubViewer and SBV, MPL2, JACOsub, VPlayer, PJS, RealText, Spruce STL,
  Scenarist SCC, VobSub and Blu-ray `.sup`, which FFmpeg's own readers read. The FFmpeg backend
  supplies both, so a player built on another backend reads only what that backend offers.
- libass draws ASS and SSA tracks as authored: moving signs, animated transforms, karaoke fills,
  clips and vector drawings. A moving sign renders again on every video frame.
- Blu-ray (PGS), DVB, DVD and XSUB image subtitles from the container, placed on the picture that
  they were authored for.
- DVB teletext subtitles from a broadcast recording or an IPTV stream, read in Kotlin with no native
  library. Each subtitle page that the channel lists is a track of its own, with its language and
  its hearing-impaired mark. A page shows its colors, its boxed backgrounds and its double height
  lines in the top or bottom half of the picture. The letters follow the page's national character
  set: Latin, Cyrillic, Greek or Hebrew.
- The closed captions that broadcast H.264, HEVC and MPEG-2 carry inside the picture (with no
  subtitle stream of their own) become a track, CC1, from the first picture that carries any. Each
  screen shows as it is sent. Like a television, the player shows them when the viewer's
  preferences or the viewer ask, not by default. A hardware decoder whose pictures carry no
  captions, as Android's MediaCodec on `KitePlayerView`, the Compose views and a live stream's
  route, still gives CC1 for H.264 and HEVC: the player reads the captions from the SEI of the
  packets it holds, and hands each picture those up to its time (#611). MPEG-2 decodes through
  FFmpeg and gives them with its pictures.
- The CEA-708 caption services in the same bytes become tracks of their own, Service 1, Service 2
  and so on, one for each service that carries data, read in Kotlin on every platform (#678). A
  broadcast often has its second language only there. Each visible window shows at the place its
  anchor sets, with its pen's colors, background, italics, underline, size and edge, and a window
  written while hidden shows when the stream displays it. Text printed other than left to right,
  the font style, flashing, borders and the justification of a window's lines are not read: the
  lines follow the horizontal part of the window's anchor. A picture whose bytes carry CEA-708 and
  no CEA-608 makes no CC1. Only Service 1 takes the picture's language, because the stream names
  the others' languages only in a transport stream descriptor the player does not read.
- Synced lyrics: an `.lrc` file, or LRC lines in a song's own tags (an ID3 `USLT` frame, a Vorbis
  or Matroska `LYRICS`, an MP4 `©lyr`), become a track that shows line by line through
  `subtitleCues`. The player selects that track when no other track is selected. Lyrics without
  times are `PlayerSnapshot.lyrics`, for the application to show.

## Choosing a track

- An external file that you add without a language takes one from its name, as `Film.en.srt`,
  `Film.eng.forced.srt` and `Film.pt-BR.sdh.srt` say it. A `forced`, `sdh`, `cc` or `hi` in the
  name marks the track. At open, the player prefers a file in a preferred language over a container
  track in a later one.
- A subtitle that the player chose by itself follows the audio. Switching an anime to the English
  dub shows the English signs track made for it, and switching back shows every line again. A
  track that the viewer chose stays. `SubtitleConfig.withMatchingAudio` (mpv's
  `subs-with-matching-audio`) keeps only forced tracks, or none, under audio in a preferred subtitle
  language.
- A disc's forced captions (the signs and foreign dialogue that a Blu-ray or DVD track marks forced
  among its pictures) can draw on their own. `setForcedPicturesOnly` (mpv's
  `sub-forced-events-only`) draws only those of the chosen track. `SubtitleConfig.forcedPicturesWhenOff`
  draws those of the track in the audio's language while no subtitle is chosen.
- `PlayerConfig.subtitles.secondaryLanguages` shows a second track in another language at each
  open. That track shows at the top, or directly above or below the first with
  `secondaryPlacement`.

## Text and style

- `SubtitleConfig.hearingImpairedNotes` hides the notes of subtitles made for deaf and
  hard-of-hearing viewers: `[DOOR SLAMS]`, a `(laughs)` that opens a line, `JOHN:` and `♪` music
  lines, and with `HideStrict` every parenthesis. The setting does not change ASS scripts.
- WebVTT keeps its colors: the standard's color classes such as `<c.yellow>` and `<c.bg_blue>`,
  and the `::cue` rules of its `STYLE` blocks for color, background, bold, italic, underline, font
  and relative size, by class, voice and cue identifier. The player ignores a whole rule that asks
  for anything more. A `SubtitleStyleOverride` still wins over the file's colors.
- The player matches an ASS script's colors to the video through its `YCbCr Matrix` header, which
  keeps a sign that was colored to blend into the picture blending in. A script with no header
  counts as BT.601 at studio range. `None` keeps the script's colors, and so do HDR and RGB video.
  The built-in styling does the same, and `SubtitleConfig.assColorMatching = false` keeps every
  color as authored.
- Fonts attached to a Matroska file load for the track, and `SubtitleConfig.fonts` adds your own.
  Apple and Windows find system fonts through CoreText and DirectWrite, while Android and Linux load
  a limited set of system font files.
- `setSubtitleSafeArea` keeps the built-in text out of a display cutout, rounded corners or a
  control bar.
- The caption look the viewer set for every app, under Accessibility on Android and Apple, reaches
  the player through `player.followSystemCaptionStyle(context)` on Android and
  `player.followSystemCaptionStyle()` on Apple: the text and background colours, the edge, the
  typeface on Android, and the text size as the subtitle scale, applied now and at every change
  until the handle closes. `systemSubtitleStyle` and `systemSubtitleScale` read them once for an
  app that applies them itself. On Android the style is null while the viewer has captions off.
  Nothing follows the settings unless the app asks, and a typeset ASS track keeps its own look
  (#679).

## libass

- `SubtitleConfig.typesetting = false` keeps the built-in Kotlin styling instead of libass.
  `PlayerSnapshot.subtitleTypesetter` says which engine draws.
- A typeset ASS track ignores `SubtitleStyleOverride`, while scale and position still apply. Only
  the primary track is typeset. A secondary track uses the built-in styling at the top of the
  picture.
- On the web, libass is a separate module (see the [web setup](../README.md#web-setup)). A browser
  has no system font, so supply fonts as attachments or through `SubtitleConfig.fonts`.
- On the web, image subtitles (PGS, DVB, DVD and XSUB) and the CEA-608 captions inside the picture
  (CC1) do not decode, because the web's FFmpeg has none of their decoders. The CEA-708 services
  are read in Kotlin, so they show there too.
- On the web, the browser's own text engine and fonts draw SubRip, WebVTT, TTML and the built-in
  ASS styling, on the page and in the worker player. See
  [the web section of TTML regions](subtitle-regions.md#the-web).
