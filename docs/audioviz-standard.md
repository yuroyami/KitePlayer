# Audio visualiser standard

The goal is one sentence: a viewer who hears the music must see the music cause the picture.

This is the design standard for the audioviz overhaul, not a claim that the current release meets
it. It covers analysis, delivery to drawings, automatic direction and finishing. Requirements use
**must**. Numerical defaults marked *judgement* are project choices to validate, not values
established by a citation. Performance and recognition targets remain unqualified until their
named fixtures and platforms pass. A cited method does not transfer its authors' results to ours.

The live path must work without a song scan. Scanning improves context; unavailable or uncertain
musical information must not stop the picture. The analysis contract is independent of Compose
and the decoder. Current platform scope and module boundaries are recorded below.

## Terms and data contract

- **Live analysis**: features extracted from the selected audio track as playback decodes it.
- **Song scan**: an independent, background decode of that track, without an audio output device.
- **Song map**: time-indexed estimates produced by a scan, with coverage, confidence and a version.
- **Driver**: a named value consumed by a drawing, with units, range, timing and missing-data rules.
- **Level**: energy relative to one shared reference. **Surprise**: change relative to recent
  history. **Confidence**: support for an estimate. These are different quantities.
- **Event**: something estimated to happen at one instant, with an identity, timestamp, kind,
  strength and confidence. An event is not its fading visual envelope.
- **Media time**: position on the selected track's presentation timeline, after the playback
  path's trim and timestamp conventions have been applied.
- **Audible time**: estimated media position reaching the listener at a given monotonic host time.
- **Generation**: one continuous analysis timeline. A seek, track switch, reopen or discontinuity
  invalidates old deliveries, even if the numerical timestamp repeats.

Each feature must carry its measurement interval and reference timestamp. A windowed level uses
its centre; an event uses the estimated onset time. Long-window and short-window features must
retain their separate times. Publishing them in one object does not make them simultaneous.
The contract must distinguish unknown timestamps, missing data, silence, warming up and valid
zero. Negative media positions alone are not a portable missing-timestamp marker.

Raw calibrated features, shared normalised drivers and artistic mappings must remain distinguishable.
A driver must document its provenance: observed audio, a live estimate, or a song-map estimate.
All public values must be finite. Channel/layout changes and non-finite input must have explicit
reset or sanitisation behavior, with a diagnostic rather than poisoned FFT history.

The default describes source audio before user volume, equalisation and tempo/pitch processing,
matching the current AudioTap. Muting may leave this source visualisation running. A mode that
claims to show the processed output needs a separately specified tap and timestamp mapping; it
must not infer post-effect PCM from the current source tap.

## Timing

A picture that is late looks detached, however good the analysis is.

- The player must supply a coherent clock reading: audible media position, its monotonic host
  timestamp, effective playback rate, generation and validity/quality. An ordinary UI position,
  especially one temporarily showing a requested seek target, is insufficient.
- For an uninterrupted interval, sample the features at
  `mediaAtAnchor + playbackRate * (displayHostTime - anchorHostTime)`, using consistent units.
  A 20 ms display delay advances media time by 40 ms at 2x. Pauses, seeks and rate changes require
  a fresh mapping; do not extrapolate through them.
- Prefer the platform's predicted presentation time for scheduling. Actual presentation feedback
  measures the error afterwards. A frame callback timestamp is not automatically either one.
  Where prediction is unavailable, use a configurable estimate, initially one refresh period.
  *Judgement.* Report it as estimated and calibrate it per output/display route. Do not subtract
  audio buffering twice if the clock already represents audible position.
- The controlled click-fixture target is a visible transient between 15 ms early and 30 ms late
  relative to its audible click. *Judgement.* Report signed errors, median, p95, maximum and
  missed events. This is a qualification target for a named route, not an unconditional promise
  during OS stalls or with unknown wireless-output latency.
- Account separately for window availability, filter delay, onset confirmation, worker delivery,
  interpolation, drawing and display. A centre timestamp does not remove the need to wait for
  the second half of a window. A smoothed bass peak need not coincide with an onset.
- Real lookahead exists only as far as timestamped, valid analysis has actually arrived.
  Expose the available horizon. Anticipatory motion may use up to 100 ms of that horizon
  (*judgement*), but its impact must land on the event. An estimated future beat remains a
  prediction, and the newest frame must never masquerade as a requested future frame.
