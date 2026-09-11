package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** What every drawing declares: a recipe that can change, something under it, and actors with a place. */
class RecipeTest {

    init { useSkiaGraphics() }

    @Test
    fun everyDrawingHasARecipeAGroundAndAnchors() {
        val problems = ArrayList<String>()
        for (drawing in VizCatalog.create()) {
            val genes = drawing.genes?.all?.size ?: 0
            if (genes < 4) problems += "${drawing.name} has $genes genes"
            if (drawing.ground == null && !drawing.paintsWholeScreen) problems += "${drawing.name} has no ground"
            RenderHarness.render(drawing, 64, 40, 3, VizPalette.Prism)
            if (drawing.anchors.isEmpty()) problems += "${drawing.name} has no anchors"
        }
        println("recipe problems: ${problems.size}")
        problems.forEach { println("  $it") }
        if (Survey.strict) assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyGroundAndDetailCompiles() {
        val broken = GroundKind.entries.mapNotNull { kind ->
            Ground(kind).compileError(kind)?.let { "ground $kind: $it" }
        } + DetailKind.entries.mapNotNull { kind ->
            Detail(kind).compileError?.let { "detail $kind: $it" }
        }
        assertTrue(broken.isEmpty(), broken.joinToString("\n\n"))
        println("${GroundKind.entries.size} grounds and ${DetailKind.entries.size} details compiled")
    }

    @Test
    fun aGroundAloneFillsTheScreenAndMoves() {
        val weak = ArrayList<String>()
        val sheet = java.awt.image.BufferedImage(4 * 326 + 6, 3 * 228 + 6, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13)
        for (kind in GroundKind.entries) {
            val picture = RenderHarness.render(GroundOnly(Ground(kind)), 320, 200, 120, VizPalette.Prism)
            val at = kind.ordinal
            graphics.drawImage(picture, 6 + (at % 4) * 326, 6 + (at / 4) * 228, null)
            graphics.color = java.awt.Color(210, 215, 225)
            graphics.drawString(kind.name, 8 + (at % 4) * 326, 222 + (at / 4) * 228)
        }
        graphics.dispose()
        javax.imageio.ImageIO.write(sheet, "png", java.io.File(java.io.File("build/reports/visualizations").apply { mkdirs() }, "ground-sheet.png"))
        for (kind in GroundKind.entries) {
            val drums = measureGround(kind, RenderHarness.Song.Lively)
            val pad = measureGround(kind, RenderHarness.Song.Calm)
            println(
                "ground $kind: drums ink ${Survey.number(drums[0])} alive ${Survey.number(drums[1])} busy ${Survey.number(drums[2])}" +
                    "  |  pad ink ${Survey.number(pad[0])} alive ${Survey.number(pad[1])} busy ${Survey.number(pad[2])}",
            )
            if (drums[0] < 0.9f || drums[1] < 0.95f || pad[1] < 0.6f) weak += "$kind: drums ${drums.toList()}, pad ${pad.toList()}"
        }
        if (Survey.strict) assertTrue(weak.isEmpty(), "these grounds leave the screen idle:\n" + weak.joinToString("\n"))
    }

    /** Ink, alive and busy share of a ground alone, over a second and a half of [song]. */
    private fun measureGround(kind: GroundKind, song: RenderHarness.Song): FloatArray {
        val alone = GroundOnly(Ground(kind))
        val width = 160
        val height = 100
        var inked = 0f
        var busy = 0f
        var samples = 0
        var pairs = 0
        val alive = BooleanArray(width * height)
        var before: IntArray? = null
        val lagged = HashMap<Int, IntArray>()
        val background = VizPalette.Prism.background.let {
            ((it.red * 255f).toInt() + (it.green * 255f).toInt() + (it.blue * 255f).toInt())
        }
        RenderHarness.forEachFrame(alone, width, height, 210, VizPalette.Prism, song) { bitmap, step ->
            if (step < 90 || step % 3 != 0) return@forEachFrame
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels)
            lagged.remove(step - 30)?.let { older ->
                for (index in pixels.indices) {
                    val a = older[index]
                    val b = pixels[index]
                    val d = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                        abs((a and 0xFF) - (b and 0xFF))
                    if (d > 12) alive[index] = true
                }
            }
            lagged[step] = pixels
            if (step >= 180) return@forEachFrame
            var lit = 0
            for (p in pixels) if ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF) - background > 30) lit++
            inked += lit.toFloat() / pixels.size
            samples++
            before?.let { previous ->
                var changed = 0
                for (index in pixels.indices) {
                    val a = previous[index]
                    val b = pixels[index]
                    val d = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
                        abs((a and 0xFF) - (b and 0xFF))
                    if (d > 24) changed++
                }
                busy += changed.toFloat() / pixels.size
                pairs++
            }
            before = pixels
        }
        return floatArrayOf(inked / samples.coerceAtLeast(1), alive.count { it }.toFloat() / alive.size, busy / pairs.coerceAtLeast(1))
    }

    /** A drawing that is nothing but a ground, for measuring grounds alone. */
    private class GroundOnly(override val ground: Ground) : Visualization {
        override val name: String get() = "Ground only"
        override val family: VizFamily get() = VizFamily.Ambience
        override fun DrawScope.draw(state: VizRenderState) {}
    }
}
