package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.ThinIce
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/** Thin Ice: ripples of two wells under black ice, white where they cross, and the ice breaking on a drop. */
class ThinIceTest {

    init { useSkiaGraphics() }

    @Test
    fun theLivelySongDrawsTwoSetsOfRipplesWithoutBlowingOut() {
        var ink = 0f
        var blown = 0f
        RenderHarness.forEachFrame(ThinIce(), 320, 180, 120, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 60) return@forEachFrame
            val pixels = IntArray(320 * 180)
            bitmap.readPixels(pixels)
            ink = maxOf(ink, pixels.count { luma(it) > 0.1f } / pixels.size.toFloat())
            blown = maxOf(blown, pixels.count { luma(it) > 0.97f } / pixels.size.toFloat())
        }
        println("thin ice: ink $ink, blown $blown")
        assertTrue(ink > 0.004f, "the rings should put ink down, had $ink")
        assertTrue(blown < 0.05f, "only the crossings are white, had $blown blown out")
    }

    @Test
    fun silenceHoldsTheRingsStillAndLit() {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        var firstInk = 0f
        val ice = ThinIce()
        val before = ice.radiiOf(0)
        RenderHarness.forEachFrame(ice, 160, 90, 180, VizPalette.Prism, RenderHarness.Song.Silence) { bitmap, step ->
            val pixels = IntArray(160 * 90)
            bitmap.readPixels(pixels)
            if (step == 0) firstInk = pixels.count { luma(it) > 0.05f } / pixels.size.toFloat()
            if (step >= 60) {
                previous?.let { old ->
                    change += old.indices.sumOf { abs(luma(old[it]) - luma(pixels[it])).toDouble() }.toFloat() / pixels.size
                    counted++
                }
            }
            previous = pixels
        }
        val mean = change / counted.coerceAtLeast(1)
        println("thin ice: silence moved $mean a frame, first frame ink $firstInk")
        assertTrue(mean < 0.0005f, "the rings hold still in silence, changed $mean")
        assertTrue(firstInk > 0.01f, "the first silent frame already shows the rings, had $firstInk")
        val after = ice.radiiOf(0)
        assertTrue(after.size == before.size && after.indices.all { abs(after[it] - before[it]) < 1e-5f },
            "no ring spreads in a silence: $before became $after")
    }

    @Test
    fun theFirstRingsSitOnTheWellsOfAPortraitScreen() {
        // A drawing starts out with the landscape shape and only learns the screen when it draws.
        val ice = ThinIce()
        RenderHarness.forEachFrame(ice, 36, 64, 3, VizPalette.Prism, RenderHarness.Song.Silence) { _, _ -> }
        for (well in 0 until 2) {
            val (x, y) = ice.wellAt(well)
            val centres = ice.ringCentresOf(well)
            assertTrue(centres.isNotEmpty(), "well $well has no rings")
            for ((cx, cy) in centres) {
                assertTrue(abs(cx - x) < 1e-5f && abs(cy - y) < 1e-5f, "a ring of well $well is centred at $cx, $cy and the well is at $x, $y")
            }
        }
    }

    @Test
    fun aTurnOfTheScreenTurnsTheCracksAndTheRingsTogether() {
        val ice = ThinIce()
        var step = 0
        val dropAt = 150
        val scope = CanvasDrawScope()
        fun draw(width: Int, height: Int) {
            val state = VizRenderState(InjectedFrames.frame(null, step), step / 60f, 1f / 60f, VizPalette.Prism, future = dropAhead(dropAt) { step })
            val bitmap = ImageBitmap(width, height)
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { drawComposedFrame(ice, state, null) }
            step++
        }
        ice.restart()
        repeat(dropAt - 1) { draw(160, 90) }
        val before = ice.crackStarts()
        val ringsBefore = ice.ringCentresOf(0)
        assertTrue(before.isNotEmpty() && ringsBefore.isNotEmpty(), "the fixture needs cracks and rings to turn: ${before.size}, ${ringsBefore.size}")
        // The next frame is drawn on a portrait screen, which turns the picture a quarter: the left
        // of the landscape screen becomes the bottom. Cracks that creep out only move their far end.
        draw(90, 160)
        val after = ice.crackStarts()
        val long = 160f / 90f
        assertTrue(after.size >= before.size)
        for ((index, start) in before.withIndex()) {
            val (x, y) = after[index]
            assertTrue(abs(x - start.second) < 1e-4f && abs(y - (long - start.first)) < 1e-4f,
                "crack $index started at ${start.first}, ${start.second} and stands at $x, $y after the turn")
        }
        // Ring centres are shares of the screen, so the quarter turn moves them the same way.
        for ((index, centre) in ice.ringCentresOf(0).withIndex()) {
            val (x, y) = ringsBefore[index]
            assertTrue(abs(centre.first - y) < 1e-4f && abs(centre.second - (1f - x)) < 1e-4f,
                "a ring at $x, $y stands at $centre after the turn")
        }
    }

    @Test
    fun ringsBornABeatApartSitTheSameDistanceApart() {
        val ice = ThinIce()
        var radii = emptyList<Float>()
        RenderHarness.forEachFrameOf(ice, 64, 36, 150, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.LowHit, step) }) { _, step ->
            if (step == 149) radii = ice.radiiOf(0)
        }
        // The three injected kicks are half a second apart, so their rings are the three smallest.
        val newest = radii.take(3)
        println("thin ice: newest rings of well A at $newest")
        assertTrue(newest.size == 3, "three kicks draw three rings, had $radii")
        val first = newest[1] - newest[0]
        val second = newest[2] - newest[1]
        assertTrue(first > 0.01f && abs(first - second) < 0.02f * first + 1e-4f, "even rings: gaps $first and $second")
    }

    @Test
    fun theCrossingPointsAreWhite() {
        val ice = ThinIce()
        val width = 640
        val height = 360
        var pixels = IntArray(0)
        var points = emptyList<Pair<Float, Float>>()
        RenderHarness.forEachFrame(ice, width, height, 90, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step == 89) {
                pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                points = ice.crossingPoints()
            }
        }
        val unit = min(width, height).toFloat()
        // The camera glides and dives the whole picture about the middle.
        val glideX = ice.glideX * width
        val glideY = ice.glideY * height
        val dive = ice.diveValue
        var checked = 0
        var white = 0
        for ((x, y) in points) {
            val px = ((x * unit - width / 2f) * dive + width / 2f + glideX).toInt()
            val py = ((y * unit - height / 2f) * dive + height / 2f + glideY).toInt()
            if (px !in 2 until width - 2 || py !in 2 until height - 2) continue
            checked++
            var best = 0
            for (oy in -1..1) for (ox in -1..1) {
                val pixel = pixels[(py + oy) * width + px + ox]
                val least = minOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
                best = maxOf(best, least)
            }
            if (best > 170) white++
        }
        println("thin ice: $white of $checked crossing points are white")
        assertTrue(checked >= 4, "the two wells' rings should cross on screen, found $checked crossings")
        assertTrue(white >= checked * 0.8f, "crossings should be white, $white of $checked were")
    }

    @Test
    fun accentedKicksAddAtMostSixCracksASection() {
        val ice = ThinIce()
        var most = 0
        var seen = 0
        RenderHarness.forEachFrame(ice, 64, 36, 900, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ ->
            most = maxOf(most, ice.kickCracksThisSection)
            seen = maxOf(seen, ice.cracks)
        }
        println("thin ice: at most $most kick cracks in a section, $seen cracks at once")
        assertTrue(most in 1..6, "accented kicks crack the ice, at most six times a section, had $most")
    }

    @Test
    fun cracksCreepOutOfTheWellsInTheLastBeatBeforeADrop() {
        val ice = ThinIce()
        var step = 0
        val dropAt = 150
        val counts = IntArray(dropAt)
        RenderHarness.forEachFrameOf(ice, 64, 36, dropAt, VizPalette.Prism,
            source = { at -> step = at; InjectedFrames.frame(null, at) }, future = { dropAhead(dropAt) { step } }) { _, at ->
            counts[at] = ice.cracks
        }
        // With no pulse the free cycle here runs about three seconds, so its beat is about 47 steps.
        println("thin ice: cracks before the drop ${counts.slice(0 until dropAt step 10)}")
        assertTrue(counts[dropAt - 60] == 0, "no crack before the last beat, had ${counts[dropAt - 60]}")
        assertTrue(counts[dropAt - 1] > 0, "cracks creep out of the wells before the drop")
    }

    @Test
    fun aBreakdownHealsTheCracks() {
        val ice = ThinIce()
        var step = 0
        // A drop that the audio ahead promised and that never came leaves its creeping cracks behind.
        val dropAt = 40
        val counts = IntArray(600)
        RenderHarness.forEachFrameOf(ice, 64, 36, 600, VizPalette.Prism,
            source = { at -> step = at; InjectedFrames.frame(VizDriver.Breakdown, at) }, future = { dropAhead(dropAt) { step } }) { _, at ->
            counts[at] = ice.cracks
        }
        println("thin ice: cracks ${counts.slice(0 until 600 step 50)}")
        assertTrue(counts[InjectedFrames.STRUCTURE_STEP] > 0, "the cracks stay until the breakdown")
        assertTrue(counts[599] == 0, "a breakdown heals every crack, ${counts[599]} left")
    }

    /** Audio ahead that holds a drop at step [dropAt] while the render is before it. */
    private fun dropAhead(dropAt: Int, now: () -> Int): VizFuture = object : VizFuture {
        override fun at(secondsAhead: Float): SpectrumFrame? = null
        override val nextOnsetSeconds: Float = -1f
        override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? {
            val step = now()
            if (kind != AudioEventKind.Drop || step >= dropAt) return null
            val pts = dropAt * 1_000_000L / 60L
            val event = AudioEvent(Generation.Initial, 0L, 1L,
                AudioDetection(AudioEventKind.Drop, pts, pts, 0.8f, 0.9f, 0.5f), AudioEventSource.LiveStructure)
            return UpcomingAudioEvent(event, (dropAt - step) / 60f)
        }
    }

    @Test
    fun aDropBreaksTheIceAndThePiecesAreGoneWithinABar() {
        val ice = ThinIce()
        val sinking = IntArray(600)
        RenderHarness.forEachFrameOf(ice, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step ->
            sinking[step] = ice.sinking
        }
        val landed = InjectedFrames.STRUCTURE_STEP
        val broke = (landed until 600).firstOrNull { sinking[it] > 0 } ?: -1
        val gone = (broke until 600).firstOrNull { sinking[it] == 0 } ?: -1
        println("thin ice: broke at $broke into ${sinking.getOrElse(broke) { 0 }} pieces, gone at $gone")
        assertTrue(broke in landed..landed + 2, "the ice breaks on the drop, broke at $broke")
        assertTrue(sinking[broke] >= 9, "the break cuts many pieces, had ${sinking[broke]}")
        // The free cycle here runs about three seconds, and the pieces sink within one bar of it.
        assertTrue(gone > broke && gone - broke < 4 * 60 + 30, "the pieces are gone within a bar, took ${gone - broke} steps")
    }

    @Test
    fun aNewbornRingBendsByUpToTwelvePercent() {
        assertTrue(ThinIce.SWING == 0.12f)
        val ice = ThinIce()
        var slot = -1
        var born = 0f
        var later = 0f
        RenderHarness.forEachFrameOf(ice, 160, 90, 188, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(if (step <= 70) VizDriver.LowHit else null, step) }) { _, step ->
            if (step == 67) {
                slot = ice.newestRing
                born = ice.amplitudeOf(slot)
            }
            if (step == 187) later = ice.amplitudeOf(slot)
        }
        println("thin ice: a newborn ring bends by $born, two seconds later by $later")
        assertTrue(born > 0.1f, "a newborn ring bends by more than a tenth of the short side, had $born")
        assertTrue(later < born * 0.6f, "the bend settles as the ring spreads, $born became $later")
    }

    @Test
    fun hitsLandWhereTheMixPutsThem() {
        val loud = FloatArray(256) { 0.9f * kotlin.math.sin(it * 0.3f) }
        val soft = FloatArray(256) { 0.1f * kotlin.math.sin(it * 0.3f) }
        fun place(driver: VizDriver, left: FloatArray, right: FloatArray): Pair<Float, Float> {
            val ice = ThinIce()
            var centre = 0f to 0f
            RenderHarness.forEachFrameOf(ice, 90, 160, 67, VizPalette.Prism,
                source = { step -> InjectedFrames.frame(driver, step, scopeLeft = left, scopeRight = right) }) { _, step ->
                if (step == 66) centre = ice.ringCentreX(ice.newestRing) to ice.ringCentreY(ice.newestRing)
            }
            return centre
        }
        val kick = place(VizDriver.LowHit, loud, soft)
        val hat = place(VizDriver.HighHit, soft, loud)
        val snare = place(VizDriver.BodyHit, soft, soft)
        println("thin ice: a kick mixed left rings at $kick, a hat mixed right at $hat, a centred snare at $snare")
        assertTrue(kick.first < 0.4f && kick.second > 0.7f, "a kick mixed left rings low on the left, at $kick")
        assertTrue(hat.first > 0.6f && hat.second < 0.3f, "a hat mixed right rings high on the right, at $hat")
        assertTrue(abs(snare.first - 0.5f) < 0.1f && abs(snare.second - 0.5f) < 0.15f, "a centred snare rings in the middle, at $snare")
    }

    @Test
    fun theWellsSitOnTheTwoLoudestBandGroups() {
        fun leaderAlong(low: Boolean): Float {
            val ice = ThinIce()
            var along = 0f
            RenderHarness.forEachFrameOf(ice, 90, 160, 180, VizPalette.Prism,
                source = { step -> InjectedFrames.toneFrame(step, lowBand = low) }) { _, step ->
                // The long side of a portrait screen runs up from its bass end at the bottom.
                if (step == 179) along = 1f - ice.wellAt(0).second
            }
            return along
        }
        val low = leaderAlong(true)
        val high = leaderAlong(false)
        println("thin ice: the loudest well sits at $low of the long side under a low tone, at $high under a high one")
        assertTrue(low < 0.25f, "a low tone puts the loudest well in the bass quarter, at $low")
        // The high tone lies in the third quarter of the bands, so its well sits above the middle.
        assertTrue(high > 0.55f, "a high tone puts the loudest well high, at $high")
    }

    @Test
    fun aBirthMovesAWellToTheBandGroupThatBecameLoudest() {
        val ice = ThinIce()
        var ringsBefore = 0
        var ringsAfter = 0
        var cracksBefore = 0
        var cracksAfter = 0
        var along = 0f
        RenderHarness.forEachFrameOf(ice, 90, 160, 271, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = false) }) { _, step ->
            if (step == 80) {
                ringsBefore = ice.radiiOf(1).size
                cracksBefore = ice.cracks
                ice.birth()
                ringsAfter = ice.radiiOf(1).size
                cracksAfter = ice.cracks
            }
            if (step == 270) along = 1f - ice.wellAt(1).second
        }
        println("thin ice: a birth moved well B to $along, rings $ringsBefore to $ringsAfter")
        assertTrue(ice.wellMoves == 1, "the birth moved a well, counted ${ice.wellMoves}")
        assertTrue(ringsAfter == ringsBefore + 1 && cracksAfter == cracksBefore, "the birth rings once and cracks nothing")
        assertTrue(along > 0.55f, "well B slews to the loudest band group, at $along")
    }

    @Test
    fun theImpulsesCarryEveryHit() {
        val ice = ThinIce()
        RenderHarness.forEachFrameOf(ice, 64, 36, 140, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.LowHit, step) }) { _, _ -> }
        assertTrue(ice.impulseCount == 3, "three kicks put three hits in the impulse list, had ${ice.impulseCount}")
    }

    @Test
    fun theSnowRemembersTheHats() {
        val ice = ThinIce()
        val share = FloatArray(300)
        var front = 0f
        var inside = 0f
        RenderHarness.forEachFrameOf(ice, 96, 54, 300, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.HighHit, step) }) { _, step ->
            share[step] = ice.driftInkShare()
            if (step == 100) {
                // The newest ring is the hat's; its front gathers snow, its middle does not.
                val slot = ice.newestRing
                val cx = ice.ringCentreX(slot)
                val cy = ice.ringCentreY(slot)
                val r = ice.radiusOf(slot)
                // Radii are in shorter sides; the screen is 54 high, so a shorter side is the height.
                front = ice.driftInkAt(cx + r * 54f / 96f, cy)
                inside = ice.driftInkAt(cx + 0.5f * r * 54f / 96f, cy)
            }
        }
        val still = ThinIce()
        var stillShare = 0f
        RenderHarness.forEachFrameOf(still, 96, 54, 300, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) }) { _, _ -> stillShare = maxOf(stillShare, still.driftInkShare()) }
        println("thin ice: snow ${share[60]} at step 60, ${share[100]} at 100, ${share[299]} at 299; front $front, inside $inside; still $stillShare")
        assertTrue(share[60] == 0f, "no snow before the first hat, had ${share[60]}")
        assertTrue(share[100] > 0.002f && share[299] > 0.001f, "the hats lay snow that lasts, had ${share[100]} and ${share[299]}")
        assertTrue(stillShare == 0f, "no hats, no snow, had $stillShare")
        assertTrue(front > inside, "the ring front gathers snow: $front at the front, $inside inside")
    }

    @Test
    fun theFormsFollowTheSong() {
        fun formAt(driver: VizDriver?, step: Int, song: RenderHarness.Song? = null, start: Int = -1): String {
            val ice = ThinIce()
            var form = ""
            if (song != null) {
                RenderHarness.forEachFrame(ice, 64, 36, step + 1, VizPalette.Prism, song) { _, at -> if (at == step) form = ice.forms.form }
            } else {
                RenderHarness.forEachFrameOf(ice, 64, 36, step + 1, VizPalette.Prism,
                    source = { at -> InjectedFrames.frame(driver, at) },
                    beforeDraw = { if (start >= 0 && ice.forms.form == "Lake" && it.frame.ptsMicros == 0L) ice.jumpTo(start) }) { _, at ->
                    if (at == step) form = ice.forms.form
                }
            }
            return form
        }
        val frost = formAt(VizDriver.Breakdown, 126)
        val shatter = formAt(VizDriver.Drop, 70)
        val under = formAt(VizDriver.Drop, 66 + 200)
        val lakeAgain = formAt(VizDriver.Section, 76, start = ThinIce.UNDERWATER)
        val silent = formAt(null, 120, RenderHarness.Song.Silence)
        val lively = formAt(null, 120, RenderHarness.Song.Lively)
        println("thin ice: breakdown $frost, drop $shatter then $under, section after under water $lakeAgain, silence $silent, lively $lively")
        assertTrue(frost == "Frost" && shatter == "Shatter" && under == "Underwater", "the forms follow the song")
        assertTrue(lakeAgain == "Lake", "a section freezes a new lake, was $lakeAgain")
        assertTrue(silent == "Lake" && lively == "Lake", "a steady song stays a lake")
        val ice = ThinIce()
        var lace = 0f
        RenderHarness.forEachFrameOf(ice, 64, 36, 187, VizPalette.Prism,
            source = { at -> InjectedFrames.frame(VizDriver.Breakdown, at) }) { _, at -> if (at == 186) lace = ice.laceShare() }
        println("thin ice: lace share $lace in a breakdown")
        assertTrue(lace > 0f, "frost grows lace, had $lace")
    }

    @Test
    fun underwaterShowsTheRingsAsLightAndBubblesFromHats() {
        val ice = ThinIce()
        var bubblesBefore = -1
        var bubblesAfter = 0
        var mean = 0f
        var white = 0
        RenderHarness.forEachFrameOf(ice, 160, 90, 90, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.HighHit, step) },
            beforeDraw = { ice.jumpTo(ThinIce.UNDERWATER) }) { bitmap, step ->
            if (step == 65) bubblesBefore = ice.bubbleCount()
            if (step == 89) {
                bubblesAfter = ice.bubbleCount()
                val pixels = IntArray(160 * 90)
                bitmap.readPixels(pixels)
                mean = pixels.sumOf { luma(it).toDouble() }.toFloat() / pixels.size
                white = pixels.count { minOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) > 230 }
            }
        }
        println("thin ice: under water bubbles $bubblesBefore then $bubblesAfter, mean luma $mean, white pixels $white")
        assertTrue(bubblesAfter > bubblesBefore, "a hat under water sends up bubbles")
        assertTrue(mean > 0.02f, "the deep is lit, mean luma $mean")
        assertTrue(white < 5, "no white ring lines under water, had $white")
    }

    @Test
    fun theCameraGlidesOnTheSlowCycleAndDivesOnADrop() {
        val ice = ThinIce()
        var lowest = 0f
        var highest = 0f
        var steepest = 0f
        var last = 0f
        RenderHarness.forEachFrame(ice, 64, 36, 1800, VizPalette.Prism, RenderHarness.Song.Lively) { _, step ->
            val x = ice.glideX
            if (step > 0) steepest = maxOf(steepest, abs(x - last))
            last = x
            lowest = minOf(lowest, x)
            highest = maxOf(highest, x)
        }
        val quiet = ThinIce()
        var quietMoved = 0f
        var quietAt60 = 0f
        RenderHarness.forEachFrame(quiet, 64, 36, 300, VizPalette.Prism, RenderHarness.Song.Silence) { _, step ->
            if (step == 60) quietAt60 = quiet.glideX
            if (step > 60) quietMoved = maxOf(quietMoved, abs(quiet.glideX - quietAt60))
        }
        val diver = ThinIce()
        val dive = FloatArray(260)
        RenderHarness.forEachFrameOf(diver, 64, 36, 260, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) }) { _, step -> dive[step] = diver.diveValue }
        val diveStep = (1 until 260).maxOf { abs(dive[it] - dive[it - 1]) }
        println("thin ice: glide $lowest to $highest, steepest $steepest a frame; silence moved $quietMoved; dive ${dive[65]} to ${dive[256]}, steepest $diveStep")
        assertTrue(lowest < 0f && highest > 0f, "the glide takes both sides")
        assertTrue(steepest <= 0.002f, "the glide never jumps, moved $steepest in a frame")
        assertTrue(quietMoved == 0f, "a silence holds the camera, moved $quietMoved")
        assertTrue(dive[65] == 1f && dive[256] > 1.5f, "the drop dives under the ice, ${dive[65]} to ${dive[256]}")
        assertTrue(diveStep <= 0.25f / 60f + 1e-4f, "the dive never cuts, moved $diveStep in a frame")
    }

    @Test
    fun noHitDriverDeclaresTheCamera() {
        val hits = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)
        val drives = checkNotNull(ThinIce().mapping).drives
        assertTrue(drives.none { it.driver in hits && it.property == VizProperty.Camera }, "a hit moves the camera")
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
}