- Interpolate continuous levels between valid neighbours in the same generation. Do not
  interpolate event flags, key identities or confidence across missing data. Beat phase requires
  circular interpolation or evaluation from its grid, not a straight blend across the wrap.
- Use elapsed time for smoothing: `alpha = 1 - exp(-dt / tau)`. Driver smoothing advances on
  analysis media time, independent of how often it is drawn. Visual envelopes and physics declare
  whether their durations use media seconds or presentation seconds.
- Never clamp the authoritative media clock or event interval to hide a stall. Physics may
  substep or discard excess integration time under a bounded policy; trails must age by the
  elapsed time. On pause, stop new events and beat progression. On starvation or end, expire old
  features and settle the picture instead of repeating the last kick.

The broadcast recommendations discuss different stimuli and timing conventions. They motivate
care with synchronisation, not these exact project limits. See
[ITU-R BT.1359-1](https://www.itu.int/rec/R-REC-BT.1359-1-199811-I/en) and
[EBU R37](https://tech.ebu.ch/docs/r/r037.pdf).

Checked by: timestamped impulses and clicks at 0.5x, 1x and 2x; pause/resume, seek, track change,
buffer underrun, display-rate change and a 500 ms stall. Sample identical analysis at 60, 90, 120
and 144 Hz and compare at the same media times. Software clock checks and physical sound/display
capture are separate evidence; comparing two readings of the same clock cannot prove what was
heard and seen.

## Audio ownership and scheduling

- The device callback must never run analysis, allocate, log, block or call rendering code.
  AudioTap runs on the feed worker, which also supplies playback and must return promptly.
- Copy borrowed PCM into bounded, preallocated storage before retaining it. The analyser owns
  its working buffers; readers receive immutable snapshots or an explicit lease. Reusing arrays
  while another thread reads them is forbidden, even when the aim is zero allocation.
- Heavy analysis and scanning must run outside the feed worker. A small synchronous operation is
  allowed only with a measured worst-case bound. Queue saturation must shed visual work and
  publish a gap/reset marker; playback must not wait for the picture.
- Bound PCM queues, feature history, event history and scan concurrency by bytes and time.
  A producer must never overwrite storage still owned by a consumer.
- Detaching the last consumer must stop its work and release owned resources. Multiple views of
  the same player should share analysis while retaining independent event cursors. A hidden
  browser/grid preview must not multiply scans or render every off-screen preset continuously.

Checked by: a deliberately slow consumer, concurrent detach/reset, rapid track changes and
multiple views. The audio feed keeps progressing, memory remains bounded, stale generations
cannot publish into the new track, and retained sample data does not change underneath a reader.

## Spectrum and waveform

- Analyse with a nominal 10 ms hop, rounded to whole samples, and derive timestamps from sample
  counts rather than repeatedly adding rounded milliseconds. This cadence is *judgement*.
- Use a Hann window. At 44.1 and 48 kHz the transient path starts with 2048 samples; at other
  rates choose a supported length that keeps its duration at or below 50 ms. *Judgement.*
  In particular, 2048 samples at 16 kHz spans 128 ms and does not meet that rule.
- Compute spectral power per channel and combine powers with documented, fixed layout weights.
  For the default mono/stereo spectrum use the mean channel power. Do not average waveforms
  before measuring energy: `L = -R` would disappear. Preserve paired left/right traces for a
  vectorscope; multichannel analysis must identify its layout and treatment of LFE.
- Normalise FFT power for transform length, sample rate, window energy and one-sided bins,
  including DC/Nyquist handling. State whether a value is power per bin, power per Hz, or
  integrated band power. Calibrate tone amplitude separately from broadband power.
- Default to 40 ERB-spaced display bands from 30 Hz to
  `min(16_000 Hz, 0.95 * sampleRate / 2)`; 32 to 64 is the ordinary authoring range.
  *Judgement.* The ERB-number formula is `21.4 * log10(1 + 0.00437 * frequencyHz)`.
  Its span here is about 38.46. Auditory spacing does not confer auditory resolution on an FFT.
  See [Glasberg and Moore (1990)](https://doi.org/10.1016/0378-5955(90)90170-T).
- Integrate power with fixed band weights. Split bin-edge overlap so that the weights conserve
  in-range power. Do not select the largest bin or force every narrow band to own an entire bin.
  Publish actual edges. Changing bar count must not change total represented energy.
- Do not promise independent bass notes from the short transform. A slower, longer-window
  spectrum is allowed for resolved bass or history, with its own timestamp and response test.
  It must not replace the transient bass driver under the short window's timestamp. Zero-padding
  alone does not increase resolving power.
- Keep the default spectrum unweighted. K-weighting belongs to the programme-level reference
  below. An optional display weighting or fixed bandwidth compensation must be named, fixed and
  reflected in calibration; it must not silently alter the onset detector or measured raw power.
- A waveform trace must declare its time span, gain, channels and trigger offset. A min/max
  envelope preserves peaks when many samples share one column; a resampled waveform instead
  needs antialias filtering. A triggered decorative scope must not supply event timestamps.

Checked by: silence, DC, a full-scale calibration tone, non-bin-centred tones and a sweep; mono,
left-only, identical stereo and opposite-polarity stereo; each supported layout and sample rate.
Compare band powers with an independent reference using the declared window and filters.
For ideal pink noise, expected unweighted band power follows `ln(highHz / lowHz)`; for white
noise it follows bandwidth. Compare a long average against that response, not an unspecified
flat picture. A Hann-windowed tone has leakage; test its expected energy distribution rather
than requiring all non-neighbouring bars to be exactly dark.

## Level and display scale

A loud part must look louder than a quiet part. Normalisation must preserve that contrast.

- One gain applies to the mix. Drivers representing energy must not acquire separate automatic
  gains per band or per drawing. A fixed artistic range is allowed and must be declared.
- Compute the programme reference from per-channel K-weighted mean-square signals and the
  appropriate channel weights. LFE is excluded from the BS.1770 programme sum; that does not mean
  it must be discarded from a musical bass driver. A centre-frequency multiplier on an ERB bar
  is not the full measurement algorithm.
- Use ungated 400 ms momentary readings for the reference distribution. Integrated loudness
  uses different gating and must be named separately. These definitions come from
  [ITU-R BS.1770-5](https://www.itu.int/dms_pubrec/itu-r/rec/bs/R-REC-BS.1770-5-202311-I%21%21PDF-E.pdf)
  and [EBU Tech 3341](https://tech.ebu.ch/files/live/sites/tech/files/shared/tech/tech3341v4_0.pdf).
- With a complete song map, use the 95th percentile of valid momentary readings as the fixed
  song reference. *Judgement.* Convert its level to linear reference power `R` using the same
  measurement convention. The default normalised power is `p = P / R`, for measured power
  `P`; the programme reference is unity, not a promise that every song's loudest bar reaches
  90% height. Programme loudness alone does not specify the loudest spectral band.
- Bound gain and reject an all-silent reference. Apply the bounded power gain to every driver;
  `p = P / R` is the unclipped-reference case. Initial defaults are reference power 0.01,
  maximum gain +24 dB and maximum attenuation 24 dB. *Judgement*, in the declared linear-power
  convention. These bounds also apply to a map; silence or a near-silent recording must not
  turn quantisation noise into a full picture.
- Without a map, keep a causal estimate of the high-level reference. Start at the fixed default;
  follow a rising reference with a 1 s time constant and a falling reference with 15 s.
  *Judgement.* Do not learn a new reference from digital silence. A causal analyser cannot know
  whether the opening passage will later prove to be a quiet intro; that guarantee requires
  future context.
- When a map arrives, move the global gain in the logarithmic domain over 2 s (*judgement*),
  then keep it fixed. Do not renormalise each visible band during the handover. Repeated seeks
  within the same track retain the same map reference.
- For display height use `h = clamp((p^0.25 - f^0.25) / (1 - f^0.25), 0, 1)`,
  where `f = 10^(-50/10)`. This is a perceptually motivated artistic compression,
  *judgement*, not an ISO loudness model or a claim about a listener's acoustic loudness.
  Power steps of 0, -10, -20 and -50 dB yield about 1, 0.536, 0.275 and 0.
  An amplitude gain `g` multiplies power by `g*g`, not `g`.
- ISO 226 describes equal-loudness contours for tones under specified listening conditions;
  ISO 532-1 specifies a loudness method. Neither validates this simple exponent as a universal
  loudness law. See [ISO 226:2023](https://www.iso.org/standard/83117.html) and
  [ISO 532-1:2017](https://www.iso.org/standard/63077.html).
- Surprise may compare a feature with its recent approximately 4 s history, and onset detection
  may normalise bands internally. Both need a denominator floor and a silence guard. Neither
  may overwrite the level driver. Expose clipping/saturation in diagnostics.

Checked by: exact power-to-height steps with gain held fixed; repeated passages separated by
12 dB; loud-to-quiet and quiet-to-loud entries; a quiet intro with and without a pre-existing map;
map arrival and an all-silent track. With a map, and during the first 10 s after a loud-to-quiet
step without one, the settled quiet display level should be at least 30% lower. *Judgement.*
Compare after the declared fast-envelope settling time, not during its intended release tail.

## Two speeds

Each continuous energy driver provides the following default envelopes:

| Speed | Rise time constant | Fall time constant | Usual visual job |
|---|---|---|---|
| Fast | 10 ms | 120 ms | Size, brightness, a push |
| Slow | 100 ms | 2 s | Camera, zoom, glow amount, density |

All four constants are *judgement*. They are exponential time constants, not meter integration
times or time-to-peak specifications. Smooth the normalised display driver once in analysis.
Do not smooth event timestamps or confidence into a level. Peak caps hold for 0.5 s then fall
at 12 dB/s in the power domain before conversion to height. *Judgement.*

Drawings may map a driver through a fixed curve, integrate velocity, or run damped physics.
Those responses must be declared, including added delay, and must not secretly renormalise or
repeat analysis smoothing. Camera inertia and particle lifetime are artistic behavior, not a
second measurement of the signal.

Checked by: analytic step responses and equal-media-time replay at different analysis and
display rates. Report the fast driver's peak lag separately from event timing.

## Onsets and events

- Use log-compressed spectral flux with maximum-filter vibrato suppression as the baseline.
  SuperFlux applies this on a logarithmically spaced filterbank, not simply on three neighbouring
  linear FFT bins. Record filter spacing, compression, temporal lag and maximum-filter width.
- Its published peak picker uses a local maximum, a local **mean** plus an offset, and a
  combination interval. A median is an adaptation to evaluate, not an exact description of the
  method. At our chosen hop, retune parameters on training fixtures and pin them before scoring.
- A causal picker can use only past data. Any future peak-confirmation window adds availability
  delay and must fit the measured lookahead budget. Backdating the resulting timestamp does
  not make the result available earlier.
- The paper's ±25 ms matching window and merging convention are evaluation choices, not a
  guarantee that every onset is found. See
  [Böck and Widmer (2013), sections 2 and 3](https://www.dafx.de/paper-archive/2013/papers/09.dafx2013_submission_12.pdf).
- Low-band, body/crack and high-band transients are useful independent event kinds. Initial
  ranges are 40-150 Hz, 150-300 Hz plus 1-5 kHz, and 6-16 kHz, capped to available bandwidth.
  *Judgement.* These are kick-like, snare-like and hat-like cues, not instrument identification.
  A plucked bass note can occupy the same band as a kick. Multiple kinds may coincide.
- Event strength must reflect shared-reference energy; detection confidence and surprise are
  separate. A small event clearing a quiet adaptive threshold must not become a full-strength hit.
- Publish an ordered event stream with generation and sequence IDs. In uninterrupted playback
  each consumer receives all available events in `(previousMediaTime, currentMediaTime]` once.
  Taking only the maximum strength loses multiplicity when two hits share a display interval.
- Publish how far event detection is complete. If confirmation arrives after a consumer passed
  the event's timestamp, offer it once with its lateness or discard it under a declared lateness
  budget and count the discard. It must not disappear silently behind the cursor, acquire a
  falsely current timestamp, or cause playback to wait.
- Size retention for the supported rate/stall budget. Overflow must be observable. After a seek,
  long suspension or overwritten history, reset the cursor and discard missed visual bursts
  under a documented catch-up policy, rather than replaying them as one flash. Exactly-once
  delivery does not mean an unlimited queue or guaranteed presentation of every late event.
- Distinguish detected onsets from estimated grid beats. Phase corrections and map handovers
  must not emit duplicate beat events or retroactive flashes.

Checked by: impulses, drum stems, bass-only notes, bass plus kick, vibrato, tremolo, sustained
tones, double hits and fast subdivisions. Require exact counts on unambiguous synthetic
fixtures; report precision/recall and timestamp errors on mixtures. Event-delivery tests inject
known events independently of detection, including two events inside one display interval.

## Beat, pitch and musical structure

- A beat grid is an estimate even when computed offline. Beat, tempo, meter, downbeat, key and
  section confidence must remain separate. A confident tempo does not establish the downbeat.
- A live tracker may begin with 6 s of history and a soft tempo prior near 120 BPM. *Judgement*,
  informed by [Ellis (2007)](https://www.ee.columbia.edu/~dpwe/pubs/Ellis07-beattrack.pdf).
  Support a declared tempo range and half/double-time ambiguity; the prior must not suppress
  evidence for slow, fast, changing or non-4/4 music.
- Motion may lock to a grid only while its confidence is usable. Start with an entry threshold
  of 0.6 and an exit threshold of 0.4 sustained for 1 s. *Judgement*, to calibrate per tracker.
  A normalised correlation or stability value is not automatically a probability of correctness.
  When evidence expires, fade to level-driven motion; do not keep emitting confident beats.
- A phase handover may slew over one known bar, or 2 s when meter is unknown. *Judgement.*
  Slew continuous motion only. Deduplicate discrete beats and avoid a hard cut during an
  ambiguous handover. A major disagreement drops the lock before reacquisition.
- Meter may be unknown or change. Do not manufacture downbeats by counting every fourth beat
  or claim every sixteen beats is a phrase. Predicted downbeats and sections need their own
  evidence and fixtures.
- Pitch-class/key analysis needs suitable frequency resolution, harmonic treatment, tuning
  tolerance and a confidence/missing-value rule. Use a separately timed longer window when
  needed. FFT bin spacing alone is not a universal 400 Hz limit on pitch estimation.
  Key profiles are a baseline hypothesis; unpitched, ambiguous or non-Western material may
  correctly return unknown. Hold or gently fade the palette in that case.
- Automatic scene/palette changes and camera cuts need a confident musical boundary or drop.
  Combine timbral/harmonic novelty, energy and rhythmic context where available; loudness alone
  cannot identify every section. A swell is not necessarily a new section.
- With no confident boundary, keep the scene and vary its continuous drivers. User-requested
  changes, recovery and accessibility controls remain allowed immediately. Randomness may pick
  a scene at an accepted boundary, with a reproducible seed; it must not invent musical timing.

Meier, Chiu and Müller report 74.72% beat F1 for RNN-PLP-On on GTZAN and use a ±70 ms scoring
window. That window is not end-to-end latency; the paper's table also depends on the activation
model and evaluation conditions. These results are reference comparisons, not promised accuracy
for a lightweight flux tracker. See
[the 2024 paper, sections 5.4 and 6](https://www.audiolabs-erlangen.de/content/05_fau/professor/00_mueller/06_projects/78_learn/2024_MeierCM_RealTimePLP_TISMIR_ePrint.pdf).

Checked by: annotated clips with steady tempo, tempo changes, swing, syncopation, half/double-time
ambiguity, 3/4 and changing meter, rubato, ambient passages and silence. Score beat F1 separately
from downbeat F1, continuity, acquisition/reacquisition time and section-boundary delay.
Initial beat targets are 0.80 offline and 0.70 live on the pinned held-out corpus using ±70 ms.
*Judgement.* Report per-clip and per-category results, include warm-up failures, and do not tune
on the evaluation set. One hand-marked song is a smoke check, not qualification. For clear
synthetic live section changes the initial delay target is at most 2 s; report ambiguous cases
as such instead of forcing a boundary.

## Song scan and song map

- A scan requires an independently reopenable reader, selected-track identity and a decoder
  that can make progress without playing audio. Known duration and seekability alone do not
  establish those capabilities. Never seek or reuse the active playback reader for a scan.
- Run at most one scan at a time by default, with cancellation and bounded I/O, CPU and memory.
  *Judgement.* Playback always has priority. A low thread priority alone does not bound decode,
  storage, bandwidth, battery or thermal cost.
- Local independent files may scan automatically. Network scans require an explicit application
  policy for additional reads/downloads; preserve the media's authentication and resolver rules.
  Single-read sources and live inputs use live analysis. Future web implementations must also
  account for the event loop, worker availability and cross-origin access.
- Scanning the next queue item is optional and consumes the same budget. Cancel on replacement,
  close or cancellation, and prevent late results from an old request entering the new state.
- Stream features through the scan; do not retain a whole decoded song in memory. Record coverage
  and completion. A partial map is usable only where it has valid data, and its loudness reference
  must not be represented as the final whole-song reference.
- Cache by content/version identity, selected audio stream, trim/timeline convention and analysis
  version. A URL alone is insufficient. Keep authentication material out of cache keys and logs.
  Use a bounded session cache, and invalidate changes to those inputs.
- The map can hold beats, downbeats, tempo/meter segments, section/drop candidates, key/chroma
  summaries and programme reference statistics. Every estimated field may be absent, and fields
  need not become ready together. Store sparse events/segments rather than redundant full-rate
  copies of every feature.
- Playback and scan must use the same sample/timestamp, encoder-delay, padding, edit-list and
  preroll conventions. Using the same decoder library does not itself make offsets cancel.
  Verify shared feature timestamps within one 10 ms hop on the same decoded PCM; evaluate
  live/offline detector differences separately.

Checked by: PCM and compressed fixtures with known leading padding/nonzero timestamps, selected
alternate audio tracks, seeks near the start and end, independent custom readers, slow network
input, cancellation and a changing source at the same URL. The scan must not change playback
position, starve its input, or require a second audio sink.

## How a drawing uses drivers

The audio must be the obvious cause of the main motion, but an expressive drawing need not be a
literal meter. These are default roles, not a ban on deliberate, documented alternatives.

| Driver | Useful role |
|---|---|
| Fast level | Size, brightness and pushes |
| Slow level | Camera, zoom, glow amount and density |
| Event | Spawn, jump, cut or bounded accent |
| Trusted beat phase | Cyclic motion |
| Confident pitch/key | Colour |
| Spectral centroid/flatness | Brightness, sharpness and texture |
| Confident section context | Scene choice and broad density |

Each drawing must declare the driver-to-property mapping, fixed transforms, envelope/physics
response, required capabilities, missing-data behavior and quality controls in machine-readable
form. Fixed mappings and restrained secondary noise are allowed. Hidden automatic rescaling that
makes quiet and loud passages equally busy is not.

Checked by: equal-duration renders at fixed size, seed and frame rate. After a declared settling
period, silence should produce at most 20% of the music render's mean frame-to-frame image change.
*Judgement.* Define the metric and a minimum nonzero music response so a frozen image cannot pass.
Test level steps and individual driver injections against the declared property, with click
timing measured on the relevant response rather than the maximum motion anywhere in the frame.
For sustained tones allow the initial onset, but require no continuing beat train. Compare aligned,
time-shifted and unrelated audio, including similar-loudness clips, in a listening/viewing review.
Image-change scores and source-text lint alone do not establish musical correspondence.

## Rendering and accessibility

- Draw sharp foreground shapes and lines at the output's physical resolution. Glow is an
  additional soft layer, off unless requested by the drawing; it must not replace sharp detail.
  Soft fields and trails explicitly designed to be soft may use reduced buffers with a declared
  quality floor. Sharp history detail follows the foreground rule.
- Express trails as a half-life: `retention = 2^(-dt / halfLife)`, ordinarily 50-250 ms.
  *Judgement.* History drawings may use longer values. Stabilise feedback gain and reset or
  reproject history on resize, discontinuity and graphics-context loss.
- Production full-screen per-pixel effects must use a GPU-capable path. A shader compiled by
  Skia does not prove its destination is GPU-backed. CPU reference renders are valid test
  oracles; they are not production-performance evidence.
- Reuse the rasterised scene across finishing passes; keep feedback on the GPU where supported.
  Bound texture uploads and avoid full-frame GPU readback in the live path. Small CPU simulation
  grids or soft compatibility layers need explicit size/time budgets.
- Drawings declare how quality can decrease, but a surface-wide governor owns the total budget,
  including two scenes during transitions, glow, history and UI composition. Preserve timing and
  sharp detail first. A fallback must remain intentional and visible, not blank or falsely named.
- Preserve supported Android versions. RuntimeShader-dependent drawings need a compatible
  alternative or honest unavailability on older devices and software canvases. Account for
  compilation failure, resize and context loss without interrupting playback.
- Reuse audioviz-owned buffers and hot-loop objects after warm-up. Measure allocations per
  analysis hop and per presented frame, including native wrappers. Framework allocations must
  be reported separately; a blanket zero-allocation claim needs a profiler.
- Provide reduced-motion and disable-visualisation controls. Reduced motion suppresses flashes,
  aggressive zoom, shake and rapid cuts while retaining a useful spectrum or restrained scene.
  Stopping visualisation must not stop audio playback.

Checked by: full-resolution still/detail checks and captured motion at every quality level;
resize, context loss, fallback and repeated scene transitions; allocation and resource-lifetime
profiles. Capture the final composition, including finishing, rather than judging a scene before
its glow and transition.

## Flash limits

The complete visual output must respect the flash policy, including shaders, motion patterns,
palette changes, feedback, transitions and finishing. Limiting explicit flash events alone
cannot enforce it.

The common guard must constrain temporal luminance and red transitions in the final composite,
or enforce an equivalent bound on every contributing layer. Capture tests qualify that guard;
they do not replace it. A guard that sees only explicit flash commands does not meet this contract.

WCAG 2.2 defines a general flash through opposing relative-luminance changes of at least 0.10
when the darker state is below 0.80, and defines red flashes separately. Its area exception is
25% of a **10-degree visual field**, not 25% of the entire screen. See
[WCAG 2.2, flash definitions](https://www.w3.org/TR/WCAG22/#dfn-general-flash-and-red-flash-thresholds).

- Default to no more than three qualifying general flashes in any rolling one-second interval,
  across the output, without relying on the area exception. Prohibit saturated-red flashing.
  This is the project's conservative policy.
- A 360 ms minimum interval between deliberate flash starts is an additional authoring guard,
  *judgement*. It is not a WCAG constant and does not account for uncommanded flashes.
- Assess the actual colour-managed output. Digital RGB percentages do not specify cd/m²;
  remove any conversion such as '20 cd/m² equals 2% of phone white'. HDR needs a separately
  qualified output policy; an SDR capture cannot establish HDR flash behavior.
- Unqualified custom shaders/presets must not inherit a safety claim from the host's event limiter.
  Use a qualified restrained fallback in the default profile until their output is checked.

Checked by: captures of every built-in drawing and transition, extreme user parameters, repeated
palette changes and dense event bursts, with a rolling-window flash analyser and known pass/fail
fixtures. Include 200 BPM clicks and subdivisions; that click track alone is insufficient.
The runtime guard, reduced-motion controls and captured-output qualification work together.
[ITU-R BT.1702](https://www.itu.int/rec/R-REC-BT.1702/en) is additional television-specific guidance,
not a universal phone-brightness conversion.

## Performance qualification

The intended floor is an Adreno 610-class phone at 1920x1080 physical output pixels and 60 Hz.
*Judgement.* Record the actual model, OS/API, graphics backend, release build, thermal condition,
audio format and output route. A GPU class alone is not a reproducible test device.

- Live-analysis target: at most 2% of one core, measured as thread CPU time per elapsed playback
  time. At 100 analyses/s that averages 0.2 ms per hop. *Judgement.* Report p95/worst hop and
  feed-copy times as well; the mean must not hide occasional starvation.
- Render target: sustain 60 fps at the declared quality after warm-up, with p95 presentation
  interval at most 1.1 refresh periods and fewer than 1% missed presentation deadlines over
  10 minutes. *Judgement.* Record maximum stalls, CPU preparation, GPU time where measurable,
  memory, allocations, temperature and chosen quality. Higher refresh rates need their own budget.
- Measure scene-only, feedback, finishing and two-scene transitions, then measure the complete
  player with audio, UI and an active scan. Include every built-in drawing that is advertised as
  available on the target. An unavailable preset is not a passing performance sample.
- Song-scan targets: less than 5 core-seconds for a four-minute fixture and a serialised map below
  2 MB. *Judgement*, unmeasured targets. Report wall time, format, bandwidth, peak working memory
  and cache size separately. Decoder cost and network waits prevent a universal five-second
  completion promise.
- Quality changes must be reported beside performance. A lower-resolution trail is a tradeoff,
  not a claim of equal-work acceleration. No visual work may introduce an audio underrun.

The existing [performance notes](../kiteplayer-audioviz/PERFORMANCE.md) report specific desktop
measurements and remaining device limits. They do not qualify this new standard. Unit tests,
CPU raster surveys, visible GPU-window measurements and physical mobile measurements must remain
separate evidence.

## Module baseline before implementation

Source checked at KitePlayer `acd0def` on 2026-09-19. This records the starting point of the
overhaul, not the implementation's current status or a second issue tracker. The
[timing API contract](audioviz-timing-api.md),
[calibrated feature contract](audioviz-feature-api.md),
[event-delivery contract](audioviz-events-api.md),
[onset method](audioviz-onset-method.md),
[structure and key contract](audioviz-structure-api.md),
[song scan contract](audioviz-song-scan-api.md) and
[drawing mapping contract](audioviz-mapping-api.md) describe the subsequent implementation
contracts and their qualification limits.

| Area | Source at the baseline and implication for the overhaul |
|---|---|
| Audio feed | Core's [AudioTap](../kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioTap.kt) already supplies borrowed pre-conversion PCM and discontinuities. Keep its ownership contract; add only the clock/identity capabilities that the new delivery contract needs. |
| Clock | [AudioVizState](../kiteplayer-audioviz/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/audioviz/AudioVizState.kt) uses SmoothClock over player position plus a fixed 16 ms lead. Core position can expose a pending seek target. Coherent audible-clock publication needs core support; presentation prediction belongs to the actual surface/backend. |
| Analysis and events | [SpectrumAnalyzer](../kiteplayer-audioviz/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/audioviz/SpectrumAnalyzer.kt) currently averages channels, uses maximum-bin bands, a separate long bass FFT and allocated snapshots. [SpectrumTimeline](../kiteplayer-audioviz/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/audioviz/SpectrumTimeline.kt) merges events by maximum strength. These are migration points, not compliance with this standard. |
| Scanning | No song-map implementation exists in audioviz. Core already has metadata inspection, decoder SPI and MediaIoFactory; metadata inspection is not a whole-track feature scan. Keep feature algorithms in audioviz and reader/decoder access behind neutral contracts. Adapt `kiteplayer-ffmpeg` and `kiteplayer-network` only where those contracts require it. |
| Rendering | Audioviz owns feedback, finishing and platform shader wrappers. Existing CPU feedback and adaptive quality must be measured before replacement. A Kite3D change needs a demonstrated dependency or bottleneck, not an assumption that 3D is the expensive part. |
| Platforms | The audioviz build currently declares Android (minSdk 26), desktop JVM, iosArm64 and iosSimulatorArm64. It has no JS/Wasm target. Web and other suite targets are expansion work, not support established by common Kotlin code. |
| Authoring/tests | The public authoring API, director, mappings and shader inputs need a versioned migration. Existing MappingLintTest encourages per-frame percentile thresholds; revisit that rule against shared level semantics instead of preserving a test that defeats the new goal. |

The tracker review included open and closed issues and relevant comments in both repositories.
Existing work must be reused where applicable:
[KitePlayer #130](https://github.com/yuroyami/KitePlayer/issues/130) covers the delivered tap,
[#131](https://github.com/yuroyami/KitePlayer/issues/131) the original module,
[#85](https://github.com/yuroyami/KitePlayer/issues/85) metadata inspection,
[#34](https://github.com/yuroyami/KitePlayer/issues/34) independent descriptor reads, and
[#70](https://github.com/yuroyami/KitePlayer/issues/70) video presentation feedback.
The latter does not by itself timestamp a Compose visualiser.
[KiteFFmpeg #30](https://github.com/yuroyami/KiteFFmpeg/issues/30) tracks filter availability;
this design must not assume `loudnorm` or `ebur128` is already shipped as its analysis engine.

Changes to public contracts must preserve source compatibility or declare the migration and
regenerate affected ABI dumps. Keep live analysis usable from plain PCM and independently of
default-player construction, scanning and Compose rendering, following
[the module contract](module-contract.md). Implement clock/ownership and calibrated feature
contracts before retuning drawings; otherwise each drawing will compensate for unstable inputs.

## Research provenance

The starting collection is
[willianjusten/awesome-audio-visualization](https://github.com/willianjusten/awesome-audio-visualization).
It is a discovery list of libraries, experiments and learning resources, not a benchmark corpus
or validation of every technique it links. This review checked the cited primary papers and
selected project documentation; it is not an execution audit of the entire collection.

Useful lessons from the linked projects, without adopting their constants as standards:

- [Meyda](https://github.com/meyda/meyda): keep feature extraction usable for both live and offline
  input. Our timing and channel conventions must stay identical across those paths.
- [Clubber](https://github.com/wizgrav/clubber): small, named feature vectors and explicit mappings
  are useful for shader authors. Adaptive band ranges and update-dependent smoothing are choices
  to evaluate, not a reason to erase the shared level reference.
- [audioMotion-analyzer](https://github.com/hvianna/audioMotion-analyzer): spectrum layout, channel
  handling, frequency scale and display weighting are distinct controls. A visually convincing
  spectrum is not evidence of beat, key or section recognition.
- [audiowaveform](https://github.com/bbc/audiowaveform): compact min/max waveform envelopes are a
  useful representation for history views. They are not an onset detector or a beat grid.

Keep a reference's revision, parameters and relevant section with any implemented algorithm or
comparison fixture. Record adaptations explicitly. Under this repository's copying policy,
GPL, LGPL, AGPL and unlicensed implementation source is study only and must not be copied or
transliterated. Permissive code reuse still requires its licence and attribution to be checked.
No dependency is introduced merely because it appears on the list.
