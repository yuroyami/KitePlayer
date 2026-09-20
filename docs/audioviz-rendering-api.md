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
the finishing pass on, and counts the flashes in the composed frames. The fixture is 200 beats a
minute with hits on the half beat, plus the ordinary drum loop, and each drawing is rendered again
with reduced motion on, which must leave no flash at all. `FlashGuardTest` fixes the runtime
guard's own counter against known pass and fail signals.

It counts by area, the way a flash analyser does. Each frame is compared with the picture 0.3
seconds earlier, which is the time a flash takes at the policy's own rate. Where at least a tenth
of the picture has moved by at least 0.10 in the same direction, that is a leg, and a pair of
opposing legs is one flash. A tenth of the picture is well below the quarter of a ten degree field
that WCAG would excuse, which is what refusing the area exception means in practice.

`FlashCounterTest` is what makes that number worth anything. It feeds the counter pictures whose
answer is known: whole screen alternations at six, three, two and one a second, an alternation
where both states are too bright to count, one whose step is too small, one over a twentieth of
the screen and one over a fifth, a fade over four seconds, and a still picture. The counter has to
report the rate it was given, and nothing at all for the rest.

Three earlier versions of this counter each gave a different verdict on the catalogue, and none of
them had been measured against anything. The first averaged the whole frame, which reads a drawing
whose bars grow on the beat as a flash and called seventeen drawings unsafe. The second compared
each frame with the last turning point, which cannot tell a jump after a long rest from a slow
drift, and called sixty-five unsafe. Brightness changes were made against the first reading and
then taken back out. Calibrate the counter before trusting it about a drawing.

**What it cannot see, measured.** A drawing draws its own bright marks, and the guard reads only
the shared light, so a picture that flashes through what it draws passes the guard. Seven of the
seventy-eight cross the policy in the captured output: Pipe at eight flashes in its busiest second,
then Hex Shaft, Ocean Mist, Wormhole, Ring Flight and Cathedral at five or six, and Pulse at four,
against a limit of three. Four of them fly down a tunnel, where the walls brighten together as the
camera surges on the beat. Damping the kick's light rib, which was the obvious suspect, moved Pipe
from eight to seven and nothing else, so it was taken back out: bringing these seven inside the
policy is a change to how they look, and the capture test is the tool to check any such change.

The test also prints the widest area that swung together for every drawing, so one that is close to
the limit is visible before it crosses it.

**Also not covered.** A change of drawing composes two scenes, and the capture test renders one at
a time. `StrobeCut` alternates the two scenes about four times inside one change, so it is out of
every default profile; it stays in `VizTransition` for a caller that asks for it by name and owns
that decision.

## Reduced motion, and turning the picture off

`AudioVizState.reducedMotion` keeps the spectrum, the colours and the shapes, and damps what throws
the picture about: the camera's punch, shake, roll and cuts, the trail's swim, and the flash limit
drops to none at all. Every change of drawing becomes a plain fade. The camera still wanders
slowly, because a still picture with a moving spectrum in it reads as broken rather than as calm.

It also holds back the hits themselves, to about a third of their strength. Without that the
setting kept none of its promise about flashes: a drawing flashes mostly through what it draws on a
hit, not through the light it is given, and the captured output with reduced motion on was
identical to the output without it. The hits are the one lever that reaches every drawing without
each of them knowing about the setting.

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
