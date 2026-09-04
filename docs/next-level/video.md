# Program V: video and rendering

Read `README.md` in this directory first. Facts verified against the tree: every renderer answers
`vsyncIntervalNanos() = null` and `RendererEvent.VsyncChanged` is never emitted; the engine ignores
it at `PlaybackCore.kt:1603`. Apple presents drawables with no time attached
(`MetalFrameComposer.kt:195`), Android releases codec buffers at the scheduled time
(`MediaCodecVideoDecoder.kt:743`). `stepFrame` takes no argument and only moves forward
(`PlaybackCore.kt:3931-3947`). `captureFrame` copies decoded planes before the renderer sees them
(`VideoPlayback.kt:319-323`) and never includes the overlay. `VideoAdjustments` collapses to one
affine colour matrix; gamma is documented absent because it is not affine. No interlace metadata
exists in either repository. No `FLAG_SECURE`, no picture in picture beyond a boolean.

`core` means `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/`; `output` means
`kiteplayer-output/src/`.

---

### V1 Backward frame step. Size M, Tier 1 plus one real-media test

**Why.** `stepFrame()` releases the next already-decoded frame (`KitePlayer.kt:446-464`). Backward
needs a frame the decoder has already thrown away: the last frame BEFORE the current one, which on
a long-GOP file means decoding forward from the previous keyframe and keeping the right one.

**Depends on:** nothing.

**Files.** Modify `core/KitePlayer.kt` (signature), `core/internal/SeekRequest.kt` (a landing
rule), `core/internal/PlaybackCore.kt` (the seek machine's precise landing, `stepOneFrame`,
`CoreCommand.StepFrame` at 6546). Tests `core/commonTest/.../StepFrameBackwardTest.kt` on the
harness; `kiteplayer-ffmpeg/src/nativeTest/.../BackwardStepRealMediaTest.kt`.

**Contract.**

```kotlin
public enum class StepDirection { Forward, Backward }

/**
 * Steps a PAUSED player by one decoded frame and returns with it on screen. Forward releases the
 * next frame from the queue and needs no seek. Backward lands on the last frame before the current
 * one: the engine seeks to the keyframe before it and decodes forward, so on a long-GOP file it
 * costs up to one group of pictures. Backward needs a seekable source.
 * @throws IllegalStateException when nothing is open, while playing, or at the first frame going backward.
 * @throws UnsupportedOperationException with no selected video track, or backward on a source that cannot seek.
 */
public suspend fun stepFrame(direction: StepDirection = StepDirection.Forward)
```

Internal landing rule: `SeekRequest` gains `landing: SeekLanding = SeekLanding.AtOrAfter`, with
`SeekLanding.Before` meaning: while decoding forward from the keyframe, keep the most recent
frame whose pts is below the target (closing the previous candidate as each new one arrives,
frames are owned by the worker and closed exactly once), and when the first frame at or after
the target arrives, present the candidate and leave that first frame at the head of the queue,
so a forward step afterwards moves to exactly where the user was. Backward step issues
`SeekRequest(Absolute(currentFramePts), Precise, landing = Before)`. If the keyframe seek lands
on a keyframe equal to the current frame (the current frame is itself a keyframe), the request
is re-issued at `currentFramePts - 1 microsecond`, which the container seek resolves to the
previous keyframe.

**Tests.** Harness, scripted VFR pts list `[0, 33_000, 50_000, 100_000, 133_000]` microseconds,
keyframes at 0 and 100_000: paused at 100_000, `stepFrame(Backward)` presents 50_000; again,
33_000; again, 0; again throws `IllegalStateException`. Forward after two backward steps
presents 50_000 again (the head-of-queue rule). Unseekable scripted source: backward throws
`UnsupportedOperationException`. Real media on `truevfr720.mp4`: walk forward ten frames
recording each presented pts, then walk backward ten times; the two sequences are reverses of
each other.

**Steps.** Red the harness tests (they fail to compile on the new parameter, then fail on the
landing). Add `SeekLanding`, the candidate-holding branch in the precise landing loop, the
backward command path. Green on `jvmTest` and one native compile. Real-media test on
`macosArm64Test`. `updateKotlinAbi`. Falsify: make `Before` present the first frame at or after
the target; the harness test lands on 100_000 and goes red.

**Commit.** `core: frame stepping goes backward too, by landing before the target`

---

### V2 Refresh-rate awareness. LANDED 2026-09-03 except the device run

