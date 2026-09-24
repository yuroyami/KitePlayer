package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
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
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Muser, a port of "Muser" by Jon Shamir.
 *
 * - Title: Muser
 * - Author: Jon Shamir (https://jonshamir.com)
 * - URL: https://jonshamir.github.io/muser/
 * - Repository: https://github.com/jonshamir/muser (src/webgl/scene/MuserVisualizer.js,
 *   src/webgl/scene/Particles.js, src/webgl/shaders/particle.vert and particle.frag,
 *   src/util/AudioPlayer.js, src/data/genres.json)
 * - Licence as found: MIT, `LICENSE.md`. Its text still carries the project template's line
 *   "Copyright (c) 2017 Matt DesLauriers"; Muser is Jon Shamir's, and the MIT notice is kept as it stands.
 * - Year: 2020 to 2021
 * - The README names Wassily Kandinsky's "Circles in a Circle" (1923) as the inspiration, takes the
 *   genre colours from the Musicmap project (https://musicmap.info/), and builds on threejs-app by
 *   Matt DesLauriers.
 * - Also ported: the Lab conversion, `darken`, `brighten` and `average` of chroma.js 2.1.0 (BSD 3-Clause,
 *   Copyright (c) 2011-2019, Gregor Aisch), and the screen-space line width of three.meshline 1.3.0
 *   (MIT, Copyright (c) 2016 Jaume Sanchez).
 *
 * Deviations from the original:
 * - The genre colour does not come from the musicnn genre model, which scored every second of the
 *   page's own songs ahead of time. Each heard second, the port scores the twelve colour families of
 *   the original genre table by how near the second's spectral centroid, flatness and onset density sit
 *   to a point chosen by hand for each family. The original's rule then runs unchanged: the five
 *   highest scores, each above 0.05 weighted by its share, averaged in Lab.
 * - The colour of a second is picked from the second heard before it, because the port cannot hear ahead.
 * - A second whose colour equals the last one holds it. The page compared colour strings and, on a
 *   match, faded again from the colour before; its per-second tags seldom matched.
 * - The page's analyser (FFT 128, smoothing 0.8, -100 to -30 dB) is rebuilt from the player's spectrum by
 *   [WebAudioAnalyser], on a 44.1 kHz layout, and read 60 times a heard second.
 * - The turn, the drift of the discs and the colour's second run on heard seconds, not on the wall
 *   clock, so the picture stands still while the music is paused or silent.
 * - A reduced-motion setting slows the turn.
 * - The line width is in dp where the page used CSS pixels: 5, which is what three.meshline draws for
 *   the page's `lineWidth` of 10 without size attenuation.
 * - A seeded generator places the discs and the lines instead of `Math.random()`, so a render repeats.
 * - Mouse orbit and zoom are dropped; the author's default camera is kept.
 * - The flash guard's light share multiplies every colour. It is 1 unless the picture would flash.
 * - The page's player, genre graph and controls are left out.
 *
 * Kandinsky's circles float in one flat field of colour that follows the music's character: grey for
 * sparse tonal music, red for dense electronic music, yellow and orange for noisy rock, blues and teals
 * between. Seven big translucent discs breathe with everything up to 5.5 kHz, fourteen near-black discs
 * drift slowly and swell with the upper middle, and a hundred bright dots drift faster, twinkle in and
 * out and grow with the treble. Ten black lines cross them, and the whole group turns slowly.
 */
internal class Muser : Visualization {

    override val name: String = "Muser"
    override val bucket: VizEnergy = VizEnergy.Calm
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // The page's "bass", the mean of bins 0 to 15 (0 to 5.5 kHz), sizes the big discs: most of the music.
            VizDrive(VizDriver.Level, VizProperty.Size, response = VizResponse.envelope(ANALYSER_SECONDS)),
            // Its "mid" (5.5 to 11 kHz) sizes the medium discs and its "treble" (11 to 16.5 kHz) the dots.
            VizDrive(VizDriver.Treble, VizProperty.Size, response = VizResponse.envelope(ANALYSER_SECONDS)),
            // The field's colour, picked from the heard second before and faded in over the next.
            VizDrive(VizDriver.Timbre, VizProperty.Colour, response = VizResponse.envelope(1f, delaySeconds = 1f)),
            VizDrive(VizDriver.Mood, VizProperty.Colour, response = VizResponse.envelope(1f, delaySeconds = 1f)),
        ),
        silence = VizSilence.Still,
    )

    /** The page's analyser: `fftSize = numFrequencyBins * 2 = 128`, and the Web Audio defaults for the rest. */
    internal val analyser = WebAudioAnalyser(fftSize = 128, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f)

    /**
     * `rangeAverage`: the mean byte of each quarter of the 64-byte row. Index 0 is the page's "bass",
     * 0 to 5.5 kHz; then "mid" to 11 kHz, "treble" to 16.5 kHz, and "high", which nothing reads.
     */
    internal val rangeAverage = DoubleArray(RANGES)

    /** How many analyser reads have run since the last reset. */
    internal var reads: Long = 0L
        private set

    /** The genre colour's per-second state. */
    internal val colour = MuserColour()

    // The page's Math.random() calls, in its order: three families of particles, then the lines.
    private val random = Random(SEED)
    internal val bass = MuserParticles(count = 8, radius = 1.2, timeScale = 0.0, random = random)
    internal val mid = MuserParticles(count = 14, radius = 2.0, timeScale = 0.8, random = random)
    internal val treble = MuserParticles(count = 100, radius = 3.0, timeScale = 3.0, random = random)
    private val families = arrayOf(bass, mid, treble)

    /** Both ends of each line, x, y and z, on a sphere of radius 2. */
    internal val lineEnds = DoubleArray(LINES * 6).also { ends ->
        for (line in 0 until LINES) {
            pointOnSphere(random, LINE_RADIUS, ends, line * 6)
            pointOnSphere(random, LINE_RADIUS, ends, line * 6 + 3)
        }
    }

    /** `this.rotation.y`, in radians. */
    internal var rotation: Double = 0.0
        private set

    /** Each family's `uTime`, in heard seconds. */
    internal var particleTime: Double = 0.0
        private set

    /** The clear colour this frame, packed RGB, as `genreColor.hex()` rounds it. */
    internal var background: Int = GREY
        private set

    private val step = DisplayStep()
    private var readOwed = 0.0
    private val genre = DoubleArray(3)
    private val shade = DoubleArray(3)

    init {
        updateScene()
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        val light = state.lightScale.coerceIn(0f, 1f)
        drawRect(Color(red(background) * light, green(background) * light, blue(background) * light))
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        if (size.width <= 0f || size.height <= 0f) return
        val light = state.lightScale.coerceIn(0f, 1f)
        // A camera 4 units out on z looking at the middle, 45 degrees of vertical field: world units to pixels.
        val focal = FOCAL * size.height / 2.0
        val middleX = size.width / 2.0
        val middleY = size.height / 2.0
        val turnCos = cos(rotation)
        val turnSin = sin(rotation)
        // Painter's order with no depth test, as on the page: bass, mid, treble, then the lines.
        for (family in families) {
            for (index in 0 until family.count) {
                val psize = family.discSize(index, particleTime)
                if (psize <= 0.0) continue
                val t = family.phase(index, particleTime)
                val shift = family.rand4[index] * t
                val x = family.offsetX[index] + shift * family.directionX[index]
                val y = family.offsetY[index] + shift * family.directionY[index]
                val z = family.offsetZ[index] + shift * family.directionZ[index]
                val turnedX = turnCos * x + turnSin * z
                val turnedZ = -turnSin * x + turnCos * z
                val depth = CAMERA_DISTANCE - turnedZ
                if (depth <= NEAR || depth >= FAR) continue
                val scale = focal / depth
                drawCircle(
                    family.colourOf(index, light),
                    radius = (psize / 2.0 * scale).toFloat(),
                    center = Offset((middleX + turnedX * scale).toFloat(), (middleY - y * scale).toFloat()),
                )
            }
        }
        val width = LINE_WIDTH_DP * density
        for (line in 0 until LINES) {
            val at = line * 6
            drawLine(
                Color.Black,
                start = project(lineEnds, at, turnCos, turnSin, focal, middleX, middleY),
                end = project(lineEnds, at + 3, turnCos, turnSin, focal, middleX, middleY),
                strokeWidth = width,
                cap = StrokeCap.Butt,
            )
        }
    }

    private fun project(
        points: DoubleArray,
        at: Int,
        turnCos: Double,
        turnSin: Double,
        focal: Double,
        middleX: Double,
        middleY: Double,
    ): Offset {
        val x = points[at]
        val z = points[at + 2]
        val turnedX = turnCos * x + turnSin * z
        val scale = focal / (CAMERA_DISTANCE - (-turnSin * x + turnCos * z))
        return Offset((middleX + turnedX * scale).toFloat(), (middleY - points[at + 1] * scale).toFloat())
    }

    /** The page's frame: the analyser reads, `uTime += dt` and `rotation.y += dt * 0.1`, on heard seconds. */
    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        // WebGLApp clamps a frame's step to 1/30 s.
        val heard = min(dt.toDouble(), MAX_DELTA) * state.frame.audible
        readOwed += heard
        var count = 0
        while (readOwed >= READ_PERIOD * 0.999 && count < MAX_READS) {
            readOwed -= READ_PERIOD
            count++
            read(state.frame)
        }
        if (count == MAX_READS) readOwed = min(readOwed, READ_PERIOD)
        colour.advance(heard, state.frame)
        particleTime += heard
        rotation += heard * ROTATION_SPEED * state.motionScale
        updateScene()
    }

    /** `getCurrentFrequencyData`: one `getByteFrequencyData` read, averaged over four equal ranges. */
    private fun read(frame: SpectrumFrame) {
        reads++
        analyser.read(frame)
        rangeAverage.fill(0.0)
        val bytes = analyser.frequencyBytes
        for (bin in bytes.indices) rangeAverage[bin / RANGE_SIZE] += bytes[bin] * (1.0 / RANGE_SIZE)
    }

    /** `updateScene`: the clear colour and each family's size and colour. */
    private fun updateScene() {
        colour.genre(genre)
        background = MuserChroma.hex(genre)
        bass.size = 0.3 + 0.35 * (rangeAverage[0] / 255.0)
        MuserChroma.darken(genre, 1.0, shade)
        bass.setColour(shade, 0.8f)
        mid.size = 0.1 + 0.1 * (rangeAverage[1] / 255.0)
        MuserChroma.darken(genre, 2.5, shade)
        mid.setColour(shade, 0.7f)
        treble.size = 0.01 + 0.05 * (rangeAverage[2] / 255.0)
        MuserChroma.darken(genre, -2.0, shade)
        treble.setColour(shade, 0.9f)
    }

    override fun reset() {
        step.reset()
        analyser.reset()
        rangeAverage.fill(0.0)
        reads = 0L
        readOwed = 0.0
        colour.reset()
        particleTime = 0.0
        rotation = 0.0
        updateScene()
    }

    internal companion object {
        const val LINES = 10
        const val RANGES = 4

        /** `numFrequencyBins / 4`: 16 of the 64 bins in each range. */
        const val RANGE_SIZE = 16

        const val LINE_RADIUS = 2.0

        /** three.meshline's width without size attenuation is half its `lineWidth` of 10, in CSS pixels. */
        const val LINE_WIDTH_DP = 5f

        /** `this.rotation.y += dt * 0.1`. */
        const val ROTATION_SPEED = 0.1

        /** The orbit controls' distance, and the camera's near and far planes. */
        const val CAMERA_DISTANCE = 4.0
        const val NEAR = 0.01
        const val FAR = 100.0

        /** `1 / tan(fov / 2)` for three.js's vertical field of view of 45 degrees. */
        val FOCAL: Double = 1.0 / tan(22.5 * PI / 180.0)

        /** WebGLApp's `maxDeltaTime`. */
        const val MAX_DELTA = 1.0 / 30.0

        /** The page read its analyser once per 60 Hz frame. */
        const val READ_PERIOD = 1.0 / 60.0

        /** At most this many reads in one frame, so a stalled frame does not spin. */
        const val MAX_READS = 4

        /** The analyser's time constant: smoothing 0.8 on every 60 Hz read. */
        val ANALYSER_SECONDS: Float = (READ_PERIOD / -ln(0.8)).toFloat()

        /** `#888888`, the page's colour before any genre is known. */
        const val GREY = 0x888888

        const val SEED = 1_923L

        /** The upper edge of the page's "bass" range: 16 bins of 44,100 / 128 Hz. */
        const val BASS_TOP_HZ = RANGE_SIZE * 44_100.0 / 128.0

        fun red(rgb: Int): Float = (rgb shr 16 and 0xFF) / 255f
        fun green(rgb: Int): Float = (rgb shr 8 and 0xFF) / 255f
        fun blue(rgb: Int): Float = (rgb and 0xFF) / 255f

        /** MuserVisualizer's `getPointOnSphere`: a uniform point on a sphere of [radius]. */
        private fun pointOnSphere(random: Random, radius: Double, out: DoubleArray, at: Int) {
            val u = random.nextDouble()
            val v = random.nextDouble()
            val theta = 2 * PI * u
            val phi = acos(2 * v - 1)
            out[at] = radius * sin(phi) * cos(theta)
            out[at + 1] = radius * sin(phi) * sin(theta)
            out[at + 2] = radius * cos(phi)
        }
    }
}

