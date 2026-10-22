package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iris's petals: Kaleidoscope's stereo curve, folded round the iris.
 *
 * The middle of the two channels runs along a wedge and their difference across it, so a mono mix
 * draws a clean star and a wide mix opens into lace. Unlike Kaleidoscope's, the curve is not scaled
 * to its own furthest point: its size is the trace's own height under the shared gain, so a loud
 * passage grows the petals and a quiet one shrinks them. A point past one petal length is held at
 * it. The trace is cut to its lower frequencies first, because a raw trace scribbles back and forth
 * at every sample and, mirrored, fills the figure solid.
 */
internal class IrisPetals {
    private val resampler = WaveformResampler()
    private val coarseLeft = FloatArray(DETAIL)
    private val coarseRight = FloatArray(DETAIL)
    private val left = FloatArray(SEED)
    private val right = FloatArray(SEED)

    /** Each point along its wedge and across it, in petal lengths. */
    val along = FloatArray(SEED)
    val across = FloatArray(SEED)
    private val normalAlong = FloatArray(SEED)
    private val normalAcross = FloatArray(SEED)

    /** Each point's stereo lean: -1 right, 0 centred, 1 left. */
    val tone = FloatArray(SEED)
    private val distance = FloatArray(SEED)

    // Which half of the trace a point folded from. The line breaks where the trace crosses over.
    private val folded = BooleanArray(SEED)

    /** The point furthest out, where the shards and sparkles leave from. */
    var tip: Int = SEED - 1
        private set

    /** How far the furthest point reaches, 0 to 1 of a petal's length. */
    var reach: Float = REST_LENGTH
        private set

    init {
        rest()
    }

    /** Reads one stereo trace. [gain] is the frame's shared waveform gain; nothing rescales after it. */
    fun read(lefts: FloatArray, rights: FloatArray, gain: Float) {
        if (lefts.size < 2 || rights.size != lefts.size) return
        resampler.resample(lefts, coarseLeft)
        resampler.resample(rights, coarseRight)
        curve(coarseLeft, left)
        curve(coarseRight, right)
        val scale = gain * GAIN
        var outermost = -1f
        for (index in 0 until SEED) {
            val l = left[index]
            val r = right[index]
            var middle = (l + r) * 0.5f * scale
            var side = (l - r) * 0.5f * scale
            val below = middle < 0f
            if (below) {
                middle = -middle
                side = -side
            }
            val out = sqrt(middle * middle + side * side)
            if (out > 1f) {
                middle /= out
                side /= out
            }
            along[index] = middle
            across[index] = side
            folded[index] = below
            distance[index] = min(out, 1f)
            if (distance[index] > outermost) {
                outermost = distance[index]
                tip = index
            }
            // Left-heavy points lean one way and right-heavy ones the other. Near the hub a point is
            // too small for its balance to mean anything, so it stays centred.
            val sum = abs(l) + abs(r)
            val balance = if (sum > 0f) (abs(l) - abs(r)) / sum else 0f
            val strength = smooth(COLOUR_FROM, COLOUR_TO, abs(balance)) * smooth(0f, HUB, distance[index])
            tone[index] = if (balance >= 0f) strength else -strength
        }
        reach = outermost.coerceAtLeast(0f)
        normals()
    }

    /** A short mono line, so the first frame shows a small star. */
    fun rest() {
        for (index in 0 until SEED) {
            along[index] = index / (SEED - 1f) * REST_LENGTH
            across[index] = 0f
            distance[index] = along[index]
            normalAlong[index] = 0f
            normalAcross[index] = 1f
            tone[index] = 0f
            folded[index] = false
        }
        tip = SEED - 1
        reach = REST_LENGTH
    }

    /**
     * Where the tip of wedge [mirror] of [folds] sits, offset from the middle, in the units of
     * [base] and [length], with y pointing down the screen. Written into [out] as x, y.
     */
    fun tipAt(mirror: Int, folds: Int, turn: Float, base: Float, length: Float, out: FloatArray) {
        val theta = turn + TAU * mirror / folds
        val flip = flipOf(mirror, folds)
        val reachOut = base + length * along[tip]
        val side = length * across[tip]
        out[0] = reachOut * sin(theta) - side * cos(theta) * flip
        out[1] = -reachOut * cos(theta) - side * sin(theta) * flip
    }

