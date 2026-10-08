@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.js.JsAny
import kotlin.time.TimeSource

/**
 * Fills a JS byte array with one frame's RGBA, tightly packed, no row padding.
 *
 * This seam exists because of a hard boundary and a hard measurement, and it is the only shape that
 * satisfies both.
 *
 * The boundary: `:kiteplayer-output` must not depend on KiteFFmpeg or FFmpeg, which the module's own
 * build file states and the boundary scans enforce. So the renderer cannot read a frame's pixels
 * itself, exactly as `AndroidSurfaceVideoRenderer` cannot and takes a converter function instead.
 *
 * The measurement: the Android seam hands back a Kotlin `ByteArray`, and on the web that is the
 * slow path by a factor of twenty. Kotlin/Wasm has no bulk typed-array bridge, so pixels in a
 * `ByteArray` cross one byte per JS call, which the web spike measured at 160 to 240 ms per 1080p frame
 * against a 33.3 ms budget. Converting in C and writing straight into the array a canvas is about
 * to draw measured 8.5 to 9.7 ms.
 *
 * So this asks for a FILL rather than a return. The implementation lives wherever KiteFFmpeg is
 * already a dependency, and the pixels never enter Kotlin memory at all.
 */
public fun interface WebFramePainter {
    /**
     * @param destination a JS `Uint8ClampedArray` of exactly width times height times four bytes.
     * @return false when this frame cannot be painted. The renderer counts a drop and carries on.
     */
    public fun paintRgba(frame: VideoFrame, destination: JsAny): Boolean
}

/**
 * Draws frames onto an HTML canvas.
 *
 * ### The two canvases, and why there are two
 *
 * The frame is written into an offscreen canvas at its own stored size with `putImageData`, and
 * that canvas is then drawn onto the visible one with `drawImage`. `putImageData` alone would be
 * one step shorter and cannot do the job: it writes raw pixels and ignores every transform, so
 * letterboxing, zoom, pan and rotation would all be impossible and the picture would only ever
 * appear at its stored size in the top-left corner. `drawImage` respects the context transform,
 * which is what makes the geometry law below apply at all.
 *
 * ### What it does not do
 *
 * This is the first web renderer tier: a canvas under the Compose controls, not the single Compose surface where
 * clip, alpha and rotation apply to the video pixels themselves. That is tier two and is not this.
 * There is also no hardware path: the wasm decoder is software by construction, so
 * [supportedHardwareSurfaces] is empty and always will be on this renderer. The picture controls in
 * [io.github.yuroyami.kiteplayer.VideoAdjustments] are not applied either.
 *
 * ### The flash guard
 *
 * With [FlashGuard.On] each staged picture is measured before it is drawn, and a flashing run is
 * dimmed by laying black over the picture, under the cues (#500, `docs/video-flash-guard.md`). The
 * measurement is one JavaScript loop over 2,304 pixels of the stage, so no pixel enters Kotlin
 * memory. A page has no system setting to follow, so [FlashGuard.FollowSystem] is off here.
 *
 * Not thread-safe, and on the web that is not a constraint: there are no threads. `present` is
 * already `suspend` and runs on the event loop with no worker, no dispatcher and no `runBlocking`.
 *
 * @param keepDisplayAwake whether the page's screen stays awake while pictures are drawn, and for
 *        two seconds after the last one (#238), through [WebDisplayAwake]. In a worker it does
 *        nothing, because a worker has no screen; the page's `KitePlayerWorker` holds it there.
 */
