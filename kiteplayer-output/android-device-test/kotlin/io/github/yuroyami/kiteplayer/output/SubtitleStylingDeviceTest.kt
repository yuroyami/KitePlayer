package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Paint
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.CueWrap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five subtitle styling checks of #12, measured on the images the Android rasterizer draws.
 * The drawing classes are stubs on the host, so only a device or an emulator can run these.
 *
 * Each case also writes its images to this test app's external files, under `subtitle-styling`,
 * for a person to look at after an `am instrument` run.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleStylingDeviceTest {

    private val rasterizer = AndroidSubtitleRasterizer()

    @Test
    fun eachWrapModeBreaksALongCueItsOwnWay() {
        val text = longerThan(1.35f * SAFE_WIDTH)
        val balanced = draw(cue(text, layout = CueLayout(wrap = CueWrap.Balanced)), "wrap-balanced")
        val greedy = draw(cue(text, layout = CueLayout(wrap = CueWrap.None)), "wrap-none")
        val never = draw(cue(text, layout = CueLayout(wrap = CueWrap.Never)), "wrap-never")

        val even = balanced.lines()
        assertEquals(2, even.size, "balanced lines $even")
        assertTrue(abs(even[0].width - even[1].width) < 0.2 * maxOf(even[0].width, even[1].width), "balanced lines $even")

        val filled = greedy.lines()
        assertEquals(2, filled.size, "greedy lines $filled")
        assertTrue(filled[0].width > 0.85 * SAFE_WIDTH, "the greedy first line is not full: $filled")
        assertTrue(filled[1].width < 0.6 * SAFE_WIDTH, "the greedy second line is not short: $filled")

        val single = never.lines()
        assertEquals(1, single.size, "never lines $single")
        assertTrue(single[0].left <= 2 && single[0].right >= never.width - 3, "the unbroken line is not clipped at both edges: $single in ${never.width}")
    }

    @Test
    fun aLargeShadowFallsDownAndRightAndLeavesTheTextWhereItWas() {
        val plain = draw(cue("SHADOW TEST", CueStyle(shadowOffsetPx = 0f)), "shadow-off")
        val shadowed = draw(cue("SHADOW TEST", CueStyle(shadowColor = RED, shadowOffsetPx = 8f)), "shadow-red-8")

        val text = assertNotNull(plain.box(::isWhite), "the plain cue has no white text")
        val textWithShadow = assertNotNull(shadowed.box(::isWhite), "the shadowed cue has no white text")
        assertEquals(text, textWithShadow, "the shadow moved the text")

        val shadow = assertNotNull(shadowed.box(::isRed), "no red shadow pixels")
        assertTrue(shadow.left - text.left in 6..10 && shadow.top - text.top in 6..10, "the shadow is not 8 px down and right: text $text, shadow $shadow")
        assertTrue(shadow.right < shadowed.left + shadowed.width - 1 && shadow.bottom < shadowed.top + shadowed.height - 1, "the shadow touches the bitmap edge: $shadow in $shadowed")
    }

    @Test
    fun twoSpansKeepTheirSizesAndOutlineColoursAndTheTallerSetsTheLine() {
        val small = CueStyle(fontSizePx = 36f, outlineColor = RED, outlineWidthPx = 3f)
        val tall = CueStyle(fontSizePx = 72f, outlineColor = BLUE, outlineWidthPx = 3f)
        val mixed = draw(SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("SMALL ", small), StyledSpan("TALL", tall))), "spans-mixed")
        val tallOnly = draw(SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("TALL", tall))), "spans-tall-only")
        val smallOnly = draw(SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("SMALL", small))), "spans-small-only")

        val red = assertNotNull(mixed.box(::isRed), "no red outline")
        val blue = assertNotNull(mixed.box(::isBlue), "no blue outline")
        assertTrue(blue.height > 1.5 * red.height, "the tall span is not taller: red $red, blue $blue")
        assertTrue(abs(mixed.height - tallOnly.height) <= 2, "the line height is ${mixed.height}, the tall span alone is ${tallOnly.height}")
        assertTrue(mixed.height > smallOnly.height, "the line height ${mixed.height} is not above the small span's ${smallOnly.height}")
    }

    @Test
    fun aFontFamilyTheDeviceHasChangesTheFaceAndAMissingOneFallsBack() {
        val default = draw(cue("Family 1234 iiii WWWW"), "family-default")
        val monospace = draw(cue("Family 1234 iiii WWWW", CueStyle(fontFamily = "monospace")), "family-monospace")
        val missing = draw(cue("Family 1234 iiii WWWW", CueStyle(fontFamily = "NoSuchFamilyOnAnyDevice")), "family-missing")

        val defaultInk = assertNotNull(default.box(::isWhite))
        val monospaceInk = assertNotNull(monospace.box(::isWhite))
        assertTrue(abs(defaultInk.width - monospaceInk.width) > 8, "monospace kept the default width: $defaultInk against $monospaceInk")
        assertTrue(default.bitmap.pixels.contentEquals(missing.bitmap.pixels), "a missing family did not fall back to the default face")
    }

    @Test
    fun anOpaqueBackgroundBoxSitsBehindEveryLineAndLeavesTheTextAlone() {
        val text = "FIRST LINE\nSECOND LINE"
        val plain = draw(cue(text), "box-off")
        val boxed = draw(cue(text, CueStyle(backgroundColor = BOX)), "box-on")

        assertEquals(plain.box(::isWhite), boxed.box(::isWhite), "the box moved the text")
        assertTrue(plain.box(::isBox) == null, "a cue without a box has box pixels")
        val lines = plain.lines()
        assertEquals(2, lines.size, "plain lines $lines")
        for (line in lines) {
            // One or two pixels beside the ink on both sides, in the middle of the line: the padding.
            val row = boxed.rowOf(plain, (line.top + line.bottom) / 2)
            val left = boxed.columnOf(plain, line.left)
            val right = boxed.columnOf(plain, line.right)
            val padded = (1..2).any { boxed.isBoxAt(left - it, row) } && (1..2).any { boxed.isBoxAt(right + it, row) }
            assertTrue(padded, "no box padding beside $line")
        }
    }

    @Test
    fun aPositionedRightToLeftCueKeepsAllItsInkOnEitherSide() {
        // A short Hebrew line in a wrap width far wider than it: the layout puts its ink at the far
        // right of that width, and the bitmap is cropped to the ink, so the ink has to be moved (#478).
        val sizes = listOf(CueAlignment.BottomLeft, CueAlignment.BottomCenter, CueAlignment.BottomRight).map { alignment ->
            val image = draw(cue(HEBREW, layout = CueLayout(alignment = alignment, positionX = 0.5f, positionY = 0.9f)), "rtl-positioned-$alignment")
            val ink = assertNotNull(image.box(::isWhite), "the positioned $alignment cue has no white text")
            assertTrue(ink.left - image.left <= 6 && image.left + image.width - 1 - ink.right <= 6, "the positioned $alignment ink $ink does not fill its image ${image.width} wide at ${image.left}")
            ink.width
        }
        assertTrue(sizes.max() - sizes.min() <= 2, "the positioned cues lost ink on one side: widths $sizes")
    }

    @Test
    fun anUnpositionedRightToLeftCueSitsOnTheSideItNames() {
        for (text in listOf(HEBREW, "HELLO WORLD")) {
            val left = draw(cue(text, layout = CueLayout(alignment = CueAlignment.BottomLeft)), "side-left-${text.length}")
            val right = draw(cue(text, layout = CueLayout(alignment = CueAlignment.BottomRight)), "side-right-${text.length}")
            val leftInk = assertNotNull(left.box(::isWhite), "the left cue '$text' has no white text")
            val rightInk = assertNotNull(right.box(::isWhite), "the right cue '$text' has no white text")
            assertTrue(leftInk.left - left.left <= 6, "the left cue '$text' starts at $leftInk in an image at ${left.left}")
            assertTrue(right.left + right.width - 1 - rightInk.right <= 6, "the right cue '$text' ends at $rightInk in an image ${right.width} wide at ${right.left}")
            assertTrue(leftInk.right < rightInk.left, "the left cue '$text' at $leftInk is not left of the right one at $rightInk")
        }
    }

    @Test
    fun eachParagraphOfAMixedCueSitsOnTheSideTheCueNames() {
        val text = "HELLO\n$HEBREW"
        val left = draw(cue(text, layout = CueLayout(alignment = CueAlignment.BottomLeft)), "mixed-left").lines()
        val right = draw(cue(text, layout = CueLayout(alignment = CueAlignment.BottomRight)), "mixed-right")
        assertEquals(2, left.size, "mixed left lines $left")
        for (line in left) assertTrue(line.left <= 6, "a mixed line does not start at the left: $left")
        val rightLines = right.lines()
        assertEquals(2, rightLines.size, "mixed right lines $rightLines")
        for (line in rightLines) assertTrue(right.width - 1 - line.right <= 6, "a mixed line does not end at the right: $rightLines in ${right.width}")
    }

    // ---- Drawing and measuring ----------------------------------------------------------------

    private fun cue(text: String, style: CueStyle = CueStyle(), layout: CueLayout = CueLayout()) =
        SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan(text, style)), layout)

    private fun draw(cue: SubtitleCue.Text, name: String): OverlayImage {
        val image = rasterizer.rasterize(listOf(cue), VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 1f).single()
        save(image, name)
        return image
    }

    /** Uppercase words without descenders, added until one line of them is wider than [width]. */
    private fun longerThan(width: Float): String {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cueFontSizePx(CueStyle(), CueLayout(), VIEWPORT_HEIGHT, 1f)
        }
        val words = "THE BIG BLACK CAT SAT ON THE WIDE MAT AND LOOKED AT THE HEAVY DOOR FOR A LONG TIME".split(' ')
        val text = StringBuilder()
        var index = 0
        while (paint.measureText(text.toString()) < width) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(words[index % words.size])
            index++
        }
        return text.toString()
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private fun OverlayImage.alpha(x: Int, y: Int) = bitmap.pixels[(y * bitmap.width + x) * 4 + 3].toInt() and 0xFF

    private fun OverlayImage.channel(x: Int, y: Int, c: Int) = bitmap.pixels[(y * bitmap.width + x) * 4 + c].toInt() and 0xFF

    private val OverlayImage.width: Int get() = bitmap.width
    private val OverlayImage.height: Int get() = bitmap.height
    private val OverlayImage.left: Int get() = x
    private val OverlayImage.top: Int get() = y

    private fun isWhite(image: OverlayImage, x: Int, y: Int) = image.alpha(x, y) > 200 &&
        (0..2).all { image.channel(x, y, it) > 200 }

    private fun isRed(image: OverlayImage, x: Int, y: Int) = image.alpha(x, y) > 128 &&
        image.channel(x, y, 0) > 150 && image.channel(x, y, 1) < 90 && image.channel(x, y, 2) < 90

    private fun isBlue(image: OverlayImage, x: Int, y: Int) = image.alpha(x, y) > 128 &&
        image.channel(x, y, 2) > 150 && image.channel(x, y, 0) < 90 && image.channel(x, y, 1) < 90

    private fun isBox(image: OverlayImage, x: Int, y: Int) = image.alpha(x, y) > 250 &&
        abs(image.channel(x, y, 0) - 0x10) < 20 && abs(image.channel(x, y, 1) - 0x40) < 20 &&
        abs(image.channel(x, y, 2) - 0xA0) < 20

    private fun OverlayImage.isBoxAt(x: Int, y: Int) = x in 0 until width && y in 0 until height && isBox(this, x, y)

    /** The bounding box of the pixels [accept] takes, in viewport coordinates, or null for none. */
    private fun OverlayImage.box(accept: (OverlayImage, Int, Int) -> Boolean): Box? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (row in 0 until height) for (column in 0 until width) {
            if (!accept(this, column, row)) continue
            left = minOf(left, column)
            top = minOf(top, row)
            right = maxOf(right, column)
            bottom = maxOf(bottom, row)
        }
        return if (right < 0) null else Box(x + left, y + top, x + right, y + bottom)
    }

    /** The inked lines, top to bottom, each as its ink extent inside this image. */
    private fun OverlayImage.lines(): List<Box> {
        val inked = BooleanArray(height) { row -> (0 until width).any { alpha(it, row) > 32 } }
        val lines = mutableListOf<Box>()
        var row = 0
        while (row < height) {
            if (!inked[row]) {
                row++
                continue
            }
            val top = row
            while (row < height && inked[row]) row++
            val bottom = row - 1
            val columns = (0 until width).filter { column -> (top..bottom).any { alpha(column, it) > 32 } }
            lines += Box(columns.first(), top, columns.last(), bottom)
        }
        return lines
    }

    /** A row of [other] as a row of this image, through viewport coordinates. */
    private fun OverlayImage.rowOf(other: OverlayImage, row: Int) = other.y + row - y

    private fun OverlayImage.columnOf(other: OverlayImage, column: Int) = other.x + column - x

    private fun <T : Any> assertNotNull(value: T?, message: String = "expected a value"): T {
        if (value == null) throw AssertionError(message)
        return value
    }

    private fun save(image: OverlayImage, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)
            ?.resolve("subtitle-styling") ?: return
        dir.mkdirs()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(image.bitmap.pixels))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val VIEWPORT_WIDTH = 1280
        const val VIEWPORT_HEIGHT = 720
        val SAFE_WIDTH = cueSafeWidth(CueLayout(), VIEWPORT_WIDTH)
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BOX = 0xFF1040A0.toInt()

        /** "Hello world" in Hebrew: short, strongly right-to-left, and with no Latin to steer it. */
        const val HEBREW = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"
    }
}
