# Drawing mappings

This contract implements "How a drawing uses drivers" in the
[audio visualiser standard](audioviz-standard.md). Every built-in drawing declares, in code, which
audio values move which parts of its picture, how, and how fast. Tests render each drawing with one
audio value changed at a time and check that the declared part of the picture answers.

A **driver** is an audio value a drawing reads. A **property** is a part of the picture that a
driver moves. A **drive** is one driver moving one property.

## Hits: confidence decides, strength sizes

A transient event carries two separate numbers. Confidence is the detector's support for the
event. Strength is the energy of the event's band under the shared gain. A quiet but clear drum
hit has high confidence and low strength. The texture of a loud pad can trigger the detector with
low confidence and high strength.

- `AudioDetection.isHit` is true for a transient kind (`Onset`, `LowTransient`, `BodyTransient`,
  `HighTransient`) with confidence of at least `AudioDetection.HIT_CONFIDENCE`, 0.35. *Judgement*,
  set from development fixtures, not from held-out music. On a drum loop 18 dB below the reference
  the gate keeps 97% of the detections, including hits a fifth as strong as the old gate wanted.
  On a loud noise texture with no drums in it, it refuses about three quarters of them. A soft pad
  fires no transient detector at all.
- A hit decides that the picture answers. Its strength decides how much. A drawing scales a spawn
  count, a size, a push or a brightness by the strength. A detection that is not a hit is still
  delivered with its confidence, but no built-in drawing accents it.
- The scalar projections `SpectrumFrame.beat`, `kick`, `snare`, `hat` and `onsetStrength` report
  the strongest hit of each kind, not the strongest detection. The fading pulses `pulse`,
  `kickPulse`, `snarePulse` and `hatPulse` start only from hits.
- A discrete response, such as a direction flip or a change of recipe, can use the hit as a
  yes or no. The drawing declares it as a cut, not as an accent.

`Gestures` reads the delivered records of each frame, not the scalar projections:

| Member | Meaning |
| --- | --- |
| `kick`, `snare`, `hat` | Strength of the strongest hit of that kind in this frame, else 0 |
| `kicks`, `snares`, `hats` | Number of hits of that kind in this frame |
| `pulseUsable` | Whether the visual cycles follow a supported pulse rather than running free |

The names `kick`, `snare` and `hat` are kept for compatibility. They do not identify
instruments. They mean low, body and high transients.

Each hit is counted once. The event cursor delivers a record once per view, a repeated frame at
an instant already read delivers nothing, and a catch-up reset skips the missed burst rather than
replaying it. A frame from a raw analysis, with detections but no delivery, counts its detections.
A frame built by hand with scalar fields only counts one hit per nonzero field, because it has no
confidence to check.

Spawns are bounded. `kickSpawn`, `snareSpawn` and `hatSpawn` sum the strengths of the frame's hits
of one kind, cap the sum at 2 (*judgement*), and multiply the drawing's base count by it. A hit
always spawns at least one thing. A dense run of high transients therefore cannot flood a drawing,
and a hard hit still spawns more than a soft one.

Anything discrete on a visual cycle edge, such as a ring every quarter cycle, needs
`Gestures.pulseUsable`. The cycles follow a supported pulse when there is one and run free
otherwise, and a discrete response on a free cycle is a beat train the music does not have. Ten
drawings held such a response; they now hold it only while the pulse is supported. Under music
with no pulse to follow, their continuous motion carries them.

## Fixed levels, no hidden rescaling

Every energy driver is a height under the one shared gain, so a fixed level means the same thing
at every point of every song. A band at height 0.2 holds about a four-hundredth of the reference
power, which is what one band of a loud mix holds when the energy is spread over a few dozen of
them. A quiet passage crosses that level less often, which is the contrast the standard asks for.

- A drawing compares energy with fixed levels. It does not rank values within a frame, and it
  does not stretch values to their own recent range.
- `SpectrumFrame.bandPercentile` and `VizRenderState.percentile` are deprecated. They ranked the
  bars of one frame, so a quiet passage lit as many bars as a loud one.
- `SpectrumSplit` used a separate automatic range per third of the spectrum, with a floor that let
  a quiet third fill the picture. Each third now maps through one fixed range instead. The ranges
  differ between thirds because the thirds hold different amounts of energy: on the loud drum
  fixture the mean height is 0.31 in the low third, 0.08 in the middle and 0.13 at the top, and
  the fixed tops are about two and a half times those. That is the tilt compensation the standard
  asks to be named rather than learned while the song plays. *Judgement.*
