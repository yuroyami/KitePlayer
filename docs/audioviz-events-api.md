# Audio event delivery contract

This is the event API design for the [audio visualiser standard](audioviz-standard.md).
Recognition accuracy and visible response remain separate qualification claims.

## Detection and identity

An analysis publishes `AudioDetections` with the latest media sample available to the detector,
an inclusive detection-complete watermark, and zero or more `AudioDetection` values. Detections
are ordered by estimated media time. Every detection has a kind, its original estimated time,
its availability time, shared-reference energy strength, confidence and surprise. Strength is
not confidence. Simultaneous kinds remain separate records.

The live causal trigger estimates an attack at the midpoint of its newest input hop, while the
continuous spectrum retains its window-centre reference. Availability is the end of the consumed
window in both cases. See the [method and measured training errors](audioviz-onset-method.md).
Anticipation and scalar compatibility sampling use the event's own time, never its parent
feature's time. Only author-created frames without a detection batch use legacy scalar timing.

For event anticipation, `SpectrumTimeline.nextEvent(ptsMicros, kind)` returns the next retained
`AudioEvent` strictly after the requested media time. It preserves identity, original time,
availability and strength. `VizFuture.nextEvent(kind)` returns an `UpcomingAudioEvent` pairing
that event with `secondsUntil` in presentation time. The default implementation returns null;
custom futures must explicitly supply event lookahead. The player adapter applies playback rate,
the 100 ms presentation horizon, and generation/revision validity. `asFuture` assumes 1x media
time. Camera anticipation uses the next low transient directly; it cannot recover event strength
by sampling a continuous spectrum at the event's time. The scalar fields on a raw or interpolated
analysis are not a future-event API.

The watermark promises that this continuous detector will publish no more detections at or
before that time. A confirming detector must hold the watermark behind any pending candidate.
Publication rejects a decreasing watermark, decreasing availability time, unordered detections,
or a detection behind the previously published watermark. Unknown media time cannot enter the
player's event history.

`SpectrumTimeline` assigns each accepted detection an increasing sequence number within its
audio generation and local analysis revision. `AudioEvent` retains that identity and the original
detection. `EnergyRise` is a cue for an attack accompanying energy recovery; it does not assert
a formal section boundary. `SectionBoundary`, `Drop` and `Breakdown` are separate structural
classifications, consumed under the [rhythm and boundary contract](audioviz-rhythm-api.md).
The transient detector publishes the four transient kinds and `EnergyRise`. Structural kinds come
only from the structural source or a song map, described under event sources below. A reset replaces both feature and event histories;
a captured old publisher cannot write into the new history. Predicted grid beats are not
detected onsets and do not use these event kinds.

## Per-view delivery

`SpectrumTimeline.eventCursor()` creates a cursor for one consumer. Calling its `sample` with
the current media time delivers every retained event in `(previousMediaTime, currentMediaTime]`
in sequence order, including multiple hits of one kind. Sampling again does not repeat them.
Independent cursors do not consume one another's events.

If a detection arrives after a cursor has passed its original time, the cursor offers it once
with its lateness when that lateness is within its source's budget: 30 ms for a transient and
3 s for a structural confirmation. Older late detections are discarded and counted. This budget is in media time; presentation-time lateness
also depends on playback rate and the output route. An on-time event inside the normal display
interval is not classified as a late arrival merely because its timestamp precedes the current
frame's time.

Initial attachment, an explicit reset, a changed history, backward media movement, a media-time
advance over 250 ms, or an overwritten unread event resets the cursor at the current time. It
discards missed past bursts and preserves future events. Paused sampling advances the discard
boundary without emitting events. Detections that arrive later but predate this boundary are
also counted as discarded. No event is retimed to the current frame to hide lateness.

`AudioEventDelivery` exposes indexed immutable deliveries, the generation/revision, current
detection-complete watermark, and per-call counts for late and catch-up discards. A delivered
entry preserves the shared event and reports its per-view lateness. Cursor totals accumulate
these counts across resets for diagnostics.

## Bounds and compatibility

The default event history retains at most 1024 records and two seconds of media history. Each
record has a fixed 64-byte logical payload, so retained event payload is at most 64 KiB. Object,
array and atomic-slot overhead is additional and is bounded by the same record count; the
payload budget is not a claim about exact JVM or native heap size. Eviction counts and retained
payload bytes are observable. At 100 analyses/s and at most five kinds per analysis, the count
bound covers the stated two-second history for that publisher. The three structural kinds do not
use this history; they have their own source, described next.

## Event sources

Each event comes from one `AudioEventSource`. A source has its own completion watermark, its own
sequence numbers and its own lateness budget. One slow source therefore never holds back another.

| Source | Publisher | Lateness budget | Retention |
| --- | --- | ---: | --- |
| `LiveTransient` | The causal transient detectors, once per analysis | 30 ms | 1024 records, 2 s |
| `LiveStructure` | A causal structural detector, which confirms a boundary after it | 3 s | 64 records, 12 s |
| `SongMap` | The structural events of an installed song map | On time only | The map itself |

`AudioDetections.source` names the source of a batch. `SpectrumFrame.detections` carries the
transient batch of an analysis. `SpectrumFrame.structure` carries its structural batch, with the
structural watermark: the time through which the structural detector will publish nothing more.
A structural detector holds that watermark behind any candidate it has not yet decided. Its
confirmations keep their original boundary time; availability records when the confirmation was
made. No confirmation is retimed to its confirmation time, and the transient stream never waits
for a structural decision. The 64-record structural history holds at most 4 KiB of logical
payload.

