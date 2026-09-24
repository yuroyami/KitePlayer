package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.ShortFftAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
 *   by [ShortFftAnalyser], on a 44.1 kHz layout. On two test songs its `volume` averaged within 2
 *   percent of Chrome's.
 * - The 20 ms reads, the 20 ms turns and the 100 ms background run on heard seconds, not on timers,
 *   so the picture stands still while the music is paused or silent.
 * - The per-frame falls of the tiles and the white outlines, and the star flight, are scaled by frame
 *   time, so a 120 Hz screen moves as fast as a 60 Hz one. They stop in silence as well.
 * - Sizes are in dp where the page used CSS pixels, and the lines are drawn sharp at the screen's
 *   resolution, where the page stretched a canvas of CSS pixels.
 * - A seeded generator places the stars instead of `Math.random()`, so a render repeats.
 * - A reduced-motion setting slows the spin and the star flight.
 * - The flash guard's light share multiplies every colour. It is 1 unless the picture would flash.
 * - The page's SoundCloud player, track panel and controls are left out.
 *
 * A honeycomb of 127 hexagons lights up like a meter: the lowest frequency sits in the middle and the
 * rings climb the spectrum. Each tile takes its colour and opacity from its level, from a faint red
 * through magenta, violet, blue and green to pale blue and pink, and falls back slowly from its peak. A loud tile gets a
 * white edge that fades over three seconds. The honeycomb turns slowly; on a loud passage the turn
 * reverses and the tiles are pulled in and thrown through the middle. Grey star streaks rush outward
 * behind it, and the edges glow red to pink with the volume.
 */
internal class Honeycomb : Visualization {