One deviation: macOS answers through CoreGraphics' display mode (the konan AppKit binding has
no maximumFramesPerSecond), and Android's frame-rate matching measures the cadence from two
consecutive frame timestamps in the renderer, because a renderer is handed frames and no track
metadata. The Metal presentAtScheduledTime half stays open below, off by default as written.

### V2, as planned. Size M, Tier 2

**Why.** The SPI says returning null from `vsyncIntervalNanos` "costs smoothness on a high refresh
display and nothing else" (`spi/VideoRenderer.kt:20`). Every renderer returns null. Phones at
120 Hz and Macs at ProMotion pay that cost today.

**Depends on:** nothing.

**Files.** Modify `output/androidMain/.../AndroidGpuImageVideoRenderer.kt:194`, `AndroidSurfaceVideoRenderer.kt:527`,
`output/iosMain/.../UIKitVideoRenderer.kt:508`, `output/macosArm64Main/.../AppKitVideoRenderer.kt:550`,
`output/appleMain/.../MetalVideoRenderer.kt:311`, `output/jvmMain/.../AwtCanvasVideoRenderer.kt:138`,
`core/internal/PlaybackCore.kt:1603` (handle `VsyncChanged`). Tests per platform where the host
can run them; the engine half on the harness.

**Contract.** No public change. Each renderer answers the display it draws into:

- Android: `view.display?.refreshRate` (the `SurfaceView`'s display; `AndroidSurfaceVideoRenderer`
  gets the Surface from `KitePlayerView`, so `KitePlayerView` passes the display's refresh rate
  through `setViewport`'s neighbour, a new internal `setDisplayRefreshRate(hz: Float)`). Re-read on
  `surfaceChanged`. Emit `VsyncChanged` when it changes. On API 30 and above, also call
  `surface.setFrameRate(videoFps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` when a video track is
  selected and the frame rate is known, and `0f` when playback stops. That is the OS's own
  frame-rate matching and needs nothing else.
- iOS: `UIScreen.mainScreen.maximumFramesPerSecond`.
- macOS: `NSScreen.mainScreen?.maximumFramesPerSecond` (macOS 12 is the deployment floor).
- Desktop AWT: `GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate`,
  null when it answers 0 (unknown).
- Web and the Compose canvas keep null; both reasons stand.
- Engine: `VsyncChanged` updates the value the schedule reads (find the read site with
  `grep -rn vsyncIntervalNanos core/internal`); today the schedule reads it once at attach.

Apple half, optional and off by default: `MetalVideoRenderer(presentAtScheduledTime = false)`.
When true, `presentDrawable(atTime:)` is used with the engine's `targetNanos` converted to
`CACurrentMediaTime()` seconds through the same host clock `AppleHostClock` uses. Off by default
because the render-quality law says nothing defaults on without a device measurement; the
DEVICE-DAY step below is that measurement.

**Tests.** Harness: a scripted renderer emitting `VsyncChanged(8_333_333)` after attach changes
the schedule's interval (assert through whatever the schedule exposes to the harness; if nothing,
add an internal getter used only by tests). Desktop: `AwtCanvasVideoRenderer` on a headless JVM
answers null without throwing. Android host: the Surface renderer with a fake display answering
120f reports `vsyncIntervalNanos() == 8_333_333`. Add DEVICE-DAY step 20: "Play `sync1080p30.mp4`
on a 120 Hz phone and on the Mac with `presentAtScheduledTime` on and off; note dropped and
repeated frames from KiteStats for each."

**Commit.** `render: renderers report the display's refresh interval, and Android asks for frame-rate matching`

---

### V3 A real frame-presented signal. LANDED 2026-09-03 except the two exact halves

The best-effort halves and the whole engine path are in; Metal's presented handler and
MediaCodec's rendered listener remain, filed in the plan.

### V3, as planned. Size M, Tier 2

**Why.** `PlayerEvent.FirstFrameRendered` says in its own KDoc that it "is not a report that a
pixel reached a display" (`PlayerEvent.kt:44-48`). Metal's `addPresentedHandler` and MediaCodec's
`setOnFrameRenderedListener` both are. Karaoke, lyric sync, A/V latency measurement and the
render ladder's device numbers all want it.

**Depends on:** nothing.