public class WebCanvasVideoRenderer(
    canvas: JsAny,
    private val painter: WebFramePainter,
    private val keepDisplayAwake: Boolean = true,
) : VideoRenderer {

    /** Keeps the original painter constructor, including trailing-lambda calls. */
    public constructor(canvas: JsAny, painter: WebFramePainter) : this(canvas, painter, true)

    private val state: JsAny? = webRendererState(canvas)

    private var viewportWidth: Int = webCanvasWidth(canvas)
    private var viewportHeight: Int = webCanvasHeight(canvas)
    private var scaleMode: VideoScale = VideoScale.Fit
    private var transform: VideoTransform = VideoTransform.Identity
    private var overlay: SubtitleOverlay? = null
    private var overlayHash: Long? = null
    private var closed: Boolean = false

    /** Size and turn of the picture the stage holds, or null while it holds none. */
    private var retainedSize: VideoSize? = null
    private var retainedRotation: Int = 0
    private var retainedMirrored: Boolean = false
    private var retainedCrop: PictureCrop? = null

    /** The flash guard's factor for the retained picture, so a redraw draws it as it was drawn. */
    private var retainedFlashFactor: Float = 1f

    private var flashGuard: FlashGuard = FlashGuard.FollowSystem
    private val guard = VideoFlashGuard()

    /** What the guard read from the last picture it measured, for the tests. */
    internal val flashCells: FloatArray = FloatArray(VideoFlashGuard.CELLS)

    private val started = TimeSource.Monotonic.markNow()

    /** The flash guard's clock, in nanoseconds: when each picture is drawn. */
    internal var flashNanos: () -> Long = { started.elapsedNow().inWholeNanoseconds }

    /**
     * True from [clearPicture] until the next picture is drawn. The canvas then shows its background
     * and the cues, and draws a change of cue at once, because no frame is coming to carry it.
     */
    private var pictureCleared: Boolean = false

    /** Diagnostics, in the same three counts the Android renderer keeps. */
    public var presentedFrames: Long = 0
        private set
    public var failedFrames: Long = 0
        private set

    private val _events = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)
    override val events: Flow<RendererEvent> = _events

    /**
     * The canvas's backing store, in device pixels: the size [setViewport] set, or the canvas's own
     * size before that. The engine lays subtitles out for it, so text lands in the bars and at the
     * canvas's own pixel size (docs/subtitle-placement.md, rule 2).
     */
    override val outputSize: VideoSize?
        get() = if (state != null && viewportWidth > 0 && viewportHeight > 0) VideoSize(viewportWidth, viewportHeight) else null

    /** Software only. A browser's own hardware decoder is WebCodecs' path and does not arrive here. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    /**
     * Any software format, because the painter converts rather than this class. [PlayerPixelFormat.Opaque]
     * is refused: it means a hardware frame, nothing on the web produces one for this renderer, and
     * answering true would let a mis-wired decoder fail per frame instead of at attach.
     */
    override fun supports(format: PlayerPixelFormat): Boolean =
        state != null && format != PlayerPixelFormat.Opaque

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        // Ownership rule 2: this renderer owns the frame from here, including on every failure
        // path, and closes it exactly once. `use` is what makes that true without a finally per
        // return, and there are seven returns below.
        frame.use {
            val s = state ?: return false
            if (closed) return false
            if (frame.hardwareSurface != null) {
                failedFrames++
                return false
            }
            val size = frame.size
            if (size.width <= 0 || size.height <= 0) {
                failedFrames++
                return false
            }
            if (!webRendererStage(s, size.width, size.height)) {
                failedFrames++
                return false
            }
            // Another frame size can give the stage a new, empty canvas, so the retained picture is
            // forgotten until this frame is committed.
            if (size != retainedSize) retainedSize = null
            if (!painter.paintRgba(frame, webStageBytes(s))) {
                failedFrames++
                return false
            }
            val layout = frameLayout(
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                size = size,
                rotationDegrees = frame.rotationDegrees,
                mode = scaleMode,
                transform = transform,
                mirrored = frame.mirrored,
                crop = frame.crop,
            )
            if (layout == null) {
                failedFrames++
                return false
            }
            val flashFactor = flashFactorFor(s, size)
            webCommitStage(s)
            retainedSize = size
            retainedFlashFactor = flashFactor
            retainedRotation = frame.rotationDegrees
            retainedMirrored = frame.mirrored
            retainedCrop = frame.crop
            drawStage(s, layout, flashFactor)
            drawOverlay(s)
            pictureCleared = false
            presentedFrames++
            if (keepDisplayAwake) WebDisplayAwake.framePresented()
            return true
        }
    }

    /**
     * The flash guard's factor for the picture on the stage, 1 when the guard is off. The cells
     * cross from JavaScript as one string of their bytes, as the overlay's pixels cross the other way.
     */
    private fun flashFactorFor(s: JsAny, size: VideoSize): Float {
        if (flashGuard != FlashGuard.On) return 1f
        val packed = webMeasureStage(s, size.width, size.height, VideoFlashGuard.COLUMNS, VideoFlashGuard.ROWS)
        if (packed.length != VideoFlashGuard.CELLS * 4) return guard.current
        for (i in 0 until VideoFlashGuard.CELLS) {
            val at = i * 4
            val bits = packed[at].code or (packed[at + 1].code shl 8) or (packed[at + 2].code shl 16) or (packed[at + 3].code shl 24)
            flashCells[i] = Float.fromBits(bits)
        }
        return guard.factorFor(flashCells, flashNanos())
    }

    /**
     * The flash guard's mode (#500). A change of mode starts the history afresh, as taking the
     * picture off does.
     */
    override fun setFlashGuard(mode: FlashGuard) {
        if (flashGuard == mode) return
        flashGuard = mode
        guard.reset()
    }

    private fun drawStage(s: JsAny, layout: FrameLayout, flashFactor: Float) {
        webDrawStage(
            state = s,
            sourceLeft = layout.sourceLeft,
            sourceTop = layout.sourceTop,
            sourceWidth = layout.sourceWidth,
            sourceHeight = layout.sourceHeight,
            drawLeft = layout.drawLeft,
            drawTop = layout.drawTop,
            drawWidth = layout.drawWidth,
            drawHeight = layout.drawHeight,
            centerX = layout.centerX,
            centerY = layout.centerY,
            rotation = layout.rotationDegrees,
            mirrored = layout.mirrored,
            dim = flashFactor,
        )
    }

    /**
     * Draws the retained picture and its subtitles again, at the current viewport.
     *
     * Resizing a canvas clears it, and a paused player sends no frame that would paint it again. The
     * stage still holds the last picture, so it is drawn from there, exactly as [present] drew it.
     */
    private fun redrawRetained(s: JsAny) {
        if (closed) return
        if (pictureCleared) return drawBackground(s)
        val size = retainedSize ?: return
        val layout = frameLayout(
            canvasWidth = viewportWidth,
            canvasHeight = viewportHeight,
            size = size,
            rotationDegrees = retainedRotation,
            mode = scaleMode,
            transform = transform,
            mirrored = retainedMirrored,
            crop = retainedCrop,
        ) ?: return
        drawStage(s, layout, retainedFlashFactor)
        drawOverlay(s)
    }

    /** The canvas's background with the cues over it, for a picture taken off. */
    private fun drawBackground(s: JsAny) {
        webClearCanvas(s)
        drawOverlay(s)
    }

    /**
     * Subtitles, drawn above the picture and the bars, NOT with the picture's transform.
     *
     * The overlay's viewport maps onto the whole canvas, each axis on its own (rule 1 of
     * docs/subtitle-placement.md). The engine lays it out for [outputSize], so the scale is 1 except
     * between a resize and the engine's next layout.
     *
     * Uploaded only when [SubtitleOverlay.contentHash] changes, and each image crosses in ONE call:
     * its pixels are a Kotlin `ByteArray`, and Kotlin/Wasm has no typed-array bridge, so the bytes
     * travel as a Latin-1 string and one JS loop writes them into the ImageData. This used to cross
     * one byte per JS call, which was tolerable for a cue a second and not for typesetting that
     * re-renders every frame while a sign moves.
     */
    private fun drawOverlay(s: JsAny) {
        val current = overlay
        if (current == null) {
            if (overlayHash != null) {
                overlayHash = null
                webClearOverlay(s)
            }
            return
        }
        if (overlayHash != current.contentHash) {
            webBeginOverlay(s, current.images.size)
            current.images.forEach { image ->
                val bitmap = image.bitmap
                val handle = webOverlayImage(s, bitmap.width, bitmap.height, image.x, image.y)
                val bytes = bitmap.width * bitmap.height * 4
                val packed = StringBuilder(bytes)
                for (i in 0 until bytes) packed.append((bitmap.pixels[i].toInt() and 0xFF).toChar())
                webOverlayBytes(handle, packed.toString())
                webOverlayCommit(s, handle)
            }
            overlayHash = current.contentHash
        }
        val scaleX = if (current.viewportWidth > 0) viewportWidth.toFloat() / current.viewportWidth else 1f
        val scaleY = if (current.viewportHeight > 0) viewportHeight.toFloat() / current.viewportHeight else 1f
        webPaintOverlay(s, scaleX, scaleY)
    }

    /**
     * Null, honestly. `requestAnimationFrame` follows the display and a page can read
     * `screen.refreshRate` nowhere portable, so a guess here would be a number the schedule trusts.
     * Rule 4 says the cost of null is smoothness on a high refresh display and nothing else.
     */
    override fun vsyncIntervalNanos(): Long? = null

    /**
     * The canvas's BACKING STORE is sized here, which is the only place that can be right.
     *
     * A canvas has two sizes: the CSS box the page lays out, and the pixel buffer it draws into.
     * Leaving the buffer at its 300x150 default and stretching it by CSS is the standard way to get
     * a blurry canvas, and no amount of correct geometry above fixes it. [scale] is the device
     * pixel ratio, so a HiDPI display gets the pixels it actually has.
     *
     * Resizing clears the canvas, so the retained picture is drawn again at the new size. That is
     * what keeps a paused picture on screen when a page moves its canvas into a picture in picture
     * window and back.
     */
    override fun setViewport(width: Int, height: Int, scale: Float) {
        val s = state ?: return
        val pixelWidth = (width * scale).toInt().coerceAtLeast(0)
        val pixelHeight = (height * scale).toInt().coerceAtLeast(0)
        viewportWidth = pixelWidth
        viewportHeight = pixelHeight
        webResizeCanvas(s, pixelWidth, pixelHeight)
        redrawRetained(s)
    }

    override fun setScaleMode(mode: VideoScale) {
        scaleMode = mode
    }

    override fun setTransform(transform: VideoTransform) {
        this.transform = transform
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        this.overlay = overlay
        // Not drawn here while a picture plays: the next present draws it above that frame, and
        // drawing now would put subtitles over a picture that is about to be cleared and replaced.
        // With the picture taken off, no frame is coming, so the cue is drawn now.
        val s = state ?: return
        if (pictureCleared && !closed) drawBackground(s)
    }

    /**
     * Takes the picture off (#530): the canvas shows its background, transparent as between the
     * bars, with the cues over it, until the next picture. The stage keeps its storage for that
     * picture, but nothing draws from it again.
     */
    override fun clearPicture() {
        val s = state ?: return
        if (closed) return
        pictureCleared = true
        retainedSize = null
        guard.reset()
        drawBackground(s)
    }

    override fun close() {
        if (closed) return
        closed = true
        overlay = null
        overlayHash = null
        retainedSize = null
        state?.let(::webReleaseState)
    }
}

