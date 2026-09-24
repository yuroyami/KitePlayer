package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import kotlin.math.*
import kotlin.test.*

/** An independent ray/plane reference checks the painter's order without adding a runtime depth buffer. */
class NeonLoFiOcclusionTest {
    init { useSkiaGraphics() }
    @Test fun projectedOpaqueTerracesAgreeWithNearestVisibleWorldFaces() {
        for (width in listOf(360, 960)) for (amount in listOf(1f, 2f)) for (seed in listOf(0.03f, 0.84f)) {
            val height = 640
            val world = NeonLoFiWorld()
            val settings = world.controls.copyOf().apply { this[0] = 3f; this[1] = amount; this[6] = 2f; this[10] = 0f }
            for (i in 0..900) world.advance(neonState(i), settings, seed)
            val state = neonState(901, held = true)
            val paint = NeonLoFiPaint().also { it.prepare(state, world) }
            val floor = NeonLoFiFloor().also { it.build(world, paint, width.toFloat(), height.toFloat()) }
            val image = ImageBitmap(width, height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                drawRect(Color.Black); drawMesh(floor.mesh)
            }
            val pixels = IntArray(width * height); image.readPixels(pixels)
            val f = world.flight; val cameraZ = f.travel.toFloat(); val cameraX = f.road(cameraZ)
            val start = kotlin.math.floor((f.travel + 1.8) / 4).toFloat() * 4
            val laneWidth = f.laneWidth(width.toFloat() / height)
            var checked = 0; var wrong = 0
            for (y in 210 until height step 7) for (x in 4 until width step 7) {
                val dx = x + 0.5f - width * 0.5f; val dy = y + 0.5f - height * NeonLoFiFlight.HORIZON
                val ux = dx * cos(f.bank) + dy * sin(f.bank)
                val uy = -dx * sin(f.bank) + dy * cos(f.bank)
                val rx = ux / f.horizontalFocal(width.toFloat(), height.toFloat()) + f.slope(cameraZ)
                val ry = -uy / (height * NeonLoFiFlight.FOCAL)
                var closest = Float.POSITIVE_INFINITY; var expected = 0; var edgeDistance = 0f
                val projectedFace = FloatArray(8)
                for (side in listOf(-1, 1)) for (row in 0..11) for (lane in 0..15) {
                    val z0 = max(start + row * 4, cameraZ + 1.8f); val z1 = start + (row + 1) * 4 - 0.04f
                    if (z1 <= z0) continue
                    val h = floor.lastHeights[(if (side == 1) 1 else 0) * 192 + row * 16 + lane]
                    val inner = NeonLoFiFlight.CORRIDOR + lane * laneWidth; val outer = inner + laneWidth - 0.035f
                    val bendSlope = (f.road(z1) - f.road(z0)) / (z1 - z0)
                    fun accept(distance: Float, face: Int) {
                        if (!distance.isFinite() || distance <= 0f || distance >= closest) return
                        val z = cameraZ + distance; val yy = f.height + ry * distance
                        val xx = (cameraX + rx * distance - f.road(z0) - bendSlope * (z - z0)) * side
                        val valid = when (face) {
                            0 -> z in z0..z1 && xx in inner..outer
                            1 -> yy in 0f..h && xx in inner..outer
                            else -> z in z0..z1 && yy in 0f..h
                        }
                        if (!valid) return
                        closest = distance; expected = floor.faceColor(lane, face, row)
                        // Measure distance to the actual projected edges. A world-z margin is not
                        // a screen-space margin under perspective, especially near the horizon.
                        fun corner(at: Int, local: Float, yy: Float, zz: Float) =
                            f.project(f.road(zz) + local * side, yy, zz, width.toFloat(), height.toFloat(), projectedFace, at * 2)
                        when (face) {
                            0 -> { corner(0, inner, h, z0); corner(1, outer, h, z0); corner(2, outer, h, z1); corner(3, inner, h, z1) }
                            1 -> { corner(0, inner, h, z0); corner(1, outer, h, z0); corner(2, outer, 0f, z0); corner(3, inner, 0f, z0) }
                            else -> { corner(0, inner, h, z0); corner(1, inner, h, z1); corner(2, inner, 0f, z1); corner(3, inner, 0f, z0) }
                        }
                        edgeDistance = Float.POSITIVE_INFINITY
                        for (edge in 0..3) {
                            val next = (edge + 1) % 4
                            val ax = projectedFace[edge * 2]; val ay = projectedFace[edge * 2 + 1]
                            val ex = projectedFace[next * 2] - ax; val ey = projectedFace[next * 2 + 1] - ay
                            val t = (((x + 0.5f - ax) * ex + (y + 0.5f - ay) * ey) / max(0.001f, ex * ex + ey * ey)).coerceIn(0f, 1f)
                            edgeDistance = min(edgeDistance, hypot(x + 0.5f - ax - ex * t, y + 0.5f - ay - ey * t))
                        }
                    }
                    if (h < f.height && abs(ry) > 0.00001f) accept((h - f.height) / ry, 0)
                    accept(z0 - cameraZ, 1)
                    val divisor = rx - bendSlope
                    if (abs(divisor) > 0.00001f) accept((f.road(z0) + inner * side - cameraX + bendSlope * (cameraZ - z0)) / divisor, 2)
                }
                if (expected == 0 || edgeDistance < 2f) continue
                checked++
                val actual = pixels[y * width + x]
                val difference = (0..2).maxOf { abs((expected shr (it * 8) and 255) - (actual shr (it * 8) and 255)) }
                if (difference > 3) wrong++
            }
            assertTrue(checked > 150, "Reference sampled too few interior faces: $checked")
            assertTrue(wrong <= checked / 100, "Nearest-face disagreement $wrong / $checked at width=$width amount=$amount seed=$seed")
        }
    }
}