**Files.** Modify `core/spi/VideoRenderer.kt` (`RendererEvent.FramePresented`), `core/PlayerEvent.kt`,
`core/PlayerConfig.kt` (`frameEvents`), `core/internal/PlaybackCore.kt` (forwarding),
`output/appleMain/.../MetalFrameComposer.kt:195` (handler), `output/androidMain/.../MediaCodecVideoDecoder.kt`
(listener), `output/androidMain/.../AndroidSurfaceVideoRenderer.kt`, `output/jvmMain/.../AwtCanvasVideoRenderer.kt`
and `output/macosArm64Main/.../AppKitVideoRenderer.kt` (after the blit, best effort, `System.nanoTime`
or `mach_absolute_time`).

**Contract.**

```kotlin
// RendererEvent
/** A frame reached the display, or the closest thing this renderer can observe; [exact] says which. */
public data class FramePresented(val pts: Pts, val atNanos: Long, val exact: Boolean) : RendererEvent

// PlayerEvent
/** Emitted per presented frame when [PlayerConfig.frameEvents] is on. [latency] is presentation minus the schedule's target. */
public data class FramePresented(val pts: Pts, val atNanos: Long, val latency: Duration, val exact: Boolean) : PlayerEvent

// PlayerConfig
/** Emit [PlayerEvent.FramePresented] for every frame. Off by default: sixty events a second is a cost nobody should pay unasked. */
val frameEvents: Boolean = false,
```

Metal: `drawable.addPresentedHandler { emit(FramePresented(pts, hostTimeToEngineNanos(it.presentedTime), exact = true)) }`
before `presentDrawable`. Android: `codec.setOnFrameRenderedListener({ _, presentationTimeUs, nanoTime -> ... }, handler)`
on the decoder that owns the surface; map `presentationTimeUs` back to the frame's pts (the
decoder already tracks the pts it hands out; find `normalize` at `MediaCodecVideoDecoder.kt:232-240`).
CPU renderers emit after their blit with `exact = false`.

**Tests.** Harness: with `frameEvents = true` a scripted renderer emitting three `FramePresented`
produces three `PlayerEvent.FramePresented` with the latencies the harness can compute; with
the default config, none, and the renderer events are still consumed (no backpressure).
Metal: `AppKitVideoRendererTest`'s neighbour on macOS: one real frame presented produces one
event with `exact = true` and `atNanos` within 100 ms of now. Android host: a fake codec
invoking the listener produces the event.

**Commit.** `render: a frame-presented event, exact where the platform can say so`

---

### V4 Auto-deinterlace. Size M, Tier 2. After K3 is published

**Why.** Interlaced material still exists (DVD rips, broadcast captures, `vob-mpeg2.vob` in the
fixture set is progressive but the shape is common). KiteFFmpeg reads the field order now;
KitePlayer does not carry it through, and `yadif` and `bwdif` are not compiled into the LGPL
build. K3 compiles the filters. This item is the policy.

**Depends on:** K3 (the filters) and the KiteFFmpeg pin moving, which carries the already-built
`VideoStreamInfo.fieldOrder`.

**Files.** Modify `core/PlayerConfig.kt`, `core/spi/MediaSource.kt` (`PlayerStreamInfo.fieldOrder`),
`kiteplayer-ffmpeg/src/commonMain/.../KiteFFmpegSource.kt` (map the field, prepend the filter),
`kiteplayer-ffmpeg/src/commonMain/.../Conversions.kt`. Tests in `kiteplayer-ffmpeg` commonTest
and one real-media test with an interlaced fixture added to `scripts/testmedia.sh`:
`interlaced480.mpg` (`-flags +ilme+ildct -top 1`, MPEG-2, 8 s), plus its manifest line.

**Contract.**

```kotlin
public enum class FieldOrder { Progressive, TopFirst, BottomFirst, Unknown }
public enum class DeinterlacePolicy { Auto, Off, Always }

// PlayerStreamInfo
val fieldOrder: FieldOrder = FieldOrder.Unknown,
// PlayerConfig
/** Auto deinterlaces when the stream says it is interlaced. Forces software decoding for that stream, like every filter. */
val deinterlace: DeinterlacePolicy = DeinterlacePolicy.Auto,
```

The backend prepends `bwdif=mode=send_frame:deint=interlaced` to the item's filter chain when the
policy says so and the field order is `TopFirst` or `BottomFirst`; the existing filter path then
does what it does for any `videoFilter` (software frames; hardware stands down with the existing
warning). `Always` prepends regardless; `Off` never.

