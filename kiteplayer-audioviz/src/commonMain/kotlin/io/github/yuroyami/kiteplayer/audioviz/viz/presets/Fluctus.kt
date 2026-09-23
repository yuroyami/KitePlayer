package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.*

/** Jordan Machado's floating spectrum sheet, reconstructed natively. See FluctusSurface. */
internal class Fluctus : Layered(
    name = "Fluctus", bucket = VizEnergy.Mid,
    kit = Kit(seed = 2_016L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f,
        shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off
    override val paintsWholeScreen: Boolean get() = true
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(0.075f)),
        VizDrive(VizDriver.Bands, VizProperty.Colour, response = VizResponse.envelope(0.075f)),
        VizDrive(VizDriver.Bands, VizProperty.Texture, response = VizResponse.envelope(0.083f)),
        silence = VizSilence.Still,
    )
    private val relief = VizParam("Surface relief", 0f, 2.5f, 1f)
    private val flow = VizParam("Flow speed", 0f, 2f, 1f)
    private val rotation = VizParam("Rotation speed", 0f, 2f, 1f)
    private val scale = VizParam("Scale", 0.6f, 1.4f, 1f)
    private val tilt = VizParam("Camera tilt", -20f, 20f, 0f)
    private val wire = VizParam("Wireframe", 0f, 2f, 0f).apply {
        step = 1f; choices = listOf("Auto", "Surface", "Wire")
    }
    private val shadow = VizParam("Shadow strength", 0f, 1.5f, 1f)
    private val paletteBlend = VizParam("Palette blend", 0f, 1f, 0f)
    override val params: List<VizParam> = listOf(relief, flow, rotation, scale, tilt, wire, shadow, paletteBlend)
    internal val surface = FluctusSurface()
    private val mesh = TriangleMesh(maxVertices = FluctusSurface.VERTICES, maxIndices = 15_000)
    private val lattice = Path()
    private val shadowMask by lazy { FluctusShadow() }

    override fun advance(state: VizRenderState) {
        surface.advance(state, relief.value, flow.value, rotation.value, wire.value.toInt())
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        surface.configure(relief.value, wire.value.toInt())
        surface.project(size.width, size.height, scale.value, tilt.value)
        val light = state.lightScale.coerceIn(0f, 1f)
        val floor = Color(0.977f * light, 0.969f * light, 0.994f * light)
        drawRect(floor)
        val horizon = surface.horizon.coerceIn(0f, size.height)
        if (horizon > 0f) drawRect(
            Brush.verticalGradient(0f to floor,
                0.80f to Color(0.953f * light, 0.946f * light, 0.994f * light),
                0.985f to Color(0.890f * light, 0.896f * light, 0.980f * light),
                1f to Color(0.710f * light, 0.748f * light, 0.936f * light), endY = horizon),
            size = Size(size.width, horizon),
        )
        if (shadow.value > 0f) shadowMask.run { draw(surface, shadow.value, light, floor) }
        mesh.clear()
        for (i in surface.height.indices) {
            var r = surface.red[i]; var g = surface.green[i]; var b = surface.blue[i]
            if (paletteBlend.value > 0f) {
                val colour = state.palette.cycled((surface.height[i] + 25f) / 50f,
                    saturation = 0.38f, value = 1f)
                val mix = paletteBlend.value
                r += (colour.red - r) * mix; g += (colour.green - g) * mix; b += (colour.blue - b) * mix
            }
            mesh.vertex(surface.projectedX[i], surface.projectedY[i],
                Color(r * light, g * light, b * light, 1f - surface.wireMix).toArgb())
        }
        for (triangle in surface.order) {
            val at = triangle * 3
            mesh.triangle(surface.indices[at], surface.indices[at + 1], surface.indices[at + 2])
        }
        if (surface.wireMix < 1f) drawMesh(mesh)
        if (surface.wireMix > 0f) {
            lattice.reset()
            val thickness = (min(size.width, size.height) / 850f).coerceIn(0.65f, 1.5f)
            fun edge(a: Int, b: Int) {
                lattice.moveTo(surface.projectedX[a], surface.projectedY[a])
                lattice.lineTo(surface.projectedX[b], surface.projectedY[b])
            }
            for (row in 0..50) for (column in 0..50) {
                val a = row * 51 + column
                if (column < 50) edge(a, a + 1)
                if (row < 50) edge(a, a + 51)
                if (column < 50 && row < 50) edge(a + 1, a + 51)
            }
            val base = Color(0.51f, 0.48f, 0.81f)
            val accent = state.palette.cycled(0.3f, saturation = 0.38f, value = 1f)
            val mix = paletteBlend.value
            drawPath(lattice, Color((base.red + (accent.red - base.red) * mix) * light,
                (base.green + (accent.green - base.green) * mix) * light,
                (base.blue + (accent.blue - base.blue) * mix) * light,
                surface.wireMix * 0.85f), style = Stroke(thickness))
        }
    }

    override fun onReset() { surface.reset() }
}

