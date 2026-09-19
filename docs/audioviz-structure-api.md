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

The detector summarises every 100 ms block of analyses into three features:

- **Timbre shape**: the log power of ten groups of four ERB bands, each floored 60 dB below the
  block's loudest group, with the block's mean log power removed. A change of gain therefore
  leaves it unchanged.
- **Onset density**: detected broadband onsets per second.
- **Level**: the ungated 400 ms K-weighted programme level, in dB.

A block below -60 dB relative to full scale is silent. A window that is more than half silent
supplies no evidence, so music starting after silence is not a section boundary.

Harmony is not a live feature. Within the 1.2 seconds after a candidate, a change of mode or key
on one instrument looks the same as an ordinary change of chord: both measured a cosine distance
of about 0.36 from the key's average profile. Such changes are left to the song scan, which can
look further ahead. The key estimate below still uses harmony.

### Decision

For a candidate boundary at the start of block `b`, the detector compares the eight seconds
before it with the 1.2 seconds after it (*judgement*). Eight seconds covers two cycles of a
four-second chord progression, so a chord that returns once per cycle is not new. No boundary
can be found in the first eight seconds after a start or a seek.

| Evidence | Measure | Zero at | One at |
| --- | --- | ---: | ---: |
| Timbre | Shift of the mean shape, in units of the spread of the eight seconds before, at least 1 dB | 2 | 5 |
| Rhythm | Absolute log2 ratio of onset densities, each plus one onset per second | 0.6 | 1.6 |
| Level | Absolute level difference | 3 dB | 9 dB |

Timbre and rhythm evidence are not capped at one. The score is the root sum of squares of the
timbre and rhythm evidence, multiplied by `0.8 + 0.2 * level`, with level evidence capped at
one. Level alone therefore cannot produce a boundary: a swell or a sudden gain step of one sound
keeps its timbre shape and rhythm. One loud onset barely moves the density of 1.2 seconds.

A candidate is published when its score is a local maximum within 0.4 seconds on either side and
reaches 0.5, and when no boundary was published in the four seconds before it (*judgement*). The
uncapped score locates the peak where both windows are purest; the event's confidence is the
score capped at one, and the boundary gate acts at 0.6 and above. The event's time is the
strongest broadband onset inside the peak's block, or the block's start when it has none.

A decision is available 1.6 seconds after its block plus up to one block. The structural
watermark is the end of the newest decided block: nothing earlier will be published.

### Classification

| Kind | Condition, in addition to a published score |
| --- | --- |
| `Drop` | Level rises by at least 6 dB and onset density after the boundary is at least 2 per second |
| `Breakdown` | Level falls by at least 6 dB and onset density after the boundary is below half of before |
| `SectionBoundary` | Any other published boundary |

Event strength is the mean shared-gain overall height after the boundary. Surprise is the timbre
evidence, capped at one. None of these values is a calibrated probability.

## Key and pitch classes

### Long window

Key analysis uses its own window, separate from the 10 ms transient spectrum. Its length is the
largest power of two no longer than 0.4 seconds: 16384 samples at 44.1 and 48 kHz. It runs every
200 ms on the power of the first two channels, packed into one complex transform. Its timestamp
is its own window centre.

### Pitch-class profile

1. Pick spectral peaks from 80 Hz to 5 kHz: local maxima at least 10 dB above the median of
   the 51 bins around them and within 60 dB of the strongest peak. At 6 dB, random maxima of
   noise passed often enough to make noise look pitched. Refine each peak's frequency by
   parabolic interpolation of its log magnitude.
2. Estimate tuning as the magnitude-weighted circular mean of each peak's deviation from the
   nearest equal-tempered semitone at A4 = 440 Hz. Smooth it with a 10 second time constant.
3. Treat each peak as a possible harmonic `h` of 1 to 4 of a fundamental at `f / h`, with weight
   `0.6^(h-1)`. Spread each contribution over the nearest pitch classes with a squared-cosine
   window four-thirds of a semitone wide, after the tuning correction. This follows the
   harmonic weighting of harmonic pitch-class profiles; it is a baseline, not a claim of benefit.
