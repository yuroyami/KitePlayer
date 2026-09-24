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
- The standard's ordinary range is 50 to 250 ms, as judgement rather than as a limit. Patterns can
  choose shorter edge smears or longer exposures. Ocean Mist keeps its live waveform outside the
  feedback buffer and exposes the background trail as a control; it also owns the optional stereo
  traces previously shown by Scope. `TrailHalfLifeTest` prints the catalogue's half lives and fails
  only on a trail that cannot survive one frame or that holds the picture for seconds.

## One journey through generated shapes

`Odyssey` is one distance field made of districts. Each district is a recipe: eight fold steps
applied to a point, ending in a box, a sphere or a cross. A step is one operation and three
numbers. The operations are mirror, box fold, sphere fold, plane fold, scale and shift, Menger
fold, rotate in the xy plane and rotate in the yz plane. A cavern repeats its shape around the
eye. A sculpture stands once in the middle of its district, with the sky visible around it.
Districts are adjacent, finite regions of one field, with physical doorways and shared lighting.
A ray can see the next district through the current one before the camera gets there. Changing
districts does not crossfade two pictures or reset the camera.

The host composes the recipes. Five templates (sponge, kaleidoscope, folded cavern, foam and
sculpture) fix most of their operations and give every number a range. A seed drawn at launch
picks the numbers. The next district mutates one to three steps of the current one, and after
three to five districts the composer jumps to the template shown least recently. Before a recipe
is shown, a Kotlin copy of the field samples it along the route, at the full fold depth and at the
lowest depth the detail control allows, and refuses a recipe that is solid, that is empty (too few
of its rays reach a surface), or that needs more march steps than the budget. The screening march
steps the way the shader steps, at rest and at full music. `OdysseyRecipeFieldTest` holds the
shader to that Kotlin copy on 200 random recipes at two fold depths. Two launches show two
different worlds; a fixed seed makes the tests repeat.

The shader holds no trigonometry. The host packs rotations as cosine and sine pairs and plane
folds as unit normals, and it moves the numbers with the music: bass pushes the mirror offsets,
hits breathe the scale steps and mids turn the rotations. Impact fronts breathe the primitive as
they travel. The step numbers reach the shader as uniform arrays read with unrolled loop indices,
which Android accepts; a dynamically indexed uniform array does not compile there.

Odyssey first searches a conservative depth grid at one quarter of the canvas width and height,
in one 112-step program that reads the field through a distance child compiled at that
resolution. A native-resolution pass starts behind the nearest neighbouring bound and resolves
every pixel with the adjustable 64..112 step budget, through a second instance of the child. Each
recipe carries its own march safety (how much of a distance estimate a ray may trust), and a
doorway takes the most cautious of the six districts around it. The final pass lights those hits
with a numerical normal of the whole scene and a glow from the fold trace: the chain records how
close a point came to a fold axis, and those seams shine on any shape. Colour is never upscaled
from the depth grid. Measured with the probe on a phone, one coarse program through the child was
20 to 30 percent faster than seven programs holding the field inline, and trigonometry inside the
fold steps cost two to four times the frame.

