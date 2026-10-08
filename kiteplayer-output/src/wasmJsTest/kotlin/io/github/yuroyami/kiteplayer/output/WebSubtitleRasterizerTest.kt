@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueInsets
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueRegion
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.CueWrap
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web rasterizer with real text (#559): a cue becomes pixels through the browser's canvas, the
 * pixels are premultiplied, and the placement is the arithmetic every platform shares.
 *
 * It needs a 2D canvas that draws text, so these cases run in a browser. Node has none: there the
 * rasterizer is absent, which the first case checks, and the others return at once.
 */
class WebSubtitleRasterizerTest {

    private val plain = CueStyle(outlineWidthPx = 0f, shadowOffsetPx = 0f)

    private fun cue(
        text: String,
        style: CueStyle = CueStyle(),
        layout: CueLayout = CueLayout(),
    ) = SubtitleCue.Text(startMicros = 0, endMicros = 1_000_000, spans = listOf(StyledSpan(text, style)), layout = layout)

    private fun rasterize(vararg cues: SubtitleCue, width: Int = 640, height: Int = 360): List<OverlayImage>? =
        WebSubtitleRasterizer.orNull()?.rasterize(cues.toList(), width, height, 1f, 1f)

    private fun OverlayImage.alpha(x: Int, y: Int): Int = bitmap.pixels[(y * bitmap.width + x) * 4 + 3].toInt() and 0xFF
    private fun OverlayImage.channel(x: Int, y: Int, c: Int): Int = bitmap.pixels[(y * bitmap.width + x) * 4 + c].toInt() and 0xFF

    /** The first and last viewport column that holds ink. */
    private fun OverlayImage.inkColumns(): IntRange {
        var first = Int.MAX_VALUE
        var last = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (alpha(x, y) > 0) {
                if (x < first) first = x
                if (x > last) last = x
            }
        }
        return (this.x + first)..(this.x + last)
    }

    @Test
    fun theRasterizerIsThereExactlyWhereACanvasDrawsText() {
        val present = WebSubtitleRasterizer.orNull() != null
        println("the web subtitle rasterizer is ${if (present) "present" else "absent"} here")
        assertEquals(hasTextCanvas(), present)
        assertEquals(present, WebOutputBackend.subtitleRasterizer != null)
    }

    @Test
    fun aPlainCueIsDrawnAtTheBottomCentreByTheSharedArithmetic() {
        val images = rasterize(cue("Hello there", plain)) ?: return
        val image = images.single()
        // The text box ends on the bottom margin: 5 percent of 360 is 18 pixels.
        assertEquals(360 - 18, image.y + image.bitmap.height)
        val ink = image.inkColumns()
        assertTrue(ink.last > ink.first, "the cue has drawn pixels")
        assertTrue(abs((ink.first + ink.last) / 2 - 320) <= 3, "the ink is centred, was $ink")
        assertTrue(image.bitmap.width < 400, "only the columns with ink cross, was ${image.bitmap.width}")
        // The default size is a twentieth of the viewport height, so one line is near 18 to 26 pixels.
        assertTrue(image.bitmap.height in 14..40, "one line, was ${image.bitmap.height}")
    }

    @Test
    fun leftAndRightCuesStandOnTheirMargins() {
        val left = (rasterize(cue("Left", plain, CueLayout(alignment = CueAlignment.BottomLeft))) ?: return).single()
        val right = rasterize(cue("Right", plain, CueLayout(alignment = CueAlignment.TopRight)))!!.single()
        // 5 percent of 640 is 32.
        assertTrue(abs(left.inkColumns().first - 32) <= 3, "left ink starts at the margin, was ${left.inkColumns()}")
        assertTrue(abs(right.inkColumns().last - (640 - 32)) <= 3, "right ink ends at the margin, was ${right.inkColumns()}")
        assertEquals(18, right.y, "a top cue starts on the top margin")
    }

    @Test
    fun aHalfTransparentCueComesOutPremultiplied() {
        val image = (rasterize(cue("MMMM", plain.copy(primaryColor = 0x80FFFFFF.toInt(), bold = true))) ?: return).single()
        var solid = 0
        for (y in 0 until image.bitmap.height) for (x in 0 until image.bitmap.width) {
            val a = image.alpha(x, y)
            for (c in 0..2) assertTrue(image.channel(x, y, c) <= a + 1, "a colour above its alpha at $x,$y is straight alpha")
            if (a in 120..136) {
                solid++
                assertTrue(abs(image.channel(x, y, 0) - a) <= 2, "white at half alpha is grey at half, was ${image.channel(x, y, 0)} for $a")
            }
        }
        assertTrue(solid > 20, "the inside of the letters is at half alpha, found $solid pixels")
    }

    @Test
    fun anOutlineShowsItsColourBesideTheFill() {
        val style = plain.copy(fontSizePx = 60f, bold = true, outlineWidthPx = 8f, outlineColor = 0xFFFF0000.toInt())
        val image = (rasterize(cue("OOO", style)) ?: return).single()
        var red = 0
        var white = 0
        for (y in 0 until image.bitmap.height) for (x in 0 until image.bitmap.width) {
            if (image.alpha(x, y) < 250) continue
            val r = image.channel(x, y, 0)
            val g = image.channel(x, y, 1)
            if (r > 240 && g < 30) red++
            if (r > 240 && g > 240) white++
        }
        assertTrue(red > 50, "outline pixels, found $red")
        assertTrue(white > 50, "fill pixels over the outline, found $white")
    }

    @Test
    fun aShadowAndABoxGrowTheImageWithoutMovingTheWords() {
        val bare = (rasterize(cue("Words", plain)) ?: return).single()
        val boxed = rasterize(
            cue("Words", plain.copy(backgroundColor = 0xC0000000.toInt(), backgroundPaddingPx = 6f, shadowOffsetPx = 3f)),
        )!!.single()
        assertEquals(bare.bitmap.height + 2 * 6 + 3, boxed.bitmap.height)
        assertEquals(bare.y - 6, boxed.y, "the box hangs above the text box, which stays where it was")
        // The corner of the box is box colour, premultiplied: black at three quarters.
        val column = boxed.inkColumns().first - boxed.x + 2
        assertEquals(0xC0, boxed.alpha(column, 2))
        assertEquals(0, boxed.channel(column, 2, 0))
    }

    @Test
    fun aLongCueBreaksAtTheSafeWidthAndAnAuthoredNewlineBreaksWhereItIs() {
        val one = (rasterize(cue("word", plain)) ?: return).single().bitmap.height
        val long = "word ".repeat(40).trim()
        val wrapped = rasterize(cue(long, plain))!!.single()
        assertTrue(wrapped.bitmap.height >= 2 * one, "forty words take more than one line")
        assertTrue(wrapped.inkColumns().first >= 32 - 3 && wrapped.inkColumns().last <= 640 - 32 + 3, "inside the margins")

        val never = rasterize(cue(long, plain, CueLayout(wrap = CueWrap.Never)))!!.single()
        assertEquals(one, never.bitmap.height, "a cue that must not wrap stays on one line")

        assertEquals(2 * one, rasterize(cue("up\ndown", plain))!!.single().bitmap.height)
        assertEquals(3 * one, rasterize(cue("up\n\ndown", plain))!!.single().bitmap.height, "an empty line keeps its height")
    }

    @Test
    fun balancedWrappingMakesTheLinesNearEqual() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen"
        val greedy = (rasterize(cue(text, plain, CueLayout(wrap = CueWrap.None))) ?: return).single()
        val balanced = rasterize(cue(text, plain, CueLayout(wrap = CueWrap.Balanced)))!!.single()
        assertEquals(greedy.bitmap.height, balanced.bitmap.height, "the same number of lines")
        val greedyInk = greedy.inkColumns()
        val balancedInk = balanced.inkColumns()
        assertTrue(
            balancedInk.last - balancedInk.first < greedyInk.last - greedyInk.first,
            "balanced lines are narrower than a filled first line: $balancedInk against $greedyInk",
        )
    }

    @Test
    fun aWordWiderThanTheLineIsCutBetweenCharacters() {
        val one = (rasterize(cue("W", plain)) ?: return).single().bitmap.height
        val image = rasterize(cue("W".repeat(120), plain, CueLayout(wrap = CueWrap.None)))!!.single()
        assertTrue(image.bitmap.height >= 2 * one, "the word is cut over lines")
        assertTrue(image.inkColumns().last <= 640 - 32 + 3, "and stays inside the margins")
    }

    @Test
    fun aPositionedCueHugsItsTextAndAnchorsOnItsPoint() {
        val layout = CueLayout(alignment = CueAlignment.TopLeft, positionX = 0.25f, positionY = 0.5f)
        val image = (rasterize(cue("Here", plain, layout)) ?: return).single()
        assertEquals(180, image.y, "the top of the text is on the point")
        assertTrue(abs(image.inkColumns().first - 160) <= 3, "the left of the text is on the point, was ${image.inkColumns()}")
    }

    @Test
    fun twoParagraphsOfOneRegionStackInsideItOverItsBackground() {
        val region = CueRegion(
            id = "r1", left = 0.1f, top = 0.5f, width = 0.8f, height = 0.4f,
            padding = CueInsets(left = 0.05f, top = 0.1f, right = 0.05f, bottom = 0.1f),
            backgroundColor = 0x80000000.toInt(),
        )
        fun paragraph(text: String, order: Int) = cue(text, plain, CueLayout(alignment = CueAlignment.BottomLeft, region = region, regionOrder = order))
        val images = rasterize(paragraph("second", 1), paragraph("first", 0)) ?: return
        assertEquals(3, images.size, "the background and two paragraphs")
        val (background, first, second) = images
        assertEquals(64, background.x)
        assertEquals(180, background.y)
        assertEquals(512, background.bitmap.width)
        assertEquals(144, background.bitmap.height)
        assertEquals(0x80, background.alpha(0, 0))
        // The padding is a tenth of 144, rounded: 14 pixels under the region's top.
        assertEquals(180 + 14, first.y)
        assertEquals(first.y + first.bitmap.height, second.y, "the second paragraph starts where the first ends")
        // 5 percent of 512 is 26 pixels of padding at the left.
        assertTrue(abs(first.inkColumns().first - (64 + 26)) <= 3, "text starts inside the padding, was ${first.inkColumns()}")
    }

    @Test
    fun spansKeepTheirOwnColoursOnOneLine() {
        val red = plain.copy(primaryColor = 0xFFFF0000.toInt(), bold = true)
        val blue = plain.copy(primaryColor = 0xFF0000FF.toInt(), bold = true)
        val cue = SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("MMMM ", red), StyledSpan("MMMM", blue)), CueLayout())
        val image = (rasterize(cue) ?: return).single()
        val middle = image.bitmap.width / 2
        var redLeft = 0
        var blueRight = 0
        var wrong = 0
        for (y in 0 until image.bitmap.height) for (x in 0 until image.bitmap.width) {
            if (image.alpha(x, y) < 250) continue
            val isRed = image.channel(x, y, 0) > 200 && image.channel(x, y, 2) < 50
            val isBlue = image.channel(x, y, 2) > 200 && image.channel(x, y, 0) < 50
            if (isRed && x < middle) redLeft++
            if (isBlue && x > middle) blueRight++
            if ((isRed && x > middle + 4) || (isBlue && x < middle - 4)) wrong++
        }
        assertTrue(redLeft > 50 && blueRight > 50, "red then blue, found $redLeft and $blueRight")
        assertEquals(0, wrong, "no span is drawn in the other's place")
    }

    @Test
    fun aRightToLeftLineOfTwoSpansIsFilledFromTheRight() {
        val red = plain.copy(primaryColor = 0xFFFF0000.toInt(), bold = true)
        val blue = plain.copy(primaryColor = 0xFF0000FF.toInt(), bold = true)
        // Hebrew: the first span is read first, so it stands at the right.
        val cue = SubtitleCue.Text(0, 1_000_000, listOf(StyledSpan("שלום ", red), StyledSpan("עולם", blue)), CueLayout())
        val image = (rasterize(cue) ?: return).single()
        var redSum = 0L
        var redCount = 0
        var blueSum = 0L
        var blueCount = 0
        for (y in 0 until image.bitmap.height) for (x in 0 until image.bitmap.width) {
            if (image.alpha(x, y) < 250) continue
            if (image.channel(x, y, 0) > 200 && image.channel(x, y, 2) < 50) { redSum += x; redCount++ }
            if (image.channel(x, y, 2) > 200 && image.channel(x, y, 0) < 50) { blueSum += x; blueCount++ }
        }
        if (redCount == 0 || blueCount == 0) return println("skipped: this browser has no Hebrew font")
        assertTrue(redSum / redCount > blueSum / blueCount, "the first span is at the right")
    }

    @Test
    fun textThatJsonMustEscapeIsDrawnAndEmptyCuesDrawNothing() {
        val images = rasterize(cue("say \"hi\" \\ tab\there \u2028 \uD83D done", plain)) ?: return
        assertTrue(images.single().inkColumns().let { it.last > it.first })
        assertTrue(rasterize(cue("", plain))!!.isEmpty())
        assertTrue(rasterize(cue("text", plain, CueLayout(marginLeft = 0.6f, marginRight = 0.6f)))!!.isEmpty(), "no room is no image")
    }

    @Test
    fun theFontAndColourStringsAreWhatACanvasReads() {
        assertEquals("36.0px sans-serif", canvasFont(CueStyle(), 36f))
        assertEquals(
            "italic bold 20.5px \"My \\\"Font\\\"\", sans-serif",
            canvasFont(CueStyle(fontFamily = "My \"Font\"", bold = true, italic = true), 20.5f),
        )
        assertEquals("rgba(255,128,0,1.0)", canvasColor(0xFFFF8000.toInt()))
        assertEquals("rgba(0,0,0,0.0)", canvasColor(0))
        assertEquals("\"a\\\"b\\\\c\\u000a\\ud83d\"", StringBuilder().appendJson("a\"b\\c\n\uD83D").toString())
    }

    /** The canvas renderer shows what the rasterizer made: the cue's pixels land on the visible canvas. */
    @Test
    fun theCanvasRendererShowsARasterizedCueOverThePicture() = kotlinx.coroutines.test.runTest {
        val images = rasterize(cue("MMMMMM", plain.copy(bold = true))) ?: return@runTest
        val canvas = subtitleTestCanvas(640, 360)
        val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> subtitleFillBlack(destination); true }
        renderer.setOverlay(io.github.yuroyami.kiteplayer.spi.SubtitleOverlay(images, 640, 360, contentHash = 7L))
        assertTrue(renderer.present(SubtitleTestFrame, 0), "the frame must draw")
        val image = images.single()
        var white = 0
        for (x in image.x until image.x + image.bitmap.width) {
            if (subtitleCanvasRed(canvas, x, image.y + image.bitmap.height / 2) > 200) white++
        }
        assertTrue(white > 20, "white subtitle pixels over the black picture, found $white")
        assertEquals(0, subtitleCanvasRed(canvas, 320, 40), "and the picture is untouched away from the cue")
        renderer.close()
    }
}

