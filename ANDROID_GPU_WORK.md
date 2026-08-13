# Android GPU Video Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. If those skills are not available in your harness, execute the tasks strictly in order, one at a time, running every verification step before moving on.

**Goal:** Replace KitePlayer's CPU-bound Android video path with a GPU path that stays 100% Compose-native: MediaCodec hardware decode into an ImageReader, frames travel as AHardwareBuffer-backed hardware Bitmaps, and Compose draws them as ImageBitmaps with zero CPU pixel work.

**Architecture:** A new `MediaCodecVideoDecoderFactory` (androidMain of kiteplayer-output) implements the existing `VideoDecoderFactory` SPI, so the engine picks it up through the same factory list it already iterates. Compressed packets from the KiteCodec demuxer are converted from avcC/hvcC length-prefixed form to Annex B by a pure-Kotlin bitstream converter, fed to MediaCodec, and decoded onto an ImageReader Surface configured for GPU sampling. Each output image becomes a `VideoFrame` wrapping a hardware `Bitmap` (`Bitmap.wrapHardwareBuffer`), which the Compose renderer returns directly as an `ImageBitmap`. The existing FFmpeg software path stays untouched as the fallback tier.

**Tech Stack:** Kotlin Multiplatform (androidMain), MediaCodec, ImageReader, HardwareBuffer, Compose Multiplatform, existing KitePlayer SPI (`VideoDecoderFactory`, `VideoDecoder`, `VideoFrame`), KiteCodec 0.0.6 (extradata exposure may require a KiteCodec change, see Task 2).

**Spec:** `/Users/macbook/StudioProjects/#Kite/KiteCodec/SOL_REVIEW.md`, section "Performance findings", blocker 1 ("Android's flagship path is entirely CPU-bound") and the "Central architectural recommendation". This plan implements the review's required direction: "MediaCodec directly to Surface where possible; AHardwareBuffer interop for composited paths; CPU RGBA only as a compatibility fallback."

## Global Constraints

- NEVER create a git branch. Work on `main` in both repos. This is a standing owner rule.
- NEVER add a `Co-Authored-By` trailer or any AI attribution to commits.
- NEVER use em-dashes in Markdown files, documentation, or code comments. Use commas, colons, or parentheses.
- Commit style: short imperative subject, body explains why (see `git log` in the repo for tone). Conventional-commit prefixes (`feat:`, `fix:`) are acceptable and were used by the audit-fix commits.
- Repos: `/Users/macbook/StudioProjects/#Kite/KitePlayer` and `/Users/macbook/StudioProjects/#Kite/KiteCodec`. Quote paths in shell: the `#` breaks unquoted globbing.
- Test suites that must stay green after every task (run from the repo root of each project):
  - KitePlayer: `./gradlew :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest :kiteplayer-ffmpeg:macosArm64Test :kiteplayer-output:macosArm64Test :buildSrc:test`
  - KitePlayer Android compilation: `./gradlew :kiteplayer-output:compileAndroidMain :kiteplayer-core:compileAndroidMain :kiteplayer-ffmpeg:compileAndroidMain`
  - KiteCodec (only if you touch it): `./gradlew :kitecodec-core:jvmTest -Pkitecodec.phoneTargetsOnly=true :kitecodec-core:compileKotlinMacosArm64`
- If KiteCodec changes, republish before building KitePlayer against it: `./gradlew publishToMavenLocal -Pkitecodec.phoneTargetsOnly=true` in KiteCodec. KitePlayer's settings resolve mavenLocal first, so the republish is picked up automatically. KiteCodec version stays 0.0.6 for local work.
- minSdk is 29 (Android 10) across every Android module in both repos, by owner decision. The GPU frame path's API requirements (`ImageReader.newInstance` with a usage flag, `ImageFormat.PRIVATE` GPU sampling, `Bitmap.wrapHardwareBuffer`) are therefore met unconditionally: NO runtime `Build.VERSION` gate is needed anywhere in this plan. Do not lower minSdk back.
- The audit fixes landed in commits `6a74344` (KitePlayer) and `2e60bf3` (KiteCodec) carry `audit P0-x` / `audit P1-x` comments. Do not undo any behavior they introduced. In particular: `VideoDecoderFactory` failures must keep producing `TrackDeselected` warnings with real reasons, and decoder EOF handling must keep retrying `send(null)` until accepted.

## Reality-Check Protocol

You have zero context for this codebase. Every task that names an SPI symbol starts with a grep step to confirm the real signature. The signatures below were verified on 2026-08-13 and are believed current, but if a grep disagrees with this plan, THE CODE WINS, and you adapt mechanically (same shape, corrected names). Known-verified anchor points:

- `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt`
  - `public interface VideoDecoder : AutoCloseable` at about line 31, with `suspend fun send(packet: PlayerPacket?): Boolean`, `suspend fun receive(): VideoFrame?`, `val isDrained: Boolean`, `suspend fun flush(newGeneration: Generation)`.
  - `AudioFormat.durationOf(frames: Int): Pts` at about line 169.
