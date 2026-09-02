# Program T: subtitles

Read `README.md` in this directory first. Facts verified against the tree: `SubtitleConfig` has
six fields and `lookahead` says in its own KDoc that nothing reads it (`PlayerConfig.kt:259-265`).
There is no style override; `CueStyle` (`subtitle/SubtitleCue.kt:72`) has no background box. One
subtitle slot (`Tracks.kt:85`). Active cues are never published to the app; `SubtitleOverlay`
goes to the renderer only (`PlaybackCore.kt:3707`). `SubtitleSource` has no `io` field although
`MediaItem.kt:98` refers to one, and a network subtitle URL fails through the local file read
returning null, reported as a generic string. `CueSelector.activeAt` scans from index 0 every tick
(`subtitle/CueSelector.kt:18-25`). The overlay is composited in output space on Metal
(`MetalVideoSupport.kt:877-886`) and in fitted-video space on the Android surface renderer
(`AndroidSurfaceVideoRenderer.kt:465-481`), while the SPI says output space
(`spi/SubtitleRasterizer.kt:16`).

`core` means `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/`.

---

### T1 Subtitle style override. Size M, Tier 2

**Why.** Users set their own font, size, colour and a background box behind the text. The only
global knobs are `fontScale`, delay and position.

**Depends on:** nothing.

**Files.** Create `core/subtitle/SubtitleStyleOverride.kt`, `core/internal/StyleOverride.kt` (pure
mapping). Modify `core/subtitle/SubtitleCue.kt` (`CueStyle.backgroundColor`), `core/PlayerConfig.kt`
(`SubtitleConfig.style`), `core/KitePlayer.kt` (`setSubtitleStyle`), `core/PlayerState.kt`,
`core/internal/PlaybackCore.kt` (apply before rasterising), and the three rasterisers:
`kiteplayer-output/src/jvmMain/.../DesktopSubtitleRasterizer.kt:45`, `appleMain/.../AppleSubtitleRasterizer.kt:75`,
`androidMain/.../AndroidSubtitleRasterizer.kt:39` (the box). Tests `StyleOverrideTest.kt` (pure),
rasteriser goldens on desktop and Apple, Android on DEVICE-DAY.

**Contract.**

```kotlin
/** Fields set here replace the authored style on every span. Null fields keep the authored value. */
public data class SubtitleStyleOverride(
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val primaryColor: Int? = null,
    val outlineColor: Int? = null,
    val outlineWidthPx: Float? = null,
    val shadowColor: Int? = null,
    val shadowOffsetPx: Float? = null,
    /** ARGB. A box drawn behind each line, padded by [backgroundPaddingPx]. Transparent draws nothing. */
    val backgroundColor: Int? = null,
    val backgroundPaddingPx: Float = 4f,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
)
// CueStyle gains: val backgroundColor: Int = 0x00000000  (transparent)
// SubtitleConfig gains: val style: SubtitleStyleOverride? = null
public fun setSubtitleStyle(override: SubtitleStyleOverride?)
// PlayerSnapshot gains: val subtitleStyle: SubtitleStyleOverride? = null

internal fun applyOverride(cues: List<SubtitleCue>, override: SubtitleStyleOverride?): List<SubtitleCue>
```

Bitmap cues are untouched. The box is drawn by each rasteriser as a filled rectangle behind the
line's text bounds (AWT `fillRect`, CoreGraphics `CGContextFillRect`, Android `drawRect`) with the
padding, under the outline and the shadow, and the overlay image grows by the padding so the box
is never clipped. ASS scripts keep their authored style unless the override names a field: an
override is the user's word over the author's, by design, and the KDoc says so.

**Tests.** Pure: an override with `primaryColor` set changes every span's colour and nothing
else; `null` returns the same list instance. Desktop golden: a cue with a red box renders red
pixels in the rectangle just outside the glyphs and the text pixels unchanged versus the golden
without the box. Apple golden the same. Android rides DEVICE-DAY step 9f.

