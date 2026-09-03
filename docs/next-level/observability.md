# Program O: observability

Read `README.md` in this directory first. Facts verified against the tree: `PlaybackStats`
(`PlayerState.kt:143-232`) carries counters, queue depths, latency and drift, and a
`containerBitrate` that its KDoc says is always null. `KiteLog` (`Diagnostics.kt:28-45`) has one
`Sink` taking a tag and a message, no fields, no redaction; redaction exists only for the support
bundle (`internal/Redaction.kt`). Nothing measures decode time or presentation lateness. No trace
export exists.

`core` means `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/`.

---

### O1 IO throughput in the stats. Size S, Tier 1 (the bitrate half waits for K2)

**Why.** A network player that cannot say how fast bytes arrive cannot explain a rebuffer.
`CachingMediaIo` sees every upstream read and counts nothing.

**Depends on:** nothing. The `containerBitrate` half depends on K2 being published.

**Files.** Modify `core/internal/CachingMediaIo.kt` (counters), `core/PlayerState.kt`
(`PlaybackStats`), `core/internal/PlaybackCore.kt` (the stats tick). Tests `IoStatsTest.kt` on the
harness with a scripted `MediaIo`.

**Contract.**

```kotlin
// PlaybackStats gains
/** Bytes read from the media source since open. Zero for opens that read through no [MediaIo]. */
val ioBytesTotal: Long = 0,
/** Bytes per second over the last stats interval, same caveat. */
val ioBytesPerSecond: Long = 0,
```

`CachingMediaIo` keeps an atomic `bytesRead` incremented by each upstream `read`'s return value;
the stats tick publishes the total and the delta divided by `statsInterval`. After K2,
`containerBitrate` is filled from the backend's `bitrateBps` and its "Always null" KDoc goes.

**Tests.** Harness, `MediaIo.ofBytes(1_000_000 bytes)` item played 2 s at `statsInterval = 1.seconds`:
`ioBytesTotal` equals the bytes the scripted demuxer consumed, `ioBytesPerSecond` is above zero
during the first interval and returns to zero once the demuxer is idle. A path-opened item keeps
both at zero and the KDoc says why.

**Commit.** `stats: bytes read and bytes per second, from the byte cache that already sees them`

---

### O2 Frame timing percentiles. Size S, Tier 1

**Why.** "Is it the decoder or the display" is the first question of every device session, and
the stats cannot answer it: no decode time, no lateness.

**Depends on:** nothing.

**Files.** Create `core/internal/Percentiles.kt` (a fixed ring of 240 `Long`s with `p(0.5)`,
`p(0.95)`). Modify `core/VideoPlayback.kt` (time the decoder call, time the present against
`targetNanos`), `core/PlayerState.kt`. Tests `PercentilesTest.kt`, `FrameTimingStatsTest.kt`.

**Contract.**

```kotlin
// PlaybackStats gains
/** Wall time one decoded video frame took, median and 95th percentile over the last 240 frames. */
val decodeTimeP50: Duration = ZERO,
val decodeTimeP95: Duration = ZERO,
/** How late frames were presented against the schedule's target, 95th percentile. Negative is early. */
val presentLatenessP95: Duration = ZERO,
```

