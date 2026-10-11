package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloom
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloomOutline
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.TwinBloomShape
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Twin Bloom on its own: the one outline its elements share, the picture it draws, how still it keeps
 * in silence, the cascade, the throw and the garden.
 */
class TwinBloomTest {

    init { useSkiaGraphics() }

    @Test
    fun oneOutlineGivesTheSpokeThePetalAndTheDisc() {
        val spoke = profile(TwinBloomShape.Spoke)
        assertTrue(spoke.max() <= 0.1f, "a spoke is thin everywhere, but is ${spoke.max()} of its length wide")

        val petal = profile(TwinBloomShape.Petal)
        val widestAt = petal.indices.maxBy { petal[it] } / SAMPLES.toFloat()
        assertTrue(widestAt > 0.55f, "a petal is widest past the middle, not at $widestAt")
        // A pointed tip narrows at least in step with the distance left to it. A round end narrows as
        // the square root of that distance, so its width falls far less over the last step.
        val petalClosing = petal[SAMPLES - 1] / petal[SAMPLES - 2]
        assertTrue(petalClosing < 0.6f, "a petal is pointed at the tip, but its last step keeps $petalClosing of the width")
        val disc = profile(TwinBloomShape.Disc)
        val discClosing = disc[SAMPLES - 1] / disc[SAMPLES - 2]
        assertTrue(discClosing > 0.65f, "a disc has round ends, but its last step keeps $discClosing of the width")

        val xs = FloatArray(TwinBloomOutline.POINTS)
        val ys = FloatArray(TwinBloomOutline.POINTS)
        TwinBloomOutline.trace(1f, TwinBloomShape.Disc.width, TwinBloomShape.Disc.widest, xs, ys)
        for (index in xs.indices) {
            val distance = hypot(xs[index] - 0.5f, ys[index])
            assertTrue(abs(distance - 0.5f) < 0.002f, "a disc is round, but point $index lies $distance from its middle")
        }
    }