- The four drawings that ranked bars now compare a height with `LOUD_BAND`, 0.2, or `LIT_BAND`,
  0.1. About three bars in ten cross `LOUD_BAND` on the loud drum fixture, and almost none do
  under a soft pad, which is the contrast a rank removed. *Judgement.*
- A fixed artistic range is allowed and is declared as a `VizCurve.Range`.

## Light follows the level, speed follows the drive

Two shared values pace every drawing. `VizRenderState.lift` says how much light the main parts
give. `VizRenderState.drive` says how hard the music pushes, which is speed.

`lift` follows `frame.energy`, the height of this moment under the shared gain. It does not follow
`drive`, because `drive` mixes in mood and density, and those say how busy the music is rather
than how loud. With the light on `drive`, a passage 12 dB down still read most of the way up: the
shared light fell only from 0.52 to 0.45, and every drawing that used it hid the change. On the
level, the same pair reads 0.60 and 0.39.

A drawing's own paths run on `VizRenderState.idle` and `VizRenderState.tempo`. Both nearly stop in
silence, at about a tenth of their speed under drums, so a still picture reads as settled rather
than as a screen saver.

The height curve has a top. A run whose power sits far above the loudness reference clips against
it, and two runs 12 dB apart then read almost the same. The qualification suites therefore set the
reference to the fixture's own programme power, which is what a song map measures for a real song.
`FixtureLevelTest` prints the whole table and holds these two rules.

## The declaration

`Visualization.mapping` returns a `VizMapping`, or null for a drawing that declares nothing. Every
built-in drawing declares one. A drive covers the whole picture, including the ground, the detail
layer and the camera, because that is what a viewer sees and what the tests measure.

A layered drawing declares its drives through `mappingOf`, which fills in what the drawing needs
and what it can give up from the drawing itself, so those cannot drift:

```kotlin
override val mapping: VizMapping by mappingOf(
    VizDrive(VizDriver.Bands, VizProperty.Size),
    VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1.6f)),
    VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.2f)),
    VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
)
```

### Drivers

| Driver | Source | Missing or silent |
| --- | --- | --- |
| `Level` | `level`, `energy`, `loudShort`: fast overall height | 0; the drawing shows its silence behaviour |
| `SlowLevel` | `loudLong`: 100 ms rise, 2 s fall | 0 |
| `Bands` | `bands` and the falling `peaks` above them | All 0 |
| `Bass` | `bass`: fast height below 250 Hz | 0 |
| `Mid` | `mid`: fast height from 250 Hz to 2 kHz | 0 |
| `Treble` | `treble`: fast height above 2 kHz | 0 |
| `Waveform` | `scope`, `scopeLeft`, `scopeRight` | A flat trace |
| `Width` | `width`: stereo width | 0, which is mono |
| `Timbre` | `centroid`, `flatness` | 0 |
| `Key` | `keyHue` weighted by `keyConfidence` | Weight 0: the palette keeps its own colours |
| `Mood` | `mood`, `density`: section liveliness | 0, which is calm |
| `LowHit` | Low transient hits and `kickPulse` | No accents |
| `BodyHit` | Body transient hits and `snarePulse` | No accents |
| `HighHit` | High transient hits and `hatPulse` | No accents |
| `Onset` | Broadband onset hits and `pulse` | No accents |
| `Pulse` | Usable pulse rate and phase | Artistic cycles run free at a rate set by `Mood` |
| `Section` | An accepted section boundary | The scene holds |
| `Drop` | An accepted drop | The scene holds |
| `Breakdown` | An accepted breakdown | The scene holds |

Composite values name each driver they contain. `VizRenderState.drive` mixes `Level`, `Mood` and
`LowHit`, so a drawing that sets a speed from it declares the drives that the tests can detect.

### Properties

| Property | What moves | Measured by |
| --- | --- | --- |
| `Size` | How big shapes are, or how much of the screen they cover | Share of pixels away from the background |
| `Brightness` | How much light the picture gives, including glow | Mean luma |
| `Colour` | Hue and saturation | Mean chroma difference |
| `Speed` | How fast things move | Mean change between frames |
| `Shape` | Where things are, or the shape they take | Mean difference from the unchanged render |
| `Spawn` | New things appear: particles, rings, splashes | Difference from the unchanged render, starting at the event |
| `Camera` | The view pans, zooms, turns or shakes | Difference from the unchanged render |
| `Cut` | A discrete change of state: a flip, a jump, a new recipe | Difference from the unchanged render, starting at the event |
| `Texture` | Fine detail | Mean absolute Laplacian of luma |