/**
 * One `Particles` instance: [count] camera-facing discs placed in a ball of [radius], each cycling
 * through a fade in and out every `1 / (0.05 * timeScale)` seconds while sliding up to one unit
 * along its own direction. The bass family's time scale is 0, so its discs never move. Disc 0 of every
 * family hashes to -1 on all four values, so its cycle starts at 0: the first bass disc never shows,
 * as on the page, and the author's screenshot has seven.
 */
internal class MuserParticles(val count: Int, val radius: Double, val timeScale: Double, random: Random) {
    val offsetX = DoubleArray(count)
    val offsetY = DoubleArray(count)
    val offsetZ = DoubleArray(count)

    /** The shader's four hashes of the instance index, between -1 and 1, in 32-bit float as on a GPU. */
    val rand1 = FloatArray(count) { hash(it, 43758.5453123f) }
    val rand2 = FloatArray(count) { hash(it, 23718.5253123f) }
    val rand3 = FloatArray(count) { hash(it, 11218.5153123f) }
    val rand4 = FloatArray(count) { hash(it, 72310.2413133f) }

    /** `normalize(vec3(rand1, rand2, rand3))`, the direction each disc slides along. */
    val directionX = DoubleArray(count)
    val directionY = DoubleArray(count)
    val directionZ = DoubleArray(count)

