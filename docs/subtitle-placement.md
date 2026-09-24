# Subtitle placement

This page is the one rule for where subtitles land. It covers every renderer and both subtitle
engines. A renderer or a view that places subtitles another way has a bug.

## Terms

- **Output**: the surface that a renderer draws into, for example a window, a view, a layer or a
  canvas.
- **Picture rectangle**: the part of the output that the video covers. Under `VideoScale.Fit`, a
  picture with another shape than the output leaves black bars: a letterbox has bars above and
  below, a pillarbox has bars left and right.
- **Overlay**: the subtitle images that the engine gives a renderer through
  `VideoRenderer.setOverlay`. A `SubtitleOverlay` carries the size that it was laid out for,
  `viewportWidth` by `viewportHeight`.
- **Safe area**: the part of the output that subtitles must stay inside. The application sets it.
- **Kotlin tier**: the built-in subtitle drawing. The engine gives text cues to the platform
  `SubtitleRasterizer`. SubRip and WebVTT use it, and so does ASS when libass is absent or turned
  off.
- **Typesetter**: `kiteplayer-libass`, which draws ASS and SSA tracks when it is installed.

## Rule 1: the overlay covers the whole output

A renderer maps the overlay's viewport onto its whole output. The overlay point (x, y) lands on
the output at:

```
outputX = x * outputWidth / viewportWidth
outputY = y * outputHeight / viewportHeight
```

Each axis scales on its own, and image sizes scale the same way.

- The overlay is not mapped into the picture rectangle.
- The overlay does not turn when the picture carries a rotation.
- The scale mode, the aspect override, zoom and pan move the picture only. They do not move, crop
  or scale the overlay.
- The overlay draws above the picture and above the bars, so a cue can sit in a bar.

## Rule 2: the engine lays out for the output size

Each time the engine lays out subtitles, it reads `VideoRenderer.outputSize`.

- A renderer that knows its output size reports it in physical pixels. The engine lays the overlay
  out for exactly that size. The scale in rule 1 is then 1, and text is drawn pixel for pixel.
- A renderer that cannot know its output size reports null. The engine then lays out for the
  picture's display size, turned upright when the video carries a quarter turn. With no picture,
  the engine lays out for 1280 by 720 pixels.
- A renderer that draws bars of its own must report its output size. If it reports null, rule 1
  stretches a picture-shaped overlay across an output of another shape.

The Core Graphics renderers draw the picture into an image of the picture's own size, and a
platform view fits that image into the window. The output of these renderers is that image. They
report null, and their subtitles stay on the picture.

## Rule 3: the safe area

`KitePlayer.setSubtitleSafeArea(value: SubtitleSafeArea)` sets the safe area.
`SubtitleSafeArea(left, top, right, bottom)` holds four insets, each a fraction of the output:
`left` and `right` of its width, `top` and `bottom` of its height. Each inset is from 0 to 0.45, so
at least a tenth of the output stays for text. `SubtitleSafeArea.None`, the default, has no inset.

Use it to keep text out of a display cutout, rounded corners, the overscan of a television, or a
control bar that is drawn over the video. The insets are fractions and not pixels, so they mean the
same on every renderer, whatever the pixel density of its output. Set them again when the output
changes shape, for example after a rotation.

- The Kotlin tier lays text out inside the safe area as if the safe area were the whole output.
  Text size, margins, alignment, the bottom stack, the viewer's position setting and positions
  set by the author all measure against the safe area. The engine then moves the images by the
  left and top insets. The overlay still covers the whole output, as rule 1 says.
- A typeset track keeps its author's placement, and the safe area does not move its events. The
  viewer's position setting, `setSubtitlePosition`, still lifts its default line.
- A screenshot from `captureFrame` uses no safe area. Its subtitles are laid out for the captured
  picture.

## Rule 4: a picture and an output of different shapes

- The Kotlin tier measures against the safe area of the output. The picture rectangle has no part
  in it. The bottom stack sits at the bottom of the safe area, which is in the bottom bar of a
  letterbox. A position that the author set is a fraction of the safe area.
- The typesetter measures against the picture rectangle, as an ASS author expects. It places
  events against the picture, and only an event that its author positioned there reaches a bar.
  Under `Fill` and `Stretch`, the typesetter uses the whole output as the picture. Zoom and pan do
  not move typeset text.
- A bitmap cue keeps its authored pixel size. Its origin maps from its authored canvas onto the
  area that the Kotlin tier lays out in, each axis on its own.

## Rule 5: when the output changes size

- The engine compares the output size each time it lays out subtitles. When the size changed, it
  lays the overlay out again for the new size.
- Until then, the renderer draws the old overlay by rule 1. Text keeps its place relative to the
  output. For that moment it can be soft or stretched.
- In the Kotlin tier, text size is a fraction of the height of the area that the text is laid out
  in. A cue with no authored size is one twentieth of that height, times the viewer's scale from
  `setSubtitleScale`. An authored size scales by that height over the height the cue was authored
  for. A change of height changes the text size. A change of width changes only where lines break.
- The typesetter sizes text against the picture, so typeset text follows the picture's size.

## What each renderer does

| Renderer | Its output | Reports its size |
|---|---|---|
| `MetalVideoRenderer` | the layer's drawable, or the viewport that its host sets | yes |
| `KiteVideo`, the Compose video composable | the composable | yes |
| `AwtCanvasVideoRenderer` | the canvas | yes, in device pixels |
| `WebCanvasVideoRenderer` | the canvas | yes, in device pixels, from `setViewport` or the canvas's own size |
| `AndroidSurfaceVideoRenderer`, drawing subtitles itself | the Surface | yes, from its first frame |
| `KitePlayerView` on Android | the view; a separate layer over the whole view draws the subtitles | yes, the view gives its size to the renderer through `setViewport` |
| `AppKitVideoRenderer`, `UIKitVideoRenderer` | the picture's own image | no, so subtitles stay on the picture |

## How this is checked

`OverlayPlacementContractTest` holds rules 1 and 2 as tests. It lives in
`kiteplayer-core/src/commonTest`. Each module that draws overlays keeps a copy, because Kotlin
Multiplatform shares no test code between modules, so change every copy in the same commit. Each
renderer and view runs the contract through a subclass of its own:

- the AWT renderer, in `kiteplayer-output` `jvmTest`
- the web canvas renderer, through a canvas that records each draw, in `kiteplayer-output`
  `wasmJsTest`, in Node and in a browser
- the Core Graphics renderer and the placement function of the Metal renderer, in
  `kiteplayer-output` `macosArm64Test`
- the Android surface renderer, through a canvas that records each draw, in `kiteplayer-output`
  `androidHostTest`
- the subtitle layer of `KitePlayerView`, in `kiteplayer-view` `androidHostTest`

The engine half of rules 2, 3 and 5 is in `PlaybackSubtitleTest` and `SubtitleTypesettingTest` in
`kiteplayer-core`.
