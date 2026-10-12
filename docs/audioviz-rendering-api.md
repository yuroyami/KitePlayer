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

## Odyssey, a ringed planet

`Odyssey` is a giant planet with a ring, drawn by one shader with exact sphere and plane tests:
each pixel's line of sight meets the planet, the ring plane, the two moons, or nothing.

- The planet always overhangs the frame by about a third, in landscape and in portrait.
- Each stripe of latitude is one band's recent past, twelve seconds of it. The newest column is
  written at the longitude facing the camera and streams away from it on both sides, so "now"
  is always in view and the oldest columns meet behind the planet.
- The planet's weather is the music. A kick breeds a storm: an eddy dropped into a cloud memory
  field of 96 by 48 cells at the stripe of the loudest low band. The storm shows as a swirl with
  slope lighting, drifts with its stripe and fades over seconds. A snare strikes lightning on the
  night side. A hat lights an aurora at the poles. The terminator softens with the energy.
- The ring has 64 ringlets, one per band now. The ring's shadow lies on the clouds and the
  planet's shadow lies across the ring. A kick also sends a ripple out through the ring.
- The ring takes the palette's brightest end. Under the default palette it stays ice blue.
- The camera orbits and never cuts. The four views are its targets: Day side, Ring plane and
  Night side in turn at each morph, over at least one cycle, and Shadow on a breakdown. On top of
  the target, the disc moves on a small ellipse at a speed from the drive. Its distance breathes
  on the slow cycle and its axis tilts with the walk. The form is published through
  `Visualization.forms`. A birth sends a comet across the sky.
- On a drop the sun rises over the planet's limb over one beat, after a short flare. When the audio
  ahead already holds the drop, the sun sets behind the planet just before it.
- Light follows the level. The planet keeps 35 percent of its light in a silence, and the night
  side is lifted to about four percent by light off the ring.

## Flat preset catalogue

`VizCatalog.create()` returns independent preset instances in one flat display order.
`AudioVizBrowser` shows that same order as a searchable adaptive preview grid. There are no
preset categories, category headers or grouped-catalogue entry points.

The catalogue has 20 drawings on desktop, iOS and Android 13 and newer. On older Android it has 17,
because Alchemy, Pipe and Odyssey need runtime shaders and have no stand-in. The first
13 are the library's own: Bars, Ocean Mist, Twin Bloom,
Contour, Alchemy, Kaleidoscope, Pipe, Thin Ice, Fluctus, Glitch, Odyssey, Night Drive
and Marble. The last 7 are ports of browser visualisers, each faithful to its original and credited
below. Each port's class documentation carries the full credit and a list of every way it differs
from the original.

