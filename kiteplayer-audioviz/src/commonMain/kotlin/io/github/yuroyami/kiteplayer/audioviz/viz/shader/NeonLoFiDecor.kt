package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.*
import kotlin.math.*

/** Small fixed pools. The city is tied to distance; rain and star accents to held-safe local time. */
internal class NeonLoFiDecor {
    val city = TriangleMesh(6000, 9000)
    val sky = TriangleMesh(1200, 1800)
    val rain = TriangleMesh(256, 384)
    private val p = FloatArray(8)
    fun build(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, portable: Boolean) {
        city.clear(); sky.clear(); rain.clear()
        val f = world.flight; val seed = f.layout
        val cityWeight = world.regions.weights[2].coerceIn(0f, 1f)
        val near = floor((f.travel + 65) / 22).toInt()
        val horizon = height * NeonLoFiFlight.HORIZON
        // The skyline has three depths and separate facade groups, never one bobbing silhouette.
        for (depth in (if (portable) 3 else 9) downTo 0) for (side in intArrayOf(-1, 1)) {
            val id = near + depth
            val z = id * 22f
            val noise = hash(id.toFloat(), side.toFloat())
            val aspectFit = height * NeonLoFiFlight.FOCAL / f.horizontalFocal(width, height)
            val x = f.road(z) + side * (10f + noise * 18f) * aspectFit
            val distance = z - f.travel.toFloat()
            val distanceWeight = NeonLoFiHistory.smooth(86f, 120f, distance) * (1f - NeonLoFiHistory.smooth(190f, 250f, distance))
            val buildingHeight = (7f + floor(noise * 4f) * 3f) * cityWeight * distanceWeight
            if (buildingHeight < 0.02f) continue
            val half = (1.4f + noise * 2f) * aspectFit
            f.project(x - half, 0f, z, width, height, p, 0)
            f.project(x + half, 0f, z, width, height, p, 2)
            f.project(x + half, buildingHeight, z, width, height, p, 4)
            f.project(x - half, buildingHeight, z, width, height, p, 6)
            quad(city, paint.packed(0, 0.045f), p)
            val band = (id + if (side > 0) 7 else 0).mod(16)
            val roof = paint.color(if (side > 0) 2 else 1, 0.4f + world.lanes[band] * 0.8f).toArgb()
            line(city, p[4], p[5], p[6], p[7], max(0.75f, width / 1100f), roof)
            val rows = if (portable) 4 else 8
            for (row in 0 until rows) for (col in 0..2) {
                val lane = (band + row + col * 3) % 16
                val strength = world.lanes[lane]
                val windowHeight = 0.24f + strength * 0.64f
                val xx = x - half * 0.7f + col * half * 0.7f
                val yy = (row + 0.8f) * buildingHeight / (rows + 1)
                f.project(xx, yy, z, width, height, p, 0)
                f.project(xx + half * 0.30f, yy + windowHeight, z, width, height, p, 2)
                val color = paint.packed(lane / 6, (0.035f + strength * 1.5f + world.impulses[lane] * 0.4f) * cityWeight)
                rect(city, p[0], p[3], p[2], p[1], color)
            }
        }
        for (i in 0 until if (portable) 16 else 64) {
            val x = fract(hash(i.toFloat(), 21f) + seed * 0.18f) * width
            val y = (0.045f + hash(i + 8f, 3f) * 0.35f) * height
            val sunR = min(0.0877f * world.controls[7] * (1f + 0.04f * world.slowLevel), width / height * 0.37f) * height
            if (hypot(x - width * 0.5f, y - height * 0.305f) < sunR * 1.1f) continue
            val lane = 8 + i % 8
            val signal = 0.8f * world.lanes[lane] + 0.2f * world.air
            val accent = world.impulses[lane] * world.controls[10]
            val r = max(0.45f, min(width, height) / 950f) * (0.8f + signal * 1.2f + accent)
            val dx = x - width * 0.5f; val dy = y - horizon
            val sx = width * 0.5f + dx * cos(f.bank) - dy * sin(f.bank)
            val sy = horizon + dx * sin(f.bank) + dy * cos(f.bank)
            sky.spark(sx, sy, r * (1.3f + accent * 2f), r * 0.45f,
                paint.packed(2, 0.10f + signal * 0.8f + accent * 0.4f))
        }
        val rainCount = if (portable) 20 else 48
        val density = (world.rain * world.controls[9]).coerceIn(0f, 1f)
        for (i in 0 until rainCount) {
            val admission = (density * rainCount - i).coerceIn(0f, 1f)
            if (admission <= 0f) continue
            val z = hash(i.toFloat(), 4f)
            val x = fract(hash(i + 0.7f, 7f) + seed * 0.13f + world.time * 0.007f * (1f + z)) * width
            val y = fract(hash(i + 2f, 11f) + world.time * (0.12f + z * 0.22f)) * height
            val length = height * (0.005f + z * 0.017f)
            rain.streak(x, y, x - length * 0.13f, y + length, max(0.6f, width / 1100f),
                paint.color(2, 0.17f + world.air * 0.15f, admission * 0.6f).toArgb())
        }
    }
    companion object {
        fun hash(x: Float, y: Float): Float = fract(sin(x * 127.1f + y * 311.7f) * 43758.5453f)
        fun fract(x: Float): Float = x - floor(x)
        fun rect(mesh: TriangleMesh, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            val v = mesh.vertex(left, top, color); mesh.vertex(right, top, color)
            mesh.vertex(right, bottom, color); mesh.vertex(left, bottom, color); mesh.quad(v, v + 1, v + 2, v + 3)
        }
        fun quad(mesh: TriangleMesh, color: Int, p: FloatArray) {
            val v = mesh.vertex(p[0], p[1], color)
            for (i in 1..3) mesh.vertex(p[i * 2], p[i * 2 + 1], color)
            mesh.quad(v, v + 1, v + 2, v + 3)
        }
        fun line(mesh: TriangleMesh, ax: Float, ay: Float, bx: Float, by: Float, width: Float, color: Int) {
            val dx = bx - ax; val dy = by - ay; val l = hypot(dx, dy).coerceAtLeast(0.001f)
            val nx = -dy / l * width * 0.5f; val ny = dx / l * width * 0.5f
            val v = mesh.vertex(ax + nx, ay + ny, color); mesh.vertex(bx + nx, by + ny, color)
            mesh.vertex(bx - nx, by - ny, color); mesh.vertex(ax - nx, ay - ny, color)
            mesh.quad(v, v + 1, v + 2, v + 3)
        }
    }
}