**Tests.** Pure: the chain builder given each policy and each field order. Real media: the
interlaced fixture under `Auto` decodes frames whose `fieldOrder` the renderer sees as
progressive and no combing on a row-difference metric (mean absolute difference between
adjacent rows of the first frame is below 8 out of 255, where the undeinterlaced frame measures
above 20 on the fixture, which the test asserts under `Off` first so the metric is proven).

**Commit.** `ffmpeg: interlaced streams are deinterlaced automatically`

---

### V5 Gamma. Size M, Tier 2

**Why.** `VideoAdjustments.kt:16` says gamma is absent because it cannot ride the affine matrix.
True, and a per-channel power in the shader is one line on each GPU tier. The render-quality law
says a rung lands on Metal and Android GL together.

**Depends on:** nothing.

**Files.** Modify `core/VideoAdjustments.kt`, `output/appleMain/.../MetalVideoSupport.kt` (the
`AdjustUniforms` struct at 124-128, the fragment at 441-447, `packAdjustUniforms` at 566),
`output/androidMain/.../AndroidGpuImageVideoRenderer.kt` (uniforms at 1012-1014, fragment at
1126-1128, `packGlAdjust` at 1151, the upload at 713-720). Every other renderer that overrides
`setAdjustments` gets the same field in its CPU path if it has one; a renderer that does not apply
adjustments at all keeps not applying them and its KDoc says so. Golden tests beside the existing
adjustment goldens (find them: `grep -rln packAdjustUniforms output`).

**Contract.**

```kotlin
/** Power curve on each channel, 0.5 to 2. 1 is neutral; above 1 lifts the mid tones. */
val gamma: Float = 1f,
```

`isIdentity` includes `gamma == 1f`. Uniforms gain `float gamma; int gammaEnabled;` (Metal) and
`uniform float uGamma; uniform float uGammaEnabled;` (GL). Shader: after the matrix,
`if (gammaEnabled) c = pow(max(c, 0), 1.0 / gamma)`. Bit-exact when disabled: the branch is skipped
entirely, as the matrix branch already is.

**Tests.** Golden on both tiers: a uniform mid-grey frame (128) at `gamma = 2f` renders 180 on
every channel within 1; at `gamma = 1f` the output equals the untouched golden byte for byte.
`VideoAdjustments(gamma = 0.4f)` throws.

**Commit.** `render: gamma joins the picture controls, on Metal and GL together`

---

### V6 PNG and JPEG snapshots. LANDED 2026-09-03

Two departures from the block below, both deliberate. The receiver is `SoftwareReadableFrame`,
which `CapturedFrame` implements, so the same call serves a capture and a backend's own frame,
and the test can decode a frame with the backend instead of needing a player session for a
capture. And `space.mp4` does not exist in the test media; `colors-bt709.mp4` is the clip.

### V6, as planned. Size S, Tier 1

**Why.** `captureFrame` returns raw planes. KiteFFmpeg already ships `Frame.encodeImage(codec)`
with the `png` and `mjpeg` encoders compiled in. The wrapper is small and everybody wants it.

**Depends on:** nothing.

**Files.** Create `kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Snapshots.kt`.
Test `kiteplayer-ffmpeg/src/nativeTest/.../SnapshotTest.kt` on `space.mp4`.

**Contract.**

```kotlin
public enum class SnapshotFormat { Png, Jpeg }

/** Encodes a captured frame as a standalone image. Runs the pixel conversion FFmpeg needs on the calling thread. */
public fun CapturedFrame.encode(format: SnapshotFormat = SnapshotFormat.Jpeg): ByteArray
```

Implementation: map `pixelFormat` through `Conversions.kt` to KiteFFmpeg's `PixelFormat`, copy the
planes into the layout `Frame.ofVideo(bytes, width, height, pixelFormat)` documents (read its KDoc
for the plane packing; tightly packed planes in order), then `encodeImage(CodecId.Png)` or
`CodecId.Mjpeg`, then close the frame. Hardware-opaque captures already throw before reaching
here.

**Tests.** A capture from `space.mp4` encoded as PNG starts with the eight PNG signature bytes and
decodes back through `MediaSource.open` on a temp file to the same width and height; JPEG starts
with `FF D8`.

**Commit.** `ffmpeg: a captured frame encodes to PNG or JPEG in one call`

---

### V7 Snapshots with the subtitles on them. Size M, Tier 1

**Why.** A screenshot of a film without its subtitles is half a screenshot. The overlay is
composited by renderers only; the capture never sees it.

