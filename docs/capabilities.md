# Capabilities

Built from the code by `scripts/capabilities.py`; do not edit by hand. CI fails when it
differs from the module files and the renderers (#926). The README's platform section says
what each platform means for an app, and links here for the full picture.

## Modules and platforms

A tick is every target of the platform; otherwise the cell names the targets the module builds for.

| Module | Android | Desktop JVM | iOS | macOS | tvOS | watchOS | Linux | Windows | Android Native | Web (wasmJs) | JS |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `kiteplayer` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  | ✓ | ✓ | androidNativeArm32, androidNativeArm64, androidNativeX64 | ✓ | ✓ |
| `kiteplayer-adaptive` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-audioviz` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-compose` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-compose-interop` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-compose-ui` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-compose-video` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-core` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-ffmpeg` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ | androidNativeArm32, androidNativeArm64, androidNativeX64 | ✓ | ✓ |
| `kiteplayer-io` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-libass` | ✓ | ✓ | iosArm64, iosSimulatorArm64 | ✓ |  |  | ✓ | ✓ | androidNativeArm32, androidNativeArm64, androidNativeX64 | ✓ | ✓ |
| `kiteplayer-network` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-output` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-rt` |  |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  |  |
| `kiteplayer-subtitles` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-view` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-view-bindings` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ | androidNativeArm32, androidNativeArm64, androidNativeX64 | ✓ | ✓ |

## Video renderers

What each renderer says it does, which is what the engine reads to warn an app that a
setting has no effect. `VideoRenderer` applies picture controls unless a renderer says
otherwise, and shows no HDR and no 360 degree view unless it says it does.

| Renderer | Module | Source folder | Picture controls | HDR on an HDR screen | 360 degree video |
| --- | --- | --- | --- | --- | --- |
| `NoPictureRenderer` | `kiteplayer-compose-video` | `js` | no | no | no |
| `KiteVideoRenderer` | `kiteplayer-compose-video` | `threaded` | yes | no | yes |
| `WebKiteVideoRenderer` | `kiteplayer-compose-video` | `wasmJs` | yes | no | yes |
| `AndroidGpuImageVideoRenderer` | `kiteplayer-output` | `android` | yes | no | no |
| `AndroidSurfaceVideoRenderer` | `kiteplayer-output` | `android` | only without a MediaCodec target | when the screen and the stream allow | no |
| `UIKitVideoRenderer` | `kiteplayer-output` | `ios` | yes | no | no |
| `AwtCanvasVideoRenderer` | `kiteplayer-output` | `jvm` | no | no | yes |
| `MetalVideoRenderer` | `kiteplayer-output` | `macosAndIos` | yes | when the screen and the stream allow | yes |
| `SampleBufferVideoRenderer` | `kiteplayer-output` | `macosAndIos` | no | no | no |
| `AppKitVideoRenderer` | `kiteplayer-output` | `macosArm64` | yes | no | no |
| `WebCanvasVideoRenderer` | `kiteplayer-output` | `wasmJs` | no | no | no |