/** Builds [WebCanvasVideoRenderer]s for one canvas. See [WebCanvasVideoRenderer] for [keepDisplayAwake]. */
public class WebCanvasVideoRendererFactory(
    private val canvas: JsAny,
    private val painter: WebFramePainter,
    private val keepDisplayAwake: Boolean = true,
) : VideoRendererFactory {
    /** Keeps the original painter constructor, including trailing-lambda calls. */
    public constructor(canvas: JsAny, painter: WebFramePainter) : this(canvas, painter, true)

    override val name: String = "web-canvas"
    override suspend fun create(): VideoRenderer = WebCanvasVideoRenderer(canvas, painter, keepDisplayAwake)
}

/* The JS half. Every call takes the state object, so nothing here holds a JS reference in Kotlin
 * beyond that one handle, and the whole draw is a handful of crossings per frame. */

/**
 * Null when the element is not a canvas or has no 2d context, which
 * [WebCanvasVideoRenderer.supports] then reports.
 */
@JsFun(
    """(canvas) => {
      if (!canvas || typeof canvas.getContext !== 'function') return null;
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      return { canvas: canvas, ctx: ctx, stage: null, sctx: null, image: null, w: 0, h: 0, overlay: [], pending: null };
    }""",
)
private external fun webRendererState(canvas: JsAny): JsAny?

