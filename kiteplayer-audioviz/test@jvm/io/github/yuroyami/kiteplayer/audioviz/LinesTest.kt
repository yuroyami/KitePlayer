package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Lines
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Lines by Silvio Paganini: it draws, it holds still in silence, and it keeps the page's knee. */
class LinesTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(Lines(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the lines should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = changeAndInk(RenderHarness.Song.Silence)
        val music = changeAndInk(RenderHarness.Song.Lively)
        assertTrue(silence.first < 0.0005f, "silence should hold the lines still, changed ${silence.first}")
        assertTrue(silence.second > 0.004f, "the flat lines should stay on screen in silence, ink ${silence.second}")
        assertTrue(music.first > 0.001f, "music should move the lines, changed ${music.first}")
        assertTrue(silence.first <= 0.2f * music.first, "silence ${silence.first} against music ${music.first}")
    }

    @Test
    fun aHeightIsTheFloorOrAPeakWithNothingBetween() {
        for (byte in 0..Lines.KNEE) assertEquals(5f, Lines.heightOf(byte), "byte $byte stays on the floor")
        for (byte in Lines.KNEE + 1..255) {
            val height = Lines.heightOf(byte)
            assertTrue(height >= 30f && height <= 76.5f, "byte $byte gives $height")
        }
        assertEquals(30.3f, Lines.heightOf(101), 1e-4f)
        assertEquals(76.5f, Lines.heightOf(255), 1e-4f)
        assertTrue((0..255).map { Lines.heightOf(it) }.none { it > 5f && it < 30f })
    }

    @Test
    fun theMiddleLinesReadTheBassFromTheBrowserAnalyserLayout() {
        val starts = (0 until Lines.LINES).map { Lines.bandStart(it) }
        assertEquals(0, starts[10])
        assertEquals(0, starts[11])
        assertEquals(640, starts[0])
        assertEquals(640, starts[21])
        for (index in starts.indices) assertEquals(starts[index], starts[Lines.LINES - 1 - index], "line $index")
        val analyser = Lines().analyser
        assertEquals(1024, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        assertTrue(starts.all { it + Lines.RANGE < analyser.binCount }, "every band fits in the 1024 bins")
    }

    @Test
    fun thePageFramesRunSixtyTimesPerHeardSecondAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val frames = Lines.PageFrames()
            var total = 0
            repeat(rate * 2) { total += frames.advance(1f / rate) }
            assertTrue(total in 119..121, "$rate Hz gave $total page frames in two seconds")
        }
        val silent = Lines.PageFrames()
        repeat(120) { assertEquals(0, silent.advance(0f)) }
        val jittery = Lines.PageFrames()
        repeat(600) { assertEquals(1, jittery.advance(if (it % 2 == 0) 1.1f / 60f else 0.9f / 60f)) }
    }

    @Test
    fun theLinesBehindShowThroughATallFrontRidge() {
        // A loud 14.5 kHz tone lands in bins 640 to 704, which only the back and the front line read.
        val tone = FloatArray(48_000 * 7) { 0.5f * sin(2.0 * PI * 14_500.0 * it / 48_000.0).toFloat() }
        val player = SongPlayer(tone, bandCount = 48)
        var last: BufferedImage? = null
        RenderHarness.forEachFrameOf(Lines(), 960, 540, 180, VizPalette.Prism, source = { player.next(1f / 60f) }) { bitmap, step ->
            if (step == 179) last = with(RenderHarness) { bitmap.toBufferedImage() }
        }
        val image = checkNotNull(last)
        // The highest ink is the tip of the front line's ridge: no other line can reach that high.
        var ridgeX = -1
        var ridgeY = image.height
        for (x in 0 until image.width) {
            for (y in 0 until ridgeY) {
                if (lit(image.getRGB(x, y))) {
                    ridgeX = x
                    ridgeY = y
                    break
                }
            }
        }
        assertTrue(ridgeY < image.height / 5, "the front line should rise into a tall ridge, its top is at $ridgeY")
        // Straight down from that tip, the flat lines behind the ridge still show: nothing is filled.
        var runs = 0
        var inRun = false
        for (y in (image.height * 0.4f).toInt() until (image.height * 0.8f).toInt()) {
            val on = lit(image.getRGB(ridgeX, y))
            if (on && !inRun) runs++
            inRun = on
        }
        assertTrue(runs >= 10, "only $runs lines show below the front ridge at x = $ridgeX")
    }

    @Test
    fun theFlatFormAtRestHasTodaysCamera() {
        val lines = Lines()
        RenderHarness.forEachFrame(lines, 320, 180, 2, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ -> }
        assertTrue(lines.eyeX == 0f, "eyeX is ${lines.eyeX}")
        assertTrue(lines.eyeY == 45f, "eyeY is ${lines.eyeY}")
        assertTrue(lines.eyeZ == 240f, "eyeZ is ${lines.eyeZ}")
        assertTrue(lines.bendValue == 0f, "bend is ${lines.bendValue}")
        assertTrue(lines.dipValue == 0f, "dip is ${lines.dipValue}")
        assertEquals("Flat", lines.forms.form)
        assertEquals(0, lines.forms.births)
    }

    @Test
    fun theFormMorphsOverFourCyclesOnTheDrumLoop() {
        val lines = Lines()
        var settled = true
        var startStep = 0
        var startCycleSeconds = 0f
        val closed = ArrayList<MorphInterval>()
        RenderHarness.forEachFrame(lines, 64, 36, 7200, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            val value = lines.morphValue
            if (settled && value < 0.999f) {
                settled = false
                startStep = step
                startCycleSeconds = lines.cycleSecondsValue
            } else if (!settled && value >= 0.999f) {
                settled = true
                closed.add(MorphInterval(step - startStep, startCycleSeconds))
            }
        }
        val morphs = lines.forms.morphs
        println("morphs $morphs, closed intervals ${closed.size}: " + closed.joinToString { it.describe() })
        assertTrue(morphs >= 3, "the drum loop should start at least 3 morphs, it started $morphs")
        assertTrue(closed.size >= 2, "at least 2 morphs should settle, ${closed.size} did")
        for (interval in closed) {
            assertTrue(
                interval.frames <= 5 * 60 * interval.cycleSeconds,
                "a morph should settle within 5 cycles, this one took ${interval.describe()}",
            )
        }
        val longest = closed.maxByOrNull { it.frames }
        assertTrue(
            longest != null && longest.frames >= 3 * 60 * longest.cycleSeconds,
            "an uninterrupted morph should take about 4 cycles, the longest took ${longest?.describe()}",
        )
    }

    @Test
    fun theTunnelWrapsEveryLineRoundTheAxis() {
        val lines = renderTwoSilentFramesIn("Tunnel")
        val right = lines.screenXOf(10, 0)
        val left = lines.screenXOf(10, 256)
        val above = lines.screenYOf(10, 128)
        val below = lines.screenYOf(10, 384)
        println("tunnel on a 320 by 180 canvas: x at vertex 0 $right, at 256 $left, y at vertex 128 $above, at 384 $below")
        assertTrue(right > 160f, "vertex 0 should sit right of the middle column, x is $right")
        assertTrue(left < 160f, "vertex 256 should sit left of the middle column, x is $left")
        assertTrue(above < 90f, "vertex 128 should sit above the middle row, y is $above")
        assertTrue(below > 90f, "vertex 384 should sit below the middle row, y is $below")
    }

    @Test
    fun theMirrorDrawsEachLineTwice() {
        val mirror = renderTwoSilentFramesIn("Mirror")
        val flat = renderTwoSilentFramesIn("Flat")
        println("paths drawn: Mirror ${mirror.pathsDrawn}, Flat ${flat.pathsDrawn}")
        assertEquals(44, mirror.pathsDrawn)
        assertEquals(22, flat.pathsDrawn)
    }

    @Test
    fun theFanRadiatesFromAPointWithTheBassLinesNearest() {
        val lines = renderTwoSilentFramesIn("Fan")
        val farX = (0 until Lines.LINES).map { lines.screenXOf(it, 0) }.filter { !it.isNaN() }
        val nearX = (0 until Lines.LINES).map { lines.screenXOf(it, 511) }.filter { !it.isNaN() }
        assertTrue(farX.isNotEmpty() && nearX.isNotEmpty(), "the fan should be on screen, far ${farX.size} near ${nearX.size}")
        val farSpread = farX.max() - farX.min()
        val nearSpread = nearX.max() - nearX.min()
        val bassDepth = lines.depthOf(10, 256)
        val trebleDepth = lines.depthOf(0, 256)
        println("fan spread of x: far end $farSpread, near end $nearSpread; depth of the middle vertex: bass line $bassDepth, treble line $trebleDepth")
        assertTrue(farSpread < nearSpread / 4f, "the lines should meet at the far point, far spread $farSpread near spread $nearSpread")
        assertTrue(bassDepth < trebleDepth, "the bass line should be nearer, depth $bassDepth against $trebleDepth")
    }

    @Test
    fun aRightHeavyTraceIsAPositiveBalance() {
        val quiet = FloatArray(64) { 0.25f }
        val loud = FloatArray(64) { 0.5f }
        assertTrue(Lines.balanceOf(quiet, loud) > 0f, "right heavy gave ${Lines.balanceOf(quiet, loud)}")
        assertTrue(Lines.balanceOf(loud, quiet) < 0f, "left heavy gave ${Lines.balanceOf(loud, quiet)}")
        assertEquals(0f, Lines.balanceOf(loud, loud.copyOf()), 1e-4f)
    }

    @Test
    fun aLeftHeavyTraceBendsTheRoadLeft() {
        val lines = Lines()
        RenderHarness.forEachFrameOf(
            lines, 160, 90, 240, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Waveform, step) },
        ) { _, _ -> }
        val middle = lines.screenXOf(0, 256)
        println("left heavy trace: bend ${lines.bendValue}, farthest middle vertex at x $middle of 160")
        assertTrue(lines.bendValue < -0.1f, "the road should bend left, bend is ${lines.bendValue}")
        assertTrue(middle < 80f - 2f, "the farthest line should sit left of the middle column, x is $middle")
    }

    @Test
    fun theCameraSwaysOnTheSlowCycleAndRestsInSilence() {
        // One run, loud then silent: a second harness run would restart the drawing and prove nothing.
        val lines = Lines()
        val lively = RenderHarness.player(RenderHarness.Song.Lively, 14f)
        val silent = RenderHarness.player(RenderHarness.Song.Silence, 8f)
        var sway = 0f
        RenderHarness.forEachFrameOf(
            lines, 96, 54, 840, VizPalette.Prism,
            source = { step -> if (step < 600) lively.next(1f / 60f) else silent.next(1f / 60f) },
        ) { _, step ->
            if (step < 600) sway = max(sway, abs(lines.eyeX))
        }
        val rest = abs(lines.eyeX)
        println("camera sway in music $sway, eyeX after silence $rest")
        assertTrue(sway > 2f, "the camera should glide under music, the widest eyeX was $sway")
        assertTrue(rest < 0.5f, "the camera should come home in silence, eyeX is $rest")
    }

    @Test
    fun aQuietFarLineTakesTheFogTintAndAPeakedLineStaysWhite() {
        val calm = Lines()
        RenderHarness.forEachFrame(calm, 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Calm) { _, _ -> }
        val far = calm.lineColourOf(0)
        println("far quiet line: red ${far.red}, green ${far.green}, blue ${far.blue}")
        assertTrue(abs(far.red - far.blue) > 0.01f, "the far line should carry the tint, red ${far.red} blue ${far.blue}")

        val tone = FloatArray(48_000 * 7) { 0.5f * sin(2.0 * PI * 14_500.0 * it / 48_000.0).toFloat() }
        val player = SongPlayer(tone, bandCount = 48)
        val peaked = Lines()
        RenderHarness.forEachFrameOf(peaked, 160, 90, 120, VizPalette.Prism, source = { player.next(1f / 60f) }) { _, _ -> }
        val front = peaked.lineColourOf(21)
        println("peaked front line: red ${front.red}, green ${front.green}, blue ${front.blue}")
        assertTrue(abs(front.red - front.green) < 0.005f, "the front line should stay white, red ${front.red} green ${front.green}")
        assertTrue(abs(front.green - front.blue) < 0.005f, "the front line should stay white, green ${front.green} blue ${front.blue}")
    }

    @Test
    fun aKickSendsAWaveOutwardThroughTheStack() {
        val hit = InjectedFrames.HIT_STEPS[0]
        val kicked = runWithOnlyTheFirstHit(VizDriver.LowHit)
        val still = runWithOnlyTheFirstHit(null)
        val atHit = kicked.first[hit]
        assertTrue(atHit[10] > 0.5f && atHit[11] > 0.5f, "the hit should lift both middle lines, got ${atHit[10]} and ${atHit[11]}")
        val later = kicked.first[hit + 6]
        val back = checkNotNull((0..9).maxByOrNull { later[it] })
        val front = checkNotNull((12..21).maxByOrNull { later[it] })
        val pixelDifference = difference(kicked.second, still.second)
        println("kick wave six steps on: back peak at line $back, front peak at line $front, line 10 at ${later[10]}, pixel difference $pixelDifference")
        assertTrue(back <= 5, "the lift should run to the back, its peak is at line $back")
        assertTrue(front >= 16, "the lift should run to the front, its peak is at line $front")
        assertTrue(later[10] < 0.3f, "the middle should have let go, line 10 is ${later[10]}")
        for (step in still.first.indices) {
            assertTrue(still.first[step].all { it == 0f }, "without a kick the wave stays 0, step $step")
        }
        assertTrue(pixelDifference > 0.0005f, "the lift should show in the picture, the difference is $pixelDifference")
    }

    @Test
    fun aSnareRaisesThePeaksForABeat() {
        val lines = Lines()
        val sharpen = FloatArray(200)
        val scale = FloatArray(200)
        RenderHarness.forEachFrameOf(
            lines, 64, 36, 200, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step < InjectedFrames.HIT_STEPS[1]) VizDriver.BodyHit else null, step) },
        ) { _, step ->
            sharpen[step] = lines.sharpenValue
            scale[step] = lines.targetScale
        }
        val hit = InjectedFrames.HIT_STEPS[0]
        println("snare raise: at the hit ${sharpen[hit]}, two beats on ${sharpen[hit + 94]}, scale at the hit ${scale[hit]}")
        assertTrue(sharpen[hit] > 0.5f, "the hit should raise the peaks, got ${sharpen[hit]}")
        assertTrue(sharpen[hit + 94] < 0.1f, "the raise should be gone two beats on, got ${sharpen[hit + 94]}")
        assertTrue(scale[hit] > 1.1f, "the targets should grow at the hit, scale is ${scale[hit]}")
        for (step in sharpen.indices) {
            assertTrue(abs(scale[step] - (1f + Lines.SHARPEN * sharpen[step])) < 1e-5f, "scale follows the raise at step $step")
        }
    }

    /** A fresh drawing held in [form] for two silent frames of 320 by 180, so the camera and the blend are settled. */
    private fun renderTwoSilentFramesIn(form: String): Lines {
        val lines = Lines()
        val silent = RenderHarness.player(RenderHarness.Song.Silence, 2f)
        RenderHarness.forEachFrameOf(
            lines, 320, 180, 2, VizPalette.Prism,
            source = { silent.next(1f / 60f) },
            beforeDraw = { lines.jumpTo(form) },
        ) { _, _ -> }
        return lines
    }

    /** One morph from its first frame below full weight to the frame it settled, with the cycle it was measured against. */
    private class MorphInterval(val frames: Int, val cycleSeconds: Float) {
        fun describe(): String = "$frames frames, ${"%.2f".format(frames / (60f * cycleSeconds))} cycles of ${"%.2f".format(cycleSeconds)} s"
    }

    /** One fresh render of 160 frames where only the first hit of [driver] lands: the wave per step, and the pixels of step 70. */
    private fun runWithOnlyTheFirstHit(driver: VizDriver?): Pair<List<FloatArray>, IntArray> {
        val lines = Lines()
        val waves = ArrayList<FloatArray>()
        var pixels = IntArray(0)
        RenderHarness.forEachFrameOf(
            lines, 96, 54, 160, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step < InjectedFrames.HIT_STEPS[1]) driver else null, step) },
        ) { bitmap, step ->
            waves.add(FloatArray(Lines.LINES) { lines.waveAt(it) })
            if (step == 70) {
                pixels = IntArray(96 * 54)
                bitmap.readPixels(pixels)
            }
        }
        return waves to pixels
    }

    /** The mean frame-to-frame change over the last second of three, and the ink of the last frame. */
    private fun changeAndInk(song: RenderHarness.Song): Pair<Float, Float> {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        var ink = 0f
        RenderHarness.forEachFrame(Lines(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = IntArray(320 * 200)
            bitmap.readPixels(pixels)
            if (step >= 120) {
                previous?.let {
                    change += difference(it, pixels)
                    counted++
                }
            }
            previous = pixels
            if (step == 179) ink = inkFraction(with(RenderHarness) { bitmap.toBufferedImage() })
        }
        return change / counted.coerceAtLeast(1) to ink
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0L
        for (index in a.indices) {
            sum += abs((a[index] shr 16 and 0xFF) - (b[index] shr 16 and 0xFF)) +
                abs((a[index] shr 8 and 0xFF) - (b[index] shr 8 and 0xFF)) +
                abs((a[index] and 0xFF) - (b[index] and 0xFF))
        }
        return sum / (a.size * 3f * 255f)
    }

    private fun lit(pixel: Int): Boolean = (pixel shr 16 and 0xFF) > 10

    /** How much of the image stopped being the corner colour, as the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                val distance = abs((pixel shr 16 and 0xFF) - (background shr 16 and 0xFF)) +
                    abs((pixel shr 8 and 0xFF) - (background shr 8 and 0xFF)) +
                    abs((pixel and 0xFF) - (background and 0xFF))
                if (distance > 18) different++
            }
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

    /** How much of the image is at or near full brightness on every channel, as the contact sheet measures it. */
    private fun blownFraction(image: BufferedImage): Float {
        var blown = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) {
                val pixel = image.getRGB(x, y)
                if ((pixel shr 16 and 0xFF) > 245 && (pixel shr 8 and 0xFF) > 245 && (pixel and 0xFF) > 245) blown++
            }
        }
        return blown.toFloat() / ((image.width / 2) * (image.height / 2))
    }
}