**Commit.** `subtitles: a style override, with a background box on all three rasterisers`

---

### T2 A cue flow for the app. Size S, Tier 1

**Why.** An app that wants to draw its own subtitles, log them, or read them to a screen reader
cannot see them: the active cues go to the renderer and nowhere else.

**Depends on:** nothing.

**Files.** Modify `core/KitePlayer.kt`, `core/internal/PlaybackCore.kt` (`timeAndPublishCues` at
3584, publish beside `publishOverlay`). Test `SubtitleCuesFlowTest.kt` on the harness.

**Contract.**

```kotlin
/** The cues active right now, delay applied, in draw order. Empty when none. Updates only when the set changes. */
public val subtitleCues: StateFlow<List<SubtitleCue>>
```

**Tests.** Harness with an external SRT of three cues: the flow emits the empty list, then each
cue's singleton list at its start, then empty at its end; a seek into the middle of cue two emits
cue two once; deselecting subtitles emits empty.

**Commit.** `core: the active subtitle cues are published to the app`

---

### T3 A secondary subtitle track. Size M, Tier 1

**Why.** Learners watch with two languages; mpv has `--secondary-sid`. One slot exists.

**Depends on:** T2 (the flow carries both), T1 (position of the second track uses layout).

**Files.** Modify `core/Tracks.kt` (`selectedSecondarySubtitle`), `core/KitePlayer.kt`,
`core/internal/PlaybackCore.kt` (a second cue table, timed with the first, laid out at the top).
Tests `SecondarySubtitleTest.kt`.

**Contract.**

```kotlin
// Tracks gains
val selectedSecondarySubtitle: TrackId? = null,
/** Shows a second subtitle track at the top of the picture. Null clears it. Must differ from the primary. */
public suspend fun selectSecondarySubtitle(track: TrackId?): TrackChange
```

The secondary table's cues are forced to `CueAlignment.TopCenter` before rasterising, and
their images are placed in the overlay after the primary's. External tracks (negative ids) are
allowed on either slot. Selecting the same id on both slots refuses with
`IllegalArgumentException`. Container tracks on the secondary slot reopen the container exactly
like the primary does today, because the demuxer must deliver a second stream.

**Tests.** Harness with `multitrack.mkv`'s shape scripted (two subtitle streams): select stream
A primary and B secondary; at a time where both have a cue, `subtitleCues` holds both, B's
layout alignment is `TopCenter`; clearing the secondary drops B; selecting A on both throws.

**Commit.** `subtitles: a secondary track, drawn at the top`

---

### T4 Subtitle sources through the byte doors, and a typed refusal. Size S, Tier 1

**Why.** `SubtitleSource.io` is documented at `MediaItem.kt:98` and does not exist. A URL
subtitle fails through `readExternalBytesOrNull` returning null (`PlaybackCore.kt:258-260`) and
the app gets `TrackDeselected` with a sentence.

**Depends on:** the doors expansion's Task 1 (`MediaIoFactory`) and Task 8 (`MediaIoResolver.resolve(item)`).

**Files.** Modify `core/MediaItem.kt` (`SubtitleSource.io`), `core/PlaybackError.kt` (warning),
`core/internal/PlaybackCore.kt` (`parseExternalSubtitle` at 252). Tests `SubtitleSourceIoTest.kt`.

**Contract.**

```kotlin
// SubtitleSource gains
/** Read the subtitle bytes through your own code. When null, [uri] is read as a local path, or through the network resolver for http and https. */
val io: MediaIoFactory? = null,
// PlaybackWarning gains
/** An external subtitle could not be read or parsed and was skipped. */
public data class SubtitleSourceUnreadable(val uri: String, val reason: String) : PlaybackWarning()
```

Read order: `io` if set; else `http`/`https` through `config.network.ioResolver` with a
`MediaItem(uri)` carrying the parent item's headers; else the local path. The whole file is read
through the `MediaIo` (subtitles are small; cap at 16 MiB and refuse above it, typed).

