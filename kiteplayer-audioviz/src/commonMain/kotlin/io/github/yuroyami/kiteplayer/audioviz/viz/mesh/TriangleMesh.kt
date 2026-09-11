package io.github.yuroyami.kiteplayer.audioviz.viz.mesh

import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * A batch of triangles with a colour at every corner, drawn in a single call.
 *
 * Compose draws paths, and a tunnel drawn as paths reads as a wire frame because it is one. This
 * fills the space between the lines instead. Each corner carries its own colour and the graphics
 * card blends between them across the face, so lighting worked out once per corner comes out smooth
 * over the whole surface. A few thousand triangles cost one call, where the same number of filled
 * paths would cost a few thousand.
 *
 * Build it once and reuse it: [clear] at the start of every frame, add corners with [vertex], join
 * them with [triangle] or [quad], then hand it to [drawMesh]. Nothing is allocated after the first
 * frame unless the number of triangles changes.
 */
@AudioVizAuthoringApi
public class TriangleMesh(
    public val maxVertices: Int = 4096,
    public val maxIndices: Int = maxVertices * 6,
) {
    init {
        // Corners are referred to by sixteen bit numbers, which is what the drawing call expects.
        require(maxVertices in 3..32_767) { "maxVertices must be 3..32767, was $maxVertices" }
        require(maxIndices >= 3) { "maxIndices must be at least 3, was $maxIndices" }
    }

    internal val positions = FloatArray(maxVertices * 2)
    internal val colors = IntArray(maxVertices)
    internal val indices = ShortArray(maxIndices)

    public var vertexCount: Int = 0
        private set

    public var indexCount: Int = 0
        private set

    public fun clear() {
        vertexCount = 0
        indexCount = 0
    }

    /** Adds a corner and answers its number, or -1 when the mesh is full. [argb] is packed colour. */
    public fun vertex(x: Float, y: Float, argb: Int): Int {
        if (vertexCount >= maxVertices) return -1
        val at = vertexCount
        positions[at * 2] = x
        positions[at * 2 + 1] = y
        colors[at] = argb
        vertexCount++
        return at
    }

    /** Joins three corners. Quietly skipped when any of them does not exist or the mesh is full. */
    public fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > maxIndices) return
        if (a !in 0 until vertexCount || b !in 0 until vertexCount || c !in 0 until vertexCount) return
        indices[indexCount] = a.toShort()
        indices[indexCount + 1] = b.toShort()
        indices[indexCount + 2] = c.toShort()
        indexCount += 3
    }

    /** Two triangles covering four corners given in order round the edge. */
    public fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    // Some drawing calls work out how many corners there are from the length of the array they are
    // handed, so they need copies trimmed to size. These are kept and only rebuilt when the size
    // actually changes, which for most drawings is never.
    private var exactPositions = FloatArray(0)
    private var exactColors = IntArray(0)
    private var exactIndices = ShortArray(0)

    internal fun positionsExact(): FloatArray {
        if (exactPositions.size != vertexCount * 2) exactPositions = FloatArray(vertexCount * 2)
        positions.copyInto(exactPositions, 0, 0, vertexCount * 2)
        return exactPositions
    }

    internal fun colorsExact(): IntArray {
        if (exactColors.size != vertexCount) exactColors = IntArray(vertexCount)
        colors.copyInto(exactColors, 0, 0, vertexCount)
        return exactColors
    }

    internal fun indicesExact(): ShortArray {
        if (exactIndices.size != indexCount) exactIndices = ShortArray(indexCount)
        indices.copyInto(exactIndices, 0, 0, indexCount)
        return exactIndices
    }
}

/**
 * Draws every triangle in [mesh] in one call, in the order they were added.
 *
 * Later triangles are laid over earlier ones, so add the far ones first. [blendMode] decides how
 * the whole batch lands on what is already there: the default covers it, and [BlendMode.Plus] adds
 * light, which is what a glowing surface in a feedback loop wants.
 */
