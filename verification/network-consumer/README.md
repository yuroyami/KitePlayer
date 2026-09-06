# Automatic network transport distribution probe

This standalone consumer resolves the checkout's staged Maven artifacts. It shares no project or
composite-build dependencies with KitePlayer, and its Kotlin sources refer only to core APIs.
`withNetwork` changes the dependency declaration only. The runner builds the same source twice:
without the optional network artifact, then with it, in separate output directories.

Prerequisites: JDK 21, the repository's normal Kotlin/Native prerequisites for Apple targets, a
compatible Node for Wasm, and an HTTP or HTTPS endpoint that returns nonempty bytes. Android also
needs the configured Android SDK and an explicitly selected running device. The runner reuses
`sdk.dir` from the repository's `local.properties`. iOS needs an explicitly selected simulator UDID.
The script does not start a server or Android emulator. It boots only the explicitly selected
iOS simulator.

Stage only the core, network, and required native ring publications:

```bash
scripts/verify-network-distribution.sh --stage
```

This writes to `build/verification-maven`. It does not publish to Maven Local or Maven Central.
The consumer resolves the Kite group exclusively from that directory, preventing a released
artifact with the same version from silently replacing the bytes under test.

Run the selected target against a server on the host:

```bash
scripts/verify-network-distribution.sh --run --target jvm --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target macos --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target ios --simulator UDID --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target wasm --node /path/to/node --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target android --android-serial emulator-5554 --url http://10.0.2.2:8765/media
```

`10.0.2.2` is the Android emulator's host alias. A physical Android device needs a reachable server
address. To exercise TLS, use an HTTPS URL with a valid trusted certificate and a nonempty response.
`--target all` requires an iOS simulator; Android joins that run only when `--android-serial` is set.

The fake media backend reads response bytes through the supplied IO, then deliberately refuses to
decode. Success means the backend receives no automatic IO without the dependency, receives bytes
with it, and the failed open closes that reader. No media decoder or audio device is involved.
The runner checks completion markers as well as exit status; the deliberate decoder refusal is
part of the proof, not a test failure.

Native probes use release executables and Wasm uses the optimized production executable. Android
uses a non-debuggable release with R8 enabled and requires its mapping file. Its local installation
signature does not disable optimization. The app adds no provider references or custom keep rules;
any discovery rules must arrive from the published dependency itself.

Outputs and logs stay under `verification/network-consumer/build/{without-network,with-network}`.
These fixtures describe checks to run; their presence alone does not claim a passing distribution.

The Android app declares no INTERNET permission itself; its network artifact must supply it.
For fixture-only retries against unchanged staged bytes, `--reuse-staged-cache` avoids refreshing
unrelated external dependencies. Do not use that option after restaging artifacts.