    /** `uSize`. */
    var size: Double = 0.1

    /** `uColor`, 0 to 1 per channel, and its alpha. */
    private val colour = FloatArray(3)
    private var alpha = 1f

    init {
        for (i in 0 until count) {
            // getPointInSphere: a uniform point inside the unit ball, then the unused angle.
            val u = random.nextDouble()
            val v = random.nextDouble()
            val theta = u * 2.0 * PI
            val phi = acos(2.0 * v - 1.0)
            val r = cbrt(random.nextDouble())
            offsetX[i] = radius * r * sin(phi) * cos(theta)
            offsetY[i] = radius * r * sin(phi) * sin(theta)
            offsetZ[i] = radius * r * cos(phi)
            random.nextDouble()
            val length = sqrt(rand1[i] * rand1[i] + rand2[i] * rand2[i] + rand3[i] * rand3[i].toDouble())
            directionX[i] = rand1[i] / length
            directionY[i] = rand2[i] / length
            directionZ[i] = rand3[i] / length
        }
    }

    /** `t = mod(rand1 + 0.05 * uTime * uTimeScale, 1.0)`: where disc [index] is in its cycle. */
    fun phase(index: Int, time: Double): Double {
        val t = rand1[index] + 0.05 * time * timeScale
        return t - floor(t)
    }

