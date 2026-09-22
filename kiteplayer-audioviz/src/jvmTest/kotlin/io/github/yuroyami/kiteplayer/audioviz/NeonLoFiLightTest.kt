package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

class NeonLoFiLightTest {
    init { useSkiaGraphics() }
    @Test fun finishedPixelsUnderRapidMusicAndMaximumControls() {
        val report = File("build/neonlofi-qualification/finished-light.csv").apply { parentFile.mkdirs() }
        report.writeText("palette,max_controls,linear_luminance_flashes,red_swing_flashes,mean_linear_luminance,largest_luminance_swing_area\n")
        val samples = SyntheticSong.drumLoop(7f, beatsPerMinute = 240f)
        for (palette in listOf(VizPalette.Sunset, VizPalette.fromColors("Red fixture", listOf(Color.Red, Color(0.65f, 0.01f, 0.005f))))) {
            for (maxima in listOf(false, true)) {
                val viz = NeonLoFi()
                if (maxima) viz.params.forEach { it.value = it.max }
                viz.params[0].value = 3f
                val player = SongPlayer(samples, referencePower = 0.04)
                var current = player.latest
                val scene = ImageComposeScene(240, 424, Density(1f), content = {
                    VisualizerSurface(viz, { current }, palette = palette, modifier = Modifier.fillMaxSize(), post = true)
                })
                val light = FloatArray(240 * 424); val red = FloatArray(light.size); val pixels = IntArray(light.size)
                val general = FlashCaptureTest.AreaFlashes(); val reds = FlashCaptureTest.AreaFlashes()
                var flashes = 0; var redFlashes = 0; var luma = 0.0
                try {
                    repeat(180) { frame ->
                        current = player.next(1f / 60)
                        scene.render((frame + 1) * 16_666_667L).use { it.toComposeImageBitmap().readPixels(pixels) }
                        for (i in pixels.indices) {
                            val r = NeonLoFiPaint.decode((pixels[i] shr 16 and 255) / 255f)
                            val g = NeonLoFiPaint.decode((pixels[i] shr 8 and 255) / 255f)
                            val b = NeonLoFiPaint.decode((pixels[i] and 255) / 255f)
                            light[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            red[i] = if (r / max(0.001f, r + g + b) > 0.8f) max(0f, r - g - b) else 0f
                            luma += light[i] / light.size
                        }
                        general.add(light, 1f / 60); reds.add(red, 1f / 60)
                        flashes = max(flashes, general.recent); redFlashes = max(redFlashes, reds.recent)
                    }
                } finally { scene.close() }
                report.appendText("${palette.name},$maxima,$flashes,$redFlashes,${luma / 180},${general.widest}\n")
                assertTrue(flashes <= 3 && redFlashes <= 3, "Finished light $palette maxima=$maxima: luma=$flashes red=$redFlashes")
            }
        }
    }
    @Test fun oneReferenceExposureIsMonotonicAndNeutralPalettesStayNeutral() {
        val paint = NeonLoFiPaint(); val world = NeonLoFiWorld()
        val neutral = VizPalette.fromColors("Neutral fixture", listOf(Color.DarkGray, Color.Gray, Color.LightGray))
        var previous = 0f
        for (i in 0..20) {
            val frame = neonFrame(i * 250_000L, i / 20f)
            val state = VizRenderState(frame, i / 4f, 0.25f, neutral)
            world.advance(state, world.controls, 0.37f); paint.prepare(state, world)
            val c = paint.color(1, 0.8f)
            assertTrue(c.red >= previous)
            assertEquals(c.red, c.green, 0.015f); assertEquals(c.green, c.blue, 0.015f)
            previous = c.red
            val guarded = VizRenderState(frame, i / 4f, 0.25f, neutral).also { it.lightScale = 0.5f }
            paint.prepare(guarded, world)
            assertEquals(world.controls[3] * lightFor(frame.energy) * 0.5f, paint.exposure, 0.000001f)
        }
    }
}
