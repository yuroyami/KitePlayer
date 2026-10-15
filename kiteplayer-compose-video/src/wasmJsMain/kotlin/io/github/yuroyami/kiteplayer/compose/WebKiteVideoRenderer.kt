@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.RenderQuality
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoScaler
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.FrameShape
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.js.JsAny
import kotlin.time.TimeSource

/**
 * How the web renderer reads a frame's pixels: fill a browser `Uint8Array` of exactly width times
 * height times four with the frame's RGBA, false when it cannot. The pixels go from the codec
 * module's memory to that array in one JavaScript `set` and never through Kotlin, which is the whole
 * reason the web path is fast enough to play (#125).
 */
internal class WebFramePaint(
    val paint: (frame: VideoFrame, destination: JsAny) -> Boolean,
    /** Whether [paint] rolls [VideoFrame]'s HDR off to SDR, which only the converter knows. */
    val toneMaps: (VideoFrame) -> Boolean = { false },
    val close: () -> Unit = {},
)

/**
 * The picture the staging array holds, and what the draw needs to place it. A new [generation]
 * means new pixels, so a node uploads it once.
 */
internal class WebStagedPicture(
    val generation: Long,
    /** The browser array the codec filled. The renderer's, and rewritten by the next frame. */
    val pixels: JsAny,
    val size: VideoSize,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val crop: PictureCrop?,
    /** The flash guard's factor for this picture (#500): 1 draws it as the controls make it. */
    val dim: Float,
)

/** The subtitle images, each already in a browser array, and the viewport they were laid out for. */
internal class WebOverlay(
    val generation: Long,
    val items: List<Item>,
    val viewportWidth: Int,
    val viewportHeight: Int,
) {
    internal class Item(val x: Int, val y: Int, val width: Int, val height: Int, val pixels: JsAny)
}

/**
 * The renderer behind the web's [KiteVideoState] (#125).
 *
 * The same contract as its threaded sibling, `KiteVideoRenderer`, with the web's one thread in
 * place of a worker. [present] keeps the newest frame and closes the one it displaces as superseded.
 * The frame is converted only when a [KiteVideo] is about to draw, in [stage], inside Compose's
 * frame callback, so a frame the display never had time for is never converted, and the staging
 * array is never rewritten while something reads it. A KiteVideo node then uploads the staged
 * picture into its own texture and draws it with [WebGlVideoPass].
 *
 * Everything runs on the page's event loop, as `WebCanvasVideoRenderer` does: no lock, no worker,
 * no `runBlocking`.
 */