`AudioEvent.source` completes an event's identity: source, generation, analysis revision and
sequence. Sequence numbers are comparable only within one source. Consumers that deduplicate, such
as the boundary gate and the anticipated camera impulse, compare the whole identity.

A cursor sample merges all sources. It delivers each event in `(previousMediaTime,
currentMediaTime]` once, ordered by original time, then source, then sequence. An event that
arrives after the cursor passed its time is delivered once with its lateness when that lateness is
within its source's budget. Otherwise it is discarded and counted in `lateDiscards`. Initial
attachment, reset, backward movement, a jump over 250 ms and overwritten unread events discard
past events of every source, including structural confirmations that arrive later for times
before that boundary. `AudioEventDelivery.structureCompleteThroughMicros` reports the structural
watermark. `completeThroughMicros` keeps its meaning: the transient watermark.

A song map is not a second live detector. A view reads an installed map from the moment it first
sees it, or from its latest reset. Map events the playhead has already passed are not delivered,
because live evidence already covered that time and a late map event could act on the same
boundary twice. From that moment on, where the map has complete structural coverage, a live
structural event is discarded as a duplicate and counted in `duplicateDiscards`; the map's event
is delivered instead. Map events take the generation and
revision of the history they are delivered into, and their sequence is their index in the map. A
seek back over a map event therefore delivers it again under the new identity, which is correct:
the music crosses that boundary again. Installing, replacing and removing a map for one track is
the song map contract's concern; this contract only merges what is installed.

`SpectrumTimeline.nextEvent` searches every source, so a camera can anticipate a mapped boundary.
Live structural confirmations lie in the past by construction and never appear as future events.
The scalar projection is unchanged: structural kinds set no scalar hit field, and `Drop` alone
sets the legacy `drop` flag.

## Feature history, compatibility and camera timing

Feature history separately retains at most the configured 2..1024 slots, two seconds measured
between feature reference times, and 8 MiB of conservatively charged primitive payload. The
charge includes waveform/spectrum arrays, drivers, detections and fixed metadata; aliases within
one frame are charged once. Bounded object/array/atomic-slot overhead is additional. Oversized
individual frames are rejected without replacing valid history. `historyStats` reports retained
frames, charged bytes, evictions and oversized rejections. Available lookahead stops at a missing
measurement or a gap greater than 100 ms; the newest frame alone does not establish that horizon.

Legacy scalar hit fields may still project the strongest hit for compatibility with drawings
that need a single envelope input. They do not provide multiplicity or event counts. New
event-triggered effects must iterate the delivered records. Continuous frame interpolation never
creates a new event, changes its identity, or changes its original timestamp.
If a reset occurs between the view's feature and event reads, their generation/revision pair
does not match. The join returns a silent frame instead of combining those histories.

The flat and flying cameras remember anticipated low-transient identities. Delivery suppresses
only the corresponding anticipated impulse; a different late hit still contributes, and multiple
delivered low hits each contribute before the spring advances once. A new generation, local
revision, explicit camera reset or cursor catch-up reset clears anticipation. Sixteen pending
identities bound the bookkeeping and cover the player's 100 ms anticipation plus 250 ms catch-up
budget at the live detector's 30 ms combination floor. Legacy author-created scalar frames keep
their single-hit compatibility behavior and cannot provide this identity or multiplicity guarantee.

Camera physics advances the previous state to the current display instant before applying new
event impulses. The flat and flying impulse springs have theoretical peak delays of about 96 ms
and 92 ms respectively, within the 100 ms lookahead limit. The flying camera's preparatory dip
is a separate sine-shaped speed offset that returns to zero at the event; it does not move the
impulse spring's equilibrium and shift its peak.

`CameraPresentationTimingTest` drives known event identities at 60/90/120/240 Hz with three
offsets relative to the display cadence and a maximum 100 ms future. On JVM on 2026-09-19:

| Camera | Cases | Signed median peak error | Absolute p95 / maximum | Signed range |
| --- | ---: | ---: | ---: | ---: |
| Flat | 12 | -1.278 ms | 11.000 ms | -11.000 to +3.777 ms |
| Flying | 12 | +4.222 ms | 13.000 ms | +0.111 to +13.000 ms |

This is an ideal display-clock simulation of the camera response, not an end-to-end timing
measurement. It excludes onset estimation, worker scheduling, audio output, rasterisation and
physical display delay. The named-route click qualification remains separate. Regressions in
applying hits before elapsed-time integration and coupling the dip to the spring target were
observed as early impacts in this matrix; the timing tolerance was not widened to accept them.

The live onset trigger uses the [documented maximum-filter log-frequency method](audioviz-onset-method.md).
Confidence is relative excess above the causal mean-plus-offset threshold, capped at one.
Surprise is positive flux change against that mean, divided by the larger of mean and offset,
capped at four and divided by four. Neither is a calibrated probability. The energy-rise
heuristic has no calibrated confidence and reports zero confidence/surprise. Synthetic onset
fixtures qualify their named signals; the transport and mathematical tests do not qualify
recognition on held-out music, beat grids or formal section boundaries.