A recipe whose primitive does not reach its own cell wall makes the field overestimate the
distance near that wall (the field only knows the copy in the point's own cell), so the coarse
search steps into the neighbour's copy and loses depth at a banked view. The template ranges keep
the primitives fat for that reason, and `OdysseyWorldTest` checks the coarse depth against a
full-ray reference for every template, straight and banked.

`./scripts/check-odyssey-android.sh DEVICE_SERIAL` exports the shader strings and twelve composed
districts, compiles every program with the device's `RuntimeShader`, renders each district,
compares accelerated depth with a full-ray search, checks that music changes geometry with the
camera locked, and reports median frame times. Pass `DEVICE_SERIAL 1080 2400 30` for
phone-resolution timing. Images land under `kiteplayer-audioviz/build/odyssey-android-probe/`.
Neither the stills nor the timings certify musical response or sustained 60 FPS.

The flight follows an S-shaped route with audible thrust, banked lateral sweeps and changes in
pitch. Quiet passages slow almost to suspension. Busy audio raises thrust; delivered low/body
attacks add short acceleration and launch two bounded fronts through the surrounding structure.
Drops sustain a launch, and breakdowns release speed into a raised view. Motion is integrated in
seconds and settles between targets, without cuts or orbit resets. Held playback freezes flight
and stored impulses; reduced motion suppresses camera excursions and impact fronts.

The pattern exposes flight, lens, curvature, banking, camera sweeps, impact waves, recipe detail,
architecture response, lighting, colour, atmosphere, particles, draw distance and ray-step
controls. Recipe detail runs four to eight of a recipe's folds. World particles have depth and
are hidden by nearer surfaces.

For offline listening/viewing checks, `OdysseyPlaybackCaptureTest` accepts `ODYSSEY_PCM`, a path to
48 kHz mono float32 little-endian samples. It feeds the production analyser and delivered-event
cursor, skips three seconds of warmup, and saves up to 30 seconds of 384 x 240 frames at 12 FPS in
`build/odyssey-playback`. This is a CPU-rendered inspection clip, not a playback FPS measurement.
Normal test runs skip it when no PCM path is supplied. Force the selected test to rerun when only
the input file changes.

## Flat preset catalogue

`VizCatalog.create()` returns independent preset instances in one flat display order.
`AudioVizBrowser` shows that same order as a searchable adaptive preview grid. There are no
preset categories, category headers or grouped-catalogue entry points.

The category contract has been removed: custom `Visualization` implementations no longer declare
`family`; `VizFamily` and `VizCatalog.byFamily()` are no longer exposed. Clients that used those
APIs should use `VizCatalog.create()` and the preset's own `name`. This is an intentional source
and binary compatibility change. Musical energy metadata remains for the automatic director;
it is not a picker grouping.

## Continuous forms and landscape travel

The flat catalogue includes the Neon Lo-Fi musical landscape below. Its internal changes transform
geometry and scene weights without crossfading preset pictures.

| Catalogue entry | Forms inside it |
| --- | --- |
| Neon Lo-Fi | Highway, mountain pass, city and coast in one musical night drive |

Plasma also remains independently selectable. The other consumed forms have no separate catalogue
entries or duplicate implementations. Neon Lo-Fi uses its own `Scene` control, whose default is
`Auto`. Manual choices still transform continuously.

Pipe has no forms to choose. It is one shader: a tube of lit cells whose rings are past spectra.
At a section the camera cuts to another lane, and on the same frame the cross section starts to
turn round, square or six-sided over one cycle.

The shared journey director weights destinations by the music and recent visits. It waits between
changes and eases material weights with zero-velocity arrival. Delivered structural boundaries,
sustained feature contrast and accumulated musical activity can invite a new destination. A
confident, identity-matched boundary in the prestudied lookahead can start preparation 1.2 seconds
early. Continuous exploration is an artistic choice, not a claimed beat or section detection.
There is no fixed ordered loop. Pause freezes the journey, and reduced motion damps its travel.

Neon Lo-Fi replaces Terrain March. It is a drive at dusk: an indigo sky over a rose horizon, a gold
to coral sun, black hills and twelve black towers, and terraces with black faces and cyan to
magenta neon edges. One analytic shader draws the native-resolution sky, the sun, the prepared ridge
profiles, the road and the optional coast. Five bounded mesh batches supply the stars and the
shooting star, the towers and the world-anchored city, the opaque spectrum terraces and palms, the
lamp posts and rain, and the lamps' added light. There is no terrain height march, rolling terrain
tile, depth/refinement chain or underwater volume.

- The sun has eight gaps, one per pair of display lanes, bass at the bottom. A gap is as wide as its
  band is loud; silence leaves thin, even gaps.
- Each tower belongs to one note name, C to B from left to right, and its windows light while that
  note sounds.
- With a supported pulse the road passes one lane dash a beat, and so one lamp pair a bar. Without
  one, the mood sets the pace.
- A kick pulses the sun and the road's edges and flashes the nearest terrace edges. A snare sends a
  shooting star, one a bar at most, and a hat makes the stars twinkle.
- A breakdown brings rain and a wet road that mirrors the sun and the lamps, until the next section
  or drop. On the coast, the stripes of light on the sea are the waveform.
- A drop lifts the view off the road for one cycle: the horizon drops, the terraces spread out below
  like a lit map, and the sun stands whole. It lands on the first beat after that cycle. Under
  reduced motion the camera stays down and only the sun closes.
- The scene keeps 40 percent of its light in a silence, and the display transfer rolls bright light
  off on its brightest channel, so a bright colour keeps its hue.

Each shoulder has sixteen lanes over twelve depth rows and a protected 7.2-unit road corridor.
The complete input spectrum is area-aggregated into the display lanes with the shared analysis
gain. High frequencies are closest to the road and bass is outside, preventing tall bass cells
from concealing quieter lanes. Current levels raise the terraces, local change bends their
response, and delivered transient records add bounded regional crests and travelling edge marks.
Opaque tops, front faces and road-facing sides are submitted far to near, outer to inner. A
bounded anamorphic horizontal projection keeps the shoulders visible in a tall viewport;
the sun retains its circular angular presentation and explicit screen bounds.

The mountains remember heard music using a 64-band, 360-row ring sampled at 4 Hz of media time.
Rows carry timestamps and validity. Late attachment creates no invented past; gaps remain unknown,
presented silence records zero, and seek/revision changes start a fresh epoch. Two host-prepared
256-sample profiles represent the most recent 30 and 90 seconds. They place newer sound beside
the protected horizon opening and older sound toward the outside. Silence holds the last visible
ridge while measured zero rows continue recording. Camera speed never changes that history span.

Auto regions need a supported structural record or sustained contrast in several features, with
at least 60 audible seconds between transition starts. There is no timed exploration carousel.
A critically damped controller converges over approximately 8-16 seconds and preserves motion
when retargeted. Manual regions bypass the dwell only. Forward motion ranges from 6 to 24 artistic
units/second before controls, with bounded acceleration; Flight speed zero stops world progression.
Local bands, window groups, ribbons and attack cues continue working. Pause freezes the world,
history and finishing grain. Silence settles the foreground/travel within about three seconds.
Reduced motion scales travel, bank, bob, weather phase and travelling accents without removing
local spectrum response.

The thirteen parameters are Scene, Spectrum floor, Musical mountains, Brightness, Tape finish,
Journey pace, Flight speed, Sun size, Sky ribbons, Rain, Hit accents, Neon intensity and Vividness.
The existing Variation action changes a coherent route/layout gene. Browser instances copy both
the chosen parameters and this layout configuration, retaining independent histories and actors.
Searching for Terrain March finds the single Neon Lo-Fi entry; `VizDirector.startWith` resolves
that former name too. No external app's persisted settings format is assumed.

The CPU and shader share explicit palette roles and one linear-light exposure/tone transfer.
Default post is restrained bloom (0.25, radius 0.018, threshold 0.65), vignette 0.18, grain 0.006,
scanlines 0.05, no aberration and no glitch. Native cores remain visible with post off. Android
26-32/software backgrounds use a simpler Canvas sky, sun, history ridge, grid, road and coast;
the same musical world and foreground mesh remain active. API 26-28 uses eight fully aggregated
lanes per shoulder, five depth rows and smaller decoration pools, bounded below 1,500 triangles.
The portable path omits expensive post. Runtime views retain the existing sample 60 FPS cap and
browser cap of at most 15 FPS (or a lower configured cap).

Plasma's grid now has 28 cells across the shorter screen dimension by default, instead of broad
rotated strips based on the canvas diagonal. Its dots bend the field and the grid through the same
coordinate deformation. Brightness remains tone-mapped with the same palette and exposure in the
menu and selected view. The CPU fallback shares the field and distortion equations.

`JourneyMotionTest`, `JourneyRenderTest` and `JourneyCatalogTest` cover continuity, welded joints,
plane geometry, navigation clearance, musical momentum, lookahead, history, pause, reduced motion,
reset determinism, shader compilation, portrait output, fallback visibility and Plasma interaction.
`JourneyPlaybackCaptureTest` optionally accepts `JOURNEY_PCM` (48 kHz mono f32le), warms the real
analyser for three seconds, and makes 512 x 288 offline clips at 12 FPS. These directed demos select
forms every seven seconds so all forms can be reviewed in one clip; automatic destination choice
is tested separately. Neither host captures nor an APK build establish phone presentation FPS.

`NeonLoFiHistoryTest`, `NeonLoFiWorldTest`, `NeonLoFiMusicTest`, `NeonLoFiCatalogTest`,
`NeonLoFiRenderTest`, `NeonLoFiQualificationTest` and `NeonLoFiLightTest` cover the replacement's
clock/history, event accounting, scene policy, real Variation/preview transfer, fixed-camera
musical response, native phone-sized output, portable output, framing, bounded geometry,
finished light and host CPU cost. The light fixture uses decoded linear sRGB and a separate
saturated-red swing trace; it is a bounded fixture, not general photosensitivity certification.

`NeonLoFiCaptureTest` accepts `NEON_PCM_DIRECTORY` containing named 48 kHz mono f32le files and
optional comma-separated `NEON_SONGS`. It warms the production analyzer for three seconds, then
records twelve seconds at 15 FPS with travel stopped and moving. The first visual frame corresponds
to decoded input time 3.066666667 seconds, which the muxed audio must match. These are offline
inspection clips, not presentation FPS measurements. `scripts/check-neonlofi-android.sh` exports
and pushes the exact shader, textures and ordered mesh batches for an explicit offscreen device
run. Its blocking draw/readback duration includes synchronization and is not isolated GPU scene
cost or app cadence. Neither that script nor an APK build establishes ten-minute presentation
performance. Current implementation evidence and pending physical checks are indexed in
[`audioviz-revamp/neonlofi-implementation-report.md`](../audioviz-revamp/neonlofi-implementation-report.md).

## Marbled ink

`Marble` replaces `Reaction Diffusion` and `Smoke Rise`. It keeps its tray on the processor, in
grids of 160 by 90 cells: three inks, and the two chemicals of a Gray-Scott reaction that grows
lace inside the ink.

- The drums drop ink. A drop moves every point outside it outward, so older ink closes into rings
  round the new drop, as paint does on a marbling tray.
- Up to five point vortices stir the tray. Their flow has no divergence, so the ink keeps its area
  as it is combed.
- The inks and the chemicals are carried along the flow before each reaction step, so the swirls
  never smear into a blur.

A shader draws the result. It reads the grids smoothly and cuts each ink edge at half strength, one
pixel wide, from the local slope, so the edges are sharp curves at native resolution although the
grid is small. The same slope lights the ink from the upper left. Where runtime shaders cannot run,
the same grids are drawn stretched over the screen. Pause and silence freeze the tray.

Controls include zero, one or two black holes, influence size, travel speed, curl, attraction,
lensing and horizon absorption, plus smoke density, directional detail, brightness, embers,
emitters, wind, light path and ceiling. Setting the count to zero removes the added fluid effects.

Focused tests cover the absence of drawn black-hole objects, concentration outside the horizon
and absorption inside after the actual fluid solve, bending/fading embers, empty-field preservation,
control limits, musical pace, pause, reduced motion, display cadence, portrait output and reset.
The optional `SMOKE_RISE_PCM` path accepts 48 kHz mono f32le audio, warms the production analyser
for three seconds and captures up to twelve seconds through the composed renderer. It simulates
at 60 Hz and saves 640 x 360 images at 30 FPS. This host capture does not measure phone performance.
Force the selected test to rerun when only the PCM file changes.

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
