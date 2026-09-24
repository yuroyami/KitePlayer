package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.GlitchScene.Companion.BARS
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The two pictures of [Glitch] made of pixels rather than shapes: the frozen frame of a datamosh and
 * the static of a channel change.
 *
 * A datamosh keeps the last clean frame at half the screen's resolution and moves it down in columns
 * of 16 screen pixels, each at the speed its band sets. What each column leaves behind at the top is
 * the new picture, a few rows a frame, so the old frame smears into the new one. It is drawn with no
 * filtering, which keeps the hard pixel edges of damaged video. Two bitmaps are kept and swapped, and
 * nothing is allocated while no datamosh runs.
 */
internal class GlitchFields {

    private var shown: ImageBitmap? = null
    private var spare: ImageBitmap? = null
    private var shownCanvas: Canvas? = null
    private var spareCanvas: Canvas? = null
    private val scope = CanvasDrawScope()
    private var block = 1
    private var columns = 0
    private val band = IntArray(MOST_COLUMNS)
    private val carried = FloatArray(MOST_COLUMNS)
    private val moved = IntArray(MOST_COLUMNS)
    private var tile: PixelImage? = null
    private var noise: ShaderBrush? = null

    /** Freezes [picture], the frame last shown, as the first frame of a datamosh. */
    fun DrawScope.freeze(picture: TriangleMesh, scene: GlitchScene) {
        val width = (size.width * SCALE).toInt().coerceAtLeast(1)
        val height = (size.height * SCALE).toInt().coerceAtLeast(1)
        prepare(width, height)
        val canvas = shownCanvas ?: return
        scope.draw(this, layoutDirection, canvas, Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.Black)
            scale(SCALE, SCALE, Offset.Zero) { drawMesh(picture) }
        }
        block = (BLOCK_PIXELS * SCALE).roundToInt().coerceAtLeast(1)
        columns = min((width + block - 1) / block, MOST_COLUMNS)
        // Each column moves at the speed of the band whose slot it stood in when the frame froze.
        var slot = 0
        for (column in 0 until columns) {
            val at = (column + 0.5f) * block / width
            while (slot < BARS - 1 && scene.edges[slot + 1] <= at) slot++
            band[column] = slot
            carried[column] = 0f
        }
    }

    /**
     * Moves the frozen frame on by [seconds] of audible time: every column slides down by its band's
     * speed, and the rows it uncovers take [picture], the new frame.
     */
    fun DrawScope.slide(picture: TriangleMesh, scene: GlitchScene, seconds: Float) {
        val from = shown ?: return
        val into = spare ?: return
        val canvas = spareCanvas ?: return
        if (seconds <= 0f) return
        val width = from.width
        val height = from.height
        for (column in 0 until columns) {
            val speed = (SLOWEST + FASTEST * GlitchScene.response(scene.glow[band[column]])) / scene.cycleSeconds
            carried[column] += speed * scene.moshMotion * seconds * height
            val whole = floor(carried[column]).toInt().coerceIn(0, height)
            moved[column] = whole
            carried[column] -= whole
        }
        scope.draw(this, layoutDirection, canvas, Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.Black)
            scale(SCALE, SCALE, Offset.Zero) { drawMesh(picture) }
            for (column in 0 until columns) {
                val left = column * block
                val wide = min(block, width - left)
                val down = moved[column]
                if (wide <= 0 || down >= height) continue
                drawImage(
                    image = from,
                    srcOffset = IntOffset(left, 0),
                    srcSize = IntSize(wide, height - down),
                    dstOffset = IntOffset(left, down),
                    dstSize = IntSize(wide, height - down),
                    blendMode = BlendMode.Src,
                    filterQuality = FilterQuality.None,
                )
            }
        }
        spare = from
        spareCanvas = shownCanvas
        shown = into
        shownCanvas = canvas
    }

    /**
     * Draws the datamosh over the whole canvas with its colour layers [split] pixels apart. Under
     * reduced motion its blocks barely move, so it greys and dims instead, the moment as a change of
     * colour and light.
     */
    fun DrawScope.drawMosh(split: Float, reduced: Boolean) {
        val image = shown ?: return
        val whole = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
        if (split < 1f) {
            drawImage(image, dstSize = whole, colorFilter = if (reduced) GREYED else null,
                filterQuality = FilterQuality.None)
            return
        }
        for (channel in CHANNELS.indices) {
            translate((channel - 1) * split, 0f) {
                drawImage(image, dstSize = whole, colorFilter = CHANNELS[channel], blendMode = BlendMode.Plus,
                    filterQuality = FilterQuality.None)
            }
        }
    }

    /** Fine static at 40 percent light at most, one noise pixel per screen pixel, placed anew each frame. */
    fun DrawScope.drawStatic(scene: GlitchScene) {
        val brush = noise ?: ShaderBrush(ImageShader(noiseTile().image, TileMode.Repeated, TileMode.Repeated))
            .also { noise = it }
        val span = TILE.toFloat()
        translate(-floor(scene.staticX * span), -floor(scene.staticY * span)) {
            drawRect(brush, Offset.Zero, Size(size.width + span, size.height + span))
        }
    }

    /** Lets the bitmaps go. The next datamosh makes new ones. */
    fun reset() {
        shown = null
        spare = null
        shownCanvas = null
        spareCanvas = null
        columns = 0
    }

    private fun prepare(width: Int, height: Int) {
        val current = shown
        if (current != null && current.width == width && current.height == height && spare != null) return
        shown = ImageBitmap(width, height).also { shownCanvas = Canvas(it) }
        spare = ImageBitmap(width, height).also { spareCanvas = Canvas(it) }
    }

    private fun noiseTile(): PixelImage = tile ?: PixelImage(TILE, TILE).also { image ->
        val random = Rng(NOISE_SEED)
        for (index in image.pixels.indices) {
            val grey = (random.next() * STATIC_LIGHT * 255f).roundToInt().coerceIn(0, 255)
            image.pixels[index] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
        image.upload()
        tile = image
    }

    private companion object {
        /** The datamosh runs at half the screen's resolution, drawn back without filtering. */
        const val SCALE = 0.5f

        /** One block of a datamosh, in screen pixels, the size of a video codec's macroblock. */
        const val BLOCK_PIXELS = 16f
        const val MOST_COLUMNS = 512

        /** Screen heights a column moves in one cycle: a quiet band's, and what a loud one adds. */
        const val SLOWEST = 0.12f
        const val FASTEST = 1.1f

        const val TILE = 128
        const val STATIC_LIGHT = 0.4f
        const val NOISE_SEED = 90_211L

        val CHANNELS = arrayOf(
            channel(1f, 0f, 0f),
            channel(0f, 1f, 0f),
            channel(0f, 0f, 1f),
        )

        /** A quarter of the colour and seven tenths of the light. */
        val GREYED: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply {
            setToSaturation(0.25f)
            for (row in 0..2) for (column in 0..2) this[row, column] = this[row, column] * 0.7f
        })

        fun channel(red: Float, green: Float, blue: Float): ColorFilter = ColorFilter.colorMatrix(
            ColorMatrix(floatArrayOf(
                red, 0f, 0f, 0f, 0f,
                0f, green, 0f, 0f, 0f,
                0f, 0f, blue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )),
        )
    }
}
