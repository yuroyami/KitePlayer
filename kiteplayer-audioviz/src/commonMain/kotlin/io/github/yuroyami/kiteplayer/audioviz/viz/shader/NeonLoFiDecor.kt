package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.*
import kotlin.math.*

/**
 * Small fixed pools: the skyline and the city, the stars, the rain, and the streetlamps.
 * The city is tied to distance; rain and star accents to held-safe local time.
 */
internal class NeonLoFiDecor {
    val city = TriangleMesh(6000, 9000)
    val sky = TriangleMesh(1200, 1800)
    /** Everything in front of the terraces: the lamp posts and the rain. */
    val rain = TriangleMesh(1024, 1536)
    /** Light added on top: the lamps' heads and the warm pools they throw on the road. */
    val glow = TriangleMesh(1024, 1536)
    private val p = FloatArray(8)
    fun build(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, portable: Boolean) {
        city.clear(); sky.clear(); rain.clear(); glow.clear()
        val f = world.flight; val seed = f.layout
        val cityWeight = world.regions.weights[2].coerceIn(0f, 1f)
        val near = floor((f.travel + 65) / 22).toInt()
        val horizon = height * f.horizon
        val core = max(0.75f, width / 1100f)
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
            quad(city, paint.packed(NeonLoFiPaint.INK, 1f), p)
            val band = (id + if (side > 0) 7 else 0).mod(16)
            val roof = paint.color(if (side > 0) NeonLoFiPaint.NEAR else NeonLoFiPaint.FAR, 0.4f + world.lanes[band] * 0.8f).toArgb()
            line(city, p[4], p[5], p[6], p[7], core, roof)
            val rows = if (portable) 4 else 8
            for (row in 0 until rows) for (col in 0..2) {
                val lane = (band + row + col * 3) % 16
                val strength = world.lanes[lane]
                val windowHeight = 0.24f + strength * 0.64f
                val xx = x - half * 0.7f + col * half * 0.7f
                val yy = (row + 0.8f) * buildingHeight / (rows + 1)
                f.project(xx, yy, z, width, height, p, 0)
                f.project(xx + half * 0.30f, yy + windowHeight, z, width, height, p, 2)
                val color = paint.packed(NeonLoFiPaint.WINDOW, (0.035f + strength * 1.2f + world.impulses[lane] * 0.4f) * cityWeight)
                rect(city, p[0], p[3], p[2], p[1], color)
            }
        }
        towers(world, paint, width, height, horizon, core, portable)
        stars(world, paint, width, height, horizon, portable)
        shootingStar(world, paint, width, height, core)
        lamps(world, paint, width, height, core, portable)
        val rainCount = if (portable) 20 else 48
        val density = (world.rain * world.controls[9]).coerceIn(0f, 1f)
        for (i in 0 until rainCount) {
            val admission = (density * rainCount - i).coerceIn(0f, 1f)
            if (admission <= 0f) continue
            val z = hash(i.toFloat(), 4f)
            val x = fract(hash(i + 0.7f, 7f) + seed * 0.13f + world.time * 0.007f * (1f + z)) * width
            val y = fract(hash(i + 2f, 11f) + world.time * (0.12f + z * 0.22f)) * height
            val length = height * (0.005f + z * 0.017f)
            rain.streak(x, y, x - length * 0.13f, y + length, max(0.6f, width / 1400f),
                paint.mix(NeonLoFiPaint.NEAR, NeonLoFiPaint.WHITE, 0.6f, 0.22f + world.air * 0.15f, admission * 0.7f).toArgb())
        }
    }

    /**
     * Twelve black towers on the horizon, one per note name from C on the left to B on the right,
     * six either side of the sun. A tower's windows light pale gold while its note sounds.
     */
    private fun towers(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, horizon: Float,
        core: Float, portable: Boolean) {
        val bank = world.flight.bank
        val c = cos(bank); val s = sin(bank)
        val body = paint.packed(NeonLoFiPaint.INK, 1f)
        val columns = 2
        val rows = if (portable) 4 else 7
        val towerWidth = min(width * 0.034f, height * 0.035f)
        for (note in 0..11) {
            val left = note < 6
            val x = width * (if (left) 0.07f + note * 0.052f else 0.67f + (note - 6) * 0.052f)
            val tall = height * (0.05f + 0.07f * hash(note.toFloat(), 17f))
            // In screen space round the horizon, turned with the road's bank.
            fun corner(dx: Float, dy: Float, at: Int) {
                val ox = x - width * 0.5f + dx; val oy = dy
                p[at] = width * 0.5f + ox * c - oy * s
                p[at + 1] = horizon + ox * s + oy * c
            }
            corner(-towerWidth * 0.5f, 0f, 0); corner(towerWidth * 0.5f, 0f, 2)
            corner(towerWidth * 0.5f, -tall, 4); corner(-towerWidth * 0.5f, -tall, 6)
            quad(city, body, p)
            line(city, p[4], p[5], p[6], p[7], core, paint.color(NeonLoFiPaint.FAR, 0.35f).toArgb())
            val lit = world.notes[note]
            val window = paint.packed(NeonLoFiPaint.WINDOW, 0.06f + 1.3f * lit)
            for (row in 0 until rows) for (col in 0 until columns) {
                val wx = -towerWidth * 0.3f + col * towerWidth * 0.36f
                val wy = -tall * (row + 1f) / (rows + 1.5f)
                corner(wx, wy, 0); corner(wx + towerWidth * 0.22f, wy, 2)
                corner(wx + towerWidth * 0.22f, wy - tall * 0.45f / (rows + 1.5f), 4); corner(wx, wy - tall * 0.45f / (rows + 1.5f), 6)
                quad(city, window, p)
            }
        }
    }

    /** Stars in the upper sky, which a hat makes twinkle. */
    private fun stars(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, horizon: Float, portable: Boolean) {
        val f = world.flight
        for (i in 0 until if (portable) 16 else 64) {
            val x = fract(hash(i.toFloat(), 21f) + f.layout * 0.18f) * width
            val y = (0.045f + hash(i + 8f, 3f) * 0.3f) * height * (f.horizon / NeonLoFiFlight.HORIZON)
            val sunR = min(0.115f * world.controls[7] * (1f + 0.04f * world.slowLevel), width / height * 0.37f) * height
            if (hypot(x - width * 0.5f, y - (horizon - 0.175f * height)) < sunR * 1.15f) continue
            val lane = 8 + i % 8
            val signal = 0.8f * world.lanes[lane] + 0.2f * world.air
            val twinkle = world.hat * world.controls[10] * (if (hash(i + 3f, 9f) > 0.5f) 3f else 1f)
            val r = max(0.45f, min(width, height) / 950f) * (0.8f + signal * 0.8f + twinkle)
            val dx = x - width * 0.5f; val dy = y - horizon
            val sx = width * 0.5f + dx * cos(f.bank) - dy * sin(f.bank)
            val sy = horizon + dx * sin(f.bank) + dy * cos(f.bank)
            sky.spark(sx, sy, r * (1.3f + twinkle * 2f), r * 0.45f,
                paint.mix(NeonLoFiPaint.WHITE, NeonLoFiPaint.WINDOW, 0.4f, 0.12f + signal * 0.35f + twinkle * 0.6f).toArgb())
        }
    }

    /** A snare's shooting star, across the upper sky in under a second. */
    private fun shootingStar(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, core: Float) {
        val age = world.starAge
        if (age < 0f) return
        val t = age / NeonLoFiWorld.STAR_SECONDS
        val seed = world.starSeed
        val fromX = width * (0.12f + 0.45f * seed); val fromY = height * (0.05f + 0.12f * seed)
        val headX = fromX + width * 0.45f * t; val headY = fromY + height * 0.14f * t
        val tail = 0.4f
        val tailX = fromX + width * 0.45f * max(0f, t - tail); val tailY = fromY + height * 0.14f * max(0f, t - tail)
        val light = sin(PI.toFloat() * t)
        sky.streak(tailX, tailY, headX, headY, core * 3.2f, paint.color(NeonLoFiPaint.WHITE, 1.2f * light).toArgb())
        sky.glow(headX, headY, max(4f, height * 0.018f), paint.color(NeonLoFiPaint.WHITE, 1.3f * light).toArgb(), 8)
    }

    /**
     * Streetlamps in pairs, one pair a bar: an amber head on a black post, and a warm pool of light
     * on the road below. On a wet road the pools stretch toward the viewer, as reflections do.
     */
    private fun lamps(world: NeonLoFiWorld, paint: NeonLoFiPaint, width: Float, height: Float, core: Float, portable: Boolean) {
        val f = world.flight
        val spacing = NeonLoFiFlight.DASH * NeonLoFiFlight.LAMP_EVERY
        val first = ceil((f.travel.toFloat() + 3f) / spacing).toInt()
        val count = if (portable) 3 else 5
        val post = paint.packed(NeonLoFiPaint.INK, 1.6f)
        val wet = world.rain.coerceIn(0f, 1f)
        val focal = height * NeonLoFiFlight.FOCAL
        for (k in first + count - 1 downTo first) {
            val z = k * spacing
            val distance = z - f.travel.toFloat()
            if (distance < 1.5f) continue
            val fade = 1f - NeonLoFiHistory.smooth(110f, 150f, distance)
            if (fade <= 0f) continue
            for (side in intArrayOf(-1, 1)) {
                val baseX = f.road(z) + side * 3.55f
                f.project(baseX, 0f, z, width, height, p, 0)
                f.project(baseX, LAMP_HEIGHT, z, width, height, p, 2)
                f.project(baseX - side * 0.9f, LAMP_HEIGHT - 0.1f, z, width, height, p, 4)
                val thick = max(core, 0.09f * focal / distance)
                line(rain, p[0], p[1], p[2], p[3], thick, post)
                line(rain, p[2], p[3], p[4], p[5], thick * 0.8f, post)
                // A lamp's head stays a lamp as it passes close, not a flare across the screen.
                val head = (0.28f * focal / distance).coerceIn(1.2f, max(1.2f, height * 0.012f))
                glow.glow(p[4], p[5], head * 2.4f, paint.color(NeonLoFiPaint.LAMP, 0.9f * fade).toArgb(), 12)
                // The pool: a flattened disc of warm light on the road under the head.
                val poolX = baseX - side * 1.6f
                f.project(poolX, 0f, z, width, height, p, 0)
                val middle = glow.vertex(p[0], p[1], paint.color(NeonLoFiPaint.LAMP, 0.32f * fade, 1f).toArgb())
                val rim = glow.vertexCount
                for (a in 0 until POOL_SIDES) {
                    val angle = 2f * PI.toFloat() * a / POOL_SIDES
                    val reach = 2.3f * (1f + 1.8f * wet * max(0f, -sin(angle)))
                    f.project(poolX + cos(angle) * 1.9f, 0f, max(z + sin(angle) * reach, f.travel.toFloat() + 1.2f), width, height, p, 2)
                    glow.vertex(p[2], p[3], 0)
                }
                for (a in 0 until POOL_SIDES) glow.triangle(middle, rim + a, rim + (a + 1) % POOL_SIDES)
            }
        }
    }

    companion object {
        const val LAMP_HEIGHT = 4.2f
        const val POOL_SIDES = 12
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
