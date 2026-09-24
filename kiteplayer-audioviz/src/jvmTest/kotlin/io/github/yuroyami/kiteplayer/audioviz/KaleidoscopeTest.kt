package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Kaleidoscope
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The kaleidoscope whose seed is the stereo image: it draws from the first frame, holds still in a
 * silence, keeps mono white and puts each channel on its own side, steps its mirrors on a first beat,
 * opens on a breakdown, splits into two images on a drop, and answers what it declares.
 */
class KaleidoscopeTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsASharpFigureFromTheFirstFramesOfALivelySong() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(Kaleidoscope(), WIDTH, HEIGHT, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, _ ->
            val pixels = pixelsOf(bitmap)
            ink = maxOf(ink, pixels.count { distance(it, pixels[0]) > 18 }.toFloat() / pixels.size)
            blown = maxOf(blown, pixels.count { red(it) > 245 && green(it) > 245 && blue(it) > 245 }.toFloat() / pixels.size)
        }
        assertTrue(ink > 0.004f, "the figure put down ink on only ${percent(ink)} of the picture")
        assertTrue(blown < 0.3f, "${percent(blown)} of the picture is blown to white")
    }

    @Test
    fun aSilenceHoldsTheFigureStillAtAThirdOfItsLight() {
        var first: IntArray? = null
        val quiet = changeAfter(RenderHarness.Song.Silence) { pixels, step -> if (step == 0) first = pixels }
        val music = changeAfter(RenderHarness.Song.Lively) { _, _ -> }
        // The first frame already shows the figure, lit at about a third, and never black.
        val lit = checkNotNull(first).filter { brightest(it) > 12 }.map { brightest(it) }.sorted()
        assertTrue(lit.size > 200, "the first frame of a silence shows only ${lit.size} lit pixels")
        val typical = lit[lit.size / 2] / 255f
        assertTrue(typical in 0.25f..0.45f, "the figure is lit at ${percent(typical)} in a silence")
        assertTrue(music > 0.004f, "the music render barely moves: $music")
        assertTrue(quiet <= 0.05f * music, "a silence moves ${percent(quiet / music)} as much as the music")
    }

    @Test
    fun aMonoSongDrawsACleanWhiteStar() {
        val player = SongPlayer(SyntheticSong.drumLoop(12f), bandCount = 48)
        var coloured = 0
        var lit = 0
        render(Kaleidoscope(), 240, { player.next(DELTA) }, { player.future }) { pixels, step, _ ->
            if (step < 60) return@render
            for (pixel in pixels) {
                if (brightest(pixel) > 40) lit++
                if (magenta(pixel) || cyan(pixel)) coloured++
            }
        }
        assertTrue(lit > 10_000, "the star is barely there: $lit lit pixels")
        assertTrue(coloured <= lit / 1000, "a mono song left $coloured coloured pixels among $lit lit ones")
    }

    @Test
    fun swappingTheChannelsSwapsTheMagentaAndCyanSides() {
        val side = SyntheticSong.drumLoopSide(12f)
        val plain = colouredFrames(side)
        val swapped = colouredFrames(FloatArray(side.size) { -side[it] })
        // Swapped channels draw the mirror image of the figure with the two colours exchanged.
        var same = 0
        var exchanged = 0
        var magentas = 0
        for (index in plain.indices) {
            val a = plain[index]
            val b = swapped[index]
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val pixel = a[y * WIDTH + x]
                    if (!magenta(pixel)) continue
                    magentas++
                    val mirrored = b[y * WIDTH + WIDTH - 1 - x]
                    if (cyan(mirrored)) exchanged++
                    if (magenta(mirrored)) same++
                }
            }
        }
        assertTrue(magentas > 500, "the stereo fixture drew only $magentas magenta pixels")
        assertTrue(exchanged > 4 * same, "swapped channels: $exchanged exchanged against $same unchanged")
    }

    @Test
    fun theMirrorCountStepsOnlyOnAFirstBeat() {
        val calm = SyntheticSong.calmPad(6f)
        val drums = SyntheticSong.drumLoop(12f)
        val player = SongPlayer(calm + drums, bandCount = 48)
        val seen = LinkedHashSet<Int>()
        var changes = 0
        var previous = -1
        val drawing = Kaleidoscope()
        render(drawing, 900, { player.next(DELTA) }, { player.future }, 96, 60) { _, _, _ ->
            val count = drawing.mirrors
            seen += count
            if (previous >= 0 && count != previous) {
                changes++
                assertTrue(drawing.firstBeat, "the mirror count went from $previous to $count off the first beat of a bar")
            }
            previous = count
        }
        assertTrue(seen.all { it in ALLOWED }, "mirror counts seen: $seen")
        assertTrue(changes >= 1, "the count never followed the drums in: $seen")
    }

    @Test
    fun aBreakdownOpensTheFoldToTwoMirrors() {
        val drawing = Kaleidoscope()
        val counts = IntArray(120)
        render(drawing, 120, { InjectedFrames.frame(VizDriver.Breakdown, it) }) { _, step, _ -> counts[step] = drawing.mirrors }
        val landed = InjectedFrames.STRUCTURE_STEP
        assertTrue(counts[landed - 1] != 2, "the fold was open before the breakdown")
        assertTrue((landed until 120).all { counts[it] == 2 }, "after a breakdown the counts were ${counts.drop(landed).distinct()}")
        assertTrue(drawing.trailAt(0.5f) > 0.97f, "a breakdown keeps the long echo")
    }

    @Test
    fun aDropSplitsTheFigureIntoMagentaAndCyanImagesAQuarterApart() {
        val drawing = Kaleidoscope()
        val landed = InjectedFrames.STRUCTURE_STEP
        var split: IntArray? = null
        var countAtDrop = 0
        var met = -1
        render(drawing, 700, { InjectedFrames.frame(VizDriver.Drop, it) }) { pixels, step, _ ->
            if (step == landed) {
                split = pixels
                countAtDrop = drawing.mirrors
            }
            if (step > landed && met < 0 && drawing.apart == 0f) met = step
        }
        assertEquals(16, countAtDrop, "the drop bar has sixteen mirrors")
        val pixels = checkNotNull(split)
        val magentaX = meanX(pixels) { magenta(it) }
        val cyanX = meanX(pixels) { cyan(it) }
        val gap = (cyanX - magentaX) / WIDTH
        assertTrue(gap in 0.2f..0.32f, "the two images sit ${percent(gap)} of the width apart")
        assertTrue(met in landed + 1..landed + 600, "the two images never met, or met at step $met")
    }

    @Test
    fun aFullKickPunchesTheFigureOutEightPercent() {
        val drawing = Kaleidoscope()
        var rest = 0f
        var most = 0f
        render(drawing, 100, { InjectedFrames.frame(VizDriver.LowHit, it, strength = 1f) }, width = 64, height = 40) { _, step, _ ->
            if (step == InjectedFrames.HIT_STEPS.first() - 1) rest = drawing.reach
            if (step in InjectedFrames.HIT_STEPS.first() until InjectedFrames.HIT_STEPS[1]) most = maxOf(most, drawing.reach)
        }
        val punch = most / rest - 1f
        assertTrue(punch in 0.07f..0.09f, "a full kick punched the figure out by ${percent(punch)}")
    }

    @Test
    fun everyDeclaredDriverMovesThePictureAndNoOtherDoes() {
        val mapping = checkNotNull(Kaleidoscope().mapping)
        val runs = RenderHarness.inParallel(listOf<VizDriver?>(null) + VizDriver.entries) { driver -> probe(driver) }
        val base = runs.first()
        val answers = VizDriver.entries.withIndex().associate { (index, driver) -> driver to DriverProbe.compare(base, runs[index + 1]) }
        val problems = ArrayList<String>()
        for (drive in mapping.drives) {
            val answer = answers.getValue(drive.driver)
            val moved = abs(answer.of(drive.property))
            val floor = when (drive.property) {
                VizProperty.Shape, VizProperty.Spawn, VizProperty.Camera, VizProperty.Cut -> 0.004f
                VizProperty.Speed -> 0.001f
                else -> 0.002f
            }
            if (moved < floor) problems += "${drive.driver} moves ${drive.property} by only ${thousandths(moved)}"
            if (drive.driver in HITS || drive.driver in STRUCTURE) {
                val landed = if (drive.driver in HITS) InjectedFrames.HIT_STEPS.first() else InjectedFrames.STRUCTURE_STEP
                val allowed = landed + 2 + (drive.response.delaySeconds * 60f).roundToInt()
                if (answer.startStep < 0 || answer.startStep > allowed) problems += "${drive.driver} answered at step ${answer.startStep}"
            }
        }
        val typical = mapping.drives.map { abs(answers.getValue(it.driver).difference) }.sorted().let { it[it.size / 2] }
        for (driver in VizDriver.entries) {
            if (driver in mapping.drivers) continue
            val moved = answers.getValue(driver).difference
            if (moved >= maxOf(0.01f, typical)) problems += "$driver moves the picture by ${thousandths(moved)} and is not declared"
        }
        val kick = mapping.drives.first { it.driver in HITS && it.curve == VizCurve.Scaled }.driver
        val hard = DriverProbe.compare(base, probe(kick, strength = 0.9f)).difference
        val soft = DriverProbe.compare(base, probe(kick, strength = 0.25f)).difference
        val unsupported = DriverProbe.compare(base, probe(kick, confidence = 0.1f)).difference
        if (hard <= soft) problems += "$kick answers a hard hit ${thousandths(hard)} and a soft one ${thousandths(soft)}"
        if (unsupported > DriverProbe.NOTICED) problems += "$kick answered an unsupported hit by ${thousandths(unsupported)}"
        answers.forEach { (driver, answer) -> println("  ${driver.name.padEnd(10)} ${thousandths(answer.difference)} start ${answer.startStep}") }
        assertTrue(problems.isEmpty(), "declarations the picture does not keep:\n" + problems.joinToString("\n"))
    }

    /** One run on hand-built frames, the way the catalogue's driver probe renders a drawing. */
    private fun probe(driver: VizDriver?, strength: Float = InjectedFrames.HIT_STRENGTH,
        confidence: Float = InjectedFrames.HIT_CONFIDENCE): DriverProbe.Run {
        val frames = ArrayList<IntArray>()
        render(Kaleidoscope(), DriverProbe.FRAMES, { InjectedFrames.frame(driver, it, strength, confidence) },
            width = DriverProbe.WIDTH, height = DriverProbe.HEIGHT) { pixels, step, _ ->
            if (step >= DriverProbe.FROM) frames += pixels
        }
        return DriverProbe.Run(frames, DriverProbe.WIDTH, DriverProbe.HEIGHT)
    }

    /** The frames from the second half of a run on the drum loop with [side] as its stereo. */
    private fun colouredFrames(side: FloatArray): List<IntArray> {
        val player = SongPlayer(SyntheticSong.drumLoop(12f), bandCount = 48, side = side)
        val out = ArrayList<IntArray>()
        render(Kaleidoscope(), 180, { player.next(DELTA) }, { player.future }) { pixels, step, _ -> if (step >= 90) out += pixels }
        return out
    }

    /** Mean frame to frame change of a render, after the two seconds it is given to settle. */
    private fun changeAfter(song: RenderHarness.Song, first: (IntArray, Int) -> Unit): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        RenderHarness.forEachFrame(Kaleidoscope(), WIDTH, HEIGHT, 240, VizPalette.Prism, song) { bitmap, step ->
            val pixels = pixelsOf(bitmap)
            first(pixels, step)
            if (step >= 120) {
                previous?.let { before ->
                    var change = 0f
                    for (index in pixels.indices) change += abs(luma(pixels[index]) - luma(before[index]))
                    sum += change / pixels.size
                    count++
                }
            }
            previous = pixels
        }
        return if (count == 0) 0f else sum / count
    }

    private fun render(
        drawing: Kaleidoscope,
        frames: Int,
        source: (Int) -> SpectrumFrame,
        future: () -> io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture? = { null },
        width: Int = WIDTH,
        height: Int = HEIGHT,
        onFrame: (IntArray, Int, Kaleidoscope) -> Unit,
    ) {
        RenderHarness.forEachFrameOf(drawing, width, height, frames, VizPalette.Prism, source, future) { bitmap, step ->
            onFrame(pixelsOf(bitmap), step, drawing)
        }
    }

    private fun pixelsOf(bitmap: androidx.compose.ui.graphics.ImageBitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    private fun meanX(pixels: IntArray, wanted: (Int) -> Boolean): Float {
        var sum = 0f
        var count = 0
        for (index in pixels.indices) {
            if (!wanted(pixels[index])) continue
            sum += index % WIDTH
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun red(pixel: Int): Int = pixel shr 16 and 0xFF
    private fun green(pixel: Int): Int = pixel shr 8 and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF
    private fun brightest(pixel: Int): Int = maxOf(red(pixel), green(pixel), blue(pixel))
    private fun luma(pixel: Int): Float = (0.2126f * red(pixel) + 0.7152f * green(pixel) + 0.0722f * blue(pixel)) / 255f
    private fun distance(a: Int, b: Int): Int = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))

    /** The hue in degrees of a pixel with real colour in it, or -1 for a grey or white one. */
    private fun hue(pixel: Int): Float {
        val r = red(pixel)
        val g = green(pixel)
        val b = blue(pixel)
        val most = maxOf(r, g, b)
        val chroma = most - minOf(r, g, b)
        if (chroma < 40) return -1f
        val sector = when (most) {
            r -> ((g - b).toFloat() / chroma + 6f) % 6f
            g -> (b - r).toFloat() / chroma + 2f
            else -> (r - g).toFloat() / chroma + 4f
        }
        return sector * 60f
    }

    private fun magenta(pixel: Int): Boolean = hue(pixel) in 290f..350f
    private fun cyan(pixel: Int): Boolean = hue(pixel) in 165f..205f

    private fun percent(value: Float): String = "${(value * 1000f).roundToInt() / 10f} %"
    private fun thousandths(value: Float): String = (value * 1000f).roundToInt().toString()

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 200
        const val DELTA = 1f / 60f
        val ALLOWED = setOf(2, 4, 6, 8, 12, 16)
        val HITS = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)
        val STRUCTURE = setOf(VizDriver.Section, VizDriver.Drop, VizDriver.Breakdown)
    }
}