@AudioVizAuthoringApi
public expect fun DrawScope.drawMesh(mesh: TriangleMesh, blendMode: BlendMode = BlendMode.SrcOver)

/**
 * Adds a soft round spot: a fan of triangles in full colour at the middle and clear at the rim.
 *
 * Thousands go out in one [drawMesh] call, where the same number of circles would each be a call of
 * their own. [argb] is the colour at the middle, alpha included.
 */
@AudioVizAuthoringApi
public fun TriangleMesh.glow(x: Float, y: Float, radius: Float, argb: Int, sides: Int = 6) {
    if (vertexCount + sides + 1 > maxVertices) return
    val middle = vertex(x, y, argb)
    val rim = argb and 0x00FFFFFF
    val first = vertexCount
    for (side in 0 until sides) {
        val angle = FULL_TURN * side / sides
        vertex(x + cos(angle) * radius, y + sin(angle) * radius, rim)
    }
    for (side in 0 until sides) triangle(middle, first + side, first + (side + 1) % sides)
}

/** A four pointed spark: two narrow diamonds crossed, bright in the middle and clear at the tips. */
@AudioVizAuthoringApi
public fun TriangleMesh.spark(x: Float, y: Float, length: Float, width: Float, argb: Int) {
    if (vertexCount + 10 > maxVertices) return
    val rim = argb and 0x00FFFFFF
    for (turn in 0 until 2) {
        val across = if (turn == 0) length else width
        val down = if (turn == 0) width else length
        val middle = vertex(x, y, argb)
        val right = vertex(x + across, y, rim)
        val below = vertex(x, y + down, rim)
        val left = vertex(x - across, y, rim)
        val above = vertex(x, y - down, rim)
        triangle(middle, right, below)
        triangle(middle, below, left)
        triangle(middle, left, above)
        triangle(middle, above, right)
    }
}

/** A ring [thickness] wide, in one colour, as a band of [sides] quads. */
@AudioVizAuthoringApi
public fun TriangleMesh.ring(x: Float, y: Float, radius: Float, thickness: Float, argb: Int, sides: Int = 24) {
    if (vertexCount + sides * 2 > maxVertices) return
    val inner = (radius - thickness * 0.5f).coerceAtLeast(0f)
    val outer = radius + thickness * 0.5f
    val first = vertexCount
    for (side in 0 until sides) {
        val angle = FULL_TURN * side / sides
        val c = cos(angle)
        val s = sin(angle)
        vertex(x + c * inner, y + s * inner, argb)
        vertex(x + c * outer, y + s * outer, argb)
    }
    for (side in 0 until sides) {
        val a = first + side * 2
        val b = first + ((side + 1) % sides) * 2
        quad(a, a + 1, b + 1, b)
    }
}

/** A filled regular polygon turned by [turn] radians: [argb] in the middle, [rim] at the corners. */
@AudioVizAuthoringApi
public fun TriangleMesh.polygon(x: Float, y: Float, radius: Float, sides: Int, turn: Float, argb: Int, rim: Int = argb) {
    if (vertexCount + sides + 1 > maxVertices) return
    val middle = vertex(x, y, argb)
    val first = vertexCount
    for (side in 0 until sides) {
        val angle = turn + FULL_TURN * side / sides
        vertex(x + cos(angle) * radius, y + sin(angle) * radius, rim)
    }
    for (side in 0 until sides) triangle(middle, first + side, first + (side + 1) % sides)
}

/** A thin sliver like a shard of glass, [length] long, turned by [turn]. */
@AudioVizAuthoringApi
public fun TriangleMesh.shard(x: Float, y: Float, length: Float, turn: Float, argb: Int) {
    if (vertexCount + 3 > maxVertices) return
    val c = cos(turn)
    val s = sin(turn)
    val a = vertex(x + c * length * 0.6f, y + s * length * 0.6f, argb)
    val b = vertex(x - c * length * 0.4f - s * length * 0.18f, y - s * length * 0.4f + c * length * 0.18f, argb)
    val d = vertex(x - c * length * 0.3f + s * length * 0.12f, y - s * length * 0.3f - c * length * 0.12f, argb and 0x7FFFFFFF)
    triangle(a, b, d)
}

