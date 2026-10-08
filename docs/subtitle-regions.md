# TTML regions

The contract for the TTML and DFXP half of #492. TTML and DFXP files load as subtitle tracks and
are drawn in their regions on the desktop, Android, Apple and the web.

TTML is the subtitle format broadcasters and streaming services hand out, and DFXP is its older
name. Unlike SubRip or WebVTT, a TTML file places its text in regions: boxes on the screen with a
size, padding, a background, and a rule for where the text sits inside them. Two paragraphs shown
together in one region flow one under the other rather than overlap. Mapping each paragraph to one
positioned cue would lose all of that, so the cue model gains a region.

## Reading a file

- `TtmlParser` in `kiteplayer-subtitles`, beside the other readers, reads TTML 1 and DFXP and
  returns `SubtitleCue.Text` cues. `TtmlParser.isTtml(text)` recognises a document by its content:
  the root element is `tt` in one of the TTML namespaces, `http://www.w3.org/ns/ttml`,
  `http://www.w3.org/2006/10/ttaf1` or `http://www.w3.org/2006/04/ttaf1`. A file name is never the
  deciding word.
- The FFmpeg backend's external file parser asks `isTtml` first, so a `.xml`, `.ttml`, `.dfxp` or
  misnamed file reads the same way. TTML parses on every target, the web included.
- Each `p` element is one cue, from its begin to its end, as the reader times them today: clock
  and offset times, frames and ticks, times counting from the parent, and an end no later than the
  parent's. A `timeContainer="seq"` parent now times its children one after another.

## The model

The additions are optional fields with defaults, so every cue that exists today is unchanged and
every exhaustive `when` over the cue types still compiles. Source compatible; the constructor and
`copy` of `CueLayout` gain two parameters, as they did for #499.

```kotlin
public data class CueLayout(
    // ... the fields of today, then:
    val region: CueRegion? = null,
    val regionOrder: Int = 0,
)

public data class CueRegion(
    val id: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val padding: CueInsets = CueInsets.None,
    val displayAlign: CueDisplayAlign = CueDisplayAlign.Before,
    val backgroundColor: Int = 0x00000000,
    val showBackground: CueShowBackground = CueShowBackground.Always,
    val clip: Boolean = true,
)

public data class CueInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    public companion object { public val None: CueInsets }
}

public enum class CueDisplayAlign { Before, Center, After }
public enum class CueShowBackground { Always, WhenActive }
```

- A region is a value: two cues are in the same region when their regions are equal. `id` is the
  region's name in its document, so two regions with the same box stay apart.
- `left`, `top`, `width` and `height` are fractions of the area subtitles lay out in, which is the
  output less the safe area, as every authored position already is (docs/subtitle-placement.md,
  rules 3 and 4).
- `padding` is inside the region's edges: `left` and `right` are fractions of the region's width,
  `top` and `bottom` of its height. That is how TTML reads a percentage padding.
- `displayAlign` places the block of paragraphs between the padded top and bottom: at the top, the
  middle or the bottom.
- `backgroundColor` is ARGB with straight alpha, as every cue colour is. A transparent one draws
  nothing. `Always` draws it while the region is active, with or without text; `WhenActive` only
  while the region shows text.
- `clip` cuts off what flows past the region's outer edges, TTML's `overflow="hidden"`, which is
  its default.
- `regionOrder` is the paragraph's place in the document. Paragraphs of one region flow in that
  order, whatever order their times put them in.
- The horizontal part of `CueLayout.alignment` is the text's alignment inside the region, with
  `start` and `end` already turned into left or right by the paragraph's direction. Its vertical
  part means nothing for a cue with a region.
- A region with a visible background and `Always` is active for its own times, or for the whole
  document when it has none. The reader gives that time a cue of its own with no text, so the
  background shows while no paragraph does.

## How a region is laid out

All three built-in rasterizers place cues through one shared loop in `kiteplayer-output`, so this
is written once there and holds on the desktop, Android and Apple alike.

- The cues on screen that share a region are drawn as one group, at the place of the first of them.
  The region's background comes first, under its text.
- Each paragraph breaks its lines at the width inside the padding, and is aligned in that width as
  its alignment says. The paragraphs stack in `regionOrder`, each starting where the one above it
  ended, and the block sits at the top, middle or bottom of the space inside the padding.
- With `clip`, every image of the group is cut to the region's outer box. A block taller than its
  region therefore loses its bottom lines when aligned to the top and its top lines when aligned
  to the bottom.
- A cue with a region is placed by its author. It never stands in the implicit bottom stack, the
  viewer's subtitle position does not move it, and the viewer's size setting still scales its text.
