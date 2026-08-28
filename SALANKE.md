# SALANKE.md

Orchestration instructions for an AI running the SALANKE sweep: mine the issue trackers of the
three most battle-tested media players (mpv, VLC, AndroidX Media3/ExoPlayer), fact-check every
relevant issue against KitePlayer's actual code, and distill the results into one ledger of
potential KitePlayer defects and missing features.

Why this works: those trackers are twenty years of distilled media edge cases. The bugs KitePlayer
fixed in August 2026 (seek preemption, buffering status honesty, epoch alignment after flush) all
have ancestors in those trackers. The implementation language does not matter; the traps are
properties of containers, codecs, clocks, and devices, and KitePlayer meets the same ones.

## 0. Context you need first

KitePlayer is a Kotlin Multiplatform media engine in this repo. Before anything else, read
`HANDOFF.md` section 3 (the five-layer architecture map) and skim
`kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`
top-of-file docs. You cannot fact-check an issue against code you have not mapped.

KitePlayer's scope, so you can reject out-of-scope issues fast: local file and HTTP playback,
FFmpeg-based demux and decode (KiteCodec), hardware decode via VideoToolbox and MediaCodec,
audio through a C ring to platform sinks, subtitles (SRT, VTT, embedded text ASS without full
styling), seeking, track selection, speed, chapters, A-B loop, playlists. The pinned FFmpeg backend
can demux basic HLS, and the Kotlin adaptive tier supports basic static DASH with one period and one
representation. NOT in scope: DRM, casting, ad insertion, dynamic or live DASH, SmoothStreaming,
player-owned HLS timelines and adaptation, advanced adaptive streaming behavior, editing, and
transcoding UIs.

## 1. The three agents

Run one agent per tracker. Each agent owns one output file, works in checkpointed batches, and
never touches another agent's file. The orchestrator only reads their outputs at the end.

| Agent | Tracker | Output |
|---|---|---|
| MPV | github.com/mpv-player/mpv issues | `SALANKE_MPV.md` |
| VLC | code.videolan.org/videolan/vlc issues (GitLab) | `SALANKE_VLC.md` |
| MEDIA3 | github.com/androidx/media issues, PLUS the archived github.com/google/ExoPlayer tracker (deep history lives there) | `SALANKE_MEDIA3.md` |

Access: `gh` CLI for the two GitHub trackers (`gh issue list --repo ... --state all --limit ...`
with pagination, and `gh api` for label filtering and reactions). VLC's GitLab has a public REST
API (`https://code.videolan.org/api/v4/projects/videolan%2Fvlc/issues`). If an API is
unreachable, say so in the output file header and do what web search allows; never fabricate
issue content.

## 2. Triage protocol (this is what keeps the sweep finite)

Issue by issue does NOT mean every issue ever filed. It means every issue that SURVIVES TRIAGE,
processed one at a time with real fact-checking. Triage rules:

1. **Prefer closed-and-fixed issues.** A closed issue with a fix is a documented trap plus its
   solution. Open issues rank second. Wontfix/invalid rank last but are still worth a skim for
   design decisions ("we refuse X because Y" is knowledge).
2. **Filter by component.** Keep: playback core, demuxing, seeking, A/V sync, audio output,
   subtitle rendering and timing, hardware decoding, track selection, timestamps and
   discontinuities, gapless, speed/pitch, chapters, color/HDR, rotation. Drop: GUI, packaging,
   build systems, scripting/Lua, platform app shells, DRM, casting, ads, CI.
3. **Signal ranking within a component.** Many reactions, long discussion with a root-cause
   comment, or a fix commit linked: process first. Drive-by reports with no diagnosis: skip
   unless the title names a mechanism.
4. **Time budget per tracker: aim for 300 to 800 processed issues**, chosen by the ranking
   above, not the first N chronologically. Record in the output header how many were listed,
   how many triaged in, how many processed.

## 3. The fact-check (the part that makes this valuable)

For each surviving issue, the agent must do all three steps:

1. **Extract the mechanism**, not the symptom. "Seek on MPEG-TS lands seconds off because the
   container has no index and byte-position seeking estimates" is a mechanism. "Seeking is
   broken" is not; if no mechanism can be extracted from the thread, drop the issue.
2. **Locate the corresponding KitePlayer code** and READ it. Name the file and function in the
   row. If KitePlayer has no corresponding subsystem, the row is a missing-feature candidate,
   not a bug candidate.
3. **Give a verdict from this exact taxonomy:**
   - `IMMUNE`: the architecture prevents it, and the row says which line/design proves it.
   - `SUSPECT`: the same mechanism can plausibly fire in KitePlayer; the row says the entry
     point and what would trigger it. Do not claim confirmed without a failing test.
   - `CONFIRMED`: you reproduced it in the virtual-time harness (see `HANDOFF.md` section 7).
     Only claim this with a committed or pasted failing test.
   - `MISSING-FEATURE`: the other player has capability KitePlayer lacks; note user value.
   - `N/A`: out of scope after reading; one clause saying why.

## 4. Row format (identical in all three files)

One row per processed issue, in this exact markdown shape so the distillation is mechanical:

```
### [TRACKER-NUMBER] Title as written upstream
- Link: <url>  State: closed-fixed | open | wontfix
- Mechanism: one to three sentences, the actual cause, not the symptom.
- KitePlayer code checked: path/File.kt, functionName (or "no corresponding subsystem")
- Verdict: IMMUNE | SUSPECT | CONFIRMED | MISSING-FEATURE | N/A
- Why: the evidence. For IMMUNE name the guard. For SUSPECT name the trigger.
- Severity if real: P0 crash/dataloss | P1 broken feature | P2 quality/perf | P3 polish
```

File header of each SALANKE_*.md: date, tracker, counts (listed, triaged, processed), and a
checkpoint line ("processed through issue #NNNN / page N") updated as you go, so an interrupted
run resumes instead of restarting.

## 5. The distillation (orchestrator, after all three agents finish)

Produce `SALANKE_FINAL.md`:

1. **Dedupe by mechanism, not by title.** mpv, VLC, and ExoPlayer all have "audio drifts after
   speed change" issues; that is ONE ledger row citing all three sources. The cross-tracker
   repetition is itself signal: a trap all three met is a trap KitePlayer WILL meet.
2. **Ledger sections, in this order:** CONFIRMED rows first, then SUSPECT sorted by severity
   then by how many trackers exhibited the mechanism, then MISSING-FEATURE sorted by user value,
   then a short IMMUNE honor roll (worth keeping: it documents which design decisions are
   load-bearing, so nobody "simplifies" them away).
3. **Every ledger row keeps its receipts:** source issue links and the KitePlayer file/function
   that was checked. A row without receipts gets deleted, not kept on faith.
4. End with a proposed work order: the top ten rows the owner should schedule, one line each on
   why that rank.

## 6. House rules

- No em dashes or en dashes anywhere in any SALANKE file.
- Plain language; a junior dev must understand every row on first read.
- Never assert KitePlayer behavior from memory or from this document; assert it from code read
  during the run, and name where.
- The virtual-time harness is the only accepted proof for CONFIRMED. Red test first.
- These files are research output, not the engine's register. Do not edit KPKMP-PAST.md or
  KPKMP-FUTURE.md; the owner promotes ledger rows to the register personally.