**Tests.** Harness: a `SubtitleSource(io = MediaIo.ofBytes(srtBytes))` becomes a selectable track
with the right cue count; an `https://` source with a scripted resolver is read through it; a
missing local file warns `SubtitleSourceUnreadable` naming the uri, not `TrackDeselected`.

**Commit.** `subtitles: sources read through the byte doors, and refuse typed`

---

### T5 A faster cue selector. Size S, Tier 1

**Why.** `CueSelector.activeAt` walks every cue from index 0 on every tick and so does
`nextChangeAfter` (`CueSelector.kt:18-40`). The dense ASS files the device sessions use carry
about 70,000 cues, so each tick pays 70,000 comparisons.

**Depends on:** nothing.

**Files.** Modify `core/subtitle/CueSelector.kt`. Test `CueSelectorTest.kt` (exists? extend or
create) with a property test and a timing bound.

**Contract.** Same public functions, same answers. Precondition already documented: cues sorted
by `startMicros`. Implementation: binary search for the first cue starting after `atMicros`; walk
backward from there while `cue.startMicros + maxCueDurationBefore(index) >= atMicros`, where
`maxDurationPrefix` is computed once per cue list and cached by list identity (a `WeakHashMap`
is not available on all targets; keep a one-entry cache keyed by the list reference plus size,
the engine replaces the list wholesale on every track change). `nextChangeAfter` becomes the
minimum of the next start after `atMicros` and the earliest end among the active cues.

**Tests.** Property: 10,000 seeded random cue lists of up to 500 cues with overlaps, 100 random
times each, the new answers equal the old linear scan (keep the old code as `private fun
activeAtLinear` for the test only, delete after). Timing: 70,000 cues, 10,000 queries, under
200 ms on the JVM (the old code takes seconds; assert the bound, print both).

**Commit.** `subtitles: the cue selector binary searches instead of scanning`

---

### T6 One overlay geometry law. Size M, Tier 2 (Android proof on device)

**Why.** The SPI says subtitles are composited in output space (`spi/SubtitleRasterizer.kt:16`).
Metal does that. The Android surface renderer maps overlay pixels into the fitted video
rectangle (`AndroidSurfaceVideoRenderer.kt:465-481`), and `AndroidGpuImageVideoRenderer` draws no
overlay at all, leaving it to `SubtitleOverlayView` in `kiteplayer-view`. Three answers to "where
does position 1.0 land". One law, one test.

**Depends on:** nothing.

**Files.** Create `core/commonTest/.../OverlayGeometryContract.kt` (an abstract test every renderer's
own test module subclasses, copied per module like the doors' `MediaIoContract`). Modify whichever
renderer fails it: expected `AndroidSurfaceVideoRenderer` (switch to output space using
`outputSize`, which it does not override today, see `spi/VideoRenderer.kt:112`) and the
`SubtitleOverlayView` path (confirm it is output space). Modify the SPI KDoc if the decision goes
the other way. Decide in a one-line design note in the commit body: output space wins, because
that is what the engine lays out for (`PlaybackCore.kt:3674-3679`).

**Tests.** The contract: a renderer with a 1000x500 output and a 4:3 video letterboxed inside it
receives an overlay image at `x = 0, y = 450` sized for a 1000x500 viewport; the pixel that ends up
at the bottom-left of the OUTPUT is the overlay's, not black. Run it against the desktop AWT
renderer (headless `BufferedImage`), the Apple CoreGraphics renderer, the Metal renderer's
geometry function (`MetalVideoSupport.kt:877-886` is pure enough to call), and the Android surface
renderer with a recording `Canvas` seam. Device: DEVICE-DAY step 9 already checks placement;
extend its PASS line with "identical placement on both Android render paths".

**Commit.** `render: subtitles composite in output space on every renderer`
