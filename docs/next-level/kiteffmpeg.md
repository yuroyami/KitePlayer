# Program K: KiteFFmpeg

Read `README.md` in this directory first, then `../KiteFFmpeg/PLANNING.md` and `GOTCHAS.md`
section 4 (the KiteFFmpeg traps: `apiCheck` needs `-Pkiteffmpeg.hostTargetsOnly=true`, the C
suites never build, the archive the audit reads). This whole program runs in the sibling
repository, `../KiteFFmpeg`. Its gate is `GOTCHAS.md` section 3's KiteFFmpeg half, Tier 2 for
anything under `native/` or `buildSrc/`.

Facts verified against the tree: 193 `ffkmp_*` entry points in `native/kitecodec-c/include/kitecodec_helpers.h`;
swresample is linked and version-reported and no `swr_*` call exists; `Frame.encodeImage` exists
with `png` and `mjpeg` compiled in; the LGPL recipe (`buildSrc/src/main/kotlin/BuildFFmpegTask.kt:442`)
compiles no `yadif`, `bwdif`, `loudnorm`, `ebur128` or `alimiter`, while the filter DSL exposes
`deinterlace()` and `loudnorm()` (`dsl/FilterDsl.kt:270`, `:291`); `MediaSink.addCopyStream`
exists and the packet write is internal; no bitstream filters; `MediaInfo` is a derived view and
there is no metadata-only open; `extractFrame(atMicros)` exists; no `FloatArray` audio accessor.
Tests synthesise media at runtime; there is no fixture directory.

**Adding a C entry point** means, every time: the declaration in `kitecodec_helpers.h`, the body in
the right `helpers_*.c`, a `methods.def` row and a `kj_*.c` wrapper for JNI, the regenerated wasm
binding (both copies, `checkWasmBindingMirror`), the signature baseline
(`symbol-audit.sh --write-signature-baseline`), and the `kc_abi_version` bump the ratchet demands.
The macOS tree proves compile and behaviour here; CI's per-target `buildFFmpegFor*` jobs prove
the other trees. That is the whole reason the C items are grouped.

`lib` means `kiteffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteffmpeg/`.

---

### K4 The filter DSL says what the build lacks. LANDED 2026-09-04

### K4, as planned. Size S, Tier 1

**Why.** `videoFilters { deinterlace() }` compiles to `bwdif`, which is not in the build; the
failure arrives from `avfilter_graph_parse` as an untyped error at graph build time.
`FilterChain.requireAvailable()` exists (`dsl/FilterDsl.kt:236`) and nothing calls it on the way
in.

**Depends on:** nothing. Runs BEFORE K3 so its test is red first.

**Files.** Modify `lib/dsl/FilterDsl.kt`, `lib/FilterGraph.kt` (two overloads). Tests in
`kiteffmpeg/src/nativeTest/.../FilterAvailabilityTest.kt`.

**Contract.**

```kotlin
// FilterChain gains
/** The filters this chain needs that [FFmpeg.hasFilter] answers false for. Empty means the chain can build. */
public fun missingFilters(): List<String>

// FilterGraph.Companion gains, each delegating to the string overload after chain.requireAvailable()
public fun buildVideo(chain: FilterChain, width: Int, height: Int, pixelFormat: PixelFormat, timeBase: Rational, frameRate: Rational, sampleAspectRatio: Rational = Rational(1, 1)): FilterGraph
public fun buildAudio(chain: FilterChain, sampleRate: Int, sampleFormat: SampleFormat, channels: Int, timeBase: Rational, outputSampleRate: Int = 0, outputSampleFormat: SampleFormat = SampleFormat.None, outputChannels: Int = 0): FilterGraph
```

`requireAvailable()` throws `FilterNotFound` naming every missing filter, not the first. The
KDoc of `deinterlace()` and `loudnorm()` names the recipe line that decides.

**Tests.** `videoFilters { deinterlace() }.missingFilters()` equals the list of those among
`bwdif` that `FFmpeg.hasFilter` rejects (written against `hasFilter`, so it stays green when K3
lands); `buildVideo(chain, ...)` with a missing filter throws `FilterNotFound` whose message
contains the filter name; `videoFilters { scale(320, 240) }` builds.

