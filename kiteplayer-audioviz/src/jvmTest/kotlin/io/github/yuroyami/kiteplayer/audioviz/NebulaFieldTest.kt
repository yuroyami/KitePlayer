package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NebulaField
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Nebula Field on its own, without the catalogue. It reads its noise once a pixel and draws thin gas.
 * A small tile shows violet gas and green threads round a bright star, and a chosen palette replaces
 * the drawing's own colours. It holds still in silence, a turn reshapes the gas within a bar, a drop
 * sends a ring out from the core, and the sparks run on the strands the shader draws.
 */
class NebulaFieldTest {

    init { useSkiaGraphics() }

    @Test
    fun theShaderReadsTheNoiseOncePerPixel() {
        val source = NebulaField.SOURCE
        assertTrue(Regex("""\bfbm\(""").findAll(source).count() == 1, "the gas must read one layered noise per pixel")
        // Android refuses a uniform array read by a computed index, and loops need fixed bounds.
        assertTrue(!source.contains('['), "the shader must not declare arrays")
        assertTrue(!Regex("""\b(for|while)\s*\(""").containsMatchIn(source), "the shader must not loop")
    }

    @Test
    fun drawsThinGasOverTheLivelySong() {
        var peakInk = 0f
        var peakBlown = 0f
        var bright = 0f
        var counted = 0
        RenderHarness.forEachFrame(NebulaField(), 160, 90, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30 || step % 5 != 4) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
            bright += brightFraction(image)
            counted++
        }
        val brightShare = bright / counted
        println("nebula field: peak ink $peakInk, peak blown $peakBlown, bright share $brightShare")
        assertTrue(peakInk > 0.004f, "the nebula drew nothing: ink $peakInk")
        assertTrue(peakBlown < 0.3f, "the nebula saturated to white: $peakBlown")
        // Thin strands, not soft blobs: most of the picture stays dark.
        assertTrue(brightShare < 0.25f, "too much of the picture is lit, which reads as blobs: $brightShare")
    }

    @Test
    fun theTileShowsVioletGasWithGreenThreadsRoundABrightStar() {
        val drawing = NebulaField()
        val image = RenderHarness.render(drawing, 192, 108, 45, VizPalette.Prism, RenderHarness.Song.Lively)
        var violet = 0
        var green = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            if (b > g + 40 && r > g + 20) violet++
            if (g > r + 40 && g > b + 30) green++
        }
        // The core is the brightest thing near its own anchor.
        val coreX = (drawing.anchors[0].x * image.width).toInt()
        val coreY = (drawing.anchors[0].y * image.height).toInt()
        var brightest = 0f
        for (y in coreY - 2..coreY + 2) for (x in coreX - 2..coreX + 2) {
            if (x in 0 until image.width && y in 0 until image.height) brightest = maxOf(brightest, luma(image.getRGB(x, y)))
        }
        println("nebula field tile: violet $violet, green $green, core luma $brightest")
        assertTrue(violet > 60, "the tile shows too little violet gas: $violet pixels")
        assertTrue(green > 15, "the tile shows too few green threads: $green pixels")
        assertTrue(brightest > 0.7f, "the core is not a bright star: $brightest")
    }

    @Test
    fun aChosenPaletteReplacesTheSwatches() {
        // Of the coloured gas pixels, the share that is warm: red and gold rather than violet and green.
        // Stars and white crossings are neither, so they do not count.
        fun warmShare(palette: VizPalette): Float {
            val image = RenderHarness.render(NebulaField(), 96, 54, 30, palette, RenderHarness.Song.Lively)
            var warm = 0
            var cool = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val p = image.getRGB(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (r > b + 40 && g > b) warm++
                if (b > r + 20 || g > r + 40) cool++
            }
            return warm.toFloat() / (warm + cool).coerceAtLeast(1)
        }
        val own = warmShare(VizPalette.Prism)
        val fire = warmShare(VizPalette.Fire)
        println("nebula field: warm share with its own swatches $own, with Fire $fire")
        assertTrue(own < 0.2f && fire > 0.8f, "the Fire palette did not replace the orchid and green: $own against $fire")
    }

    @Test
    fun silenceHoldsStillAndLit() {
        val music = meanChange(RenderHarness.Song.Lively)
        val quiet = meanChange(RenderHarness.Song.Silence)
        println("nebula field: change a frame under music $music, in silence $quiet")
        assertTrue(music > 0.004f, "the music barely moves the nebula: $music")
        assertTrue(quiet <= 0.2f * music, "silence moves the nebula $quiet against $music under music")
        // The first frame already shows the nebula, dimmed rather than black.
        val first = RenderHarness.render(NebulaField(), 160, 90, 1, VizPalette.Prism, RenderHarness.Song.Silence)
        val ink = inkFraction(first)
        println("nebula field: first silent frame ink $ink")
        assertTrue(ink > 0.02f, "the first frame is nearly black: $ink")
    }

    @Test
    fun aTurnGivesTheGasANewShapeWithinOneBar() {
        // Without a pulse the injected frames run their cycles free, at about 3.1 seconds a bar.
        val bar = 190
        val base = injected(null, InjectedFrames.STRUCTURE_STEP + bar + 1)
        val turned = injected(VizDriver.Section, InjectedFrames.STRUCTURE_STEP + bar + 1)
        val after = InjectedFrames.STRUCTURE_STEP + bar
        val reshaped = difference(base[after], turned[after])
        val drifted = difference(base[InjectedFrames.STRUCTURE_STEP], base[after])
        println("nebula field: one bar after a turn the gas differs by $reshaped; the same bar without one drifts $drifted")
        assertTrue(reshaped > 0.02f, "a turn barely changes the gas: $reshaped")
    }

    @Test
    fun aDropSendsARingOutFromTheCore() {
        val width = 160
        val height = 90
        val cores = HashMap<Int, Pair<Float, Float>>()
        val base = injected(null, InjectedFrames.STRUCTURE_STEP + 70, width, height)
        val dropped = injected(VizDriver.Drop, InjectedFrames.STRUCTURE_STEP + 70, width, height, cores)
        val early = ringDistance(base, dropped, cores, InjectedFrames.STRUCTURE_STEP + 20, width, height)
        val late = ringDistance(base, dropped, cores, InjectedFrames.STRUCTURE_STEP + 60, width, height)
        println("nebula field: the ring after a drop sits $early then $late screen heights from the core")
        assertTrue(early > 0f && late > early + 0.1f, "no ring travels out from the core: $early then $late")
    }

    @Test
    fun theSparksRunOnTheStrandsTheShaderDraws() {
        // Large enough that a strand is at least a pixel wide: a smaller frame steps over most of them.
        val width = 640
        val height = 360
        val drawing = NebulaField()
        var last: VizRenderState? = null
        var sparks = 0
        RenderHarness.forEachFrame(drawing, width, height, 90, VizPalette.Prism, RenderHarness.Song.Lively,
            beforeDraw = { last = it }) { _, _ ->
            sparks = maxOf(sparks, drawing.sparks.alive.count { it })
        }
        // The shader alone, without the sparks and comets drawn over it.
        val gasOnly = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(gasOnly), Size(width.toFloat(), height.toFloat())) {
            drawVisualizationFrame(drawing, checkNotNull(last), null)
        }
        val pixels = IntArray(width * height)
        gasOnly.readPixels(pixels)
        // Where the processor's copy puts a strand's crest, the shader should draw bright gas; well away from
        // every crest it should draw little. Both are read only where the gas shows and away from the core.
        var crest = 0f
        var crests = 0
        var between = 0f
        var betweens = 0
        for (y in 0 until height) for (x in 0 until width) {
            val px = (x + 0.5f - width * 0.5f) / (height * 0.5f)
            val py = (y + 0.5f - height * 0.5f) / (height * 0.5f)
            drawing.gas.evaluate(px, py, drawing.past)
            if (drawing.gas.visible < 0.5f) continue
            val dx = px - drawing.gas.coreX
            val dy = py - drawing.gas.coreY
            if (dx * dx + dy * dy < 0.04f) continue
            val shade = luma(pixels[y * width + x])
            val offset = abs(drawing.gas.offset)
            if (offset < 0.15f) {
                crest += shade
                crests++
            } else if (offset > 1.5f) {
                between += shade
                betweens++
            }
        }
        val onCrest = crest / crests.coerceAtLeast(1)
        val offCrest = between / betweens.coerceAtLeast(1)
        println("nebula field: $sparks sparks at most; $crests crest pixels at $onCrest, $betweens others at $offCrest")
        assertTrue(crests > 200, "the processor's copy found almost no strand in view: $crests pixels")
        // The drum loop's mids are weak, so the crests are dim here; what matters is how far they stand out.
        assertTrue(onCrest > 0.02f && onCrest > 10f * offCrest,
            "the processor's strands are not where the shader draws them: $onCrest on a crest against $offCrest off it")
        assertTrue(sparks > 0, "no spark ran under the drum loop")
    }

    /** Every frame of a run with [driver] injected, as pixels. [cores] collects where the core was. */
    private fun injected(
        driver: VizDriver?, frames: Int, width: Int = 64, height: Int = 40,
        cores: MutableMap<Int, Pair<Float, Float>>? = null,
    ): List<IntArray> {
        val out = ArrayList<IntArray>(frames)
        val drawing = NebulaField()
        RenderHarness.forEachFrameOf(drawing, width, height, frames, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(driver, step) }) { bitmap, step ->
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels)
            out += pixels
            cores?.put(step, drawing.anchors[0].x to drawing.anchors[0].y)
        }
        return out
    }

    /**
     * How far from the core the pixels sit that the drop made much brighter than the run without it,
     * in screen heights, as their median.
     */
    private fun ringDistance(
        base: List<IntArray>, dropped: List<IntArray>, cores: Map<Int, Pair<Float, Float>>,
        step: Int, width: Int, height: Int,
    ): Float {
        val (coreX, coreY) = cores.getValue(step)
        val distances = ArrayList<Float>()
        for (y in 0 until height) for (x in 0 until width) {
            val at = y * width + x
            if (luma(dropped[step][at]) - luma(base[step][at]) < 0.3f) continue
            val dx = (x + 0.5f) / height - coreX * width / height
            val dy = (y + 0.5f) / height - coreY
            distances += sqrt(dx * dx + dy * dy)
        }
        if (distances.isEmpty()) return 0f
        distances.sort()
        return distances[distances.size / 2]
    }

    /** The mean change a frame, as a share of full luma, once the drawing has settled. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var sum = 0f
        var pairs = 0
        RenderHarness.forEachFrame(NebulaField(), 64, 40, 240, VizPalette.Prism, song) { bitmap, step ->
            if (step < 120) return@forEachFrame
            val pixels = IntArray(64 * 40)
            bitmap.readPixels(pixels)
            previous?.let {
                sum += difference(it, pixels)
                pairs++
            }
            previous = pixels
        }
        return sum / pairs.coerceAtLeast(1)
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f +
            0.0722f * (pixel and 0xFF) / 255f

    /** How much of the image stopped being the corner colour, as the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val p = image.getRGB(x, y)
                val d = abs((p shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    abs((p shr 8 and 0xFF) - (background shr 8 and 0xFF)) + abs((p and 0xFF) - (background and 0xFF))
                if (d > 18) different++
            }
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

    /** How much of the image is at or near full brightness on every channel. */
    private fun blownFraction(image: BufferedImage): Float {
        var blown = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val p = image.getRGB(x, y)
                if ((p shr 16 and 0xFF) > 245 && (p shr 8 and 0xFF) > 245 && (p and 0xFF) > 245) blown++
            }
        }
        return blown.toFloat() / ((image.width / 2) * (image.height / 2))
    }

    /** How much of the image is brighter than a third of full luma. */
    private fun brightFraction(image: BufferedImage): Float {
        var bright = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (luma(image.getRGB(x, y)) > 0.33f) bright++
        }
        return bright.toFloat() / (image.width * image.height)
    }
}
