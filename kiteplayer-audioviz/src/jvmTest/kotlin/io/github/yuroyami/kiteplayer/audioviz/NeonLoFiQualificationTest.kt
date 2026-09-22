package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

class NeonLoFiQualificationTest {
    init { useSkiaGraphics() }
    private val directory = File("build/neonlofi-qualification").apply { mkdirs() }

    @Test fun realPhoneSizeSurfaceKeepsStripedCoresAndFreezesItsFinishingPass() {
        for (portable in listOf(false, true)) for (post in listOf(false, true)) {
            val viz = NeonLoFi().apply { params[0].value = 1f; params[6].value = 0f; forcePortable = portable }
            val settings = viz.params.map { it.value }.toFloatArray()
            for (i in 0..300) viz.world.advance(neonState(i, fps = 15), settings, 0.37f)
            val frame = neonState(301, fps = 15, held = true).frame
            val scene = ImageComposeScene(1080, 2400, Density(1f), content = {
                VisualizerSurface(viz, { frame }, palette = VizPalette.Sunset, modifier = Modifier.fillMaxSize(), post = post)
            })
            try {
                var previous: BufferedImage? = null
                repeat(4) { i ->
                    scene.render(i * 66_666_667L).use { image ->
                        val pixels = image.toComposeImageBitmap().toAwtImage()
                        if (i == 3) {
                            ImageIO.write(pixels, "png", File(directory, "phone-${if (portable) "canvas" else "shader"}-${if (post) "post" else "plain"}.png"))
                            assertTrue(luma(pixels) > 0.015, "Whole picture is not black")
                            val before = checkNotNull(previous)
                            var changed = 0
                            for (y in 0 until 2400 step 4) for (x in 0 until 1080 step 4) if (pixels.getRGB(x, y) != before.getRGB(x, y)) changed++
                            assertEquals(0, changed, "Held playback freezes the finished pixels, including grain")
                        }
                        previous = pixels
                    }
                }
            } finally { scene.close() }
        }
    }

    @Test fun cameraFramingAndLaneVisibilityAtCombinedExtremes() {
        val w = NeonLoFiWorld()
        for (aspect in listOf(0.32f, 0.45f, 0.5625f, 1f, 1.7778f, 2.4f)) for (seed in listOf(0f, 0.37f, 1f)) {
            val settings = w.controls.copyOf().apply { this[1] = 2f; this[7] = 1.5f; this[6] = 2f }
            for (i in 1..600) w.advance(neonState(i + (seed * 10_000).toInt(), motion = 1f), settings, seed)
            val view = NeonLoFiView().also { it.prepare(1000f * aspect, 1000f, w) }
            val f = w.flight; val c = cos(f.bank); val s = sin(f.bank)
            val x = aspect * 0.5f + view.sun[0] * c - view.sun[1] * s
            val y = NeonLoFiFlight.HORIZON + view.sun[0] * s + view.sun[1] * c
            val r = view.sun[2]
            assertTrue(x - r >= 0.02f * aspect && x + r <= aspect * 0.98f)
            assertTrue(y - r >= 0.02f && y + r < 0.5f)
            val point = FloatArray(2)
            val z = f.travel.toFloat() + 18f
            for (lane in 0..15) for (side in listOf(-1, 1)) {
                val local = NeonLoFiFlight.CORRIDOR + (lane + 0.5f) * f.laneWidth(aspect)
                assertTrue(local > 3.5f, "Every raised lane stays outside the swept corridor")
                f.project(f.road(z) + local * side, w.floorHeight(lane, z), z, 1000f * aspect, 1000f, point)
                assertTrue(point[0] in 0f..1000f * aspect, "Frequency lane $lane offscreen at aspect $aspect")
                assertTrue(point[1] in 0f..1000f)
            }
        }
    }

    @Test fun hostGeometryCostAndFixedMeshBudgets() {
        val report = File(directory, "cpu-cost.csv")
        report.writeText("tier,scene,triangles,geometry_copy_bytes,p50_ms,p95_ms,maximum_ms\n")
        for (portable in listOf(false, true)) for (region in 1..4) {
            val viz = NeonLoFi().apply { params[0].value = region.toFloat(); params[6].value = 0f }
            val values = viz.params.map { it.value }.toFloatArray()
            val times = mutableListOf<Double>()
            var triangles = 0; var bytes = 0
            for (i in 0 until 360) {
                val state = neonState(i)
                val start = System.nanoTime()
                viz.world.advance(state, values, 0.37f)
                viz.paint.prepare(state, viz.world)
                viz.floor.portable = portable
                viz.floor.build(viz.world, viz.paint, 1080f, 2400f)
                viz.decor.build(viz.world, viz.paint, 1080f, 2400f, portable)
                // Skia trims and copies every submission. Include that cost and retained arrays here.
                val meshes = listOf(viz.floor.mesh, viz.decor.sky, viz.decor.city, viz.decor.rain)
                meshes.forEach { it.positionsExact(); it.colorsExact(); it.indicesExact() }
                if (i >= 60) times += (System.nanoTime() - start) / 1e6
                triangles = max(triangles, meshes.sumOf { it.indexCount / 3 })
                bytes = max(bytes, meshes.sumOf { it.vertexCount * 12 + it.indexCount * 2 })
            }
            times.sort()
            report.appendText("${if (portable) "portable" else "native"},$region,$triangles,$bytes,${times[150]},${times[285]},${times.last()}\n")
            assertTrue(triangles <= if (portable) 1500 else 6000, "Bounded geometry $triangles")
            assertTrue(bytes < 240_000, "Actual trimmed mesh copy remains bounded: $bytes")
        }
    }

    companion object {
        fun luma(image: BufferedImage): Double {
            var sum = 0.0; var count = 0
            for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                val p = image.getRGB(x, y)
                sum += 0.2126 * NeonLoFiPaint.decode((p shr 16 and 255) / 255f) +
                    0.7152 * NeonLoFiPaint.decode((p shr 8 and 255) / 255f) + 0.0722 * NeonLoFiPaint.decode((p and 255) / 255f)
                count++
            }
            return sum / count
        }
    }
}
