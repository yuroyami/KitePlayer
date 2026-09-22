package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pack one camera basis on the host instead of rebuilding it inside every distance query. */
internal object OdysseyCamera {
    fun write(out: FloatArray, phase: FloatArray, curve: Float, x: Float, y: Float,
        yaw: Float, pitch: Float, bank: Float) {
        fun routeX(z: Float) = (11f * sin(phase[0] + z * 0.025f) + 3.5f * sin(phase[1] + z * 0.061f)) * curve
        fun routeY(z: Float) = 4f * sin(phase[2] + z * 0.021f) * curve
        val originX = routeX(0f); val originY = routeY(0f)
        var fx = routeX(7f) - originX; var fy = routeY(7f) - originY; var fz = 7f
        val length = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= length; fy /= length; fz /= length
        val horizontal = sqrt(fx * fx + fz * fz)
        var rx = fz / horizontal; var ry = 0f; var rz = -fx / horizontal
        var ux = fy * rz; var uy = fz * rx - fx * rz; var uz = -fy * rx
        val cy = cos(yaw); val sy = sin(yaw)
        val lx = fx * cy + rx * sy; val ly = fy * cy + ry * sy; val lz = fz * cy + rz * sy
        rx = rx * cy - fx * sy; ry = ry * cy - fy * sy; rz = rz * cy - fz * sy
        val cp = cos(pitch); val sp = sin(pitch)
        fx = lx * cp + ux * sp; fy = ly * cp + uy * sp; fz = lz * cp + uz * sp
        ux = ux * cp - lx * sp; uy = uy * cp - ly * sp; uz = uz * cp - lz * sp
        val cb = cos(bank); val sb = sin(bank)
        out[0] = originX + x; out[1] = originY + y; out[2] = 0f
        out[3] = fx; out[4] = fy; out[5] = fz
        out[6] = rx * cb + ux * sb; out[7] = ry * cb + uy * sb; out[8] = rz * cb + uz * sb
        out[9] = ux * cb - rx * sb; out[10] = uy * cb - ry * sb; out[11] = uz * cb - rz * sb
    }
}