**Commit.** `dsl: a chain can say which filters the build lacks, and refuses typed before FFmpeg does`

---

### K2 The small C entry points: field order and container bitrate. Size S, Tier 2

**Why.** `AVCodecParameters.field_order` and `AVFormatContext.bit_rate` exist in every build and
neither is bound. KitePlayer's auto-deinterlace (V4) and the always-null `containerBitrate` stat
(O1) wait on exactly these two.

**Depends on:** nothing.

**Files.** Modify `native/kitecodec-c/include/kitecodec_helpers.h`, `native/kitecodec-c/src/helpers_codecpar.c`,
`native/kitecodec-c/src/helpers_format.c`, `native/kitecodec-jni/methods.def` plus the matching
`kj_*.c`, the wasm binding (regenerate), `lib/StreamInfo.kt` (`VideoStreamInfo.fieldOrder`),
`lib/MediaSource.kt` (`bitrateBps`), both `MediaSource` actuals and the wasm reader, the wasm fake
in `wasmJsTest`. Tests in `nativeTest` and `wasmJsTest`.

**Contract.**

```c
/* 0 unknown, 1 progressive, 2 top field first, 3 bottom field first; the coded-order variants map to their display order. */
KC_API int32_t ffkmp_codecpar_field_order(const AVCodecParameters *par);
/* The container's own bit rate estimate, or 0 when it has none. */
KC_API int64_t ffkmp_fmt_bit_rate(const AVFormatContext *ctx);
```

```kotlin
public enum class FieldOrder { Unknown, Progressive, TopFirst, BottomFirst }
// VideoStreamInfo gains: val fieldOrder: FieldOrder = FieldOrder.Unknown
// MediaSource gains: public val bitrateBps: Long?   (null when the container reports 0)
```

**Tests.** Native: a synthesised MPEG-4 part 2 encode with `options = mapOf("flags" to "+ilme+ildct", "top" to "1")`
reopened reports `TopFirst`; a plain synthesised encode reports `Progressive` or `Unknown` and
never `TopFirst`. If the mpeg4 encoder does not stamp the field order (check `ffprobe -show_streams`
on the temp file for `field_order`), keep the progressive assertion and note that V4's fixture in
KitePlayer proves the interlaced answer. `bitrateBps` on the synthesised file is above zero and
within 30 percent of file size times eight over duration. Wasm: the fake scripts both fields and
the reader reads them. `apiDump` with the host flag.

**Commit.** `c: field order and container bitrate are bound`

---

### K3 The missing filters join the recipe. Size S code, one rebake, Tier 2

**Why.** The DSL promises `deinterlace()` and `loudnorm()`; the build has neither. Loudness and
deinterlacing are ordinary player features.

**Depends on:** K4 (so the availability test flips from red to green here).

**Files.** Modify `buildSrc/src/main/kotlin/BuildFFmpegTask.kt:442` (append `yadif,bwdif,loudnorm,ebur128,alimiter`
to `--enable-filter=`). The recipe fingerprint moves: follow `CheckFFmpegRecipesTask`'s own
procedure for the expected value. Rebake the macOS tree (`./gradlew :kiteffmpeg:buildFFmpegForMacosArm64`).

**Steps.** Before committing, open the rebaked tree's `ffmpeg-configure.txt` and confirm configure
accepted all five without `--enable-gpl`; any filter it refused as GPL-only is dropped from the
line and named in the commit body. Then `FFmpeg.hasFilter("bwdif")` is true on `macosArm64Test`,
`videoFilters { deinterlace() }.missingFilters()` is empty, and `buildVideo(chain, ...)` on a
synthesised interlaced stream produces frames. `checkFFmpegRecipes` green. The wasm list
(`BuildFFmpegWasmTask.kt:223`) stays minimal on purpose; say so in the commit.

**Commit.** `build: the recipe compiles the filters the DSL already promised`

---

### K5 A public packet write. Size S, Tier 1

**Why.** `MediaSink.addCopyStream` returns a `CopyStream` with no public member, and the write is
`internal` (`MediaSink.jvm.kt:553`, `MediaSink.native.kt:750`). A player that wants to tee its
own packets into a file cannot.

**Depends on:** nothing.

