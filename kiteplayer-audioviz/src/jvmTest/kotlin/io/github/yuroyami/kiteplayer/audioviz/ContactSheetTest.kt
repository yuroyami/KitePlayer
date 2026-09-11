package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualization
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every drawing off screen and checks it actually puts ink down.
 *
 * A visualisation that silently draws nothing still compiles and still passes any test that only
 * asks whether it threw. This runs each one over a short stretch of simulated music, counts the
 * pixels that are no longer the background, and writes a contact sheet so the result can be looked
 * at rather than only measured.
 */
class ContactSheetTest {

    init { useSkiaGraphics() }

    private val cellWidth = 320
    private val cellHeight = 200
    private val framesPerPreset = 150

    /**
     * How many frames a shader drawing gets instead.
     *
     * A shader runs on the graphics card in the application and on the processor here, one pixel
     * at a time, in an interpreted language. That is the right trade for a test that has to run
     * anywhere, but it is hundreds of times slower, so these get a short run. They settle quickly
     * anyway: there is no trail to fill and no particles to spread out.
     */
    private val shaderFrames = 24

    @Test
    fun everyVisualizationDrawsSomething() {
        val catalogue = VizCatalog.create()
        assertTrue(catalogue.size >= 45, "the catalogue should be a real set, had ${catalogue.size}")

        val measured = catalogue.map { it to measure(it) }
        val rendered = measured.map { (visualization, run) -> visualization to run.last }
        val sheet = contactSheet(rendered)
        val outputDirectory = File("build/reports/visualizations").apply { mkdirs() }
        val sheetFile = File(outputDirectory, "contact-sheet.png")
        ImageIO.write(sheet, "png", sheetFile)
        println("contact sheet: ${sheetFile.absolutePath}")

        val blank = measured.filter { (_, run) -> run.peakInk < 0.004f }
        assertTrue(
            blank.isEmpty(),
            "these drew nothing: ${blank.joinToString { it.first.name }}",
        )

        // A drawing that feeds its own last frame back can run away: if what returns each pass
        // totals more than what decays, brightness multiplies and the picture is solid white
        // within a second. It still counts as ink, so the blank check above cannot see it.
        val blownOut = measured.filter { (_, run) -> run.peakBlown > 0.3f }
        assertTrue(
            blownOut.isEmpty(),
            "these saturated to white, so the feedback gain is above one: " +
                blownOut.joinToString { "${it.first.name} at ${(it.second.peakBlown * 100).toInt()}%" },
        )
    }

    /** How much of the image is at or near full brightness on every channel. */
    private fun blownFraction(image: BufferedImage): Float {
        var blown = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                if (red > 245 && green > 245 && blue > 245) blown++
            }
        }
        val sampled = (image.width / 2) * (image.height / 2)
        return blown.toFloat() / sampled
    }

    /**
     * Whether this drawing runs a program per pixel, either as its whole picture or on the frame
     * it feeds back. Either way the processor has to do that work here rather than the graphics
     * card, so the run is kept short.
     */
    private fun usesShader(visualization: Visualization): Boolean =
        visualization is ShaderPreset || visualization.warp != null

    /** The last frame of a run, plus the most ink and the most glare seen anywhere in it. */
    private class Run(val last: BufferedImage, val peakInk: Float, val peakBlown: Float)

    /**
     * Runs one drawing over the fake drum loop and measures it across the whole run.
     *
     * Judging a drawing by its final frame alone is unfair to anything driven by the drums: a
     * preset that draws a ring on every kick is empty in the gaps, and whether the run happened to
     * end in a gap is luck rather than a fault. So ink and glare are the worst case found anywhere
     * in the second half of the run, and only the picture itself comes from the last frame.
     */
    private fun measure(visualization: Visualization): Run {
        var last: BufferedImage? = null
        var peakInk = 0f
        var peakBlown = 0f
        val runFor = if (usesShader(visualization)) shaderFrames else framesPerPreset
        val from = runFor / 2
        RenderHarness.forEachFrame(
            visualization,
            cellWidth,
            cellHeight,
            runFor,
            paletteFor(visualization),
            RenderHarness.Song.Lively,
        ) { bitmap, step ->
            val isLast = step == runFor - 1
            // Sampled rather than every frame: reading pixels back out of a buffer costs more
            // than drawing into it did.
            if (!isLast && (step < from || step % 5 != 0)) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
            if (isLast) last = with(RenderHarness) { image.scaledTo(cellWidth, cellHeight) }
        }
        val picture = last ?: BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB)
        return Run(picture, peakInk, peakBlown)
    }

    /** Each family gets the palette it was designed against. */
    private fun paletteFor(visualization: Visualization): VizPalette = when (visualization.family) {
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.BarsAndWaves -> VizPalette.Classic
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Battery -> VizPalette.Prism
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Ambience -> VizPalette.Ambience
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Plenoptic -> VizPalette.Fire
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Alchemy -> VizPalette.Prism
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.MusicalColors -> VizPalette.Classic
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Immersion -> VizPalette.Prism
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Acid -> VizPalette.Ambience
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Warp -> VizPalette.Vapor
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Raymarch -> VizPalette.Prism
        io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily.Fluid -> VizPalette.Vapor
    }


    /** How much of the image stopped being the darkest corner colour. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                if (colourDistance(image.getRGB(x, y), background) > 18) different++
            }
        }
        val sampled = (image.width / 2) * (image.height / 2)
        return different.toFloat() / sampled
    }

    private fun colourDistance(left: Int, right: Int): Int {
        val red = abs((left shr 16 and 0xFF) - (right shr 16 and 0xFF))
        val green = abs((left shr 8 and 0xFF) - (right shr 8 and 0xFF))
        val blue = abs((left and 0xFF) - (right and 0xFF))
        return red + green + blue
    }


    private fun contactSheet(rendered: List<Pair<Visualization, BufferedImage>>): BufferedImage {
        val columns = 4
        val rows = (rendered.size + columns - 1) / columns
        val labelHeight = 22
        val padding = 6
        val sheet = BufferedImage(
            columns * (cellWidth + padding) + padding,
            rows * (cellHeight + labelHeight + padding) + padding,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = sheet.createGraphics()
        graphics.color = AwtColor(20, 20, 24)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)

        rendered.forEachIndexed { index, (visualization, image) ->
            val column = index % columns
            val row = index / columns
            val x = padding + column * (cellWidth + padding)
            val y = padding + row * (cellHeight + labelHeight + padding)
            graphics.drawImage(image, x, y, null)
            graphics.color = AwtColor(200, 205, 215)
            graphics.drawString(
                "${visualization.name}  (${visualization.family})",
                x + 2,
                y + cellHeight + 15,
            )
        }
        graphics.dispose()
        return sheet
    }
}