    /** `psize = fadeInOut(t) * uSize * (1.0 + (0.5 + 0.5 * rand2))`: the disc's diameter in world units. */
    fun discSize(index: Int, time: Double): Double =
        fadeInOut(phase(index, time)) * size * (1.0 + (0.5 + 0.5 * rand2[index]))

    fun setColour(rgb: DoubleArray, alpha: Float) {
        for (channel in 0 until 3) colour[channel] = (rgb[channel] / 255.0).toFloat()
        this.alpha = alpha
    }

    /** `vColor`: the family colour with its hue moved by `0.03 * rand1` and its value by `0.1 * rand2`. */
    fun colourOf(index: Int, light: Float): Color {
        val hsv = rgbToHsv(colour[0], colour[1], colour[2])
        var hue = hsv[0] + 0.03f * rand1[index]
        hue -= floor(hue)
        val value = (hsv[2] + 0.1f * rand2[index]).coerceIn(0f, 1f)
        val rgb = hsvToRgb(hue, hsv[1], value)
        return Color(rgb[0] * light, rgb[1] * light, rgb[2] * light, alpha)
    }

    private val hsvOut = FloatArray(3)
    private val rgbOut = FloatArray(3)

    /** The shader's `rgb2hsv`. */
    private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        // p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g))
        val px: Float
        val py: Float
        val pz: Float
        val pw: Float
        if (g >= b) {
            px = g; py = b; pz = 0f; pw = -1f / 3f
        } else {
            px = b; py = g; pz = -1f; pw = 2f / 3f
        }
        // q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r))
        val qx: Float
        val qy: Float
        val qz: Float
        val qw: Float
        if (r >= px) {
            qx = r; qy = py; qz = pz; qw = px
        } else {
            qx = px; qy = py; qz = pw; qw = r
        }
        val d = qx - minOf(qw, qy)
        val e = 1.0e-10f
        hsvOut[0] = abs(qz + (qw - qy) / (6f * d + e))
        hsvOut[1] = d / (qx + e)
        hsvOut[2] = qx
        return hsvOut
    }

    /** The shader's `hsv2rgb`. */
    private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
        val offsets = HSV_K
        for (channel in 0 until 3) {
            val shifted = h + offsets[channel]
            val p = abs((shifted - floor(shifted)) * 6f - 3f)
            rgbOut[channel] = v * (1f + ((p - 1f).coerceIn(0f, 1f) - 1f) * s)
        }
        return rgbOut
    }

    private companion object {
        val HSV_K = floatArrayOf(1f, 2f / 3f, 1f / 3f)

        /** `2.0 * (rand(pindex, alpha) - 0.5)` with `rand(n, alpha) = fract(sin(n) * alpha)`. */
        fun hash(index: Int, alpha: Float): Float {
            val value = sin(index.toFloat()) * alpha
            return 2f * ((value - floor(value)) - 0.5f)
        }

        /** `-pow(2.0 * (t - 0.5), 6.0) + 1.0`. */
        fun fadeInOut(t: Double): Double = -(2.0 * (t - 0.5)).pow(6) + 1.0
    }
}

