package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
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
    private val mesh = TriangleMesh(maxVertices = FluctusSurface.VERTICES, maxIndices = FluctusSurface.TRIANGLES * 3)
    private val lattice = Path()
    private val shadowOutline = FluctusShadow()

    override fun advance(state: VizRenderState) {
        surface.advance(state, relief.value, flow.value, rotation.value, wire.value.toInt())
    }

    /** The floor and its horizon. They are smooth, so the echo layer's resolution is enough. */
    override fun DrawScope.drawEcho(state: VizRenderState) {
        val light = state.lightScale.coerceIn(0f, 1f)
        val floor = floorColour(light)
        drawRect(floor)
        surface.configure(relief.value, wire.value.toInt())
        surface.project(size.width, size.height, scale.value, tilt.value)
        val horizon = surface.horizon.coerceIn(0f, size.height)
        if (horizon > 0f) drawRect(
            Brush.verticalGradient(0f to floor,
                0.80f to Color(0.953f * light, 0.946f * light, 0.994f * light),
                0.985f to Color(0.890f * light, 0.896f * light, 0.980f * light),
                1f to Color(0.710f * light, 0.748f * light, 0.936f * light), endY = horizon),
            size = Size(size.width, horizon),
        )
    }

    /** The shadow and the sheet, in the front layer at the screen's own resolution. */
    override fun DrawScope.drawTop(state: VizRenderState) {
        surface.configure(relief.value, wire.value.toInt())
        surface.project(size.width, size.height, scale.value, tilt.value)
        val light = state.lightScale.coerceIn(0f, 1f)
        if (shadow.value > 0f) shadowOutline.run { draw(surface, shadow.value, light) }
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
            // The page's lattice has 50 cells a side, so every second line of the finer grid is
            // drawn, with the page's diagonals.
            val side = FluctusSurface.SIDE
            val last = FluctusSurface.SEGMENTS
            for (row in 0..last step LATTICE_STEP) for (column in 0..last step LATTICE_STEP) {
                val a = row * side + column
                if (column < last) edge(a, a + LATTICE_STEP)
                if (row < last) edge(a, a + LATTICE_STEP * side)
                if (column < last && row < last) edge(a + LATTICE_STEP, a + LATTICE_STEP * side)
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

    private companion object {
        /** Grid lines between two lattice lines: the finer grid has twice the page's cells. */
        const val LATTICE_STEP = FluctusSurface.SEGMENTS / 50

        fun floorColour(light: Float) = Color(0.977f * light, 0.969f * light, 0.994f * light)
    }
}

/**
 * The sheet's shadow on the floor: the outline of its edge cast from the light above, filled, with
 * a soft rim at the screen's own resolution.
 *
 * The page's shadow map drew the shadow from the sheet's triangles even while the sheet showed as
 * a lattice, so the shadow is solid in both. The outline is filled as a fan round its middle, and a
 * deformed edge can fold back on itself for a few pixels, which would lay two triangles of the fan
 * over each other and draw a dark line. So the outline's points are taken in order of their angle
 * round the middle, and the soft rim runs outward along the same rays, so every triangle keeps to
 * its own wedge and no pixel is covered twice.
 */
private class FluctusShadow {
    private val edge = IntArray(4 * FluctusSurface.SEGMENTS)
    private val x = FloatArray(edge.size)
    private val y = FloatArray(edge.size)
    private val angle = FloatArray(edge.size)

    /** The outline's points in order of angle. Kept between frames, so the sort starts almost done. */
    private val order = IntArray(edge.size) { it }
    private val mesh = TriangleMesh(maxVertices = edge.size * 2 + 1, maxIndices = edge.size * 9)

    init {
        // Round the sheet's edge once: along the first row, down the last column, back along the
        // last row and up the first column.
        val segments = FluctusSurface.SEGMENTS
        val side = FluctusSurface.SIDE
        var at = 0
        for (column in 0 until segments) edge[at++] = column
        for (row in 0 until segments) edge[at++] = row * side + segments
        for (column in segments downTo 1) edge[at++] = segments * side + column
        for (row in segments downTo 1) edge[at++] = row * side
    }

    fun DrawScope.draw(surface: FluctusSurface, strength: Float, light: Float) {
        var centreX = 0f
        var centreY = 0f
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (i in edge.indices) {
            x[i] = surface.shadowX[edge[i]]
            y[i] = surface.shadowY[edge[i]]
            centreX += x[i]; centreY += y[i]
            left = min(left, x[i]); right = max(right, x[i])
        }
        centreX /= edge.size
        centreY /= edge.size
        for (i in edge.indices) angle[i] = atan2(y[i] - centreY, x[i] - centreX)
        for (i in 1 until order.size) {
            val moving = order[i]
            var j = i - 1
            while (j >= 0 && angle[order[j]] > angle[moving]) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = moving
        }
        // The page's soft shadow map blurred its edge by about two percent of the sheet.
        val soft = max(2f, (right - left) * SOFT_SHARE)
        val opacity = (strength * SHADOW_OPACITY).coerceIn(0f, SHADOW_MOST)
        val inside = Color(0.39f * light, 0.45f * light, 0.72f * light, opacity).toArgb()
        val outside = Color(0.39f * light, 0.45f * light, 0.72f * light, 0f).toArgb()
        mesh.clear()
        val middle = mesh.vertex(centreX, centreY, inside)
        val first = mesh.vertexCount
        for (i in order.indices) {
            val at = order[i]
            val dx = x[at] - centreX
            val dy = y[at] - centreY
            val reach = sqrt(dx * dx + dy * dy)
            val out = if (reach > 1e-3f) soft / reach else 0f
            mesh.vertex(x[at], y[at], inside)
            mesh.vertex(x[at] + dx * out, y[at] + dy * out, outside)
        }
        for (i in edge.indices) {
            val a = first + i * 2
            val b = first + ((i + 1) % edge.size) * 2
            mesh.triangle(middle, a, b)
            mesh.quad(a, b, b + 1, a + 1)
        }
        drawMesh(mesh)
    }

    private companion object {
        const val SOFT_SHARE = 0.02f
        const val SHADOW_OPACITY = 0.52f
        const val SHADOW_MOST = 0.82f
    }
}
