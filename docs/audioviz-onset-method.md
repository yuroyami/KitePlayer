# Onset method

This records the detector mathematics for the [audio visualiser standard](audioviz-standard.md).
The mathematical components are tested separately from recognition, event delivery and physical
presentation. Their presence alone does not qualify the live detector on music.

## Filterbank and difference

The baseline is the log-frequency, maximum-filter spectral difference described in
[Böck and Widmer (2013)](https://www.dafx.de/paper-archive/2013/papers/09.dafx2013_submission_12.pdf).
We author the implementation from the method; no reference-library source is copied.

Nominal frequency anchors have 24 steps per octave, referenced to 440 Hz, from 27.5 Hz to
`min(16_000 Hz, 0.95 * Nyquist)`. Round anchors to their nearest FFT bins, remove duplicate
bins, then construct triangular filters from consecutive triples. Each triangle has unit
height and is not normalised by its area. The actual centres and boundaries are the resolved
FFT-bin frequencies. Narrow low-frequency filters consequently merge; they do not gain
resolution that the transform lacks. This rounding/deduplication rule is our explicit choice,
consistent with the paper's 138 filters at 44.1 kHz and 2048 points.

The input is the calibrated per-bin tone-amplitude spectrum, averaged in channel power before
amplitude conversion. Apply `log10(1 + 512 * filteredAmplitude)`. The fixed factor 512 is a
project adaptation that makes compression independent of transform length; it approximates
the unnormalised Hann FFT magnitude scale for a 2048-point transform. It is not the display
gain and must not follow the song reference. Detection thresholds require separate training
because this is not an exact reproduction of the paper's input convention.

Compare the current log-filterbank value with the largest previous value within one neighbouring
filter on either side. Retain only positive differences. The maximum filter acts on the previous
log-frequency spectrum, not on linear FFT bins or the resulting flux. A partial above-bandwidth
range supplies no fabricated frequencies. The first complete lag of observations primes history
without manufacturing an attack after attachment or reset.

Choose the comparison lag using the Hann window's half-height point: round the distance from
its first sample above 0.5 to its centre, divided by the analysis hop, with a minimum of one.
At 44.1/48 kHz and a nominal 10 ms hop this is one analysis. At 44.1 kHz and a 5 ms hop it is
two. Changing this lag does not erase the window's availability delay. The continuous spectrum
and a detected attack use the distinct references described below.

## Causal peak selection

The picker sees one nonnegative scalar flux value per hop. It requires a local maximum against
the preceding 30 ms, a value above the mean of the preceding 100 ms plus a fixed offset, and
at least 30 ms since the last accepted event. Durations round up to whole hops. All windows
exclude future values; there is no hidden confirmation lookahead. A flat maximum emits once,
and a falling tail cannot emit as a fresh maximum. Threshold equality does not trigger.

The caller declares the flux aggregation and offset. These parameters must be pinned after
training on the synthetic/stem training fixtures and before scoring held-out mixtures. The
picker's threshold excess can support a confidence diagnostic, but is not a calibrated
probability and must not become event strength. Strength remains shared-reference energy.

## Live integration and training fixtures

`BeatDetector` uses the mean of positive filter growth for each cue. The whole-spectrum cue
uses every filter. Low uses centres in 40-150 Hz; body uses 150-300 Hz and 1-5 kHz; high uses
6-16 kHz. Their fixed offsets are respectively 0.012, 0.080, 0.014 and 0.010 in mean log-growth
units. The low offset was raised from 0.018 after the mixed bass fixture exposed extra detections
on an attack's decaying tail. The combination interval remains 30 ms for all four cues.

The live analyser feeds this detector only complete windows. Continuous spectral features use
the window centre. Detections use the midpoint of the newest input hop:
`availableThroughMicros - hopSamples * 500_000 / sampleRate`. This is a causal attack-time
estimator chosen for this implementation, not a timestamp convention claimed by the paper.
The detection-complete watermark advances to that reference. Availability remains the end of
the consumed window; worker or display time never substitutes for the original estimate.
Event energy is computed separately
from calibrated power and the shared gain. Fixed compression and detection offsets do not
change when a song reference changes. The low-band cue does not use a long refractory period
that would discard fast subdivisions.

`SuperFluxTest` checks filter resolution, triangle response, bandwidth, finite values, history
ownership, comparison lag, neighbour suppression, past-mean selection and plateau handling.
`SuperFluxRecognitionTest` supplies explicit training causes:

| Fixture | Required result |
| --- | --- |
| Five impulses, including a 60 ms pair, at 8/16/44.1/48 kHz | Exactly five general onsets per rate, each within 25 ms of its source sample |
| A 1 kHz held tone entering after silence | One attack, no repeating event train |
| Sustained 1 kHz tone with 5 Hz, half-semitone vibrato | No fresh transient cues after the initial 200 ms |
| Three 80 Hz attacks over a sustained 55 Hz bass tone | Exactly three low cues, each within 25 ms |
| Eight 80 Hz attacks 75 ms apart at amplitudes 0.05/0.2/0.8 | Exactly eight low cues at every level, each within 25 ms |

These software timestamp tolerances are not physical sound/display latency. The fixtures were
used for development and parameter selection; they must not be presented as a held-out music
score. Vibrato and fast low attacks exposed failures in the preceding detector. The independent
event-delivery tests continue to inject records without depending on these recognition rules.

The JVM phase-offset fixture on 2026-09-19 measured the following impulse errors before and after
separating the attack estimate from the spectral centre.
Positive error means late. All five impulses were detected at each rate; their offsets vary
within the 10 ms analysis hop rather than all landing on a hop boundary.

| Sample rate | Centre-dated signed median | Centre-dated absolute p95 / max | New signed median | New absolute p95 / max |
| --- | ---: | ---: | ---: | ---: |
| 8 kHz | -7.125 ms | 14.750 ms | +3.875 ms | 5.000 ms |
| 16 kHz | -7.125 ms | 14.750 ms | +3.875 ms | 5.000 ms |
| 44.1 kHz | -14.331 ms | 21.950 ms | +3.889 ms | 5.000 ms |
| 48 kHz | -12.459 ms | 20.084 ms | +3.875 ms | 5.000 ms |

The corrected rapid-low-attack fixture has signed median +7.5 ms and absolute p95/max 10 ms
at all three tested levels; held-tone and mixed-bass attacks are +5 ms. These observations
support the estimator on the named training signals. They do not establish the visible-click
target or accuracy on other music. The reports retain source-to-availability delay separately,
approximately 1.25-10 ms for the impulses. That delay does not include worker scheduling or
presentation and did not change with the timestamp correction.

These components do not identify instruments, estimate a beat grid, or establish a section
boundary. Tempo, key and section recognition require their own evidence. Threshold changes after
the first held-out evaluation require a new method version and a fresh evaluation split.