    /**
     * The curve repeated round ([centreX], [centreY]) in [folds] wedges, each starting [base] pixels
     * out and [length] pixels long, turned by [turn]. With an even count every other wedge is
     * mirrored so the seams meet; with an odd count the flower turns without mirrors. Each line is a
     * flat core [line] pixels wide with soft edges, and it adds its light. [image] picks the plain
     * picture, coloured from [tones], or one of the drop's two lens images, which open to every point
     * as they [meet].
     */
    fun DrawScope.drawPetals(
        mesh: TriangleMesh,
        folds: Int,
        centreX: Float,
        centreY: Float,
        base: Float,
        length: Float,
        turn: Float,
        tones: IntArray,
        alpha: Float,
        image: Int,
        meet: Float,
        line: Float,
    ) {
        if (alpha <= 0f || folds <= 0 || length <= 0f) return
        val inner = line * 0.5f
        val outer = inner + FEATHER
        val fixed = when (image) {
            MAGENTA_IMAGE -> mix(MAGENTA, WHITE, meet)
            CYAN_IMAGE -> mix(CYAN, WHITE, meet)
            else -> WHITE
        }
        mesh.clear()
        for (mirror in 0 until folds) {
            if (mesh.vertexCount + SEED * 4 > mesh.maxVertices) {
                drawMesh(mesh, BlendMode.Plus)
                mesh.clear()
            }
            val theta = turn + TAU * mirror / folds
            val flip = flipOf(mirror, folds)
            val alongX = sin(theta)
            val alongY = -cos(theta)
            val acrossX = -cos(theta) * flip
            val acrossY = -sin(theta) * flip
            var previous = -1
            for (index in 0 until SEED) {
                val out = base + length * along[index]
                val side = length * across[index]
                val x = centreX + out * alongX + side * acrossX
                val y = centreY + out * alongY + side * acrossY
                val normalX = normalAlong[index] * alongX + normalAcross[index] * acrossX
                val normalY = normalAlong[index] * alongY + normalAcross[index] * acrossY
                val code = tone[index]
                val rgb: Int
                val weight: Float
                when (image) {
                    NORMAL -> {
                        rgb = toneOf(tones, code)
                        weight = 1f
                    }
                    // Each lens passes the points that lean its way and the centred ones.
                    MAGENTA_IMAGE -> {
                        rgb = fixed
                        weight = lens(if (code >= 0f) 1f else 1f + code, meet)
                    }
                    else -> {
                        rgb = fixed
                        weight = lens(if (code <= 0f) 1f else 1f - code, meet)
                    }
                }
                val lit = argb(alpha * weight, rgb)
                // The same colour with no alpha at the rim, so the edge fades out rather than stepping.
                val first = mesh.vertex(x - normalX * outer, y - normalY * outer, rgb)
                mesh.vertex(x - normalX * inner, y - normalY * inner, lit)
                mesh.vertex(x + normalX * inner, y + normalY * inner, lit)
                mesh.vertex(x + normalX * outer, y + normalY * outer, rgb)
                if (previous >= 0 && folded[index] == folded[index - 1]) {
                    mesh.quad(previous, previous + 1, first + 1, first)
                    mesh.quad(previous + 1, previous + 2, first + 2, first + 1)
                    mesh.quad(previous + 2, previous + 3, first + 3, first + 2)
                }
                previous = first
            }
        }
        drawMesh(mesh, BlendMode.Plus)
    }

    // Each point's normal, from its neighbours on the same side of the fold.
    private fun normals() {
        for (index in 0 until SEED) {
            val first = index == 0 || folded[index] != folded[index - 1]
            val last = index == SEED - 1 || folded[index + 1] != folded[index]
            val from = if (first) index else index - 1
            val to = if (last) index else index + 1
            val stepAlong = along[to] - along[from]
            val stepAcross = across[to] - across[from]
            val length = sqrt(stepAlong * stepAlong + stepAcross * stepAcross)
            if (length < 1e-6f) {
                normalAlong[index] = 0f
                normalAcross[index] = 1f
            } else {
                normalAlong[index] = -stepAcross / length
                normalAcross[index] = stepAlong / length
            }
        }
    }

    // A smooth curve through the band-limited points, back at the seed's full count, so the lines have no corners.
    private fun curve(points: FloatArray, into: FloatArray) {
        val last = points.size - 1
        for (index in into.indices) {
            val at = index * last.toFloat() / (into.size - 1)
            val step = at.toInt().coerceAtMost(last - 1)
            val t = at - step
            val p0 = points[(step - 1).coerceAtLeast(0)]
            val p1 = points[step]
            val p2 = points[step + 1]
            val p3 = points[(step + 2).coerceAtMost(last)]
            into[index] = 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                (3f * p1 - p0 - 3f * p2 + p3) * t * t * t)
        }
    }

    internal companion object {
        /** Points in the curve. The trace is cut to [DETAIL] points first, so more would add nothing. */
        const val SEED = 256

        /** Points the trace is band-limited to before the curve is drawn back through [SEED]: about 2 kHz. */
        const val DETAIL = 48

        /** Petal lengths a full-scale centred trace reaches under a gain of 1, before the hold at 1. */
        const val GAIN = 1.5f

        /** The first frame's star, in petal lengths. */
        const val REST_LENGTH = 0.05f

        /** The soft edge of a line, in pixels. */
        const val FEATHER = 0.75f

        /** How far a point must lean before it takes colour, where it is fully coloured, and the hub it ignores. */
        const val COLOUR_FROM = 0.06f
        const val COLOUR_TO = 0.45f
        const val HUB = 0.12f

        const val NORMAL = 0
        const val MAGENTA_IMAGE = 1
        const val CYAN_IMAGE = 2

        /** Entries of a tone table, from -1 (right) through 0 (centred) to 1 (left). */
        const val TONES = 65

        const val WHITE = 0xFFFFFF

        /** The drop's magenta and cyan, Kaleidoscope's swatches, packed without alpha. No palette changes them. */
        val MAGENTA = colourOf(0.64f, 0.26f, 350f).toArgb() and 0xFFFFFF
        val CYAN = colourOf(0.86f, 0.14f, 205f).toArgb() and 0xFFFFFF

        fun toneOf(tones: IntArray, code: Float): Int =
            tones[((code.coerceIn(-1f, 1f) + 1f) * 0.5f * (TONES - 1) + 0.5f).toInt()]

        fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or rgb

        fun mix(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            val red = (from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * t
            val green = (from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * t
            val blue = (from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t
            return ((red + 0.5f).toInt() shl 16) or ((green + 0.5f).toInt() shl 8) or (blue + 0.5f).toInt()
        }

        fun smooth(from: Float, to: Float, value: Float): Float {
            val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** As the two lens images meet, both open to every point, so the meeting comes out whole. */
        fun lens(weight: Float, meet: Float): Float = weight + (1f - weight) * meet

        /** Every other wedge is mirrored when the count is even; an odd count turns without mirrors. */
        fun flipOf(mirror: Int, folds: Int): Float = if (folds % 2 != 0 || mirror % 2 == 0) 1f else -1f
    }
}
