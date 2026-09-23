package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class JourneyRenderTest {
    init { useSkiaGraphics() }
    private val directory = File("build/journey-preview").apply { mkdirs() }

    private fun snapshot(viz: Visualization, name: String, width: Int = 640, height: Int = 360, shaderReference: String? = null) {
        val image = ImageBitmap(width, height)
        val state = journeyState(1000, held = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawRect(state.palette.background)
            with(viz) { draw(state) }
            // The Java probe checks the shader stages; projected CPU structures are checked in the full image/app.
            if (shaderReference != null) ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png",
                File(directory, "$shaderReference.png"))
            with(viz) { drawFront(state) }
        }
        val pixels = image.toPixelMap()
        var visible = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = pixels[x, y]
            if (maxOf(c.red, c.green, c.blue) > 0.12f) visible++
        }
        assertTrue(visible > width * height / 100, "$name must remain visible")
        ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png", File(directory, "$name.png"))
    }

    @Test
    fun pipeFormsRenderAtBothAspectRatios() {
        for (form in 0..2) {
            val viz = Pipe()
            RenderHarness.forEachFrame(viz, 160, 90, 390, VizPalette.Prism, RenderHarness.Song.Lively,
                beforeDraw = {
                    viz.params.single { it.name == "Journey" }.value = 0f
                    viz.params.single { it.name == "Form" }.value = form.toFloat()
                }) { _, _ -> }
            snapshot(viz, "pipe-$form")
            if (form == 0 || form == 2) snapshot(viz, "pipe-$form-portrait", 360, 640)
        }
    }

    @Test
    fun plasmaDotsBendTheMatrixAcrossAnAreaAndGridIsFine() {
        fun draw(distortion: Float, name: String): java.awt.image.BufferedImage {
            val plasma = Plasma()
            assertTrue(plasma.runs, plasma.compileError)
            var result: java.awt.image.BufferedImage? = null
            RenderHarness.forEachFrame(plasma, 320, 180, 120, VizPalette.Prism, RenderHarness.Song.Lively, beforeDraw = {
                plasma.params.single { it.name == "Dot distortion" }.value = distortion
            }) { image, frame -> if (frame == 119) result = with(RenderHarness) { image.toBufferedImage() } }
            return checkNotNull(result).also { ImageIO.write(it, "png", File(directory, "$name.png")) }
        }
        val warped = draw(1f, "plasma"); val flat = draw(0f, "plasma-undistorted")
        var changed = 0
        for (y in 0 until 180) for (x in 0 until 320) {
            val a = warped.getRGB(x, y); val b = flat.getRGB(x, y)
            val diff = (0..2).sumOf { kotlin.math.abs((a shr (it * 8) and 255) - (b shr (it * 8) and 255)) }
            if (diff > 18) changed++
        }
        assertTrue(changed > 320 * 180 / 8, "The dots must deform a visible part of the matrix, not only their own pixels")
        assertTrue(Plasma().params.single { it.name == "Grid density" }.default >= 24f)
    }

}