@JsFun("(c) => (c && typeof c.width === 'number') ? c.width : 0")
private external fun webCanvasWidth(canvas: JsAny): Int

@JsFun("(c) => (c && typeof c.height === 'number') ? c.height : 0")
private external fun webCanvasHeight(canvas: JsAny): Int

/**
 * The offscreen canvas the frame is written into, rebuilt only when the frame size changes.
 *
 * `OffscreenCanvas` where it exists and a detached element otherwise, so this works in a worker as
 * well as a page, which the worker work will need.
 */
@JsFun(
    """(s, w, h) => {
      if (s.w === w && s.h === h && s.image) return true;
      let stage = null;
      if (typeof OffscreenCanvas !== 'undefined') { stage = new OffscreenCanvas(w, h); }
      else if (typeof document !== 'undefined') { stage = document.createElement('canvas'); stage.width = w; stage.height = h; }
      if (!stage) return false;
      const sctx = stage.getContext('2d');
      if (!sctx) return false;
      s.stage = stage; s.sctx = sctx; s.image = sctx.createImageData(w, h); s.w = w; s.h = h;
      return true;
    }""",
)
private external fun webRendererStage(state: JsAny, width: Int, height: Int): Boolean

@JsFun("(s) => s.image.data")
private external fun webStageBytes(state: JsAny): JsAny

