package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.FlashGuard
import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFlashGuard
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Fills a packed integer raster with one frame's pixels, one ARGB value per pixel, no row padding.
 *
 * The seam exists for the boundary reason its Android and Web twins exist for:
 * `:kiteplayer-output` must not depend on KiteFFmpeg, so a renderer here cannot read a frame's
 * pixels itself and is handed the conversion instead. The implementation lives where KiteFFmpeg
 * is already a dependency.
 *
 * An `IntArray` rather than a `ByteArray` because that is what a `BufferedImage` of type
 * `TYPE_INT_RGB` stores, so the converted pixels land directly in the image's own backing buffer
 * with no second copy on the way to the screen.
 */
public fun interface AwtFramePainter {
    /**
     * @param destination exactly `width * height` entries, each `0xRRGGBB` with the top byte free.
     * @return false when this frame cannot be painted; the renderer counts it failed and carries on.
     */
    public fun paintArgb(frame: VideoFrame, destination: IntArray, width: Int, height: Int): Boolean

    /**
     * Whether painting [frame] rolls HDR off to standard dynamic range.
     *
     * The renderer publishes `RendererEvent.ToneMapEngaged` on the strength of this and nothing
     * else. The default is false, which is the truthful answer for a painter that does not
     * convert colour: a painter that DOES must say so, because only it can tell tone mapping
     * apart from handing HDR through untouched.
     */
    public fun toneMapped(frame: VideoFrame): Boolean = false
}

/**
 * Draws over every frame an [AwtCanvasVideoRenderer] composes, after the picture and the subtitles.
 *
 * It exists for player controls: nothing a toolkit draws can appear above a native canvas, so what
 * must show over the picture is drawn into the same frame. [paint] runs on whichever thread paints
 * the frame, with the renderer's paint lock held, so it reads only what is safe from any thread
 * and never waits.
 */
public fun interface AwtCanvasDecoration {
    /** Draws into a canvas of [width] by [height], in the canvas's own coordinates. */
    public fun paint(graphics: java.awt.Graphics2D, width: Int, height: Int)
}

/**
 * Draws frames onto an AWT canvas, on the engine's video scheduler rather than Compose's clock.
 *
 * ### Why this exists next to the Compose renderer
 *
 * Compose draws the whole window as one scene on one clock, so heavy UI work delays the picture.
 * This renderer paints a heavyweight AWT canvas off that clock through a `BufferStrategy`, which
 * the window server presents independently. Measured 2026-08-30: with the Compose frame
 * clock choked to 8 percent of its rate, a canvas painted this way kept 100 percent of its own.
 *
 * ### The ownership rule that matters most
 *
 * Every frame handed to [present] is closed exactly once, on every path including refusal. A leak
 * here is 3.11 MB per 1080p frame, so the paths that refuse are the ones worth reading: no canvas,
 * no peer yet, a zero-sized canvas, a painter that declines the frame, and a closed renderer.
 * Each closes and counts.
 *
 * ### Where the painting happens, said plainly because it is easy to assume otherwise
 *
 * [present] converts and paints on the thread that calls it, which is the engine's video
 * scheduler. That is already independent of Compose, which is the decoupling this path exists
 * for; it is NOT independent of the engine, and a slow present slows the schedule that called it,
 * an [AwtCanvasDecoration] included, since it paints in the same call. There is no queue and no painter thread here,
 * so nothing is ever superseded inside this renderer and [supersededFrames] stays zero: the
 * counter exists because the view's ledger sums one number across every renderer it builds, and a
 * renderer with nothing to report reports nothing rather than being absent from the sum.
 *
 * Every member may be called from any thread, and the engine does so: [present] on its video
 * scheduler, [setOverlay] on the subtitle raster lane and the actor, the picture controls on the
 * actor, and a resize on the AWT event thread. One paint lock serialises every draw, and
 * [setCanvas] and [close] take it too, so a canvas handed back is fenced from the paint in flight.
 * The fence waits for that paint for at most 500 ms and then goes on without it (#691), because the
 * view calls [setCanvas] under AWT's tree lock and a paint that builds a `BufferStrategy` may want
 * that lock, so an unbounded wait could deadlock the event thread (#225). See [setCanvas] for what
 * a paint that outlives the wait can still do.
 */
