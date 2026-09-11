package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the families that depend on depth or on feedback at a size where they can be judged.
 *
 * Both effects die in a thumbnail. A tunnel and a flat spiral look the same at two hundred pixels
 * wide, and an echo that has been dragged four pixels reads as a blurry edge.
 */
class FamilySheetTest {

    init { useSkiaGraphics() }

    private val cellWidth = 620
    private val cellHeight = 390
    private val frames = 300

    /**
     * A shader drawing keeps no state from frame to frame, so a short run shows it as well as a long
     * one, and on the processor each of its frames costs as much as a hundred of the others.
     */
    private val shaderFrames = 10

    @Test
    fun renderTheDeepSheets() {
        sheet(VizFamily.Immersion, "immersion-sheet.png", VizPalette.Prism)
        sheet(VizFamily.Acid, "acid-sheet.png", VizPalette.Prism)
        sheet(VizFamily.Warp, "warp-sheet.png", VizPalette.Prism)
        sheet(VizFamily.Raymarch, "raymarch-sheet.png", VizPalette.Prism)
        sheet(VizFamily.Fluid, "fluid-sheet.png", VizPalette.Prism)
    }

    private fun sheet(family: VizFamily, fileName: String, palette: VizPalette) {
        val chosen = VizCatalog.create().filter { it.family == family }
        if (chosen.isEmpty()) return

        val rendered = chosen.map {
            it to RenderHarness.render(it, cellWidth, cellHeight, if (it is ShaderPreset) shaderFrames else frames, palette)
        }
        val columns = 2
        val rows = (rendered.size + columns - 1) / columns
        val labelHeight = 24
        val padding = 8
        val image = BufferedImage(
            columns * (cellWidth + padding) + padding,
            rows * (cellHeight + labelHeight + padding) + padding,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = image.createGraphics()
        graphics.color = AwtColor(18, 18, 22)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 15)
        rendered.forEachIndexed { index, (visualization: Visualization, cell) ->
            val x = padding + (index % columns) * (cellWidth + padding)
            val y = padding + (index / columns) * (cellHeight + labelHeight + padding)
            graphics.drawImage(cell, x, y, null)
            graphics.color = AwtColor(205, 210, 220)
            graphics.drawString(visualization.name, x + 3, y + cellHeight + 17)
        }
        graphics.dispose()

        val out = File("build/reports/visualizations").apply { mkdirs() }
        val file = File(out, fileName)
        ImageIO.write(image, "png", file)
        println("${family.name} sheet: ${file.absolutePath}")
    }
}
