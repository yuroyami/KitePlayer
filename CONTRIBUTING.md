# Contributing to KitePlayer

KitePlayer is a Kotlin Multiplatform media player: one Kotlin engine, with containers, codecs and
platform output arriving through a service interface. The FFmpeg backend in `kiteplayer-ffmpeg` is
one implementation of that interface, built on the sibling KiteFFmpeg library.

Open work lives in GitHub Issues. There is no private planning file, and a change starts with an
issue.

## Ground rules

These are not style preferences. Each one exists because ignoring it cost someone a day.

- **Red first, always.** Write the failing test, run it, watch it fail at the line you predicted,
  fix it, watch it pass, then break the fix and watch it go red again. A test that was never seen
  red proves nothing.
- **A claim carries the strength of its evidence and no more.** Compilation is not support. A
  source-set declaration is not support. A laptop green is not a device green. A simulator green is
  not a device green. A cached up-to-date Gradle run proves only that the cache is not red; gates
  rerun for real. When code, artifacts, docs and measurements disagree, the weakest result is the
  truth.
- **No em dashes in any file**: code, comments, Markdown, or commit messages. The gate scans for
  them.
- **No new dependency without asking first**: not a library, not a plugin, not a toolchain or
  Gradle bump, not a GitHub Action. C or shader source we author ourselves is fine.
- **A design act is its own commit.** Deciding a public API shape and executing it never happen in
  the same commit.
- **Explicit API mode is on in every module.** Any public API change regenerates the ABI dumps in
  the same commit with `./gradlew updateKotlinAbi`.
- **When the tree contradicts an issue or a document, stop and say so.** Do not improvise the
  document back into truth. Prose drifting from the tree is this project's measured failure mode.
- **Size estimates rot the same way claims do.** An estimate made behind a blocker is a guess about
  what the blocker hides. Re-size when the blocker falls.
- **After any task**: re-read every changed file once, run the gate tier the changed paths select,
  then commit.

### House Kotlin style, when a task leaves a choice

Sealed transactional outcomes over thrown control flow. Structured finalizer scopes over
hand-paired cleanup. Ownership-aware lease APIs over raw handles. Inline plane iteration over
per-pixel calls. Checked-size helpers over bare arithmetic. Resource ledgers over close-and-hope.

Style is guidance. It is never a task of its own.

### What we may and may not copy

- KitePlayer ships under a permissive licence. The GPL and LGPL players checked out under
  `vendor/` are **study only**. Designs, algorithms and thresholds are facts and may be restated.
  Source text is expression, and a Kotlin transliteration inherits its licence. Never
  transliterate, and never name a study-only source in a comment as the origin of an
  implementation.
- Android's own media libraries are permissively licensed and may be ported directly, with credit
  in `NOTICE`.
- The `ffmpeg` and `ffprobe` binaries as test oracles are always fine. Differential testing
  compares outputs, never source.

## Getting a build

- JDK 21.
- A clean clone needs `local.properties` with an Android SDK path, or the `ANDROID_HOME`
  environment variable, before the Android targets have a task graph at all.
- Test fixtures are generated, not committed: run `./scripts/testmedia.sh` before any real-media
  suite. It records the encoder version in the manifest and warns when it differs from the recorded
  series. `TESTMEDIA_STRICT_FFMPEG=1` turns that warning into a refusal for version-sensitive work.

## The gate before every commit

Three tiers, selected by which paths changed, never by how confident you feel. Say which tier you
ran and which rule selected it.

### Tier 1, every change without exception, seconds

```bash
./scripts/check-gate.sh tier1
```

The aggregate runs the coupling and ABI checks and the core and subtitle JVM tests, builds and
executes plain C tests, then runs the render audit, source discipline and tracked-file em dash scan.
Stage new files before the final gate so the scan includes them. It handles the scan's expected
no-match exit code without suppressing errors.

Tier 1 cannot catch data races, wrong-architecture archives, real-media regressions, or anything
about a target it did not build.

### Tier 2, roughly 10 to 15 minutes

Selected by any of: files under `native/` or `buildSrc/`, any `.def` file, any `build.gradle.kts`,
any version catalog, any Kotlin under a platform source set, or the completion of any major piece
of work.

Contents: Tier 1, plus `./scripts/testmedia.sh` first, the build logic tests, the macOS host
suites, the iOS simulator view suite, the sanitizer and interpose C runs, the desktop JVM suites,
the web Node suite, `./scripts/linux-tests.sh`, `./scripts/linux-jvm-tests.sh`, the Windows link
check, cross-compile spot checks, and the sample run over the house clips plus a nonexistent path.

```bash
./scripts/check-gate.sh tier2
```

Run the aggregate script, not a hand-written list of modules. It is the maintained command list
for a local macOS arm64 host and stops on the first failure. `--dry-run` prints the steps without
running them. After resolving an environment failure, `--from=STEP` resumes at that step; retain
earlier successful logs and rerun affected steps if sources changed. A resumed run reports its
partial coverage. Gradle reuses unchanged outputs. Tier 2 also checks publication metadata and
dependency hygiene, and runs the libass C packing suite in plain and sanitizer configurations.
Docker must be running, an iOS simulator runtime must be installed, and the sibling
KiteFFmpeg checkout must contain its cross-built native libraries for the Linux execution check.
The sample smoke uses the four original house clips; the format-matrix suites cover the wider set.
The Node gate does not claim browser execution, and Windows links do not claim Windows execution.
CI runs those environments separately. Distribution-only integration checks remain additional
checks for changes to published dependency metadata or automatic provider discovery.

### Tier 3

Everything in Tier 2, plus a supervised device run. Selected by changes to the real-time render
path or the ring handoff.

### Reading a gate result honestly

- `./gradlew ... | tail` reports the exit code of `tail`, not of the build. Pipe to a file and read
  the file, or check the log for BUILD FAILED.
- A sample miss on a loaded machine that passes on two quiet reruns is recorded as a load
  observation, not rerun until green.

## What "plays all formats" means here

The conformance matrix is the set of clips `scripts/testmedia.sh` generates, plus the matrix tests
that drive them. It is one measured claim against that matrix, on every platform that advertises
playback, and it means nothing more than that.

Size tiers are measured per target at release time. No size is promised before it is measured.

## Pull requests

- One change per pull request, with tests where the change is testable.
- The gate tier your changed paths select must pass, and the pull request says which tier that was.
- New public API needs KDoc, and the ABI dump regenerated in the same commit.
- A commit that fixes an issue says `Fixes #n` in its body, not its subject.
