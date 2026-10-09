package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Evolution
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Honeycomb, a port of "Soundcloud Visualizer" by Michael Bromley.
 *
 * - Title: Soundcloud Visualizer
 * - Author: Michael Bromley
 * - URL: https://www.michaelbromley.co.uk/experiments/soundcloud-vis/
 * - Repository: https://github.com/michaelbromley/soundcloud-visualizer
 * - Licence as found: MIT, `LICENSE.txt`: "Copyright (c) 2013 Michael Bromley"
 * - Year: 2013
 * - The README names the artwork of Muse's album "The Resistance" as its inspiration.
 *
 * Deviations from the original:
 * - The page's analyser (FFT 256, smoothing 0.8, -100 to -30 dB) is rebuilt from the player's spectrum
 *   by [WebAudioAnalyser], on a 44.1 kHz layout. On two test songs its `volume` averaged within 2
 *   percent of Chrome's.
 * - The 20 ms reads and the 100 ms background run on heard seconds, not on timers, so the picture
 *   stands still while the music is paused or silent.
 * - The per-frame falls of the tiles and the white outlines, and the star flight, are scaled by frame
 *   time, so a 120 Hz screen moves as fast as a 60 Hz one. They stop in silence as well.
 * - Sizes are in dp where the page used CSS pixels, and the lines are drawn sharp at the screen's
 *   resolution, where the page stretched a canvas of CSS pixels.
 * - A seeded generator places the stars instead of `Math.random()`, so a render repeats.
 * - A reduced-motion setting slows the spin and the star flight.
 * - The corners follow the page's `mentalFactor` at `FACTOR_SPEED` at most. The page jumps from 2 to
 *   -20 in one read where the tangent passes its pole, which throws every tile off the screen for a
 *   frame. On a drum loop those jumps brought the picture to six flashes in its busiest second,
 *   against a limit of three.
 * - The flash guard's light share multiplies every colour. It is 1 unless the picture would flash.
 * - Each tile reads the live spectrum at its ring and its angle, the centre the bass and the outer
 *   ring the treble, where the page gave most tiles one bin of the lowest quarter.
 * - The tiles and the background take their colour from the chosen palette, walked by the genes,
 *   where the page had a fixed red to pink ramp.
 * - The hive turns one notch on each beat and a form change flips the direction, where the page
 *   sheared the corners every 20 ms.
 * - The hive starts at four rings and shows up to six. A birth or a surge adds one, a breakdown takes
 *   it back to two, and a kick opens the loud cells, none of which the page did.
 * - The page's SoundCloud player, track panel and controls are left out.
 *
 * A honeycomb of 127 hexagons lights up like a meter: the lowest frequency sits in the middle and the
 * rings climb the spectrum. The spectrum spreads over the rings the hive shows, so a small hive still
 * shows all of it. Each tile takes its colour and opacity from its level, from the palette,
 * climbing it with the level, and falls back slowly from its peak. A loud tile gets a
 * white edge that fades over three seconds. The hive turns a notch on each beat, and on a loud passage
 * the tiles are pulled in and thrown through the middle. The hive grows a ring on a birth and falls back
 * to two on a breakdown, and a kick opens its loud cells. Grey star streaks rush outward
 * behind it, and the edges glow in the palette's colour with the volume.
 *
 * The hive has four forms. Disc is the flat page. Dome lifts the middle toward the viewer on a sphere
 * and tilts the camera. Tunnel is a cone where each ring shows the spectrum one beat further back than
 * the ring inside it, the middle deepest. Shatter flies the tiles apart on a drop, and they return
 * within a cycle. The evolution pacer takes turns through Disc, Dome and Tunnel over two cycles each,
 * as weights, so a morph never jumps. The form on screen is published through [forms].
 */
internal class Honeycomb : Visualization {

