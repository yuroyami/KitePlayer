package io.github.yuroyami.kiteplayer.audioviz.viz.field

import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the ink of a memory field goes each second, at a point.
 *
 * Points are in the drawings' centred units: 0,0 is the middle, one unit is half the screen height,
 * and x runs to plus or minus `aspect`. y points down the screen, as `centred()` in the shader
 * header does: -1 is the top edge. The answer goes into `out` as dx, dy per second.
 * These are Battery's "CurrentShift" displacements, written as closed forms, so a cell costs one call.
 */
internal fun interface Flow {
    fun at(x: Float, y: Float, aspect: Float, out: FloatArray)
}

internal object Flows {

    /** Turns about the middle, faster further out, so straight marks become spirals. */
    fun Swirl(turnPerSecond: Float): Flow = Flow { x, y, _, out ->
        out[0] = -y * turnPerSecond
        out[1] = x * turnPerSecond
    }

    /** Pulls every point towards the middle line of its wedge, so the picture folds into [wedges] copies. */
    fun Kaleido(wedges: Int, pull: Float): Flow = Flow { x, y, _, out ->
        val wedge = TAU / wedges
        val angle = atan2(y, x)
        val into = angle - floor(angle / wedge) * wedge
        val target = angle - into + wedge * 0.5f
        val reach = sqrt(x * x + y * y)
        out[0] = (reach * cos(target) - x) * pull
        out[1] = (reach * sin(target) - y) * pull
    }

    /** Everything moves outward from the middle: a tunnel rushing at the viewer. */
    fun Tunnel(perSecond: Float): Flow = Flow { x, y, _, out ->
        out[0] = x * perSecond
        out[1] = y * perSecond
    }

    /** Everything falls inward: the picture drains into the middle. */
    fun Burst(perSecond: Float): Flow = Flow { x, y, _, out ->
        out[0] = -x * perSecond
        out[1] = -y * perSecond
    }

    /** A constant shift. */
    fun Drift(dx: Float, dy: Float): Flow = Flow { _, _, _, out ->
        out[0] = dx
        out[1] = dy
    }

    /** Tiles of [cells] per unit slide in alternating directions, row by row and column by column. */
    fun Blocks(cells: Float, perSecond: Float): Flow = Flow { x, y, _, out ->
        val row = floor(y * cells).toInt()
        val column = floor(x * cells).toInt()
        out[0] = if (row and 1 == 0) perSecond else -perSecond
        out[1] = if (column and 1 == 0) perSecond * 0.5f else -perSecond * 0.5f
    }

    /** A sine ripple across and down, which makes the picture shimmer like heat. */
    fun Shimmer(amount: Float, waves: Float): Flow = Flow { x, y, _, out ->
        out[0] = sin(y * waves) * amount
        out[1] = sin(x * waves * 0.7f) * amount
    }

    /** The Julia rule, square and add a constant, as a displacement scaled by [amount]. */
    fun Julia(cx: Float, cy: Float, amount: Float): Flow = Flow { x, y, _, out ->
        val zx = x * 1.1f
        val zy = y * 1.1f
        val fx = (zx * zx - zy * zy + cx) / 1.1f
        val fy = (2f * zx * zy + cy) / 1.1f
        out[0] = (fx - x) * amount
        out[1] = (fy - y) * amount
    }

    /** [b] blended over [a] by [mix], for a cross fade between two flows. */
    fun Mixed(a: Flow, b: Flow, mix: Float): Flow {
        val spare = FloatArray(2)
        return Flow { x, y, aspect, out ->
            a.at(x, y, aspect, out)
            b.at(x, y, aspect, spare)
            out[0] += (spare[0] - out[0]) * mix
            out[1] += (spare[1] - out[1]) * mix
        }
    }
}
