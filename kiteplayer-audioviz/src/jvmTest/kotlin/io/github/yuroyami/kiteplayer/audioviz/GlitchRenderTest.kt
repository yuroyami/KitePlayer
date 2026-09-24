package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a viewer sees of Glitch, rendered off screen the way the window renders it. */
class GlitchRenderTest {
    init { useSkiaGraphics() }

    private val directory = File("build/glitch-preview").apply { mkdirs() }

    @Test
    fun drawsABarcodeWithInkAndNoBlowOutOnTheLivelySong() {
        var ink = 0f
        var blown = 0f
        var last: BufferedImage? = null
        RenderHarness.forEachFrame(Glitch(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            val image = bitmap.toBufferedImage()
            ink = maxOf(ink, inkFraction(image))
            blown = maxOf(blown, blownFraction(image))
            if (step == 59) last = image
        }
        ImageIO.write(checkNotNull(last), "png", File(directory, "lively.png"))
        assertTrue(ink > 0.004f, "Glitch drew nothing: ink $ink")
        assertTrue(blown < 0.1f, "Glitch went white over ${blown * 100} percent of the picture")
    }

    @Test
    fun silenceHoldsAStillBarcodeThatIsLitNotBlack() {
        val quiet = motion(RenderHarness.Song.Silence)
        val music = motion(RenderHarness.Song.Lively)
        assertTrue(music.first > 0.004f, "the music barely moves the picture: ${music.first}")
        assertTrue(quiet.first <= 0.05f * music.first, "silence moves ${quiet.first} against ${music.first} with music")
        assertTrue(quiet.second > 0.04f, "the idle barcode is lit, mean light ${quiet.second}")
    }

    @Test
    fun everyBarRunsTheFullHeightAndALoudBandIsWider() {
        val image = render(WIDTH, HEIGHT, 90, { it == 89 }) { GlitchFrames.frame(it, LOUD_LOW) }.getValue(89)
        ImageIO.write(image, "png", File(directory, "clean.png"))
        for (x in 0 until WIDTH) {
            assertEquals(image.getRGB(x, 1), image.getRGB(x, HEIGHT - 2), "column $x changes between top and bottom")
        }
        val runs = litRuns(image, 20)
        assertTrue(runs.size in 40..64, "expected the bars as separate runs, found ${runs.size}")
        val loud = runs.take(8).average()
        val quiet = runs.takeLast(8).average()
        assertTrue(loud > quiet * 1.5, "loud bars are $loud px wide and quiet bars $quiet px")
    }

    @Test
    fun aKickFringesTheEdgesInRedGreenAndBlueAndTheyAreCleanAgain120MillisecondsLater() {
        val keep = { step: Int -> step == 90 || step == 97 }
        val base = render(WIDTH, HEIGHT, 98, keep) { GlitchFrames.frame(it, LOUD_LOW) }
        val kicked = render(WIDTH, HEIGHT, 98, keep) {
            GlitchFrames.frame(it, LOUD_LOW, hits = if (it == 90) listOf(AudioEventKind.LowTransient to 0.9f) else emptyList())
        }
        ImageIO.write(kicked.getValue(90), "png", File(directory, "kick.png"))
        assertEquals(0, fringes(base.getValue(90)).sum(), "a clean frame has no red, green or blue")
        val split = fringes(kicked.getValue(90))
        for ((channel, name) in listOf("red", "green", "blue").withIndex()) {
            assertTrue(split[channel] > WIDTH * HEIGHT / 200, "a kick shows $name fringes: ${split[channel]} pixels")
        }
        assertContentEquals(pixels(base.getValue(97)), pixels(kicked.getValue(97)), "117 ms after the kick the edges are clean")
    }

    @Test
    fun aSnareTearsHorizontalSlicesAndSlidesThemSideways() {
        val keep = { step: Int -> step == 90 || step == 95 }
        val base = render(WIDTH, HEIGHT, 96, keep) { GlitchFrames.frame(it, LOUD_LOW) }
        val torn = render(WIDTH, HEIGHT, 96, keep) {
            GlitchFrames.frame(it, LOUD_LOW, hits = if (it == 90) listOf(AudioEventKind.BodyTransient to 1f) else emptyList())
        }
        ImageIO.write(torn.getValue(90), "png", File(directory, "snare.png"))
        val before = base.getValue(90)
        val after = torn.getValue(90)
        var moved = 0
        var bands = 0
        var inBand = false
        for (y in 0 until HEIGHT) {
            val same = (0 until WIDTH).all { before.getRGB(it, y) == after.getRGB(it, y) }
            if (!same) {
                moved++
                assertTrue(shiftOf(before, after, y) != 0, "row $y changed but is not the same row slid sideways")
            }
            if (!same && !inBand) bands++
            inBand = !same
        }
        assertTrue(moved > HEIGHT / 20, "only $moved rows moved")
        assertTrue(bands in 1..8, "the tear shows as $bands bands of rows")
        assertContentEquals(pixels(base.getValue(95)), pixels(torn.getValue(95)), "five frames later the picture is clean")
    }

    @Test
    fun aBreakdownLeavesOneBrightLineWithTheWaveformRunningAlongIt() {
        val image = render(WIDTH, HEIGHT, 100, { it == 99 }) {
            GlitchFrames.frame(it, LOUD_LOW, scope = WAVE, structure = if (it == 60) AudioEventKind.Breakdown else null)
        }.getValue(99)
        ImageIO.write(image, "png", File(directory, "breakdown.png"))
        val middle = HEIGHT / 2
        for (y in 0 until HEIGHT) {
            if (abs(y - middle) <= HEIGHT * 0.08f) continue
            for (x in 0 until WIDTH) assertTrue(brightest(image.getRGB(x, y)) < 16, "($x, $y) is lit away from the line")
        }
        val lit = (0 until WIDTH).count { x -> (middle - 3..middle + 3).any { luma(image.getRGB(x, it)) > 0.5f } }
        assertTrue(lit > WIDTH * 0.9f, "the line is bright across the width: $lit of $WIDTH columns")
        val heights = (0 until WIDTH).map { x -> (0 until HEIGHT).maxBy { luma(image.getRGB(x, it)) } }
        assertTrue(heights.max() - heights.min() >= 3, "the waveform runs along the line")
    }

    @Test
    fun aDropFreezesTheFrameIntoADatamoshAndACleanFrameSnapsBackOnAFirstBeat() {
        val frames = 60 + 420
        val drawing = Glitch()
        var ended = -1
        val moshed = render(WIDTH, HEIGHT, frames, { it == 59 || it == 60 || it == 90 || it == ended }, drawing, afterFrame = { step ->
            if (ended < 0 && step > 60 && !drawing.scene.moshing) ended = step
        }) { GlitchFrames.frame(it, if (it < 60) LOUD_LOW else LOUD_HIGH, structure = if (it == 60) AudioEventKind.Drop else null) }
        // The same music with a plain section at the same moment, so both end on the same colours.
        val clean = render(WIDTH, HEIGHT, frames, { it == 90 || it == ended }) {
            GlitchFrames.frame(it, if (it < 60) LOUD_LOW else LOUD_HIGH, structure = if (it == 60) AudioEventKind.SectionBoundary else null)
        }
        assertTrue(ended > 0, "the datamosh never ended")
        val cycle = drawing.scene.cycleSeconds * 60f
        assertTrue(ended - 60 >= cycle * 0.5f - 1 && ended - 60 <= cycle * 2f + 1, "it held ${ended - 60} frames of a $cycle frame cycle")
        ImageIO.write(moshed.getValue(90), "png", File(directory, "datamosh.png"))
        // The frozen frame is kept at half resolution, so an edge may land one pixel off.
        assertTrue(nearShare(moshed.getValue(59), moshed.getValue(60)) > 0.97f, "the drop freezes the frame last shown")
        assertTrue(changedShare(clean.getValue(90), moshed.getValue(90)) > 0.1f, "the datamosh is not the clean picture")
        assertTrue(changedShare(moshed.getValue(60), moshed.getValue(90)) > 0.05f, "the frozen blocks slide")
        assertContentEquals(pixels(clean.getValue(ended)), pixels(moshed.getValue(ended)), "a clean frame snaps back, sharp")
    }

    @Test
    fun barsTakeTheirSetInTurnAndNoBarIsRedGreenOrBlue() {
        for (set in 0..2) {
            val sections = (1..set).map { 30 + it * 10 }.toSet()
            val image = render(WIDTH, HEIGHT, 80, { it == 79 }) {
                GlitchFrames.frame(it, EVEN, structure = if (it in sections) AudioEventKind.SectionBoundary else null)
            }.getValue(79)
            ImageIO.write(image, "png", File(directory, "set-$set.png"))
            assertEquals(0, fringes(image).sum(), "set $set draws a red, green or blue bar")
            val hues = litRuns(image, 20, centres = true).map { hueOf(image.getRGB(it, 20)) }
            assertTrue(hues.size >= 12)
            for (bar in 0 until 9) {
                assertTrue(hueDistance(hues[bar], hues[bar + 3]) < 12f, "bars $bar and ${bar + 3} of set $set differ")
                assertTrue(hueDistance(hues[bar], hues[bar + 1]) > 30f, "bars $bar and ${bar + 1} of set $set look alike")
            }
        }
    }

    @Test
    fun noFrameIsInvertedOrMostlyWhite() {
        var fewestBlack = 1f
        var mostWhite = 0f
        var darkest = 0
        val drawing = Glitch()
        RenderHarness.forEachFrame(drawing, 320, 200, 300, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            val image = bitmap.toBufferedImage()
            mostWhite = maxOf(mostWhite, blownFraction(image))
            // A split lays the colour layers over the narrow gaps for a moment, which is its job.
            if (drawing.scene.split > 0f || drawing.scene.staticOn) return@forEachFrame
            var black = 0
            for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
                if (brightest(image.getRGB(x, y)) < 24) black++
            }
            val share = black / ((image.width / 2f) * (image.height / 2f))
            if (share < fewestBlack) {
                fewestBlack = share
                darkest = step
                ImageIO.write(image, "png", File(directory, "least-black.png"))
            }
        }
        assertTrue(fewestBlack > 0.1f, "between faults the black between the bars shows, least $fewestBlack at frame $darkest")
        assertTrue(mostWhite < 0.1f, "no frame is mostly white, most $mostWhite")
    }

