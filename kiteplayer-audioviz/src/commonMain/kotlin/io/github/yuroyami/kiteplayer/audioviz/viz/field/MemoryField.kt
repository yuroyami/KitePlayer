package io.github.yuroyami.kiteplayer.audioviz.viz.field

import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small grid of ink that a flow carries, fades, and that the signal is written into every frame.
 *
 * This is the memory of a drawing: the last seconds of sound as shape, transported through space.
 * Each cell keeps how much ink it holds and how old that ink is. A frame advects the grid along a
 * [Flow] with one bilinear read per cell, as Marble's tray does, fades it by a half life, and then
 * the drawing writes lines, discs and rings into it. The grid goes to the shader as one picture, ink
 * in red and age in green, and the shader reads it sharp with `fieldAt`, `fieldSlope` and `isoLine`.
 *
 * Positions are centred units: 0,0 is the middle and one unit is half the screen height. y points
 * down the screen, as `centred()` in the shader header does: y = -1 is the top edge and the first
 * row of the picture. The grid covers the whole screen, [rows] cells tall and as many columns as the
 * aspect ratio asks for, so a cell is square on any screen.
 */
internal class MemoryField(val rows: Int = 108, private val withExtra: Boolean = false) {
    var columns: Int = (rows * 16 / 9)
        private set
    var aspect: Float = 16f / 9f
        private set

    /** Seconds for the ink to fade to half. */
    var halfLife: Float = 1f

    private var ink = FloatArray(columns * rows)
    private var age = FloatArray(columns * rows)
    private var spareInk = FloatArray(columns * rows)
    private var spareAge = FloatArray(columns * rows)

    /**
     * A third channel carried with the ink and faded with it, or null. Contour keeps its lace here.
     * It goes to the shader in the blue channel.
     */
    var extra: FloatArray? = if (withExtra) FloatArray(columns * rows) else null
        private set
    private var spareExtra: FloatArray? = if (withExtra) FloatArray(columns * rows) else null
    private var texture = PixelImage(columns, rows)
    private val flowOut = FloatArray(2)
    private var dirty = true

    /** Sets the grid for a screen of this width over height. A change clears the grid. */
    fun size(aspect: Float) {
        val wanted = (rows * aspect).roundToInt().coerceIn(32, 256)
        if (wanted == columns && aspect == this.aspect) return
        this.aspect = aspect
        columns = wanted
        ink = FloatArray(columns * rows)
        age = FloatArray(columns * rows)
        spareInk = FloatArray(columns * rows)
        spareAge = FloatArray(columns * rows)
        if (withExtra) {
            extra = FloatArray(columns * rows)
            spareExtra = FloatArray(columns * rows)
        }
        texture = PixelImage(columns, rows)
        dirty = true
    }

    /** Carries the ink, its age and the extra channel along [flow] for [heardSeconds], and fades them. Nothing happens for zero. */
    fun advance(flow: Flow, heardSeconds: Float) {
        if (heardSeconds <= 0f) return
        val keep = 2f.pow(-heardSeconds / max(halfLife, 0.01f))
        val cellsPerUnit = rows * 0.5f
        val extraNow = extra
        val extraNext = spareExtra
        for (row in 0 until rows) {
            val y = (row + 0.5f) / cellsPerUnit - 1f
            for (column in 0 until columns) {
                val x = (column + 0.5f) / cellsPerUnit - aspect
                flow.at(x, y, aspect, flowOut)
                // Where this cell's ink was a step ago, in cells, clamped so a wild flow cannot read nowhere.
                val backX = (column + 0.5f - flowOut[0] * heardSeconds * cellsPerUnit)
                val backY = (row + 0.5f - flowOut[1] * heardSeconds * cellsPerUnit)
                val cell = row * columns + column
                spareInk[cell] = sample(ink, backX, backY) * keep
                spareAge[cell] = sample(age, backX, backY) + heardSeconds
                if (extraNow != null && extraNext != null) extraNext[cell] = sample(extraNow, backX, backY) * keep
            }
        }
        swap()
        dirty = true
    }

    /** The spare set becomes the field, and the field becomes the spare set. */
    private fun swap() {
        val oldInk = ink
        ink = spareInk
        spareInk = oldInk
        val oldAge = age
        age = spareAge
        spareAge = oldAge
        val oldExtra = extra
        extra = spareExtra
        spareExtra = oldExtra
    }

    /** A polyline of [count] points, [width] cells wide, inked at [ink] with an age of zero. */
    fun line(xs: FloatArray, ys: FloatArray, count: Int, width: Float, ink: Float) {
        for (i in 0 until count - 1) {
            segment(xs[i], ys[i], xs[i + 1], ys[i + 1], width, ink)
        }
    }

