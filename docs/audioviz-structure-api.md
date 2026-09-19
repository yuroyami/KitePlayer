# Musical structure and key

This contract covers the live structural detector and the key estimate of the
[audio visualiser standard](audioviz-standard.md). Scene changes consume the structural events
under the [rhythm and boundary contract](audioviz-rhythm-api.md), and the events travel through
the structural source of the [event delivery contract](audioviz-events-api.md). Every number
marked *judgement* is a project choice tuned on development fixtures, not a qualified result.

## Section boundaries, drops and breakdowns

The analyser runs a causal section detector on the analysis worker. It never looks at audio that
has not played. It publishes its decisions as `SpectrumFrame.structure`, a batch of the
`LiveStructure` source.

### Features

The detector summarises every 100 ms block of analyses into four features:

- **Timbre shape**: the log power of ten groups of four ERB bands, with the block's mean log power
  removed. Loudness therefore does not change it.
- **Harmony**: the short-term pitch-class profile from the key analysis below, when the block is
  pitched.
- **Onset density**: detected broadband onsets per second.
- **Level**: the ungated 400 ms K-weighted programme level, in dB.

A block below -60 dB relative to full scale is silent. A window that is more than half silent
supplies no evidence, so music starting after silence is not a section boundary.

### Decision

For a candidate boundary at the start of block `b`, the detector compares the three seconds before
it with the 1.2 seconds after it (*judgement*). Each feature gives evidence in 0..1:

| Evidence | Measure | Zero at | Full at |
| --- | --- | ---: | ---: |
| Timbre | Shift of the mean shape, in pooled standard deviations of both windows | 2 | 5 |
| Harmony | Cosine distance of the mean profiles, when both windows are pitched | 0.15 | 0.45 |
| Rhythm | Absolute log2 ratio of onset densities, each plus 0.5 onsets/s | 0.6 | 1.6 |
| Level | Absolute level difference | 3 dB | 9 dB |

The novelty score is the root sum of squares of the timbre, harmony and rhythm evidence, capped at
one, then multiplied by `0.8 + 0.2 * level`. Level alone therefore cannot produce a boundary: a
swell of one sound keeps its timbre, harmony and rhythm. One loud onset barely moves a
three-second mean.

A candidate is published when its score is a local maximum within 0.4 seconds on either side and
reaches 0.5, and when no boundary was published in the four seconds before it (*judgement*). The
score becomes the event's confidence; the boundary gate acts at 0.6 and above. The event's time is
the start of its block, moved to the strongest broadband onset within 150 ms when one exists.

A decision is available 1.6 seconds after the boundary plus up to one block, so a clear change is
confirmed within two seconds. The structural watermark trails the newest block by the same 1.6
seconds: nothing earlier will be published.

### Classification

| Kind | Condition, in addition to a published score |
| --- | --- |
| `Drop` | Level rises by at least 6 dB and onset density after the boundary is at least 2 per second |
| `Breakdown` | Level falls by at least 6 dB and onset density after the boundary is below half of before |
| `SectionBoundary` | Any other published boundary |

Event strength is the mean shared-gain overall height after the boundary. Surprise is the timbre
evidence. None of these values is a calibrated probability.

## Key and pitch classes

### Long window

Key analysis uses its own window, separate from the 10 ms transient spectrum. Its length is the
largest power of two no longer than 0.4 seconds: 16384 samples at 44.1 and 48 kHz. It runs every
200 ms on the power of the first two channels, packed into one complex transform. Its timestamp
is its own window centre.

### Pitch-class profile

1. Pick spectral peaks from 80 Hz to 5 kHz: local maxima at least 6 dB above the median of
   their neighbourhood and within 60 dB of the strongest peak. Refine each peak's frequency by
   parabolic interpolation of its log magnitude.
2. Estimate tuning as the magnitude-weighted circular mean of each peak's deviation from the
   nearest equal-tempered semitone at A4 = 440 Hz. Smooth it with a 10 second time constant.
3. Treat each peak as a possible harmonic `h` of 1 to 4 of a fundamental at `f / h`, with weight
   `0.6^(h-1)`. Spread each contribution over the nearest pitch classes with a squared-cosine
   window of one semitone width, after the tuning correction.
4. A frame is pitched when its peaks hold at least 20 percent of the power from 80 Hz to 5 kHz
   (*judgement*). Unpitched frames do not update the key; drums and noise stay unknown.

`SpectrumFrame.chroma` is this profile smoothed over about one second and normalised so that its
largest value is one. It is zero while the audio is unpitched.

### Key decision

The key accumulator averages pitched profiles with an 8 second time constant. It is compared by
correlation with the 24 rotations of the Krumhansl-Kessler major and minor key profiles, from
[Krumhansl (1990), Cognitive Foundations of Musical Pitch](https://doi.org/10.1093/acprof:oso/9780195148367.001.0001).
The profiles are published data; they are a baseline hypothesis, not a model of every tradition.

The key is unknown until three seconds of pitched evidence exist, and whenever the best
correlation is below 0.5 or leads the best different key by less than 0.05 (*judgement*).
Confidence rises from zero at those limits to one at a correlation of 0.8 and a lead of 0.15. A
new key replaces the current one only after it has led for three seconds.

`SpectrumFrame.key` holds a `KeyEstimate` or null for unknown: tonic pitch class with C as zero,
major or minor mode, confidence, correlation, lead over the next key, tuning in cents and the
window it describes. `keyHue` places the key on the circle of fifths, with a minor key at its
relative major, and `keyConfidence` is its confidence, zero while unknown.

### Palette

The palette leans towards the key hue only while a key is known. The hue slews around the circle
at most a quarter turn per second. When the key becomes unknown, the lean keeps its last hue and
fades out over eight seconds instead of snapping back (*judgement*).

## Meter and downbeats

No meter or downbeat is estimated. `barPhase` and `phrasePhase` stay zero and the gestures report
no bars or phrases. Counting pulses is not structure. A future estimator needs its own
confidence, timestamps, fixtures and metrics, separate from tempo.

## Qualification boundary

Development fixtures are synthetic and were used to choose these parameters. They cannot be
relabelled as held-out data. Held-out section delay, key accuracy and failure behaviour on real
music remain unqualified until a pinned corpus is scored under the standard's rules.