/**
 * The genre colour over time: one colour a heard second, picked from what that second sounded like,
 * and a Lab cross-fade from the colour before it across the next second.
 */
internal class MuserColour {
    /** `prevBG` and `currBG`, packed RGB. */
    var previous: Int = Muser.GREY
        private set
    var current: Int = Muser.GREY
        private set

    /** The heard seconds since the last reset: the page's `player.getCurrentTime()`. */
    var songTime: Double = 0.0
        private set

    private var second = 0L
    private var heard = 0.0
    private var centroid = 0.0
    private var flatness = 0.0
    private var density = 0.0

    fun advance(seconds: Double, frame: SpectrumFrame) {
        if (seconds <= 0.0) return
        heard += seconds
        centroid += frame.centroid * seconds
        flatness += frame.flatness * seconds
        density += frame.density * seconds
        songTime += seconds
        val now = floor(songTime).toLong()
        if (now == second) return
        second = now
        val next = if (heard > 0.0) MuserGenres.colourOf(centroid / heard, flatness / heard, density / heard) else current
        previous = current
        current = next
        heard = 0.0
        centroid = 0.0
        flatness = 0.0
        density = 0.0
    }

    private val pair = IntArray(2)
    private val weights = DoubleArray(2)

    /** `chroma.average([prevBG, currBG], "lab", [1 - a, a])` with `a = currentTime % 1`, into [out] as 0 to 255. */
    fun genre(out: DoubleArray) {
        val a = songTime - floor(songTime)
        pair[0] = previous
        pair[1] = current
        weights[0] = 1.0 - a
        weights[1] = a
        MuserChroma.average(pair, weights, out)
    }