    /** A filled disc of [radius] units. */
    fun disc(x: Float, y: Float, radius: Float, ink: Float) {
        splat(toCellX(x), toCellY(y), radius * rows * 0.5f, ink)
    }

    /** A ring of [radius] units and [width] cells, drawn as 96 short segments. */
    fun ring(x: Float, y: Float, radius: Float, width: Float, ink: Float) {
        var px = x + radius
        var py = y
        for (i in 1..96) {
            val a = i / 96f * 6.2831855f
            val nx = x + radius * kotlin.math.cos(a)
            val ny = y + radius * kotlin.math.sin(a)
            segment(px, py, nx, ny, width, ink)
            px = nx; py = ny
        }
    }

    /** The ink at a point, 0 to 1, read between cells. */
    fun inkAt(x: Float, y: Float): Float = sample(ink, toCellX(x), toCellY(y))

    /** The age of the ink at a point, in heard seconds. */
    fun ageAt(x: Float, y: Float): Float = sample(age, toCellX(x), toCellY(y))

    /** The extra channel at a point, or 0 for a field without one. */
    fun extraAt(x: Float, y: Float): Float {
        val values = extra ?: return 0f
        return sample(values, toCellX(x), toCellY(y))
    }

    /** Marks the field for upload after something other than the field wrote into it, such as a reaction. */
    fun markChanged() {
        dirty = true
    }

    /** The centred x of the middle of [column]. */
    fun xOf(column: Int): Float = (column + 0.5f) / (rows * 0.5f) - aspect

    /** The centred y of the middle of [row]; -1 is the top of the screen. */
    fun yOf(row: Int): Float = (row + 0.5f) / (rows * 0.5f) - 1f

    /** Uploads the grid when it changed and hands it to [program] as `uField` with `uFieldSize`. */
    fun bindTo(program: ShaderProgram) {
        if (dirty) {
            for (cell in ink.indices) {
                val r = (ink[cell].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = ((age[cell] / MOST_AGE).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = ((extra?.get(cell) ?: 0f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                texture.pixels[cell] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            texture.upload()
            dirty = false
        }
        program.child("uField", texture.image)
        program.uniform("uFieldSize", columns.toFloat(), rows.toFloat())
    }

    /** Empties the grid. */
    fun clear() {
        ink.fill(0f)
        age.fill(0f)
        extra?.fill(0f)
        dirty = true
    }

    private fun toCellX(x: Float): Float = (x + aspect) * rows * 0.5f
    private fun toCellY(y: Float): Float = (y + 1f) * rows * 0.5f

    /** Walks a segment in half cell steps and splats a disc at each step, so a line has no gaps. */
    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, ink: Float) {
        val ax = toCellX(x0); val ay = toCellY(y0)
        val bx = toCellX(x1); val by = toCellY(y1)
        val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
        val steps = max(1, (length * 2f).toInt())
        val radius = width * 0.5f
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            splat(ax + (bx - ax) * t, ay + (by - ay) * t, radius, ink)
        }
    }

    /** Writes a soft disc, keeping whichever is more: the ink already there or the new ink. */
    private fun splat(cx: Float, cy: Float, radius: Float, value: Float) {
        val reach = radius + 1f
        val left = (cx - reach).toInt().coerceAtLeast(0)
        val right = (cx + reach).toInt().coerceAtMost(columns - 1)
        val top = (cy - reach).toInt().coerceAtLeast(0)
        val bottom = (cy + reach).toInt().coerceAtMost(rows - 1)
        for (row in top..bottom) {
            for (column in left..right) {
                val dx = column + 0.5f - cx
                val dy = row + 0.5f - cy
                val away = sqrt(dx * dx + dy * dy)
                // Full inside the radius, gone one cell past it.
                val cover = (radius + 1f - away).coerceIn(0f, 1f) * value
                if (cover <= 0f) continue
                val cell = row * columns + column
                if (cover > ink[cell]) {
                    ink[cell] = cover
                    age[cell] = 0f
                }
            }
        }
        dirty = true
    }

    private fun sample(field: FloatArray, x: Float, y: Float): Float {
        val px = (x - 0.5f).coerceIn(0f, columns - 1f)
        val py = (y - 0.5f).coerceIn(0f, rows - 1f)
        val left = px.toInt().coerceAtMost(columns - 2)
        val top = py.toInt().coerceAtMost(rows - 2)
        val tx = px - left
        val ty = py - top
        val a = top * columns + left
        return field[a] * (1f - tx) * (1f - ty) + field[a + 1] * tx * (1f - ty) +
            field[a + columns] * (1f - tx) * ty + field[a + columns + 1] * tx * ty
    }

    companion object {
        /** Seconds of age the texture can carry. Older ink reads as this. */
        const val MOST_AGE = 8f
    }
}
