package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.*
import kotlin.math.*

/**
 * Opaque, depth-ordered terraces with black faces and neon edges, cyan near the viewer and magenta
 * toward the horizon. A terrace's top lights in its edge colour while its band is loud, and a kick
 * flashes the nearest edges. Treble is beside the road, bass outside: bass cannot hide quieter lanes.
 * The rows touch, and depth is time, so the field is the spectrum of the last four seconds. The road's
 * edges carry the waveform, and on a wet road the terraces nearest it streak their light onto it.
 */
internal class NeonLoFiFloor {
    val mesh = TriangleMesh(14_000, 21_000)
    private val points = FloatArray(16)
    private val heights = FloatArray(16)
    private val edgeColors = IntArray(ROWS * 16)
    private val tops = IntArray(ROWS * 16)
    private val fronts = IntArray(ROWS * 16)
    private val sides = IntArray(ROWS * 16)
    internal fun faceColor(lane: Int, face: Int, row: Int = 0): Int {
        val at = row * 16 + lane
        return when (face) { 0 -> tops[at]; 1 -> fronts[at]; else -> sides[at] }
    }
    var triangles = 0; private set
    var copiedBytes = 0; private set
    var portable = false
    val lastHeights = FloatArray(16 * 12 * 2)
    /** The largest waveform swing the road's edges drew in the last build, 0 to 1. */
    var edgeAmplitude = 0f; private set