- The engine iterates video factories in `PlaybackCore.createVideoDecoder` (`kiteplayer-core/.../internal/PlaybackCore.kt`, search for `createVideoDecoder`): `factory.create(stream, config.hardwareDecode)` inside `withContext(dispatchers.videoDecode)`, first non-null decoder wins, refusals warn `HardwareDecodeUnavailable` when hardware was requested.
- `VideoFrame` implementations must provide: `pts: Pts`, `duration: Pts?`, `generation: Generation`, `rotationDegrees: Int`, `size: VideoSize`, `pixelFormat: PlayerPixelFormat`, `colorSpace: ColorSpaceInfo`, `hardwareSurface: HwSurfaceKind?`, `close()`. Reference implementation: `KiteCodecVideoFrame` in `kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt` around line 624.
- The Compose bridge on Android is `kiteplayer-compose/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/compose/ImageBitmaps.android.kt` (the audit flagged its mutable-bitmap reuse at line 11).
- Factories come from `BackendSession` (`spi/MediaBackend.kt`). Task 5 adds an engine-side injection point for extra factories so the output module can contribute one without the FFmpeg backend knowing.

---

### Task 0: Baseline measurement harness

You cannot claim a performance win without a before number. The sample app is the measurement vehicle.

**Files:**
- Inspect: `kiteplayer-sample-android/` (module layout, main activity)
- Modify: the sample's player screen composable (grep for `KiteVideo` usage inside `kiteplayer-sample-android/src`)

**Interfaces:**
- Consumes: `KitePlayer.state` (a `StateFlow<PlayerSnapshot>`; grep `PlayerSnapshot` for the statistics fields, the audit made `framesPresented`/dropped counters non-negative and monotonic)
- Produces: a debug overlay showing presented FPS, decode FPS, and dropped frames, plus a logcat line per second: `KitePerf: presented=<n> decoded=<n> dropped=<n>`

- [ ] **Step 1: Reality check.** Run:

```bash
cd "/Users/macbook/StudioProjects/#Kite/KitePlayer"
grep -rn "framesPresented\|framesDecoded\|droppedFrames\|VideoStats\|statistics" kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt | head -20
grep -rn "KiteVideo(" kiteplayer-sample-android/src --include="*.kt" | head
```

Note the exact statistics field names. If no per-second frame counters exist in the snapshot, derive presented FPS in the overlay from successive snapshot samples (sample the flow once per second, subtract).

- [ ] **Step 2: Add the overlay.** In the sample's player screen, add a `Text` in a corner driven by a `LaunchedEffect` that samples the player state once per second and formats the three numbers. Also `Log.i("KitePerf", ...)` the same line. Keep it behind a boolean `showPerfOverlay = true` local so it is trivial to strip later.

- [ ] **Step 3: Build and install on the device you will use for the whole plan.**

```bash
./gradlew :kiteplayer-sample-android:installDebug
```

- [ ] **Step 4: Record the baseline.** Play a 1080p H.264 file (put one in `testmedia/` if none suits; `ls testmedia/`). Let it run 30 seconds. Record the stable FPS number and the logcat lines into `ANDROID_GPU_WORK.baseline.txt` at the repo root (plain text, three lines: device model, media description, observed FPS).

- [ ] **Step 5: Commit.**

```bash
git add -A && git commit -m "chore(sample): add perf overlay and record Android baseline"
```

---

### Task 1: Annex B bitstream converter (pure Kotlin, fully TDD)

MediaCodec wants Annex B (start-code delimited) input for H.264/HEVC, while MP4/MKV packets are length-prefixed (avcC/hvcC). This converter is pure byte-array math, so it lives in commonMain and gets real unit tests. It must also extract the codec-specific-data (SPS/PPS/VPS) from container extradata for `MediaFormat` csd buffers.

**Files:**
- Create: `kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/AnnexB.kt`
- Test: `kiteplayer-output/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/output/AnnexBTest.kt`

**Interfaces:**
- Consumes: nothing from the project, plain `ByteArray`s.
- Produces (exact signatures later tasks rely on):

```kotlin
package io.github.yuroyami.kiteplayer.output

/** Parsed decoder configuration, ready for MediaFormat csd buffers. */
public class CodecSpecificData(
    /** Annex B blob for csd-0: SPS (H.264) or VPS+SPS+PPS (HEVC). */
    public val csd0: ByteArray,
    /** Annex B blob for csd-1: PPS (H.264 only). Null for HEVC. */
    public val csd1: ByteArray?,
    /** NAL length field size from the config record (1, 2 or 4). */
    public val nalLengthSize: Int,
)

public object AnnexB {
    /** Parses an avcC record (ISO 14496-15 AVCDecoderConfigurationRecord). Null when malformed. */
    public fun parseAvcC(extradata: ByteArray): CodecSpecificData?
    /** Parses an hvcC record (HEVCDecoderConfigurationRecord). Null when malformed. */
    public fun parseHvcC(extradata: ByteArray): CodecSpecificData?
    /** True when the payload already carries 00 00 01 / 00 00 00 01 start codes. */
    public fun isAnnexB(payload: ByteArray): Boolean
    /** Rewrites length-prefixed NAL units to 4-byte start codes. Payload already Annex B is returned as-is. Returns null when a length field overruns the buffer (corrupt packet). */
    public fun toAnnexB(payload: ByteArray, nalLengthSize: Int): ByteArray?
}
```

- [ ] **Step 1: Write the failing tests.** Create `AnnexBTest.kt` with real byte vectors:

```kotlin
package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AnnexBTest {

    /** Minimal but structurally valid avcC: version 1, one SPS (4 bytes), one PPS (2 bytes), lengthSizeMinusOne = 3. */
    private val avcc = byteArrayOf(
        0x01,                    // configurationVersion
        0x64, 0x00, 0x28,       // profile, compat, level
        0xFF.toByte(),           // 6 bits reserved + lengthSizeMinusOne (3 -> 4-byte lengths)
        0xE1.toByte(),           // 3 bits reserved + numOfSPS = 1
        0x00, 0x04,              // SPS length = 4
        0x67, 0x64, 0x00, 0x28,  // SPS payload
        0x01,                    // numOfPPS = 1
        0x00, 0x02,              // PPS length = 2
        0x68, 0xEE.toByte(),     // PPS payload
    )

    @Test
    fun parsesAvcC() {
        val csd = AnnexB.parseAvcC(avcc)!!
        assertEquals(4, csd.nalLengthSize)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28), csd.csd0)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte()), csd.csd1!!)
    }

    @Test
    fun refusesTruncatedAvcC() {
        assertNull(AnnexB.parseAvcC(avcc.copyOf(9)))
        assertNull(AnnexB.parseAvcC(ByteArray(0)))
    }

    @Test
    fun convertsLengthPrefixedToStartCodes() {
        val packet = byteArrayOf(
            0x00, 0x00, 0x00, 0x02, 0x65, 0x01,   // NAL 1, length 2
            0x00, 0x00, 0x00, 0x01, 0x41,          // NAL 2, length 1
        )
        val out = AnnexB.toAnnexB(packet, nalLengthSize = 4)!!
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x41),
            out,
        )
    }

    @Test
    fun refusesOverrunningLength() {
        val corrupt = byteArrayOf(0x00, 0x00, 0x00, 0x09, 0x65)
        assertNull(AnnexB.toAnnexB(corrupt, nalLengthSize = 4))
    }

    @Test
    fun passesThroughExistingAnnexB() {
        val annexb = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 1, 0x68)
        assertTrue(AnnexB.isAnnexB(annexb))
        assertContentEquals(annexb, AnnexB.toAnnexB(annexb, 4)!!)
        assertFalse(AnnexB.isAnnexB(byteArrayOf(0, 0, 0, 9)))
    }
}
```

Also add an hvcC test after reading the record layout: hvcC has a 22-byte header, then `numOfArrays`, each array being 1 byte (completeness+NAL type), 2 bytes count, then per-NAL 2-byte length + payload. Build one array with a VPS (type 32), SPS (33), PPS (34), assert all three land in `csd0` in that order, each with a 4-byte start code, and `csd1 == null`.

- [ ] **Step 2: Run to verify failure.**

```bash
./gradlew :kiteplayer-output:jvmTest --tests "*AnnexBTest*" 2>&1 | tail -5
```

If kiteplayer-output has no jvm target, run the common tests through the fastest available target instead: `./gradlew :kiteplayer-output:testDebugUnitTest` or `:kiteplayer-output:macosArm64Test`. Discover with `./gradlew :kiteplayer-output:tasks --all | grep -i test`. Expected: compilation failure (AnnexB unresolved).

- [ ] **Step 3: Implement `AnnexB.kt`.**

