# 0.0.23 verification record

This records the module and automatic-network changes only. It does not claim libass playback
integration, a published Maven release, or physical-device/browser qualification.

## Behavior and distribution

- The automatic-reader test failed before implementation and passed afterward. Disabling the
  automatic selection again made the test fail; restoring it restored the passing result.
- Focused core resolution/registry tests and the network suite passed. They cover explicit-reader
  precedence, resolver opt-out, headers, unsupported URIs, concurrent registration and ownership.
- The UI-only dependency test failed when it could load the default factory and passed after the
  adapters moved below the runtime. It also excludes the network transport from that classpath.
- Independent consumers resolved staged Maven-format artifacts through an exclusive repository.
  They reference only core symbols and test both the absence and presence of the network module.
  JVM, macOS Native release, iOS simulator release and Wasm production consumed bytes successfully
  from a local HTTP fixture. Android release/R8 and Wasm production also read public HTTPS bytes.
- The Android consumer declares no internet permission itself. Manifest-merger evidence identifies
  `kiteplayer-network-android:0.0.23` as the permission source. Its R8 release read 32 HTTPS bytes.
- The consumer requires positive bytes after the deliberately failed decoder open. An empty HTTP
  response exposed a false-positive in the original probe. The rebuilt Native release consumer
  now rejects that response, and still reads 32 bytes from the nonempty fixture.
- Staging writes only `build/verification-maven`. It does not publish to Maven Local or Central.

## Apple public-network limit

On this Mac, new Native test executables stall before sending a TCP SYN for public URLs. The same
problem occurs with a direct Ktor Darwin client and plain HTTP; local HTTP succeeds, and Apple's
`nscurl` reaches the same remote endpoint. LuLu is active, making executable filtering a plausible
cause, but no specific deny rule was established. The iOS simulator reports an offline error for
public HTTPS. No firewall or trust settings were changed.

These results prove optimized Apple provider discovery and local transport, not Apple public HTTPS
on this host. CI includes a separate macOS release-consumer HTTPS check on a hosted runner.

## Linux JVM packaging limit

Linux arm64 Native execution passed. The Linux JVM suite ran 82 tests and failed 15 because the
unchanged `kiteffmpeg-jvm:0.2.0` JAR contains only `kiteffmpeg-native/macos-arm64` resources.
Its loader cannot find Linux arm64 JNI. This is a dependency packaging failure, so the complete
Tier 2 gate is not green. The dependency stays at 0.2.0; no unrequested KiteFFmpeg release or
local-artifact substitution hides this result.

## Regression gate

The maintained entry point is `./scripts/check-gate.sh tier2`. The module/network-only tree passed
ABI and publication ratchets, build logic tests, core/subtitle JVM suites, macOS Native suites,
iOS simulator view tests, C plain/interpose/ASan/TSan suites, JVM playback/UI/network suites,
Wasm Node suites, Linux Native execution, Windows test links, Android/iOS compile checks, and the
four sample clips plus missing-input rejection. The updated desktop and web samples also compile.

The first Linux attempt found Docker stopped; starting it and selecting its socket allowed Native
execution. The Linux JVM packaging failure above remains. Later gate steps were resumed with
`--from=windows`, retaining earlier evidence; this is not a claim that the complete gate passed.
Windows cross-links do not establish Windows execution. Node tests do not establish browser
execution. Simulator results do not establish physical-device execution.
