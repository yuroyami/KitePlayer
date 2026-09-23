package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Five translucent solids, graphic planes and a bounded field of flying points. */
internal class GlitchGeometry {
    private val planes = TriangleMesh(maxVertices = 288, maxIndices = 432)
    private val crystals = TriangleMesh(maxVertices = 600, maxIndices = 840)
    private val stars = TriangleMesh(maxVertices = STAR_COUNT * 4, maxIndices = STAR_COUNT * 6)
    private val projectedX = FloatArray(30)
    private val projectedY = FloatArray(30)
    private val projectedZ = FloatArray(30)
    private val faces = IntArray(40) { it }
    private val faceDepth = FloatArray(40)
    private val starX = FloatArray(STAR_COUNT) { hash(it * 3 + 11) * 2f - 1f }
    private val starY = FloatArray(STAR_COUNT) { hash(it * 3 + 12) * 2f - 1f }
    private val starDepth = FloatArray(STAR_COUNT) { hash(it * 3 + 13) }

    fun DrawScope.draw(
        scene: GlitchScene,
        density: Float,
        scale: Float,
        crystalAmount: Float,
        tunnelAmount: Float,
        starAmount: Float,
        colourSpread: Float,
        light: Float,
        split: Float,
    ) {
        if (size.width <= 0f || size.height <= 0f || light <= 0f) return
        if (tunnelAmount > 0f) drawPlanes(scene, scale, tunnelAmount, colourSpread, light)
        if (crystalAmount > 0f) drawCrystals(scene, scale, crystalAmount, colourSpread, light, split)
        if (starAmount > 0f && density > 0f) drawStars(scene, density, scale, starAmount, colourSpread, light)
    }

    private fun DrawScope.drawPlanes(
        scene: GlitchScene,
        scale: Float,
        amount: Float,
        spread: Float,
        light: Float,
    ) {
        planes.clear()
        val unit = size.minDimension * scale
        val centreX = center.x + size.width * 0.045f * sin(scene.turn * 0.13f)
        val centreY = center.y + size.height * 0.035f * cos(scene.turn * 0.17f)
        for (plane in 0 until 12) {
            val depth = fract(plane / 12f - scene.travel * 0.075f)
            val radius = unit * 0.056f * exp(depth * 3.35f)
            val sides = if (plane % 3 == 0) 6 else 4
            val angle = scene.turn * 0.13f + plane * 0.042f + PI_OVER_FOUR
            val opacity = amount * light * (0.22f + 0.35f * scene.pressure + 0.15f * scene.lowAccent) *
                (depth * 5f).coerceIn(0f, 1f) * ((1f - depth) * 7f).coerceIn(0f, 1f)
            val thickness = unit * (0.007f + 0.032f * scene.pressure + 0.009f * scene.lowAccent) *
                (0.45f + depth)
            val colour = colour(scene.hue + 0.08f + plane * 0.083f * spread, 0.82f, opacity)
            for (edge in 0 until sides) {
                val from = angle + FULL_TURN * edge / sides
                val to = angle + FULL_TURN * (edge + 1) / sides
                val x0 = cos(from) * radius * (1.40f + 0.16f * scene.body)
                val y0 = sin(from) * radius
                val x1 = cos(to) * radius * (1.40f + 0.16f * scene.body)
                val y1 = sin(to) * radius
                val b0 = bow(x0, y0, size.width, size.height)
                val b1 = bow(x1, y1, size.width, size.height)
                planes.line(centreX + x0 * b0, centreY + y0 * b0,
                    centreX + x1 * b1, centreY + y1 * b1, thickness, colour)
            }
        }
        drawMesh(planes, BlendMode.Plus)
    }

