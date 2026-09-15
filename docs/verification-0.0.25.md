# Verification for 0.0.25

Local verification ran on macOS arm64 on 2026-09-15.

## Regression checks

- Issue #135: two Android crash logs, one from the sample app and one from a consuming app, were
  retraced against the R8 mapping. Both end in `BaseCanvas.throwIfHasHwFeaturesInSwMode`, reached
  from `replayPrevious` and from `ShaderPreset.drawEcho` inside an echo buffer. The fix was
  checked in the published Android class: `canDrawRuntimeShaders` reads
  `Canvas.isHardwareAccelerated` before any shader brush is drawn. No automated test covers it,
  because the JVM and iOS tests draw through Skia, which runs shaders on a bitmap without error.
- Audio visualisation: 124 JVM tests and 97 iOS simulator tests passed on the changed sources.
- A consuming Android app built against a local publication of these sources passed its own
  482 shared tests and its release build.

## Platform gate

`./scripts/check-gate.sh tier2` completed fixture generation, coupling and ABI validation,
build-logic tests, publication metadata and dependency checks, macOS tests, iOS simulator tests,
C sanitizers, JVM suites and Node/Wasm suites.

Only the audio visualiser and the samples changed since the previous gate run, so Gradle reused
the test results of the other modules against unchanged inputs. Those results are: 652 core
tests, 130 FFmpeg tests and 129 output tests on macOS, and 28 iOS view tests.

Linux arm64 native execution ran fresh and passed 651 core tests, 44 subtitle tests and 117
FFmpeg tests.

The Linux arm64 JVM run has the existing distribution limitation: 19 of 95 tests fail because
KiteFFmpeg 0.2.0 does not bundle `kitecodec_jni` for Linux arm64. 18 fail while loading that
library and the last one is the matrix summary. This is not a clean JVM Linux arm64 result.

Following the documented resume procedure, `./scripts/check-gate.sh tier2 --from=windows`
passed the Windows link checks, the cross-compile checks and the sample playback checks. The
sample played 300 of 300 frames with no drops and no audio underruns, and refused a missing
file. Local Node tests do not establish browser execution, and local Windows links do not
establish Windows execution.
