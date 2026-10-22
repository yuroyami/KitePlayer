package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Odyssey: a ringed giant planet whose clouds and rings are the spectrum. */
class OdysseyTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsTheRingedPlanetWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(Odyssey(), 320, 180, 150, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 90) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.08f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("odyssey: ink $ink, blown $blown")
        assertTrue(ink > 0.2f, "the planet and its rings should fill much of the frame, had $ink")
        assertTrue(blown < 0.05f, "only the rim and the sun glow white, had $blown blown out")
    }

    @Test
    fun silenceHoldsThePlanetStillAndHalfLit() {
        var previous: IntArray? = null
        var worst = 0f
        var firstInk = 0f
        RenderHarness.forEachFrame(Odyssey(), 160, 90, 120, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.03f } / pixels.size.toFloat()
            if (step >= 30) previous?.let { old ->
                worst = maxOf(worst, old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size)
            }
            previous = pixels
        }
        println("odyssey: silence changed at most $worst a frame, first frame ink $firstInk")
        assertTrue(worst < 0.0002f, "the planet hangs still in a silence, changed $worst")
        assertTrue(firstInk > 0.1f, "the first frame already shows the lit planet, had $firstInk")
    }

    @Test
    fun everyViewCutsAboutAThirdOfThePlanetOffAtTheFrameEdge() {
        for (aspect in listOf(16f / 9f, 20f / 9f, 4f / 3f, 9f / 16f, 9f / 20f)) for ((index, view) in Odyssey.VIEWS.withIndex()) {
            if (index == Odyssey.BREAKDOWN_VIEW) continue
            val cx = view[0] * aspect
            val cy = view[1]
            val radius = view[2] * minOf(1f, aspect)
            var inside = 0
            var all = 0
            for (i in 0 until 200) for (j in 0 until 200) {
                val x = cx - radius + 2f * radius * (i + 0.5f) / 200f
                val y = cy - radius + 2f * radius * (j + 0.5f) / 200f
                if (hypot(x - cx, y - cy) > radius) continue
                all++
                if (abs(x) <= aspect && abs(y) <= 1f) inside++
            }
            val share = inside.toFloat() / all
            println("odyssey: view $index at aspect $aspect shows $share of the disc")
            assertTrue(share in 0.5f..0.8f, "view $index at aspect $aspect should cut about a third off, shows $share")
        }
    }

    @Test
    fun aSingleToneBrightensOneRingletAndOneStripe() {
        val odyssey = Odyssey()
        RenderHarness.forEachFrameOf(odyssey, 64, 36, 90, VizPalette.Prism,
            source = { step ->
                val frame = InjectedFrames.frame(VizDriver.Bands, step.coerceAtLeast(InjectedFrames.STEP_AT))
                for (band in frame.bands.indices) frame.bands[band] = if (band == 20) 0.85f else 0.02f
                frame
            }) { _, _ -> }
        val ringlets = odyssey.ringletLevels()
        val stripes = odyssey.newestStripes()
        val brightRinglets = ringlets.count { it > 0.5f }
        val brightStripes = stripes.count { it > 0.5f }
        println("odyssey: a single tone lit $brightRinglets ringlets and $brightStripes stripes")
        assertTrue(brightRinglets in 1..2, "one ringlet lights, with its neighbour at most, had $brightRinglets")
        assertEquals(1, brightStripes, "one stripe lights")
    }

    @Test
    fun aSectionMovesToANewViewAndADropBringsTheSunUp() {
        val sectioned = Odyssey()
        var before = -1
        var after = -1
        RenderHarness.forEachFrameOf(sectioned, 64, 36, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP - 1) before = sectioned.view
            if (step == 89) after = sectioned.view
        }
        assertTrue(after != before, "a section moves to another view: $before then $after")

        val dropped = Odyssey()
        var atDrop = -1f
        var risen = -1f
        RenderHarness.forEachFrameOf(dropped, 64, 36, 140, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            if (step == InjectedFrames.STRUCTURE_STEP) atDrop = dropped.risen
            if (step == 139) risen = dropped.risen
        }
        println("odyssey: view $before then $after; the sun at $atDrop on the drop and $risen later")
        assertTrue(atDrop < 0.2f && risen >= 1f, "the sun rises over one beat after a drop: $atDrop then $risen")
    }

    /** The largest mean change of one pixel from one frame to the next while a section moves the view. */
    private fun fastestMove(motionScale: Float): Float {
        var earlier: FloatArray? = null
        var fastest = 0f
        RenderHarness.forEachFrameOf(Odyssey(), 96, 54, 150, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) },
            beforeDraw = { it.motionScale = motionScale }) { bitmap, step ->
            val pixels = IntArray(96 * 54).also { bitmap.readPixels(it) }
            val lumas = FloatArray(pixels.size) { luma(pixels[it]) }
            if (step > InjectedFrames.STRUCTURE_STEP) earlier?.let { before ->
                fastest = maxOf(fastest, lumas.indices.sumOf { abs(lumas[it] - before[it]).toDouble() }.toFloat() / lumas.size)
            }
            earlier = lumas
        }
        return fastest
    }

    @Test
    fun reducedMotionSlowsTheMoveToANewView() {
        val full = fastestMove(1f)
        val reduced = fastestMove(0.15f)
        println("odyssey: the view moves at $full a frame, and at $reduced with reduced motion")
        assertTrue(full > 0.002f, "the section must move the view, moved $full")
        assertTrue(reduced <= full * 0.7f, "reduced motion should slow the move: $reduced against $full")
        assertTrue(reduced > 0.0005f, "reduced motion slows the move and does not freeze it: $reduced")
    }

    @Test
    fun aKickBreedsAStormInTheCloudsThatOutlivesTheRipple() {
        val kicked = Odyssey()
        val still = Odyssey()
        val kickShares = FloatArray(2)
        val stillShares = FloatArray(2)
        val kickPixels = IntArray(96 * 54)
        val stillPixels = IntArray(96 * 54)
        RenderHarness.forEachFrameOf(kicked, 96, 54, 260, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.LowHit, step) }) { bitmap, step ->
            if (step == 60) kickShares[0] = kicked.cloudInkShare()
            if (step == 259) {
                kickShares[1] = kicked.cloudInkShare()
                bitmap.readPixels(kickPixels)
            }
        }
        RenderHarness.forEachFrameOf(still, 96, 54, 260, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { bitmap, step ->
            if (step == 60) stillShares[0] = still.cloudInkShare()
            if (step == 259) {
                stillShares[1] = still.cloudInkShare()
                bitmap.readPixels(stillPixels)
            }
        }
        val difference = meanChannelDifference(kickPixels, stillPixels)
        println("odyssey: cloud ink ${kickShares[1]} after the kicks, ${stillShares[1]} without; frames differ by $difference")
        assertEquals(0f, kickShares[0])
        assertEquals(0f, stillShares[0])
        assertTrue(kickShares[1] > 0.003f, "the kicks should leave storms in the clouds, ink ${kickShares[1]}")
        assertEquals(0f, stillShares[1])
        assertTrue(difference > 0.003f, "the storms should show after the ripple is gone, differed by $difference")
    }

    @Test
    fun theNewestCloudColumnFacesTheCamera() {
        val changed = Odyssey()
        val steady = Odyssey()
        val a230 = IntArray(160 * 90)
        val b230 = IntArray(160 * 90)
        val a399 = IntArray(160 * 90)
        val b399 = IntArray(160 * 90)
        var started = false
        RenderHarness.forEachFrameOf(changed, 160, 90, 400, VizPalette.Prism,
            source = { step -> if (step < 200) InjectedFrames.toneFrame(step, lowBand = true) else inverted(InjectedFrames.toneFrame(step, lowBand = true)) },
            beforeDraw = { if (!started) { changed.jumpTo(1); started = true } }) { bitmap, step ->
            if (step == 230) bitmap.readPixels(a230)
            if (step == 399) bitmap.readPixels(a399)
        }
        var steadyStarted = false
        var facing = Pair(0f, 0f)
        RenderHarness.forEachFrameOf(steady, 160, 90, 400, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = true) },
            beforeDraw = { if (!steadyStarted) { steady.jumpTo(1); steadyStarted = true } }) { bitmap, step ->
            // The orbit moves the disc, so the facing point is read on the frame it is compared on.
            if (step == 230) { bitmap.readPixels(b230); facing = steady.facingPixel(160f, 90f) }
            if (step == 399) bitmap.readPixels(b399)
        }
        val (fx, facingY) = facing
        // The newest half second, with the churn of the stripe edges, spans about 0.45 radii each side,
        // so the side patches sit at 0.6 radii. The patches sit 0.15 radii above the facing row: clear
        // of the ring's bright line below and of the great storm above right, which neither run changes.
        val side = 0.6f * steady.planetRadiusPixels(90f)
        val fy = facingY - 0.15f * steady.planetRadiusPixels(90f)
        val centre = patchDifference(a230, b230, fx, fy)
        val left = patchDifference(a230, b230, fx - side, fy)
        val right = patchDifference(a230, b230, fx + side, fy)
        val leftLater = patchDifference(a399, b399, fx - side, fy)
        val rightLater = patchDifference(a399, b399, fx + side, fy)
        println("odyssey: facing at ($fx, $fy), sides at ${fx - side} and ${fx + side}; half a second after the change " +
            "facing $centre, left $left, right $right; later left $leftLater, right $rightLater")
        assertTrue(centre > 0.01f && centre > 2f * left && centre > 2f * right,
            "the newest column should face the camera: facing $centre, left $left, right $right")
        // The left patch lies on the night side, lit only by the rings, so its change is dimmer.
        assertTrue(leftLater > 0.003f && rightLater > 0.01f, "the change should stream out to both sides: $leftLater, $rightLater")
    }

    @Test
    fun aHatLightsAnAuroraAtThePoles() {
        val planet = Odyssey()
        var before = 0f
        var after = 0f
        RenderHarness.forEachFrameOf(planet, 64, 36, 80, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.HighHit, step) }) { _, step ->
            if (step == 60) before = planet.auroraValue
            if (step == 67) after = planet.auroraValue
        }
        println("odyssey: aurora $before before the hat, $after after")
        assertTrue(before < 0.1f, "no aurora before the hat, had $before")
        assertTrue(after > 0.5f, "a hat should light the aurora, had $after")
    }

    @Test
    fun theStormsDriftWithTheirStripe() {
        val planet = Odyssey()
        val counts = IntArray(2)
        val xs = FloatArray(2)
        val centroids = FloatArray(2)
        RenderHarness.forEachFrameOf(planet, 64, 36, 130, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step < 90) VizDriver.LowHit else null, step) }) { _, step ->
            val at = when (step) { 70 -> 0; 129 -> 1; else -> -1 }
            if (at >= 0) {
                counts[at] = planet.stormCount
                xs[at] = planet.stormX(0)
                centroids[at] = planet.cloudInkCentroidX()
            }
        }
        val moved = xs[1] - xs[0]
        val inkMoved = centroids[1] - centroids[0]
        println("odyssey: storm x ${xs[0]} to ${xs[1]}, ink centroid ${centroids[0]} to ${centroids[1]}")
        assertEquals(1, counts[0])
        assertEquals(1, counts[1])
        assertTrue(abs(moved) > 0.1f, "the storm should drift with its stripe, moved $moved")
        assertTrue(inkMoved * moved > 0f && abs(inkMoved) > 0.5f * abs(moved),
            "the storm's ink should travel with it: storm $moved, ink $inkMoved")
    }

    @Test
    fun theFieldIsEmptyInSilenceAndNeverMovesThere() {
        val planet = Odyssey()
        var most = 0f
        RenderHarness.forEachFrame(planet, 64, 36, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ ->
            most = maxOf(most, planet.cloudInkShare())
        }
        assertEquals(0f, most)
    }

    @Test
    fun theCameraOrbitsWithoutACutOnTheDrumLoop() {
        val planet = Odyssey()
        val last = FloatArray(3)
        var biggest = 0f
        var travelled = 0f
        var lastPhase = 0f
        val signs = mutableSetOf<Float>()
        RenderHarness.forEachFrame(planet, 64, 36, 3600, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            if (step > 0) for (i in 0..2) biggest = maxOf(biggest, abs(planet.shownValue(i) - last[i]))
            for (i in 0..2) last[i] = planet.shownValue(i)
            travelled += abs(planet.orbitPhaseValue - lastPhase)
            lastPhase = planet.orbitPhaseValue
            signs += planet.orbitSignValue
        }
        println("odyssey: the view moved at most $biggest a frame, the orbit travelled $travelled turns, signs $signs")
        assertTrue(biggest < 0.004f, "the camera never cuts, moved $biggest in one frame")
        assertTrue(travelled > 0.3f, "the camera orbits with the music, travelled $travelled turns")
        assertEquals(setOf(1f, -1f), signs, "a morph turns the orbit the other way")

        val still = Odyssey()
        var phaseAt60 = 0f
        var xAt60 = 0f
        RenderHarness.forEachFrame(still, 64, 36, 300, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 60) { phaseAt60 = still.orbitPhaseValue; xAt60 = still.shownValue(0) }
        }
        assertEquals(phaseAt60, still.orbitPhaseValue, "a silence holds the orbit")
        assertEquals(xAt60, still.shownValue(0), "a silence holds the view")
    }

    @Test
    fun theViewsCycleInOrderAndABreakdownIsTheShadow() {
        val sectioned = Odyssey()
        RenderHarness.forEachFrameOf(sectioned, 64, 36, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Section, step) }) { _, _ -> }
        assertEquals(1, sectioned.view, "a section moves from the day side to the ring plane")
        assertEquals("Ring plane", sectioned.forms.form)

        val broken = Odyssey()
        var at70 = -1
        RenderHarness.forEachFrameOf(broken, 64, 36, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Breakdown, step) }) { _, step ->
            if (step == 70) at70 = broken.view
        }
        assertEquals(Odyssey.BREAKDOWN_VIEW, at70, "a breakdown drifts into the shadow")
        assertEquals("Shadow", broken.forms.form)
    }

    @Test
    fun theFormNamesMatchTheLitShareOfEachView() {
        val shares = mutableMapOf<String, Float>()
        for (index in 0..2) {
            val planet = Odyssey()
            var share = 0f
            // The harness resets the drawing before its first frame, so the view is set inside the run.
            RenderHarness.forEachFrameOf(planet, 160, 90, 30, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(null, step) },
                beforeDraw = { if (planet.view != index) planet.jumpTo(index) }) { bitmap, step ->
                if (step != 29) return@forEachFrameOf
                val pixels = IntArray(160 * 90).also { bitmap.readPixels(it) }
                val (cx, cy) = planet.facingPixel(160f, 90f)
                val radius = planet.planetRadiusPixels(90f)
                var inside = 0
                var lit = 0
                for (y in 0 until 90) for (x in 0 until 160) {
                    if (hypot(x + 0.5f - cx, y + 0.5f - cy) > radius) continue
                    inside++
                    if (luma(pixels[y * 160 + x]) > 0.08f) lit++
                }
                share = lit.toFloat() / inside.coerceAtLeast(1)
            }
            shares[Odyssey.FORM_NAMES[index]] = share
        }
        println("odyssey: lit share of the disc by view $shares")
        assertTrue(shares.getValue("Day side") > shares.getValue("Ring plane") + 0.1f, "the day side is the most lit: $shares")
        assertTrue(shares.getValue("Ring plane") > shares.getValue("Night side") + 0.1f, "the night side is the least lit: $shares")
    }

    @Test
    fun aBirthSendsACometAcrossTheSky() {
        val born = Odyssey()
        val still = Odyssey()
        var at71 = 0f
        var at360 = 1f
        var bornLuma = 0f
        var stillLuma = 0f
        RenderHarness.forEachFrameOf(born, 160, 90, 361, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { bitmap, step ->
            if (step == 70) born.birth()
            if (step == 71) at71 = born.cometValue
            if (step == 360) at360 = born.cometValue
            if (step == 100) bornLuma = meanLuma(bitmap)
        }
        RenderHarness.forEachFrameOf(still, 160, 90, 101, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { bitmap, step ->
            if (step == 100) stillLuma = meanLuma(bitmap)
        }
        println("odyssey: the comet at $at71 a frame after its birth and $at360 later; luma $bornLuma against $stillLuma")
        assertTrue(at71 > 0.9f, "a birth starts a comet, had $at71")
        assertEquals(0f, at360, "the comet has crossed the sky by a cycle and a half")
        assertTrue(bornLuma > stillLuma, "the comet adds light: $bornLuma against $stillLuma")
    }

    @Test
    fun theLightFollowsTheLevelAndKeepsAThirdInSilence() {
        val loud = Odyssey()
        var heard = 0f
        var energy = 0f
        RenderHarness.forEachFrameOf(loud, 64, 36, 91, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step).also { if (step == 90) energy = it.energy } },
            beforeDraw = { it.lightScale = 1f }) { _, step ->
            if (step == 90) heard = loud.lightValue
        }
        val quiet = Odyssey()
        RenderHarness.forEachFrame(quiet, 64, 36, 120, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ -> }
        println("odyssey: light $heard at energy $energy, ${quiet.lightValue} in a silence")
        assertEquals(lightFor(energy), heard, 0.02f, "the light follows the level")
        assertEquals(Odyssey.IDLE_LIGHT, quiet.lightValue, 0.02f, "a silence keeps the idle light")
    }

    private fun meanLuma(bitmap: ImageBitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        return pixels.sumOf { luma(it).toDouble() }.toFloat() / pixels.size
    }

    private fun inverted(base: SpectrumFrame): SpectrumFrame {
        val bands = FloatArray(base.bands.size) { if (base.bands[it] < 0.5f) 0.9f else 0f }
        return SpectrumFrame(
            ptsMicros = base.ptsMicros, bands = bands, peaks = bands, scope = base.scope,
            level = base.level, bass = base.bass, mid = base.mid, treble = base.treble, beat = 0f, pulse = 0f,
            bandsRel = bands, levelRel = base.levelRel, bassRel = base.bassRel, midRel = base.midRel,
            trebleRel = base.trebleRel, energy = base.energy, density = base.density, mood = base.mood,
            loudShort = base.loudShort, loudLong = base.loudLong, trend = base.trend,
            generation = base.generation, hasTimestamp = true, events = base.events,
        )
    }

    /** The mean luma difference over the 5 by 5 patch at [x], [y] of two 160 by 90 frames. */
    private fun patchDifference(a: IntArray, b: IntArray, x: Float, y: Float): Float {
        var total = 0f
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val px = (x.toInt() + dx).coerceIn(0, 159)
            val py = (y.toInt() + dy).coerceIn(0, 89)
            total += abs(luma(a[py * 160 + px]) - luma(b[py * 160 + px]))
            count++
        }
        return total / count
    }

    private fun meanChannelDifference(a: IntArray, b: IntArray): Float {
        var total = 0L
        for (i in a.indices) {
            total += abs((a[i] shr 16 and 0xFF) - (b[i] shr 16 and 0xFF)) +
                abs((a[i] shr 8 and 0xFF) - (b[i] shr 8 and 0xFF)) + abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return total / (a.size * 3f * 255f)
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}