    fun reset() {
        previous = Muser.GREY
        current = Muser.GREY
        songTime = 0.0
        second = 0L
        heard = 0.0
        centroid = 0.0
        flatness = 0.0
        density = 0.0
    }
}

/**
 * The page's genre table, `genres.json`, as colour families, and the rule that turns scores into one colour.
 *
 * Every title and colour is the original's, in its order. The point each family sits at (a spectral
 * centroid, a flatness and an onset density, each 0 to 1) is the port's own, chosen by hand against
 * six test songs: tonal and sparse for classical, ambient and folk; dark and busy for hip-hop; dense
 * and noisy for electronic music; noisier and brighter through rock to metal. *Judgement.*
 */
internal object MuserGenres {

    class Family(
        val titles: List<String>,
        val colour: Int,
        val centroid: Double,
        val flatness: Double,
        val density: Double,
    )

    val FAMILIES: List<Family> = listOf(
        Family(listOf("classical", "classic", "opera"), 0x888888, 0.45, 0.08, 0.30),
        Family(listOf("ambient", "chill", "chillout", "Mellow"), 0x741b47, 0.35, 0.12, 0.15),
        Family(listOf("electro", "electronic", "electronica", "dance", "party", "House"), 0x990000, 0.52, 0.40, 0.95),
        Family(listOf("Hip-Hop"), 0x741b47, 0.28, 0.18, 0.75),
        Family(listOf("blues"), 0x1155cc, 0.38, 0.10, 0.55),
        Family(listOf("jazz"), 0x0a5394, 0.46, 0.15, 0.65),
        Family(listOf("rnb", "soul", "funk"), 0x134f5c, 0.34, 0.22, 0.80),
        Family(listOf("folk"), 0x124f5c, 0.45, 0.10, 0.45),
        Family(listOf("country", "pop", "indie pop"), 0xb3c376, 0.50, 0.30, 0.70),
        Family(
            listOf("rock", "oldies", "classic rock", "hard rock", "indie", "indie rock", "alternative rock", "alternative", "punk"),
            0xf1c232, 0.54, 0.46, 0.82,
        ),
        Family(listOf("metal", "heavy metal"), 0xe69238, 0.60, 0.58, 1.00),
        Family(listOf("Progressive rock"), 0xe66c38, 0.56, 0.50, 0.62),
    )

    /** How far from a family's point a score falls to 1/e, for the centroid, the flatness and the density. */
    const val CENTROID_REACH = 0.08
    const val FLATNESS_REACH = 0.10
    const val DENSITY_REACH = 0.25

    /** `tagConfidenceThreshold`. */
    const val CONFIDENCE = 0.05

    /** `topGenres = currentGenres.slice(0, 5)`. */
    const val TOP = 5

    /** Each family's score, 0 to 1, for one second that sounded like this. */
    fun scores(centroid: Double, flatness: Double, density: Double): DoubleArray = DoubleArray(FAMILIES.size) {
        val family = FAMILIES[it]
        val c = (centroid - family.centroid) / CENTROID_REACH
        val f = (flatness - family.flatness) / FLATNESS_REACH
        val d = (density - family.density) / DENSITY_REACH
        exp(-(c * c + f * f + d * d))
    }

    fun colourOf(centroid: Double, flatness: Double, density: Double): Int = topGenresColour(scores(centroid, flatness, density))

    /**
     * `_preprocessTrackTags` for one second: the [TOP] highest [values], each weighted by its value over
     * the sum, a value at or under [CONFIDENCE] weighted 0, and their colours averaged in Lab, rounded.
     * The page's sum starts from the highest value whatever it is, because its `reduce` has no seed.
     */
    fun topGenresColour(values: DoubleArray): Int {
        // A stable sort, highest first, as Array.prototype.sort with compareGenres.
        val order = values.indices.sortedByDescending { values[it] }.take(TOP)
        var sum = values[order[0]]
        for (rank in 1 until order.size) if (values[order[rank]] > CONFIDENCE) sum += values[order[rank]]
        val weights = DoubleArray(order.size) { rank ->
            val value = values[order[rank]]
            if (value > CONFIDENCE) value / sum else 0.0
        }
        // With every weight 0 the page's average is not a number; the port keeps the nearest family.
        if (weights.all { it == 0.0 }) return FAMILIES[order[0]].colour
        val colours = IntArray(order.size) { FAMILIES[order[it]].colour }
        val out = DoubleArray(3)
        MuserChroma.average(colours, weights, out)
        return MuserChroma.hex(out)
    }
}

