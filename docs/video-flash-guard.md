# Dimming flashing video

The contract for #500 and #561, built in the order at the end. Today the detector, the setting, the Compose
canvas renderer, the AWT canvas, the Metal renderer, the Core Graphics renderers on Apple, the
Android GL renderer, the Android software path and the web canvas carry it. Apple's sample buffer
layer and Android's direct MediaCodec surface draw as before.

Repeated bright flashes can trigger seizures in people with photosensitive epilepsy and discomfort
in many more. Since iOS 16.4 and macOS 13.3, Apple has a Dim Flashing Lights setting that
AVFoundation honours by dimming video while flashes repeat. A player of its own has to read the
setting and do the same. The flash guard is that: while a picture flashes more than three times in
a second over a large part of it, the renderer dims it, and lets it back once the flashing stops. A
frame outside such a run is never touched.

## Turning it on

```kotlin
public enum class FlashGuard { Off, On, FollowSystem }
```

- `KitePlayer.setFlashGuard(mode)` sets it, `PlayerSnapshot.flashGuard` reads it, and
  `PlayerConfig.flashGuard` gives the first value. The default is `FollowSystem`.
- `FollowSystem` follows the platform's own setting where there is one, which today is Apple's Dim
  Flashing Lights (`MADimFlashingLightsEnabled`, with its change notification). Where the platform
  has none it is the same as `Off`, so nothing changes for an application that sets nothing.
- `On` guards whatever the platform says, for an application with its own setting.
- The memento does not carry it: it is the viewer's preference on that device, not part of where
  playback was.

## What a flash is

The rule is the general flash rule of ITU-R BT.1702 and WCAG 2.2, in relative luminance:

- Each frame is measured as a grid of 16 by 9 cells, each the mean relative luminance of its part
  of the picture, in linear light with the BT.709 weights, from 0 for black to 1 for white.
- A cell moves when its luminance has gone 0.10 or more from its last extreme the other way, and the
  darker of the two is below 0.80. As in both rules, how fast it went does not matter: a fade in and
  out is a flash too, and only the count of them in a second makes a run.
- The picture has a leg on a frame where cells that moved the same way, on that frame or the one
  before, cover at least a quarter of the picture. That is BT.1702's share of the screen; WCAG's
  10 degree field covers about the same on a phone or a television at viewing distance.
- Two legs the opposite way are a flash. More than three flashes in any one second, which is a
  seventh leg within a second of the first, is a flashing run.

### Saturated red

Both rules also count a flash to or from a saturated red, which can move no luminance at all: a cut
between a full red and a blue of the same luminance, for example. The guard counts it by the red
flash rule of WCAG 2.2 (#561), on the same grid:

- A colour is a saturated red when `R / (R + G + B)` is 0.8 or more, in linear light. Each cell
  carries two more values: its mean red measure, which is `R - G - B` with a negative taken as zero,
  and the share of it that is a saturated red.
- A cell makes a red leg when its red measure has gone 20 of 320 or more from its last extreme the
  other way (0.0625 on the scale of 0 to 1), and at least half of the cell is a saturated red on one
  side of the change. A flash between two colours that are not a saturated red is not a red flash.
- The picture's red legs are counted as its luminance legs are, and apart from them: a quarter of
  the picture, and a seventh leg within a second starts a run.

## How it dims

- On the leg that makes the run, the guard dims the picture by a factor `k`, so that the largest
  leg of the run comes out at most 0.08, under the 0.10 that makes a leg. The swing is the mean
  change of the cells that moved. A renderer scales encoded values, and light goes about as their
  power of 2.2, so `k = (0.08 / swing)^(1 / 2.2)`. A black and white strobe comes out at about a
  third of its encoded level, which is 0.08 of its light.
- A red run dims the same way, to a red leg of at most 16 of 320, so `k = (0.05 / swing)^(1 / 2.2)`.
  A full red against a blue comes out at about a quarter of its encoded level. The guard dims a red
  run and does not lower its saturation: every renderer can scale the picture, and the two that
  only lay black over it (the AWT canvas and the web canvas) cannot change a colour. Dimming also
  brings a red and black strobe under both limits at once, which its luminance factor alone does
  not: at that factor its red measure would still move by about 120 of 320.
- A larger leg later in the run, of either kind, lowers `k` at once. While legs keep coming, `k` holds.
- One second after the last leg, `k` rises back to 1 over 1.5 seconds, slower than any leg, and is
  exactly 1 at the end, after which frames are drawn untouched again.
- The renderer applies `k` after the viewer's picture adjustments and before the gamma, by
  multiplying the adjustment's colour matrix, offsets included, by `k`. `VideoAdjustments` and
  what `PlayerSnapshot` publishes stay the viewer's own. Subtitles and other overlays are not
  dimmed.

## Where it measures

Each renderer measures the picture it is about to draw, before any adjustment, and runs one shared
detector, `VideoFlashGuard` in the core's `spi` package, so every renderer applies the same rule.

- A renderer that converts pixels on the CPU (the Compose canvas renderer, the AWT canvas, Core
  Graphics on Apple, the Android software path) samples the converted picture on a sparse lattice,
  four by four points in each cell, 2,304 reads a frame and never the whole frame. It measures a
  frame before drawing it, so the frame that completes a run is already dimmed. The same reads
  give the two red values. The Compose canvas
  renderer measures on its worker, hands each picture to the draw with its factor, and the draw
  folds the factor into the picture controls' colour filter (a MediaCodec picture on Android is
  dimmed earlier, in the GL blit, and the draw then applies the picture controls to it); outside a run it draws with the
  controls' own filter, or none, as before. The AWT canvas has no picture controls, so it lays black
  over the picture at `1 - k` before the cues, which leaves `k` of each encoded value under it, and
  a repaint of a held picture draws it at the factor it was shown with. The Core Graphics
  renderers, AppKit's and UIKit's, measure on their conversion worker and fold the factor into the
  matrix they apply to the bytes. The Android software path does the same with the colour matrix
  of its paint.