- A secondary subtitle track drops its regions and goes where the viewer put it (#494).

A cue without a region is laid out exactly as today, which keeps every SubRip, WebVTT and ASS file
where it was. So does a TTML paragraph with no region: it goes to the bottom of the picture like a
SubRip line, aligned as its `textAlign` says, and centred like a SubRip line when nothing sets one.

## What the reader keeps

- Text, line breaks (`br`, and newlines under `xml:space="preserve"`), and the styles of each span:
  `color`, `fontFamily` (its first named family; a generic one such as `sansSerif` is the
  player's own), `fontSize`, `fontStyle`, `fontWeight`, `textDecoration` (underline and
  line-through), the colour of `textOutline` or its `none`, and the `backgroundColor` of a span or
  its paragraph, which every rasterizer takes from a cue's first span. The text keeps the player's
  outline and shadow unless `textOutline` says otherwise, because TTML's own default of none is
  unreadable over a bright picture.
- Region styles: `origin`, `extent`, `padding`, `displayAlign`, `backgroundColor`,
  `showBackground`, `overflow` and `zIndex`, which becomes the cue's `layer`. The styles a region
  sets for its text, such as `color` or `textAlign`, are inherited by the paragraphs flowed into it,
  before the paragraph's own.
- `textAlign` and `direction` set the horizontal alignment. `wrapOption="noWrap"` is `CueWrap.Never`.
- Lengths in percent, in cells (`ttp:cellResolution`, 32 by 15 by default), and in pixels when the
  root `tt` element states its `tts:extent` in pixels. A font size in cells or pixels becomes a
  size against the authored height, and a percentage or `em` a factor on the parent's size. A paragraph
  with no font size keeps the player's own size, so a file that never sets one reads like every
  other format.
- A region whose box cannot be resolved, such as one in pixels with no root extent, is not used,
  and its paragraphs go to the bottom like those with no region.

Not drawn, and named here so their absence is a decision rather than a surprise: vertical writing
modes, which are laid out horizontally; ruby and text emphasis; `opacity`; `lineHeight`;
`linePadding` and `multiRowAlign`; animation with `set`; images; a background behind a span
other than the first of its paragraph, or behind a `body` or `div`; and an outline's thickness. A
paragraph's base direction follows the platform's own bidi of its text.

## The network path

DASH sidecar and `stpp` subtitles and HLS TTML keep their behaviour: their TTML still becomes
WebVTT text with italic, bold, underline and line breaks, which FFmpeg's WebVTT reader plays. The
region model does not cross that conversion, and this change does not claim to fix regional
placement there.

## Where the reader lives

The bounded XML reader and the TTML reader move from `kiteplayer-network` into
`kiteplayer-subtitles`, and the network module depends on the subtitles module to use them. The
XML reader is shared between modules through a new opt-in annotation in the core,
`KitePlayerInternalApi`: public so that KitePlayer's own modules can reach it, an error to use
without opting in, and with no compatibility promise, because those modules always ship together.
The DASH manifest parser still throws the network module's public `XmlException`, with the same
message and offset.

## The web

The web output draws text cues of every format with the browser's own text engine, through a 2D
canvas (#559). It uses an `OffscreenCanvas` where there is one, so it also works in the worker
player. It goes through the same shared loop as the other rasterizers, so a cue and a region land
at the same place as on the desktop.

Three things differ from the other platforms, because a canvas draws text and does not lay it out:

- Line breaking is the rasterizer's own. Lines break at spaces, and between the words that
  `Intl.Segmenter` finds in scripts that write no spaces, such as Chinese, Japanese and Thai. A
  word wider than the line is cut between characters.
- A line of several styled spans is drawn span by span. A line that starts with a right-to-left
  character is filled from its right end. Text that mixes directions inside one span is ordered by
  the browser.
- Underline and line-through are drawn as bars, because a canvas has no text decoration.

Node has no canvas, so the rasterizer is absent there and no text cue is drawn.

## Order of work

1. This contract.
2. The annotation, and the XML reader moved into `kiteplayer-subtitles`, with the network module
   using it and its tests unchanged. Done.
3. The model, `TtmlParser` in `kiteplayer-subtitles` with its cues' regions and styles, the network
   module's WebVTT conversion built on it in place of its own TTML reader, and the external file
   path. Done.
4. Region layout in the shared rasterizer loop. Done, and tested with real text on the desktop
   rasterizer, on CoreText on a Mac and on the Android rasterizer on an emulator.
5. A browser raster path for text cues, regions included. Done in #559, for every text format,
   and tested with real text in a headless browser.

## Tests

- The reader: a TTML and a DFXP document give their paragraphs with the right times, text, styles,
  regions and document order; `seq` times children one after another; a region in pixels with no
  root extent is dropped; a document in another namespace is not TTML.
- The file path: a TTML and a DFXP file load as a track through the FFmpeg backend and show their
  first cue at its time, on the JVM and on a native target; the formats already added keep their
  tests.
- The layout: two paragraphs in one region stack rather than overlap, in document order; the block
  sits at the top, middle or bottom of the region as `displayAlign` says; padding moves the text in
  by a share of the region; a block taller than its region is cut at the region's edge with `clip`
  and not without it; a region's background shows under its text, and alone while `Always`; a cue
  without a region lands where it did before.
- The network module's TTML and DASH tests stay green unchanged.
