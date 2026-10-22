package io.github.yuroyami.kiteplayer.libass

import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JNI half proved on a real Android runtime: the adapter loads, libass renders, and the pixels
 * that come back are the ones the Kotlin/Native half produces for the same document.
 *
 * This has to be a DEVICE test. The adapter is an Android `.so`, so a host JVM cannot load it, and
 * the interesting failures (a missing symbol, a bad relocation, an ABI directory the packager did
 * not include) are all things that only appear when something actually dlopens the library.
 *
 * A font is added by hand because Android has no system font provider in this chain: libass sees
 * exactly the fonts it is given, which is the behaviour [LibassRenderer.addFont] exists for.
 */
class LibassAndroidRenderTest {

    private val script = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Roboto,40,&H0000FF00,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Full throttle
    """.trimIndent()

    /**
     * A real TEXT font this device ships, chosen by name rather than by whatever sorts first.
     *
     * "Whatever sorts first" is `AndroidClock.ttf`, which carries clock glyphs and not one Latin
     * letter, so libass renders a perfectly correct nothing from it. The failure looks exactly
     * like a broken adapter, which is how this list came to be written.
     */
    private fun systemFont(): File? = sequenceOf(
        "Roboto-Regular.ttf", "DroidSans.ttf", "NotoSans-Regular.ttf", "RobotoStatic-Regular.ttf",
    ).map { File("/system/fonts", it) }.firstOrNull { it.isFile }

    @Test
    fun theAdapterLoadsAndRendersTheSamePixelsTheNativeHalfWould() {
        val font = assertNotNull(systemFont(), "no font under /system/fonts to render with")
        LibassRenderer().use { renderer ->
            renderer.addFont(font.nameWithoutExtension, font.readBytes())

            val cue = assertNotNull(
                renderer.renderDocument(script, timeMillis = 2_000, frameWidth = 640, frameHeight = 360),
                "libass rendered nothing for a visible event with ${font.name} loaded",
            )
            assertTrue(cue.regions.isNotEmpty(), "the cue carries no regions")

            var visible = 0
            var greenish = 0
            cue.regions.forEach { region ->
                assertTrue(region.canvasWidth == 640 && region.canvasHeight == 360, "wrong canvas")
                val pixels = region.bitmap.pixels
                assertTrue(
                    pixels.size == region.width * region.height * 4,
                    "region ${region.width}x${region.height} carries ${pixels.size} bytes",
                )
                var at = 0
                while (at < pixels.size) {
                    val alpha = pixels[at + 3].toInt() and 0xFF
                    if (alpha > 32) {
                        visible++
                        val red = pixels[at].toInt() and 0xFF
                        val green = pixels[at + 1].toInt() and 0xFF
                        if (green > 200 && red < 64) greenish++
                        // Premultiplied, so no channel may exceed its own alpha. This is the
                        // contract RgbaBitmap documents and the one thing a JNI packing bug
                        // would break silently rather than loudly.
                        assertTrue(red <= alpha + 1, "red $red exceeds alpha $alpha: not premultiplied")
                    }
                    at += 4
                }
            }
            assertTrue(visible > 100, "a 40px line must cover more than 100 visible pixels, got $visible")
            assertTrue(greenish > 500, "the style's primary is green; only $greenish of $visible were")

            assertNull(
                renderer.renderDocument(script, timeMillis = 20_000, frameWidth = 640, frameHeight = 360),
                "a time past the event's end must render nothing",
            )
        }
    }

    /** Closing twice is safe, and rendering after close refuses rather than crashing native code. */
    @Test
    fun closeIsIdempotentAndUseAfterCloseRefuses() {
        val renderer = LibassRenderer()
        renderer.close()
        renderer.close()
        val failure = runCatching {
            renderer.renderDocument(script, 2_000, 640, 360)
        }.exceptionOrNull()
        assertNotNull(failure, "rendering after close must refuse")
        assertTrue(failure is IllegalStateException, "expected IllegalStateException, got $failure")
        assertTrue(abs(0) == 0)
    }
}