    override val name: String = "Honeycomb"
    override val bucket: VizEnergy = VizEnergy.High
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // Each tile reads one bin of the page's analyser: its colour, its opacity and how far it is pushed.
            VizDrive(VizDriver.Bands, VizProperty.Colour, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Bands, VizProperty.Brightness, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Bands, VizProperty.Shape, response = VizResponse.envelope(ANALYSER_SECONDS)),
            // The page's volume, the sum of its lowest 80 bins, pushes the corners and sets the turn,
            // the star flight, the streak length and the background's colour.
            VizDrive(VizDriver.Level, VizProperty.Shape, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Level, VizProperty.Speed, response = VizResponse.Rate),
            VizDrive(VizDriver.Level, VizProperty.Size, response = VizResponse.envelope(ANALYSER_SECONDS)),
            VizDrive(VizDriver.Level, VizProperty.Colour, response = VizResponse.envelope(ANALYSER_SECONDS)),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = 256`, and the Web Audio defaults for the rest. */
    internal val analyser = ShortFftAnalyser(fftSize = 256, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f)

    /** `audioSource.volume`: the sum of bins 0 to 79, taken at each read. */
    internal var volume: Int = 0
        private set

    /** How many 20 ms steps have run since the last reset. */
    internal var ticks: Long = 0L
        private set

    private val step = DisplayStep()
    private var random = Random(SEED)

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

    private val path = Path()

    /** The canvas's 1 px line, with its default miter limit of 10. */
    private val hairline = Stroke(width = 1f, miter = 10f)

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state, size, density)
        val light = state.lightScale.coerceIn(0f, 1f)
        drawBackground(light)
        withPage { drawStars(light) }
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state, size, density)
        val light = state.lightScale.coerceIn(0f, 1f)
        withPage {
            drawTiles(light)
            drawEdges(light)
        }
    }

    /** The page's translated context: dp from the middle of the screen. */
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
        // `window.innerWidth` and `innerHeight` are whole CSS pixels.
        val pageWidth = floor(canvas.width / density + 0.5)
        val pageHeight = floor(canvas.height / density + 0.5)
        if (pageWidth != width || pageHeight != height) resize(pageWidth, pageHeight)
        val heard = dt.toDouble() * state.frame.audible
        owed += heard
        var steps = 0
        while (owed >= TICK_SECONDS - 1e-9 && steps < MAX_TICKS) {
            owed -= TICK_SECONDS
            steps++
            tick(state)
        }
        if (steps == MAX_TICKS) owed = min(owed, TICK_SECONDS)
        // The page's per-frame steps, for a page drawing 60 frames a second.
        val frames = heard * 60.0
        moveStars(frames, state.motionScale.toDouble())
        updateTiles(frames)
    }

    /** What the page's two 20 ms timers and its 100 ms timer do. */
    private fun tick(state: VizRenderState) {
        ticks++
        // SoundCloudAudioSource: "get the volume from the first 80 bins, else it gets too loud with treble".
        analyser.read(state.frame)
        var total = 0
        for (bin in 0 until VOLUME_BINS) total += analyser.frequencyBytes[bin]
        volume = total
        // rotateForeground: a shear that turns every corner a little, in place.
        val shear = sin(rotationStep(volume)) * state.motionScale
        for (index in vertexX.indices) {
            vertexX[index] = vertexX[index] - vertexY[index] * shear
            vertexY[index] = vertexY[index] + vertexX[index] * shear
        }
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
        fun add(x: Int, y: Int) = placeTile(num++, x, y)
        add(0, 0)
        for (layer in 1 until 7) {
            add(0, layer)
            add(0, -layer)
            for (x in 1 until layer) {
                add(x, -layer)
                add(-x, layer)
                add(x, layer - x)
                add(-x, -layer + x)
            }
            for (y in -layer..0) {
                add(layer, y)
                add(-layer, -y)
            }
        }
    }

    /** The Polygon constructor: a centre on the 60 degree grid, and six corners from 90 degrees round. */
    private fun placeTile(num: Int, x: Int, y: Int) {
        val step = jsRound(cos(PI / 6) * tileSize * 2)
        val centreY = jsRound(step * sin(PI / 3) * -y)
        val centreX = jsRound(x * step + y * step / 2)
        for (corner in 0 until SIDES) {
            val i = corner + 1
            vertexX[num * SIDES + corner] = centreX + tileSize * cos(i * 2 * PI / SIDES + PI / 6)
            vertexY[num * SIDES + corner] = centreY + tileSize * sin(i * 2 * PI / SIDES + PI / 6)
        }
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
    private fun updateTiles(frames: Double) {
        val bytes = analyser.frequencyBytes
        for (num in 0 until TILES) {
            var value = (bytes[bucketOf(num)] / 255.0).pow(2) * 255
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
    private fun DrawScope.drawBackground(light: Float) {
        drawRect(Color.Black)
        if (backgroundVolume != brushVolume || size.width != brushWidth || size.height != brushHeight ||
            density != brushDensity || light != brushLight
        ) {
            brushVolume = backgroundVolume
            brushWidth = size.width
            brushHeight = size.height
            brushDensity = density
            brushLight = light
            background = backgroundBrush(light)
        }
        background?.let { drawRect(it) }
    }

    private fun DrawScope.backgroundBrush(light: Float): Brush? {
        val value = backgroundVolume / 1000.0
        val red = channel(200 + (sin(value) + 1) * 28)
        val green = channel(value * 2)
        val blue = channel(value * 8)
        val inner = value
        val outer = width - min(value.pow(2.7), width - 20)
        // "If x0 = x1 and y0 = y1 and r0 = r1, then the radial gradient must paint nothing."
        if (outer == inner) return null
        val reach = max(inner, outer)
        val stops = ArrayList<Pair<Float, Color>>(GRADIENT_SAMPLES + 3)
        fun addStop(distance: Double) {
            // The canvas interpolates colour and alpha unpremultiplied, from clear black at 0 to the
            // edge colour at alpha 0.4 at 0.8, so over black the light rises with the square of the way.
            val along = ((distance - inner) / (outer - inner)).coerceIn(0.0, 1.0)
            val share = min(along / 0.8, 1.0)
            val lit = (0.4 * share * share).toFloat() * light
            stops += (distance / reach).toFloat() to Color(red * lit, green * lit, blue * lit)
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

    private fun DrawScope.drawTiles(light: Float) {
        val dark = Color(DARK * light, DARK * light, DARK * light, 0.5f)
        for (num in 0 until TILES) {
            val value = shown[num]
            if (value <= 0) continue
            if (!tracePath(num)) continue
            drawPath(path, fillOf(value, light))
            if (value > 20) drawPath(path, dark, style = hairline)
        }
    }

    /** drawHighlight: a white edge on every tile whose highlight has not faded yet. */
    private fun DrawScope.drawEdges(light: Float) {
        for (num in 0 until TILES) {
            val alpha = edge[num]
            if (alpha <= 0) continue
            if (!tracePath(num)) continue
            drawPath(path, Color(light, light, light, alpha.toFloat().coerceIn(0f, 1f)), style = hairline)
        }
    }

    /**
     * The tile's outline with every corner pushed along its own line from the middle
     * (calculateOffset). False when the push is not a number, a tile whose peak has fallen below
     * zero, where the canvas ignores every point and draws nothing.
     */
    private fun tracePath(num: Int): Boolean {
        path.reset()
        for (corner in 0 until SIDES) {
            val x = vertexX[num * SIDES + corner]
            val y = vertexY[num * SIDES + corner]
            val angle = atan(y / x)
            val distance = sqrt(x.pow(2) + y.pow(2))
            val push = offsetFactor(distance, volume, high[num])
            if (push.isNaN()) return false
            var offsetX = cos(angle) * push
            var offsetY = sin(angle) * push
            if (x < 0) {
                offsetX = -offsetX
                offsetY = -offsetY
            }
            val px = (x + offsetX).toFloat()
            val py = (y + offsetY).toFloat()
            if (corner == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return true
    }

    private fun fillOf(value: Double, light: Float): Color {
        val rgb = rgbOf(value)
        return Color(
            (rgb shr 16 and 0xFF) / 255f * light,
            (rgb shr 8 and 0xFF) / 255f * light,
            (rgb and 0xFF) / 255f * light,
            alphaOf(value).toFloat().coerceIn(0f, 1f),
        )
    }

    override fun reset() {
        step.reset()
        analyser.reset()
        random = Random(SEED)
        volume = 0
        ticks = 0L
        owed = 0.0
        width = -1.0
        height = -1.0
        backgroundVolume = 0
        brushVolume = -1
        background = null
    }

    internal companion object {
        const val TILES = 127
        const val SIDES = 6

        /** The bins `volume` adds up: 0 to 79 of 128. */
        const val VOLUME_BINS = 80

        /** The page's two fast timers, `setInterval(..., 20)`. */
        const val TICK_SECONDS = 0.02

        /** At most this many 20 ms steps in one frame, so a stalled frame does not spin. */
        const val MAX_TICKS = 10

        /** How many samples draw the background's rise from clear to the edge colour. */
        const val GRADIENT_SAMPLES = 8

        const val DARK = 20f / 255f

        /** The page writes e as 2.7182 in the tile's opacity. */
        const val E = 2.7182

        const val SEED = 20_131_231L

        /** The analyser's time constant: smoothing 0.8 on every 20 ms read. */
        val ANALYSER_SECONDS: Float = (TICK_SECONDS / -ln(0.8)).toFloat()

        /** The bin tile [num] reads: `Math.ceil(128 / 127 * num)`, so tile 0 reads bin 0 and bin 1 is never read. */
        fun bucketOf(num: Int): Int = ceil(128.0 / TILES * num).toInt()

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
         * peak below zero.
         */
        fun offsetFactor(distance: Double, volume: Int, high: Double): Double =
            (distance / 3).pow(2) * (volume / 2000000.0) * (high.pow(1.3) / 300) * mentalFactor(volume)

        /** rotateForeground's angle per 20 ms: 0.001, and turned back by sin(volume / 800,000) above 10,000. */
        fun rotationStep(volume: Int): Double {
            var rotation = FG_ROTATION
            rotation -= if (volume > 10000) sin(volume / 800000.0) else 0.0
            return rotation
        }

        private const val FG_ROTATION = 0.001

        /** The fill colour of a tile at [value], as 0xRRGGBB, clamped as the canvas clamps `rgba()`. */
        fun rgbOf(value: Double): Int {
            val r: Double
            val g: Double
            val b: Double
            if (value > 128) {
                r = (value - 128) * 2
                g = ((cos((2 * value / 128 * PI / 2) - 4 * PI / 3) + 1) * 128)
                b = (value - 105) * 3
            } else {
                // The page's `else if (val > 175)` branch sits after `val > 128` and never runs.
                r = ((cos((2 * value / 128 * PI / 2)) + 1) * 128)
                g = ((cos((2 * value / 128 * PI / 2) - 4 * PI / 3) + 1) * 128)
                b = ((cos((2.4 * value / 128 * PI / 2) - 2 * PI / 3) + 1) * 128)
            }
            return (byteOf(r) shl 16) or (byteOf(g) shl 8) or byteOf(b)
        }

        /** The fill opacity of a tile at [value]: two logistic steps, about 0.02 at 0 and opaque from 160. */
        fun alphaOf(value: Double): Double =
            (0.5 / (1 + 40 * E.pow(-value / 8))) + (0.5 / (1 + 40 * E.pow(-value / 20)))

        /** `Math.round`, which takes a half up, then the canvas's clamp to 0..255. */
        private fun byteOf(channel: Double): Int = jsRound(channel).coerceIn(0.0, 255.0).toInt()

        /** A colour channel of the background, rounded and clamped, as a share of full. */
        private fun channel(value: Double): Float = byteOf(value) / 255f

        /** JavaScript's `Math.round`: the nearest whole number, with a half taken up. */
        private fun jsRound(value: Double): Double = floor(value + 0.5)
    }
}