    @Test
    fun drawsTheGardenOnTheLivelySong() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(TwinBloom(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30 || step % 5 != 0) return@forEachFrame
            val image = bitmap.toBufferedImage()
            peakInk = max(peakInk, inkFraction(image))
            peakBlown = max(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the garden puts down ink on only ${peakInk * 100}% of the picture")
        assertTrue(peakBlown <= 0.3f, "${peakBlown * 100}% of the picture saturated to white")
    }

    @Test
    fun theFirstFrameShowsOneBudAndTheLivelySongGrowsAGarden() {
        for ((width, height) in listOf(320 to 200, 200 to 320)) {
            val image = RenderHarness.render(TwinBloom(), width, height, 1, VizPalette.Prism, RenderHarness.Song.Silence)
            val (across, down) = litSpan(image)
            val shorter = minOf(width, height)
            val span = maxOf(across, down) / shorter.toFloat()
            println("twin bloom: the first bud spans $across by $down of ${width}x$height, $span of the shorter side")
            assertTrue(span in 0.05f..0.25f, "the first frame shows one bud, spanning $span of the shorter side")
        }
        val garden = TwinBloom()
        RenderHarness.forEachFrame(garden, 96, 54, 3600, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        println("twin bloom: ${garden.bloomCount} blooms after a minute of drums")
        assertTrue(garden.bloomCount >= 2, "the drums grow a garden, ${garden.bloomCount} blooms")
        assertTrue(garden.forms.births >= 1, "the garden counts its births")
    }

    @Test
    fun petalLengthRunsTheFullRange() {
        assertEquals(0.2f, TwinBloom.petalLengthFor(0f), 0.001f)
        assertEquals(1f, TwinBloom.petalLengthFor(1f), 0.001f)
    }

    @Test
    fun aBirthPlantsABloomWhereTheMixPutsIt() {
        fun plant(driver: VizDriver?): TwinBloom {
            val drawing = TwinBloom()
            RenderHarness.forEachFrameOf(drawing, 96, 54, 102, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(driver, step) }) { _, step ->
                if (step == 100) drawing.birth()
            }
            return drawing
        }
        val leaning = plant(VizDriver.Waveform)
        val bright = plant(VizDriver.Timbre)
        val plain = plant(null)
        println("twin bloom: a birth under a left-heavy mix stands at ${leaning.bloomX(1)} against the bud at ${leaning.bloomX(0)}; " +
            "depth ${bright.bloomDepth(1)} under a bright sound, ${plain.bloomDepth(1)} under the baseline")
        assertEquals(2, leaning.bloomCount)
        assertTrue(leaning.bloomX(0) - leaning.bloomX(1) > 0.15f, "a left-heavy mix plants to the left")
        assertTrue(bright.bloomDepth(1) > plain.bloomDepth(1), "a bright sound plants further back")
    }

    @Test
    fun theSixthBirthWiltsTheOldest() {
        val drawing = TwinBloom()
        var afterFourth = 0
        var fifthAge = -1f
        var sixthAge = -1f
        var sixthOldest = -1f
        RenderHarness.forEachFrameOf(drawing, 96, 54, 72, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { _, step ->
            if (step in listOf(10, 20, 30, 40, 50, 60)) drawing.birth()
            if (step == 41) afterFourth = drawing.bloomCount
            if (step == 51) fifthAge = drawing.bloomAge(0)
            if (step == 61) { sixthAge = drawing.bloomAge(1); sixthOldest = drawing.bloomAge(0) }
        }
        println("twin bloom: $afterFourth blooms after four births; the fifth reborn at age $fifthAge, the sixth at $sixthAge with slot 0 at $sixthOldest")
        assertEquals(5, afterFourth)
        assertTrue(fifthAge < 0.05f, "the fifth birth replaces the first bud")
        assertTrue(sixthAge < 0.05f && sixthOldest > 0.1f, "the sixth replaces the next oldest")
        assertEquals(5, drawing.bloomCount)
    }

    @Test
    fun theStemGrowsWithTheEnergyHeard() {
        fun grown(song: RenderHarness.Song): TwinBloom {
            val drawing = TwinBloom()
            RenderHarness.forEachFrame(drawing, 96, 54, 300, VizPalette.Prism, song) { _, _ -> }
            return drawing
        }
        val lively = grown(RenderHarness.Song.Lively).stemHeight(0)
        val calm = grown(RenderHarness.Song.Calm).stemHeight(0)
        val quiet = TwinBloom()
        var first = -1f
        var last = -1f
        RenderHarness.forEachFrame(quiet, 96, 54, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 0) first = quiet.stemHeight(0)
            last = quiet.stemHeight(0)
        }
        println("twin bloom: the stem grew to $lively on the drums and $calm on the pad; $first to $last in silence")
        assertTrue(lively > calm, "more energy grows a taller stem")
        assertEquals(first, last, "a silence grows nothing")
    }

    @Test
    fun theOrbitIsGone() {
        val drawing = TwinBloom()
        var first = Float.NaN
        var widest = 0f
        RenderHarness.forEachFrame(drawing, 96, 54, 300, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (step == 0) first = drawing.bloomX(0)
            widest = max(widest, abs(drawing.bloomX(0) - first))
        }
        println("twin bloom: the first bud moved at most $widest of the screen across")
        assertTrue(widest <= 0.02f, "the bud only sways, $widest")
        assertTrue(drawing.genes.all.none { "Backwards" in it.name || "backwards" in it.name }, "no backwards gene")
    }

    @Test
    fun silenceMovesTheGardenFarLessThanMusic() {
        val music = meanChange(RenderHarness.Song.Lively)
        val quiet = meanChange(RenderHarness.Song.Silence)
        println("twin bloom: frames change by $music on the drums and $quiet in silence")
        // The garden opens as one bud, which covers less of the frame than a full flower.
        assertTrue(music > 0.003f, "the music render barely moves: $music")
        assertTrue(quiet <= 0.2f * music, "silence moves ${quiet / music} as much as the music does")
    }

    @Test
    fun aTurnStartsACascadeThatShowsTwoShapesAtOnce() {
        // The drum loop has no accepted boundary, so its first turn is the eighth bar's fallback.
        val drawing = TwinBloom()
        var changes = 0
        var turnAt = -1
        var mixedAt = -1
        var settledAt = -1
        RenderHarness.forEachFrame(drawing, 96, 60, 17 * 60, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            val now = drawing.changes
            if (step > 5 * 60 && now > changes && turnAt < 0) turnAt = step
            changes = now
            if (turnAt < 0) return@forEachFrame
            val shapes = (0 until 3).map { drawing.shapeOf(0, it) }.toSet()
            if (mixedAt < 0 && shapes.size > 1) mixedAt = step
            if (mixedAt >= 0 && settledAt < 0 && shapes.size == 1) settledAt = step
        }
        assertTrue(turnAt > 0, "no cascade started after the first five seconds")
        assertTrue(mixedAt >= turnAt, "the cascade never showed two shapes at once")
        assertTrue(settledAt > mixedAt, "the cascade never settled on one shape")
    }

    @Test
    fun aDropThrowsTheDiscsToTheFrameEdgesWithinABar() {
        val drawing = TwinBloom()
        val width = 320
        val height = 200
        val drop = InjectedFrames.STRUCTURE_STEP
        var bar = 0f
        var edgeBefore = 0f
        var edgeAfter = 0f
        var reachedAt = -1
        RenderHarness.forEachFrameOf(
            drawing, width, height, drop + 300, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) },
        ) { bitmap, step ->
            if (step == drop) bar = drawing.barSeconds
            val image = bitmap.toBufferedImage()
            val edge = edgeInk(image)
            if (step in drop - 20 until drop) edgeBefore = max(edgeBefore, edge)
            if (step >= drop) {
                if (step - drop <= bar * 60f) edgeAfter = max(edgeAfter, edge)
                if (reachedAt < 0 && edge > 0.03f) reachedAt = step
            }
        }
        assertTrue(bar > 0f, "the drawing read no bar length at the drop")
        assertTrue(edgeBefore < 0.01f, "the frame edges were lit before the drop: $edgeBefore")
        assertTrue(edgeAfter > 0.03f, "the thrown discs never reached the frame edges within a bar: $edgeAfter")
        assertTrue(reachedAt - drop <= bar * 60f, "the discs reached the edges ${reachedAt - drop} frames after the drop")
    }

    @Test
    fun theCarpetRemembersFallenPetals() {
        fun run(driver: VizDriver?): Triple<Float, Float, Float> {
            val drawing = TwinBloom()
            var at60 = -1f
            var at160 = -1f
            var at299 = -1f
            RenderHarness.forEachFrameOf(drawing, 96, 54, 300, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(driver, step) }) { _, step ->
                if (step == 60) at60 = drawing.carpetInkShare()
                if (step == 160) at160 = drawing.carpetInkShare()
                if (step == 299) at299 = drawing.carpetInkShare()
            }
            return Triple(at60, at160, at299)
        }
        val (before, landed, later) = run(VizDriver.BodyHit)
        val (_, _, still) = run(null)
        println("twin bloom: the carpet holds $before before the snares, $landed after them and $later two seconds on; $still with no snare")
        assertEquals(0f, before)
        assertTrue(landed > 0.002f, "broken petals ink the carpet, $landed")
        assertTrue(later > 0.001f, "the carpet remembers for seconds, $later")
        assertEquals(0f, still)
    }

    @Test
    fun theCarpetDriftsAndNeverMovesInAPause() {
        val drawing = TwinBloom()
        var at200 = 0f
        var at299 = 0f
        var last: SpectrumFrame? = null
        var heldFirst = 0f
        var heldLast = 0f
        RenderHarness.forEachFrameOf(drawing, 96, 54, 420, VizPalette.Prism,
            source = { step ->
                if (step < 300) InjectedFrames.frame(VizDriver.BodyHit, step).also { last = it }
                else checkNotNull(last).withPulseHeld().withEvents(held = true)
            }) { _, step ->
            if (step == 200) at200 = drawing.carpetCentroidX()
            if (step == 299) at299 = drawing.carpetCentroidX()
            if (step == 300) heldFirst = drawing.carpetCentroidX()
            if (step == 419) heldLast = drawing.carpetCentroidX()
        }
        println("twin bloom: the carpet's centre moved from $at200 to $at299, and from $heldFirst to $heldLast in a pause")
        assertTrue(abs(at299 - at200) > 0.01f, "the carpet drifts")
        assertEquals(heldFirst, heldLast, "a pause holds the carpet")
    }

    @Test
    fun theFormFollowsTheGarden() {
        fun formAfter(frames: Int, source: (Int) -> SpectrumFrame): String {
            val drawing = TwinBloom()
            RenderHarness.forEachFrameOf(drawing, 96, 54, frames, VizPalette.Prism, source) { _, _ -> }
            return drawing.forms.form
        }
        fun songForm(song: RenderHarness.Song, frames: Int): String {
            val drawing = TwinBloom()
            RenderHarness.forEachFrame(drawing, 96, 54, frames, VizPalette.Prism, song) { _, _ -> }
            return drawing.forms.form
        }
        val quiet = songForm(RenderHarness.Song.Silence, 120)
        val lively = songForm(RenderHarness.Song.Lively, 600)
        val dropped = formAfter(71) { step -> InjectedFrames.frame(VizDriver.Drop, step) }
        val broken = formAfter(127) { step -> InjectedFrames.frame(VizDriver.Breakdown, step) }
        println("twin bloom: $quiet in silence, $lively on the drums, $dropped after a drop, $broken after a breakdown")
        assertEquals("Bud", quiet)
        assertTrue(lively == "Bloom" || lively == "Field", "the drums show a bloom or a field, $lively")
        assertEquals("Storm", dropped)
        assertEquals("Bud", broken)
    }

    @Test
    fun theGroundTheBloomsAndTheForegroundPetalMoveAtThreeSpeeds() {
        val drawing = TwinBloom()
        val reach = FloatArray(3)
        RenderHarness.forEachFrame(drawing, 96, 54, 600, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            for (layer in 0..2) reach[layer] = max(reach[layer], abs(drawing.parallaxOf(layer)))
        }
        val quiet = TwinBloom()
        RenderHarness.forEachFrame(quiet, 96, 54, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ -> }
        println("twin bloom: the sway reaches ${reach.toList()} for the ground, the blooms and the foreground")
        assertTrue(reach[1] > 0f, "the blooms sway")
        assertEquals(0.5f, reach[0] / reach[1], 0.05f)
        assertEquals(2f, reach[2] / reach[1], 0.2f)
        for (layer in 0..2) assertEquals(0f, quiet.parallaxOf(layer), 1e-6f)
    }

    @Test
    fun noHitDriverDeclaresTheCamera() {
        val hits = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)
        val drives = checkNotNull(TwinBloom().mapping).drives
        assertTrue(drives.none { it.driver in hits && it.property == VizProperty.Camera }, "a hit moves the camera")
    }

    /** The full width along the length, for a length of one. */
    private fun profile(shape: TwinBloomShape): FloatArray =
        FloatArray(SAMPLES + 1) { 2f * TwinBloomOutline.halfWidth(it / SAMPLES.toFloat(), shape.width, shape.widest) }

    /** The mean change between frames over the settled part of a run: two seconds in, four seconds long. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var total = 0f
        var count = 0
        RenderHarness.forEachFrame(TwinBloom(), 128, 80, 360, VizPalette.Prism, song) { bitmap, step ->
            if (step < 120) return@forEachFrame
            val pixels = IntArray(128 * 80)
            bitmap.readPixels(pixels)
            previous?.let {
                total += difference(it, pixels)
                count++
            }
            previous = pixels
        }
        return if (count == 0) 0f else total / count
    }

    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun luma(pixel: Int): Float =
        0.2126f * (pixel shr 16 and 0xFF) / 255f + 0.7152f * (pixel shr 8 and 0xFF) / 255f + 0.0722f * (pixel and 0xFF) / 255f

    /** How much of the picture stopped being the corner colour, the way the contact sheet measures it. */
    private fun inkFraction(image: BufferedImage): Float {
        val background = image.getRGB(0, 0)
        var different = 0
        for (y in 0 until image.height step 2) {
            for (x in 0 until image.width step 2) if (distance(image.getRGB(x, y), background) > 18) different++
        }
        return different.toFloat() / ((image.width / 2) * (image.height / 2))
    }

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

    /** The width and height of the box round every lit pixel. */
    private fun litSpan(image: BufferedImage): Pair<Int, Int> {
        val background = GROUND
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (distance(image.getRGB(x, y), background) <= 18) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return (right - left + 1).coerceAtLeast(0) to (bottom - top + 1).coerceAtLeast(0)
    }

    /** How much of the outer band of the picture, a thirtieth of each side deep, is lit. */
    private fun edgeInk(image: BufferedImage): Float {
        val background = GROUND
        val bandX = image.width / 30
        val bandY = image.height / 30
        var lit = 0
        var seen = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val inBand = x < bandX || x >= image.width - bandX || y < bandY || y >= image.height - bandY
                if (!inBand) continue
                seen++
                if (distance(image.getRGB(x, y), background) > 40) lit++
            }
        }
        return lit.toFloat() / seen
    }

    private fun distance(left: Int, right: Int): Int =
        abs((left shr 16 and 0xFF) - (right shr 16 and 0xFF)) + abs((left shr 8 and 0xFF) - (right shr 8 and 0xFF)) +
            abs((left and 0xFF) - (right and 0xFF))

    private companion object {
        const val SAMPLES = 200
        val GROUND: Int = VizPalette.Prism.background.toArgb()
    }
}
