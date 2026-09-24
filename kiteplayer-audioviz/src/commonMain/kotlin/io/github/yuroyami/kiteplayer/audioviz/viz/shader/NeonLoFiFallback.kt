package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*

/** Portable background. The live mesh, city, history, clock and effects stay identical. */
internal class NeonLoFiFallback {
    private val path = Path()
    private val point = FloatArray(4)
    fun DrawScope.draw(world: NeonLoFiWorld, paint: NeonLoFiPaint, view: NeonLoFiView, lift: Float = 0f) {
        val h = size.height; val w = size.width; val horizon = h * world.flight.horizon
        val core = max(0.7f, min(w, h) / 650f)
        withTransform({ rotate(world.flight.bank * 180f / PI.toFloat(), Offset(w * 0.5f, horizon)) }) {
            // The only gradient in the scene: rose at the horizon under an indigo sky. It extends
            // beyond the viewport so a small bank never reveals an unpainted corner.
            drawRect(Brush.verticalGradient(listOf(paint.color(NeonLoFiPaint.LOW, 1f), paint.color(NeonLoFiPaint.LOW, 1f),
                paint.mix(NeonLoFiPaint.MID, NeonLoFiPaint.LOW, 0.5f, 1f), paint.color(NeonLoFiPaint.MID, 1f)),
                horizon - h * 0.5f, horizon), Offset(-w, -h), Size(w * 3, h + horizon))
            val center = Offset(w * 0.5f + view.sun[0] * h, horizon + view.sun[1] * h)
            val radius = view.sun[2] * h * (1f + 0.03f * world.kick)
            drawCircle(Brush.radialGradient(listOf(paint.mix(NeonLoFiPaint.HIGH, NeonLoFiPaint.CAP, 0.5f, 0.2f), Color.Transparent),
                center, radius * 1.3f), radius * 1.3f, center)
            val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))) }
            clipPath(disc) {
                val power = 1.5f + 0.35f * world.kick
                val brush = Brush.verticalGradient(listOf(paint.color(NeonLoFiPaint.HIGH, power), paint.color(NeonLoFiPaint.CAP, power)),
                    center.y - radius, center.y + radius)
                // The disc between its eight gaps, bass at the bottom, each gap as wide as its band is loud.
                var top = center.y - radius
                for (k in NeonLoFiWorld.GAPS - 1 downTo 0) {
                    val middle = center.y + (0.9f - k * 0.12f) * radius
                    val half = (0.012f + 0.05f * world.gaps[k]) * (1f - lift) * radius
                    if (middle - half > top) drawRect(brush, Offset(center.x - radius, top), Size(radius * 2, middle - half - top))
                    top = max(top, middle + half)
                }
                drawRect(brush, Offset(center.x - radius, top), Size(radius * 2, center.y + radius - top))
            }
            for (r in 0..3) {
                path.rewind()
                val measured = world.lanes[8 + r * 2]
                for (i in 0..64) {
                    val x = (i / 64f - 0.5f) * w
                    val y = horizon + h * (-0.30f - r * 0.043f + sin(x / h * (1.5f + r * 0.35f + world.texture * 0.25f) + r * 1.8f) * (0.02f + measured * 0.035f + world.air * 0.015f))
                    if (i == 0) path.moveTo(w * 0.5f + x, y) else path.lineTo(w * 0.5f + x, y)
                }
                drawPath(path, paint.mix(NeonLoFiPaint.FAR, NeonLoFiPaint.HIGH, r / 3f, world.controls[8] * (0.03f + measured * 0.1f)), style = Stroke(core))
            }
            for (ridge in 1 downTo 0) {
                path.rewind(); path.moveTo(-w, horizon)
                for (i in 0 until 256) {
                    val az = i / 255f
                    val opening = NeonLoFiHistory.smooth(0.03f, 0.28f, abs(az - 0.5f) * 2f)
                    val rise = (sqrt(world.profile[ridge * 256 + i]) * (if (ridge == 0) 0.30f else 0.20f) * world.controls[2] + opening * 0.008f) * (1f + world.regions.weights[1] * 0.45f)
                    val gap = (view.sun[2] / (w / h) * 2f).coerceIn(0.10f, 0.65f)
                    val signed = az * 2f - 1f
                    val projectedX = w * 0.5f + sign(signed) * (gap + abs(signed) * (1f - gap)) * w * 0.5f
                    path.lineTo(projectedX, horizon - h * rise)
                }
                path.lineTo(w * 2, horizon); path.lineTo(w * 2, h * 2); path.lineTo(-w, h * 2); path.close()
                drawPath(path, paint.color(NeonLoFiPaint.INK, 1f))
                drawPath(path, paint.color(NeonLoFiPaint.FAR, (if (ridge == 0) 0.6f else 0.35f) * world.controls[11]), style = Stroke(core))
            }
            drawRect(paint.color(NeonLoFiPaint.INK, 1f), Offset(-w, horizon), Size(w * 3, h * 2))
        }
        // Use the very same perspective and route as the live floor; no second camera transform.
        val f = world.flight
        val start = floor(f.travel / 4f).toInt()
        for (row in 60 downTo 1) {
            val z = (start + row) * 4f
            val distance = z - f.travel.toFloat()
            if (distance < 2f) continue
            val strength = (1f - NeonLoFiHistory.smooth(50f, 200f, distance)) * world.controls[11]
            f.project(-100f + f.road(z), 0f, z, w, h, point, 0)
            f.project(100f + f.road(z), 0f, z, w, h, point, 2)
            drawLine(paint.color(NeonLoFiPaint.FAR, strength * 0.14f), Offset(point[0], point[1]), Offset(point[2], point[3]), core)
        }
        for (column in -30..30) {
            val x = column * 4f
            f.project(x, 0f, f.travel.toFloat() + 2f, w, h, point, 0)
            f.project(x, 0f, f.travel.toFloat() + 180f, w, h, point, 2)
            drawLine(paint.color(NeonLoFiPaint.FAR, 0.12f * world.controls[11]), Offset(point[0], point[1]), Offset(point[2], point[3]), core)
        }
        // Continuous coast tint/reflection; a shoreline never switches the foreground road off.
        if (world.regions.weights[3] > 0.001f) {
            path.rewind()
            for (side in 0..1) for (row in if (side == 0) 1..80 else 80 downTo 1) {
                val z = f.travel.toFloat() + row * 3f - 1.5f
                f.project(f.road(z) + if (side == 0) 3.6f else 150f, 0f, z, w, h, point)
                if (side == 0 && row == 1) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
            }
            path.close()
            drawPath(path, Brush.verticalGradient(listOf(paint.color(NeonLoFiPaint.MID, 0.4f, world.regions.weights[3] * 0.85f),
                paint.color(NeonLoFiPaint.INK, 1f, world.regions.weights[3] * 0.85f)), horizon, h))
            clipPath(path) {
                for (i in 0..28) {
                    val y = horizon + (i + 0.5f).pow(1.5f) / 29f.pow(1.5f) * h * 0.35f
                    val span = (0.006f + i * 0.003f) * h
                    drawLine(paint.mix(NeonLoFiPaint.HIGH, NeonLoFiPaint.CAP, 0.5f, 0.6f + world.wave[i * 8] , world.regions.weights[3] * 0.5f),
                        Offset(w * 0.5f - span, y), Offset(w * 0.5f + span, y), core)
                }
            }
        }
        path.rewind()
        for (side in intArrayOf(-1, 1)) for (row in if (side == -1) 1..96 else 96 downTo 1) {
            val z = f.travel.toFloat() + row * 2.5f - 1f
            f.project(f.road(z) + side * 3.25f, 0f, z, w, h, point)
            if (side == -1 && row == 1) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
        }
        path.close(); drawPath(path, paint.color(NeonLoFiPaint.INK, 0.5f))
        for (side in intArrayOf(-1, 1)) {
            path.rewind()
            for (row in 1..96) {
                val z = f.travel.toFloat() + row * 2.5f - 1f
                f.project(f.road(z) + side * 3.25f, 0f, z, w, h, point)
                if (row == 1) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
            }
            drawPath(path, paint.color(NeonLoFiPaint.NEAR, (0.9f + 0.9f * world.kick * world.controls[10]) * world.controls[11]), style = Stroke(core))
        }
        val first = floor(f.travel / 7f).toInt()
        for (i in 1..30) {
            val z = (first + i) * 7f
            f.project(f.road(z), 0f, z, w, h, point, 0)
            f.project(f.road(z + 3f), 0f, z + 3f, w, h, point, 2)
            drawLine(paint.color(NeonLoFiPaint.WHITE, 0.85f), Offset(point[0], point[1]), Offset(point[2], point[3]), core)
        }
    }
}
