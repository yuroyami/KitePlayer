package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.dominantColors
import io.github.yuroyami.kiteplayer.audioviz.viz.toOklab
import io.github.yuroyami.kiteplayer.audioviz.viz.PaletteFade
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The colours drawings work in, and building new ones from a picture. */
class PaletteTest {

    init { useSkiaGraphics() }

    @Test
    fun aFullTurnKeepsItsBrightness() {
        // The point of the palettes' colour space. Walking hue round the circle the usual way makes
        // yellow glare and blue go muddy at the same setting; here the lightness should barely move.
        val prism = VizPalette.Prism
        val perceptual = (0 until 36).map { prism.cycled(it / 36f, saturation = 0.85f, value = 0.6f).toOklab().lightness }
        val hsv = (0 until 36).map { Color.hsv(it * 10f, 0.85f, 0.85f).toOklab().lightness }
        val spread = perceptual.max() - perceptual.min()
        val hsvSpread = hsv.max() - hsv.min()
        println("lightness spread over a full turn: $spread here, $hsvSpread with plain HSV")
        assertTrue(spread < 0.06f, "a full turn should keep its brightness, it moved by $spread")
        assertTrue(spread < hsvSpread / 3f, "and do far better than plain HSV, $spread against $hsvSpread")
    }

    @Test
    fun aPaletteCanBeBuiltFromColours() {
        val palette = VizPalette.fromColors(
            "Test",
            listOf(Color(0xFF102040), Color(0xFF3060C0), Color(0xFFE0B040), Color(0xFFF0F0E0), Color(0xFF802020)),
        )
        val low = palette.ramp(0f).toOklab().lightness
        val middle = palette.ramp(0.5f).toOklab().lightness
        val high = palette.ramp(1f).toOklab().lightness
        println("built ramp lightness: $low, $middle, $high; span ${palette.hueSpan} from ${palette.baseHue}")
        assertTrue(low < middle && middle < high, "the ramp should climb from dark to light")
        assertTrue(palette.background.toOklab().lightness < 0.15f, "the ground should be dark")
        assertEquals("Test", palette.name)
    }

    @Test
    fun aPictureGivesItsMainColoursDarkToLight() {
        val stripes = listOf(Color(0xFF101830), Color(0xFF2050A0), Color(0xFF30A060), Color(0xFFE0A030), Color(0xFFF4F0E8))
        val image = ImageBitmap(100, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(100f, 40f)) {
            stripes.forEachIndexed { index, colour ->
                drawRect(colour, topLeft = Offset(index * 20f, 0f), size = Size(20f, 40f))
            }
        }
        val found = dominantColors(image, 5)
        val lightness = found.map { it.toOklab().lightness }
        println("found ${found.size} colours, lightness $lightness")
        assertEquals(5, found.size)
        assertTrue(lightness.zipWithNext().all { (a, b) -> a <= b + 1e-4f }, "they should come back darkest first")
        for (i in found.indices) {
            for (j in i + 1 until found.size) {
                assertTrue(found[i].toOklab().distanceTo(found[j].toOklab()) > 0.05f, "colours $i and $j are the same")
            }
        }
        val fromPicture = VizPalette.fromImage("Cover", image)
        assertTrue(fromPicture.ramp(1f).toOklab().lightness > fromPicture.ramp(0f).toOklab().lightness)
    }

    @Test
    fun theBuiltInPalettesAreAllThere() {
        assertEquals(12, VizPalette.entries.size)
        assertEquals(VizPalette.entries.size, VizPalette.entries.map { it.name }.toSet().size, "names should be unique")
    }

    @Test
    fun aChangeOfPaletteFadesRatherThanCuts() {
        val fade = PaletteFade()
        val steady = frameWith(bpm = 120f, confidence = 1f)
        assertEquals(VizPalette.Classic, fade.advance(VizPalette.Classic, steady, 0.016f))
        // One second of the four that two bars take at 120 beats a minute.
        val partWay = fade.advance(VizPalette.Fire, steady, 1f)
        val fromClassic = partWay.low.toOklab().distanceTo(VizPalette.Classic.low.toOklab())
        val fromFire = partWay.low.toOklab().distanceTo(VizPalette.Fire.low.toOklab())
        println("a second into a change: $fromClassic from the old colour, $fromFire from the new")
        assertTrue(fromClassic > 0.005f && fromFire > 0.005f, "a second in, the colours should sit between the two")
        var last = partWay
        repeat(10) { last = fade.advance(VizPalette.Fire, steady, 0.5f) }
        assertEquals(VizPalette.Fire, last, "once the fade is over it should be exactly the palette asked for")
    }

    @Test
    fun aClearKeyLeansThePalette() {
        val leaned = PaletteFade().advance(VizPalette.Classic, frameWith(keyHue = 0.5f, keyConfidence = 1f), 0.016f)
        val turn = leaned.baseHue - VizPalette.Classic.baseHue
        println("a clear key turned the palette by $turn degrees")
        assertTrue(abs(turn) in 1f..20f, "a clear key should turn the palette a little, turned it $turn")
        val unsure = PaletteFade().advance(VizPalette.Classic, frameWith(keyHue = 0.5f, keyConfidence = 0.3f), 0.016f)
        assertEquals(VizPalette.Classic, unsure, "an unclear key should leave it alone")
    }

    private fun frameWith(
        bpm: Float = 0f,
        confidence: Float = 0f,
        keyHue: Float = 0f,
        keyConfidence: Float = 0f,
    ): SpectrumFrame = SpectrumFrame(
        ptsMicros = 0L,
        bands = FloatArray(4),
        peaks = FloatArray(4),
        scope = FloatArray(4),
        level = 0f,
        bass = 0f,
        mid = 0f,
        treble = 0f,
        beat = 0f,
        pulse = 0f,
        bpm = bpm,
        beatConfidence = confidence,
        keyHue = keyHue,
        keyConfidence = keyConfidence,
    )
}
