# Dimming flashing video

The contract for #500. The code follows in separate changes, in the order at the end.

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
  darker of the two is below 0.80. A move slower than 0.3 s from that extreme is a fade, not a flash
  leg, and only resets the extreme.
- The picture has a leg on a frame where cells that moved the same way, on that frame or the one
  before, cover at least a quarter of the picture. That is BT.1702's share of the screen; WCAG's
  10 degree field covers about the same on a phone or a television at viewing distance.
- Two legs the opposite way are a flash. More than three flashes in any one second, which is a
  seventh leg within a second of the first, is a flashing run.

Saturated red flashes, which both rules also count, are not detected yet; the grid carries no
colour. That is a later step.

## How it dims

- On the leg that makes the run, the guard dims the picture by a factor `k`, so that the largest
  leg of the run comes out at most 0.08, under the 0.10 that makes a leg: `k = 0.08 / swing`, where
  the swing is the mean change of the cells that moved. A black and white strobe comes out at a
  little under a tenth of its light.
- A larger leg later in the run lowers `k` at once. While legs keep coming, `k` holds.
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
  frame before drawing it, so the frame that completes a run is already dimmed.
- A GPU renderer (Metal, Android GL) draws a 16 by 9 reduced copy beside its adjustment pass and
  reads it back asynchronously, so it dims one frame late.
- A renderer that draws no adjustments at all (the web canvas, Apple's sample buffer layer and
  Android's direct MediaCodec surface) has no guard until it has an adjustment stage.

The engine tells each renderer the mode through `VideoRenderer.setFlashGuard(mode)`, defaulted to
do nothing, on attach and on every change, as it does the adjustments. Only an Apple renderer reads
the system setting for `FollowSystem`.

## Order of work

1. This contract.
2. The detector, the setting and the engine's part, with the detector's tests.
3. The Compose canvas renderer, tested on the JVM with a strobe.
4. The AWT canvas, the desktop default.
5. Metal and the Apple setting, on a Mac, then Core Graphics.
6. Android GL and the Android software path, on a device.
7. The web canvas, once it has an adjustment stage.

## Tests

- The detector: a full-picture strobe at 5 Hz runs undimmed for three flashes, then holds every
  output leg under 0.10 from its seventh leg; three flashes in a second are never touched; a fade
  over half a second is not a flash; a strobe over a fifth of the picture is not a run; and after a
  run the factor returns to exactly 1.
- The setting: `setFlashGuard` reaches an attached renderer and one attached later, and the
  snapshot reads it back.
- Each renderer: with the guard on, a strobe's drawn legs stay under 0.10 after the run starts, and
  a picture that does not flash is drawn exactly as without the guard.
