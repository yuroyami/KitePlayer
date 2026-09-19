# Rhythm evidence and motion

The live tracker estimates periodicity and a pulse phase. It does not estimate meter, downbeats
or phrase boundaries. Those remain unknown until a separately qualified estimator supplies them.
Counting four or sixteen pulses must not create musical boundary evidence.

`RhythmEstimate` publishes the media reference time and availability time of the estimate,
primary and competing half/double-time pulse rates, separate tempo and phase support, and whether
the phase is currently usable. Support values are algorithm scores, not calibrated probabilities.
The phase belongs to its stated media reference, independent of the shorter spectral window.

The live baseline keeps six seconds of causal activation history, searches 40 through 240 BPM,
and re-estimates every 500 ms after enough evidence exists. Normalized autocorrelation identifies
periodicity. Alignment coverage distinguishes a pulse period from multiples that explain only
some attacks; a weak log-tempo preference near 120 BPM only breaks close ties. The alternate
octave remains visible when the unweighted evidence supports it. These are project choices,
not a reproduction or accuracy claim for [Ellis (2007)](https://www.ee.columbia.edu/~dpwe/pubs/Ellis07-beattrack.pdf).

A usable phase needs support at least 0.6 for one second. Support below 0.4 for one second exits
the lock; intermediate scores retain the current state. Missing attacks expire evidence after
two estimated pulse periods, with a one-second minimum. A major rate disagreement immediately
drops the lock before acquiring the new estimate. Reset clears all evidence and phase state.
Warmup, silence, unsupported history and expired evidence produce no predicted beat countdown.

Continuous visual cycles may keep moving at a level-driven pace while the phase is unavailable.
Acquisition and loss must slew continuous motion; an oscillator wrap is not a downbeat, a phrase,
or permission to change scenes. Timestamped detected attacks remain available independently of
the rhythm estimate.

The `MusicClock` frame overload follows only `rhythm.usable`, blends between the accepted rate
and free motion over two seconds, and gently corrects the nearest pulse within its local cycle.
The cycle origin is artistic. The old scalar overload is deprecated and remains available for
author-supplied cycles; built-in drawing calls use the frame overload.

Scene changes consume independently supported `SectionBoundary`, `Drop` or `Breakdown` events.
An `EnergyRise` event and the legacy scalar flags do not establish these classifications.
The common consumer gate requires support at least 0.6, a matching generation and analysis
revision, and a new event identity. It consumes duplicates without acting again. If several
boundaries arrive in one display interval it makes one scene decision, preferring a drop, then
a breakdown, then a generic boundary. This coalesces scene decisions, not event delivery.

The director's `minimumHoldSeconds` is a cooldown, not a timer that schedules a new scene.
With no accepted boundary it holds the scene and reports an unknown next-change time. Manual
selection remains immediate. A seek or track change does not cut a change already under way; it
finishes over its planned duration, and boundaries delivered meanwhile are consumed rather than
queued. The event vocabulary and consumer gate do not themselves recognize sections. The live
detector that publishes them, with its development evidence and its limits, is described in the
[structure and key contract](audioviz-structure-api.md).

The `VizDirector` constructor no longer takes `leastPhrases`, `mostPhrases` or
`secondsWithoutTempo`. Those parameters scheduled changes by counted phrases or elapsed time,
which this contract forbids. `minimumHoldSeconds` replaces them as a cooldown. This is a declared
authoring API migration: source and binary callers of the old constructor must update.

`Gestures.section` and `sections` describe accepted structural edges. Its `cyclePhase`,
`slowCyclePhase`, `cycles`, `slowCycles` and `cycleSeconds` describe artistic animation cycles.
The old bar/phrase edges, counts and phases are deprecated and report unknown rather than
manufacturing meter. Built-in preset changes use the structural edge; their ongoing animation
uses the visual cycles. Camera cuts and drop gestures use the same boundary gate as the director.

Built-in shader programs read `uCyclePhase` and `uSlowCyclePhase` for artistic motion. The
legacy `uBarPhase` and `uPhrasePhase` uniforms remain zero because no corresponding musical
structure is known. `uBeatUsable` explicitly distinguishes an accepted pulse phase from the
diagnostic tempo. The feedback warp runner publishes the same input block as every other shader
program, including these beat and cycle uniforms and the shared waveform gain.

Qualification must separate injected activation tests, generated audio integration, held-out
music recognition, and physical sound/display timing. None substitutes for another. The
declared range and lock thresholds must be included in held-out results, including acquisition,
expiry, reacquisition, octave ambiguity and failure to infer meter.

## Development evidence on 2026-09-19

The full normal JVM suite passed 256 tests and the iOS simulator suite passed 222 tests after
the rhythm and structural-consumer changes. These are software test results. Structural tests
inject accepted events independently of generated audio; they do not establish section detection.

On the 100 Hz injected activation fixtures, estimated pulse rates after 18 seconds were:

| Input pulse rate | Estimated rate |
| ---: | ---: |
| 44 BPM | 44.007 BPM |
| 52 BPM | 52.009 BPM |
| 80 BPM | 80.046 BPM |
| 128 BPM | 127.958 BPM |
| 176 BPM | 176.047 BPM |
| 224 BPM | 223.810 BPM |

The generated 128 BPM audio click fixture acquired a usable phase at 2.979 seconds, ended at
127.828 BPM, and had a median absolute phase error of 15.387 ms in its measured steady interval.
The generated 130 BPM drum loop settled at 130.157 BPM. The pad and pink-noise fixtures did not
publish a usable beat confidence. These are development cases, not a held-out beat F1 result,
an octave-ambiguity benchmark, or physical sound/display alignment.

Mutation checks failed when confidence timers or evidence expiry were weakened, slow/fast rates
were folded into the old range, unknown meter was converted into a phase, conflicting evidence
was blended, or motion ignored the usable-state flag. Structural-consumer checks separately
failed for weakened confidence/identity/reset gates, incorrect boundary priority, timer-driven
scene changes, unsupported preset/camera changes, and frozen shader cycles. Each mutation was
restored before the successful full runs.