**Files.** Modify `lib/MediaSink.kt` (`CopyStream.write`), both actuals, `lib/Playback.kt`
(`Packet.clone()` if absent). Tests `nativeTest/.../PacketTeeTest.kt`.

**Contract.**

```kotlin
// CopyStream gains
/** Writes one packet read from the stream this copy was declared for. Timestamps are rescaled; the packet stays the caller's. */
@KiteFFmpegLowLevelApi
public fun write(packet: Packet)
// Packet gains, if it lacks one
/** A reference-counted copy that outlives the reader's next read. */
public fun clone(): Packet
```

Ordering law, in the KDoc: packets are written in the order read; a sink with several copy
streams interleaves whatever the caller hands it, which is what the demuxer's own order gives.

**Tests.** Synthesise a two-stream file, `openPacketReader` on both streams, a sink with two
copy streams, loop `read` then `write(packet.clone())`, close both; reopen the output: two
streams, duration equal within one frame, packet count equal (count with a second reader).

**Commit.** `sink: a copy stream accepts packets from the caller`

---

### K7 A probe in one call, and the player's `inspect`. Size S plus S, Tier 1

**Why.** `MediaSource.open` already does the cheap thing (open plus `find_stream_info`, no
decoders, `helpers_format.c:45-58`), but a caller who wants only the facts still has to manage a
source's lifetime. And a media library app wants duration, tracks and chapters for a thousand
files without opening playback.

**Depends on:** nothing. The player half lands in KitePlayer after this is published.

**Files.** KiteFFmpeg: create `lib/MediaProbe.kt`; modify `lib/MediaSource.kt` (two companion
functions). KitePlayer: modify `core/spi/MediaBackend.kt`, `core/KitePlayer.kt`, `kiteplayer-ffmpeg/.../KiteFFmpegMediaBackend.kt`.

**Contract.**

```kotlin
public data class MediaProbe(
    val formatName: String, val durationMicros: Long?, val startTimeMicros: Long, val isSeekable: Boolean,
    val bitrateBps: Long?, val metadata: Map<String, String>, val chapters: List<Chapter>, val streams: List<StreamInfo>,
)
// MediaSource.Companion gains; both open, read, and close before returning
public fun probe(path: String, options: Map<String, String> = emptyMap()): MediaProbe
public fun probe(io: MediaByteSource, options: Map<String, String> = emptyMap()): MediaProbe

// KitePlayer, core
public data class MediaInspection(val duration: Duration?, val tracks: Tracks, val metadata: Map<String, String>, val chapters: List<Chapter>, val seekable: Boolean)
// MediaBackend gains, default null
public suspend fun inspect(media: MediaItem): MediaInspection? = null
/** Reads what [open] would publish, without opening playback. @throws UnsupportedOperationException when the backend cannot. */
public suspend fun inspect(media: MediaItem): MediaInspection
```

**Tests.** KiteFFmpeg: `probe` on a synthesised two-stream file returns two streams and the
duration within 50 ms, and leaves no handle open (the handle table's live count, which
`test_handles.c` can read, is unchanged; or the source's own `close` is observed through the
fake on wasm). KitePlayer: `inspect(item)` through `KiteFFmpegMediaBackend` on `sync1080p30.mp4`
equals the `tracks.all`, `chapters` and `duration` that `open` publishes.

**Commit lines.** `source: a probe that opens, reads and closes in one call` then, in KitePlayer,
`core: inspect a media item without opening playback`.

---

### K8 Thumbnails. LANDED 2026-09-03

**Why.** Seek previews and library grids. `extractFrame(atMicros)`, the `scale` filter and
`encodeImage` already exist; this is the sentence that joins them.

**Depends on:** nothing. Uses the doors' `io` route when the item has one.

**Files.** Create `kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Thumbnails.kt`.
Tests `kiteplayer-ffmpeg/src/nativeTest/.../ThumbnailsTest.kt` on `sync1080p30.mp4`.

**Contract.**

```kotlin
public data class Thumbnail(val position: Duration, val width: Int, val height: Int, val bytes: ByteArray, val format: SnapshotFormat)

public object Thumbnails {
    /** One image per position, scaled to at most [maxWidth] wide, aspect kept. Opens the item once. Positions are keyframe-snapped: fast, not exact. */
    public suspend fun at(item: MediaItem, positions: List<Duration>, maxWidth: Int = 320, format: SnapshotFormat = SnapshotFormat.Jpeg): List<Thumbnail>
}
```

