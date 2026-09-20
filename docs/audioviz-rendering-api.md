# Rendering, motion and flash limits

This contract implements "Rendering and accessibility" and "Flash limits" in the
[audio visualiser standard](audioviz-standard.md). It says how a trail fades, what holds the
finished picture to the flash policy, and what a viewer can turn down or turn off.

## A trail is a half life

A drawing declares its trail as the share of the picture that survives one sixtieth of a second,
which is how the drawings were authored. `Visualization.trailHalfLifeAt(mood)` gives the same
number as a time, and the surface fades by `2^(-dt / half life)`.

- At sixty frames a second nothing changes. At any other rate the drawing now fades at the same
  rate in seconds. Before this, a 120 Hz screen held every trail twice as long as a 60 Hz one.
- The zoom, spin and drift of the trail were already applied per second rather than per frame.
- The standard's ordinary range is 50 to 250 ms, as judgement rather than as a limit. Measured,
  this catalogue sits mostly below it: many drawings are between 11 and 35 ms, which is a smear on
  a moving edge rather than a trail, and that is what those drawings were drawn for. Two sit far
  above it on purpose, Scope at 571 ms being the longest. `TrailHalfLifeTest` prints all of them
  and fails only on a trail that cannot survive one frame or that holds the picture for seconds.

## The flash guard

`FlashGuard` counts flashes the way WCAG 2.2 defines them: a pair of opposing changes in relative
luminance of at least 0.10, where the darker of the two states is below 0.80. A red flash is the
same pattern in saturated red. The project's policy is stricter than WCAG's: at most three in any
rolling second, with no area exception, and no saturated red flashing at all.

The guard scales the light every drawing is told to give, through `VizRenderState.lightScale`. A
held frame is dimmer, not dropped, so the picture keeps moving, and because every drawing's
brightness follows that one value the guard acts on what the drawings draw.

It does not dim the finished frame. That was tried and it made matters worse: dimming a composed
picture by how far a guess at its brightness overshot adds a swing of its own, and the captured
output showed more flashes with that in place than without it.

**What it watches.** The light the drawings are told to give, which is what every drawing's
brightness now follows. It does not read the composed frame back: a full frame readback every frame
is what the standard tells us not to do in the live path.

**What it leaves alone.** A change that takes longer than 0.3 seconds to cross the step is a fade,
not a flash, and passes untouched. Without that rule a guard that is already holding would stop a
slow fade halfway, which is worse than the flash it was built to prevent.

**What checks the rest.** `FlashCaptureTest` renders every drawing through the same surface, with
the finishing pass on, and runs the composed frames through the same counter. The fixture is 200
beats a minute with hits on the half beat, plus the ordinary drum loop. `FlashGuardTest` fixes the
counter itself against known pass and fail signals.

**What it cannot see, measured.** Seventeen of the seventy-eight drawings pulse the whole picture
on every beat hard enough to cross the policy at 200 beats a minute: the capture test counts four
to six flashes in their busiest second, against a limit of three. Their pulse is drawn into the
picture rather than taken from the shared light, so the guard cannot see it without reading the
finished frame back, which the standard tells us not to do in the live path. The capture test
prints the largest swing of each drawing as well as the count, and the two together say which is
which: Stable Fluids swings 84 percent of the range and flashes none, because it swings slowly,
while Hex Shaft swings 56 percent at beat rate and flashes six times. Bringing those seventeen
inside the policy means reducing how much of the whole picture their beat brightens, which changes
how they look, so it is a decision for the owner.

**Also not covered.** A change of drawing composes two scenes, and the capture test renders one at
a time. `StrobeCut` alternates the two scenes about four times inside one change, so it is out of
every default profile; it stays in `VizTransition` for a caller that asks for it by name and owns
that decision.

## Reduced motion, and turning the picture off

`AudioVizState.reducedMotion` keeps the spectrum, the colours and the shapes, and damps what throws
the picture about: the camera's punch, shake, roll and cuts, the trail's swim, and the flash limit
drops to none at all. Every change of drawing becomes a plain fade. The camera still wanders
slowly, because a still picture with a moving spectrum in it reads as broken rather than as calm.

`AudioVizState.visible` draws the background alone. The analysis keeps running and the player keeps
playing, so a viewer who turns the picture off keeps the music.

Both reach a drawing as `VizRenderState.motionScale`, so a drawing written outside this repository
gets the same setting without knowing about it.

## Still open

- **Glow is on by default.** The standard says glow is an additional layer, off unless the drawing
  asks for it. Every drawing inherits a bloom of 0.8 through `PostSpec.Default`. Changing that
  changes the look of most of the catalogue, so it is a decision for the owner rather than a
  mechanical fix.
- **No evidence of the destination backend.** A shader compiled by Skia does not prove its
  destination is the graphics card. That needs a device.
- **The governor owns no total budget.** Drawings declare what they can give up through
  `VizQualityControl`, and the quality control shrinks the echo buffer, but there is no
  surface-wide budget across two scenes, glow, history and the interface.
- **Android 26 to 32 and context loss** are unqualified. Both need a device.