| Drawing | Original | Author | Where | Licence as found |
| --- | --- | --- | --- | --- |
| Silk | Silk (codevember, day 21) | Matt DesLauriers | [page](http://mattdesl.github.io/codevember/21.html), [code](https://github.com/mattdesl/codevember) | MIT |
| Lines | Lines | Silvio Paganini (FLUUUID) | [page](https://labs.fluuu.id/lines/), [code](https://github.com/fluuuid/labs) | ISC, declared in `package.json` |
| Honeycomb | Soundcloud Visualizer | Michael Bromley | [page](https://www.michaelbromley.co.uk/experiments/soundcloud-vis/), [code](https://github.com/michaelbromley/soundcloud-visualizer) | MIT |
| Fracture | Vissonance: Fracture | Tariq Soliman | [page](https://tariqksoliman.github.io/Vissonance/), [code](https://github.com/tariqksoliman/Vissonance) | MIT |
| Threads | Vissonance: Silk | Tariq Soliman | [page](https://tariqksoliman.github.io/Vissonance/), [code](https://github.com/tariqksoliman/Vissonance) | MIT |
| Iris | Vissonance: Iris | Tariq Soliman | [page](https://tariqksoliman.github.io/Vissonance/), [code](https://github.com/tariqksoliman/Vissonance) | MIT |
| Wavy Spiral | Audible Visuals: Wavy Spiral and Flower | Sonia Boller | [page](https://soniaboller.github.io/audible-visuals/), [code](https://github.com/soniaboller/soniaboller.github.io) | Apache 2.0 text in the older [audible-visuals](https://github.com/soniaboller/audible-visuals) repository, copyright line left blank |

Tariq Soliman's Silk is named Threads here, because the catalogue's Silk is Matt DesLauriers' piece.

The category contract has been removed: custom `Visualization` implementations no longer declare
`family`; `VizFamily` and `VizCatalog.byFamily()` are no longer exposed. Clients that used those
APIs should use `VizCatalog.create()` and the preset's own `name`. This is an intentional source
and binary compatibility change. Musical energy metadata remains for the automatic director;
it is not a picker grouping.

## Continuous forms and landscape travel

The flat catalogue includes the Night Drive musical landscape below. Its internal changes transform
geometry and scene weights without crossfading preset pictures.

| Catalogue entry | Forms inside it |
| --- | --- |
| Night Drive | Highway, mountain pass, city and coast in one musical night drive |

The consumed forms have no separate catalogue entries or duplicate implementations. Night Drive
uses its own `Scene` control, whose default is `Auto`. Manual choices still transform continuously.

Pipe is one shader, a flight forward through a tube of lit cells. Its forms change with the music;
see "Pipe, a flight forward" below.

Night Drive replaces Neon Lo-Fi and Terrain March. It is a drive at dusk: an indigo sky over a rose horizon, a gold
to coral sun, black hills and twelve glass towers, and terraces with black faces and cyan to
magenta neon edges. One analytic shader draws the native-resolution sky, the sun, the prepared ridge
profiles, the road and the optional coast. Five bounded mesh batches supply the stars and the
shooting star, the towers and the world-anchored city, the opaque spectrum terraces and the road's
edges, the lamp posts with their power lines and the rain, and the lamps' added light. There is no
terrain height march, rolling terrain tile, depth/refinement chain or underwater volume.

- The sun is a radial spectrum of 32 stripes over the whole disc, bass at the bottom. A gap is as
  wide as its band is loud, and silence leaves thin, even gaps. The bass swells the sun by up to
  six percent of its radius, a kick pulses it, and the brightness of the sound warms or cools it.
- The ground is a field of terraces. Across is band and back is time: the rows hold the last four
  heard seconds of the spectrum, one row each quarter second, blended between rows so the field
  is continuous. The data flows toward the viewer at the same pace whatever the flight speed.
- The road's two edges are the live waveform.
- Each tower belongs to one note name, C to B from left to right. It is a lit glass volume whose
  height is the energy of its band and whose windows light while that note sounds.
- The lamp posts carry power lines that swing with the waveform. There are no palms.
- With a supported pulse the road passes one lane dash a beat, and so one lamp pair a bar. Without
  one, the mood sets the pace.
- A kick flashes the nearest terrace edges. A snare sends a shooting star, one a bar at most, and a
  hat makes the stars twinkle.
- A breakdown brings rain, and the sun dims and sinks a little. The wet road reflects the terraces
  as streaks, and mirrors the sun and the lamps, until the next section or drop. On the coast, the
  stripes of light on the sea are the waveform.
- A drop lifts the view off the road for one cycle: the horizon drops, the terraces spread out below
  like a lit map, and the sun stands whole. It lands on the first beat after that cycle. Under
  reduced motion the camera rises about 15 percent as far, and the sun closes as usual.
- The forms are the four regions, Highway, Mountain pass, City and Coast, published through
  `Visualization.forms`. Inside a region a morph mirrors the lane order or swaps the towers' sides,
  each eased over a cycle, and a birth raises a cluster of four towers ahead.
- The slow colour walk turns the sky from dusk toward night and back over the song.
- Light follows the level. In a silence the scene keeps 40 percent of its light. The display
  transfer rolls bright light off on its brightest channel, so a bright colour keeps its hue.

Each shoulder has sixteen lanes over twelve depth rows and a protected 7.2-unit road corridor.
The complete input spectrum is area-aggregated into the display lanes with the shared analysis
gain. High frequencies are closest to the road and bass is outside, preventing tall bass cells
from concealing quieter lanes. Delivered transient records add bounded regional crests and
travelling edge marks. Opaque tops, front faces and road-facing sides are submitted far to near,
outer to inner, and a side face hidden behind its neighbour is left out. A bounded anamorphic
horizontal projection keeps the shoulders visible in a tall viewport; the sun retains its
circular angular presentation and explicit screen bounds.

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
Reduced motion scales travel, bank, weather phase and travelling accents without removing
local spectrum response.

The thirteen parameters are Scene, Spectrum floor, Musical mountains, Brightness, Tape finish,
Journey pace, Flight speed, Sun size, Sky ribbons, Rain, Hit accents, Neon intensity and Vividness.
The Mutate now button (`AudioVizState.mutate()`) changes its one gene, Route variation, which is a
coherent route and layout choice. Browser instances copy both
the chosen parameters and this layout configuration, retaining independent histories and actors.
Searching for Neon Lo-Fi or Terrain March finds the single Night Drive entry; `VizDirector.startWith`
resolves both former names too. No external app's persisted settings format is assumed.

The CPU and shader share explicit palette roles and one linear-light exposure/tone transfer.
Default post is restrained bloom (0.25, radius 0.018, threshold 0.65), vignette 0.18, grain 0.006,
scanlines 0.05, no aberration and no glitch. Native cores remain visible with post off. Android
26-32/software backgrounds use a simpler Canvas sky, sun, history ridge, grid, road and coast;
the same musical world and foreground mesh remain active. API 26-28 uses eight fully aggregated
lanes per shoulder, five depth rows and smaller decoration pools, bounded below 1,500 triangles.
The portable path omits expensive post. Runtime views retain the existing sample 60 FPS cap and
browser cap of at most 15 FPS (or a lower configured cap).

`NeonLoFiHistoryTest`, `NeonLoFiWorldTest`, `NeonLoFiMusicTest`, `NeonLoFiCatalogTest`,
`NeonLoFiRenderTest`, `NeonLoFiQualificationTest` and `NeonLoFiLightTest` cover the replacement's
clock/history, event accounting, scene policy, real route variation and preview transfer, fixed-camera
musical response, native phone-sized output, portable output, framing, bounded geometry,
finished light and host CPU cost. The light fixture uses decoded linear sRGB and a separate
saturated-red swing trace; it is a bounded fixture, not general photosensitivity certification.

`NeonLoFiCaptureTest` accepts `NEON_PCM_DIRECTORY` containing named 48 kHz mono f32le files and
optional comma-separated `NEON_SONGS`. It warms the production analyser for three seconds, then
records twelve seconds at 15 FPS with travel stopped and moving. The first visual frame corresponds
to decoded input time 3.066666667 seconds, which the muxed audio must match. These are offline
inspection clips, not presentation FPS measurements. `scripts/check-neonlofi-android.sh` exports
and pushes the exact shader, textures and ordered mesh batches for an explicit offscreen device
run. Its blocking draw/readback duration includes synchronisation and is not isolated GPU scene
cost or app cadence. Neither that script nor an APK build establishes ten-minute presentation
performance.

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

Marble has no controls of its own. The music sets everything through its mapping: kicks and snares
drop ink, the spectrum shapes the vortices, a section changes the recipe of the lace, a breakdown
stops the vortices while the lace keeps growing, and a drop lands one large gold drop in the middle.

`MarbleTest` covers the lively picture without blown-out white, silence, the orange drop of a
kick that pushes the old ink outward, the growth of the lace, the breakdown and the gold drop.
`PausedPictureTest` covers the pause.

## Contour, a living map

`Contour` is a map seen straight down, drawn as contour lines by one shader, with a Canvas
stand-in where runtime shaders cannot run. The stand-in draws the land's lines only.

- The land: 32 islands stand on the bands, bass first. A loud band raises its island, and each
  island's coastline is the live waveform wrapped round it, a fraction of a level high.
- The north of the map is the last eight seconds of the spectrum by map time. Map time runs at the
  music's pace and stops in a pause or a silence, so new land rises at the top edge and drifts south.
- The sea keeps a memory field of 90 rows (160 by 90 on a 16 by 9 screen) with a half life of three
  seconds. A kick drops ink round the loudest bass island; every point outside the drop moves outward,
  as paint does on a marbling tray. Up to five point vortices comb the ink. The shader draws the ink
  as contour lines of its own, only where the ground lies under the water.
- A faint wash of the ground's colour keeps the map lit, at most 22 percent of the line colours.
- The forms are Archipelago, Ridge, Crater and Delta. A morph of the evolution pacer glides the
  islands to the next form over one to four cycles and turns the currents the other way; a birth
  raises a new group of four islands where the loudest bands sit. A section raises a new archipelago.
- A snare cracks an island for under a second. A breakdown brings high tide, stills the sea and
  grows lace foam in it with a Gray-Scott reaction in the field's third channel. A drop brings low
  tide for a cycle, over which the currents run on in gold.

## Alchemy, a memory field

`Alchemy` keeps a memory field: a grid of 108 rows and as many columns as the screen's shape asks
for, each cell holding ink and the ink's age. Every frame a drawer writes the signal into it, a flow
carries it, and it fades with a half life of 1.4 seconds. The shader reads the grid with a cubic
B-spline and draws it as banded ink lit from the upper left, with an iso-line at every band, so the
small grid is crisp at the screen's own resolution. The live drawer is stroked on top at native
resolution.

- Drawers: Ring, Radar, Dots, Polygon, Twin, Edge and Fan. Ring and Polygon draw the waveform and
  the bands, Dots and Edge draw the bands, and the rest draw the waveform.
- Flows: Swirl, Kaleido, Tunnel, Burst, Blocks, Shimmer, Julia and Drift. Their speeds follow the
  music's pace, so a ballad swirls slowly and nothing moves in a silence or a pause.
- The form is the pair of drawer and flow, published through `forms`. A morph of the evolution
  pacer (`Evolution`, which paces form changes by how much is happening in the music) changes the
  flow's parameters and may change the drawer. A birth changes the flow with a cross fade over one
  cycle and picks a new drawer. A drop brings Burst. A breakdown brings Dots plus Drift, the Gas
  form, which also draws thin gas filaments over the ink.
- A kick pushes the whole field outward for a moment and sends a ring from the core. A snare flips
  the spin and strikes a lightning arc, at most two a second. A hat throws sparks and specks of ink
  past the ring, which the flow carries away. A drop turns the ink gold from the core out over one
  beat, and the gold cools back from the rim over two cycles.
- The ink takes the cool end of the palette's ramp. Its colour is walked by the ink's age and by
  `Genes.walk`, and leans towards the song's key when the key is known. Gold is the one fixed
  accent.

`AlchemyTest` covers the forms on a drum loop, the ink in music and in silence, the push of a kick,
the gold of a drop and the arc limit. `WorldsCatalogueTest` checks that a low and a high tone draw
different pictures, that the loud picture covers at least a fifth of the frame, that a calm pad is
at least 40 percent dimmer than a drum loop, and that two palettes draw different pictures.

## Iris, an eye

`Iris` is an eye drawn with meshes, so it runs on every Android version. Its memory is geometry,
not a field.

- The fibres are Vissonance's Iris: 128 spokes round a pupil, the spectrum mirrored left and right
  with the bass at the top, shaded by depth. Each fibre's hue comes from the palette, spread over at
  least 120 degrees and walked by the genes; its saturation and light follow its own band. A frame
  that carries no fine power spectrum feeds the fibres from its bands instead.
- The petals are the stereo trace folded into wedges: the middle of the two channels along a wedge
  and their difference across it. They are as large as the trace is loud.
- The petals and the rings take the register's colour: where the weight of the spectrum sits turns
  their hue round the palette, so dark music and bright music colour the flower differently.
- The rings are past waveforms. One is born each visual cycle, on each kick and on each change of
  form, and holds the waveform of that moment as 128 dots. It spreads outward at the music's pace
  and fades over eight cycles, so its distance from the middle is how long ago it was born.
- Two dark lids open with the level. A silence closes them to a slit, and so does a breakdown,
  until the next turn.
- The forms are Eye, Flower and Mandala, which take turns at the pace of the evolution pacer, and
  Vortex, which runs for one cycle on a drop or a surge: the pupil opens, the rings fly inward and
  the petals part into a magenta and a cyan image that slide back together.
- A kick dilates the pupil, a snare throws shards off the petal tips, and a hat sparkles there.

## Pipe, a flight forward

`Pipe` is one shader: a tube whose wall is lit cells, each ring one past spectrum wrapped round the
tube with the bass along the floor and the treble across the ceiling.

- Rings are born at the far point, one for every sixteenth note, and stream toward the viewer. The
  nearest rings are also lit by the live spectrum, in full at the mouth, so a hit is felt at the camera.
- The tube bends with the stereo balance and the spectral centroid, and the camera banks into the
  bend. Past rings curve into view on the inside of a bend.
- The forms are Round, Square and Hex, which the evolution pacer takes turns through, Chamber (a
  drop doubles the radius for about a cycle at light speed), Throat (a breakdown narrows the tube to
  half speed and lit edges) and Fork (a birth splits the far point and the lane glides to one
  branch over a cycle). The form is published through `Visualization.forms`.
- Cells take their hue from the palette, the cool end at the floor and the warm end at the ceiling,
  walked by the genes, with a new accent at each section. A new peak still burns white. The live
  waveform runs as two rails along the floor, bright at the mouth.
- A kick squeezes the tube and pushes the flight on for a moment, with a small flare on the nearest
  rings. A snare sends a bright ring rushing in from the far point. Hats throw sparks. Light follows
  the level.

## Lines, a road through sound

`Lines` keeps its port: black, 22 hairlines, one frequency band per line drawn bin by bin, the
knee at byte 100, perspective and fog, flat in silence. Around that it is a road.

- The stack follows a path in depth that bends with the stereo balance and dips with the level,
  and the camera glides along it on the slow cycle. At rest the camera is the port's, so a song's
  first seconds look as they did.
- A kick lifts the two middle lines and the lift runs outward through the stack one line per
  page frame. A snare raises the peaks for a beat. The ends shimmer as before.
- The fog takes a faint hue from the palette on quiet far lines. Peaks stay white.
- The forms are Flat, Tunnel (the lines wrap round the viewer), Mirror (reflected below) and Fan
  (the lines radiate from a point, the bass lines nearest). The evolution pacer takes turns through
  them over four cycles, as weights, so a morph never jumps. Lines has no birth. The form is
  published through `Visualization.forms`.

## Honeycomb, a hive

`Honeycomb` keeps its port's hexagon tiles that light like a meter, the bass in the middle, the
peak hold, the white edges, the stars and the glow. It is a hive that grows and turns.

- The tiles read the spectrum bands by ring and angle, the bass at the centre and the treble on
  the outermost ring the hive shows, so the whole hive lights whatever its size. Each tile's hue
  comes from the palette by its level, rotated by its ring and walked by the genes; a quiet tile is
  dim colour, not black.
- The hive turns in notches, one outer tile's angle per beat while the pulse is usable, and turns
  the other way at each morph. Without a pulse it turns slowly at the mood's pace. It is still in
  silence.
- The hive shows four to six rings: a birth or a surge adds one, a breakdown takes it back to two,
  over a cycle. A kick opens the loud cells and light pours out. The push through the middle stays,
  rate limited.
- The forms are Disc, Dome (the hive bulges toward the viewer under a tilted camera) and Tunnel
  (the rings sink into depth and each shows the spectrum of one beat earlier than the ring inside
  it, so the sound steps outward), which the evolution pacer takes turns through over two cycles,
  as weights, and Shatter, which a drop brings for one cycle. The form is published through
  `Visualization.forms`.

## Bars, a city

`Bars` keeps its meter: the fixed height map, the falling caps, the cells, the complementary bar,
the sweep and the embers. The meter is now the front row of a city.

- Behind the front row stand 64 rows of the spectrum's past, one row per fifteenth of a second,
  receding into fog. The walls and the ceiling reflect them.
- The live waveform runs as a bright rail along the floor's front edge. In a silence the hall is
  empty: a floor grid and a faint flat rail.
- The forms are Hall, Canyon (each half of the spectrum stands on one wall), Lake (the rows stand
  further apart and mirror below the water line, with the rail as the shore) and Tower (the rows
  are floors stacked upward, which the camera rises along). The rows bend from one form to the
  next over two to four cycles at the evolution pace, and on a birth the front row bursts into
  embers. The form is published through `Visualization.forms`.
- A kick sends a wave back through the rows, sixty rows a second. A snare runs the sweep over the
  caps. Hats and a steady trickle raise embers, at most two hundred a second. A drop lifts every
  row toward its peak and the room light to full for a cycle. A breakdown sinks the rows to a
  quarter of their height until the next turn, and only the rail plays.
- The camera looks down on the city from above and cranes round it on the slow cycle, rolling at
  most 0.02 rad. A pause holds it and a silence brings it home. It never cuts, shakes or punches.
- Each bar wears the palette by band, walked by the genes; the caps are white; the room light
  follows the level. The bloom is 0.5.

## Ocean Mist, a sea that remembers

`Ocean Mist` keeps its sea under a moon: the front row is the live waveform, with the kick's crest,
the snare's spray, the hats' glitter, the drop's wave and its break, the mist and the key lean.

- Every row behind the front one is the waveform one thirtieth of a heard second earlier, 96 rows
  from the shared row history, so the swell rolling to the horizon is the last three seconds of
  sound. Each row is a line over dark water of its own colour. The bass raises the long swell and
  the treble adds fine ripples.
- The moon's reflection is the spectrum: the width of the glitter column on each row is that
  row's band, the bass in front and the treble far out. The moon breathes with the bass and
  crosses the sky once per walk of the genes, never on a section.
- The forms are Glass, Swell and Storm, which the energy and the mood move between, and Tide,
  which a breakdown brings: the water draws back and leaves wet sand with the moon's streak on
  it. At each morph the swell turns, from the front row out, and the horizon moves. At each birth
  a front with its own hue rolls in from the horizon. The form is published through
  `Visualization.forms`.
- The viewer glides forward and down on the slow cycle and never cuts. The horizon drifts with
  the mood. A drop also lights the sky for a tenth of a second, under the flash guard. The sky
  walks from dusk to night to dawn over the song.
- The weather, the horizon, the moon and the sky move on heard time, so a silence holds them.

## Silk, a cloth of sound

`Silk` keeps its ribbons on light paper, the catalogue's one bright drawing, with the vignette
and the grain. A faint wash from the cool half of the palette lies over the paper. The ribbons
now take one of four forms.

- Cloth: a sheet of fifty rows. Each row is the spectrum one sixteenth of a heard second earlier,
  so the sheet holds the last three seconds of sound. Across is frequency on the engine's ERB
  bands, height is level, and the front edge carries the live waveform. The drawing opens on the
  Cloth.
- Tube rolls the cloth round the viewer, who looks down its axis. Veil hangs the cloth from above,
  seen from the side, with time running down.
- Strand: the port's fine ribbons twisting round one line. The twist follows the energy and
  turns the other way at each morph. A birth weaves a second strand in.
- The forms change at the music's pace, and each point of the sheet blends from one form to the
  next. A drop lays the cloth out, and a breakdown hangs the veil. The form is published through
  `Visualization.forms`.
- The sheet floats over Fluctus's stage, which this drawing absorbed: a floor under a soft shadow,
  seen from twenty degrees up, with a horizon. The camera orbits on the slow cycle and moves
  closer in a bright mood. It never moves on a beat.
- A kick sends a crest from the front row to the back. A snare pleats the sheet. A hat makes the
  threads shimmer. The bass billows the cloth, a drop lifts the billow for a cycle, and a
  breakdown stills it until the music turns.
- The rows take the palette from front to back, walked by the genes. The paper follows the
  allowed light, and the ink follows the level. On this light paper more music means more ink,
  so a loud frame is darker than a quiet one.

## Twin Bloom, a garden

`Twin Bloom` keeps its radial blooms whose petals are bands, the spring that answers a kick with
lookahead, the petals that break off on a snare, the sparkles on a hat, the throw on a drop and
the buds of a breakdown. It is a garden now.

- The garden starts as one bud on a short stem. Each birth plants a new bloom where the mix puts
  it: across from the stereo balance, and further back the brighter the sound. The sixth birth
  replaces the oldest bloom. A stem's height is the energy heard since its birth, and the live
  waveform runs along it.
- Every petal is one band, from the bass in the inner ring to the treble in the outer ring. Its
  length runs the full range, from 0.2 of its full length in silence to all of it at full level.
- Fallen petals settle on the ground into a carpet. The carpet is a memory field with a four
  second half life that drifts slowly sideways, drawn as lit ink in four flat steps. On a device
  without runtime shaders the carpet is not drawn.
- The forms are Bud, Bloom, Field and Storm, named by what the garden shows and published
  through `Visualization.forms`. At each change of form the blooms open or wilt with the slow
  level over two cycles.
- The view is fixed, with no orbit. One sway on the cycle moves three layers at three speeds: the
  ground at half, the blooms at one, and a faint foreground petal at twice. The foreground petal
  comes in with the music.
- Each bloom takes its own place on the palette by birth order, walked by the genes and leaned
  toward the key, so a garden holds several flowers. The rings of one bloom sit a step apart on
  the palette. A faint ground in the palette's low colour lies under the garden. Light follows
  the level, with a floor in silence.

## Thin Ice, a lake as big as the song

`Thin Ice` keeps one ring per hit, with the waveform and the spectrum frozen at the hit and the
rings spaced evenly on the beat. It also keeps the white crossings, the cracks, the creep before a
drop and the break on a drop.

- The lake is a map of the mix. Across the screen is the stereo balance. The long side runs from
  bass to treble, from the left of a landscape screen and from the bottom of a portrait one.
- Kicks ring at the bass end, snares in the middle and hats at the treble end. Every hit also goes
  into the shared impulse list.
- The two wells slew to the two loudest quarters of the bands. A well that no hit rang for a beat
  rings by itself, as strong as its quarter is loud, so a held tone still draws the map. A birth
  hands the loudest quarter to the other well.
- A newborn ring bends with the waveform by up to 12 percent of the short side and settles as it
  spreads.
- Hats lay snow into a drift, a memory field with an eight second half life that creeps slowly.
  Snow that falls on a young ring front settles along it. On a device without runtime shaders the
  drift is not drawn.
- The forms are Lake, Frost, Shatter and Underwater, published through `Visualization.forms`. A
  breakdown brings Frost, and lace grows inside the rings. A drop breaks the ice (Shatter), and
  the lake goes under water when the pieces have sunk. Under water the rings are caustic light and
  hats send up bubbles, until the next section or eight cycles freeze a new lake. Each morph turns
  the glide and swaps the wells.
- The camera glides over the lake on the slow cycle and dives over a cycle after a drop. It never
  cuts, and a pause holds it. Cyan and orchid are the rings on the default palette, and any other
  palette gives its own pair. Light follows the level.

## Fracture, a shattered corridor

`Fracture` keeps its flight down a corridor whose floor and ceiling are the spectrum. It keeps
the page's loudness laws for the flight, the squeeze and the hue, and the page's clipping. The
one slit is now a cracked plane of many shards.

- Each of the 64 strips shows its own time slice of the spectrum, four heard seconds across the
  corridor, so the corridor is a spectrogram you fly into. Each shard tilts by its own level,
  alternate shards the other way.
- The roll turns one way in the first half of each cycle and back in the second, faster when
  louder. A morph turns the roll and every shard's tilt the other way.
- Tariq Soliman's Silk flies over the corridor as glass dust: one hexagon per band of 512, with
  its log scale, its thirty unit climb and its four way mirror. Fracture absorbed it.
- Twelve cracks of light lie on the floor and the ceiling. The main crack runs down the middle of
  the floor and carries the live waveform. A kick runs four cracks again from their start, a
  snare flips a pane of four shards, a hat glints the dust, and a birth grows a new crack network.
- The forms are Corridor, Spiral (the roll at full speed), Shatter (a drop bursts the shards
  outward for one cycle) and Fuse (a breakdown stops the tilt and the roll, and the cracks heal
  into one plane until the music turns). They are published through `Visualization.forms`.
- The flight runs at half the page's speed with no drive and at the page's speed at full drive.
  Nothing rolls on the level alone.
- On Prism the strips keep the page's one hue from the loudness. On any other palette each column
  takes its hue from the palette by band and by the slow colour walk. The hue turns up to 30
  degrees toward the key.
- The page's white clear is a dim space now, and the flat ground keeps a faint glow near the
  camera. Light follows the level, with a quarter kept in a silence.

## Glitch, a broadcast that keeps breaking

`Glitch` keeps its six swatches, one set of three per section, the waveform trace, the datamosh,
the stall before a drop and the scanlines.

- The picture is the spectrogram as large blocks. Across is time, four heard seconds scrolling to
  the left. Up is band, with the bass at the bottom. A block takes the first, second or third
  swatch of the set by its level.
- For the eyes: every block is at least 24 px on both sides, neighbouring blocks differ in light
  by at most half, and the hues change only with the set. The flash guard dims every layer,
  including the split, the tears, the static and the datamosh.
- The faults: a kick splits the red, green and blue layers for about 120 ms, a snare tears slices
  sideways (further when the spectrum changed more), and a hat slips one row of blocks one to two
  blocks sideways for two to four frames. Dense music joins the blocks into pairs and fours, and
  a slow tracking roll follows the cycle.
- A section changes the channel to the next set behind three frames of static. A drop freezes
  the frame into a datamosh until a first beat. A breakdown is dead air: the picture collapses
  into one bright line over a faint static until the music turns.
- The forms are Broadcast, Tracking (the roll reaches twice as far), Mosh (a datamosh starts
  every cycle with no drop) and Dead air. They are published through `Visualization.forms`. A
  birth wipes the next set in from the top.
- The key biases which set the next section brings, and the slow colour walk turns the hues a
  little within a set. On Prism the swatches are the drawing's own. Any other palette pulls each
  swatch's hue about a third of the way toward the palette's colours.
- There is no camera: it is a screen. Light follows the level. In a silence the blocks keep 30
  percent light and the trace lies flat.

## The flash guard

`FlashGuard` counts flashes the way WCAG 2.2 defines them: a pair of opposing changes in relative
luminance of at least 0.10, where the darker of the two states is below 0.80. A red flash is the
same pattern in saturated red. The project's policy is stricter than WCAG's: at most three in any
rolling second, with no area exception, and no saturated red flashing at all. The red rule holds in
`FlashGuard.limit`. The surface calls `FlashGuard.allowance`, which watches one number, the light
level, and cannot see red.

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
with reduced motion on, which must bring it inside the policy and add at most one flash. It renders
for minutes, so it runs in `./gradlew :kiteplayer-audioviz:audiovizSurvey` and not in `jvmTest`.
`FlashGuardTest` fixes the runtime guard's own counter against known pass and fail signals.

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
whose bars grow on the beat as a flash and called seventeen drawings of the old 78-drawing
catalogue unsafe. The second compared
each frame with the last turning point, which cannot tell a jump after a long rest from a slow
drift, and called sixty-five of the old catalogue unsafe. Brightness changes were made against the first reading and
then taken back out. Calibrate the counter before trusting it about a drawing.

**What it cannot see, measured.** A drawing draws its own bright marks, and the guard reads only
the shared light, so a picture that flashes through what it draws passes the guard. Each drawing
has to keep its own marks inside the policy. The capture test ran on all 24 drawings on
2026-09-29, and none of them crosses the policy in the captured output. Bars, Fracture, Iris and
Odyssey reach three on the drum loop, the limit itself, and the other twenty stay below it. With
reduced motion on, every drawing stays inside the policy. Pipe and Honeycomb still flash once in
their busiest second, and the other twenty-two flash not at all.

Before 2026-09-29, Honeycomb and Pipe crossed the policy on the drum loop, at six and five flashes
in their busiest second (#298). In Honeycomb, a push that turned from outward to ten times as far
inward in one read threw every tile off the screen for a frame. The corners now follow that turn at
a limited speed. In Pipe, a snare's ring lit the nearest rings, which cover much of the screen, and
hats and chord stabs are heard as snares too. The ring now shows only past those rings. Both
drawings now reach two on each fixture.

The two fixtures are not every kind of music. Measured by hand with the same counter on
2026-09-29, a loop of hats alone at 150 beats a minute brings Honeycomb to five, and a loop without
hats at 190 beats a minute brings Pipe to four.

The test also prints the widest area that swung together for every drawing, so one that is close to
the limit is visible before it crosses it.

**The allowed light reaches every drawing.** The guard cannot dim a finished frame. It lowers
`VizRenderState.lightScale`, and each drawing scales its own light by it. `FlashGuardReachTest`
renders every drawing at full and at half the allowed light, and the half render must be dimmer.

**Also not covered.** A change of drawing composes two scenes, and the capture test renders one at
a time. `StrobeCut` alternates the two scenes about four times inside one change, so it is out of
every default profile; it stays in `VizTransition` for a caller that asks for it by name and owns
that decision.

## Reduced motion, and turning the picture off

`AudioVizState.reducedMotion` keeps the spectrum, the colours and the shapes, and damps what throws
the picture about: the camera's punch, shake, roll and cuts, the trail's swim, and the flash limit
drops to none at all. Every change of drawing becomes a plain fade. A preferred way that a caller
sets still wins, except the strobe, which is never used. The camera still wanders slowly, because a
still picture with a moving spectrum in it reads as broken rather than as calm.

The reading a drawing sees is slowed down: its smooth values and its power spectrum glide to each
new frame over half a second. The waveform, the hits, the delivered events and the pause stay those
of the newest frame, so a drawing that draws the waveform still follows the music and a pause still
stops the picture.

It also holds back the hits that `Gestures` reads, to about a third of their strength. A drawing
flashes mostly through what it draws on a hit, not through the light it is given, and the captured
output with reduced motion on was once identical to the output without it. Only the drawings that
use `Gestures` feel this. The others get reduced motion from the slowed reading and from their own
use of `motionScale`.

`AudioVizState.visible` draws the background alone. The analysis keeps running and the player keeps
playing, so a viewer who turns the picture off keeps the music.

The reduced motion setting reaches a drawing as `VizRenderState.motionScale`, so a drawing written
outside this repository gets it without knowing about it. The visible setting never reaches a
drawing: the surface draws the background and returns.

## Still open

- **No evidence of the destination backend on a phone.** A shader compiled by Skia does not prove
  its destination is the graphics card. The desktop benchmark reports the window's graphics backend,
  and Android and iOS have no such evidence yet. That needs a device.
- **The governor owns no total budget.** Drawings declare what they can give up through
  `VizQualityControl`, and the quality control shrinks the echo buffer, but there is no
  surface-wide budget across two scenes, glow, history and the interface.
- **Android 26 to 32 and context loss** are unqualified. Both need a device.