/** A streak from a clear tail to a bright head, [width] wide at the head. */
@AudioVizAuthoringApi
public fun TriangleMesh.streak(tailX: Float, tailY: Float, headX: Float, headY: Float, width: Float, argb: Int) {
    if (vertexCount + 4 > maxVertices) return
    val dx = headX - tailX
    val dy = headY - tailY
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
    val nx = -dy / length * width * 0.5f
    val ny = dx / length * width * 0.5f
    val clear = argb and 0x00FFFFFF
    val tail = vertex(tailX, tailY, clear)
    val left = vertex(headX + nx, headY + ny, argb)
    val right = vertex(headX - nx, headY - ny, argb)
    val tip = vertex(headX + dx / length * width, headY + dy / length * width, argb)
    triangle(tail, left, right)
    triangle(left, tip, right)
}

/** One of four small marks, turned by [turn]: 0 a cross, 1 a diamond, 2 a chevron, 3 a dash. */
@AudioVizAuthoringApi
public fun TriangleMesh.glyph(kind: Int, x: Float, y: Float, size: Float, turn: Float, argb: Int) {
    val c = cos(turn)
    val s = sin(turn)
    fun bar(fromX: Float, fromY: Float, toX: Float, toY: Float, wide: Float) {
        val ax = x + (fromX * c - fromY * s) * size
        val ay = y + (fromX * s + fromY * c) * size
        val bx = x + (toX * c - toY * s) * size
        val by = y + (toX * s + toY * c) * size
        val dx = bx - ax
        val dy = by - ay
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val nx = -dy / length * wide * size * 0.5f
        val ny = dx / length * wide * size * 0.5f
        if (vertexCount + 4 > maxVertices) return
        val p = vertex(ax + nx, ay + ny, argb)
        val q = vertex(bx + nx, by + ny, argb)
        val r = vertex(bx - nx, by - ny, argb)
        val t = vertex(ax - nx, ay - ny, argb)
        quad(p, q, r, t)
    }
    when (kind) {
        0 -> {
            bar(-1f, 0f, 1f, 0f, 0.3f)
            bar(0f, -1f, 0f, 1f, 0.3f)
        }
        1 -> polygon(x, y, size, 4, turn, argb)
        2 -> {
            bar(-1f, -0.6f, 0f, 0.4f, 0.32f)
            bar(0f, 0.4f, 1f, -0.6f, 0.32f)
        }
        else -> bar(-1f, 0f, 1f, 0f, 0.35f)
    }
}

/** A wide line through the first [count] points of [xs] and [ys]. */
@AudioVizAuthoringApi
public fun TriangleMesh.strip(xs: FloatArray, ys: FloatArray, count: Int, width: Float, argb: Int) {
    if (count < 2 || vertexCount + count * 2 > maxVertices) return
    val first = vertexCount
    for (index in 0 until count) {
        val before = (index - 1).coerceAtLeast(0)
        val after = (index + 1).coerceAtMost(count - 1)
        val dx = xs[after] - xs[before]
        val dy = ys[after] - ys[before]
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val nx = -dy / length * width * 0.5f
        val ny = dx / length * width * 0.5f
        vertex(xs[index] + nx, ys[index] + ny, argb)
        vertex(xs[index] - nx, ys[index] - ny, argb)
    }
    for (index in 0 until count - 1) {
        val a = first + index * 2
        quad(a, a + 2, a + 3, a + 1)
    }
}

/**
 * How much of a lamp at the camera reaches a wall [radius] from the axis and [distance] ahead: nearly
 * all of it up close, less and less further down. Seen from inside a tube, no normals are needed.
 */
@AudioVizAuthoringApi
public fun headlight(radius: Float, distance: Float): Float = radius / sqrt(radius * radius + distance * distance)

private const val FULL_TURN = 6.2831855f