Implementation: `MediaSource.open(path or BlockingMediaIo(item.io))`, for each position
`extractFrame(micros)`, `downloadFromHardware()` if `info.isHardware`, one `FilterGraph.buildVideo("scale=$maxWidth:-2", ...)`
built once from the first frame's geometry and reused, `encodeImage(codec)`, close every frame.

**Tests.** Positions 0, 3 s, 6 s give three images at most 320 wide (decode each back through a
temp file to read its size), the first and third differ in bytes, and the source is closed
after the call (open a second time to prove nothing is held; the test's temp root cleans up).

**Commit.** `ffmpeg: thumbnails at positions, scaled and encoded in one call`

---

### K9 Waveforms. LANDED 2026-09-03

**Why.** Audio apps draw the file. `decodedFrames` plus `copyPlanesToByteArray` give the samples
and nothing turns them into peaks.

**Depends on:** A8's `AudioSamples.toFloatInterleaved` (write it here if A8 has not landed;
identical contract).

**Files.** Create `kiteplayer-ffmpeg/src/commonMain/.../Waveforms.kt`. Tests `WaveformsTest.kt`
(nativeTest) on a synthesised sine written to FLAC through `MediaSink`, and on `audio-flac.flac`.

**Contract.**

```kotlin
public data class Waveform(val bucketDuration: Duration, val peaks: FloatArray, val rms: FloatArray)

public object Waveforms {
    /** Decodes the primary audio stream (or [stream]) into [buckets] equal time slices of peak and RMS, mono-mixed. */
    public suspend fun of(item: MediaItem, buckets: Int = 1000, stream: TrackId? = null): Waveform
}
```

**Tests.** A 2 s sine at amplitude 0.5 gives every bucket's peak within 0.02 of 0.5 and RMS within
0.02 of 0.354; silence gives zeros; `buckets = 0` throws; the real FLAC gives peaks in 0..1 with at
least one above 0.1.

**Commit.** `ffmpeg: a waveform of any item, peaks and RMS per bucket`

---

### K6 Record while playing, the player half. Size M, Tier 2. After K5 is published

**Why.** Saving what is playing, especially from a network source, is a feature people expect
from a player with a demuxer in its hands. K5 makes the write possible; this is the tee.

**Depends on:** K5 published and the pin moved.

**Files.** Modify `core/spi/MediaSource.kt` (an optional capability), `core/KitePlayer.kt`,
`core/PlaybackError.kt` (warning), `kiteplayer-ffmpeg/.../KiteFFmpegSource.kt` (the tee in
`readPacket`). Tests `RecordingTest.kt` in `kiteplayer-ffmpeg` nativeTest on `sync1080p30.mp4`.

**Contract.**

```kotlin
// spi, optional capability a source may implement
public interface RecordingCapable {
    /** Starts copying every selected stream's packets into [path], Matroska. Untouched: no re-encode. */
    fun startRecording(path: String)
    fun stopRecording()
}
// KitePlayer
/** Records the selected streams to [path] as they play. Ends on [stopRecording], on close, or on a seek, which warns [PlaybackWarning.RecordingStopped]. @throws UnsupportedOperationException when the backend cannot. */
public suspend fun startRecording(path: String)
public suspend fun stopRecording()
// PlaybackWarning gains
public data class RecordingStopped(val path: String, val reason: String) : PlaybackWarning()
```

Matroska output because it accepts any codec pairing without a bitstream filter, which the
sink does not have. `KiteFFmpegSource.readPacket` writes `packet.clone()` into the sink's copy
stream for that stream index while recording; a seek closes the sink and warns.

**Tests.** Record 2 s of the clip, stop, reopen the output: video and audio streams, duration
between 1.5 and 2.5 s. A seek during recording produces `RecordingStopped` and a valid file.
A scripted backend without the capability makes `startRecording` throw.

**Commit.** `core: record the selected streams to a file as they play`

---

### K1 swresample, bound and used. Size L, Tier 2