`Percentiles` sorts a copy of its ring at the stats tick only (240 elements, once a second).
Decode time brackets the decoder's receive call on the video worker with the monotonic clock;
lateness is `presentedAt - targetNanos` where `presentedAt` is the clock read just after
`present` returns (V3's exact time replaces it when available).

**Tests.** `Percentiles`: 240 values 1..240 give p50 120 and p95 228; fewer than two samples give
zero. Harness with a scripted decoder that sleeps 4 ms of virtual time per frame: `decodeTimeP50`
is 4 ms.

**Commit.** `stats: decode time and presentation lateness percentiles`

---

### O3 Trace export, Chrome trace format. Size M, Tier 1

**Why.** A timeline of open, seek phases, decodes and presents, viewable in `chrome://tracing`
or Perfetto, turns a device session's "it stuttered around 1:12" into a picture. `KiteLog` is
text; this is structure.

**Depends on:** nothing. O2's timers become spans here.

**Files.** Create `core/KiteTrace.kt`, `core/internal/Tracing.kt`. Modify `core/internal/PlaybackCore.kt`
(open, seek phases, track switch), `core/VideoPlayback.kt` (decode and present spans when
per-frame is on), `core/AudioPlayback.kt` (underrun instants). Tests `KiteTraceTest.kt`,
`ChromeTraceFormatTest.kt`.

**Contract.**

```kotlin
/** Process-wide trace sink, like [KiteLog]. Nothing is recorded until a sink is installed. */
public object KiteTrace {
    public fun interface Sink {
        public fun span(category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>)
        public fun instant(category: String, name: String, atNanos: Long, args: Map<String, String>)
    }
    public fun install(sink: Sink?, perFrame: Boolean = false)
    public val enabled: Boolean
}

/** One Chrome trace event as a JSON object line. Apps write `[` then lines joined by commas then `]`. */
public object ChromeTraceFormat {
    public fun span(pid: Int, tid: Int, category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>): String
    public fun instant(pid: Int, tid: Int, category: String, name: String, atNanos: Long, args: Map<String, String>): String
}
```

Spans: `session/open`, `seek/keyframe`, `seek/refine`, `track/switch`; per frame when `perFrame`:
`video/decode`, `video/present` with `pts` in args; instants: `audio/underrun`, `video/drop`.
Every emit site is guarded by `if (KiteTrace.enabled)` so the hot path pays one volatile read.
Values are strings and never a URI: paths pass through `redactUri` (`internal/Redaction.kt`).

**Tests.** Format: a span becomes `{"ph":"X","pid":1,"tid":2,"cat":"seek","name":"keyframe","ts":1000,"dur":500,"args":{"pts":"3"}}`
(microseconds, per the format). Harness: an open under an installed sink produces one
`session/open` span with `beginNanos < endNanos`; a seek produces `seek/keyframe` then
`seek/refine` under `KeyframeThenRefine`; with no sink, a counting decoder proves the emit sites
were never entered (count calls through a test seam on `Tracing`).

**Commit.** `diagnostics: a trace sink, and the Chrome trace format to write it with`

---

### O4 A structured log sink, with redaction. LANDED 2026-09-03

Two departures from the plan:

- **`installStructured`, not an `install` overload.** The plan wrote a second `install(sink:
  StructuredSink?)`. Two nullable overloads make a bare `install(null)` ambiguous, and existing
  tests used exactly that to go back to silence, so the overload broke them at compile time. A
  separate name costs nothing and breaks nobody.
- **The fields live on `PlaybackWarning`, not at the call site.** An open `fields` property
  defaulting to the class's own simple name means every warning is queryable without touching the
  one place the engine logs, and a new warning is structured the day it is written rather than the
  day someone remembers to add it to a `when`.

### O4, as planned. Size S, Tier 1

**Why.** `KiteLog.Sink.log(tag, message)` flattens the warning type, the stream index and the
URI into prose. Structured backends want fields, and a log line should not carry a token.

**Depends on:** nothing.

**Files.** Modify `core/Diagnostics.kt`, `core/internal/PlaybackCore.kt` (the warning sites pass
fields). Tests `StructuredLogTest.kt`.

**Contract.**

```kotlin
// KiteLog gains
public fun interface StructuredSink {
    public fun event(tag: String, message: String, fields: Map<String, String>)
}
public fun install(sink: StructuredSink?)
/** Strip queries, fragments and directories from URIs in messages and field values. On by default. */
public var redactUris: Boolean
```

Installing either sink kind replaces the other. A plain `Sink` receives fields appended as
`key=value` pairs. Every `PlaybackWarning` logged carries at least `warning` (the type's simple
name) and, where it has one, `stream`.

**Tests.** A structured sink receives `warning=TrackDeselected` and `stream=3` for that warning;
a plain sink receives the same as a suffix; a message containing
`https://cdn.example/v/movie.mkv?token=abc` arrives as `movie.mkv`; with `redactUris = false` it
arrives intact.

**Commit.** `diagnostics: a structured log sink, and URIs are redacted by default`
