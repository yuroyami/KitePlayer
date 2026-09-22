package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.fluid.FluidGrid
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Invisible moving sinks: their surroundings carry every visible part of the effect. */
internal class SmokeVortices {
    val x = floatArrayOf(0.27f, 0.73f)
    val y = floatArrayOf(0.48f, 0.48f)
    var phase = 0.0; private set
    var activity = 0f; private set
    var radius = 0.055f; private set
    private var accent = 0f
    private val travelX = FloatArray(2)
    private val travelY = FloatArray(2)
    private var carried = FloatArray(0)
    var sampleX = 0f; private set
    var sampleY = 0f; private set

    fun advance(state: VizRenderState, orbit: Float, size: Float, hit: Float) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f)
        val target = (0.55f * state.drive + 0.45f * state.frame.density) * state.frame.audible
        activity += (target - activity) * (1f - exp(-dt / if (target > activity) 0.18f else 0.8f))
        accent = maxOf(accent * exp(-dt / 0.3f), hit * state.frame.audible)
        phase += dt * (0.13 + 0.85 * activity + 0.22 * accent) * orbit *
            state.frame.audible * state.motionScale
        radius = size * (0.92f + 0.15f * state.frame.bassRel + 0.12f * accent)
        // Opposed ellipses keep the pair apart, even in portrait. Music changes the pace, not time jumps.
        for (hole in 0..1) {
            val way = if (hole == 0) -1f else 1f
            val beforeX = x[hole]; val beforeY = y[hole]
            x[hole] = 0.5f + way * (0.23f + 0.03f * activity) * cos(phase).toFloat()
            y[hole] = 0.48f + way * 0.19f * sin(phase).toFloat()
            if (dt > 0f) {
                travelX[hole] = ((x[hole] - beforeX) / dt).coerceIn(-0.6f, 0.6f)
                travelY[hole] = ((y[hole] - beforeY) / dt).coerceIn(-0.6f, 0.6f)
            }
        }
    }

    fun stir(grid: FluidGrid, dt: Float, count: Int, aspect: Float, curl: Float, pull: Float,
        motion: Float) {
        if (dt <= 0f || count == 0 || motion <= 0f) return
        val short = min(1f, aspect)
        val reach = radius * 4.8f
        val force = dt.coerceAtMost(0.1f) * (0.3f + 1.7f * activity + 0.7f * accent) * motion
        for (hole in 0 until count) {
            val way = if (hole == 0) 1f else -1f
            val fromX = ((x[hole] - reach * short / aspect) * grid.width).toInt().coerceAtLeast(0)
            val toX = ((x[hole] + reach * short / aspect) * grid.width).toInt().coerceAtMost(grid.width - 1)
            val fromY = ((y[hole] - reach * short) * grid.height).toInt().coerceAtLeast(0)
            val toY = ((y[hole] + reach * short) * grid.height).toInt().coerceAtMost(grid.height - 1)
            for (cy in fromY..toY) for (cx in fromX..toX) {
                val dx = ((cx + 0.5f) / grid.width - x[hole]) * aspect / short
                val dy = ((cy + 0.5f) / grid.height - y[hole]) / short
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                val r = distance / radius
                if (r > 4.8f) continue
                val falloff = exp(-r * r * 0.22f)
                val at = grid.index(cx, cy)
                val acceleration = force * falloff / distance
                // Tangential acceleration bends real smoke into a wake, not just a drawn ring.
                grid.velocityX[at] += (-dy * way * curl * 1.8f - dx * pull * 0.7f) *
                    acceleration * grid.width * short / aspect
                grid.velocityX[at] += travelX[hole] * grid.width * force * falloff * 1.5f
                grid.velocityY[at] += travelY[hole] * grid.height * force * falloff * 1.5f
                grid.velocityY[at] += (dx * way * curl * 1.8f - dy * pull * 0.7f) *
                    acceleration * grid.height * short
            }
        }
    }

    /**
     * Compressible smoke transport after the incompressible velocity solve. The solver projects
     * inward divergence away, so a sink cannot be represented by a velocity splat alone. Carry
     * density along the converging characteristics, including their Jacobian: it gathers into a
     * distorted shoulder before crossing the absorbing interior. There is no painted horizon.
     */
    fun condense(grid: FluidGrid, dt: Float, count: Int, aspect: Float, curl: Float, pull: Float,
        capture: Float, motion: Float) {
        if (dt <= 0f || count == 0 || motion <= 0f) return
        val elapsed = dt.coerceAtMost(0.05f) * motion
        val short = min(1f, aspect)
        val dye = grid.dye[0]
        if (carried.size != dye.size) carried = FloatArray(dye.size)
        val inward = pull * (0.7f + 2.8f * activity + 1.2f * accent)
        for (hole in 0 until count) {
            dye.copyInto(carried)
            val way = if (hole == 0) 1f else -1f
            for (cy in 0 until grid.height) for (cx in 0 until grid.width) {
                val dx = ((cx + 0.5f) / grid.width - x[hole]) * aspect / short
                val dy = ((cy + 0.5f) / grid.height - y[hole]) / short
                val r2 = (dx * dx + dy * dy) / (radius * radius)
                if (r2 > 25f) continue
                val r = sqrt(r2)
                val falloff = exp(-r2 * 0.14f)
                val t = ((r - 0.9f) / 1.1f).coerceIn(0f, 1f)
                val brake = 0.18f + 0.82f * t * t * (3f - 2f * t)
                val derivative = 0.82f * 6f * t * (1f - t) / 1.1f
                val expand = exp(elapsed * inward * falloff * brake)
                val turn = -way * elapsed * curl * (0.8f + 3f * activity) * falloff
                val c = cos(turn); val s = sin(turn)
                val sx = x[hole] + (dx * c - dy * s) * expand * short / aspect
                val sy = y[hole] + (dx * s + dy * c) * expand * short
                val compression = inward * falloff * ((2f - 0.28f * r2) * brake + r * derivative)
                val absorption = absorption(r2, capture)
                val at = grid.index(cx, cy)
                dye[at] = (sample(grid, carried, sx, sy) *
                    exp(elapsed * (compression - absorption))).coerceIn(0f, 12f)
            }
        }
    }

    /** Embers enter the same converging flow and fade as they cross its absorbing interior. */
    fun carryEmbers(pool: Particles, dt: Float, count: Int, aspect: Float, curl: Float,
        pull: Float, capture: Float, motion: Float) {
        if (dt <= 0f || motion <= 0f || count == 0) return
        val short = min(1f, aspect)
        val force = dt.coerceAtMost(0.05f) * motion * (0.3f + 1.7f * activity + 0.7f * accent)
        for (slot in 0 until pool.capacity) {
            if (pool.life[slot] <= 0f) continue
            for (hole in 0 until count) {
                val dx = (pool.x[slot] - x[hole]) * aspect / short
                val dy = (pool.y[slot] - y[hole]) / short
                val r2 = (dx * dx + dy * dy) / (radius * radius)
                if (r2 > 25f) continue
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                val weight = exp(-r2 * 0.22f)
                val way = if (hole == 0) 1f else -1f
                pool.velocityX[slot] += (-dy * way * curl - dx * pull) / distance * force * weight * short / aspect
                pool.velocityY[slot] += (dx * way * curl - dy * pull) / distance * force * weight * short
                pool.life[slot] *= exp(-dt * motion * absorption(r2, capture))
            }
        }
    }

    private fun absorption(radiusSquared: Float, amount: Float): Float =
        amount * (200f + 300f * activity) * exp(-radiusSquared * radiusSquared / 0.3f)

    private fun sample(grid: FluidGrid, dye: FloatArray, x: Float, y: Float): Float {
        val px = (x * grid.width - 0.5f).coerceIn(0f, grid.width - 1f)
        val py = (y * grid.height - 0.5f).coerceIn(0f, grid.height - 1f)
        val left = px.toInt(); val top = py.toInt()
        val right = (left + 1).coerceAtMost(grid.width - 1)
        val bottom = (top + 1).coerceAtMost(grid.height - 1)
        val tx = px - left; val ty = py - top
        val a = dye[grid.index(left, top)] * (1f - tx) + dye[grid.index(right, top)] * tx
        val b = dye[grid.index(left, bottom)] * (1f - tx) + dye[grid.index(right, bottom)] * tx
        return a * (1f - ty) + b * ty
    }

    /** Backward sampling of the smoke sheet bends existing light around each core. */
    fun lens(px: Float, py: Float, count: Int, aspect: Float, amount: Float) {
        sampleX = px; sampleY = py
        if (amount <= 0f) return
        val short = min(1f, aspect)
        for (hole in 0 until count) {
            val dx = (sampleX - x[hole]) * aspect / short
            val dy = (sampleY - y[hole]) / short
            val r2 = (dx * dx + dy * dy) / (radius * radius)
            if (r2 > 25f) continue
            val weight = exp(-r2 * 0.18f) * amount
            // Bend the surrounding material without sampling bright exterior smoke into the sink.
            val t = (sqrt(r2) - 0.75f).coerceIn(0f, 1f)
            val edge = t * t * (3f - 2f * t)
            val turn = weight * edge * (1.1f + 0.55f * activity) * if (hole == 0) 1f else -1f
            val expand = 1f + weight * edge * 0.85f
            val c = cos(turn); val s = sin(turn)
            sampleX = x[hole] + (dx * c - dy * s) * expand * short / aspect
            sampleY = y[hole] + (dx * s + dy * c) * expand * short
        }
    }

    fun reset() {
        x[0] = 0.27f; x[1] = 0.73f; y.fill(0.48f)
        phase = 0.0; activity = 0f; accent = 0f; radius = 0.055f
        travelX.fill(0f); travelY.fill(0f); carried.fill(0f)
    }
}