private object SubtitleTestFrame : io.github.yuroyami.kiteplayer.spi.VideoFrame {
    override val size = io.github.yuroyami.kiteplayer.VideoSize(640, 360)
    override val rotationDegrees: Int = 0
    override val hardwareSurface: io.github.yuroyami.kiteplayer.spi.HwSurfaceKind? = null
    override val pts = io.github.yuroyami.kiteplayer.Pts(0)
    override val duration: io.github.yuroyami.kiteplayer.Pts? = null
    override val pixelFormat = io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Yuv420p
    override val colorSpace = io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo()
    override val generation = io.github.yuroyami.kiteplayer.Generation(0)
    override fun close() {}
}

@JsFun(
    """() => {
      try {
        const c = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(1, 1)
          : typeof document !== 'undefined' ? document.createElement('canvas') : null;
        const x = c && c.getContext('2d');
        return !!x && typeof x.measureText === 'function' && typeof x.getImageData === 'function';
      } catch (e) { return false; }
    }""",
)
private external fun hasTextCanvas(): Boolean

@JsFun("(w, h) => new OffscreenCanvas(w, h)")
private external fun subtitleTestCanvas(width: Int, height: Int): kotlin.js.JsAny

@JsFun("(d) => { for (let i = 0; i < d.length; i += 4) { d[i] = 0; d[i + 1] = 0; d[i + 2] = 0; d[i + 3] = 255; } }")
private external fun subtitleFillBlack(destination: kotlin.js.JsAny)

@JsFun("(c, x, y) => c.getContext('2d').getImageData(x, y, 1, 1).data[0]")
private external fun subtitleCanvasRed(canvas: kotlin.js.JsAny, x: Int, y: Int): Int
