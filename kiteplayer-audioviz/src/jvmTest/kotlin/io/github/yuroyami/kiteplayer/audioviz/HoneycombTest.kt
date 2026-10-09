package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Honeycomb
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port of Soundcloud Visualizer by Michael Bromley: it draws, it stands still in silence, and it keeps the page's volume and its pole. */
class HoneycombTest {

    init { useSkiaGraphics() }

    @Test
    fun drawsSixtyFramesOfTheLivelySongWithInkAndNoBlowOut() {
        var peakInk = 0f
        var peakBlown = 0f
        RenderHarness.forEachFrame(Honeycomb(), 320, 200, 60, VizPalette.Prism, RenderHarness.Song.Lively) { bitmap, step ->
            if (step < 30) return@forEachFrame
            val image = with(RenderHarness) { bitmap.toBufferedImage() }
            peakInk = maxOf(peakInk, inkFraction(image))
            peakBlown = maxOf(peakBlown, blownFraction(image))
        }
        assertTrue(peakInk > 0.004f, "the honeycomb should put ink down, had $peakInk")
        assertTrue(peakBlown <= 0.3f, "the picture should not saturate to white, had $peakBlown")
    }

    @Test
    fun standsStillInSilenceAndMovesUnderMusic() {
        val silence = meanChange(RenderHarness.Song.Silence)
        val music = meanChange(RenderHarness.Song.Lively)
        assertTrue(silence < 0.0005f, "silence should hold the picture still, changed $silence")
        assertTrue(music > 0.002f, "music should move the picture, changed $music")
        assertTrue(silence <= 0.2f * music, "silence $silence against music $music")
    }

    @Test
    fun theVolumeIsTheSumOfBinsZeroToSeventyNineOfTheBrowsersRow() {
        val honeycomb = Honeycomb()
        val analyser = honeycomb.analyser
        assertEquals(256, analyser.fftSize)
        assertEquals(128, analyser.binCount)
        assertEquals(0.8f, analyser.smoothing)
        assertEquals(-100f, analyser.minDecibels)
        assertEquals(-30f, analyser.maxDecibels)
        run(honeycomb, RenderHarness.Song.Lively, rate = 60, seconds = 2f)
        assertEquals(analyser.frequencyBytes.take(80).sum(), honeycomb.volume)
        assertTrue(honeycomb.volume > 4_000, "a loud drum loop gives a large volume, had ${honeycomb.volume}")
    }