`Shape`, `Spawn`, `Camera` and `Cut` are checked only as a change of the picture that the driver
causes. The tests do not tell a camera move from a change of shape.

### Curves and responses

`VizCurve` is the fixed transform from driver to property:

- `Linear`: in proportion over the driver's whole range.
- `Range(from, to)`: a fixed artistic range. Nothing below `from`, all of it above `to`.
- `Threshold(level)`: on above a fixed level of a height, off below it.
- `Scaled`: for a hit. The response scales with the hit's strength.
- `Discrete`: for a hit or a structural event. A response whose size does not follow strength.

`VizResponse` is the drawing's own timing, on top of the analysis envelopes. The drawing must not
repeat the analysis smoothing.

| Response | Meaning | Seconds |
| --- | --- | --- |
| `Direct` | The property follows the driver in the same frame | None |
| `envelope(seconds)` | A first-order rise | The rise time constant |
| `spring(seconds)` | Damped physics | The time to settle |
| `Rate` | The driver sets a rate; the property is its running sum | None |
| `lifetime(seconds)` | Spawned things live this long | The lifetime |

Every response starts in the frame that delivers the change. `delaySeconds` declares a deliberate
wait, for example a response held for the next visual cycle.

### Needs, silence and quality

`VizNeed` lists what the host must provide:

- `RuntimeShader`: the whole picture is one runtime shader. Without runtime shaders the drawing
  shows its stand-in, or the catalogue leaves it out when it has none.
- `ShaderLayers`: a ground, a detail layer or a per-pixel warp uses runtime shaders. Without them
  those layers are left out and the rest of the drawing draws.
- `EchoBuffer`: the drawing feeds its frames back through an offscreen buffer.
- `SoftBuffer`: that buffer runs at a reduced scale by design, because the drawing is soft.

`VizSilence` is what the drawing does when there is nothing to hear: `Idle` keeps a restrained
motion, `Still` settles and holds, and `Fade` fades to the background.

`VizQualityControl` lists what can be reduced under load. `EchoResolution` means the echo buffer
may shrink while the sharp front stays at full resolution. `BoundedPool` means spawned things come
from a fixed pool, so their number has a ceiling.

`mappingOf` fills in the needs and the quality controls from the drawing itself: whether it is a
runtime shader, whether it has a ground, a detail layer or a warp, whether it feeds frames back,
and whether it blooms. A drawing cannot declare a need it does not have or hide one it does.

## Qualification

Tests render at a fixed size, seed and frame rate of 60 frames a second. Drawings are
deterministic, so two renders from the same start differ only by what the test changed.

**Driver injection.** For each drawing, a baseline run holds every driver at a mid value. Each
driver then runs once with only that driver changed. The test measures every property's metric
against the baseline.

- Every declared drive must move its property by at least the minimum effect for that property.
- Every driver whose change moves the picture as much as the drawing's median declared drive must
  be declared. A declaration cannot hide a strong response.
- A hit or structural drive must start in the frame that delivers the event, unless it declares
  a delay.
- A `Scaled` hit drive answers a hit of strength 0.9 more than a hit of strength 0.3. A detection
  below the confidence gate moves nothing.

**Level step.** The drum loop plays twice with the song reference fixed, once 12 dB quieter. Every
drawing must look louder in the loud run on the properties it declares for `Level`, `Bands`,
`Bass`, `Mid` or `Treble`.

**Silence against music.** After the declared settling period, silence must produce at most 20%
of the music render's mean change between frames. *Judgement.* The music render must itself reach
a minimum mean change, so a frozen picture cannot pass. A `Still` drawing must be nearly still.

**Sustained tone.** A held tone gives one attack. After it, the change between frames must show no
continuing train of accents.

**The same music, late.** The drum loop plays twice, the second time a quarter of a second later.
Both renders start from the same reset, so the only difference is when the drums land, and the two
pictures must differ. A drawing whose motion merely looks musical passes every other check here
and fails this one.

**Source lint.** `MappingLintTest` refuses ranks within a frame and automatic ranges. It no longer
asks for them.

These checks do not establish that a viewer sees the music cause the picture. The standard's
listening and viewing comparison, with aligned, shifted and unrelated audio of similar loudness,
is a separate review with people.