```kotlin
package io.github.yuroyami.kiteplayer.output

public class CodecSpecificData(
    public val csd0: ByteArray,
    public val csd1: ByteArray?,
    public val nalLengthSize: Int,
)

/**
 * avcC/hvcC to Annex B conversion for MediaCodec input. Containers store parameter sets in the
 * codec config record and length-prefix every NAL unit; MediaCodec wants start codes and csd
 * buffers. Pure byte math, no platform types, so it is testable on any target.
 */
public object AnnexB {
    private val START = byteArrayOf(0, 0, 0, 1)

    public fun parseAvcC(extradata: ByteArray): CodecSpecificData? {
        if (extradata.size < 7 || extradata[0].toInt() != 1) return null
        val nalLengthSize = (extradata[4].toInt() and 0x03) + 1
        var at = 5
        val spsCount = extradata[at].toInt() and 0x1F; at += 1
        val sps = ArrayList<ByteArray>(spsCount)
        repeat(spsCount) {
            val len = readU16(extradata, at) ?: return null; at += 2
            if (at + len > extradata.size) return null
            sps += extradata.copyOfRange(at, at + len); at += len
        }
        if (at >= extradata.size) return null
        val ppsCount = extradata[at].toInt() and 0xFF; at += 1
        val pps = ArrayList<ByteArray>(ppsCount)
        repeat(ppsCount) {
            val len = readU16(extradata, at) ?: return null; at += 2
            if (at + len > extradata.size) return null
            pps += extradata.copyOfRange(at, at + len); at += len
        }
        if (sps.isEmpty() || pps.isEmpty()) return null
        return CodecSpecificData(
            csd0 = concatWithStartCodes(sps),
            csd1 = concatWithStartCodes(pps),
            nalLengthSize = nalLengthSize,
        )
    }

    public fun parseHvcC(extradata: ByteArray): CodecSpecificData? {
        if (extradata.size < 23 || extradata[0].toInt() != 1) return null
        val nalLengthSize = (extradata[21].toInt() and 0x03) + 1
        var at = 22
        val arrays = extradata[at].toInt() and 0xFF; at += 1
        val units = ArrayList<ByteArray>()
        repeat(arrays) {
            if (at + 3 > extradata.size) return null
            at += 1 // array_completeness + reserved + NAL_unit_type
            val count = readU16(extradata, at) ?: return null; at += 2
            repeat(count) {
                val len = readU16(extradata, at) ?: return null; at += 2
                if (at + len > extradata.size) return null
                units += extradata.copyOfRange(at, at + len); at += len
            }
        }
        if (units.isEmpty()) return null
        return CodecSpecificData(csd0 = concatWithStartCodes(units), csd1 = null, nalLengthSize = nalLengthSize)
    }

    public fun isAnnexB(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        val threeByte = payload[0].toInt() == 0 && payload[1].toInt() == 0 && payload[2].toInt() == 1
        val fourByte = payload.size >= 5 && payload[0].toInt() == 0 && payload[1].toInt() == 0 &&
            payload[2].toInt() == 0 && payload[3].toInt() == 1
        return threeByte || fourByte
    }

    public fun toAnnexB(payload: ByteArray, nalLengthSize: Int): ByteArray? {
        if (isAnnexB(payload)) return payload
        if (nalLengthSize !in intArrayOf(1, 2, 4)) return null
        // First pass sizes the output exactly: each NAL trades its length field for a
        // 4-byte start code.
        var at = 0
        var outSize = 0
        while (at < payload.size) {
            val len = readLength(payload, at, nalLengthSize) ?: return null
            at += nalLengthSize
            if (len < 0 || at + len > payload.size) return null
            outSize += 4 + len
            at += len
        }
        val out = ByteArray(outSize)
        at = 0
        var write = 0
        while (at < payload.size) {
            val len = readLength(payload, at, nalLengthSize)!!
            at += nalLengthSize
            START.copyInto(out, write); write += 4
            payload.copyInto(out, write, at, at + len)
            write += len; at += len
        }
        return out
    }

    private fun readU16(bytes: ByteArray, at: Int): Int? {
        if (at + 2 > bytes.size) return null
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    private fun readLength(bytes: ByteArray, at: Int, size: Int): Int? {
        if (at + size > bytes.size) return null
        var value = 0
        for (i in 0 until size) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private fun concatWithStartCodes(units: List<ByteArray>): ByteArray {
        val total = units.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var write = 0
        for (unit in units) {
            START.copyInto(out, write); write += 4
            unit.copyInto(out, write); write += unit.size
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests until green.** Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add -A && git commit -m "feat(output): add Annex B bitstream converter with csd extraction"
```

---

### Task 2: Expose codec extradata through the SPI

MediaCodec configuration needs the container's decoder config record (avcC/hvcC bytes). Confirm whether `PlayerStreamInfo` already carries it; if not, thread it through. This may require a KiteCodec change.

**Files:**
- Inspect: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaBackend.kt` (or wherever `PlayerStreamInfo` lives; grep)
- Possibly modify: `PlayerStreamInfo`, `KiteCodecSource` mapping, and KiteCodec (`StreamInfo`, C helper, JNI, cinterop)

**Interfaces:**
- Produces: `PlayerStreamInfo.codecExtradata: ByteArray?` (null when the container carries none).

- [ ] **Step 1: Reality check.**

```bash
cd "/Users/macbook/StudioProjects/#Kite/KitePlayer"
grep -rn "class PlayerStreamInfo\|extradata" kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/*.kt
cd "/Users/macbook/StudioProjects/#Kite/KiteCodec"
grep -rn "extradata" kitecodec-core/src/commonMain kitecodec-core/src/nativeMain kitecodec-core/src/jvmAndAndroidMain native/kitecodec-c/src native/kitecodec-jni | head -20
```

- [ ] **Step 2: Branch on findings.**
  - If KiteCodec's `StreamInfo` already exposes extradata bytes: add `codecExtradata: ByteArray? = null` to `PlayerStreamInfo` (default null keeps every existing constructor call compiling), populate it in `KiteCodecSource`'s stream mapping (grep `PlayerStreamInfo(` in kiteplayer-ffmpeg), done.
  - If it does not (expected): add it to KiteCodec first.
    - C helper in `native/kitecodec-c/src/helpers_codecpar.c` (follow the file's existing style exactly):

```c
/* The codec config record (avcC, hvcC, esds payload...) or the raw extradata bytes.
   Returns the byte count, 0 when none, negative AVERROR on bad arguments. When dst is
   non-NULL at most dst_size bytes are copied, so call once with NULL to size. */