**Depends on:** V6, T2 (the cue flow is not needed, but its overlay snapshot helper is shared).

**Files.** Modify `core/KitePlayer.kt` (`captureFrame(withSubtitles)`), `core/CapturedFrame.kt`
(carry the overlay), `core/internal/PlaybackCore.kt` (attach the current overlay to the capture).
Create `kiteplayer-ffmpeg/src/commonMain/.../SnapshotCompositor.kt`. Tests: harness for the
overlay attachment, nativeTest for the compositing on `subbed.mkv`.

**Contract.**

```kotlin
/** Like [captureFrame]; with [withSubtitles] the frame carries the overlay that was on screen, laid out for the frame's own size. */
public suspend fun captureFrame(withSubtitles: Boolean = false): CapturedFrame

// CapturedFrame gains
public val overlay: SubtitleOverlay?   // null when not requested or nothing was showing

// Snapshots.kt
/** RGBA pixels of the frame with the overlay drawn on it, premultiplied alpha blended. */
public fun CapturedFrame.toRgbaWithOverlay(): ByteArray
```

`encode(format)` from V6 uses the overlay when present. The engine lays out the overlay for the
frame's own size at capture time (the rasteriser is already viewport-parametrised, so this is one
extra `rasterize(cues, frameWidth, frameHeight, fontScale, position)` call).

**Tests.** Harness: a scripted session with one active cue captures with `overlay != null` and
`overlay.viewportWidth == frame.size.width`. Real: `subbed.mkv` at 2 s with subtitles on: the
composited RGBA differs from the plain one only inside the cue's bounding boxes (assert equality
outside the union of the overlay image rectangles, inequality inside at least one).

**Commit.** `core: a snapshot can carry the subtitles that were on screen`

---

### V8 Secure surface on Android. LANDED 2026-09-03 except the device screenshot

**Why.** Apps showing paid content ask for `FLAG_SECURE` on the video surface. Nothing exposes it.

**Depends on:** nothing.

**Files.** Modify `kiteplayer-view/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/view/KitePlayerView.kt`.

**Contract.**

```kotlin
/** Marks the surface secure: excluded from screenshots, screen recording and non-secure displays. Off by default. */
public var secure: Boolean
```

Setter calls `surfaceView.setSecure(value)` (API 17). Host test cannot see the stub's effect, so
the item's proof is DEVICE-DAY step 21: "set `secure = true`, take a screenshot: the video area
is black." Add that step.

**Commit.** `view: a secure surface flag on the Android view`

---

### V9 Picture in picture on Android, the helper. LANDED 2026-09-03 except the device run

**Why.** `KitePlayerPlatform.supportsPictureInPicture` on Android mirrors "is the player available"
(`KitePlayerPlatform.android.kt:18-19`), which is not what the name says. The activity owns the
PiP transition; what the view can own is the parameters and the honest capability answer.

**Depends on:** nothing.

**Files.** Modify `kiteplayer-view/src/androidMain/.../KitePlayerView.kt`, `kiteplayer-mobile/src/androidMain/.../KitePlayerPlatform.android.kt`.

**Contract.**

```kotlin
// KitePlayerView
/** Parameters for `Activity.enterPictureInPictureMode`: the video's aspect, this view as the source rect, auto-enter while playing on API 31. */
public fun pictureInPictureParams(autoEnterWhilePlaying: Boolean = true): PictureInPictureParams

// KitePlayerPlatform, Android actual
override val supportsPictureInPicture: Boolean
    get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
```

If the Android actual has no `Context`, add `KitePlayerPlatform.supportsPictureInPicture(context)`
beside the property and make the property's KDoc say it answers only whether the player exists.
Aspect ratio uses `videoSize` and `rotationDegrees` from the snapshot, clamped to the OS's
2.39:1 and 1:2.39 limits.

**Tests.** Host: the aspect computation is a pure function; test it with 1920x1080 rotated 90
giving 9:16, and a 4000x100 frame clamped. Device: DEVICE-DAY step 22, enter PiP from the sample.

**Commit.** `view: picture-in-picture parameters, and an honest capability answer`

---

### V10 Picture in picture on iOS. Size L, NEEDS-DESIGN, owner device at the end

**Why.** iOS PiP needs an `AVPictureInPictureController`, and since iOS 15 that controller accepts
an `AVSampleBufferDisplayLayer` content source, which is the only door for a player that is not
`AVPlayer`. `KitePlayerUIView` draws into a `CALayer` or a `CAMetalLayer` (`KitePlayerUIView.kt:39-40`),
neither of which can be a PiP source.

