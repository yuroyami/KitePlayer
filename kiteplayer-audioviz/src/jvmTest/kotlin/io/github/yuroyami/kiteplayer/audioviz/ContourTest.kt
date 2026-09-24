package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Contour
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The contour map: a map from the first frame, still in silence, islands where the bands are, rings
 * in the water, and the tides. Most checks read the drawing's own sea level and heights, because a
 * picture of a sea level is much harder to measure than the number itself.
 */
class ContourTest {

    init { useSkiaGraphics() }

    private val directory = File("build/contour-preview").apply { mkdirs() }

    @Test
    fun theLinePassCompiles() {
        val contour = Contour()
        assertTrue(contour.runs, "the contour shader did not compile:\n${contour.compileError}")
    }

    @Test
    fun theMapPutsInkDownWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        var last: BufferedImage? = null
        RenderHarness.forEachFrame(Contour(), 320, 180, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30 || step % 5 != 4) return@forEachFrame
            val image = bitmap.toBufferedImage()
            ink = maxOf(ink, inkFraction(image))
            blown = maxOf(blown, blownFraction(image))
            last = image
        }
        last?.let { ImageIO.write(it, "png", File(directory, "lively.png")) }
        assertTrue(ink > 0.004f, "the map should put ink down, had $ink")
        assertTrue(blown < 0.3f, "the map should not saturate to white, had $blown")
    }

    @Test
    fun theFirstFrameShowsTheMapAtAThirdOfItsLight() {
        val image = RenderHarness.render(Contour(), 320, 180, 1, VizPalette.Prism, RenderHarness.Song.Silence)
        ImageIO.write(image, "png", File(directory, "first-frame.png"))
        val ink = inkFraction(image)
        var brightest = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getRGB(x, y)
            brightest = maxOf(brightest, pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
        }
        assertTrue(ink > 0.01f, "the first frame should already show the map, had ink $ink")
        assertTrue(brightest in 50..120, "an idle map is lit at about a third, had a brightest channel of $brightest")
    }

    @Test
    fun silenceHoldsTheMapStill() {
        val quiet = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        assertTrue(music > 0.002f, "music should move the map, had $music")
        assertTrue(quiet <= 0.2f * music, "silence should move the map far less than music: $quiet against $music")
        assertTrue(quiet < 0.0005f, "an idle map should not move at all, had $quiet")
    }

    @Test
    fun aPausedMapKeepsItsLastPicture() {
        val contour = Contour()
        val player = RenderHarness.player(RenderHarness.Song.Lively, 8f)
        var held: SpectrumFrame? = null
        var before: IntArray? = null
        var changed = 0
        RenderHarness.forEachFrameOf(contour, 160, 90, 110, VizPalette.Prism, source = { step ->
            if (step < 80) player.next(1f / 60f).also { held = it } else checkNotNull(held).withEvents(held = true)
        }) { bitmap, step ->
            if (step < 81) return@forEachFrameOf
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            before?.let { if (!it.contentEquals(pixels)) changed++ }
            before = pixels
        }
        assertTrue(changed == 0, "a paused map should not change, but $changed frames did")
    }

    @Test
    fun aLowToneRaisesAMiddleIslandAndAHighToneAnEdgeIslet() {
        val low = liftsFor(tone(55f))
        val high = liftsFor(tone(10_000f))
        val lowest = low.indices.maxBy { low[it] }
        val highest = high.indices.maxBy { high[it] }
        println("low tone lifts ${low.joinToString { "%.2f".format(it) }}")
        println("high tone lifts ${high.joinToString { "%.2f".format(it) }}")
        assertTrue(lowest <= 3, "a low tone should raise an island in the middle, raised $lowest")
        assertTrue(highest >= 24, "a high tone should raise an islet at the edge, raised $highest")
        assertTrue(low.drop(8).all { it < 0.1f }, "a low tone should leave the outer islands flat")
        assertTrue(high.take(16).all { it < 0.1f }, "a high tone should leave the inner islands flat")
    }

    @Test
    fun onsetsSendRingsThroughTheWater() {
        val contour = Contour().apply { forcePortable = true }
        val alive = IntArray(140)
        RenderHarness.forEachFrameOf(contour, 96, 54, 140, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Onset, it) }) { _, step -> alive[step] = contour.ringsAlive }
        assertTrue(alive[InjectedFrames.HIT_STEPS.first() - 1] == 0, "no ring before the first onset")
        assertTrue(alive[InjectedFrames.HIT_STEPS.first() + 1] >= 1, "an onset should send a ring out")
        assertTrue(alive[InjectedFrames.HIT_STEPS.last() + 1] >= 3, "each onset should send its own ring, had ${alive[InjectedFrames.HIT_STEPS.last() + 1]}")
    }

    @Test
    fun aSteadyLoudPassageKeepsRingsMovingThroughWater() {
        val contour = Contour().apply { forcePortable = true }
        var withRings = 0
        var counted = 0
        var lowestSea = Float.MAX_VALUE
        RenderHarness.forEachFrame(contour, 160, 90, 360, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (step < 60) return@forEachFrame
            counted++
            if (contour.ringsAlive > 0) withRings++
            lowestSea = minOf(lowestSea, contour.sea)
        }
        assertTrue(withRings > counted * 0.8f, "rings should be in the water most of the time, were in $withRings of $counted frames")
        assertTrue(lowestSea > 2f, "loud music lowers the sea but leaves water for the rings, went down to $lowestSea")
    }

    @Test
    fun aDropBringsLowTideForABar() {
        val contour = Contour()
        val sea = FloatArray(560)
        val tide = FloatArray(560)
        val drop = InjectedFrames.STRUCTURE_STEP
        // No pulse in these frames, so a bar is the free cycle of 3.12 s and a beat a quarter of it.
        val beat = 47
        val bar = 187
        val pictures = mapOf(drop - 1 to "before-low-tide.png", drop + beat + bar / 2 to "low-tide.png")
        var drawn = 0
        RenderHarness.forEachFrameOf(contour, 480, 270, 560, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Drop, it) },
            beforeDraw = { contour.forcePortable = drawn++ !in pictures }) { bitmap, step ->
            sea[step] = contour.sea
            tide[step] = contour.lowTide
            pictures[step]?.let { ImageIO.write(bitmap.toBufferedImage(), "png", File(directory, it)) }
        }
        assertTrue(sea[drop - 1] > 2f, "the sea should stand above the seabed before the drop, was ${sea[drop - 1]}")
        assertTrue(sea[drop + beat + 3] < 0f, "the sea should be out within a beat of the drop, was ${sea[drop + beat + 3]}")
        assertTrue(tide[drop + beat + bar - 5] > 0.99f, "low tide should hold for a bar")
        assertTrue(tide[drop + beat + 2 * bar + 5] == 0f && sea[drop + beat + 2 * bar + 5] > 2f,
            "the sea should be back over the next bar, was ${sea[drop + beat + 2 * bar + 5]}")
    }

    @Test
    fun underReducedMotionLowTideIsColourAndLightNotMovement() {
        val contour = Contour().apply { forcePortable = true }
        val sea = FloatArray(200)
        val tide = FloatArray(200)
        RenderHarness.forEachFrameOf(contour, 96, 54, 200, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Drop, it) }, beforeDraw = { it.motionScale = 0.15f }) { _, step ->
            sea[step] = contour.sea
            tide[step] = contour.lowTide
        }
        val drop = InjectedFrames.STRUCTURE_STEP
        assertTrue(tide[drop + 60] > 0.99f, "low tide should still come under reduced motion")
        assertTrue(abs(sea[drop + 60] - sea[drop - 1]) < 0.3f, "but the coastline should stay put, went from ${sea[drop - 1]} to ${sea[drop + 60]}")
    }

    @Test
    fun aBreakdownBringsHighTide() {
        val contour = Contour()
        var landBefore = 0f
        var drawn = 0
        RenderHarness.forEachFrameOf(contour, 480, 270, 320, VizPalette.Prism,
            source = { InjectedFrames.frame(VizDriver.Breakdown, it) },
            beforeDraw = { contour.forcePortable = drawn++ < 319 }) { bitmap, step ->
            if (step == InjectedFrames.STRUCTURE_STEP - 1) landBefore = contour.landShare()
            if (step == 319) ImageIO.write(bitmap.toBufferedImage(), "png", File(directory, "high-tide.png"))
        }
        val landAfter = contour.landShare()
        assertTrue(contour.sea >= contour.tallest - 1.6f, "high tide should leave only the tallest summits dry: sea ${contour.sea}, top ${contour.tallest}")
        assertTrue(landAfter < 0.05f && landAfter < landBefore, "high tide should cover most land: $landBefore before, $landAfter after")
    }

    @Test
    fun aSlowCrescendoSlidesItsLines() {
        val seconds = 10f
        val rate = 48_000
        val samples = FloatArray((seconds * rate).toInt()) { index ->
            val time = index.toFloat() / rate
            val swell = 0.01f + 0.3f * (time / seconds)
            swell * (sin(2f * PI.toFloat() * 110f * time) + sin(2f * PI.toFloat() * 440f * time + 1f) +
                sin(2f * PI.toFloat() * 1_760f * time + 2f) + sin(2f * PI.toFloat() * 5_000f * time + 3f)) / 4f
        }
        val player = SongPlayer(samples)
        val changes = ArrayList<Float>()
        var previous: IntArray? = null
        RenderHarness.forEachFrameOf(Contour(), 160, 90, 360, VizPalette.Prism, source = { player.next(1f / 60f) }) { bitmap, step ->
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            previous?.let { if (step > 30) changes += difference(it, pixels) }
            previous = pixels
        }
        val sorted = changes.sorted()
        val middle = sorted[sorted.size / 2]
        val worst = sorted.last()
        println("crescendo: median change $middle, worst $worst")
        assertTrue(worst <= maxOf(middle * 6f, 0.004f), "a line popped: the worst frame changed $worst against a median of $middle")
    }

    @Test
    fun theCanvasStandInDrawsTheSameMap() {
        val shader = RenderHarness.render(Contour(), 320, 180, 40, VizPalette.Prism)
        val canvas = RenderHarness.render(Contour().apply { forcePortable = true }, 320, 180, 40, VizPalette.Prism)
        ImageIO.write(canvas, "png", File(directory, "stand-in.png"))
        ImageIO.write(shader, "png", File(directory, "shader.png"))
        val shaderInk = inkFraction(shader)
        val canvasInk = inkFraction(canvas)
        assertTrue(canvasInk > 0.004f, "the stand-in should draw the map, had $canvasInk")
        assertTrue(canvasInk in shaderInk * 0.4f..shaderInk * 2.5f, "the stand-in should draw about as much as the shader: $canvasInk against $shaderInk")
    }

    @Test
    fun probeBandsWhenRequested() {
        val path = System.getenv("CONTOUR_PROBE")
        assumeTrue("Optional band probe", path != null)
        val buffer = ByteBuffer.wrap(File(checkNotNull(path)).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val player = SongPlayer(samples, bandCount = 48)
        val gestures = Gestures()
        var elapsed = 0f
        for (step in 0 until 60 * 60) {
            elapsed += 1f / 60f
            val frame = player.next(1f / 60f)
            gestures.update(VizRenderState(frame, elapsed, 1f / 60f, VizPalette.Prism))
            if (gestures.turn || gestures.surge) println("t=${"%.1f".format(elapsed + 3f)} turn=${gestures.turn} surge=${gestures.surge}")
        }
    }

    /** Each island's lift after four seconds of [samples], drawn on the canvas stand-in to keep it quick. */
    private fun liftsFor(samples: FloatArray): FloatArray {
        val contour = Contour().apply { forcePortable = true }
        val player = SongPlayer(samples, bandCount = 48)
        RenderHarness.forEachFrameOf(contour, 96, 54, 240, VizPalette.Prism, source = { player.next(1f / 60f) }) { _, _ -> }
        return FloatArray(32) { contour.lift(it) }
    }

    private fun tone(hertz: Float): FloatArray {
        val rate = 48_000
        return FloatArray(rate * 9) { index ->
            val time = index.toFloat() / rate
            0.3f * sin(2f * PI.toFloat() * hertz * time)
        }
    }

    /** The mean change from frame to frame over the second half of a three second render. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var sum = 0f
        var count = 0
        RenderHarness.forEachFrame(Contour(), 160, 90, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            if (step >= 90) previous?.let {
                sum += difference(it, pixels)
                count++
            }
            previous = pixels
        }
        return if (count == 0) 0f else sum / count
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f + 0.0722f * (pixel and 0xFF) / 255f

    /** How much of the image stopped being the corner colour, the measure the contact sheet uses. */
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
}
