# Network transport check

This project checks that adding `kiteplayer-network` as a dependency is enough to turn on network
playback, with no code changes, on every target.

It is a standalone consumer project. It shares no project or composite build with KitePlayer, and
its Kotlin sources only use the core API. It resolves the KitePlayer artifacts that the script
stages from this checkout. The script builds the same sources twice, in separate output
directories: once without `kiteplayer-network`, and once with it. The `withNetwork` switch only
changes the dependency list.

## What a pass means

The project uses a fake media backend. It reads the response bytes through the `MediaIo` that the
player gives it, and then refuses to decode on purpose. No real decoder or audio device is
involved. A run passes when:

- without the dependency, the backend receives no network reader
- with the dependency, the backend receives the bytes, and the failed open closes the reader

The script checks completion markers as well as the exit status. The refused decode is part of
the check, not a test failure.

## Requirements

- JDK 21
- the usual Kotlin/Native requirements of this repository, for the Apple targets
- a compatible Node, for Wasm
- an HTTP or HTTPS address that returns some bytes
- for Android: the Android SDK from `sdk.dir` in the repository's `local.properties`, and a running
  device that you name
- for iOS: a simulator UDID that you name

The script does not start a server or an Android emulator. It only boots the iOS simulator that you
name.

## Stage the artifacts

Stage only the core, network and native audio ring publications:

```bash
scripts/verify-network-distribution.sh --stage
```

`scripts/stage-verification-maven.sh` publishes these modules to Maven Local in one `./kotlin publish mavenLocal` call.
Then it copies only this version of these modules from Maven Local into `build/verification-maven`. The consumer
resolves the KitePlayer group only from that directory. A released artifact with the same version
therefore cannot replace the bytes under test.

The consumer itself is a Gradle build. It uses the Gradle wrapper in `verification/`, so it checks
what a Gradle user of KitePlayer gets.

## Run a target

Start a server on the host first, then run one target:

```bash
scripts/verify-network-distribution.sh --run --target jvm --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target macos --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target ios --simulator UDID --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target wasm --node /path/to/node --url http://localhost:8765/media
scripts/verify-network-distribution.sh --run --target android --android-serial emulator-5554 --url http://10.0.2.2:8765/media
```

- `10.0.2.2` is how the Android emulator reaches the host. A physical device needs an address that
  it can reach.
- To test TLS, use an HTTPS address with a valid, trusted certificate.
- `--target all` needs an iOS simulator. Android joins that run only when you pass
  `--android-serial`.
- `--reuse-staged-cache` skips refreshing unrelated external dependencies when you retry against
  the same staged artifacts. Do not use it after you stage again.

## How each target is built

The native targets use release executables, and Wasm uses the optimised production build. Android
uses a release build that is not debuggable, with R8 on, and the script requires its mapping file.

The Android app adds no keep rules of its own, so any rules R8 needs must come from the published
dependency. An Android archive that the Kotlin Toolchain builds carries no manifest entries. So the
network build of the app declares the INTERNET and ACCESS_NETWORK_STATE permissions and the network
provider itself, in `android-app/src/network/AndroidManifest.xml`. The
[module contract](../../docs/module-contract.md#automatic-network-transport) tells every app to do
the same.

Outputs and logs go to `verification/network-consumer/build/without-network` and
`verification/network-consumer/build/with-network`. This folder only describes the check. Its
presence does not mean that a run has passed.