/** The parts of chroma.js 2.1.0 the page calls, on colours as 0 to 255 per channel. */
internal object MuserChroma {
    /** How far `darken(1)` moves the Lab lightness. */
    const val KN = 18.0

    // The D65 white and the constants of chroma.js's Lab.
    private const val XN = 0.950470
    private const val YN = 1.0
    private const val ZN = 1.088830
    private const val T0 = 0.137931034
    private const val T1 = 0.206896552
    private const val T2 = 0.12841855
    private const val T3 = 0.008856452

    /** `rgb2lab` into [out]. */
    fun lab(r: Double, g: Double, b: Double, out: DoubleArray) {
        val lr = rgbXyz(r)
        val lg = rgbXyz(g)
        val lb = rgbXyz(b)
        val x = xyzLab((0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb) / XN)
        val y = xyzLab((0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb) / YN)
        val z = xyzLab((0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb) / ZN)
        val l = 116 * y - 16
        out[0] = if (l < 0) 0.0 else l
        out[1] = 500 * (x - y)
        out[2] = 200 * (y - z)
    }

    /** `lab2rgb`, then the colour constructor's clip to 0..255, into [out]. */
    fun rgb(l: Double, a: Double, b: Double, out: DoubleArray) {
        var y = (l + 16) / 116
        var x = y + a / 500
        var z = y - b / 200
        y = YN * labXyz(y)
        x = XN * labXyz(x)
        z = ZN * labXyz(z)
        out[0] = xyzRgb(3.2404542 * x - 1.5371385 * y - 0.4985314 * z).coerceIn(0.0, 255.0)
        out[1] = xyzRgb(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z).coerceIn(0.0, 255.0)
        out[2] = xyzRgb(0.0556434 * x - 0.2040259 * y + 1.0572252 * z).coerceIn(0.0, 255.0)
    }

    /** `color.darken(amount)`; a negative amount is `brighten`. */
    fun darken(rgb: DoubleArray, amount: Double, out: DoubleArray) {
        val lab = DoubleArray(3)
        lab(rgb[0], rgb[1], rgb[2], lab)
        rgb(lab[0] - KN * amount, lab[1], lab[2], out)
    }

    /** `chroma.average(colors, "lab", weights)`: the weighted mean in Lab, into [out]. */
    fun average(colours: IntArray, weights: DoubleArray, out: DoubleArray) {
        val lab = DoubleArray(3)
        val sum = DoubleArray(3)
        var total = 0.0
        for (index in colours.indices) {
            val colour = colours[index]
            lab((colour shr 16 and 0xFF).toDouble(), (colour shr 8 and 0xFF).toDouble(), (colour and 0xFF).toDouble(), lab)
            for (channel in 0 until 3) sum[channel] += lab[channel] * weights[index]
            total += weights[index]
        }
        rgb(sum[0] / total, sum[1] / total, sum[2] / total, out)
    }

    /** `color.hex()`: each channel rounded half up, packed. */
    fun hex(rgb: DoubleArray): Int {
        fun byte(value: Double): Int = floor(value + 0.5).toInt().coerceIn(0, 255)
        return (byte(rgb[0]) shl 16) or (byte(rgb[1]) shl 8) or byte(rgb[2])
    }

    private fun rgbXyz(channel: Double): Double {
        val c = channel / 255
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun xyzLab(t: Double): Double = if (t > T3) t.pow(1.0 / 3.0) else t / T2 + T0

    private fun xyzRgb(r: Double): Double = 255 * (if (r <= 0.00304) 12.92 * r else 1.055 * r.pow(1 / 2.4) - 0.055)

    private fun labXyz(t: Double): Double = if (t > T1) t * t * t else T2 * (t - T0)
}