public class AwtCanvasVideoRenderer(
    private val painter: AwtFramePainter,
    private val onVideoGeometry: (VideoSize, Int) -> Unit = { _, _ -> },
) : VideoRenderer {

    private val presented = AtomicLong(0)
    private val superseded = AtomicLong(0)
    private val failed = AtomicLong(0)

    public val presentedFrames: Long get() = presented.get()
    public val supersededFrames: Long get() = superseded.get()
    public val failedFrames: Long get() = failed.get()

    private val eventFlow = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)
    override val events: Flow<RendererEvent> get() = eventFlow

    private val hdrAnnouncer = HdrAnnouncer { eventFlow.tryEmit(it) }

    private val lock = Any()
    private var canvas: Canvas? = null
    private var closed = false

    /** Holds the BufferStrategy's own size, which is why it is per renderer and not a singleton. */
    internal var presenter: CanvasPresenter = AwtCanvasPresenter()

    /** How recent a frame means the picture plays, so its next frame draws a turned view; a test widens it. */
    internal var playingWindowNanos: Long = PLAYING_WINDOW_NANOS

    /** The flash guard's clock, in nanoseconds: when each picture is painted. */
    internal var guardNanos: () -> Long = System::nanoTime

    /**
     * Held for the whole of a paint, so one thread at a time draws into the one BufferStrategy
     * (#225). Fair, so a waiting fence goes before the next frame's paint.
     */
    private val paintLock = java.util.concurrent.locks.ReentrantLock(true)

    /**
     * Waits for the paint in flight, then runs [block]. Bounded, because the view calls this under
     * AWT's tree lock, which a paint that builds a BufferStrategy may want.
     */
    private inline fun <T> fenced(block: () -> T): T {
        val held = paintLock.tryLock(CANVAS_FENCE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            if (held) paintLock.unlock()
        }
    }

    /**
     * Repaints the retained picture when the canvas changes size.
     *
     * Without it a resize while PAUSED leaves the old geometry on screen until something else
     * happens to repaint: no frame is arriving to trigger one. Attached in [setCanvas] and removed
     * there and in [close], because a listener left on a canvas the view has thrown away keeps
     * this renderer alive with it.
     */
    private val resizeListener = object : java.awt.event.ComponentAdapter() {
        override fun componentResized(event: java.awt.event.ComponentEvent) {
            repaintRetained()
        }
    }

    /**
     * The last frame converted, kept so an overlay or control change can redraw without a frame.
     * Null before the first frame and after [clearPicture], when a repaint draws the background and
     * the cues alone.
     */
    private var lastImage: BufferedImage? = null
    private var lastSize: VideoSize? = null
    private var lastRotation: Int = 0
    private var lastMirrored: Boolean = false
    /** The flash guard's factor for [lastImage], so a repaint draws it as it was drawn. */
    private var lastDim: Float = 1f
    /** The full raster behind [lastImage] and where the shown part starts in it, for the view into a sphere (#587). */
    private var lastRaster: IntArray? = null
    private var lastStride: Int = 0
    private var lastLeft: Int = 0
    private var lastTop: Int = 0

    /** The projection whose view is drawn, or null while the picture draws flat. */
    private var sphere: VideoProjection? = null
    private var sphereView: SphericalView = SphericalView()

    /** The view's map and its image, used only inside a paint. */
    private val sphereRemap = SphereViewRemap()
    private var viewImage: BufferedImage? = null

    /** When the last frame was presented, and when a paint last read the view, on [System.nanoTime]. */
    private val lastPresentNanos = AtomicLong(Long.MIN_VALUE / 2)
    private val viewReadNanos = AtomicLong(Long.MIN_VALUE / 2)
    private val viewAskedNanos = AtomicLong(0)
    private val viewRepaintPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The thread a turned view is redrawn on while no frame comes, made at the first turn. */
    private var viewRepaints: java.util.concurrent.ScheduledExecutorService? = null

    private var overlay: SubtitleOverlay? = null
    private var decoration: AwtCanvasDecoration? = null
    private var scaleMode: VideoScale = VideoScale.Fit
    private var transform: VideoTransform = VideoTransform.Identity

    private val flashGuard = java.util.concurrent.atomic.AtomicReference(FlashGuard.FollowSystem)

    /** Set when the picture is taken off or the mode changes, so the next picture starts the guard afresh. */
    private val guardForgets = java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(KitePlayerLowLevelApi::class)
    private val guard = VideoFlashGuard()
    @OptIn(KitePlayerLowLevelApi::class)
    private val guardCells = FloatArray(VideoFlashGuard.MEASURES)

    /**
     * The flash guard's mode (#500). This renderer measures each picture it paints and dims it while
     * a flashing run lasts. It cannot read a system setting, so [FlashGuard.FollowSystem] is off here.
     */
    override fun setFlashGuard(mode: FlashGuard) {
        // A change of mode starts the history afresh, as taking the picture off does.
        if (flashGuard.getAndSet(mode) != mode) guardForgets.set(true)
    }

    /** The flash guard's factor for [pixels], measured on a sparse lattice of them: 1 when the guard is off. */
    @OptIn(KitePlayerLowLevelApi::class)
    private fun dimFor(pixels: IntArray, width: Int, height: Int): Float {
        if (flashGuard.get() != FlashGuard.On) return 1f
        return synchronized(guard) {
            if (guardForgets.getAndSet(false)) guard.reset()
            VideoFlashGuard.cellsFromPackedRgb(pixels, width, height, into = guardCells)
            guard.factorFor(guardCells, guardNanos())
        }
    }

    /** Says that this painter rolled HDR off to SDR while painting. */
    private fun announceToneMap(frame: VideoFrame) {
        if (painter.toneMapped(frame)) hdrAnnouncer.announce(frame.colorSpace.transfer.name)
    }

    override fun supports(format: PlayerPixelFormat): Boolean = true

    /** No hardware surface: the painter converts every frame on the CPU, a hardware frame through its downloaded copy. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun vsyncIntervalNanos(): Long? = try {
        // The default screen's mode; 0 and REFRESH_RATE_UNKNOWN both mean "no answer". Headless
        // JVMs (CI) throw, which is the same honest null.
        val rate = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.displayMode.refreshRate
        if (rate > 0) 1_000_000_000L / rate else null
    } catch (unavailable: Throwable) {
        null
    }

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    /**
     * The canvas in device pixels: its size times the scale of the screen it is on. The engine lays
     * subtitles out for this size, rule 2 of docs/subtitle-placement.md, and the presenter draws
     * them back at one overlay pixel to one device pixel. Null before a canvas with a size exists.
     */
    override val outputSize: VideoSize?
        get() {
            val target = synchronized(lock) { if (closed) null else canvas } ?: return null
            val width = target.width
            val height = target.height
            if (width <= 0 || height <= 0) return null
            val transform = target.graphicsConfiguration?.defaultTransform
            val scaleX = transform?.scaleX?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val scaleY = transform?.scaleY?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            return VideoSize((width * scaleX).roundToInt(), (height * scaleY).roundToInt())
        }

    /** The canvas paints the decoded picture as it is, so the engine warns when picture controls are set (#610). */
    override val appliesAdjustments: Boolean get() = false

    override fun setScaleMode(mode: VideoScale) {
        synchronized(lock) { scaleMode = mode }
        repaintRetained()
    }

    override fun setTransform(transform: VideoTransform) {
        val reshaped = synchronized(lock) {
            val before = this.transform.orient(lastRotation, lastMirrored).rotationDegrees
            this.transform = transform
            val after = transform.orient(lastRotation, lastMirrored).rotationDegrees
            lastSize?.takeIf { before != after }?.let { it to after }
        }
        // A turn changed while a picture is held reshapes the view at once, not with the next frame (#428).
        reshaped?.let { (size, turn) -> onVideoGeometry(size, turn) }
        repaintRetained()
    }

    /** The whole sphere and a tile of it, which this renderer draws a view into on the processor (#587). */
    @OptIn(KitePlayerInternalApi::class)
    override fun projects(kind: ProjectionKind): Boolean = SphericalLens.draws(kind)

    /**
     * Draws the view into a 360 degree picture while [view] is projected, over the whole canvas, as
     * the Compose canvas path draws it: the scale mode, the framing and the picture's turn do not
     * apply, because the view's field of view is its zoom. A change redraws the retained picture at
     * once, so a paused player turns with the viewer.
     */
    @OptIn(KitePlayerInternalApi::class)
    override fun setProjection(projection: VideoProjection?, view: SphericalView) {
        val repaints = synchronized(lock) {
            if (closed) return
            val drawn = projection?.takeIf { view.projected && SphericalLens.draws(it.kind) }
            val changed = drawn != sphere || (drawn != null && view != sphereView)
            sphere = drawn
            sphereView = view
            if (!changed) return
            viewRepaints ?: java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "kiteplayer-awt-sphere-view").apply { isDaemon = true }
            }.also { viewRepaints = it }
        }
        scheduleViewRepaint(repaints)
    }

    /**
     * Redraws the retained picture with the view as it now is, off the caller's thread, because a
     * turn builds the view's map again and the engine tells this renderer on its actor. While
     * frames arrive the next one draws the new view, so the redraw waits a moment and is dropped
     * when a paint read the view after it was asked for. Turns asked for while one waits share it.
     */
    private fun scheduleViewRepaint(repaints: java.util.concurrent.ScheduledExecutorService) {
        val asked = System.nanoTime()
        viewAskedNanos.set(asked)
        if (!viewRepaintPending.compareAndSet(false, true)) return
        val playing = asked - lastPresentNanos.get() < playingWindowNanos
        try {
            repaints.schedule(
                {
                    viewRepaintPending.set(false)
                    if (viewReadNanos.get() < viewAskedNanos.get()) repaintRetained()
                },
                if (playing) playingWindowNanos else 0L,
                java.util.concurrent.TimeUnit.NANOSECONDS,
            )
        } catch (closedMeanwhile: java.util.concurrent.RejectedExecutionException) {
            viewRepaintPending.set(false)
        }
    }

    /**
     * The canvas to paint into, or null to fence all painting off the previous one.
     *
     * The view calls this with null while AWT is about to destroy the peer, so the call waits for the
     * paint in flight before it lets the previous canvas go. The wait is bounded at 500 ms (#691): the
     * view calls this under AWT's tree lock, which a paint that builds a `BufferStrategy` may want, so
     * waiting for ever could deadlock the event thread (#225). Every paint that starts after this
     * returns sees the new canvas, or none. A paint that was already running and outlives the wait,
     * which takes a stall such as a long garbage collection, keeps the previous canvas it read and
     * can still finish drawing into its `BufferStrategy` while the peer goes, where AWT drops the
     * picture or the paint fails. A picture that never reached the screen this way is lost, and
     * the next frame paints into the new canvas.
     */
    public fun setCanvas(canvas: Canvas?) {
        val previous = fenced {
            synchronized(lock) {
                val old = this.canvas
                this.canvas = canvas
                old
            }
        }
        if (previous !== canvas) {
            previous?.removeComponentListener(resizeListener)
            canvas?.addComponentListener(resizeListener)
        }
    }

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        val target = synchronized(lock) { if (closed) null else canvas }
        if (target == null) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val width = frame.size.width
        val height = frame.size.height
        if (width <= 0 || height <= 0) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val raster = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val painted = try {
            painter.paintArgb(frame, raster, width, height)
        } catch (t: Throwable) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        val crop = frame.crop?.takeIf { !it.isEmpty && it.fits(width, height) }
        // The crop comes off first and shares the pixels, so everything after it, the fit, the
        // turn, the overlay and the geometry the view hears, sees only what is left (#497).
        val shown = if (crop == null) {
            image
        } else {
            image.getSubimage(crop.left, crop.top, width - crop.left - crop.right, height - crop.top - crop.bottom)
        }
        val size = frame.size.cropped(crop)
        val rotation = frame.rotationDegrees
        val mirrored = frame.mirrored
        val pts = frame.pts
        if (painted) announceToneMap(frame)
        frame.close()
        if (!painted) {
            failed.incrementAndGet()
            return false
        }
        // Measured before it is shown, so the picture that completes a run is already dimmed.
        val dim = dimFor(raster, width, height)
        val shownTurn = synchronized(lock) {
            if (closed) {
                failed.incrementAndGet()
                return false
            }
            lastImage = shown
            lastRaster = raster
            lastStride = width
            lastLeft = crop?.left ?: 0
            lastTop = crop?.top ?: 0
            lastSize = size
            lastRotation = rotation
            lastMirrored = mirrored
            lastDim = dim
            transform.orient(rotation, mirrored).rotationDegrees
        }
        lastPresentNanos.set(System.nanoTime())
        // The view shapes itself for the picture as the viewer turned it (#428).
        onVideoGeometry(size, shownTurn)
        // Counted and reported only once the strategy showed it. A canvas with no peer or no size
        // draws nothing, and the picture stays retained for the next repaint (#290).
        if (!paintNow()) {
            failed.incrementAndGet()
            return false
        }
        presented.incrementAndGet()
        // Best effort by design: the AWT blit returned, which is the closest this path can
        // observe to pixels on glass.
        eventFlow.tryEmit(RendererEvent.FramePresented(pts, atNanos = System.nanoTime(), exact = false))
        return true
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        synchronized(lock) {
            if (closed) return
            this.overlay = overlay
        }
        repaintRetained()
    }

    /**
     * What to draw over every frame, or null for nothing. The retained picture is painted again at
     * once, so a paused player shows the change.
     */
    public fun setDecoration(decoration: AwtCanvasDecoration?) {
        synchronized(lock) {
            if (closed) return
            this.decoration = decoration
        }
        repaintRetained()
    }

    /** Paints the retained picture again, for a decoration that has something new to draw. */
    public fun repaint() {
        repaintRetained()
    }

    /**
     * Forgets the retained picture and paints the background with the cues over it (#530). There is
     * no queue here, so the retained picture is the only frame there is to forget.
     */
    override fun clearPicture() {
        synchronized(lock) {
            if (closed) return
            lastImage = null
            lastRaster = null
            lastSize = null
        }
        guardForgets.set(true)
        paintNow()
    }

    /**
     * Redraws what is already on screen, the picture or, with none, the background.
     *
     * A paused player still changes what should be visible: a subtitle cue arrives or leaves, the
     * scale mode changes, the picture controls move, and a sound with no picture can carry cues
     * too. Without this the screen would keep the old composition until the next frame, which for
     * a paused player is forever.
     */
    private fun repaintRetained() {
        val open = synchronized(lock) { !closed }
        if (open) paintNow()
    }

    /** Paints the retained picture, or the background with none, and answers true only when it reached the screen. */
    private fun paintNow(): Boolean {
        paintLock.lock()
        try {
            return paintHeld()
        } finally {
            paintLock.unlock()
        }
    }

    /** Only with [paintLock] held. */
    private fun paintHeld(): Boolean {
        val target: Canvas
        val image: BufferedImage?
        val size: VideoSize?
        val rotation: Int
        val mirrored: Boolean
        val mode: VideoScale
        val currentTransform: VideoTransform
        val dim: Float
        val over: AwtCanvasDecoration?
        val raster: IntArray?
        val stride: Int
        val left: Int
        val top: Int
        val projection: VideoProjection?
        val view: SphericalView
        synchronized(lock) {
            if (closed) return false
            target = canvas ?: return false
            image = lastImage
            raster = lastRaster
            stride = lastStride
            left = lastLeft
            top = lastTop
            projection = sphere
            view = sphereView
            viewReadNanos.set(System.nanoTime())
            size = lastSize
            rotation = lastRotation
            mirrored = lastMirrored
            mode = scaleMode
            currentTransform = transform
            dim = lastDim
            over = decoration
        }
        if (!target.isDisplayable) return false
        if (image == null || size == null) return presenter.present(target, null, null, overlaySnapshot(), 1f, over)
        if (projection != null && raster != null) {
            return paintSphereView(target, raster, stride, left, top, size, projection, view, dim, over)
        }
        val layout = frameLayout(
            canvasWidth = target.width,
            canvasHeight = target.height,
            size = size,
            rotationDegrees = rotation,
            mode = mode,
            transform = currentTransform,
            mirrored = mirrored,
        ) ?: return false
        return presenter.present(target, image, layout, overlaySnapshot(), dim, over)
    }

    private fun overlaySnapshot(): SubtitleOverlay? = synchronized(lock) { overlay }

    /**
     * Draws the view into the retained picture and hands it to the presenter as a picture that
     * fills the canvas. It is drawn at the canvas's size, or at the picture's own detail when that
     * is less, and the presenter's bilinear scale fills the rest (see [SphereViewRemap.viewSize]).
     * Only with [paintLock] held, which is what keeps [sphereRemap] and [viewImage] to one paint at
     * a time.
     */
    private fun paintSphereView(
        target: Canvas,
        raster: IntArray,
        stride: Int,
        left: Int,
        top: Int,
        size: VideoSize,
        projection: VideoProjection,
        view: SphericalView,
        dim: Float,
        over: AwtCanvasDecoration?,
    ): Boolean {
        val canvasWidth = target.width
        val canvasHeight = target.height
        if (canvasWidth <= 0 || canvasHeight <= 0) return false
        val (width, height) = SphereViewRemap.viewSize(canvasWidth, canvasHeight, size.width, projection, view)
        val drawn = viewImage?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { viewImage = it }
        val pixels = (drawn.raster.dataBuffer as java.awt.image.DataBufferInt).data
        sphereRemap.draw(raster, stride, left, top, size.width, size.height, projection, view, pixels, width, height)
        val layout = frameLayout(canvasWidth, canvasHeight, VideoSize(width, height), rotationDegrees = 0, mode = VideoScale.Stretch)
            ?: return false
        return presenter.present(target, drawn, layout, overlaySnapshot(), dim, over)
    }

    /** Lets the canvas go and stops painting, after the paint in flight, waited for as [setCanvas] does. */
    override fun close() {
        fenced { closeFenced() }
    }

    private fun closeFenced() {
        val released = synchronized(lock) {
            if (closed) return
            closed = true
            viewRepaints?.shutdownNow()
            viewRepaints = null
            val old = canvas
            canvas = null
            lastImage = null
            lastRaster = null
            viewImage = null
            lastSize = null
            overlay = null
            decoration = null
            old
        }
        released?.removeComponentListener(resizeListener)
    }
}

/** How long [AwtCanvasVideoRenderer.setCanvas] and its close wait for a paint in flight. */
private const val CANVAS_FENCE_TIMEOUT_MS = 500L

/** A frame within this long means the picture plays, and its next frame will draw a turned view. */
private const val PLAYING_WINDOW_NANOS = 100_000_000L