    private fun DrawScope.drawCrystals(
        scene: GlitchScene,
        scale: Float,
        amount: Float,
        spread: Float,
        light: Float,
        split: Float,
    ) {
        crystals.clear()
        val unit = size.minDimension * scale
        val expansion = 0.82f + 0.24f * scene.body + 0.13f * scene.bass + 0.10f * scene.lowAccent
        for (solid in 0 until 5) {
            val phase = solid * 1.2566371f
            val cx = center.x + size.width * CENTRES_X[solid]
            val cy = center.y + size.height * CENTRES_Y[solid]
            val radius = unit * RADII[solid] * expansion
            val yaw = scene.turn * (0.24f + solid * 0.041f) + phase
            val pitch = scene.turn * (0.15f - solid * 0.027f) + 0.58f + phase * 0.43f
            val roll = scene.turn * (if (solid % 2 == 0) 0.11f else -0.10f) + phase * 0.31f
            val sy = sin(yaw); val cyaw = cos(yaw)
            val sp = sin(pitch); val cp = cos(pitch)
            val sr = sin(roll); val cr = cos(roll)
            for (corner in 0 until 6) {
                val vx = AXES[corner * 3]
                val vy = AXES[corner * 3 + 1] * (1.05f + 0.28f * scene.body)
                val vz = AXES[corner * 3 + 2]
                val x = vx * cyaw + vz * sy
                val z = vz * cyaw - vx * sy
                val y = vy * cp - z * sp
                val depth = z * cp + vy * sp
                val perspective = 3.6f / (3.6f + depth)
                val dx = (x * cr - y * sr) * radius * perspective
                val dy = (x * sr + y * cr) * radius * perspective
                val curve = bow(cx - center.x + dx, cy - center.y + dy, size.width, size.height)
                val index = solid * 6 + corner
                projectedX[index] = center.x + (cx - center.x + dx) * curve
                projectedY[index] = center.y + (cy - center.y + dy) * curve
                projectedZ[index] = depth
            }
            for (face in 0 until 8) {
                val offset = solid * 6
                faceDepth[solid * 8 + face] = projectedZ[offset + FACE_CORNERS[face * 3]] +
                    projectedZ[offset + FACE_CORNERS[face * 3 + 1]] +
                    projectedZ[offset + FACE_CORNERS[face * 3 + 2]]
            }
        }
        // Stable bounded insertion sort, with the rear translucent facets emitted first.
        for (i in faces.indices) faces[i] = i
        for (i in 1 until faces.size) {
            val face = faces[i]
            var before = i - 1
            while (before >= 0 && faceDepth[faces[before]] < faceDepth[face]) {
                faces[before + 1] = faces[before]
                before--
            }
            faces[before + 1] = face
        }
        val separation = unit * 0.011f * split.coerceAtLeast(0f) * scene.bodyAccent
        for (faceIndex in faces) {
            val solid = faceIndex / 8
            val face = faceIndex % 8
            val offset = solid * 6
            val a = offset + FACE_CORNERS[face * 3]
            val b = offset + FACE_CORNERS[face * 3 + 1]
            val c = offset + FACE_CORNERS[face * 3 + 2]
            val brightness = 0.72f + 0.28f * (face % 3) / 2f
            val opacity = amount * light * brightness * (0.055f + 0.08f * scene.level +
                0.045f * scene.bodyAccent) * (if (solid == 0) 1f else 0.86f)
            val hue = scene.hue + spread * (solid * 0.159f + face * 0.045f)
            crystals.face(a, b, c, colour(hue, 0.72f, opacity))
            if (separation > 0.08f) {
                crystals.face(a, b, c, Color(1f, 0.07f, 0.18f, (opacity * 0.27f).coerceIn(0f, 1f)).toArgb(), separation)
                crystals.face(a, b, c, Color(0.03f, 0.48f, 1f, (opacity * 0.27f).coerceIn(0f, 1f)).toArgb(), -separation)
            }
        }
        for (solid in 0 until 5) {
            val colour = colour(scene.hue + spread * (solid * 0.159f + 0.15f), 0.57f,
                amount * light * (0.065f + 0.08f * scene.air + 0.09f * scene.bodyAccent))
            for (edge in EDGES.indices step 2) {
                val a = solid * 6 + EDGES[edge]
                val b = solid * 6 + EDGES[edge + 1]
                crystals.line(projectedX[a], projectedY[a], projectedX[b], projectedY[b],
                    max(0.65f, unit * 0.0013f), colour)
            }
        }
        drawMesh(crystals, BlendMode.Plus)
    }

