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
  suite. It refuses an `ffmpeg` outside its pinned version, on purpose. When the pin needs to move,
  move the pin line and regenerate in the same commit.

## The gate before every commit

Three tiers, selected by which paths changed, never by how confident you feel. Say which tier you
ran and which rule selected it.

### Tier 1, every change without exception, seconds

```bash
./gradlew checkKitertCoupling
./gradlew checkKotlinAbi
./gradlew :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest
kiteplayer-rt/native/scripts/run-c-tests.sh plain
kiteplayer-rt/native/scripts/render-audit.sh
kiteplayer-rt/native/scripts/source-discipline.sh
git ls-files -z | xargs -0 grep -n $'\u2014'   # em dash scan: printing nothing is the pass
```

The em dash scan's `grep` exits 1 when it finds nothing, which is the passing outcome. Do not wrap
it in a shell that reads that exit as a failure.

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

Run the aggregate task, not a hand-written list of modules.

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