internal class WebKiteVideoRenderer(
    private val frames: WebFramePaint,
    /** The flash guard's clock, in nanoseconds. */
    private val guardNanos: () -> Long = monotonicNanos(),
) : VideoRenderer {

    private var closed = false

    /** The newest frame accepted and not yet converted. */
    private var held: VideoFrame? = null

    /** The browser array the codec writes into, rebuilt only when the frame size changes. */
    private var staging: JsAny? = null
    private var stagingBytes = 0

    private var generation = 0L
    private var overlayGeneration = 0L

    /** The staged picture, or null while none is shown. */
    var picture: WebStagedPicture? = null
        private set

    /** The subtitle images, or null while there are none. */
    var overlay: WebOverlay? = null
        private set

    private var overlayHash: Long = Long.MIN_VALUE

    private var scaleMode: VideoScale = VideoScale.Fit
    private var transform: VideoTransform = VideoTransform.Identity
    private var adjustments: VideoAdjustments = VideoAdjustments.Identity
    private var cubic = 0

    /** How the picture is drawn now, for the pass. */
    val look: WebLook get() = WebLook(scaleMode, transform, adjustments, cubic)

    /**
     * Moves on whenever the next draw would differ: a frame accepted, the picture taken off, a cue,
     * a setting. Each KiteVideo node draws once per change, on its next display frame.
     */
    private val changeCount = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    private fun changed() {
        changeCount.value += 1
    }

    private var presented = 0L
    private var superseded = 0L
    private var failed = 0L
    val presentedFrames: Long get() = presented
    val supersededFrames: Long get() = superseded
    val failedFrames: Long get() = failed

    /** Conversion plus upload, per published frame. */
    private val cost = FrameCostTracker()
    fun costSnapshot(): KiteVideoFrameCost = cost.snapshot()

    /** The conversion of the newest staged picture, waiting for its upload to complete the cost. */
    private var paintNanosFor: Long = -1L
    private var paintNanos: Long = 0L

    /** Set once when the frame's backend is not the one this renderer reads. */
    private var unsupportedPairing = false

    private var flashGuard: FlashGuard = FlashGuard.FollowSystem
    private val guard = VideoFlashGuard()
    private val guardCells = FloatArray(VideoFlashGuard.MEASURES)

    private var toneMapSaidAt = Long.MIN_VALUE
    private val toneMapClock = TimeSource.Monotonic.markNow()

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /** Software only: the web's decoders hand over memory frames. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = format != PlayerPixelFormat.Opaque

    override fun accepts(shape: FrameShape): Boolean = when (shape) {
        is FrameShape.Memory -> shape.pixelFormat != PlayerPixelFormat.Opaque
        is FrameShape.Surface -> false
    }

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        if (closed || frame.hardwareSurface != null) {
            frame.close()
            failed++
            return false
        }
        held?.let { displaced ->
            displaced.close()
            superseded++
        }
        held = frame
        changed()
        return true
    }

    /**
     * Converts the frame [present] holds into the staging array, if there is one, and answers the
     * picture to draw. Called by a KiteVideo node inside Compose's frame callback, before it draws.
     */
    fun stage(): WebStagedPicture? {
        val frame = held ?: return picture
        held = null
        val started = TimeSource.Monotonic.markNow()
        frame.use {
            if (unsupportedPairing) {
                failed++
                return picture
            }
            val size = frame.size
            if (size.width <= 0 || size.height <= 0) {
                failFrame("a ${size.width}x${size.height} frame has no pixels to draw")
                return picture
            }
            val bytes = size.width.toLong() * size.height * 4
            if (bytes > Int.MAX_VALUE) {
                failFrame("a ${size.width}x${size.height} frame is too large for one browser array")
                return picture
            }
            if (frames.toneMaps(frame)) sayToneMapped(frame)
            val destination = stagingFor(bytes.toInt())
            val painted = try {
                frames.paint(frame, destination)
            } catch (refusal: UnsupportedFrameType) {
                unsupportedPairing = true
                failFrame(refusal.message ?: "this renderer cannot read the backend's frames")
                return picture
            } catch (failure: Throwable) {
                failFrame(failure.message ?: "the converter failed")
                return picture
            }
            if (!painted) {
                failFrame("the codec could not convert a ${size.width}x${size.height} frame to RGBA")
                return picture
            }
            val dim = dimFor(destination, size.width, size.height)
            val crop = frame.crop?.takeIf { !it.isEmpty && it.fits(size.width, size.height) }
            generation += 1
            val staged = WebStagedPicture(
                generation = generation,
                pixels = destination,
                size = size,
                rotationDegrees = quarterTurn(frame.rotationDegrees),
                mirrored = frame.mirrored,
                crop = crop,
                dim = dim,
            )
            picture = staged
            presented++
            paintNanos = started.elapsedNow().inWholeNanoseconds
            paintNanosFor = generation
            return staged
        }
    }

    /** A node uploaded picture [generation] in [millis]; the first upload completes its cost. */
    fun uploaded(generation: Long, millis: Double) {
        if (millis.isNaN() || millis < 0) return
        if (generation != paintNanosFor) return
        paintNanosFor = -1L
        cost.record(paintNanos + (millis * 1_000_000.0).toLong())
    }

    /** A node could not draw at all, for example a browser with no WebGL2. Said once per reason. */
    fun nodeFailed(detail: String) {
        if (reportedNodeFailures.add(detail)) eventFlow.tryEmit(RendererEvent.Failed(detail))
    }

    private val reportedNodeFailures = mutableSetOf<String>()

    private fun stagingFor(bytes: Int): JsAny {
        val current = staging
        if (current != null && stagingBytes == bytes) return current
        // Rewritten only here, in the frame callback, right before [picture] moves to the new
        // generation, so a node never uploads one picture's pixels under another's generation.
        return webByteArray(bytes).also {
            staging = it
            stagingBytes = bytes
        }
    }

    private fun dimFor(rgba: JsAny, width: Int, height: Int): Float {
        if (flashGuard != FlashGuard.On) return 1f
        val packed = webMeasureRgba(rgba, width, height, VideoFlashGuard.COLUMNS, VideoFlashGuard.ROWS)
        if (packed.length != VideoFlashGuard.MEASURES * 4) return guard.current
        for (i in 0 until VideoFlashGuard.MEASURES) {
            val at = i * 4
            val bits = packed[at].code or (packed[at + 1].code shl 8) or
                (packed[at + 2].code shl 16) or (packed[at + 3].code shl 24)
            guardCells[i] = Float.fromBits(bits)
        }
        return guard.factorFor(guardCells, guardNanos())
    }

    private fun sayToneMapped(frame: VideoFrame) {
        val now = toneMapClock.elapsedNow().inWholeMilliseconds
        if (toneMapSaidAt != Long.MIN_VALUE && now - toneMapSaidAt < 1_000L) return
        toneMapSaidAt = now
        eventFlow.tryEmit(RendererEvent.ToneMapEngaged(transfer = frame.colorSpace.transfer.name))
    }

    private fun failFrame(detail: String) {
        failed++
        eventFlow.tryEmit(RendererEvent.Failed(detail))
    }

    override fun vsyncIntervalNanos(): Long? = null

    /** The node's size in device pixels, which is what the engine lays subtitles out for. */
    override fun setViewport(width: Int, height: Int, scale: Float) {
        surfaceWidth = (width * scale).toInt()
        surfaceHeight = (height * scale).toInt()
    }

    override val outputSize: VideoSize?
        get() = if (surfaceWidth > 0 && surfaceHeight > 0) VideoSize(surfaceWidth, surfaceHeight) else null

    override fun setScaleMode(mode: VideoScale) {
        scaleMode = mode
        changed()
    }

    override fun setTransform(transform: VideoTransform) {
        this.transform = transform
        changed()
    }

    override fun setAdjustments(adjustments: VideoAdjustments) {
        this.adjustments = adjustments
        changed()
    }

    /** The scaler is the shader's: Catmull-Rom or bilinear. Dither and the rest have no web stage. */
    override fun setRenderQuality(quality: RenderQuality) {
        cubic = if (quality.scaler == VideoScaler.CatmullRom) 1 else 0
        changed()
    }

    /** A page has no system setting to follow, so [FlashGuard.FollowSystem] is off here. */
    override fun setFlashGuard(mode: FlashGuard) {
        if (flashGuard == mode) return
        flashGuard = mode
        guard.reset()
    }

    /**
     * Each image crosses as ONE Latin-1 string, never one byte per JavaScript call: Kotlin/Wasm has
     * no typed-array bridge, and typesetting can redraw a sign every frame. An unchanged contentHash
     * keeps what the nodes already uploaded.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        if (closed) return
        if (overlay == null || overlay.images.isEmpty()) {
            if (overlayHash != Long.MIN_VALUE || this.overlay != null) {
                overlayHash = Long.MIN_VALUE
                this.overlay = null
                changed()
            }
            return
        }
        if (overlay.contentHash == overlayHash) return
        val items = try {
            overlay.images.mapNotNull { image ->
                val bitmap = image.bitmap
                if (bitmap.width <= 0 || bitmap.height <= 0) return@mapNotNull null
                val bytes = bitmap.width * bitmap.height * 4
                val packed = StringBuilder(bytes)
                for (i in 0 until bytes) packed.append((bitmap.pixels[i].toInt() and 0xFF).toChar())
                WebOverlay.Item(image.x, image.y, bitmap.width, bitmap.height, webBytesFromLatin1(packed.toString()))
            }
        } catch (failure: Throwable) {
            // The hash stays where it was, so the next call is the retry.
            eventFlow.tryEmit(RendererEvent.Failed(failure.message ?: "building an overlay image failed"))
            return
        }
        overlayHash = overlay.contentHash
        overlayGeneration += 1
        this.overlay = WebOverlay(overlayGeneration, items, overlay.viewportWidth, overlay.viewportHeight)
        changed()
    }

    /** Takes the picture off (#530): the frame waiting goes, and the nodes draw the cues alone. */
    override fun clearPicture() {
        if (closed) return
        held?.let { waiting ->
            waiting.close()
            superseded++
        }
        held = null
        picture = null
        guard.reset()
        changed()
    }

    override fun close() {
        if (closed) return
        closed = true
        held?.let { stranded ->
            stranded.close()
            failed++
        }
        held = null
        picture = null
        overlay = null
        staging = null
        stagingBytes = 0
        try {
            frames.close()
        } finally {
            changed()
        }
    }
}

