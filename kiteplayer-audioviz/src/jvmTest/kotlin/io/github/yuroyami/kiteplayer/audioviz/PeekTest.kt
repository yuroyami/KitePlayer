package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders a few named drawings at four moments of one run, for looking at one while working on it.
 *
 * Does nothing unless AUDIOVIZ_PEEK names drawings, separated by commas. AUDIOVIZ_SONG picks calm,
 * lively or silence, AUDIOVIZ_FRAMES the length of the run, and AUDIOVIZ_PALETTE a palette by name.
 * The sheet is written to build/reports/visualizations/peek.png.
 */
class PeekTest {

    init { useSkiaGraphics() }

    @Test
    fun peek() {
        val names = System.getenv("AUDIOVIZ_PEEK")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (names.isEmpty()) return
        val song = when (System.getenv("AUDIOVIZ_SONG")?.lowercase()) {
            "calm" -> RenderHarness.Song.Calm
            "silence" -> RenderHarness.Song.Silence
            else -> RenderHarness.Song.Lively
        }
        val frames = System.getenv("AUDIOVIZ_FRAMES")?.toIntOrNull()?.coerceAtLeast(4) ?: 240
        val palette = VizPalette.entries.firstOrNull { it.name.equals(System.getenv("AUDIOVIZ_PALETTE"), ignoreCase = true) }
            ?: VizPalette.Prism
        val chosen = VizCatalog.create().filter { it.name in names }
        val moments = listOf(frames / 4 - 1, frames / 2 - 1, frames * 3 / 4 - 1, frames - 1)
        val cellWidth = 480
        val cellHeight = 300
        val gap = 6
        val label = 22
        val sheet = BufferedImage(
            moments.size * (cellWidth + gap) + gap,
            chosen.size.coerceAtLeast(1) * (cellHeight + label + gap) + gap,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = sheet.createGraphics()
        graphics.color = AwtColor(18, 18, 22)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        chosen.forEachIndexed { row, visualization ->
            RenderHarness.forEachFrame(visualization, cellWidth, cellHeight, frames, palette, song) { bitmap, step ->
                val column = moments.indexOf(step)
                if (column < 0) return@forEachFrame
                val image = with(RenderHarness) { bitmap.toBufferedImage().scaledTo(cellWidth, cellHeight) }
                val x = gap + column * (cellWidth + gap)
                val y = gap + row * (cellHeight + label + gap)
                graphics.drawImage(image, x, y, null)
                graphics.color = AwtColor(200, 205, 215)
                graphics.drawString("${visualization.name}  frame ${step + 1}  ${song.name.lowercase()}", x + 2, y + cellHeight + 16)
            }
        }
        graphics.dispose()
        val file = File(File("build/reports/visualizations").apply { mkdirs() }, "peek.png")
        ImageIO.write(sheet, "png", file)
        println("peek sheet: ${file.absolutePath}")
    }
}