4. A frame is pitched when its peaks hold at least 20 percent of the power from 80 Hz to 5 kHz
   (*judgement*). Unpitched frames do not update the key; drums and noise stay unknown.

Each pitched window's profile is normalised to a largest value of one. `SpectrumFrame.chroma`
smooths those profiles with a one second time constant and decays toward zero during unpitched
audio.

### Key decision

The key accumulator averages pitched profiles with an 8 second time constant. It is compared by
correlation with the 24 rotations of the Krumhansl-Kessler major and minor key profiles, from
[Krumhansl (1990), Cognitive Foundations of Musical Pitch](https://doi.org/10.1093/acprof:oso/9780195148367.001.0001).
The profiles are published data; they are a baseline hypothesis, not a model of every tradition.

The key is unknown until three seconds of pitched evidence exist, and whenever the best
correlation is below 0.5 or leads the best different key by less than 0.05 (*judgement*). Eight
seconds of unpitched audio discard the evidence, so a long drum passage returns to unknown.
Confidence rises from zero at those limits to one at a correlation of 0.8 and a lead of 0.15. The
eight second average is what keeps the key steady; no separate switching delay is added.

`SpectrumFrame.key` holds a `KeyEstimate` or null for unknown: tonic pitch class with C as zero,
major or minor mode, confidence, correlation, lead over the next key, tuning in cents and the
window it describes. `keyHue` places the key on the circle of fifths, with a minor key at its
relative major, and `keyConfidence` is its confidence, zero while unknown.

### Palette

The palette leans towards the key hue only while a key is known. The lean comes in over two
seconds and its hue moves around the circle at most a quarter turn per second. When the key
becomes unknown, the lean keeps its last hue and fades out over eight seconds instead of snapping
back (*judgement*).

## Meter and downbeats

No meter or downbeat is estimated. `barPhase` and `phrasePhase` stay zero and the gestures report
no bars or phrases. Counting pulses is not structure. A future estimator needs its own
confidence, timestamps, fixtures and metrics, separate from tempo.

## Development evidence on 2026-09-19

These synthetic fixtures chose the parameters above. They cannot be relabelled as held-out data.
Each fixture joins two 20 second passages at 20 seconds, fed as stereo at 48 kHz.

| Fixture | Decision | Placed at | Confirmed after the change |
| --- | --- | ---: | ---: |
| Quiet pad into a drum loop | `Drop` | 20.005 s | 1.54 s |
| Drum loop into a quiet pad | `Breakdown` | 20.029 s | 1.64 s |
| Pad into piano-like chords at a similar level | `SectionBoundary` | 20.005 s | 1.54 s |
| C major chords into F sharp major chords | `SectionBoundary` | 21.015 s | 2.54 s |
| C major chords into A major chords | `SectionBoundary` | 19.829 s | 1.44 s |
| C major chords into C minor chords | None | | |

The two key changes were found through the shift of register in the timbre shape, not through
harmony; the F sharp case peaked a chord late and is confirmed inside the three second structural
budget but outside the two second target. The mode change was not found. A 40 second pad swell, a
pure tone rising 40 dB, a pure tone stepping up 40 dB, one loud hit in a pad, 60 seconds of a
steady drum loop and a drum loop after ten seconds of silence produced no decision.

Key estimates after 12 to 14 seconds of I-IV-V-I progressions with six harmonics per note:

| Fixture | Estimate | Confidence | Correlation | Lead | First known |
| --- | --- | ---: | ---: | ---: | ---: |
| C major | C major | 1.00 | 0.957 | 0.209 | 3.15 s |
| A harmonic minor | A minor | 0.27 | 0.871 | 0.077 | 3.75 s |
| G major, 45 cents sharp | G major, tuning 44.4 cents | 1.00 | 0.988 | 0.357 | 3.15 s |

A change from C major to F sharp major was followed 5.55 seconds later. Drums without pitched
content, noise, silence and a pure-tone C6 chord, which fits C major and A minor almost equally,
gave no key. Twelve seconds of drums after a known key returned it to unknown.

Held-out section delay, key accuracy and failure behaviour on real music remain unqualified until
a pinned corpus is scored under the standard's rules.