/**
 * Measures the staged picture for the flash guard exactly as `VideoFlashGuard.cellsFromRgba` does:
 * each cell's mean relative luminance from four by four points, in linear light with the BT.709
 * weights. Answers the cells' 32-bit floats as one string of their bytes, low byte first.
 */
@JsFun(
    """(s, w, h, columns, rows) => {
      if (!s.linear) {
        s.linear = new Float32Array(256);
        for (let c = 0; c < 256; c++) { const e = c / 255; s.linear[c] = e <= 0.04045 ? e / 12.92 : Math.pow((e + 0.055) / 1.055, 2.4); }
      }
      if (!s.cells || s.cells.length !== columns * rows) { s.cells = new Float32Array(columns * rows); s.cellBytes = new Uint8Array(s.cells.buffer); }
      const t = s.linear, d = s.image.data, cells = s.cells, n = 4;
      for (let row = 0; row < rows; row++) {
        const top = Math.trunc(row * h / rows), bottom = Math.trunc((row + 1) * h / rows);
        for (let column = 0; column < columns; column++) {
          const left = Math.trunc(column * w / columns), right = Math.trunc((column + 1) * w / columns);
          let sum = 0;
          for (let sy = 0; sy < n; sy++) {
            const y = Math.min(h - 1, Math.max(0, top + Math.trunc((2 * sy + 1) * (bottom - top) / (2 * n))));
            for (let sx = 0; sx < n; sx++) {
              const x = Math.min(w - 1, Math.max(0, left + Math.trunc((2 * sx + 1) * (right - left) / (2 * n))));
              const at = (y * w + x) * 4;
              sum += 0.2126 * t[d[at]] + 0.7152 * t[d[at + 1]] + 0.0722 * t[d[at + 2]];
            }
          }
          cells[row * columns + column] = sum / (n * n);
        }
      }
      return String.fromCharCode.apply(null, s.cellBytes);
    }""",
)
private external fun webMeasureStage(state: JsAny, width: Int, height: Int, columns: Int, rows: Int): String

@JsFun("(s) => { s.sctx.putImageData(s.image, 0, 0); }")
private external fun webCommitStage(state: JsAny)