**Why.** The README says audio rate conversion is interim quality and libswresample replaces it
before 1.0. The library is linked and version-reported and not one `swr_*` call exists
(`AudioEncodeGuard.kt:16` says so). The audio encoder refuses a format mismatch instead of
converting, and the player's own resampler is a windowed sinc written in Kotlin.

**Depends on:** nothing. Land the C and Kotlin halves in KiteFFmpeg first; the player half
after the publish.

**Files.** KiteFFmpeg: modify `native/kitecodec-c/include/kitecodec_helpers.h`, create
`native/kitecodec-c/src/helpers_swr.c`, JNI rows and wrapper, wasm binding; create `lib/Resampler.kt`
plus actuals; modify `lib/MediaSink.kt` (`AudioEncoder.drive` converts), `lib/AudioEncodeGuard.kt`.
KitePlayer, later: create `core/spi/AudioResampler.kt`, modify `core/internal/AudioPipeline.kt`
(`buildResampler` picks the SPI when supplied), `core/PlayerConfig.kt`, create
`kiteplayer-ffmpeg/.../KiteFFmpegResampler.kt`.

**Contract, C.**

```c
typedef struct kc_swr kc_swr;
/* Layout masks are FFmpeg channel masks; sample formats are AVSampleFormat values. */
KC_API int      ffkmp_swr_create(kc_swr **out, int32_t in_rate, uint64_t in_mask, int32_t in_fmt, int32_t out_rate, uint64_t out_mask, int32_t out_fmt);
/* `in` may be NULL to flush. `out` must be an allocated frame with rate, layout and format set. */
KC_API int      ffkmp_swr_convert_frame(kc_swr *swr, AVFrame *out, const AVFrame *in);
KC_API int64_t  ffkmp_swr_delay(kc_swr *swr, int64_t base);
KC_API void     ffkmp_swr_free(kc_swr **swr);
```

Implementation: `av_channel_layout_from_mask` for both layouts, `swr_alloc_set_opts2`, `swr_init`,
`swr_convert_frame`, `swr_get_delay`, `swr_free`. Every error maps through the existing error
helper so it arrives typed.

**Contract, Kotlin.**

```kotlin
public data class AudioSpec(val sampleRate: Int, val channels: Int, val sampleFormat: SampleFormat)

/** Converts audio frames between rates, layouts and sample formats through libswresample. */
public class Resampler(public val input: AudioSpec, public val output: AudioSpec) : AutoCloseable {
    /** The converted frame, or null when the resampler buffered everything. The caller owns the result. */
    public fun convert(frame: Frame): Frame?
    /** The remaining buffered samples, or null. */
    public fun flush(): Frame?
    override fun close()
}
```

`AudioEncoder.drive` converts every frame whose spec differs from the encoder's; the guard's
refusal stays for the case where conversion itself fails. KitePlayer:

```kotlin
// core/spi
public interface AudioResampler : AutoCloseable {
    public fun process(input: FloatArray, frames: Int, output: FloatArray): Int
    public fun flush(output: FloatArray): Int
}
public fun interface AudioResamplerFactory { public fun create(inputRate: Int, outputRate: Int, channels: Int): AudioResampler }
// AudioConfig gains: val resampler: AudioResamplerFactory? = null   (null keeps the engine's sinc)
```

**Tests.** KiteFFmpeg native: a 48 kHz stereo fltp sine converted to 44.1 kHz s16 has the expected
sample count within one and a spectrum peak at the same frequency (Goertzel); flush returns the
tail; an unsupported layout mask fails typed. `AudioEncoder.drive` with a fltp frame into an
s16-only encoder now encodes instead of refusing (`PipelineRoundTripTest` gains the case). JNI
and wasm compile in CI. KitePlayer: the SPI adapter passes the same Goertzel test through
`AudioPipeline`; an A/B test resamples a 20 Hz to 20 kHz sweep through both resamplers and
prints the energy above the Nyquist fold for each. The default stays the sinc until the owner
reads those numbers; the README sentence changes only then.

**Commit lines.** `c: swresample is bound` then `sink: the audio encoder converts instead of refusing`
then, in KitePlayer, `audio: a resampler SPI, and the FFmpeg one behind it`.