/** A union coverage mask: intersecting folds cast one shadow, never dark triangle seams. */
private class FluctusShadow {
    private val width = 192
    private val height = 128
    private val image = PixelImage(width, height)
    private val mask = FloatArray(width * height)
    private val blurred = FloatArray(width * height)
    private val x = FloatArray(FluctusSurface.VERTICES)
    private val y = FloatArray(FluctusSurface.VERTICES)

    fun DrawScope.draw(surface: FluctusSurface, strength: Float, light: Float, floor: Color) {
        var left = Float.POSITIVE_INFINITY; var top = left
        var right = Float.NEGATIVE_INFINITY; var bottom = right
        for (i in x.indices) {
            left = min(left, surface.shadowX[i]); right = max(right, surface.shadowX[i])
            top = min(top, surface.shadowY[i]); bottom = max(bottom, surface.shadowY[i])
        }
        val padding = (right - left) * 0.025f + 2f
        left = floor(left - padding); top = floor(top - padding)
        right = ceil(right + padding); bottom = ceil(bottom + padding)
        val w = (right - left).coerceAtLeast(1f); val h = (bottom - top).coerceAtLeast(1f)
        for (i in x.indices) {
            x[i] = (surface.shadowX[i] - left) / w * (width - 1)
            y[i] = (surface.shadowY[i] - top) / h * (height - 1)
        }
        mask.fill(0f)
        for (at in surface.indices.indices step 3) {
            val a = surface.indices[at]; val b = surface.indices[at + 1]; val c = surface.indices[at + 2]
            if (surface.wireMix < 1f) triangle(a, b, c, 1f - surface.wireMix)
            if (surface.wireMix > 0f) {
                line(a, b, surface.wireMix); line(b, c, surface.wireMix); line(c, a, surface.wireMix)
            }
        }
        // A fixed two-pixel penumbra only on the shadow, never on the sheet.
        for (row in 0 until height) for (col in 0 until width) {
            var sum = 0f
            for (dx in -2..2) sum += mask[row * width + (col + dx).coerceIn(0, width - 1)]
            blurred[row * width + col] = sum / 5f
        }
        for (row in 0 until height) for (col in 0 until width) {
            var sum = 0f
            for (dy in -2..2) sum += blurred[(row + dy).coerceIn(0, height - 1) * width + col]
            val opacity = (sum / 5f * strength * 0.52f).coerceIn(0f, 0.82f)
            val r = floor.red + (0.39f * light - floor.red) * opacity
            val g = floor.green + (0.45f * light - floor.green) * opacity
            val b = floor.blue + (0.72f * light - floor.blue) * opacity
            image.pixels[row * width + col] = Color(r, g, b).toArgb()
        }
        image.upload()
        drawImage(image.image, dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
    }

    private fun triangle(a: Int, b: Int, c: Int, coverage: Float) {
        val ax = x[a]; val ay = y[a]; val bx = x[b]; val by = y[b]; val cx = x[c]; val cy = y[c]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 0.0001f) return
        val minX = floor(min(ax, min(bx, cx))).toInt().coerceIn(0, width - 1)
        val maxX = ceil(max(ax, max(bx, cx))).toInt().coerceIn(0, width - 1)
        val minY = floor(min(ay, min(by, cy))).toInt().coerceIn(0, height - 1)
        val maxY = ceil(max(ay, max(by, cy))).toInt().coerceIn(0, height - 1)
        for (row in minY..maxY) for (col in minX..maxX) {
            val px = col + 0.5f; val py = row + 0.5f
            val u = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
            val v = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
            if (u >= 0f && v >= 0f && u + v <= 1.0001f) {
                val index = row * width + col
                mask[index] = max(mask[index], coverage)
            }
        }
    }

    private fun line(a: Int, b: Int, coverage: Float) {
        val dx = x[b] - x[a]; val dy = y[b] - y[a]
        val steps = ceil(max(abs(dx), abs(dy)) * 2f).toInt().coerceAtLeast(1)
        for (s in 0..steps) {
            val px = x[a] + dx * s / steps; val py = y[a] + dy * s / steps
            val ix = px.toInt(); val iy = py.toInt()
            for (oy in 0..1) for (ox in 0..1) {
                val col = ix + ox; val row = iy + oy
                if (col !in 0 until width || row !in 0 until height) continue
                val weight = (1f - abs(px - col)) * (1f - abs(py - row))
                val index = row * width + col
                mask[index] = max(mask[index], weight * coverage)
            }
        }
    }
}
