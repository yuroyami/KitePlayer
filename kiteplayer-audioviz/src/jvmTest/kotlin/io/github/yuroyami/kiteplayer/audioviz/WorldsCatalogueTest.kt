package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Worlds laws, measured on every drawing that publishes its forms. A drawing joins this suite
 * by publishing [Visualization.forms]; the others are not judged by it.
 */
class WorldsCatalogueTest {

    init { useSkiaGraphics() }

    private val rebuilt: List<Visualization> get() = VizCatalog.create().filter { it.forms != null }

    private fun forEach(block: (Visualization) -> String?) {
        val drawings = rebuilt
        if (drawings.isEmpty()) {
            println("worlds: no rebuilt drawings yet")
            return
        }
        val failures = drawings.mapNotNull(block)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun theSignalIsTheMatter() = forEach { drawing ->
        // A low tone and a high tone at the same level must give different pictures.
        val low = render(drawing) { step -> InjectedFrames.toneFrame(step, lowBand = true) }
        val high = render(drawing) { step -> InjectedFrames.toneFrame(step, lowBand = false) }
        val change = meanDifference(low, high)
        println("worlds: ${drawing.name} a low and a high tone differ by $change")
        if (change < MIN_SIGNAL) "${drawing.name}: a low and a high tone look alike, change $change" else null
    }

    @Test
    fun theWholeFrameIsAlive() = forEach { drawing ->
        val loud = RenderHarness.render(drawing, 320, 180, 600, VizPalette.Prism, RenderHarness.Song.Lively)
        val background = VizPalette.Prism.background
        var away = 0
        for (y in 0 until 180) for (x in 0 until 320) {
            val p = loud.getRGB(x, y)
            val dr = (p shr 16 and 0xFF) - (background.red * 255).toInt()
            val dg = (p shr 8 and 0xFF) - (background.green * 255).toInt()
            val db = (p and 0xFF) - (background.blue * 255).toInt()
            if (dr * dr + dg * dg + db * db > 32 * 32) away++
        }
        val share = away / (320f * 180f)
        println("worlds: ${drawing.name} covers ${(share * 100).toInt()} percent at loud")
        if (share < 0.2f) "${drawing.name}: only ${(share * 100).toInt()} percent of the frame is drawn at loud" else null
    }

    @Test
    fun quietAndLoudAreDifferentWorlds() = forEach { drawing ->
        val loud = RenderHarness.render(drawing, 160, 90, 600, VizPalette.Prism, RenderHarness.Song.Lively)
        val quiet = RenderHarness.render(drawing, 160, 90, 600, VizPalette.Prism, RenderHarness.Song.Calm)
        val loudLight = meanLuma(loud)
        val quietLight = meanLuma(quiet)
        val fall = if (loudLight <= 0f) 0f else (loudLight - quietLight) / loudLight
        println("worlds: ${drawing.name} light $loudLight loud, $quietLight quiet, ${(fall * 100).toInt()} percent dimmer")
        if (fall < 0.4f) "${drawing.name}: the calm pad is only ${(fall * 100).toInt()} percent dimmer than the drum loop" else null
    }

    @Test
    fun formsChangeWithTheMusic() = forEach { drawing ->
        var morphs = 0
        var births = 0
        RenderHarness.forEachFrame(drawing, 96, 54, 7_200, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            morphs = drawing.forms?.morphs ?: 0
            births = drawing.forms?.births ?: 0
        }
        println("worlds: ${drawing.name} $morphs morphs and $births births in two minutes of drums")
        if (morphs < 6 || births < 2) "${drawing.name}: $morphs morphs and $births births in two minutes of drums" else null
    }

    @Test
    fun noHitMovesTheCamera() = forEach { drawing ->
        val mapping = drawing.mapping ?: return@forEach "${drawing.name}: no mapping"
        val hits = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)
        val offending = mapping.drives.filter { it.driver in hits && it.property == VizProperty.Camera }
        if (offending.isNotEmpty()) "${drawing.name}: a hit drives the camera: $offending" else null
    }

    @Test
    fun aDifferentPaletteGivesADifferentPicture() = forEach { drawing ->
        val a = RenderHarness.render(drawing, 160, 90, 300, VizPalette.Prism, RenderHarness.Song.Lively)
        val b = RenderHarness.render(drawing, 160, 90, 300, VizPalette.Fire, RenderHarness.Song.Lively)
        val change = meanDifference(a, b)
        println("worlds: ${drawing.name} Prism and Fire differ by $change")
        if (change < MIN_COLOUR) "${drawing.name}: Prism and Fire look alike, change $change" else null
    }

    private fun render(drawing: Visualization, source: (Int) -> SpectrumFrame): java.awt.image.BufferedImage {
        var last: java.awt.image.BufferedImage? = null
        RenderHarness.forEachFrameOf(drawing, 160, 90, 300, VizPalette.Prism, source) { bitmap, step ->
            if (step == 299) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        return last!!
    }

    private fun meanDifference(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage): Float {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            total += kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
                kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + kotlin.math.abs((p and 0xFF) - (q and 0xFF))
        }
        return total / (a.width * a.height * 3f * 255f)
    }

    private fun meanLuma(image: java.awt.image.BufferedImage): Float {
        var total = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            total += 0.2126 * (p shr 16 and 0xFF) + 0.7152 * (p shr 8 and 0xFF) + 0.0722 * (p and 0xFF)
        }
        return (total / (image.width * image.height * 255.0)).toFloat()
    }

    private companion object {
        /** Mean per-channel difference, 0 to 1, below which two pictures count as alike. *Judgement.* */
        const val MIN_SIGNAL = 0.02f
        const val MIN_COLOUR = 0.03f
    }
}
