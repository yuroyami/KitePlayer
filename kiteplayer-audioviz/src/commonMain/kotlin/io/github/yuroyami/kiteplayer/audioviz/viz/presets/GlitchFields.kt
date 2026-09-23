package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import kotlin.math.*

/** Broad, overlapping colour forms. Every frequency has a visible piece of the surface. */
internal class GlitchFields {
    private val wheel = TriangleMesh(2_400, 7_200)
    private val halo = TriangleMesh(2_400, 7_200)
    private val ribbons = TriangleMesh(7_600, 24_000)
    private val points = TriangleMesh(4_536, 11_340)
    private val membrane = TriangleMesh(2_000, 12_000)
    private val position = FloatArray(2)
    private val reliefHistory = FloatArray(64)

    fun DrawScope.draw(scene: GlitchScene, density: Float, scale: Float, wheelAmount: Float,
        eclipseAmount: Float, ribbonAmount: Float, colourSpread: Float, light: Float, split: Float) {
        val unit = min(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        if (wheelAmount > 0.001f) {
            wheel.clear()
            val outer = hypot(size.width, size.height) * 0.72f * scale
            val lobes = 16
            val arcs = 8
            for (i in 0 until lobes) {
                var band = 0f
                for (bin in 0..3) band += scene.bands[i * 4 + bin] * 0.25f
                val angle = i * TAU / lobes + scene.turn * 0.32f
                val hue = scene.hue + i / lobes.toFloat() * colourSpread
                val strength = (0.28f + sqrt(band.coerceIn(0f, 1f)) * 1.10f) * wheelAmount * light
                val base = wheel.vertexCount
                for (ring in 0..3) {
                    val along = ring / 3f
                    val radius = outer * (0.008f + along * 1.06f)
                    val bend = (band - 0.4f) * along * along * 0.42f +
                        sin(i * 1.7f + scene.turn) * along * 0.08f
                    for (arc in 0..arcs) {
                        val across = arc / arcs.toFloat() * 2f - 1f
                        val turn = angle + across * TAU / lobes * 0.92f + bend
                        val edge = (1f - abs(across)).coerceAtLeast(0f).pow(0.55f)
                        val radial = if (ring == 0) 0.35f else 1f - along * 0.24f
                        wheel.vertex(cx + cos(turn) * radius, cy + sin(turn) * radius,
                            colour(hue + across * 0.035f, 0.90f, strength * radial, edge * 0.82f))
                    }
                }
                for (ring in 0..2) for (arc in 0 until arcs) {
                    val at = base + ring * (arcs + 1) + arc
                    wheel.quad(at, at + 1, at + arcs + 2, at + arcs + 1)
                }
            }
            drawMesh(wheel, BlendMode.Plus)
        }
        if (ribbonAmount > 0.001f) drawRibbons(scene, density, scale, ribbonAmount, colourSpread, light, split)
        if (eclipseAmount > 0.001f) {
            halo.clear()
            val radius = unit * scale * (0.24f + scene.bass * 0.15f + scene.lowAccent * 0.035f)
            // The dark aperture makes the light's contour legible over the colour fan.
            drawCircle(Color.Black.copy(alpha = (eclipseAmount * 0.88f).coerceIn(0f, 0.96f)),
                radius = radius * 0.965f, center = Offset(cx, cy))
            val count = 96
            for (i in 0 until count) {
                val at = i / count.toFloat()
                val band = scene.bands[(at * 63).toInt()].coerceIn(0f, 1f)
                val edge = radius * (1f + 0.04f * band)
                val width = unit * (0.018f + band * 0.06f)
                val a = at * TAU + scene.turn * 0.13f
                val b = (i + 1) * TAU / count + scene.turn * 0.13f
                val hue = scene.hue + at * colourSpread
                val value = (0.35f + 1.9f * sqrt(band) + 0.28f * scene.lowAccent) * eclipseAmount * light
                val start = halo.vertexCount
                for (r in 0..3) {
                    val distance = when (r) { 0 -> edge * 0.975f; 1 -> edge; 2 -> edge + width * 0.35f; else -> edge + width * 2.8f }
                    val opacity = when (r) { 0 -> 0f; 1 -> 0.94f; 2 -> 0.30f; else -> 0f }
                    val c = colour(hue, if (r == 1) 0.35f else 0.92f, value, opacity)
                    halo.vertex(cx + cos(a) * distance, cy + sin(a) * distance, c)
                    halo.vertex(cx + cos(b) * distance, cy + sin(b) * distance, c)
                }
                for (r in 0..2) {
                    val v = start + r * 2
                    halo.quad(v, v + 1, v + 3, v + 2)
                }
            }
            drawMesh(halo, BlendMode.Plus)
        }
    }

    private fun DrawScope.drawRibbons(scene: GlitchScene, density: Float, scale: Float,
        amount: Float, spread: Float, light: Float, split: Float) {
        ribbons.clear(); points.clear()
        val rows = (32 * density).roundToInt().coerceIn(10, 48)
        val columns = 72
        val unit = min(size.width, size.height)
        val spanX = size.width / unit * 1.35f
        val spanY = size.height / unit * 1.35f
        val dotMix = if (scene.composition == 2) 0.8f else 0.22f + scene.air * 0.30f
        val colourOffset = scene.hue + 0.15f
        // A short spatial filter keeps the moving audio history continuous across the sheet.
        // Current spectral detail still articulates each stripe and dot independently.
        for (i in reliefHistory.indices) {
            var sum = 0f
            for (tap in -2..2) sum += scene.history[(i + tap).coerceIn(0, 63)] * (3 - abs(tap))
            reliefHistory[i] = sum / 9f
        }
        membrane.clear()
        val surfaceRows = 24
        for (row in 0..surfaceRows) for (col in 0..columns) {
            val x = (col / columns.toFloat() - 0.5f) * spanX
            val y = (row / surfaceRows.toFloat() - 0.5f) * spanY
            project(x, y, scene, scale, unit, size.width, size.height)
            val crest = cos(x / spanX * TAU * 0.62f + sin(y * 2.1f + scene.travel * 0.22f) * 0.9f)
                .coerceAtLeast(0f).pow(0.5f)
            val band = scene.bands[(col * 63 / columns).coerceIn(0, 63)]
            val radiance = amount * light * (0.30f + scene.body * 1.6f + band * 1.2f)
            val tint = colourOffset + col / columns.toFloat() * spread * 0.42f + row * 0.006f
            membrane.vertex(position[0], position[1], colour(tint, 0.52f, radiance,
                crest * (0.24f + scene.level * 0.64f)))
        }
        for (row in 0 until surfaceRows) for (col in 0 until columns) {
            val at = row * (columns + 1) + col
            membrane.quad(at, at + 1, at + columns + 2, at + columns + 1)
        }
        drawMesh(membrane, BlendMode.Plus)
        // A broad corrugated sheet, with thickness articulated independently of its relief.
        for (row in 0 until rows) {
            val y = (row / (rows - 1f) - 0.5f) * spanY
            val hue = colourOffset + row / rows.toFloat() * spread * 0.6f
            val band = scene.bands[(row * 61 / rows).coerceIn(0, 63)]
            val strength = amount * light * (0.20f + 1.4f * band + 0.55f * scene.body)
            val thickness = (0.0024f + 0.014f * scene.body + 0.007f * scene.bodyAccent) * (1f - dotMix * 0.7f)
            val base = ribbons.vertexCount
            for (col in 0..columns) {
                val x = (col / columns.toFloat() - 0.5f) * spanX
                project(x, y - thickness, scene, scale, unit, size.width, size.height)
                ribbons.vertex(position[0], position[1], colour(hue + col * 0.003f, 0.72f, strength))
                project(x, y + thickness, scene, scale, unit, size.width, size.height)
                ribbons.vertex(position[0], position[1], colour(hue + col * 0.003f, 0.82f, strength * 0.84f))
            }
            for (col in 0 until columns) {
                val v = base + col * 2
                ribbons.quad(v, v + 1, v + 3, v + 2)
            }
        }
        drawMesh(ribbons, BlendMode.Plus)
        // Small colour-separated marks answer upper-frequency articulation even without bass.
        val across = (14 * density).roundToInt().coerceIn(5, 21)
        val down = (12 * density).roundToInt().coerceIn(4, 18)
        for (row in 0 until down) for (col in 0 until across) {
            val x = (col / (across - 1f) - 0.5f) * spanX
            val y = (row / (down - 1f) - 0.5f) * spanY
            project(x, y, scene, scale, unit, size.width, size.height)
            val band = scene.bands[(col * 63 / across).coerceIn(0, 63)]
            val radius = unit * (0.001f + 0.0035f * band + 0.0025f * scene.highAccent)
            val value = amount * light * dotMix * (0.05f + band * 0.65f)
            val hue = colourOffset + (row / down.toFloat() + col / across.toFloat()) * spread * 0.5f
            if (split > 0.001f) {
                val shift = unit * split * (0.002f + scene.bodyAccent * 0.007f)
                points.glow(position[0] - shift, position[1], radius, colour(hue + 0.08f, 0.85f, value * 0.5f), 5)
            }
            points.glow(position[0], position[1], radius, colour(hue, 0.58f, value), 5)
        }
        drawMesh(points, BlendMode.Plus)
    }

    private fun project(x: Float, y: Float, scene: GlitchScene, scale: Float,
        unit: Float, width: Float, height: Float) {
        val historyAt = ((sqrt(x * x + 0.0036f) - 0.06f) * 40f).coerceIn(0f, 63f)
        val hx = historyAt.toInt()
        val f = historyAt - hx
        val h0 = reliefHistory[max(0, hx - 1)]
        val h1 = reliefHistory[hx]
        val h2 = reliefHistory[min(63, hx + 1)]
        val h3 = reliefHistory[min(63, hx + 2)]
        val relief = (0.5f * (2f * h1 + (h2 - h0) * f +
            (2f * h0 - 5f * h1 + 4f * h2 - h3) * f * f +
            (3f * h1 - h0 - 3f * h2 + h3) * f * f * f)).coerceIn(0f, 1f)
        val wave = sin(x * 5f + scene.travel * 0.6f) * cos(y * 4f - scene.travel * 0.32f)
        val z = wave * (0.16f + scene.body * 0.50f) + relief * 0.48f
        val shear = sin(y * 37f + scene.turn) * scene.bodyAccent * 0.016f
        val perspective = 1.9f / (1.9f - z).coerceAtLeast(0.8f)
        val px = (x + shear) * perspective
        val py = (y * 0.93f - z * 0.18f) * perspective
        val rotation = scene.turn * 0.18f + 0.18f
        val c = cos(rotation); val s = sin(rotation)
        position[0] = width * 0.5f + (px * c - py * s) * unit * scale
        position[1] = height * 0.5f + (px * s + py * c) * unit * scale
    }

    private fun colour(hue: Float, saturation: Float, value: Float, opacity: Float = 1f): Int =
        Color.hsv(((hue % 1f + 1f) % 1f) * 360f, (saturation / (1f + max(0f, value - 0.75f) * 2.5f)).coerceIn(0f, 1f),
            value.coerceIn(0f, 1f), opacity.coerceIn(0f, 1f)).toArgb()

    private companion object { const val TAU = 6.2831855f }
}