KC_API int ffkmp_codecpar_extradata(AVCodecParameters *p, uint8_t *dst, int dst_size) {
    if (!p) return AVERROR(EINVAL);
    if (!p->extradata || p->extradata_size <= 0) return 0;
    if (dst) {
        int n = p->extradata_size < dst_size ? p->extradata_size : dst_size;
        memcpy(dst, p->extradata, n);
        return n;
    }
    return p->extradata_size;
}
```

    - Declare it in `native/kitecodec-c/include/kitecodec_helpers.h` next to the other codecpar getters.
    - Native Kotlin: in `buildStreams` (nativeMain `MediaSource.native.kt`), read it with a two-call size-then-copy under `memScoped` and put it on `StreamInfo` as `codecExtradata: ByteArray? = null` (data class, defaulted so existing call sites compile).
    - JVM: add a JNI entry in `native/kitecodec-jni/kj_codec.c` following the neighboring getters (resolve the codecpar handle, return a `jbyteArray` or null), register it in `methods.def`/registration exactly like its neighbors, surface it in `Internals.jvm.kt`, and populate `buildStreams` in `MediaSource.jvm.kt`.
    - Run the KiteCodec suites from Global Constraints, then republish to mavenLocal, then do the KitePlayer half (the `PlayerStreamInfo` field and the `KiteCodecSource` mapping).
    - The JNI half needs the native library rebuilt; the KiteCodec build does that. If the C build is not wired into the jvmTest path on this machine, `:kitecodec-core:jvmTest` failing to see the new symbol will tell you; investigate how the existing `kj_*` symbols get built (grep buildSrc for the JNI compile task) rather than skipping the test.
  - KiteCodec commits are separate from KitePlayer commits.

- [ ] **Step 3: Verify.** All Global Constraints suites green in both repos. In the sample app or a quick jvm test, open an MP4 with H.264 and assert `codecExtradata` is non-null and starts with byte `0x01` (avcC version).

- [ ] **Step 4: Commit** (each repo separately).

```bash
git add -A && git commit -m "feat: expose codec extradata on stream info for hardware decoders"
```

---

### Task 3: MediaCodecVideoDecoder and the hardware frame

The core deliverable. Lives entirely in `kiteplayer-output` androidMain.

**Files:**
- Create: `kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt`
- Create: `kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/HardwareVideoFrame.kt`

**Interfaces:**
- Consumes: `AnnexB` (Task 1), `PlayerStreamInfo.codecExtradata` (Task 2), SPI types from `spi/Decoders.kt` and `spi/MediaBackend.kt` (grep exact names first: `PlayerPacket`, `Pts`, `Generation`, `VideoSize`, `PlayerPixelFormat`, `ColorSpaceInfo`, `HwSurfaceKind`).
- Produces:

```kotlin
public class MediaCodecVideoDecoderFactory : VideoDecoderFactory {
    override val name: String get() = "MediaCodec hardware"
    override suspend fun create(stream: PlayerStreamInfo, policy: HwdecPolicy): VideoDecoder?
}
```

(Grep `interface VideoDecoderFactory` for the true `create` parameter list and mirror it exactly; the engine calls `factory.create(stream, config.hardwareDecode)`.)

- [ ] **Step 1: Reality check.**

```bash
grep -n "interface VideoDecoderFactory" -A 6 kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt
grep -n "interface PlayerPacket" -A 12 kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/*.kt
grep -n "interface VideoFrame" -A 20 kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/*.kt
grep -n "enum class HwSurfaceKind\|sealed.*HwSurfaceKind" -r kiteplayer-core/src/commonMain
grep -n "class HwdecPolicy\|sealed.*HwdecPolicy" -r kiteplayer-core/src/commonMain | head -3
```

Confirm how a `PlayerPacket` exposes payload bytes and pts. The KiteCodec-backed packet wraps a native packet whose `copyBytes()` exists; if `PlayerPacket` itself lacks a byte accessor, add `fun copyBytes(): ByteArray` to the SPI interface and implement it in the KiteCodec packet wrapper (grep `KiteCodecPacket` in kiteplayer-ffmpeg) before continuing.

- [ ] **Step 2: Add `HwSurfaceKind.AndroidHardwareBuffer`** to the enum (commonMain). One line plus a KDoc line. Nothing else consumes it yet; renderers that switch over `HwSurfaceKind` must keep their else/null branches working (grep for `HwSurfaceKind.` usages and check every `when` is exhaustive or has an else).

- [ ] **Step 3: Write `HardwareVideoFrame`.**

```kotlin
package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.media.Image

/**
 * One decoded frame living in GPU memory. The wrapped hardware Bitmap holds its own reference
 * to the AHardwareBuffer, so the ImageReader Image is closed as soon as construction succeeds
 * and the reader slot is returned to MediaCodec immediately. Compose draws the Bitmap without
 * any CPU pixel access.
 */