private fun monotonicNanos(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeNanoseconds }
}

@JsFun("(n) => new Uint8Array(n)")
private external fun webByteArray(length: Int): JsAny

// Latin-1 by construction: every byte is one code unit below 0x100, so no encoding step can touch it.
@JsFun("(s) => { const n = s.length; const d = new Uint8Array(n); for (let i = 0; i < n; i++) d[i] = s.charCodeAt(i); return d; }")
private external fun webBytesFromLatin1(packed: String): JsAny

/**
 * Measures an RGBA array for the flash guard exactly as `VideoFlashGuard.cellsFromRgba` does, and
 * as the web canvas renderer's stage measure does: four by four points in each cell, in linear
 * light, each cell's mean relative luminance with the BT.709 weights, then its mean red measure,
 * then the share of it that is a saturated red. Answers the 32-bit floats as one string of their
 * bytes, low byte first, so 432 values cross in one call.
 */
@JsFun(
    """(d, w, h, columns, rows) => {
      const g = globalThis;
      if (!g.__kiteVideoLinear) {
        const t = new Float32Array(256);
        for (let c = 0; c < 256; c++) { const e = c / 255; t[c] = e <= 0.04045 ? e / 12.92 : Math.pow((e + 0.055) / 1.055, 2.4); }
        g.__kiteVideoLinear = t;
      }
      const t = g.__kiteVideoLinear;
      const count = columns * rows;
      const cells = new Float32Array(count * 3);
      const n = 4;
      for (let row = 0; row < rows; row++) {
        const top = Math.trunc(row * h / rows), bottom = Math.trunc((row + 1) * h / rows);
        for (let column = 0; column < columns; column++) {
          const left = Math.trunc(column * w / columns), right = Math.trunc((column + 1) * w / columns);
          let sum = 0, redSum = 0, saturated = 0;
          for (let sy = 0; sy < n; sy++) {
            const y = Math.min(h - 1, Math.max(0, top + Math.trunc((2 * sy + 1) * (bottom - top) / (2 * n))));
            for (let sx = 0; sx < n; sx++) {
              const x = Math.min(w - 1, Math.max(0, left + Math.trunc((2 * sx + 1) * (right - left) / (2 * n))));
              const at = (y * w + x) * 4;
              const r = t[d[at]], gr = t[d[at + 1]], b = t[d[at + 2]];
              sum += 0.2126 * r + 0.7152 * gr + 0.0722 * b;
              const measure = r - gr - b;
              if (measure > 0) redSum += measure;
              if (r > 0 && r >= Math.fround(Math.fround(0.8) * Math.fround(r + gr + b))) saturated++;
            }
          }
          const cell = row * columns + column;
          cells[cell] = sum / (n * n);
          cells[count + cell] = redSum / (n * n);
          cells[2 * count + cell] = saturated / (n * n);
        }
      }
      const bytes = new Uint8Array(cells.buffer);
      let out = "";
      for (let i = 0; i < bytes.length; i += 8192) out += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
      return out;
    }""",
)
private external fun webMeasureRgba(rgba: JsAny, width: Int, height: Int, columns: Int, rows: Int): String
