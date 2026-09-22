package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

internal fun neonState(index: Int, fps: Int = 60, held: Boolean = false, level: Float = 0.7f,
    motion: Float = 1f, palette: VizPalette = VizPalette.Vapor): VizRenderState {
    val time = index.toFloat() / fps
    val bands = FloatArray(64) { b ->
        level * (0.06f + 0.85f * max(0f, sin(b * 0.31f - time * (1f + b % 4))) *
            (0.4f + 0.6f * max(0f, cos(time * 0.41f + b * 0.08f))))
    }
    val sound = SpectrumFrame(index * 1_000_000L / fps, bands, bands, FloatArray(0), level, level * 0.7f,
        level * 0.6f, level * 0.5f, 0f, 0f, energy = level, loudLong = level,
        mood = 0.5f, density = 0.6f, centroid = 0.4f, held = held)
    return VizRenderState(sound, time, 1f / fps, palette).also { it.motionScale = motion }
}

class NeonLoFiRenderTest {
    init { useSkiaGraphics() }
    private val directory = File("build/neonlofi-preview").apply { mkdirs() }
    @Test fun musicalRoadAtPhoneAndWideSizesWithBothBackgrounds() {
        for (scene in 1..4) {
            val viz = NeonLoFi()
            assertTrue(viz.runs, viz.compileError)
            viz.params[0].value = scene.toFloat()
            viz.params[6].value = 0f
            val settings = viz.params.map { it.value }.toFloatArray()
            for (i in 0..1800) viz.world.advance(neonState(i, fps = 20), settings, 0.37f)
            for (portable in listOf(false, true)) for ((w, h) in listOf(960 to 540, 432 to 960)) {
                viz.forcePortable = portable
                val image = ImageBitmap(w, h)
                val state = neonState(1801, fps = 20, held = true)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                    with(viz) { draw(state); drawFront(state) }
                }
                ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png",
                    File(directory, "scene-$scene-${w}x$h-${if (portable) "canvas" else "shader"}.png"))
                if (!portable) {
                    val fixtureFolder = File(directory, if (w == 960) "gpu-wide" else "gpu-phone").apply { mkdirs() }
                    NeonLoFiGpuFixture.write(viz, state, fixtureFolder, scene)
                    ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png", File(fixtureFolder, "neon-$scene-reference.png"))
                }
                val triangles = viz.floor.triangles + viz.decor.city.indexCount / 3 + viz.decor.sky.indexCount / 3 + viz.decor.rain.indexCount / 3
                println("Neon scene=$scene $w x $h portable=$portable triangles=$triangles meshCopy=${viz.floor.copiedBytes}")
                assertTrue(triangles <= if (portable) 1500 else 6000, "Triangle budget $triangles")
            }
        }
    }
}