public class HardwareVideoFrame internal constructor(
    public val bitmap: Bitmap,
    override val pts: Pts,
    override val duration: Pts?,
    override val generation: Generation,
    override val rotationDegrees: Int,
    override val size: VideoSize,
    override val colorSpace: ColorSpaceInfo,
) : VideoFrame {
    override val pixelFormat: PlayerPixelFormat get() = PlayerPixelFormat.Rgba
    override val hardwareSurface: HwSurfaceKind? get() = HwSurfaceKind.AndroidHardwareBuffer

    private var closed = false
    override fun close() {
        if (closed) return
        closed = true
        // Hardware bitmaps are cheap objects over a refcounted buffer; recycle releases the
        // reference deterministically instead of waiting for the GC.
        bitmap.recycle()
    }

    internal companion object {
        internal fun of(
            image: Image,
            pts: Pts,
            duration: Pts?,
            generation: Generation,
            rotationDegrees: Int,
            colorSpace: ColorSpaceInfo,
        ): HardwareVideoFrame? {
            val buffer = image.hardwareBuffer ?: return null
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            // wrapHardwareBuffer took its own reference (or refused); ours is released either way.
            buffer.close()
            if (bitmap == null) return null
            return HardwareVideoFrame(
                bitmap = bitmap,
                pts = pts,
                duration = duration,
                generation = generation,
                rotationDegrees = rotationDegrees,
                size = VideoSize(
                    width = image.cropRect?.width().takeIf { it != null && it > 0 } ?: image.width,
                    height = image.cropRect?.height().takeIf { it != null && it > 0 } ?: image.height,
                    pixelAspectNumerator = 1,
                    pixelAspectDenominator = 1,
                ),
                colorSpace = colorSpace,
            )
        }
    }
}
```

Adapt `VideoSize`'s real constructor parameters (grep). If `VideoFrame` requires members not listed here, mirror `KiteCodecVideoFrame`'s handling of them.

- [ ] **Step 4: Write the decoder.** Synchronous MediaCodec mode driven by the engine's own send/receive loop, ImageReader as the output surface. Full skeleton (adapt names per Step 1 findings):

```kotlin
package io.github.yuroyami.kiteplayer.output

import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.hardware.HardwareBuffer
import android.graphics.ImageFormat
import java.nio.ByteBuffer

public class MediaCodecVideoDecoderFactory : VideoDecoderFactory {
    override val name: String = "MediaCodec hardware"

    override suspend fun create(stream: PlayerStreamInfo, policy: HwdecPolicy): VideoDecoder? {
        // No SDK_INT gate: minSdk is 29, which covers every API this path uses.
        if (policy is HwdecPolicy.Disabled) return null   // grep the real policy variants
        val mime = when (stream.codec) {                   // grep how codec ids are spelled on PlayerStreamInfo
            "h264", "avc" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "hevc", "h265" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "vp9" -> MediaFormat.MIMETYPE_VIDEO_VP9
            "av1" -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> return null
        }
        val width = stream.width ?: return null
        val height = stream.height ?: return null
        val csd = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> stream.codecExtradata?.let(AnnexB::parseAvcC) ?: return null
            MediaFormat.MIMETYPE_VIDEO_HEVC -> stream.codecExtradata?.let(AnnexB::parseHvcC) ?: return null
            else -> null   // VP9/AV1 need no csd from the container
        }
        return runCatching {
            MediaCodecVideoDecoder(mime, width, height, csd, stream)
        }.getOrNull()      // a refusing device falls through to the software factory
    }
}

