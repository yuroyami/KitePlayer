# Verification for 0.0.24

Local verification ran on macOS arm64 on 2026-09-12.

## Regression checks

- Issue #43: the shared input contract checks partial reads, invalid slices, seeks, EOF,
  close and independent reopen state. A subtitled Matroska file opened through `ofBytes`
  reports the same streams as disk and decodes its video and audio. Removing cursor advancement
  makes two contract tests fail.
- Issue #35: the first seek supplies a two-second lower bound. A refused window retries once;
  cancellation and interrupted sources do not retry. Real media with five-second keyframe gaps
  still decodes after seeking. Removing the bound and retry makes three policy tests fail.
- Android output: 143 host tests passed, including Surface replacement during a blocked draw,
  bounded destruction waits and picture adjustments surviving Surface changes.
- Audio visualisation: 124 JVM tests and 97 iOS simulator tests passed. The clock probe measured
  a median analysis offset of -4 ms. A subsequent macOS CI run measured -6 ms but rejected a
  clock-step assertion: device position updates can re-anchor the clock behind extrapolated time,
  so subtracting wall-clock delay still does not make that assertion valid. The real-device probe
  retains its two-frame analysis-alignment check. The seven controlled `SmoothClockTest` cases
  check interpolation, updates, pause/resume, speed, the extrapolation limit and backward seeks.
  Disabling interpolation makes six of those seven tests fail.

## Platform gate

`./scripts/check-gate.sh tier2` completed fixture generation, coupling and ABI validation,
core/subtitle JVM tests, build-logic tests, publication metadata and dependency checks,
macOS tests, iOS simulator tests, C sanitizers, JVM suites and Node/Wasm suites.

The macOS suites include 652 core tests, 130 FFmpeg tests and 129 output tests. The iOS view
suite passed 28 tests. Linux arm64 native execution passed 651 core tests, 44 subtitle tests
and 117 FFmpeg tests.

The Linux arm64 JVM run has the existing distribution limitation: 19 of 95 tests fail because
KiteFFmpeg 0.2.0 does not bundle `kitecodec_jni` for Linux arm64. This is not a clean JVM Linux
arm64 result. Native Linux arm64 execution is separate and passed.

Following the documented resume procedure, `./scripts/check-gate.sh tier2 --from=windows`
passed the Windows link checks, Android/iOS compilation and the sample playback checks.
The earlier successful results were retained. Local Node tests do not establish browser
execution, and local Windows links do not establish Windows execution.
