package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A contour map of the last few seconds of music laid out in rings round a centre on an orbit, its
 * outer rings wider than the screen. Each level sits at its own depth, hikers walk round the lines,
 * shooting stars cross above, and a kick sends a ring out from the core. On a drop time runs backwards
 * for a visual cycle and the map turns inside out.
 */
internal class Contour : Layered(
    name = "Contour",
    family = VizFamily.Plenoptic,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 403L, groundKind = GroundKind.Spectrogram, groundDim = 0.6f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.06f, seed = 403)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.6f, livelyTrail = 0.5f)

    private val levels = genes.choice("Levels", 3, start = 1)
    private val maps = genes.toggle("Second map", start = false)
    private val backwards = genes.toggle("Time backwards", start = false)
    private val lineKind = genes.choice("Line kind", 2)

    private val grid = Array(ROWS) { FloatArray(COLUMNS) }
    private var newest = 0
    private var owed = 0f
    private val sorted = FloatArray(ROWS * COLUMNS)
    private val paths = Array(7) { Path() }
    private val turn = MusicClock(beatsPerCycle = 16f)
    private var spin = 0f
    private val core = Spring(stiffness = 220f, damping = 0.45f)
    private val stage = Stage(reachX = 0.22f, reachY = 0.16f, start = 1.9f)
    private val centre = Orbiter(radiusX = 0.3f, radiusY = 0.24f, lapsPerBar = 0.25f)
    private var inside = 0f
    private val hikerAngle = FloatArray(HIKERS) { it * TAU / HIKERS }
    private val ringAge = FloatArray(6)
    private var nextRing = 0
    private val comets = Comets(size = 0.025f)
    private val hikerMesh = TriangleMesh(maxVertices = HIKERS * 8 + 8)
    private val dashes = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val bands = state.frame.bandsRel
        if (bands.isNotEmpty()) {
            owed += dt * (6f * state.idle + 8f * state.tempo)
            while (owed >= 1f) {
                owed -= 1f
                newest = (newest - 1 + ROWS) % ROWS
                val row = grid[newest]
                for (column in 0 until COLUMNS) row[column] = bands.foldedAt(column.toFloat() / COLUMNS)
            }
        }
        spin = turn.advance(dt, state.frame, state.paced(0.05f)) * TAU
        core.kick(gestures.kick * 7f)
        core.advance(dt)
        if (gestures.kick > 0f) {
            ringAge[nextRing] = 0.001f
            nextRing = (nextRing + 1) % ringAge.size
        }
        for (slot in ringAge.indices) {
            if (ringAge[slot] <= 0f) continue
            ringAge[slot] += dt / (gestures.beatSeconds * 2f)
            if (ringAge[slot] >= 1f) ringAge[slot] = 0f
        }
        if (gestures.drop) inside = 1f
        inside = (inside - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        stage.advance(dt * state.idle)
        centre.centreX = stage.x
        centre.centreY = stage.y
        centre.advance(state, gestures)
        kit.place(0, centre.x, centre.y)
        for (hiker in 0 until HIKERS) hikerAngle[hiker] += dt * (0.9f * state.idle + 0.2f * hiker) * state.tempo * if (hiker % 2 == 0) 1f else -1f
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
        val aspect = kit.aspect
        val reach = hikerReach(0)
        kit.place(2, centre.x + cos(hikerAngle[0]) * reach / aspect, centre.y + sin(hikerAngle[0]) * reach)
    }

    /** How far hiker [index] walks from the centre, as a share of the height. */
    private fun hikerReach(index: Int): Float = 0.18f + 0.03f * index

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        var count = 0
        for (row in grid) for (value in row) sorted[count++] = value
        sorted.sort()
        val at = Offset(centre.x * size.width, centre.y * size.height)
        val inner = size.minDimension * 0.07f
        val outer = sceneRadius * 1.3f
        val set = LEVELS[levels.value]
        val lift = 0.09f + 1.17f * state.lift
        val second = maps.weight(1)
        for (level in set.indices) {
            val threshold = sorted[((sorted.size - 1) * set[level]).toInt()]
            val path = paths[level]
            path.reset()
            if (threshold <= 0f) continue
            trace(path, threshold, at, spin, inner, outer)
            val share = level.toFloat() / (set.size - 1)
            val colour = state.palette.cycled(share * 0.6f + genes.walk, alpha = ((0.35f + 0.55f * share) * lift).coerceIn(0f, 1f))
            val style = Stroke(
                (size.minDimension * (0.006f + 0.004f * share)).coerceAtLeast(1.2f),
                cap = StrokeCap.Round,
                pathEffect = if (lineKind.value == 1) dashes else null,
            )
            // Each level a little nearer than the one below, so the map reads as terrain seen from above.
            val depth = 0.2f * share
            translate(camera.panX * size.width * depth, camera.panY * size.height * depth) {
                drawPath(path, colour, style = style)
                if (second > 0.01f) scale(-1f, -1f, center) { drawPath(path, colour.copy(alpha = colour.alpha * second), style = style) }
            }
        }
        drawCircle(
            state.palette.cap.copy(alpha = (0.15f + 0.6f * core.value).coerceIn(0f, 1f)),
            size.minDimension * 0.025f * (0.5f + abs(core.value).coerceAtMost(1.5f)),
            at,
        )
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    /**
     * Marching squares: each cell of four readings is crossed by the line where the level falls between
     * them, and one of sixteen patterns says which of its edges the line joins.
     */
    private fun DrawScope.trace(path: Path, level: Float, at: Offset, spin: Float, inner: Float, outer: Float) {
        for (row in 0 until ROWS - 1) {
            val near = grid[(newest + row) % ROWS]
            val far = grid[(newest + row + 1) % ROWS]
            for (column in 0 until COLUMNS) {
                val right = (column + 1) % COLUMNS
                val a = near[column]
                val b = near[right]
                val c = far[right]
                val d = far[column]
                val pattern = (if (a > level) 1 else 0) or (if (b > level) 2 else 0) or
                    (if (c > level) 4 else 0) or (if (d > level) 8 else 0)
                if (pattern == 0 || pattern == 15) continue
                val x = column.toFloat()
                val y = row.toFloat()
                val topX = x + crossing(a, b, level)
                val rightY = y + crossing(b, c, level)
                val bottomX = x + crossing(d, c, level)
                val leftY = y + crossing(a, d, level)
                when (pattern) {
                    1, 14 -> segment(path, topX, y, x, leftY, at, spin, inner, outer)
                    2, 13 -> segment(path, topX, y, x + 1f, rightY, at, spin, inner, outer)
                    3, 12 -> segment(path, x, leftY, x + 1f, rightY, at, spin, inner, outer)
                    4, 11 -> segment(path, x + 1f, rightY, bottomX, y + 1f, at, spin, inner, outer)
                    6, 9 -> segment(path, topX, y, bottomX, y + 1f, at, spin, inner, outer)
                    7, 8 -> segment(path, x, leftY, bottomX, y + 1f, at, spin, inner, outer)
                    5 -> {
                        segment(path, topX, y, x, leftY, at, spin, inner, outer)
                        segment(path, x + 1f, rightY, bottomX, y + 1f, at, spin, inner, outer)
                    }
                    10 -> {
                        segment(path, topX, y, x + 1f, rightY, at, spin, inner, outer)
                        segment(path, x, leftY, bottomX, y + 1f, at, spin, inner, outer)
                    }
                }
            }
        }
    }

    private fun crossing(from: Float, to: Float, level: Float): Float {
        val span = to - from
        return if (abs(span) < 1e-6f) 0.5f else ((level - from) / span).coerceIn(0f, 1f)
    }

    /** One piece of line: columns go round, rows go outward, or inward when time runs backwards. */
    private fun segment(path: Path, x1: Float, y1: Float, x2: Float, y2: Float, at: Offset, spin: Float, inner: Float, outer: Float) {
        val from = polar(at, TAU * x1 / COLUMNS + spin, radiusOf(y1, inner, outer))
        val to = polar(at, TAU * x2 / COLUMNS + spin, radiusOf(y2, inner, outer))
        path.moveTo(from.x, from.y)
        path.lineTo(to.x, to.y)
    }

    private fun radiusOf(row: Float, inner: Float, outer: Float): Float {
        val out = row / (ROWS - 1)
        val flipped = if (backwards.on) 1f - out else out
        // A drop turns the map inside out for a visual cycle.
        val shown = flipped + (1f - 2f * flipped) * inside
        return inner + (outer - inner) * shown
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val at = Offset(centre.x * size.width, centre.y * size.height)
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (slot in ringAge.indices) {
            val age = ringAge[slot]
            if (age <= 0f) continue
            drawCircle(
                state.palette.cap.copy(alpha = (0.5f * (1f - age)).coerceIn(0f, 1f)),
                sceneRadius * 0.9f * age,
                at,
                style = Stroke(thin * (1f + 3f * (1f - age))),
            )
        }
        hikerMesh.clear()
        for (hiker in 0 until HIKERS) {
            val reach = hikerReach(hiker) * size.height
            hikerMesh.glow(
                at.x + cos(hikerAngle[hiker]) * reach,
                at.y + sin(hikerAngle[hiker]) * reach,
                size.minDimension * 0.016f,
                state.palette.argb(0.15f * hiker + genes.walk, saturation = 0.4f, value = 1f, alpha = 0.12f + 1.15f * state.lift),
            )
        }
        drawMesh(hikerMesh, BlendMode.Plus)
    }

    override fun onReset() {
        for (row in grid) row.fill(0f)
        newest = 0
        owed = 0f
        turn.reset()
        spin = 0f
        core.reset()
        stage.reset()
        centre.reset()
        inside = 0f
        ringAge.fill(0f)
        nextRing = 0
        comets.clear()
    }

    private companion object {
        const val ROWS = 36
        const val COLUMNS = 48
        const val HIKERS = 12

        /** The levels traced, as shares of the grid's own readings from low to high, for three, five or seven lines. */
        val LEVELS = arrayOf(
            floatArrayOf(0.5f, 0.72f, 0.9f),
            floatArrayOf(0.4f, 0.58f, 0.72f, 0.84f, 0.93f),
            floatArrayOf(0.3f, 0.45f, 0.58f, 0.7f, 0.8f, 0.88f, 0.95f),
        )
    }
}