internal class MediaCodecVideoDecoder(
    mime: String,
    width: Int,
    height: Int,
    private val csd: CodecSpecificData?,
    private val stream: PlayerStreamInfo,
) : VideoDecoder {

    private val reader: ImageReader = ImageReader.newInstance(
        width, height, ImageFormat.PRIVATE, MAX_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    private val codec: MediaCodec
    private var generation: Generation = Generation.Initial
    private var drainSent = false
    private var drained = false
    override val isDrained: Boolean get() = drained
    /** pts of queued inputs, so output frames can be paired with a duration later if needed. */
    private val bufferInfo = MediaCodec.BufferInfo()
    /** Decoded images MediaCodec has released to the reader, in presentation order. */
    private val ready = ArrayDeque<HardwareVideoFrame>()

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        csd?.let {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(it.csd0))
            it.csd1?.let { c1 -> format.setByteBuffer("csd-1", ByteBuffer.wrap(c1)) }
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            ?: run { reader.close(); throw IllegalStateException("no decoder for $mime") }
        codec = MediaCodec.createByCodecName(codecName)
        try {
            codec.configure(format, reader.surface, null, 0)
            codec.start()
        } catch (failure: Throwable) {
            codec.release(); reader.close(); throw failure
        }
    }

    override suspend fun send(packet: PlayerPacket?): Boolean {
        if (drainSent && packet == null) return true
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return false   // engine retries; audit P1-7 contract
        if (packet == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainSent = true
            return true
        }
        val payload = csd?.let { AnnexB.toAnnexB(packet.copyBytes(), it.nalLengthSize) }
            ?: packet.copyBytes()
        if (payload == null) return true   // corrupt packet: swallow, same tolerance as the software path
        val input = codec.getInputBuffer(inputIndex) ?: return false
        input.clear(); input.put(payload)
        codec.queueInputBuffer(inputIndex, 0, payload.size, packet.pts.micros, 0)
        return true
    }

    override suspend fun receive(): VideoFrame? {
        pumpOutput()
        return ready.removeFirstOrNull()
    }

    private fun pumpOutput() {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            when {
                outIndex >= 0 -> {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasFrame = bufferInfo.size > 0
                    // render=true pushes the frame to the ImageReader surface.
                    codec.releaseOutputBuffer(outIndex, hasFrame)
                    if (hasFrame) {
                        val image = reader.acquireNextImage()
                        if (image != null) {
                            val frame = HardwareVideoFrame.of(
                                image = image,
                                pts = Pts(bufferInfo.presentationTimeUs),
                                duration = null,
                                generation = generation,
                                rotationDegrees = stream.rotationDegrees ?: 0,
                                colorSpace = ColorSpaceInfo.Unspecified,
                            )
                            image.close()
                            if (frame != null) ready.addLast(frame)
                        }
                    }
                    if (eos) { drained = true; return }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> return
            }
        }
    }

    override suspend fun flush(newGeneration: Generation) {
        generation = newGeneration
        codec.flush()
        codec.start()   // flush in surface mode returns the codec to a state that needs start on some devices; verify on target device, remove if start throws IllegalStateException on yours
        drainSent = false
        drained = false
        while (true) { ready.removeFirstOrNull()?.close() ?: break }
        while (true) { reader.acquireNextImage()?.close() ?: break }
    }

    override fun close() {
        while (true) { ready.removeFirstOrNull()?.close() ?: break }
        runCatching { codec.stop() }
        codec.release()
        reader.close()
    }

    private companion object {
        const val MAX_IMAGES = 6
        const val DEQUEUE_TIMEOUT_US = 0L
    }
}
```

Known device-behavior caveats to verify live (do not guess, test on the device): whether `codec.start()` is needed after `flush()` in surface mode (the docs say resume is automatic for surface output on API 29+; if `start()` throws, delete that line), and whether `acquireNextImage` can lag one `releaseOutputBuffer` call (if frames arrive with a delay, drain the reader inside `receive()` as written; the queue handles ordering).

- [ ] **Step 5: Compile.**

```bash
./gradlew :kiteplayer-output:compileAndroidMain 2>&1 | grep -E "^e:|BUILD"
```

Fix until green. The commonMain suites from Global Constraints must also stay green (the new files are androidMain plus one enum entry and possibly one SPI method; run the full list).

- [ ] **Step 6: Commit.**

```bash
git add -A && git commit -m "feat(output): MediaCodec hardware decoder producing AHardwareBuffer frames"
```

---

### Task 4: Compose consumes the hardware frame with zero copies

**Files:**
- Modify: `kiteplayer-compose/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/compose/ImageBitmaps.android.kt`

**Interfaces:**
- Consumes: `HardwareVideoFrame.bitmap` (Task 3).
- Produces: the existing android actual conversion function (grep its exact name; it is the function the audit flagged at line 11) returns `frame.bitmap.asImageBitmap()` for hardware frames, preserving its current behavior for every other frame type.

- [ ] **Step 1: Reality check.** Read the whole file (it is short) and the caller in `KiteVideoRenderer` (grep `ImageBitmaps` or the actual function name in kiteplayer-compose commonMain). Understand the frame-to-ImageBitmap flow and where frames get closed. Critical ownership question to answer from the code: does the renderer close the `VideoFrame` after conversion? If yes, the hardware `Bitmap` must survive the frame close while the ImageBitmap is still on screen. Resolve by having `HardwareVideoFrame.close()` NOT recycle when the bitmap was handed out; instead hand out the bitmap and let the next conversion recycle the previous one, mirroring however the current code manages its reused software bitmaps. Implement whichever ownership rule the surrounding code actually uses; document it in a comment at the conversion site.

- [ ] **Step 2: Implement.** At the top of the android conversion function:

```kotlin
if (frame is io.github.yuroyami.kiteplayer.output.HardwareVideoFrame) {
    // GPU tier: the bitmap wraps an AHardwareBuffer, Compose samples it directly. No CPU pixels.
    return frame.takeBitmap().asImageBitmap()
}
```

Where `takeBitmap()` is a new method on `HardwareVideoFrame` transferring bitmap ownership (frame.close() then skips recycle when taken; the consumer recycles the previous bitmap when the next frame replaces it, or leans on the GC if the surrounding code does). Match the module dependency direction: kiteplayer-compose must already depend on kiteplayer-output for this cast; check its build.gradle.kts, and if the dependency is absent, add it the same way the module declares its other project dependencies.

- [ ] **Step 3: While in the file, fix the audit's fence finding for the software path.** The flagged pattern reuses ONE mutable bitmap for every frame. Change to a two-bitmap flip (allocate two, write into the one not last handed to Compose, alternate). Keep the software path otherwise untouched.

- [ ] **Step 4: Compile everything.** Global Constraints suites plus `:kiteplayer-compose:compileAndroidMain`.

- [ ] **Step 5: Commit.**

```bash
git add -A && git commit -m "feat(compose): draw hardware frames as ImageBitmaps without CPU copies"
```

---

### Task 5: Engine wiring: factory injection and policy

The engine builds decoders from `BackendSession.videoDecoders` (owned by the FFmpeg backend). The output module cannot reach into that list, so give the player a supported injection point.

**Files:**
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt`
- Modify: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt` (function `createVideoDecoder`)
- Modify: `kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidOutputBackend.kt`
- Test: `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/KitePlayerTest.kt` (add one test)

**Interfaces:**
- Produces: `PlayerConfig.extraVideoDecoders: List<VideoDecoderFactory> = emptyList()`, tried BEFORE the backend's own factories. Android's output backend (or the sample) supplies `listOf(MediaCodecVideoDecoderFactory())`.

- [ ] **Step 1: Reality check.** Read `PlayerConfig.kt` (the audit added an `init` validation block; your new field needs no validation but must not break it) and `createVideoDecoder` in PlaybackCore (the audit changed its factory loop to retain per-candidate failure reasons; preserve that behavior exactly for the injected factories too). Also check how `AndroidOutputBackend` is handed to the player and whether config construction sites exist in the sample.

- [ ] **Step 2: Add the config field** with KDoc:

```kotlin
/**
 * Video decoder factories tried BEFORE the media backend's own decoders, in order. This is how
 * a platform output module contributes a hardware decoder (MediaCodec on Android) without the
 * demuxing backend knowing about it. An empty list means backend decoders only.
 */