    private fun DrawScope.drawStars(
        scene: GlitchScene,
        density: Float,
        scale: Float,
        amount: Float,
        spread: Float,
        light: Float,
    ) {
        stars.clear()
        val angle = scene.turn * 0.022f
        val ca = cos(angle); val sa = sin(angle)
        val length = 0.012f + 0.12f * scene.pressure + 0.04f * scene.highAccent
        for (star in 0 until STAR_COUNT) {
            val selected = (density * STAR_COUNT - star).coerceIn(0f, 1f)
            if (selected == 0f) break
            val depth = 0.19f + fract(starDepth[star] - scene.travel * 0.14f) * 2.8f
            val baseX = (starX[star] * ca - starY[star] * sa) * size.width * 0.68f * scale
            val baseY = (starX[star] * sa + starY[star] * ca) * size.height * 0.68f * scale
            val dx = baseX / depth
            val dy = baseY / depth
            val bend = bow(dx, dy, size.width, size.height)
            val headX = center.x + dx * bend
            val headY = center.y + dy * bend
            if (headX < -8f || headX > size.width + 8f || headY < -8f || headY > size.height + 8f) continue
            val tailX = baseX / (depth + length)
            val tailY = baseY / (depth + length)
            val tailBend = bow(tailX, tailY, size.width, size.height)
            val fade = min((depth - 0.19f) * 5f, (2.99f - depth) * 2f).coerceIn(0f, 1f)
            val opacity = selected * amount * light * fade * (0.19f + 0.39f * scene.air +
                0.19f * scene.highAccent)
            stars.streak(center.x + tailX * tailBend, center.y + tailY * tailBend,
                headX, headY, max(0.65f, size.minDimension * (0.001f + 0.0015f / depth)),
                colour(scene.hue + starDepth[star] * spread, 0.39f, opacity))
        }
        drawMesh(stars, BlendMode.Plus)
    }

    private fun TriangleMesh.face(a: Int, b: Int, c: Int, argb: Int, dx: Float = 0f) {
        val first = vertex(projectedX[a] + dx, projectedY[a], argb)
        val second = vertex(projectedX[b] + dx, projectedY[b], argb)
        val third = vertex(projectedX[c] + dx, projectedY[c], argb)
        triangle(first, second, third)
    }

    private fun TriangleMesh.line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, argb: Int) {
        val dx = x1 - x0; val dy = y1 - y0
        val normal = width * 0.5f / sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val nx = -dy * normal; val ny = dx * normal
        val a = vertex(x0 - nx, y0 - ny, argb)
        val b = vertex(x1 - nx, y1 - ny, argb)
        val c = vertex(x1 + nx, y1 + ny, argb)
        val d = vertex(x0 + nx, y0 + ny, argb)
        quad(a, b, c, d)
    }

    private fun colour(hue: Float, saturation: Float, alpha: Float): Int =
        Color.hsv(fract(hue) * 360f, saturation, 1f, alpha.coerceIn(0f, 1f)).toArgb()

    private fun bow(x: Float, y: Float, width: Float, height: Float): Float {
        val nx = x / width
        val ny = y / height
        return 1f + min(0.22f, (nx * nx + ny * ny) * 0.28f)
    }

    private fun fract(value: Float): Float = value - floor(value)

    private fun hash(seed: Int): Float {
        var bits = seed * 1_103_515_245 + 12_345
        bits = bits xor (bits ushr 13)
        bits *= -1_640_531_527
        bits = bits xor (bits ushr 16)
        return (bits ushr 8) / 16_777_216f
    }

    private companion object {
        const val STAR_COUNT = 150
        const val FULL_TURN = 6.2831855f
        const val PI_OVER_FOUR = 0.7853982f
        val CENTRES_X = floatArrayOf(-0.04f, 0.12f, -0.16f, 0.11f, -0.10f)
        val CENTRES_Y = floatArrayOf(-0.03f, -0.11f, 0.13f, 0.15f, -0.16f)
        val RADII = floatArrayOf(0.60f, 0.48f, 0.45f, 0.42f, 0.39f)
        val AXES = floatArrayOf(1f, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f,
            0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, -1f)
        val FACE_CORNERS = intArrayOf(0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4,
            2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5)
        val EDGES = intArrayOf(0, 2, 2, 1, 1, 3, 3, 0, 0, 4, 1, 4,
            2, 4, 3, 4, 0, 5, 1, 5, 2, 5, 3, 5)
    }
}