    @Test
    fun theFinishedPictureDarkensEveryThirdRowLikeAnOldScreen() {
        val width = 240
        val height = 150
        val frame = mutableStateOf(GlitchFrames.frame(0, EVEN))
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            VisualizerSurface(Glitch(), { frame.value }, VizPalette.Prism, Modifier.fillMaxSize(), post = true, framesPerSecond = 60)
        })
        val image = try {
            repeat(30) { index ->
                frame.value = GlitchFrames.frame(index, EVEN)
                scene.render(index * 16_666_667L).close()
            }
            scene.render(30 * 16_666_667L).use { it.toComposeImageBitmap().toBufferedImage() }
        } finally {
            scene.close()
        }
        ImageIO.write(image, "png", File(directory, "surface.png"))
        var dark = 0.0
        var light = 0.0
        var darkRows = 0
        var lightRows = 0
        for (y in 3 until height / 3) {
            var sum = 0.0
            for (x in 0 until width) sum += luma(image.getRGB(x, y))
            if (y % 3 == 2) { dark += sum; darkRows++ } else { light += sum; lightRows++ }
        }
        val ratio = (dark / darkRows) / (light / lightRows)
        assertTrue(ratio in 0.8..0.9, "every third row keeps $ratio of the light")
    }

    /** Mean change between frames, and mean light, over the settled part of a run. */
    private fun motion(song: RenderHarness.Song): Pair<Float, Float> {
        var previous: IntArray? = null
        var change = 0f
        var light = 0f
        var counted = 0
        RenderHarness.forEachFrame(Glitch(), 160, 100, 150, VizPalette.Prism, song) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val now = IntArray(160 * 100)
            bitmap.readPixels(now)
            previous?.let { change += meanDifference(it, now) }
            light += now.map { luma(it) }.average().toFloat()
            counted++
            previous = now
        }
        return change / (counted - 1) to light / counted
    }
}