- A GPU renderer (Metal, Android GL) draws a reduced copy beside its adjustment pass and reads it
  back when the next picture is about to draw, so it dims one frame late. The copy is 64 by 36
  texels, one for each point of the lattice above, so the detector reads the same points as on a
  CPU renderer. The copy holds the picture's colour, so the red values come from it too. An HDR picture is measured as it looks tone mapped to standard range. The Metal
  renderer draws the copy in the same commands as the picture, so the planes are uploaded once. On
  its extended-range layer the picture is light, so it scales by `k` to the power of 2.2. A picture
  drawn again while paused is not measured again and keeps the factor it was shown with. The
  Android GL renderer has an OpenGL ES 2 context, with no way to read back later, so it draws the
  copy first and reads it at once: the picture that completes a run is already dimmed. That read
  took 0.8 to 1.0 ms a picture on an ASUS ROG Phone 9.
- The web canvas measures the picture it has staged, at the same points, in one JavaScript loop,
  so no pixel enters Kotlin memory, and the 432 values cross to the detector in one call. It has no
  picture controls, so it dims as the AWT canvas does: black over the picture at `1 - k`, under the
  cues, with the bars left as they were. A held picture drawn again after a resize keeps its
  factor.
- A renderer that never holds the pixels (Apple's sample buffer layer and Android's direct
  MediaCodec surface) has no guard.

The engine tells each renderer the mode through `VideoRenderer.setFlashGuard(mode)`, defaulted to
do nothing, on attach and on every change, as it does the adjustments. A change of mode, and taking
the picture off, start a renderer's history afresh. Only an Apple renderer reads the system setting
for `FollowSystem`, so on the Compose canvas renderer, the AWT canvas and the web canvas, which
cannot, `FollowSystem` is off. The Apple renderers look the setting up by name at run time, so an application still loads
on a system older than the setting, where `FollowSystem` is off.

## Order of work

1. This contract. Done.
2. The detector, the setting and the engine's part, with the detector's tests. Done.
3. The Compose canvas renderer, tested on the JVM with a strobe. Done for the pictures it converts;
   its Android hardware tier comes with step 6.
4. The AWT canvas, the desktop default. Done.
5. Metal and the Apple setting, on a Mac. Done, tested offscreen on a Mac; the check by eye on a
   Mac and on an iPhone is owed. Core Graphics is done too, tested on a Mac and on the iOS
   simulator.
6. Android GL and the Android software path. Done: the GL half is tested on an ASUS ROG Phone 9
   and the software path on the host. The Compose renderer's hardware tier is the same GL renderer:
   the Compose renderer sends the mode on to it. On the phone a 5 Hz strobe played through
   MediaCodec reads 81 of 255 at the centre of the screen once its run has started.
7. The web canvas. Done, tested in Node and in headless Chrome, where a white picture of a 5 Hz
   strobe reads 81 of 255 on a real canvas once its run has started.
8. Saturated red flashes (#561). Done on every renderer above: the detector takes two more values
   for each cell, and each renderer measures them where it measures the luminance. Tested on the
   detector, on five renderers on the host and a Mac, in Node and headless Chrome, and through the
   GL path on an ASUS ROG Phone 9.

## Tests

- The detector: a full-picture strobe at 5 Hz runs undimmed for three flashes, then holds every
  output leg under 0.10 from its seventh leg; three flashes in a second are never touched; a fade
  out and in is one flash; a strobe over a fifth of the picture is not a run; a strobe between two
  bright greys, above 0.80, is not a run; and after a run the factor returns to exactly 1.
- The red rule: a 5 Hz strobe between a full red and a blue of the same luminance, which the
  luminance rule does not see, starts a run on its seventh leg and every red leg then comes out
  under 20 of 320; a red and black strobe is dimmed further than for its luminance; a steady red
  picture, three red flashes in a second, a red strobe over a fifth of the picture, and a strobe
  between an orange and a grey are never touched.
- The setting: `setFlashGuard` reaches an attached renderer and one attached later, and the
  snapshot reads it back.
- Each renderer: with the guard on, a strobe's drawn legs stay under 0.10 after the run starts, and
  a picture that does not flash is drawn exactly as without the guard.
