package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.BlendMode as SkiaBlendMode

/**
 * The padded copies that the Skia call draws from give the same picture as exact copies, and stay
 * the same arrays while the number of corners changes a little.
 */
class MeshPaddingTest {

    init { useSkiaGraphics() }

    private fun mesh(quads: Int): TriangleMesh {
        val mesh = TriangleMesh(maxVertices = 4096)
        for (quad in 0 until quads) {
            val x = 4f + (quad % 12) * 10f
            val y = 4f + (quad / 12) * 10f
            val colour = 0xFF000000.toInt() or (quad * 37 % 256 shl 16) or (quad * 91 % 256 shl 8) or (quad * 53 % 256)
            val a = mesh.vertex(x, y, colour)
            val b = mesh.vertex(x + 8f, y, colour)
            val c = mesh.vertex(x + 8f, y + 8f, colour xor 0x00FFFFFF)
            val d = mesh.vertex(x, y + 8f, colour xor 0x00FFFFFF)
            mesh.quad(a, b, c, d)
        }
        return mesh
    }

    private fun pixels(draw: DrawScope.() -> Unit): IntArray {
        val image = ImageBitmap(140, 100)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(140f, 100f)) { draw() }
        return IntArray(140 * 100).also { image.readPixels(it) }
    }

    @Test
    fun paddedArraysDrawTheSamePictureAsExactOnes() {
        for (quads in listOf(1, 5, 64, 65, 100)) {
            val mesh = mesh(quads)
            val padded = pixels { drawMesh(mesh) }
            val exact = pixels {
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawVertices(
                        VertexMode.TRIANGLES, mesh.positionsExact(), mesh.colorsExact(), null, mesh.indicesExact(),
                        SkiaBlendMode.MODULATE,
                        Paint().apply { isAntiAlias = true; color = -1; blendMode = SkiaBlendMode.SRC_OVER },
                    )
                }
            }
            assertContentEquals(exact, padded, "$quads quads drew differently with padded arrays")
            assertTrue(padded.any { it != 0 }, "the mesh drew nothing")
        }
    }

    private fun fill(mesh: TriangleMesh, quads: Int) {
        mesh.clear()
        repeat(quads) { quad ->
            val x = quad * 5f
            val a = mesh.vertex(x, 0f, -1)
            val b = mesh.vertex(x + 4f, 0f, -1)
            val c = mesh.vertex(x + 4f, 4f, -1)
            val d = mesh.vertex(x, 4f, -1)
            mesh.quad(a, b, c, d)
        }
    }

    @Test
    fun aFewMoreOrFewerCornersReuseTheSameArrays() {
        val mesh = TriangleMesh(maxVertices = 4096)
        fill(mesh, 10)
        val positions = mesh.positionsPadded()
        val colours = mesh.colorsPadded()
        val indices = mesh.indicesPadded()
        fill(mesh, 12)
        assertSame(positions, mesh.positionsPadded())
        assertSame(colours, mesh.colorsPadded())
        assertSame(indices, mesh.indicesPadded())
        assertTrue(positions.size % 512 == 0 && colours.size % 256 == 0 && indices.size % 768 == 0)
    }

    @Test
    fun aMeshThatAlternatesBetweenSizesAllocatesEachSizeOnce() {
        val mesh = TriangleMesh(maxVertices = 4096)
        fill(mesh, 30)
        val small = mesh.positionsPadded()
        fill(mesh, 600)
        val large = mesh.positionsPadded()
        repeat(5) {
            fill(mesh, 30)
            assertSame(small, mesh.positionsPadded(), "the small size was allocated again")
            fill(mesh, 600)
            assertSame(large, mesh.positionsPadded(), "the large size was allocated again")
        }
    }

    @Test
    fun aMeshWithMoreIndicesThanTheLargestSizeStillDraws() {
        val mesh = TriangleMesh(maxVertices = 64, maxIndices = 300_000)
        val a = mesh.vertex(2f, 2f, -1)
        val b = mesh.vertex(20f, 2f, -1)
        val c = mesh.vertex(2f, 20f, -1)
        repeat(100_000) { mesh.triangle(a, b, c) }
        assertTrue(mesh.indexCount > 196_608, "the fixture must exceed the largest size class")
        val indices = mesh.indicesPadded()
        assertTrue(indices.size >= mesh.indexCount && indices.size % 3 == 0)
        for (at in mesh.indexCount until indices.size) assertTrue(indices[at].toInt() == 0)
        assertTrue(pixels { drawMesh(mesh) }.any { it != 0 }, "the mesh drew nothing")
    }

    @Test
    fun theExtraCornersAndTrianglesAreEmptyWhateverWasDrawnBefore() {
        val mesh = TriangleMesh(maxVertices = 4096)
        fill(mesh, 200)
        mesh.positionsPadded(); mesh.colorsPadded(); mesh.indicesPadded()
        fill(mesh, 3)
        val indices = mesh.indicesPadded()
        val colours = mesh.colorsPadded()
        for (at in mesh.indexCount until indices.size) assertTrue(indices[at].toInt() == 0, "stale index at $at")
        for (at in mesh.vertexCount until colours.size) assertTrue(colours[at] == 0, "stale colour at $at")
    }
}