    override val name: String = "Honeycomb"
    override val bucket: VizEnergy = VizEnergy.High
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Each tile reads the live spectrum at its place: its colour, its opacity and how far it is pushed.
            VizDrive(VizDriver.Bands, VizProperty.Colour, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Bands, VizProperty.Brightness, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(ANALYSER_SECONDS)),
            // The page's volume, the sum of its lowest 80 bins, pushes the corners and sets the star
            // flight, the streak length and the background's colour.
            VizDrive(VizDriver.Level, VizProperty.Shape, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Level, VizProperty.Size, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Level, VizProperty.Colour, response = VizResponse.envelope(ANALYSER_SECONDS)),
            // A drop shatters the hive, and it reassembles within a cycle.
            VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(3f)),
            // A section starts a morph to another form, over two cycles.
            VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(6f)),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 256`, and the Web Audio defaults for the rest. */
    internal val analyser = WebAudioAnalyser(fftSize = 256, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f)

    /** `audioSource.volume`: the sum of bins 0 to 79, taken at each read. */
    internal var volume: Int = 0
        private set

    /**
     * The page's `mentalFactor` as the corners use it this frame: followed at `FACTOR_SPEED` at most,
     * so a crossing of the tangent's pole moves the tiles rather than blanking them.
     */
    internal var factor: Double = 0.0
        private set

    /** How many 20 ms steps have run since the last reset. */
    internal var ticks: Long = 0L
        private set

    private val step = DisplayStep()
    private var random = Random(SEED)

    // The shared pieces every world drawing reads. The genes are `recipe` because Visualization has a `genes`.
    private val gestures = Gestures()
    private val evolution = Evolution()
    private val recipe = Genes(SEED)

    // The 3D lens and the density of this frame. The hive is laid out in dp and projected in canvas pixels.
    private val scene = Scene3D()
    private var pixelDensity = 1f

    // The form the pacer is morphing toward.
    private var formTo = DISC

    // How far each blended form has come, 0 to 1. Every one moves toward its target, so a morph never jumps.
    private val blend = FloatArray(BLENDED).also { it[DISC] = 1f }

    // The eased, normalized weights of this frame. They add up to 1.
    private val formWeight = FloatArray(BLENDED)

    // Picks the next form, apart from the star generator.
    private var formRandom = Random(SEED + 1)

    // How far the hive is shattered, 1 at a drop and gone within a cycle.
    private var shatter = 0f
    internal val shatterValue: Float get() = shatter

    // The last few seconds of the spectrum, in heard seconds, for the Tunnel's rings.
    private val history = History(HISTORY_ROWS)
    private var heardSeconds = 0.0

    init {
        updateWeights()
    }

    // The hive turns one notch, an outer ring tile, on each beat, and a form change flips the direction.
    private var turnTarget = 0f
    private var turn = 0f
    internal val turnValue: Float get() = turn
    internal var turnSign = 1f
        private set
    private var lastQuarter = -1

    /** How many rings the hive is growing toward, 2 to 6. */
    internal var ringsTarget = 4
        private set

    // How many rings the hive shows now, eased over about a cycle.
    private var ringsShown = 4f
    internal val ringsShownValue: Float get() = ringsShown

    // How far the loud cells are open after a kick, 1 at the hit.
    private var open = 0f
    internal val openValue: Float get() = open

    /** Heard seconds not yet spent on 20 ms steps. */
    private var owed = 0.0

    // The page's canvas, in dp, and the tile size it sets. Tiles and stars are rebuilt when it changes.
    private var width = -1.0
    private var height = -1.0

    /** `tileSize`: the hexagon's radius, centre to corner, in dp. */
    internal var tileSize: Double = 0.0
        private set

    // Six corners a tile, in dp from the middle, turned in place every 20 ms.
    internal val vertexX = DoubleArray(TILES * SIDES)
    internal val vertexY = DoubleArray(TILES * SIDES)
    private val high = DoubleArray(TILES)
    private val highlight = DoubleArray(TILES)
    private val ring = IntArray(TILES)

    // Where each tile sits round its ring, 0 to 1 from the positive x axis, and the same folded to 0 to 1 and back.
    // In the Tunnel each ring is one past spectrum: the bass at the right, the treble at the left, mirrored top and bottom.
    private val tileAngle = FloatArray(TILES)
    private val spoke = FloatArray(TILES)

    // A fixed random number of each tile, to scatter it in the Shatter form. Every resize gives the same values.
    private val tileRandom = FloatArray(TILES)

    // The hive's size in dp, set when the tiles are placed: the farthest corner from the middle, and the ring spacing.
    private var outerRadius = 1f
    private var domeRim = 0f
    private var ringStep = 1f

    /** This frame's `val` of each tile. */
    private val shown = DoubleArray(TILES)

    /** This frame's white edge of each tile, as its alpha, or 0 for none. */
    private val edge = DoubleArray(TILES)

    private var starCount = 0
    private var starX = DoubleArray(0)
    private var starY = DoubleArray(0)
    private var starAngle = DoubleArray(0)
    private var starSize = DoubleArray(0)
    private var starHigh = DoubleArray(0)

    // What each star draws this frame, taken before it moves, as the page drew it.
    private var lineFromX = FloatArray(0)
    private var lineFromY = FloatArray(0)
    private var lineToX = FloatArray(0)
    private var lineToY = FloatArray(0)
    private var lineWidth = FloatArray(0)
    private var lineGrey = FloatArray(0)

    /** The volume the background was last painted with, every 100 ms. */
    private var backgroundVolume = 0
    private var background: Brush? = null

    // What the cached background was built for.
    private var brushVolume = -1
    private var brushWidth = 0f
    private var brushHeight = 0f
    private var brushDensity = 0f
    private var brushLight = -1f
    private var brushPalette: VizPalette? = null
    private var brushWalk = Float.NaN

    private val path = Path()

    // The corners of the tile that tracePath last traced: in dp from the middle after the push, the turn and the
    // shatter, and then in canvas pixels after the projection.
    private val pageX = FloatArray(SIDES)
    private val pageY = FloatArray(SIDES)
    private val cornerX = FloatArray(SIDES)
    private val cornerY = FloatArray(SIDES)
    private val hole = Path()

    /** The canvas's 1 px line, with its default miter limit of 10. It is `density` pixels wide in pixel space. */
    private var hairline = Stroke(width = 1f, miter = 10f)
    private var hairlineDensity = 1f

    /** The form on screen. */
    override val forms: FormReadout
        get() = FormReadout(if (shatter > 0.5f) FORMS[SHATTER] else FORMS[strongestForm()], evolution.morphs, evolution.births)

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state, size, density)
        val light = state.lift.coerceIn(0f, 1f)
        drawBackground(light, state.palette, recipe.walk)
        withPage { drawStars(light) }
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state, size, density)
        val light = state.lift.coerceIn(0f, 1f)
        // The tiles are projected to canvas pixels, so they are drawn with no transform.
        drawTiles(light, state.palette, recipe.walk)
        drawEdges(light)
    }

    /** The page's translated context: dp from the middle of the screen. The stars use it. */
    private inline fun DrawScope.withPage(block: DrawScope.() -> Unit) {
        val middleX = size.width / 2f
        val middleY = size.height / 2f
        val dp = density
        withTransform({
            translate(middleX, middleY)
            scale(dp, dp, Offset.Zero)
        }, block)
    }

    private fun advance(state: VizRenderState, canvas: Size, density: Float) {
        val dt = step.of(state) ?: return
        gestures.update(state)
        evolution.update(state, gestures)
        recipe.advance(gestures, if (state.frame.held) 0f else dt, evolution.morph)
        if (evolution.morph) onMorph()
        val frame = state.frame
        val heard = dt.toDouble() * frame.audible
        val heardNow = heard.toFloat()
        // The evolution pacer picks the next form. The weights turn from where they are, so a morph never jumps.
        if (evolution.morph) formTo = (formTo + 1 + formRandom.nextInt(BLENDED - 1)) % BLENDED
        val rate = heardNow / (MORPH_CYCLES * gestures.cycleSeconds.coerceAtLeast(0.5f))
        for (f in blend.indices) {
            val target = if (f == formTo) 1f else 0f
            blend[f] += (target - blend[f]).coerceIn(-rate, rate)
        }
        updateWeights()
        // A drop shatters the hive, and the pieces fly back within a cycle of heard time.
        if (evolution.bloom) shatter = 1f
        shatter = max(0f, shatter - heardNow / gestures.cycleSeconds.coerceAtLeast(0.5f))
        val quarter = floor(gestures.cyclePhase * 4f).toInt()
        if (gestures.pulseUsable && lastQuarter >= 0 && quarter != lastQuarter && frame.audible > 0.5f) {
            turnTarget += turnSign * NOTCH * state.motionScale
        }
        lastQuarter = quarter
        // Without a beat the hive turns slowly with the drive, as the page turned.
        if (!gestures.pulseUsable) turnTarget += turnSign * state.paced(TURN_RATE) * dt * state.motionScale
        if (evolution.birth || gestures.surge) grow()
        if (evolution.collapse) collapse()
        // The shown rings follow the target over about a cycle of heard time.
        ringsShown += (ringsTarget - ringsShown) * (1f - exp(-heard.toFloat() / gestures.cycleSeconds.coerceAtLeast(0.5f)))
        // A kick opens the loud cells, and they close within a quarter second.
        open = max(open * exp(-dt / 0.25f), gestures.kick)
        // `window.innerWidth` and `innerHeight` are whole CSS pixels.
        val pageWidth = floor(canvas.width / density + 0.5)
        val pageHeight = floor(canvas.height / density + 0.5)
        if (pageWidth != width || pageHeight != height) resize(pageWidth, pageHeight)
        pixelDensity = density
        if (hairlineDensity != density) {
            hairline = Stroke(width = density, miter = 10f)
            hairlineDensity = density
        }
        scene.lens(canvas, FOV, NEAR, FAR)
        // The eye sits where a flat hive at z = 0 projects one to one: a page dp is a canvas pixel times density.
        val distance = (canvas.height / 2f) / tan(FOV / 2f * PI.toFloat() / 180f)
        val tilt = formWeight[DOME] * TILT
        scene.camera(0f, distance * sin(tilt), distance * cos(tilt), 0f, 0f, 0f)
        turn += (turnTarget - turn) * (1f - exp(-heard.toFloat() / NOTCH_SECONDS))
        owed += heard
        var steps = 0
        while (owed >= TICK_SECONDS - 1e-9 && steps < MAX_TICKS) {
            owed -= TICK_SECONDS
            steps++
            tick(state)
        }
        if (steps == MAX_TICKS) owed = min(owed, TICK_SECONDS)
        val reach = FACTOR_SPEED * heard
        factor += (mentalFactor(volume) - factor).coerceIn(-reach, reach)
        // The page's per-frame steps, for a page drawing 60 frames a second.
        val frames = heard * 60.0
        moveStars(frames, state.motionScale.toDouble())
        heardSeconds += heard
        history.push(frame.bandsRel, heardSeconds)
        updateTiles(frames, frame, formWeight[TUNNEL])
    }

    /** What the page's two 20 ms timers and its 100 ms timer do. */
    private fun tick(state: VizRenderState) {
        ticks++
        // SoundCloudAudioSource: "get the volume from the first 80 bins, else it gets too loud with treble".
        analyser.read(state.frame)
        var total = 0
        for (bin in 0 until VOLUME_BINS) total += analyser.frequencyBytes[bin]
        volume = total
        // drawBg, every fifth step.
        if (ticks % 5L == 0L) backgroundVolume = volume
    }

    /** The page's resizeCanvas: the background repainted, then new tiles and new stars. */
    private fun resize(pageWidth: Double, pageHeight: Double) {
        width = pageWidth
        height = pageHeight
        backgroundVolume = volume
        tileSize = max(pageWidth, pageHeight) / 25.0
        makeTiles()
        makeStars()
    }

    /** makePolygonArray: the centre tile, then rings 1 to 6, in the page's order. */
    private fun makeTiles() {
        var num = 0
        fun add(x: Int, y: Int, layer: Int) = placeTile(num++, x, y, layer)
        add(0, 0, 0)
        for (layer in 1 until 7) {
            add(0, layer, layer)
            add(0, -layer, layer)
            for (x in 1 until layer) {
                add(x, -layer, layer)
                add(-x, layer, layer)
                add(x, layer - x, layer)
                add(-x, -layer + x, layer)
            }
            for (y in -layer..0) {
                add(layer, y, layer)
                add(-layer, -y, layer)
            }
        }
        ringStep = jsRound(cos(PI / 6) * tileSize * 2).toFloat()
        var farthest = 0.0
        for (corner in 0 until TILES * SIDES) {
            farthest = max(farthest, sqrt(vertexX[corner] * vertexX[corner] + vertexY[corner] * vertexY[corner]))
        }
        outerRadius = farthest.toFloat()
        val sphere = DOME_R * outerRadius
        domeRim = sqrt(sphere * sphere - outerRadius * outerRadius)
        val dice = Random(SEED + 2)
        for (tile in 0 until TILES) tileRandom[tile] = dice.nextFloat()
    }

    /** The Polygon constructor: a centre on the 60 degree grid, and six corners from 90 degrees round. */
    private fun placeTile(num: Int, x: Int, y: Int, layer: Int) {
        val step = jsRound(cos(PI / 6) * tileSize * 2)
        val centreY = jsRound(step * sin(PI / 3) * -y)
        val centreX = jsRound(x * step + y * step / 2)
        for (corner in 0 until SIDES) {
            val i = corner + 1
            vertexX[num * SIDES + corner] = centreX + tileSize * cos(i * 2 * PI / SIDES + PI / 6)
            vertexY[num * SIDES + corner] = centreY + tileSize * sin(i * 2 * PI / SIDES + PI / 6)
        }
        ring[num] = layer
        // Where the tile sits round its ring, 0 to 1 from the positive x axis; the centre reads 0.
        val angle = if (layer == 0) 0f else ((atan2(centreY, centreX) / TAU.toDouble()).toFloat() + 1f) % 1f
        tileAngle[num] = angle
        spoke[num] = if (angle <= 0.5f) angle * 2f else (1f - angle) * 2f
        high[num] = 0.0
        highlight[num] = 0.0
        shown[num] = 0.0
        edge[num] = 0.0
    }

    /** makeStarArray: one star per 15 dp of width, anywhere on the canvas. */
    private fun makeStars() {
        val limit = width / 15.0
        var count = 0
        while (count < limit) count++
        if (count != starCount) {
            starCount = count
            starX = DoubleArray(count)
            starY = DoubleArray(count)
            starAngle = DoubleArray(count)
            starSize = DoubleArray(count)
            starHigh = DoubleArray(count)
            lineFromX = FloatArray(count)
            lineFromY = FloatArray(count)
            lineToX = FloatArray(count)
            lineToY = FloatArray(count)
            lineWidth = FloatArray(count)
            lineGrey = FloatArray(count)
        }
        for (star in 0 until count) {
            val x = (random.nextDouble() - 0.5) * width
            val y = (random.nextDouble() - 0.5) * height
            starX[star] = x
            starY[star] = y
            starSize[star] = (random.nextDouble() + 0.1) * 3
            starAngle[star] = atan(abs(y) / abs(x))
            starHigh[star] = 0.0
            lineWidth[star] = 0f
        }
    }

    /** Star.drawStar: each star's streak as it stands, then its move outward, [frames] page frames long. */
    private fun moveStars(frames: Double, motionScale: Double) {
        val loud = volume.toDouble().pow(2)
        val limitY = height / 2 + 500
        val limitX = width / 2 + 500
        for (star in 0 until starCount) {
            val x = starX[star]
            val y = starY[star]
            val distanceFromCentre = sqrt(x.pow(2) + y.pow(2))
            lineGrey[star] = (200 + min(jsRound(starHigh[star] * 5), 55.0)).toFloat()
            lineWidth[star] = (0.5 + distanceFromCentre / 2000 * max(starSize[star] / 2, 1.0)).toFloat()
            val lengthFactor = 1 + min(distanceFromCentre.pow(2) / 30000 * loud / 6000000, distanceFromCentre)
            var toX = cos(starAngle[star]) * -lengthFactor
            var toY = sin(starAngle[star]) * -lengthFactor
            toX *= if (x > 0) 1.0 else -1.0
            toY *= if (y > 0) 1.0 else -1.0
            lineFromX[star] = x.toFloat()
            lineFromY[star] = y.toFloat()
            lineToX[star] = (x + toX).toFloat()
            lineToY[star] = (y + toY).toFloat()
            // "starfield movement coming towards the camera"
            val speed = lengthFactor / 20 * starSize[star]
            starHigh[star] -= max(starHigh[star] - 0.0001, 0.0)
            if (speed > starHigh[star]) starHigh[star] = speed
            val dX = cos(starAngle[star]) * starHigh[star] * frames * motionScale
            val dY = sin(starAngle[star]) * starHigh[star] * frames * motionScale
            starX[star] += if (x > 0) dX else -dX
            starY[star] += if (y > 0) dY else -dY
            if (starY[star] > limitY || starY[star] < -limitY || starX[star] > limitX || starX[star] < -limitX) {
                // "it has gone off the edge so respawn it somewhere near the middle"
                starX[star] = (random.nextDouble() - 0.5) * width / 3
                starY[star] = (random.nextDouble() - 0.5) * height / 3
                starAngle[star] = atan(abs(starY[star]) / abs(starX[star]))
            }
        }
    }

    /** Polygon.drawPolygon's reading and peak hold for every tile, then the highlight pass's fade. */
    private fun updateTiles(frames: Double, frame: SpectrumFrame, tunnelShare: Float) {
        for (num in 0 until TILES) {
            // Each tile reads the live spectrum at its place, on the page's 0 to 255 scale.
            val live = frame.bandsRel.sampleAt(positionOf(num))
            // In the Tunnel ring r shows the spectrum r beats back. Ring 0 reads the newest row, the live frame.
            val past = if (tunnelShare > 0f) history.sample(history.row(ring[num] * gestures.beatSeconds), spoke[num]) else live
            val rel = (live + (past - live) * tunnelShare).toDouble()
            var value = rel * rel * 255
            if (num > 42) value *= 1.1
            if (value > high[num]) {
                high[num] = value
            } else {
                high[num] -= (if (num > 42) 1.5 else 2.0) * frames
                value = high[num]
            }
            shown[num] = value
            if (value > 120) highlight[num] = 100.0
        }
        for (num in 0 until TILES) {
            if (highlight[num] > 0) {
                edge[num] = highlight[num] / 100
                highlight[num] -= 0.5 * frames
            } else {
                edge[num] = 0.0
            }
        }
    }

    /** drawBg: black under a radial gradient from clear in the middle to a translucent red or pink. */
    private fun DrawScope.drawBackground(light: Float, palette: VizPalette, walk: Float) {
        drawRect(Color.Black)
        // The walk is rounded to 0.02, so the brush is not rebuilt on every frame.
        val rounded = (walk * 50f).roundToInt() / 50f
        // The light follows the energy, so it is rounded to 0.02 too, or the brush rebuilds on every frame.
        val lightKey = (light * 50f).roundToInt() / 50f
        if (backgroundVolume != brushVolume || size.width != brushWidth || size.height != brushHeight ||
            density != brushDensity || lightKey != brushLight || palette !== brushPalette || rounded != brushWalk
        ) {
            brushVolume = backgroundVolume
            brushWidth = size.width
            brushHeight = size.height
            brushDensity = density
            brushLight = lightKey
            brushPalette = palette
            brushWalk = rounded
            background = backgroundBrush(lightKey, palette, rounded)
        }
        background?.let { drawRect(it) }
    }

    private fun DrawScope.backgroundBrush(light: Float, palette: VizPalette, walk: Float): Brush? {
        val value = backgroundVolume / 1000.0
        val tint = palette.cycled(0.1f + walk, 0.8f, 1f)
        val inner = value
        val outer = width - min(value.pow(2.7), width - 20)
        // "If x0 = x1 and y0 = y1 and r0 = r1, then the radial gradient must paint nothing."
        if (outer == inner) return null
        val reach = max(inner, outer)
        val stops = ArrayList<Pair<Float, Color>>(GRADIENT_SAMPLES + 3)
        fun addStop(distance: Double) {
            // The canvas interpolates colour and alpha unpremultiplied, from clear black at 0 to the
            // edge colour at alpha 0.6 at 0.8, so over black the light rises with the square of the way.
            val along = ((distance - inner) / (outer - inner)).coerceIn(0.0, 1.0)
            val share = min(along / 0.8, 1.0)
            val lit = (0.6 * share * share).toFloat() * light
            stops += (distance / reach).toFloat() to Color(tint.red * lit, tint.green * lit, tint.blue * lit)
        }
        addStop(0.0)
        for (sample in 0..GRADIENT_SAMPLES) addStop(inner + (outer - inner) * 0.8 * sample / GRADIENT_SAMPLES)
        addStop(outer)
        stops.sortBy { it.first }
        return Brush.radialGradient(
            *stops.toTypedArray(),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = (reach * density).toFloat(),
        )
    }

    private fun DrawScope.drawStars(light: Float) {
        for (star in 0 until starCount) {
            val grey = lineGrey[star] / 255f * light
            drawLine(
                Color(grey, grey, grey),
                Offset(lineFromX[star], lineFromY[star]),
                Offset(lineToX[star], lineToY[star]),
                strokeWidth = lineWidth[star],
            )
        }
    }

    private fun DrawScope.drawTiles(light: Float, palette: VizPalette, walk: Float) {
        for (num in 0 until TILES) {
            val value = shown[num]
            val share = ringShare(num)
            if (share <= 0f) continue
            if (!tracePath(num)) continue
            val fill = fillOf(value, light, palette, walk, ring[num])
            drawPath(path, fill.copy(alpha = fill.alpha * share))
            if (value > 20) drawPath(path, Color(DARK * light, DARK * light, DARK * light, 0.5f * share), style = hairline)
            // A kick opens a loud cell: an inner hexagon of pale colour pours light out of the tile.
            if (value > OPEN_LEVEL && open > 0.02f) {
                var cx = 0f
                var cy = 0f
                for (corner in 0 until SIDES) {
                    cx += cornerX[corner]
                    cy += cornerY[corner]
                }
                cx /= SIDES
                cy /= SIDES
                val inward = 1f - 0.55f * open
                hole.reset()
                for (corner in 0 until SIDES) {
                    val hx = cx + (cornerX[corner] - cx) * inward
                    val hy = cy + (cornerY[corner] - cy) * inward
                    if (corner == 0) hole.moveTo(hx, hy) else hole.lineTo(hx, hy)
                }
                hole.close()
                val level = (value / 255.0).coerceIn(0.0, 1.0).toFloat()
                drawPath(
                    hole,
                    palette.cycled(level * 0.7f + ring[num] * RING_HUE_STEP + walk + 0.5f, 0.3f, light, alpha = (open * share).coerceIn(0f, 1f)),
                )
            }
        }
    }

    /** drawHighlight: a white edge on every tile whose highlight has not faded yet. */
    private fun DrawScope.drawEdges(light: Float) {
        for (num in 0 until TILES) {
            val alpha = edge[num]
            if (alpha <= 0) continue
            val share = ringShare(num)
            if (share <= 0f) continue
            if (!tracePath(num)) continue
            drawPath(path, Color(light, light, light, (alpha.toFloat() * share).coerceIn(0f, 1f)), style = hairline)
        }
    }

    /**
     * The tile's outline with every corner pushed along its own line from the middle
     * (calculateOffset), turned with the hive, scattered by the Shatter form and projected to canvas
     * pixels. False when the push is not a number, a tile whose peak has fallen below zero, where the
     * canvas ignores every point and draws nothing, or when a corner cannot be projected.
     */
    private fun tracePath(num: Int): Boolean {
        path.reset()
        val c = cos(turn)
        val s = sin(turn)
        for (corner in 0 until SIDES) {
            val x = vertexX[num * SIDES + corner]
            val y = vertexY[num * SIDES + corner]
            val angle = atan(y / x)
            val distance = sqrt(x.pow(2) + y.pow(2))
            val push = offsetFactor(distance, volume, high[num], factor)
            if (push.isNaN()) return false
            var offsetX = cos(angle) * push
            var offsetY = sin(angle) * push
            if (x < 0) {
                offsetX = -offsetX
                offsetY = -offsetY
            }
            val px = (x + offsetX).toFloat()
            val py = (y + offsetY).toFloat()
            pageX[corner] = px * c - py * s
            pageY[corner] = px * s + py * c
        }
        // Shatter: each tile spins about its own centre and flies outward along the line from the middle.
        val e = shatter * shatter
        if (e > 0f) {
            var cx = 0f
            var cy = 0f
            for (corner in 0 until SIDES) {
                cx += pageX[corner]
                cy += pageY[corner]
            }
            cx /= SIDES
            cy /= SIDES
            val len = sqrt(cx * cx + cy * cy)
            val ux = if (len < 1e-3f) 0f else cx / len
            val uy = if (len < 1e-3f) 0f else cy / len
            val spin = e * (2f * tileRandom[num] - 1f) * SHATTER_SPIN
            val reach = e * SHATTER_REACH * len * (0.5f + 0.5f * tileRandom[num])
            val sinSpin = sin(spin)
            val cosSpin = cos(spin)
            for (corner in 0 until SIDES) {
                val dx = pageX[corner] - cx
                val dy = pageY[corner] - cy
                pageX[corner] = cx + dx * cosSpin - dy * sinSpin + ux * reach
                pageY[corner] = cy + dx * sinSpin + dy * cosSpin + uy * reach
            }
        }
        // Depth, then the projection. World y points up and page y points down, hence the minus.
        val sphere = DOME_R * outerRadius
        for (corner in 0 until SIDES) {
            val x = pageX[corner]
            val y = pageY[corner]
            val r = sqrt(x * x + y * y)
            // Dome: the rim stays at z = 0 and the middle rises toward the eye on a sphere of radius DOME_R times the hive.
            val dome = sqrt(max(sphere * sphere - r * r, 0f)) - domeRim
            // Tunnel: a cone that drops one TUNNEL_DEPTH for each ring inward, the middle deepest, the rim at z = 0.
            val tunnel = -max(outerRadius - r, 0f) / ringStep * TUNNEL_DEPTH
            val z = formWeight[DOME] * dome + formWeight[TUNNEL] * tunnel
            if (!scene.project(x * pixelDensity, -y * pixelDensity, z * pixelDensity)) return false
            cornerX[corner] = scene.screenX
            cornerY[corner] = scene.screenY
            if (corner == 0) path.moveTo(cornerX[corner], cornerY[corner]) else path.lineTo(cornerX[corner], cornerY[corner])
        }
        path.close()
        return true
    }

    /** How much of a tile shows: 1 inside the rings the hive has grown to, 0 beyond, eased between. */
    private fun ringShare(num: Int): Float {
        val t = (ringsShown - (ring[num] - 1f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The ring a tile sits in, 0 for the centre and 6 for the outer ring. */
    internal fun ringOf(num: Int): Int = ring[num]

    /** Where a tile reads the spectrum this frame: the bass at the centre, the treble on the outermost shown ring. */
    internal fun positionOf(num: Int): Float = (ring[num] + tileAngle[num]) / (ringsShown + 1f)

    /** What a tile shows this frame, on the page's 0 to 255 scale. */
    internal fun tileValue(num: Int): Double = shown[num]

    /** For tests: the tile of [ring] nearest [angle], 0 to 1 from the positive x axis. */
    internal fun tileNearest(ring: Int, angle: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (num in 0 until TILES) {
            if (this.ring[num] != ring) continue
            val apart = abs(tileAngle[num] - angle)
            val distance = min(apart, 1f - apart)
            if (distance < bestDistance) {
                bestDistance = distance
                best = num
            }
        }
        return best
    }

    /** For tests: the x of corner [corner] of tile [num] in dp from the middle, after the last frame. */
    internal fun cornerPageX(num: Int, corner: Int): Float = if (tracePath(num)) pageX[corner] else Float.NaN

    /** For tests: the y of corner [corner] of tile [num] in dp from the middle, after the last frame. */
    internal fun cornerPageY(num: Int, corner: Int): Float = if (tracePath(num)) pageY[corner] else Float.NaN

    /** For tests: the x of corner [corner] of tile [num] on the canvas in pixels, after the last frame. */
    internal fun cornerScreenX(num: Int, corner: Int): Float = if (tracePath(num)) cornerX[corner] else Float.NaN

    /** For tests: the y of corner [corner] of tile [num] on the canvas in pixels, after the last frame. */
    internal fun cornerScreenY(num: Int, corner: Int): Float = if (tracePath(num)) cornerY[corner] else Float.NaN

    /** For tests: puts the drawing straight into [form], with no morph. */
    internal fun jumpTo(form: String) {
        if (form == FORMS[SHATTER]) {
            shatter = 1f
            return
        }
        val target = FORMS.indexOf(form)
        require(target in 0 until BLENDED) { "no form named $form" }
        formTo = target
        blend.fill(0f)
        blend[formTo] = 1f
        updateWeights()
    }

    /** Eases each blend into a weight and divides by the sum, so the weights add up to 1. */
    private fun updateWeights() {
        var sum = 0f
        for (f in blend.indices) {
            val b = blend[f]
            formWeight[f] = b * b * (3f - 2f * b)
            sum += formWeight[f]
        }
        if (sum <= 0f) {
            formWeight.fill(0f)
            formWeight[formTo] = 1f
        } else {
            for (f in formWeight.indices) formWeight[f] /= sum
        }
    }

    /** The index of the blended form with the largest weight. */
    private fun strongestForm(): Int {
        var best = 0
        for (f in formWeight.indices) if (formWeight[f] > formWeight[best]) best = f
        return best
    }

    /** A birth or a surge adds a ring to the hive, up to six. */
    internal fun grow() {
        ringsTarget = min(6, ringsTarget + 1)
    }

    /** A breakdown takes the hive back to the middle. */
    internal fun collapse() {
        ringsTarget = 2
    }

    /** A form change turns the hive the other way. */
    internal fun onMorph() {
        turnSign = -turnSign
    }

    override fun reset() {
        step.reset()
        analyser.reset()
        random = Random(SEED)
        gestures.reset()
        evolution.reset()
        recipe.restart()
        turnTarget = 0f
        turn = 0f
        turnSign = 1f
        lastQuarter = -1
        formTo = DISC
        blend.fill(0f)
        blend[DISC] = 1f
        updateWeights()
        formRandom = Random(SEED + 1)
        shatter = 0f
        history.clear()
        heardSeconds = 0.0
        ringsTarget = 4
        ringsShown = 4f
        open = 0f
        volume = 0
        factor = 0.0
        ticks = 0L
        owed = 0.0
        width = -1.0
        height = -1.0
        backgroundVolume = 0
        brushVolume = -1
        brushPalette = null
        brushWalk = Float.NaN
        background = null
    }

    internal companion object {
        const val TILES = 127
        const val SIDES = 6

        /**
         * How fast the corners follow the page's factor, in units a heard second. The widest swing,
         * from 2 to -20, then takes three quarters of a second. *Judgement.*
         */
        const val FACTOR_SPEED = 30.0

        /** The bins `volume` adds up: 0 to 79 of 128. */
        const val VOLUME_BINS = 80

        /** The page's two fast timers, `setInterval(..., 20)`. */
        const val TICK_SECONDS = 0.02

        /** At most this many 20 ms steps in one frame, so a stalled frame does not spin. */
        const val MAX_TICKS = 10

        /** How many samples draw the background's rise from clear to the edge colour. */
        const val GRADIENT_SAMPLES = 8

        const val DARK = 20f / 255f

        /** The value above which a kick opens a cell, the page's highlight threshold. */
        const val OPEN_LEVEL = 120.0

        /** The page writes e as 2.7182 in the tile's opacity. */
        const val E = 2.7182

        const val SEED = 20_131_231L

        /** The forms of the hive, by name. Disc, Dome and Tunnel blend; Shatter is an overlay. */
        val FORMS = listOf("Disc", "Dome", "Tunnel", "Shatter")

        /** The flat page. */
        const val DISC = 0

        /** The middle lifted toward the viewer on a sphere, under a tilted camera. */
        const val DOME = 1

        /** A cone where each ring shows the spectrum one beat further back. */
        const val TUNNEL = 2

        /** The tiles flown apart by a drop. It is no blended form, so it has no weight. */
        const val SHATTER = 3

        /** How many forms blend: the first three of [FORMS]. */
        const val BLENDED = 3

        /** Cycles a morph takes to settle. *Judgement.* */
        const val MORPH_CYCLES = 2f

        /** The lens's vertical field of view, in degrees. Kite3D's angle is vertical. */
        const val FOV = 40f

        /** The near plane, in canvas pixels. */
        const val NEAR = 1f

        /** The far plane, in canvas pixels. */
        const val FAR = 20_000f

        /** How far the camera tilts up in the full Dome, in radians: 35 degrees. */
        const val TILT = 35f * PI.toFloat() / 180f

        /** The Dome's sphere has this radius, as a multiple of the hive's radius. *Judgement.* */
        const val DOME_R = 1.2f

        /** How far the Tunnel drops for each ring inward, in dp. *Judgement.* */
        const val TUNNEL_DEPTH = 120f

        /** How far a shattered tile flies, as a share of its distance from the middle. *Judgement.* */
        const val SHATTER_REACH = 0.9f

        /** The most a shattered tile spins, in radians: 60 degrees. *Judgement.* */
        const val SHATTER_SPIN = 60f * PI.toFloat() / 180f

        /** How many spectrum rows the Tunnel keeps: about four seconds. */
        const val HISTORY_ROWS = 240

        /** The analyser's time constant: smoothing 0.8 on every 20 ms read. */
        val ANALYSER_SECONDS: Float = (TICK_SECONDS / -ln(0.8)).toFloat()

        /** One turn of the hive on a beat: one tile of the outer ring, which has 36. */
        const val NOTCH = TAU / 36f

        /** Radians a heard second at full drive that the hive turns without a beat. *Judgement.* */
        const val TURN_RATE = 0.15f

        /** The time constant of the turn, in seconds. A notch snaps in about three of these. *Judgement.* */
        const val NOTCH_SECONDS = 0.05f

        /**
         * `mentalFactor`: "this factor makes the visualization go crazy wild". It is 0.5 * tan(volume / 6000),
         * held between -20 and 2. The tangent's pole sits between a volume of 9,424 and 9,425: at 7,955 and
         * up to the pole the corners are pushed out at the full 2, and past it they are pulled in at -20,
         * through the middle, before the pull eases towards zero at 18,850.
         */
        fun mentalFactor(volume: Int): Double = min(max(tan(volume / 6000.0) * 0.5, -20.0), 2.0)

        /**
         * calculateOffset's `offsetFactor`: how far a corner [distance] dp from the middle moves along its
         * line from the middle, outward when positive, for a tile whose peak is [high]. Not a number for a
         * peak below zero. [factor] is the page's [mentalFactor] unless the caller holds it back.
         */
        fun offsetFactor(distance: Double, volume: Int, high: Double, factor: Double = mentalFactor(volume)): Double =
            (distance / 3).pow(2) * (volume / 2000000.0) * (high.pow(1.3) / 300) * factor

        /** How much a tile shows at a value of 0, so the hive reads as one body where nothing plays. */
        const val ALPHA_FLOOR = 0.45f

        /**
         * How far round the palette's span each ring sits from the one inside it, so the seven rings
         * of a quiet hive wear the whole span: Prism's rings run red to violet outward, Fire's orange
         * to yellow. At 0.04 all seven rings sat inside the span's warm start, and a Prism hive read
         * like a Fire one.
         */
        const val RING_HUE_STEP = 0.14f

        /**
         * A tile's fill: its hue walks the palette with its level and steps round it with its ring, its
         * colour and its light rise with the level, and its alpha is the page's ramp with a floor.
         */
        fun fillOf(value: Double, light: Float, palette: VizPalette, walk: Float, ring: Int): Color {
            val level = (value / 255.0).coerceIn(0.0, 1.0).toFloat()
            return palette.cycled(
                position = level * 0.7f + ring * RING_HUE_STEP + walk,
                saturation = 0.55f + 0.45f * level,
                value = (0.3f + 0.7f * level) * light,
                alpha = max(ALPHA_FLOOR, alphaOf(value).toFloat().coerceIn(0f, 1f)),
            )
        }

        /** The fill opacity of a tile at [value]: two logistic steps, about 0.02 at 0 and opaque from 160. */
        fun alphaOf(value: Double): Double =
            (0.5 / (1 + 40 * E.pow(-value / 8))) + (0.5 / (1 + 40 * E.pow(-value / 20)))

        /** JavaScript's `Math.round`: the nearest whole number, with a half taken up. */
        private fun jsRound(value: Double): Double = floor(value + 0.5)
    }
}
