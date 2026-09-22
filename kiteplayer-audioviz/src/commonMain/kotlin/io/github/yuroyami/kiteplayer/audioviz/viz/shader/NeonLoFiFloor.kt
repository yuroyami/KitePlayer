package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.*
import kotlin.math.*

/** Opaque, depth-ordered terraces. Treble is beside the road, bass outside: bass cannot hide quieter lanes. */
internal class NeonLoFiFloor {
    val mesh = TriangleMesh(14_000, 21_000)
    private val points = FloatArray(16)
    private val edgeColors = IntArray(16)
    private val tops = IntArray(16)
    private val fronts = IntArray(16)
    private val sides = IntArray(16)
    internal fun faceColor(lane: Int, face: Int): Int = when (face) { 0 -> tops[lane]; 1 -> fronts[lane]; else -> sides[lane] }
    var triangles = 0; private set
    var copiedBytes = 0; private set
    var portable = false
    val lastHeights = FloatArray(16 * 12 * 2)

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
        for (i in 0 until lanes) {
            val band = 15 - i * 16 / lanes
            val t = 0.22f + band / 15f * 0.75f
            val a = if (t < 0.5f) 0 else 1; val b = a + 1
            val mix = if (t < 0.5f) t * 2f else (t - 0.5f) * 2f
            edgeColors[i] = paint.mix(a, b, mix, (0.55f + world.lanes[band] * 0.85f + world.impulses[band] * world.controls[10] * 0.8f) * neon).toArgb()
            tops[i] = paint.mix(a, b, mix, 0.19f + world.lanes[band] * 0.16f).toArgb()
            fronts[i] = paint.mix(a, b, mix, 0.05f + world.lanes[band] * 0.06f).toArgb()
            sides[i] = paint.mix(a, b, mix, 0.08f + world.lanes[band] * 0.09f).toArgb()
        }
        for (row in rows - 1 downTo 0) {
            val z0 = max(start + row * stride, flight.travel.toFloat() + 1.8f)
            val z1 = start + (row + 1) * stride - 0.04f
            if (z1 <= z0) continue
            val distance = (z0 + z1) * 0.5f - flight.travel.toFloat()
            val fade = NeonLoFiHistory.smooth(52f, 37f, distance)
            for (side in intArrayOf(-1, 1)) for (lane in lanes - 1 downTo 0) {
                var h = 0f
                for (b in lane * 16 / lanes until (lane + 1) * 16 / lanes) h += world.floorHeight(15 - b, (z0 + z1) * 0.5f)
                h /= (16 / lanes)
                // The shore eases down into the analytic water, without moving the protected road.
                val coast = if (side == 1) 1f - world.regions.weights[3] * NeonLoFiHistory.smooth(0f, 4f, lane.toFloat()) else 1f
                h *= fade * coast * if (lane == 0) 0.55f else 1f
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
                if ((0..6 step 2).all { points[it] < -4f } || (0..6 step 2).all { points[it] > width + 4f }) continue
                // A face at an equal/lower depth is submitted later. Each cell is opaque even in silence.
                if (h < flight.height) {
                    face(0, 2, 4, 6, tops[lane])
                    line(points[4], points[5], points[6], points[7], pixelCore, edgeColors[lane])
                }
                face(0, 6, 12, 8, sides[lane])
                face(0, 8, 10, 2, fronts[lane])
                line(points[0], points[1], points[6], points[7], pixelCore, edgeColors[lane])
                var frontEdge = edgeColors[lane]
                // A hit travels through its frequency region; a stopped/reduced-motion view keeps the local crest.
                if (!portable && world.controls[10] > 0f && flight.motion > 0f) {
                    for (accent in world.accents) {
                        if (accent.age >= 2.5f || abs(distance - (3f + accent.age * 19f * flight.motion)) > stride * 0.55f) continue
                        val band = 15 - lane * 16 / lanes
                        if (accent.region == 0 && band >= 4 || accent.region == 1 && band !in 4..9 || accent.region == 2 && band < 10) continue
                        frontEdge = paint.mix(band * 2 / 16, 3, 0.35f,
                            0.8f + accent.strength * world.controls[10] * exp(-accent.age * 1.1f)).toArgb()
                        break
                    }
                }
                line(points[0], points[1], points[2], points[3], pixelCore, frontEdge)
            }
            if (!portable) palms(world, paint, width, height, z0, z1, pixelCore)
        }
        triangles = mesh.indexCount / 3
        copiedBytes = mesh.vertexCount * 12 + mesh.indexCount * 2
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
            val color = paint.color(1, 0.55f * intensity, distanceFade).toArgb()
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
}