/** Frames built by hand for Glitch, so two renders differ only by what a test changes. */
internal object GlitchFrames {
    /**
     * The frame for [step]. The level is low enough that no scan bar rolls, and the energy high
     * enough that the bars are well lit, so rows compare exactly.
     */
    fun frame(
        step: Int,
        bands: FloatArray,
        scope: FloatArray = FloatArray(256),
        hits: List<Pair<AudioEventKind, Float>> = emptyList(),
        structure: AudioEventKind? = null,
    ): SpectrumFrame {
        val pts = step * 1_000_000L / 60L
        val events = ArrayList<DeliveredAudioEvent>()
        for ((kind, strength) in hits) {
            events += DeliveredAudioEvent(AudioEvent(Generation.Initial, 0L, step.toLong(),
                AudioDetection(kind, pts, pts, strength, 0.9f, 0.5f), AudioEventSource.LiveTransient), 0L)
        }
        if (structure != null) {
            events += DeliveredAudioEvent(AudioEvent(Generation.Initial, 0L, step.toLong(),
                AudioDetection(structure, pts, pts, 0.7f, 0.8f, 0.5f), AudioEventSource.LiveStructure), 0L)
        }
        return SpectrumFrame(
            ptsMicros = pts, bands = bands, peaks = bands, scope = scope,
            level = 0.08f, bass = 0.3f, mid = 0.3f, treble = 0.3f, beat = 0f, pulse = 0f,
            energy = 0.8f, mood = 0.5f, density = 0.4f,
            events = AudioEventDelivery(Generation.Initial, 0L, pts, events.toTypedArray()),
        )
    }
}