    fun build(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float) {
        mesh.clear(); lastHeights.fill(0f)
        val flight = world.flight
        val lanes = if (portable) 8 else 16
        val rows = if (portable) 5 else 12
        val stride = 48f / rows
        val laneWidth = flight.laneWidth(width / height) * 16f / lanes
        val start = floor((flight.travel + 1.8) / stride).toFloat() * stride
        val pixelCore = max(0.7f, min(width, height) / 650f)
        val neon = world.controls[11]
        val face = paint.packed(NeonLoFiPaint.INK, 1f)
        // The road's edges lie on the ground, so they go first and every terrace face covers them.
        roadEdges(world, paint, width, height, start + rows * stride, pixelCore, neon)
        for (row in rows - 1 downTo 0) {
            val z0 = max(start + row * stride, flight.travel.toFloat() + 1.8f)
            val z1 = start + (row + 1) * stride
            if (z1 <= z0) continue
            val distance = (z0 + z1) * 0.5f - flight.travel.toFloat()
            val fade = NeonLoFiHistory.smooth(52f, 37f, distance)
            // Cyan near the viewer, magenta toward the horizon; a kick flashes the nearest rows.
            val far = (distance / 48f).coerceIn(0f, 1f)
            val flash = world.kick * world.controls[10] * 1.6f * (1f - NeonLoFiHistory.smooth(6f, 16f, distance))
            for (i in 0 until lanes) {
                val band = 15 - i * 16 / lanes
                val level = world.lanes[band]
                val lit = NeonLoFiHistory.smooth(0.18f, 0.55f, level)
                val at = row * 16 + i
                edgeColors[at] = paint.mix(NeonLoFiPaint.NEAR, NeonLoFiPaint.FAR, far,
                    (0.7f + 0.9f * level + world.impulses[band] * world.controls[10] * 0.8f + flash) * neon).toArgb()
                tops[at] = if (lit > 0.01f) paint.mix(NeonLoFiPaint.NEAR, NeonLoFiPaint.FAR, far, 0.6f * lit * neon).toArgb() else face
                fronts[at] = face
                sides[at] = face
            }
            for (side in intArrayOf(-1, 1)) {
            for (lane in 0 until lanes) {
                var h = 0f
                for (b in lane * 16 / lanes until (lane + 1) * 16 / lanes) h += world.floorHeight(15 - b, (z0 + z1) * 0.5f)
                h /= (16 / lanes)
                // The shore eases down into the analytic water, without moving the protected road.
                val coast = if (side == 1) 1f - world.regions.weights[3] * NeonLoFiHistory.smooth(0f, 4f, lane.toFloat()) else 1f
                heights[lane] = h * fade * coast * if (lane == 0) 0.55f else 1f
            }
            for (lane in lanes - 1 downTo 0) {
                val h = heights[lane]
                lastHeights[(if (side == 1) 1 else 0) * 192 + row * 16 + lane] = h
                val inner = NeonLoFiFlight.CORRIDOR + lane * laneWidth
                val outer = inner + laneWidth - 0.035f
                flight.project(flight.road(z0) + inner * side, h, z0, width, height, points, 0)
                flight.project(flight.road(z0) + outer * side, h, z0, width, height, points, 2)
                flight.project(flight.road(z1) + outer * side, h, z1, width, height, points, 4)
                flight.project(flight.road(z1) + inner * side, h, z1, width, height, points, 6)
                flight.project(flight.road(z0) + inner * side, 0f, z0, width, height, points, 8)
                flight.project(flight.road(z0) + outer * side, 0f, z0, width, height, points, 10)
                flight.project(flight.road(z1) + inner * side, 0f, z1, width, height, points, 12)
                // Every x of the top face, on the same side of the frame. No range: this runs for every cell.
                val x0 = points[0]; val x1 = points[2]; val x2 = points[4]; val x3 = points[6]
                if ((x0 < -4f && x1 < -4f && x2 < -4f && x3 < -4f) ||
                    (x0 > width + 4f && x1 > width + 4f && x2 > width + 4f && x3 > width + 4f)) continue
                // A face at an equal/lower depth is submitted later. Each cell is opaque even in silence.
                val at = row * 16 + lane
                // A wet road: the two lanes nearest it streak their edge light down onto the road.
                if (!portable && lane < 2 && world.rain > 0.01f) streak(flight, width, height, side, lane, z0, z1,
                    pixelCore, edgeColors[at], (0.15f + 0.35f * world.rain) * neon)
                if (h < flight.height) {
                    face(0, 2, 4, 6, tops[at])
                    line(points[4], points[5], points[6], points[7], pixelCore, edgeColors[at])
                }
                // The side faces the road, so a terrace no taller than its inner neighbour hides it: that
                // neighbour is drawn later and covers it.
                if (lane == 0 || h > heights[lane - 1]) face(0, 6, 12, 8, sides[at])
                face(0, 8, 10, 2, fronts[at])
                line(points[0], points[1], points[6], points[7], pixelCore, edgeColors[at])
                var frontEdge = edgeColors[at]
                // A hit travels through its frequency region; a stopped/reduced-motion view keeps the local crest.
                if (!portable && world.controls[10] > 0f && flight.motion > 0f) {
                    for (accent in world.accents) {
                        if (accent.age >= 2.5f || abs(distance - (3f + accent.age * 19f * flight.motion)) > stride * 0.55f) continue
                        val band = 15 - lane * 16 / lanes
                        if (accent.region == 0 && band >= 4 || accent.region == 1 && band !in 4..9 || accent.region == 2 && band < 10) continue
                        frontEdge = paint.mix(NeonLoFiPaint.NEAR, NeonLoFiPaint.WHITE, 0.35f,
                            0.8f + accent.strength * world.controls[10] * exp(-accent.age * 1.1f)).toArgb()
                        break
                    }
                }
                line(points[0], points[1], points[2], points[3], pixelCore, frontEdge)
            }
            }
            if (!portable) palms(world, paint, width, height, z0, z1, pixelCore)
        }
        triangles = mesh.indexCount / 3
        copiedBytes = mesh.vertexCount * 12 + mesh.indexCount * 2
    }
    /**
     * The two road edges from the nearest row to [far], each swung sideways by the waveform along its
     * length, in the near edge colour and pulsed by the kick.
     */
    private fun roadEdges(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, far: Float,
        core: Float, neon: Float) {
        val flight = world.flight
        val near = flight.travel.toFloat() + 1.8f
        if (far <= near) return
        val color = paint.color(NeonLoFiPaint.NEAR, EDGE_LIGHT * neon * (1f + 0.9f * world.kick)).toArgb()
        val wave = world.wave
        var most = 0f
        val steps = if (portable) PORTABLE_EDGE_STEPS else EDGE_STEPS
        for (side in intArrayOf(-1, 1)) {
            var px = 0f; var py = 0f
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val z = near + (far - near) * t * t
                val swing = (wave[(t * (wave.size - 1)).roundToInt()] - 0.5f) * 2f
                most = max(most, abs(swing))
                flight.project(flight.road(z) + side * (ROAD_HALF + swing * EDGE_REACH), 0f, z, width, height, points, 0)
                if (i > 0) line(px, py, points[0], points[1], core * 1.5f, color)
                px = points[0]; py = points[1]
            }
        }
        edgeAmplitude = most
    }

    /** A streak of [color] at [alpha] on the road, under a terrace's road side edge, toward the viewer. */
    private fun streak(flight: NeonLoFiFlight, width: Float, height: Float, side: Int, lane: Int, z0: Float, z1: Float,
        core: Float, color: Int, alpha: Float) {
        val x = ROAD_HALF - 0.5f - lane * 0.7f
        val near = max(z0 - (z1 - z0), flight.travel.toFloat() + 1.8f)
        flight.project(flight.road(z1) + x * side, 0f, z1, width, height, points, 0)
        flight.project(flight.road(near) + x * side, 0f, near, width, height, points, 2)
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        line(points[0], points[1], points[2], points[3], core * 2f, (color and 0x00FFFFFF) or (a shl 24))
    }

    private fun face(a: Int, b: Int, c: Int, d: Int, color: Int) {
        val v = mesh.vertex(points[a], points[a + 1], color)
        mesh.vertex(points[b], points[b + 1], color); mesh.vertex(points[c], points[c + 1], color); mesh.vertex(points[d], points[d + 1], color)
        mesh.quad(v, v + 1, v + 2, v + 3)
    }
    fun line(ax: Float, ay: Float, bx: Float, by: Float, width: Float, color: Int) {
        val dx = bx - ax; val dy = by - ay; val l = hypot(dx, dy).coerceAtLeast(0.001f)
        val nx = -dy / l * width * 0.5f; val ny = dx / l * width * 0.5f
        val v = mesh.vertex(ax + nx, ay + ny, color)
        mesh.vertex(bx + nx, by + ny, color); mesh.vertex(bx - nx, by - ny, color); mesh.vertex(ax - nx, ay - ny, color)
        mesh.quad(v, v + 1, v + 2, v + 3)
    }
    private fun palms(world: NeonLoFiWorld, paint: NeonLoFiPaint, w: Float, h: Float, z0: Float, z1: Float, core: Float) {
        val id = floor(z1 / 19f).toInt(); val z = id * 19f
        if (z < z0 || z > z1) return
        val intensity = (world.regions.weights[0] + world.regions.weights[3] * 0.65f).coerceIn(0f, 1f)
        if (intensity < 0.01f) return
        for (side in intArrayOf(-1, 1)) {
            val seed = abs(sin(id * 91.17f + side * 12f))
            val x = world.flight.road(z) + side * 3.45f
            val y = (2.5f + seed * 1.7f) * intensity
            world.flight.project(x, 0f, z, w, h, points, 0)
            world.flight.project(x + side * 0.3f, y, z, w, h, points, 2)
            val distanceFade = NeonLoFiHistory.smooth(1.8f, 9f, z - world.flight.travel.toFloat())
            val color = paint.color(NeonLoFiPaint.FAR, 0.55f * intensity, distanceFade).toArgb()
            line(points[0], points[1], points[2], points[3], max(core, h / (z - world.flight.travel.toFloat()) * 0.035f), color)
            val crownX = points[2]; val crownY = points[3]
            for (frond in 0..5) {
                var px = crownX; var py = crownY
                for (step in 1..4) {
                    val t = step / 4f; val a = frond * 1.05f + seed
                    val xx = x + side * 0.3f + cos(a) * t * 1.2f * h * NeonLoFiFlight.FOCAL / world.flight.horizontalFocal(w, h)
                    val yy = y + sin(a) * t * 0.55f + sin(t * PI.toFloat()) * 0.3f - t * t * 0.45f
                    world.flight.project(xx, yy, z, w, h, points, 4)
                    line(px, py, points[4], points[5], max(core, h * NeonLoFiFlight.FOCAL /
                        (z - world.flight.travel.toFloat()) * 0.07f * (1f - t)), color); px = points[4]; py = points[5]
                }
            }
        }
    }
    fun DrawScope.draw() { drawMesh(mesh) }
    companion object {
        const val ROWS = 12

        /** The painted road's half width, how far the waveform swings its edges, and their light. */
        const val ROAD_HALF = 3.25f
        const val EDGE_REACH = 0.3f
        const val EDGE_LIGHT = 1.2f
        /** Segments per road edge, spaced closer near the viewer. They fit inside the triangle budgets. */
        const val EDGE_STEPS = 20
        const val PORTABLE_EDGE_STEPS = 12
    }
}
