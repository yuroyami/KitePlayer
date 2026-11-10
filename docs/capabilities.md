# Capabilities

Built from the code by `scripts/capabilities.py`; do not edit by hand. CI fails when it
differs from the module files and the renderers (#926). The README's platform section says
what each platform means for an app, and links here for the full picture.

## Modules and platforms

A tick is every target of the platform; otherwise the cell names the targets the module builds for.

| Module | Android | Desktop JVM | iOS | macOS | tvOS | watchOS | Linux | Windows | Android Native | Web (wasmJs) | JS |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `kiteplayer` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ |  | ✓ | ✓ |
| `kiteplayer-adaptive` | ✓ | ✓ | ✓ | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-audioviz` | ✓ | ✓ | ✓ |  |  |  |  |  |  |  |  |
| `kiteplayer-compose` | ✓ | ✓ | ✓ |  |  |  |  |  |  | ✓ |  |
| `kiteplayer-compose-interop` | ✓ | ✓ | ✓ |  |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-compose-ui` | ✓ | ✓ | ✓ |  |  |  |  |  |  | ✓ |  |
| `kiteplayer-compose-video` | ✓ | ✓ | ✓ |  |  |  |  |  |  | ✓ |  |
| `kiteplayer-core` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-ffmpeg` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ |  | ✓ |  |
| `kiteplayer-io` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-libass` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ |  | ✓ | ✓ |
| `kiteplayer-network` | ✓ | ✓ | ✓ | ✓ |  |  |  |  |  | ✓ | ✓ |
| `kiteplayer-output` | ✓ | ✓ | ✓ | ✓ |  |  | ✓ | ✓ |  | ✓ |  |
| `kiteplayer-rt` |  |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  |  |
| `kiteplayer-subtitles` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `kiteplayer-view` | ✓ | ✓ | ✓ | ✓ |  |  |  |  |  | ✓ |  |
| `kiteplayer-view-bindings` | ✓ | ✓ | ✓ |  |  |  |  |  |  |  |  |

## Video renderers

What each renderer says it does, which is what the engine reads to warn an app that a
setting has no effect. `VideoRenderer` applies picture controls unless a renderer says
otherwise, and shows no HDR and no 360 degree view unless it says it does.

| Renderer | Module | Source folder | Picture controls | HDR on an HDR screen | 360 degree video |
| --- | --- | --- | --- | --- | --- |
| `KiteVideoRenderer` | `kiteplayer-compose-video` | `threaded` | yes | no | yes |
| `WebKiteVideoRenderer` | `kiteplayer-compose-video` | `wasmJs` | yes | no | yes |
| `AndroidGpuImageVideoRenderer` | `kiteplayer-output` | `android` | yes | no | no |
| `AndroidSurfaceVideoRenderer` | `kiteplayer-output` | `android` | only without a MediaCodec target | when the screen and the stream allow | no |
| `MetalVideoRenderer` | `kiteplayer-output` | `apple` | yes | when the screen and the stream allow | no |
| `SampleBufferVideoRenderer` | `kiteplayer-output` | `apple` | no | no | no |
| `UIKitVideoRenderer` | `kiteplayer-output` | `ios` | yes | no | no |
| `AwtCanvasVideoRenderer` | `kiteplayer-output` | `jvm` | no | no | yes |
| `AppKitVideoRenderer` | `kiteplayer-output` | `macosArm64` | yes | no | no |
| `WebCanvasVideoRenderer` | `kiteplayer-output` | `wasmJs` | no | no | no |