private const val WIDTH = 480
private const val HEIGHT = 270

/** Loud low bands and quiet high ones, then the other way round. */
private val LOUD_LOW = FloatArray(48) { if (it < 24) 0.4f else 0.04f }
private val LOUD_HIGH = FloatArray(48) { if (it < 24) 0.04f else 0.4f }
private val EVEN = FloatArray(48) { 0.2f }
private val WAVE = FloatArray(256) { 0.5f * sin(2f * PI.toFloat() * 4f * it / 256f) }

/** Renders [frames] frames of [drawing] from [source] and keeps the steps [keep] accepts. */
private fun render(
    width: Int,
    height: Int,
    frames: Int,
    keep: (Int) -> Boolean,
    drawing: Glitch = Glitch(),
    afterFrame: (Int) -> Unit = {},
    source: (Int) -> SpectrumFrame,
): Map<Int, BufferedImage> {
    val kept = HashMap<Int, BufferedImage>()
    RenderHarness.forEachFrameOf(drawing, width, height, frames, VizPalette.Prism, source) { bitmap, step ->
        afterFrame(step)
        if (keep(step)) kept[step] = bitmap.toBufferedImage()
    }
    return kept
}

private fun pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

private fun luma(pixel: Int): Float =
    0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f + 0.0722f * (pixel and 0xFF) / 255f

private fun brightest(pixel: Int): Int = maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)

private fun meanDifference(a: IntArray, b: IntArray): Float {
    var sum = 0f
    for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
    return sum / a.size
}

private fun meanDifference(a: BufferedImage, b: BufferedImage): Float = meanDifference(pixels(a), pixels(b))

