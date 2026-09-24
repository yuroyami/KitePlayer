package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import kotlin.math.floor

/**
 * Ashima Arts' 3D simplex noise, `snoise(vec3)` from webgl-noise as the glsl-noise package ships it,
 * ported to Kotlin for the drawings that run it on the processor.
 *
 * Copyright (C) 2011 Ashima Arts. Distributed under the MIT License.
 * Author Ian McEwan, https://github.com/ashima/webgl-noise.
 *
 * The permutation polynomial and the 289 gradients are tables built with the shader's own float32
 * arithmetic. For every lattice value the tables give the same result as the shader's `mod289`,
 * `permute` and gradient steps, so only the lookups are faster.
 */
internal object AshimaNoise {

    private const val CX = 1f / 6f
    private const val CY = 1f / 3f

    /** `permute(x) = mod289((x * 34 + 1) * x)` for every x from 0 to 288. */
    private val permute = IntArray(RING) { ((it * 34 + 1) * it) % RING }

    // The 7 by 7 gradients mapped onto an octahedron, normalised with the shader's taylorInvSqrt.
    private val gradientX = FloatArray(RING)
    private val gradientY = FloatArray(RING)
    private val gradientZ = FloatArray(RING)

    init {
        val n = 0.142857142857f
        val nsx = n * 2f
        val nsy = n * 0.5f - 1f
        val nsz = n
        for (p in 0 until RING) {
            val value = p.toFloat()
            val j = value - 49f * floor(value * nsz * nsz)
            val xi = floor(j * nsz)
            val yi = floor(j - 7f * xi)
            val x = xi * nsx + nsy
            val y = yi * nsx + nsy
            val h = 1f - kotlin.math.abs(x) - kotlin.math.abs(y)
            val sh = if (h <= 0f) -1f else 0f
            val gx = x + (floor(x) * 2f + 1f) * sh
            val gy = y + (floor(y) * 2f + 1f) * sh
            val norm = 1.79284291400159f - 0.85373472095314f * (gx * gx + gy * gy + h * h)
            gradientX[p] = gx * norm
            gradientY[p] = gy * norm
            gradientZ[p] = h * norm
        }
    }

    /** The noise at ([vx], [vy], [vz]), between about -1 and 1. */
    fun simplex3(vx: Float, vy: Float, vz: Float): Float {
        // First corner.
        val skew = vx * CY + vy * CY + vz * CY
        val ix = floor(vx + skew)
        val iy = floor(vy + skew)
        val iz = floor(vz + skew)
        val unskew = ix * CX + iy * CX + iz * CX
        val x0 = vx - ix + unskew
        val y0 = vy - iy + unskew
        val z0 = vz - iz + unskew

        // Other corners: g = step(x0.yzx, x0.xyz), i1 = min(g, l.zxy), i2 = max(g, l.zxy).
        val gx = if (x0 >= y0) 1 else 0
        val gy = if (y0 >= z0) 1 else 0
        val gz = if (z0 >= x0) 1 else 0
        val i1x = minOf(gx, 1 - gz)
        val i1y = minOf(gy, 1 - gx)
        val i1z = minOf(gz, 1 - gy)
        val i2x = maxOf(gx, 1 - gz)
        val i2y = maxOf(gy, 1 - gx)
        val i2z = maxOf(gz, 1 - gy)

        val x1 = x0 - i1x + CX
        val y1 = y0 - i1y + CX
        val z1 = z0 - i1z + CX
        val x2 = x0 - i2x + CY
        val y2 = y0 - i2y + CY
        val z2 = z0 - i2z + CY
        val x3 = x0 - 0.5f
        val y3 = y0 - 0.5f
        val z3 = z0 - 0.5f

        // Permutations, on the lattice taken modulo 289.
        val lx = ring(ix)
        val ly = ring(iy)
        val lz = ring(iz)
        val p0 = corner(lx, ly, lz)
        val p1 = corner(lx + i1x, ly + i1y, lz + i1z)
        val p2 = corner(lx + i2x, ly + i2y, lz + i2z)
        val p3 = corner(lx + 1, ly + 1, lz + 1)

        return 42f * (
            falloff(x0, y0, z0, p0) + falloff(x1, y1, z1, p1) +
                falloff(x2, y2, z2, p2) + falloff(x3, y3, z3, p3)
            )
    }

    /** `permute(permute(permute(z) + y) + x)`, each argument at most one past the ring. */
    private fun corner(x: Int, y: Int, z: Int): Int =
        permute[(permute[(permute[z % RING] + y) % RING] + x) % RING]

    private fun falloff(x: Float, y: Float, z: Float, p: Int): Float {
        var m = 0.6f - (x * x + y * y + z * z)
        if (m <= 0f) return 0f
        m *= m
        return m * m * (gradientX[p] * x + gradientY[p] * y + gradientZ[p] * z)
    }

    private fun ring(value: Float): Int {
        val remainder = value.toInt() % RING
        return if (remainder < 0) remainder + RING else remainder
    }

    private const val RING = 289
}
