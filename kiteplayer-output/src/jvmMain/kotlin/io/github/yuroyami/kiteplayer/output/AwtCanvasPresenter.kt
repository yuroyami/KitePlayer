package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * The one place a composed frame reaches an AWT canvas.
 *
 * Split out from the renderer so the drawing rules can be read and changed without the ownership
 * and counting rules around them, and so a test can compose into a plain image instead of a
 * canvas that would need a window.
 *
 * `BufferStrategy` rather than `Graphics` from `paint`: the point of this whole path is that the
 * picture is presented by a thread of its own rather than by whatever else is drawing the UI, and
 * the strategy's own loop is what makes a present safe against a buffer being restored or lost
 * underneath it.
 */
internal interface CanvasPresenter {
    /** Draws and shows one composed frame, and answers true only when the strategy showed it. */
    fun present(canvas: Canvas, image: BufferedImage, layout: FrameLayout, overlay: SubtitleOverlay?): Boolean
}

internal class AwtCanvasPresenter : CanvasPresenter {

    /** The canvas size the current BufferStrategy was built for. See [strategyIsStale]. */
    private var builtWidth: Int = 0
    private var builtHeight: Int = 0

    override fun present(
        canvas: Canvas,
        image: BufferedImage,
        layout: FrameLayout,
        overlay: SubtitleOverlay?,
    ): Boolean {
        if (canvas.width <= 0 || canvas.height <= 0) return false
        if (strategyIsStale(canvas.bufferStrategy != null, builtWidth, builtHeight, canvas.width, canvas.height)) {
            // Creating a strategy needs a peer, and the caller has already checked for one; a
            // race with the peer going away still throws, and losing a frame to that is correct.
            runCatching { canvas.createBufferStrategy(2) }
            builtWidth = canvas.width
            builtHeight = canvas.height
        }
        val strategy = canvas.bufferStrategy ?: return false
        var shown: Boolean
        do {
            do {
                val g = strategy.drawGraphics as? Graphics2D ?: return false
                try {
                    compose(g, canvas.width, canvas.height, image, layout, overlay)
                } finally {
                    g.dispose()
                }
            } while (strategy.contentsRestored())
            // A show that throws reached no screen, and the caller must not report it (#290).
            shown = runCatching { strategy.show() }.isSuccess
        } while (strategy.contentsLost())
        return shown
    }

    companion object {

    /**
     * Whether the canvas needs a fresh BufferStrategy before this paint.
     *
     * A strategy owns real buffers of a fixed size. Resizing the canvas does not resize them, so
     * a strategy built for the old size draws the new frame into the old buffers: the picture is
     * clipped or stretched and stays that way until something else rebuilds it. AWT does not
     * report that as an error, which is why this is a size comparison rather than a check of the
     * strategy's own state.
     */
    fun strategyIsStale(
        hasStrategy: Boolean,
        builtWidth: Int,
        builtHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Boolean = !hasStrategy || builtWidth != canvasWidth || builtHeight != canvasHeight

    /**
     * Draws one composed frame: the letterbox, the picture, then the cues on top.
     *
     * Visible for testing, and tested against a plain image rather than a canvas, because the
     * geometry and the overlay placement are the parts worth pinning and neither needs a window.
     */
    fun compose(
        g: Graphics2D,
        canvasWidth: Int,
        canvasHeight: Int,
        image: BufferedImage,
        layout: FrameLayout,
        overlay: SubtitleOverlay?,
    ) {
        // The letterbox is painted every time rather than only when the geometry changes: a
        // narrower frame after a wider one would otherwise leave the old picture's edges on screen.
        g.color = Color.BLACK
        g.fillRect(0, 0, canvasWidth, canvasHeight)
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, layout.left, layout.top, layout.width, layout.height, null)
        drawOverlay(g, overlay, canvasWidth, canvasHeight)
    }

    /**
     * Cues cover the whole canvas, rule 1 of docs/subtitle-placement.md: the overlay's viewport
     * maps onto the canvas, each axis on its own, whatever the picture's fit.
     *
     * The engine lays the overlay out for the canvas in device pixels, which is what
     * [AwtCanvasVideoRenderer.outputSize] reports, so the scale here undoes the screen's own scale
     * and each overlay pixel lands on one device pixel. An overlay laid out for an older size is
     * stretched until the engine lays it out again.
     */
    private fun drawOverlay(g: Graphics2D, overlay: SubtitleOverlay?, canvasWidth: Int, canvasHeight: Int) {
        val active = overlay ?: return
        if (active.viewportWidth <= 0 || active.viewportHeight <= 0) return
        val scaleX = canvasWidth.toDouble() / active.viewportWidth
        val scaleY = canvasHeight.toDouble() / active.viewportHeight
        for (cue in active.images) {
            val bitmap = cue.bitmap
            if (bitmap.width <= 0 || bitmap.height <= 0) continue
            // The cue contract is PREMULTIPLIED rgba bytes; TYPE_INT_ARGB_PRE is the matching
            // AWT model, so the bytes are reinterpreted rather than converted a second time.
            val premultiplied = BufferedImage(
                bitmap.width,
                bitmap.height,
                BufferedImage.TYPE_INT_ARGB_PRE,
            )
            val target = (premultiplied.raster.dataBuffer as java.awt.image.DataBufferInt).data
            val source = bitmap.pixels
            var i = 0
            while (i < target.size && (i * 4 + 3) < source.size) {
                val r = source[i * 4].toInt() and 0xFF
                val gg = source[i * 4 + 1].toInt() and 0xFF
                val b = source[i * 4 + 2].toInt() and 0xFF
                val a = source[i * 4 + 3].toInt() and 0xFF
                target[i] = (a shl 24) or (r shl 16) or (gg shl 8) or b
                i++
            }
            val left = (cue.x * scaleX).roundToInt()
            val top = (cue.y * scaleY).roundToInt()
            val right = ((cue.x + bitmap.width) * scaleX).roundToInt()
            val bottom = ((cue.y + bitmap.height) * scaleY).roundToInt()
            g.drawImage(premultiplied, left, top, right - left, bottom - top, null)
        }
    }
    }
}