/** The share of pixels of [b] that match [a] at the same place or one pixel to either side. */
private fun nearShare(a: BufferedImage, b: BufferedImage): Float {
    var near = 0
    for (y in 0 until a.height) for (x in 0 until a.width) {
        val pixel = b.getRGB(x, y)
        if ((-1..1).any { dx -> x + dx in 0 until a.width && close(a.getRGB(x + dx, y), pixel) }) near++
    }
    return near.toFloat() / (a.width * a.height)
}

private fun close(a: Int, b: Int): Boolean =
    (0..2).sumOf { channel -> abs((a shr (channel * 8) and 255) - (b shr (channel * 8) and 255)) } <= 18

/** The share of pixels that differ by more than a rounding step. */
private fun changedShare(a: BufferedImage, b: BufferedImage): Float {
    val first = pixels(a)
    val second = pixels(b)
    var changed = 0
    for (index in first.indices) {
        val distance = (0..2).sumOf { channel -> abs((first[index] shr (channel * 8) and 255) - (second[index] shr (channel * 8) and 255)) }
        if (distance > 18) changed++
    }
    return changed.toFloat() / first.size
}

/**
 * Pixels that read as red, green or blue, counted per channel: one channel bright and at least two and
 * a half times the next. No swatch passes that at any light, so only a split of the layers makes them.
 */
private fun fringes(image: BufferedImage): IntArray {
    val out = IntArray(3)
    for (pixel in pixels(image)) {
        val channels = intArrayOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
        val strongest = (0..2).maxBy { channels[it] }
        val next = (0..2).filter { it != strongest }.maxOf { channels[it] }
        if (channels[strongest] >= 120 && channels[strongest] >= next * 2.5f) out[strongest]++
    }
    return out
}

/** The lengths of the lit runs along row [y], or their centres. */
private fun litRuns(image: BufferedImage, y: Int, centres: Boolean = false): List<Int> {
    val out = ArrayList<Int>()
    var start = -1
    for (x in 0..image.width) {
        val lit = x < image.width && brightest(image.getRGB(x, y)) > 30
        if (lit && start < 0) start = x
        if (!lit && start >= 0) {
            out += if (centres) (start + x - 1) / 2 else x - start
            start = -1
        }
    }
    return out
}

/** How far row [y] of [after] is row [y] of [before] slid sideways and wrapped, or 0 when it is not. */
private fun shiftOf(before: BufferedImage, after: BufferedImage, y: Int): Int {
    val width = before.width
    for (shift in 1 until width) {
        var same = 0
        for (x in 0 until width) if (after.getRGB(x, y) == before.getRGB((x - shift + width) % width, y)) same++
        if (same >= width * 0.97f) return shift
    }
    return 0
}

private fun hueOf(pixel: Int): Float {
    val hsb = java.awt.Color.RGBtoHSB(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF, null)
    return hsb[0] * 360f
}

private fun hueDistance(a: Float, b: Float): Float {
    val turn = abs(a - b) % 360f
    return if (turn > 180f) 360f - turn else turn
}

private fun inkFraction(image: BufferedImage): Float {
    val background = image.getRGB(0, 0)
    var different = 0
    for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
        val pixel = image.getRGB(x, y)
        val distance = abs((pixel shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
            abs((pixel shr 8 and 0xFF) - (background shr 8 and 0xFF)) + abs((pixel and 0xFF) - (background and 0xFF))
        if (distance > 18) different++
    }
    return different.toFloat() / ((image.width / 2) * (image.height / 2))
}

private fun blownFraction(image: BufferedImage): Float {
    var blown = 0
    for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
        val pixel = image.getRGB(x, y)
        if ((pixel shr 16 and 0xFF) > 245 && (pixel shr 8 and 0xFF) > 245 && (pixel and 0xFF) > 245) blown++
    }
    return blown.toFloat() / ((image.width / 2) * (image.height / 2))
}
