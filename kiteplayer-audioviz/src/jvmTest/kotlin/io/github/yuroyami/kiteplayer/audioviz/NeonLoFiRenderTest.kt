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
                // The floor's count includes the road's edges and the wet road's streaks.
                val triangles = viz.floor.triangles + viz.decor.city.indexCount / 3 + viz.decor.sky.indexCount / 3 + viz.decor.rain.indexCount / 3
                println("Neon scene=$scene $w x $h portable=$portable triangles=$triangles floor=${viz.floor.triangles} meshCopy=${viz.floor.copiedBytes}")
                assertTrue(triangles <= if (portable) 1500 else 6000, "Triangle budget $triangles")
            }
        }
    }

    @Test fun theTowersAreGlassWithHeightsFromTheBands() {
        // A tower of note n reads lane 2 + n: the low tone fills lanes 0 to 2, the high tone lanes 10 to 12.
        fun heights(low: Boolean): Pair<Float, Float> {
            val viz = NeonLoFi()
            RenderHarness.forEachFrameOf(viz, 320, 180, 240, VizPalette.Prism,
                source = { InjectedFrames.toneFrame(it, lowBand = low) }) { _, _ -> }
            return viz.decor.towerHeight(0) to viz.decor.towerHeight(9)
        }
        val (lowC, lowA) = heights(true)
        val (highC, highA) = heights(false)
        println("neon towers: low tone C (lane 2) $lowC, A (lane 11) $lowA; high tone C $highC, A $highA")
        assertTrue(lowC > lowA, "the low tone raises the tower on lane 2: $lowC against $lowA")
        assertTrue(highA > highC, "the high tone raises the tower on lane 11: $highA against $highC")
    }

    @Test fun thePowerLinesCarryTheWaveform() {
        fun lines(driver: VizDriver?): NeonLoFi {
            val viz = NeonLoFi()
            RenderHarness.forEachFrameOf(viz, 320, 180, 100, VizPalette.Prism,
                source = { InjectedFrames.frame(driver, it) }) { _, _ -> }
            return viz
        }
        val waved = lines(VizDriver.Waveform).decor
        val plain = lines(null).decor
        println("neon power lines: ${waved.lineCount} spans, waveform ${waved.lineAmplitude}, plain ${plain.lineAmplitude}")
        assertTrue(waved.lineCount >= 2, "only ${waved.lineCount} power line spans")
        assertTrue(waved.lineAmplitude > 0.5f, "the lines swing by only ${waved.lineAmplitude}")
        assertTrue(waved.lineAmplitude > plain.lineAmplitude, "the waveform does not swing the lines more")
    }

    @Test fun theSkyWalksFromDuskToNight() {
        assertTrue(abs(NeonLoFiPaint.skyTurnFor(0.25f) - NeonLoFiPaint.skyTurnFor(0.75f)) > 10f)
        assertTrue(abs(NeonLoFiPaint.skyLiftFor(0f) - NeonLoFiPaint.skyLiftFor(0.5f)) > 0.05f)
        val world = NeonLoFiWorld()
        fun roles(walk: Float, palette: VizPalette) =
            NeonLoFiPaint().also { it.prepare(neonState(0, palette = palette), world, walk) }.roles.copyOf()
        for (palette in listOf(VizPalette.Prism, VizPalette.Vapor)) {
            val dusk = roles(0f, palette)
            val night = roles(0.25f, palette)
            val sky = setOf(NeonLoFiPaint.LOW, NeonLoFiPaint.MID)
            for (role in 0 until NeonLoFiPaint.ROLES) {
                val moved = (0..2).maxOf { abs(dusk[role * 3 + it] - night[role * 3 + it]) }
                if (role in sky) assertTrue(moved > 0.005f, "the walk does not turn sky role $role on ${palette.name}")
                else assertEquals(0f, moved, "the walk changed role $role on ${palette.name}")
            }
        }
    }

    @Test fun theRoadEdgesAreTheWaveformAndTheWetRoadReflectsTheTerraces() {
        fun run(driver: VizDriver?, keep: Int): Pair<NeonLoFi, java.awt.image.BufferedImage?> {
            val viz = NeonLoFi()
            var kept: java.awt.image.BufferedImage? = null
            RenderHarness.forEachFrameOf(viz, 320, 180, keep + 1, VizPalette.Prism,
                source = { InjectedFrames.frame(driver, it) }) { bitmap, step ->
                if (step == keep) kept = with(RenderHarness) { bitmap.toBufferedImage() }
            }
            return viz to kept
        }
        val waved = run(VizDriver.Waveform, 100).first.floor.edgeAmplitude
        val plain = run(null, 100).first.floor.edgeAmplitude
        println("neon road edges: waveform $waved, plain $plain")
        assertTrue(waved > 0.5f, "the road's edges swing by only $waved")
        assertTrue(waved > plain, "the waveform does not swing the edges more than the plain run")
        fun roadLuma(image: java.awt.image.BufferedImage): Float {
            var sum = 0.0; var count = 0
            for (y in (image.height * 0.62f).toInt() until (image.height * 0.95f).toInt())
                for (x in (image.width * 0.4f).toInt() until (image.width * 0.6f).toInt()) {
                    val p = image.getRGB(x, y)
                    sum += 0.2126 * (p shr 16 and 255) / 255 + 0.7152 * (p shr 8 and 255) / 255 + 0.0722 * (p and 255) / 255
                    count++
                }
            return (sum / count).toFloat()
        }
        val wet = roadLuma(run(VizDriver.Breakdown, 126).second!!)
        val dry = roadLuma(run(null, 126).second!!)
        println("neon road centre: wet $wet, dry $dry")
        assertTrue(wet > dry + 0.01f, "the wet road is not brighter: $wet against $dry")
    }
}