    @Test
    fun theReadsRunEveryTwentyMillisecondsOfHeardTimeAtAnyScreenRate() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            val honeycomb = Honeycomb()
            run(honeycomb, RenderHarness.Song.Lively, rate = rate, seconds = 2f)
            assertTrue(honeycomb.ticks in 98L..100L, "$rate Hz gave ${honeycomb.ticks} reads in two seconds")
        }
        val silent = Honeycomb()
        run(silent, RenderHarness.Song.Silence, rate = 60, seconds = 2f)
        assertEquals(0L, silent.ticks, "silence is not heard, so nothing is read and nothing turns")
    }

    @Test
    fun theTangentPoleSitsBetweenAVolumeOf9424And9425() {
        assertEquals(2.0, Honeycomb.mentalFactor(9_424))
        assertEquals(-20.0, Honeycomb.mentalFactor(9_425))
        // The push is held at 2 from 7,955 up to the pole, and at -20 for 150 past it.
        assertEquals(2.0, Honeycomb.mentalFactor(7_955))
        assertTrue(Honeycomb.mentalFactor(7_954) < 2.0)
        assertEquals(-20.0, Honeycomb.mentalFactor(9_574))
        assertTrue(Honeycomb.mentalFactor(9_575) > -20.0)
        assertTrue(Honeycomb.mentalFactor(18_849) < 0.0 && Honeycomb.mentalFactor(18_851) > 0.0)
        // One step of volume across the pole turns a corner's push from outward to ten times as far inward.
        val out = Honeycomb.offsetFactor(distance = 400.0, volume = 9_424, high = 200.0)
        val back = Honeycomb.offsetFactor(distance = 400.0, volume = 9_425, high = 200.0)
        assertTrue(out > 0.0 && back < 0.0, "outward below the pole, inward above it: $out and $back")
        assertEquals(-10.0, back / out, 0.01)
        assertTrue(abs(back) > 400.0, "past the pole a corner 400 dp out is thrown through the middle, moved $back")
        assertTrue(Honeycomb.offsetFactor(400.0, 9_425, -1.0).isNaN(), "a peak below zero has no push, as on the page")
    }

    @Test
    fun theCornersFollowTheJumpAtThePoleAtABoundedSpeed() {
        // The drum loop carries the volume across the tangent's pole, where the page's factor jumps
        // from 2 to -20 in one read and blanks the honeycomb for a frame: a flash at every crossing.
        val honeycomb = Honeycomb()
        var page = Honeycomb.mentalFactor(0)
        var used = honeycomb.factor
        var widestJump = 0.0
        var widestStep = 0.0
        run(honeycomb, RenderHarness.Song.Lively, rate = 60, seconds = 4f) {
            val next = Honeycomb.mentalFactor(honeycomb.volume)
            widestJump = maxOf(widestJump, abs(next - page))
            widestStep = maxOf(widestStep, abs(honeycomb.factor - used))
            page = next
            used = honeycomb.factor
        }
        assertTrue(widestJump > 10.0, "the song should carry the page's factor across the pole, it jumped $widestJump")
        // The harness steps by 1f / 60, a Float, so the bound takes the same frame time.
        val bound = Honeycomb.FACTOR_SPEED * (1f / 60).toDouble()
        assertTrue(widestStep <= bound + 1e-9, "the corners moved $widestStep of the factor in one frame, against $bound")
    }

    @Test
    fun theHoneycombHas127TilesSizedByTheLongerSideInDp() {
        for ((density, scale) in listOf(1f to 1, 2f to 2, 3f to 3)) {
            val honeycomb = Honeycomb()
            run(honeycomb, RenderHarness.Song.Silence, rate = 60, seconds = 0.05f, width = 960 * scale, height = 540 * scale, density = density)
            assertEquals(38.4, honeycomb.tileSize, 1e-9, "max(960, 540) / 25 in dp at density $density")
            // The centre tile's first corner points straight down, one tile size from the middle.
            assertEquals(0.0, honeycomb.vertexX[0], 1e-9)
            assertEquals(38.4, honeycomb.vertexY[0], 1e-9)
            var farthest = 0.0
            for (corner in 0 until Honeycomb.TILES * Honeycomb.SIDES) {
                farthest = maxOf(farthest, sqrt(honeycomb.vertexX[corner] * honeycomb.vertexX[corner] + honeycomb.vertexY[corner] * honeycomb.vertexY[corner]))
            }
            // Six rings of tiles 67 dp apart, plus a tile size.
            assertTrue(farthest in 400.0..450.0, "the outer ring's corners reach $farthest dp")
            assertEquals(0, honeycomb.ringOf(0))
            for (num in 1..6) assertEquals(1, honeycomb.ringOf(num), "tile $num")
            assertEquals(6, honeycomb.ringOf(126))
            for (num in 0 until Honeycomb.TILES) {
                val p = honeycomb.positionOf(num)
                assertTrue(p >= 0f && p < 1f, "tile $num at $p")
            }
            assertEquals(0f, honeycomb.positionOf(0))
        }
    }

    @Test
    fun theAlphaRampIsThePages() {
        assertEquals(0.024, Honeycomb.alphaOf(0.0), 0.001)
        assertEquals(0.34, Honeycomb.alphaOf(32.0), 0.01)
        assertEquals(0.68, Honeycomb.alphaOf(64.0), 0.01)
        assertEquals(0.88, Honeycomb.alphaOf(96.0), 0.01)
        assertTrue(Honeycomb.alphaOf(160.0) > 0.99)
    }

    @Test
    fun theTilesReadTheWholeSpectrumWithTheBassInTheMiddle() {
        val low = Honeycomb()
        RenderHarness.forEachFrameOf(low, 160, 90, 120, VizPalette.Prism, source = { step -> InjectedFrames.toneFrame(step, lowBand = true) }) { _, _ -> }
        val lowCentre = low.tileValue(0)
        val lowOuter = (91..126).map { low.tileValue(it) }.average()
        val high = Honeycomb()
        RenderHarness.forEachFrameOf(high, 160, 90, 120, VizPalette.Prism, source = { step -> InjectedFrames.toneFrame(step, lowBand = false) }) { _, _ -> }
        val highCentre = high.tileValue(0)
        val highMiddle = (37..90).maxOf { high.tileValue(it) }
        println("bass tone: centre tile $lowCentre, mean of ring 6 $lowOuter")
        println("treble tone: centre tile $highCentre, largest of rings 4 and 5 $highMiddle")
        assertTrue(lowCentre > 150.0, "a bass tone lights the centre, had $lowCentre")
        assertTrue(lowOuter < 20.0, "a bass tone leaves the outer ring dim, had $lowOuter")
        assertTrue(highCentre < 60.0, "a treble tone leaves the centre dim, had $highCentre")
        assertTrue(highMiddle > 150.0, "a treble tone lights rings 4 and 5, had $highMiddle")
    }

    @Test
    fun aTilesHueMovesWithItsLevelOverThePalette() {
        val dim = Honeycomb.fillOf(20.0, 1f, VizPalette.Prism, 0f, 0)
        val middle = Honeycomb.fillOf(120.0, 1f, VizPalette.Prism, 0f, 0)
        val bright = Honeycomb.fillOf(240.0, 1f, VizPalette.Prism, 0f, 0)
        val fire = Honeycomb.fillOf(200.0, 1f, VizPalette.Fire, 0f, 0)
        val prism = Honeycomb.fillOf(200.0, 1f, VizPalette.Prism, 0f, 0)
        println("fills: 20 $dim, 120 $middle, 240 $bright, Prism 200 $prism, Fire 200 $fire")
        println(
            "fill distances: 20 to 120 ${colourDistance(dim, middle)}, 120 to 240 ${colourDistance(middle, bright)}, " +
                "20 to 240 ${colourDistance(dim, bright)}, Prism to Fire ${colourDistance(prism, fire)}",
        )
        // A dim tile keeps the palette's lightness floor, so its distance to the middle tile is the smallest.
        assertTrue(colourDistance(dim, middle) > 0.12f, "20 against 120: ${colourDistance(dim, middle)}")
        assertTrue(colourDistance(middle, bright) > 0.12f, "120 against 240: ${colourDistance(middle, bright)}")
        assertTrue(colourDistance(dim, bright) > 0.12f, "20 against 240: ${colourDistance(dim, bright)}")
        assertTrue(colourDistance(prism, fire) > 0.12f, "Prism against Fire: ${colourDistance(prism, fire)}")
        assertTrue(Honeycomb.fillOf(0.0, 1f, VizPalette.Prism, 0f, 0).alpha >= Honeycomb.ALPHA_FLOOR)
    }

    @Test
    fun theHiveTurnsANotchOnEachBeatAndIsStillInSilence() {
        // One run, a pulse then silence: a second harness run would restart the drawing and prove nothing.
        val honeycomb = Honeycomb()
        val silent = RenderHarness.player(RenderHarness.Song.Silence, 8f)
        val turns = FloatArray(840)
        RenderHarness.forEachFrameOf(
            honeycomb, 96, 54, 840, VizPalette.Prism,
            source = { step -> if (step < 600) InjectedFrames.frame(VizDriver.Pulse, step) else silent.next(1f / 60f) },
        ) { _, step ->
            turns[step] = honeycomb.turnValue
        }
        val notch = Honeycomb.NOTCH
        assertTrue(abs(turns[599]) >= 14 * notch && abs(turns[599]) <= 20 * notch, "the turn ended at ${turns[599] / notch} notches")
        var still = 0
        var snapped = 0
        for (step in 61..599) {
            val moved = abs(turns[step] - turns[step - 1])
            if (moved < notch / 20) still++
            if (moved > notch / 4) snapped++
        }
        println("final turn ${turns[599] / notch} notches, $still still frames, $snapped snapping frames")
        assertTrue(still >= 10, "the turn should hold between beats, held on $still frames")
        assertTrue(snapped >= 10, "the turn should step on a beat, stepped on $snapped frames")

        var widest = 0f
        for (step in 720..839) widest = maxOf(widest, abs(turns[step] - turns[step - 1]))
        println("silence: widest change between two frames $widest")
        assertTrue(widest < 1e-4f, "a silence should hold the turn, it moved $widest")
    }

    @Test
    fun aMorphFlipsTheTurn() {
        val honeycomb = Honeycomb()
        honeycomb.reset()
        assertEquals(1f, honeycomb.turnSign)
        honeycomb.onMorph()
        assertEquals(-1f, honeycomb.turnSign)
        honeycomb.onMorph()
        assertEquals(1f, honeycomb.turnSign)
    }

    @Test
    fun theHiveGrowsARingOnEachBirthAndShrinksOnABreakdown() {
        val honeycomb = Honeycomb()
        honeycomb.reset()
        assertEquals(3, honeycomb.ringsTarget)
        repeat(3) { honeycomb.grow() }
        assertEquals(6, honeycomb.ringsTarget)
        honeycomb.grow()
        assertEquals(6, honeycomb.ringsTarget, "the hive stops at six rings")
        honeycomb.collapse()
        assertEquals(2, honeycomb.ringsTarget)
        honeycomb.grow()
        assertEquals(3, honeycomb.ringsTarget)

        // The harness restarts the drawing before its first frame, so the growth is asked for after that.
        val following = Honeycomb()
        var grown = false
        val shown = FloatArray(600)
        RenderHarness.forEachFrameOf(
            following, 64, 36, 600, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(null, step) },
            beforeDraw = {
                if (!grown) {
                    following.grow()
                    grown = true
                }
            },
        ) { _, step ->
            shown[step] = following.ringsShownValue
        }
        println("rings shown: step 29 ${shown[29]}, step 599 ${shown[599]}")
        assertTrue(shown[29] > 3f && shown[29] < 4f, "half a second in the fourth ring is half grown, had ${shown[29]}")
        assertTrue(shown[599] > 3.9f, "ten seconds in the fourth ring is fully grown, had ${shown[599]}")
    }

    @Test
    fun aKickOpensTheLoudCells() {
        val hit = InjectedFrames.HIT_STEPS[1]
        val opens = HashMap<VizDriver?, FloatArray>()
        val lumas = HashMap<VizDriver?, Float>()
        for (driver in listOf(VizDriver.LowHit, null)) {
            val honeycomb = Honeycomb()
            val open = FloatArray(120)
            RenderHarness.forEachFrameOf(
                honeycomb, 160, 90, 120, VizPalette.Prism,
                // Only the first hit counts.
                source = { step -> loudCentre(InjectedFrames.frame(if (step < hit) driver else null, step)) },
            ) { bitmap, step ->
                open[step] = honeycomb.openValue
                if (step == InjectedFrames.HIT_STEPS[0]) {
                    val pixels = IntArray(160 * 90)
                    bitmap.readPixels(pixels)
                    var sum = 0f
                    var count = 0
                    for (y in 36 until 54) {
                        for (x in 64 until 96) {
                            val pixel = pixels[y * 160 + x]
                            sum += (0.2126f * (pixel shr 16 and 0xFF) + 0.7152f * (pixel shr 8 and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
                            count++
                        }
                    }
                    lumas[driver] = sum / count
                }
            }
            opens[driver] = open
        }
        val kicked = opens.getValue(VizDriver.LowHit)
        val still = opens.getValue(null)
        val first = InjectedFrames.HIT_STEPS[0]
        val kickedLuma = lumas.getValue(VizDriver.LowHit)
        val stillLuma = lumas.getValue(null)
        println("open at the hit ${kicked[first]}, 45 steps later ${kicked[first + 45]}")
        println("middle luma at the hit: kick $kickedLuma, no kick $stillLuma")
        assertTrue(kicked[first] > 0.5f, "a kick opens the cells, had ${kicked[first]}")
        assertTrue(kicked[first + 45] < 0.1f, "they close within a second, had ${kicked[first + 45]}")
        assertTrue(still.all { it == 0f }, "without a kick nothing opens")
        assertTrue(abs(kickedLuma - stillLuma) > 0.01f, "the open cells light the middle: $kickedLuma against $stillLuma")
    }

    @Test
    fun theDiscProjectsAsTheFlatLayout() {
        val honeycomb = renderTwoSilentFramesIn("Disc", 960, 540)
        println(
            "disc, tile 0 corner 0: page (${honeycomb.cornerPageX(0, 0)}, ${honeycomb.cornerPageY(0, 0)}) " +
                "screen (${honeycomb.cornerScreenX(0, 0)}, ${honeycomb.cornerScreenY(0, 0)})",
        )
        for (num in listOf(0, 126)) {
            for (corner in 0 until Honeycomb.SIDES) {
                val pageX = honeycomb.cornerPageX(num, corner)
                val pageY = honeycomb.cornerPageY(num, corner)
                val screenX = honeycomb.cornerScreenX(num, corner)
                val screenY = honeycomb.cornerScreenY(num, corner)
                assertTrue(abs(screenX - (480f + pageX)) < 0.5f, "tile $num corner $corner: screen x $screenX against 480 + page x $pageX")
                assertTrue(abs(screenY - (270f + pageY)) < 0.5f, "tile $num corner $corner: screen y $screenY against 270 + page y $pageY")
            }
        }
    }

    @Test
    fun theFormStartsAsADiscAndChangesOnTheDrumLoop() {
        assertEquals("Disc", Honeycomb().forms.form)
        val honeycomb = Honeycomb()
        RenderHarness.forEachFrame(honeycomb, 64, 36, 7200, VizPalette.Prism, RenderHarness.Song.Lively) { _, _ -> }
        val forms = honeycomb.forms
        println("drum loop, two minutes: ${forms.morphs} morphs, ${forms.births} births, ends on ${forms.form}")
        assertTrue(forms.morphs >= 3, "the drum loop should morph at least three times, had ${forms.morphs}")
        assertTrue(forms.births >= 1, "the drum loop should give at least one birth, had ${forms.births}")
        assertTrue(forms.form in Honeycomb.FORMS, "the form should be one of the four, was ${forms.form}")
    }

    @Test
    fun theDomeLiftsTheCentreTowardTheViewer() {
        val disc = renderTwoSilentFramesIn("Disc", 960, 540)
        val dome = renderTwoSilentFramesIn("Dome", 960, 540)
        val discWidth = widthOf(disc, 0)
        val domeWidth = widthOf(dome, 0)
        val discRim = widthOf(disc, 126)
        val domeRim = widthOf(dome, 126)
        val centreGrowth = domeWidth / discWidth
        val rimGrowth = domeRim / discRim
        println("centre tile width: disc $discWidth, dome $domeWidth; rim tile 126 width: disc $discRim, dome $domeRim")
        assertTrue(domeWidth >= 1.05f * discWidth, "the middle tile should be nearer the eye: width $domeWidth against $discWidth")
        // The rim's corners sit on the sphere too and the tilt moves them, so the rim's width changes as well, 66 to 43
        // on this canvas. The dome shows as the middle growing far more than the rim.
        assertTrue(centreGrowth >= 1.2f * rimGrowth, "the middle should grow far more than the rim: $centreGrowth against $rimGrowth")
    }

    @Test
    fun theTunnelShowsThePastInItsOuterRings() {
        val honeycomb = Honeycomb()
        RenderHarness.forEachFrameOf(
            honeycomb, 160, 90, 300, VizPalette.Prism,
            source = { step -> InjectedFrames.toneFrame(step, lowBand = step < 180) },
            beforeDraw = { honeycomb.jumpTo("Tunnel") },
        ) { _, _ -> }
        // The low tone ran from step 60 to 179, then the high tone. Ring 3 reads about three beats back.
        val tile = honeycomb.tileNearest(3, 0f)
        val ringValue = honeycomb.tileValue(tile)
        val centreValue = honeycomb.tileValue(0)
        println("tunnel after 300 steps: ring 3 tile $tile shows $ringValue, centre shows $centreValue, ring 3 beat time ${3 * 0.78f} s")
        assertTrue(ringValue > 150.0, "ring 3 should still show the low tone from three beats back, had $ringValue")
        assertTrue(centreValue < 60.0, "the centre should show the live high tone at the bass position, had $centreValue")
    }

    @Test
    fun aDropShattersTheHiveAndItReassemblesWithinACycle() {
        val honeycomb = Honeycomb()
        val shatter = FloatArray(400)
        var formAtTen = ""
        RenderHarness.forEachFrameOf(
            honeycomb, 96, 54, 400, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(VizDriver.Drop, step) },
        ) { _, step ->
            shatter[step] = honeycomb.shatterValue
            if (step == InjectedFrames.STRUCTURE_STEP + 10) formAtTen = honeycomb.forms.form
        }
        val at = InjectedFrames.STRUCTURE_STEP
        val peak = (at..at + 2).maxOf { shatter[it] }
        println("drop: shatter peak $peak, 240 steps later ${shatter[at + 240]}, form ten steps after the drop $formAtTen")
        assertTrue(peak > 0.8f, "a drop should shatter the hive, peak $peak")
        assertTrue(shatter[at + 240] < 0.1f, "the hive should be whole within a cycle, had ${shatter[at + 240]}")
        assertEquals("Shatter", formAtTen)
    }

    /** A fresh drawing held in [form] for two silent frames of [width] by [height], so the lens and the weights are settled. */
    private fun renderTwoSilentFramesIn(form: String, width: Int, height: Int): Honeycomb {
        val honeycomb = Honeycomb()
        val silent = RenderHarness.player(RenderHarness.Song.Silence, 2f)
        RenderHarness.forEachFrameOf(
            honeycomb, width, height, 2, VizPalette.Prism,
            source = { silent.next(1f / 60f) },
            beforeDraw = { honeycomb.jumpTo(form) },
        ) { _, _ -> }
        return honeycomb
    }

    /** The width on the canvas of tile [num], from its corners. */
    private fun widthOf(honeycomb: Honeycomb, num: Int): Float {
        val xs = (0 until Honeycomb.SIDES).map { honeycomb.cornerScreenX(num, it) }
        return xs.max() - xs.min()
    }

    /** The baseline with the lowest eight bands raised, so the centre tiles are loud. */
    private fun loudCentre(base: SpectrumFrame): SpectrumFrame {
        val bands = base.bands.copyOf().also { for (b in 0 until 8) it[b] = 0.9f }
        val peaks = base.peaks.copyOf().also { for (b in 0 until 8) it[b] = 0.95f }
        val relative = base.bandsRel.copyOf().also { for (b in 0 until 8) it[b] = 0.9f }
        return SpectrumFrame(
            ptsMicros = base.ptsMicros, bands = bands, peaks = peaks, scope = base.scope,
            level = base.level, bass = base.bass, mid = base.mid, treble = base.treble,
            beat = base.beat, pulse = base.pulse, bandsRel = relative,
            levelRel = base.levelRel, bassRel = base.bassRel, midRel = base.midRel,
            trebleRel = base.trebleRel, kick = base.kick, snare = base.snare, hat = base.hat,
            onsetStrength = base.onsetStrength, novelty = base.novelty, kickPulse = base.kickPulse,
            snarePulse = base.snarePulse, hatPulse = base.hatPulse, energy = base.energy,
            density = base.density, mood = base.mood, loudShort = base.loudShort,
            loudLong = base.loudLong, trend = base.trend, generation = base.generation,
            hasTimestamp = true, events = base.events,
        )
    }

    private fun colourDistance(a: Color, b: Color): Float = (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)) / 3f

    /** Draws [honeycomb] for [seconds] at [rate] frames a second, the way a window does. */
    private fun run(
        honeycomb: Honeycomb,
        song: RenderHarness.Song,
        rate: Int,
        seconds: Float,
        width: Int = 320,
        height: Int = 200,
        density: Float = 1f,
        onFrame: () -> Unit = {},
    ) {
        val player = RenderHarness.player(song, seconds + 4f)
        val bitmap = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())
        val delta = 1f / rate
        var elapsed = 0f
        var music = 0f
        honeycomb.reset()
        repeat((seconds * rate).roundToInt().coerceAtLeast(1)) {
            elapsed += delta
            val frame = player.next(delta)
            music += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, VizPalette.Prism, music, player.future)
            scope.draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), size) {
                with(honeycomb) {
                    draw(state)
                    drawFront(state)
                }
            }
            onFrame()
        }
    }

    /** The mean frame-to-frame change over the last second of three. */
    private fun meanChange(song: RenderHarness.Song): Float {
        var previous: IntArray? = null
        var change = 0f
        var counted = 0
        RenderHarness.forEachFrame(Honeycomb(), 320, 200, 180, VizPalette.Prism, song) { bitmap, step ->
            val pixels = IntArray(320 * 200)
            bitmap.readPixels(pixels)
            if (step >= 120) {
                previous?.let {
                    change += difference(it, pixels)
                    counted++
                }
            }
            previous = pixels
        }
        return change / counted.coerceAtLeast(1)
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