/**
 * Clears the visible canvas and draws the staged picture through the geometry law.
 *
 * The turn is applied about the layout's centre and the picture drawn into the pre-turn rectangle,
 * which is exactly what the Android renderer does with the same [FrameLayout], so the two cannot
 * disagree about where a rotated frame lands. A mirror is set after the turn, so it applies to the
 * picture first. Only the layout's source rectangle of the stage is drawn, which is how a crop
 * comes off before both.
 *
 * A `dim` below 1 is the flash guard's factor: black over the picture at `1 - dim` leaves `dim` of
 * each encoded value under it, and the bars stay as they were.
 */
@JsFun(
    """(s, sl, st, sw, sh, dl, dt, dw, dh, cx, cy, rot, mirror, dim) => {
      const g = s.ctx, c = s.canvas;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, c.width, c.height);
      if (rot !== 0) { g.translate(cx, cy); g.rotate(rot * Math.PI / 180); g.translate(-cx, -cy); }
      if (mirror) { g.translate(cx, cy); g.scale(-1, 1); g.translate(-cx, -cy); }
      g.drawImage(s.stage, sl, st, sw, sh, dl, dt, dw, dh);
      if (dim < 1) { g.globalAlpha = 1 - dim; g.fillStyle = '#000'; g.fillRect(dl, dt, dw, dh); g.globalAlpha = 1; }
      g.setTransform(1, 0, 0, 1, 0, 0);
    }""",
)
private external fun webDrawStage(
    state: JsAny,
    sourceLeft: Int,
    sourceTop: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    drawLeft: Float,
    drawTop: Float,
    drawWidth: Float,
    drawHeight: Float,
    centerX: Float,
    centerY: Float,
    rotation: Int,
    mirrored: Boolean,
    dim: Float,
)

@JsFun("(s) => { const g = s.ctx; g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, s.canvas.width, s.canvas.height); }")
private external fun webClearCanvas(state: JsAny)

@JsFun("(s, w, h) => { if (s.canvas.width !== w) s.canvas.width = w; if (s.canvas.height !== h) s.canvas.height = h; }")
private external fun webResizeCanvas(state: JsAny, width: Int, height: Int)

@JsFun("(s, n) => { s.overlay = []; s.pending = null; }")
private external fun webBeginOverlay(state: JsAny, count: Int)

@JsFun(
    """(s, w, h, x, y) => {
      const c = (typeof OffscreenCanvas !== 'undefined') ? new OffscreenCanvas(w, h)
              : (typeof document !== 'undefined') ? Object.assign(document.createElement('canvas'), { width: w, height: h })
              : null;
      if (!c) return null;
      const g = c.getContext('2d');
      if (!g) return null;
      return { canvas: c, ctx: g, image: g.createImageData(w, h), x: x, y: y };
    }""",
)
private external fun webOverlayImage(state: JsAny, width: Int, height: Int, x: Int, y: Int): JsAny?

// Latin-1 by construction: every byte is one code unit below 0x100, so no encoding step can touch it.
@JsFun("(h, s) => { if (h) { const d = h.image.data; const n = Math.min(s.length, d.length); for (let i = 0; i < n; i++) d[i] = s.charCodeAt(i); } }")
private external fun webOverlayBytes(handle: JsAny?, packed: String)

@JsFun("(s, h) => { if (h) { h.ctx.putImageData(h.image, 0, 0); s.overlay.push(h); } }")
private external fun webOverlayCommit(state: JsAny, handle: JsAny?)

/** Over the picture, untransformed, with the overlay's viewport scaled onto the whole canvas. */
@JsFun(
    """(s, sx, sy) => {
      const g = s.ctx;
      g.setTransform(1, 0, 0, 1, 0, 0);
      for (const o of s.overlay) g.drawImage(o.canvas, o.x * sx, o.y * sy, o.canvas.width * sx, o.canvas.height * sy);
    }""",
)
private external fun webPaintOverlay(state: JsAny, scaleX: Float, scaleY: Float)

@JsFun("(s) => { s.overlay = []; }")
private external fun webClearOverlay(state: JsAny)

@JsFun("(s) => { s.overlay = []; s.stage = null; s.sctx = null; s.image = null; s.w = 0; s.h = 0; s.cells = null; s.cellBytes = null; }")
private external fun webReleaseState(state: JsAny)
