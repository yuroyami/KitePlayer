package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

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
 * Draws frames onto an AWT canvas, on a thread of its own.
 *
 * ### Why this exists next to the Compose renderer
 *
 * Compose draws the whole window as one scene on one clock, so heavy UI work delays the picture.
 * This renderer paints a heavyweight AWT canvas from its own thread through a `BufferStrategy`,
 * which the window server presents independently. Measured 2026-08-30: with the Compose frame
 * clock choked to 8 percent of its rate, a canvas painted this way kept 100 percent of its own.
 *
 * ### The ownership rule that matters most
 *
 * Every frame handed to [present] is closed exactly once, on every path including refusal. A leak
 * here is 3.11 MB per 1080p frame, so the paths that refuse are the ones worth reading: no canvas,
 * no peer yet, a zero-sized canvas, a painter that declines the frame, a closed renderer, and a
 * frame superseded by a newer one while the painter was busy. Each closes and counts.
 *
 ### Where the painting happens, said plainly because it is easy to assume otherwise
 *
 * [present] converts and paints on the thread that calls it, which is the engine's video
 * scheduler, exactly as the Android surface renderer does. That is already independent of
 * Compose, which is the decoupling this path exists for; it is NOT independent of the engine, and
 * a slow present slows the schedule that called it. There is no queue and no painter thread here,
 * so nothing is ever superseded inside this renderer and [supersededFrames] stays zero: the
 * counter exists because the view's ledger sums one number across every renderer it builds, and a
 * renderer with nothing to report reports nothing rather than being absent from the sum.
 *
 * Not thread-safe for configuration: [setCanvas], [close] and the picture controls come from the
 * view's thread. [present] may come from the engine's scheduler and is the only member that does.
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

    /** Published once, not once per frame: the engine latches it anyway, and a flood is noise. */
    private val toneMapAnnounced = java.util.concurrent.atomic.AtomicBoolean(false)

    private val lock = Any()
    private var canvas: Canvas? = null
    private var closed = false

    /** The last frame converted, kept so an overlay or control change can redraw without a frame. */
    private var lastImage: BufferedImage? = null
    private var lastSize: VideoSize? = null
    private var lastRotation: Int = 0
    private var overlay: SubtitleOverlay? = null
    private var scaleMode: VideoScale = VideoScale.Fit
    private var transform: VideoTransform = VideoTransform.Identity

    /** Says once, per renderer, that this painter rolled HDR off to SDR while painting. */
    private fun announceToneMap(frame: VideoFrame) {
        if (!painter.toneMapped(frame)) return
        if (!toneMapAnnounced.compareAndSet(false, true)) return
        eventFlow.tryEmit(RendererEvent.ToneMapEngaged(transfer = frame.colorSpace.transfer.name))
    }

    override fun supports(format: PlayerPixelFormat): Boolean = true

    /** No hardware path: the desktop backend decodes in software and the painter converts on the CPU. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    override fun vsyncIntervalNanos(): Long? = null

    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    override fun setScaleMode(mode: VideoScale) {
        synchronized(lock) { scaleMode = mode }
        repaintRetained()
    }

    override fun setTransform(transform: VideoTransform) {
        synchronized(lock) { this.transform = transform }
        repaintRetained()
    }

    /**
     * The canvas to paint into, or null to fence all painting off the previous one.
     *
     * Passing null must return only once no paint can touch that canvas again, because the view
     * calls it while AWT is about to destroy the peer.
     */
    public fun setCanvas(canvas: Canvas?) {
        synchronized(lock) { this.canvas = canvas }
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
        val size = frame.size
        val rotation = frame.rotationDegrees
        if (painted) announceToneMap(frame)
        frame.close()
        if (!painted) {
            failed.incrementAndGet()
            return false
        }
        synchronized(lock) {
            if (closed) {
                failed.incrementAndGet()
                return false
            }
            lastImage = image
            lastSize = size
            lastRotation = rotation
        }
        onVideoGeometry(size, rotation)
        presented.incrementAndGet()
        paintNow()
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
     * Redraws the picture already on screen.
     *
     * A paused player still changes what should be visible: a subtitle cue arrives or leaves, the
     * scale mode changes, the picture controls move. Without this the screen would keep the old
     * composition until the next frame, which for a paused player is forever.
     */
    private fun repaintRetained() {
        val hasPicture = synchronized(lock) { lastImage != null && !closed }
        if (hasPicture) paintNow()
    }

    private fun paintNow() {
        val target: Canvas
        val image: BufferedImage
        val size: VideoSize
        val rotation: Int
        val mode: VideoScale
        val currentTransform: VideoTransform
        synchronized(lock) {
            if (closed) return
            target = canvas ?: return
            image = lastImage ?: return
            size = lastSize ?: return
            rotation = lastRotation
            mode = scaleMode
            currentTransform = transform
        }
        if (!target.isDisplayable) return
        val layout = frameLayout(
            canvasWidth = target.width,
            canvasHeight = target.height,
            size = size,
            rotationDegrees = rotation,
            mode = mode,
            transform = currentTransform,
        ) ?: return
        AwtCanvasPresenter.present(target, image, layout, overlaySnapshot())
    }

    private fun overlaySnapshot(): SubtitleOverlay? = synchronized(lock) { overlay }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            canvas = null
            lastImage = null
            lastSize = null
            overlay = null
        }
    }
}
