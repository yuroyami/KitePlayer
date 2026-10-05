package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On Android a style that names a font the device lacks, such as "Arial", draws in Roboto, the
 * system's own sans face, even when the application or the file hands over another font before the
 * track opens (#507). libass takes its default family from the first font it is given, and the
 * typesetter loads `/system/fonts` ahead of everything else for exactly that.
 */
class LibassAndroidDefaultFamilyTest {

    private val frame = TypesetFrame(width = 640, height = 360, videoWidth = 640, videoHeight = 360)

    private fun script(font: String) = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,$font,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Hamburgefonstiv 0123
    """.trimIndent() + "\n"

    /** What a style naming [font] draws after the device's serif face went in ahead of the track. */
    private fun render(font: String, serif: File): List<OverlayImage> = LibassTypesetter().use { typesetter ->
        typesetter.addFont(serif.name, serif.readBytes())
        typesetter.openDocument(script(font).encodeToByteArray())
        assertNotNull(typesetter.render(2_000, frame), "the first render of a track is never unchanged")
    }

    private fun same(a: List<OverlayImage>, b: List<OverlayImage>): Boolean = a.size == b.size && a.zip(b).all { (x, y) ->
        x.x == y.x && x.y == y.y && x.bitmap.width == y.bitmap.width && x.bitmap.height == y.bitmap.height &&
            x.bitmap.pixels.contentEquals(y.bitmap.pixels)
    }

    @Test
    fun aMissingFontDrawsInRobotoEvenWhenAnotherFontArrivesFirst() {
        val serif = assertNotNull(
            sequenceOf("NotoSerif-Regular.ttf", "DroidSerif-Regular.ttf").map { File("/system/fonts", it) }.firstOrNull { it.isFile },
            "no serif face under /system/fonts to hand over first",
        )
        val missing = render("Arial Unicode MS", serif)
        assertTrue(missing.isNotEmpty(), "the missing font drew nothing")
        assertTrue(same(missing, render("Roboto", serif)), "a missing font did not draw in Roboto")
        val serifFamily = if (serif.name.startsWith("Noto")) "Noto Serif" else "Droid Serif"
        assertTrue(!same(missing, render(serifFamily, serif)), "$serifFamily draws the line exactly as Roboto does, so this proves nothing")
    }
}
