package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Where libass has no font provider and reads the system's font files from memory, a style that
 * names a font nobody has falls back to the family of the first font libass was given. That has
 * to be the system's Latin sans face, the first file the scan picks, even when the application or
 * the container hands over a font before the track opens (#507).
 *
 * The test names no family, so it holds on any host: it compares what the missing font draws with
 * what each candidate draws when it is the only font loaded.
 */
class SystemFontDefaultFamilyTest {

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

    /** What a style naming a missing font draws once [appFonts] went in ahead of the track. */
    private fun render(appFonts: List<Pair<String, ByteArray>>, systemFonts: Boolean = true): List<OverlayImage> {
        val directories = KiteLibass.fontDirectories
        if (!systemFonts) KiteLibass.fontDirectories = emptyList()
        try {
            return LibassTypesetter().use { typesetter ->
                appFonts.forEach { (name, bytes) -> typesetter.addFont(name, bytes) }
                typesetter.openDocument(script("No Such Font Anywhere").encodeToByteArray())
                assertNotNull(typesetter.render(2_000, frame), "the first render of a track is never unchanged")
            }
        } finally {
            KiteLibass.fontDirectories = directories
        }
    }

    private fun ink(images: List<OverlayImage>): Int = images.sumOf { image ->
        (3 until image.bitmap.pixels.size step 4).count { (image.bitmap.pixels[it].toInt() and 0xFF) > 32 }
    }

    private fun same(a: List<OverlayImage>, b: List<OverlayImage>): Boolean = a.size == b.size && a.zip(b).all { (x, y) ->
        x.x == y.x && x.y == y.y && x.bitmap.width == y.bitmap.width && x.bitmap.height == y.bitmap.height &&
            x.bitmap.pixels.contentEquals(y.bitmap.pixels)
    }

    @Test
    fun theFirstSystemFontIsTheDefaultFamilyEvenWhenAnAppFontArrivesFirst() {
        // macOS and Windows: CoreText and DirectWrite answer for the missing font, and no file is read.
        if (!KiteLibass.needsSystemFontFiles) return
        val system = readFontFiles(KiteLibass.fontDirectories, KiteLibass.systemFontBudgetBytes)
        if (system.size < 2) fail("the font directories ${KiteLibass.fontDirectories} gave ${system.size} files; this test needs two")

        val default = render(emptyList())
        assertTrue(ink(default) > 0, "the default family drew nothing")
        assertTrue(same(default, render(listOf(system.first()), systemFonts = false)), "the default is not ${system.first().first}")

        // An application font that draws the line differently, so it would show if it took over.
        val appFont = system.drop(1).asReversed().firstOrNull { font ->
            render(listOf(font), systemFonts = false).let { alone -> ink(alone) > 0 && !same(alone, default) }
        } ?: fail("every font in ${system.map { it.first }} draws the line the same way")
        assertTrue(same(default, render(listOf(appFont))), "${appFont.first}, handed over first, became the default family")
    }
}