public val extraVideoDecoders: List<io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory> = emptyList(),
```

- [ ] **Step 3: Use it in `createVideoDecoder`.** The loop becomes `for (factory in config.extraVideoDecoders + session.videoDecoders)`, preserving the audit's failure-reason retention and `HardwareDecodeUnavailable` warning behavior for every factory.

- [ ] **Step 4: Respect `HwdecPolicy`.** Grep the policy type. Behavior to implement: `Require` means if no factory produced a hardware decoder, the existing deselect-and-warn path runs its course (no new code needed if the factory list simply produced nothing); `Prefer` is the default ordering already achieved by putting extras first; `Disabled`/off means `MediaCodecVideoDecoderFactory.create` returns null (already implemented in Task 3). Confirm the real variant names and adjust Task 3's check.

- [ ] **Step 5: Test.** In `KitePlayerTest.kt`, add a test using the existing harness pattern (copy the shape of a neighboring test): configure a fake `VideoDecoderFactory` in `extraVideoDecoders` that records it was called and returns null, open scripted media with video, assert the fake was consulted and playback still opened via the harness's own decoder. Run `:kiteplayer-core:jvmTest`.

- [ ] **Step 6: Wire Android.** Wherever the sample (and Synkplay-style consumers) build their `PlayerConfig` for Android, add `extraVideoDecoders = listOf(MediaCodecVideoDecoderFactory())`. If `AndroidOutputBackend` exposes a convenience constructor for config, add the list there so consumers get it by default; read the file and follow its conventions.

- [ ] **Step 7: All suites green, then commit.**

```bash
git add -A && git commit -m "feat(core): let platform outputs inject video decoder factories"
```

---

### Task 6: Device verification and measurement

- [ ] **Step 1: Install the sample on the same device as Task 0.** Same media file.

- [ ] **Step 2: Functional checks, in this order:**
  - Playback shows moving video (not black, not frozen). A black screen with running audio means the ImageReader images never reach Compose: log `reader.acquireNextImage` results and the conversion path.
  - Seek five times rapidly. No crash, no stale frame stuck on screen (exercises `flush`, audit P0-7's quiescence rules still hold).
  - Pause and resume. Frame freezes and resumes.
  - Rotate a portrait-recorded phone clip: orientation correct (rotationDegrees flows through).
  - Play a file the device cannot hardware-decode (e.g. an old MPEG-2 or a 10-bit HEVC on a device without the profile): playback still works through the software fallback and a `HardwareDecodeUnavailable`/`TrackDeselected`-free open (the MediaCodec factory returned null silently; only `HwdecPolicy` requests should warn).
  - Let a file play to the end: EOF drain completes and the player reaches Ended (exercises `drainSent`/`isDrained`, audit P1-7).
  - 30-minute soak on the 1080p file while watching `adb shell dumpsys meminfo <sample-package>` every 10 minutes: stable graphics memory (catches Image/HardwareBuffer leaks).

- [ ] **Step 3: Measure.** Same 30-second run as Task 0. Append device, media, and the new FPS to `ANDROID_GPU_WORK.baseline.txt` as an "after" section. Success criterion: the 1080p file plays at its native frame rate (24/30/60) with dropped frames near zero, and CPU usage (`adb shell top -n 1 | grep <package>`) drops substantially versus baseline.

- [ ] **Step 4: Update docs.** Add a short section to `KPKMP.md` (or the README section that describes rendering, grep for where the Android renderer is documented) stating the two tiers: MediaCodec GPU path (API 29+, H.264/HEVC/VP9/AV1, falls back automatically) and the CPU compatibility path. No em-dashes.

- [ ] **Step 5: Commit, push, publish.**

```bash
git add -A && git commit -m "feat(android): verify GPU video path on device and document the tiers"
git push origin main
./gradlew publishToMavenLocal
```

If KiteCodec changed in Task 2 and was not yet pushed: push it and republish it too.

---

## Explicitly Out of Scope (do not drift into these)

- Apple side (Metal readback removal, `ImageBitmaps.ios.kt`): separate work block.
- GLES/Vulkan YUV upload for the software-decode path: the CPU fallback stays as-is beyond the Task 4 fence fix.
- Audio path, subtitle rendering, dispatcher-count reduction, snapshot allocation reduction: SOL_REVIEW performance items handled elsewhere.
- Bitstream filters beyond avcC/hvcC to Annex B (no MPEG-TS work, no ADTS).
- Secure/DRM content, tunneled playback, dynamic resolution switching beyond what `INFO_OUTPUT_FORMAT_CHANGED` already tolerates.

## Failure Escalation

If a device behaves differently from this plan's MediaCodec assumptions (flush semantics, csd handling, reader timing), prefer the device's observed behavior, add a code comment naming the device and the observation, and keep the software fallback reachable. If `PlayerPacket` cannot expose bytes without a KiteCodec API change you cannot make safely, stop and report rather than inventing a parallel packet path.