**Depends on:** nothing. Design commit first (rule 13), then the renderer, then the controller.

**Files.** Create `output/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/SampleBufferVideoRenderer.kt`,
`kiteplayer-view/src/iosMain/.../KitePlayerPictureInPicture.kt`. Modify `KitePlayerUIView.kt`
(third layer choice), `ApplePlayerViewRendererFactory.kt:16`, `kiteplayer-mobile/src/iosMain/.../KitePlayerPlatform.ios.kt:11`.

**Design, to be written as the design commit's KDoc.**
- `SampleBufferVideoRenderer : VideoRenderer` owns an `AVSampleBufferDisplayLayer`. `present`
  turns the frame into a `CVPixelBuffer`: hardware VideoToolbox frames already are one (reach it
  through the frame's hardware handle, the same one the Metal path samples); software frames
  become `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange` through `CVPixelBufferCreateWithPlanarBytes`
  from the frame's planes when the format is NV12 or yuv420p (copying the two chroma planes into
  one interleaved plane for yuv420p), and BGRA through `SoftwareConverter.toRgba` otherwise.
  Wrap in a `CMSampleBuffer` with `CMSampleTimingInfo(presentationTimeStamp = targetNanos as host time)`,
  `enqueue`. Overlay: PiP cannot show the subtitle overlay (the layer shows only the sample
  buffers); the renderer composites the overlay into the pixel buffer only when a flag says so,
  because it costs a CPU blend per frame. `vsyncIntervalNanos` from `UIScreen`.
- `KitePlayerUIView.preferSampleBuffer: Boolean = false` chooses this renderer; the view keeps its
  existing Metal and CALayer paths untouched.
- `KitePlayerPictureInPicture(view, player)` builds `AVPictureInPictureController(contentSource:)`
  with a `sampleBufferDisplayLayer` and a `playbackDelegate` mapping: `setPlaying` to play and
  pause, `timeRangeForPlayback` to `0..duration`, `isPlaybackPaused` from the snapshot,
  `skipByInterval` to a seek, `didTransitionToRenderSize` ignored. `start()`, `stop()`,
  `isSupported` from `AVPictureInPictureController.isPictureInPictureSupported()`.
- `KitePlayerPlatform.supportsPictureInPicture` on iOS answers that static.
- The audio session category is already `Playback`, which PiP requires.

**Tests.** Simulator (iosSimulatorArm64Test in kiteplayer-output): the renderer accepts a
synthesised NV12 frame and the layer's `status` is not `.failed` after one enqueue; a yuv420p
frame goes through the interleave path and the resulting pixel buffer's plane count is 2 and its
chroma plane's first bytes match the expected interleaving of the source planes. The controller
cannot be exercised headless; DEVICE-DAY step 23: "start PiP from the iOS sample while playing,
leave the app, picture keeps moving, play/pause from the PiP window works." The owner runs it.

**Commit lines.** `design: iOS picture in picture rides a sample buffer layer` then
`render: a sample-buffer renderer for iOS, the door to picture in picture` then
`view: an iOS picture-in-picture controller over the sample-buffer renderer`.

---

### V11 Typed video filters through the player. LANDED 2026-09-03

**Why.** `MediaItem.videoFilter` is a raw string behind the low-level opt-in. KiteFFmpeg's
`videoFilters { scale(...); eq(...) }` builds the same string with types. The bridge is one function.

**Depends on:** nothing.

**Files.** Create `kiteplayer-ffmpeg/src/commonMain/.../TypedFilters.kt`. Test beside it.

**Contract.**

```kotlin
/** The typed route to [MediaItem.videoFilter]. No opt-in needed: the DSL is not raw FFmpeg syntax. */
public fun MediaItem.withVideoFilter(chain: FilterChain): MediaItem
```

Implementation: `@OptIn(KitePlayerLowLevelApi::class) copy(videoFilter = chain.description)` where
`description` is whatever `FilterChain` exposes as its compiled string (`KdGoldensTest` in
KiteFFmpeg uses it; copy the accessor name from there).

**Tests.** `videoFilters { scale(1280, 720) }` applied to an item gives `videoFilter == "scale=1280:720"`
(match the golden's exact string).

**Commit.** `ffmpeg: a typed filter chain attaches to a media item without the opt-in`
