package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizQualityControl
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Fireworks, a port of "Fireworks with WebGL" by Ondřej Žára.
 *
 * - Title: Fireworks with WebGL (the page is titled "WebGL + Web Audio API = Fireworks")
 * - Author: Ondřej Žára, https://ondras.zarovi.cz/
 * - URL: http://ondras.github.io/fireworks-webgl/
 * - Repository: https://github.com/ondras/fireworks-webgl
 * - Licence as found: MIT, `LICENSE`: "Copyright (c) 2021 Ondřej Žára". The file was added in 2021;
 *   the code dates from December 2014.
 * - Year: 2014, made for New Year 2015
 * - The page bundles gl-matrix 2.2.1 (BSD-style notice, Brandon Jones and Colin MacKenzie IV). Its
 *   `vec3.random`, `vec2.random`, `perspective`, `lookAt`, `rotateX` and `rotateY` are re-implemented here.
 *
 * Deviations from the original:
 * - The page's analyser (FFT 1024, smoothing 0.5, -100 to -20 dB) is rebuilt from the player's
 *   spectrum by [WebAudioAnalyser] on a 44.1 kHz layout. Its reading spans the frame's window of
 *   about 43 ms, not the browser's 23 ms snapshot, so a kick's rise can be spread over two reads.
 * - The 30 ms trigger timer, the 300 ms hold and every burst's age run on heard milliseconds, not
 *   on `Date.now()`, so bursts freeze while the music is paused or silent.
 * - The camera's orbit (radius 20, a turn in 188 s) runs on heard seconds from angle zero, not on
 *   `Date.now()`, and a reduced-motion setting slows it.
 * - Every spark set is drawn in one textured triangle call instead of a point call, for phones. The
 *   counts are the author's; the texture keeps the `(1 - d)^3` falloff at 128 texels.
 * - The stars are drawn in one triangle call, each square in its own grey, as the page draws them in one point call.
 * - Sizes are in dp where the page used CSS pixels, drawn at the screen's resolution. The page drew
 *   a canvas of CSS pixels, which the browser then stretched.
 * - A seeded generator replaces `Math.random()`, so a render repeats.
 * - At most 48 bursts live at once, and the oldest goes first. The page has no ceiling.
 * - The flash guard's light share multiplies every colour. It is 1 unless the picture would flash.
 * - The page's jukebox, song banner and credit line are left out.
 *
 * Coloured bursts of sparks pop in black space among 10,000 grey stars while the camera circles.
 * A burst is a sphere of 1000 sparks, or one to three tilted rings of 100 to 200, or a sphere with
 * rings; each set has one warm random colour, and the sparks add up to white where they are dense.
 * A burst spreads fast, slows down, sags and fades out over two to five seconds, and up to 8.5
 * after the largest rise. Only the lowest
 * frequency bin listens: a rise of more than 15 in one 30 ms read fires a burst, at most one per
 * 300 ms, and the size of the rise sets how far and how long the burst flies.
 */
internal class Fireworks : Visualization {

    override val name: String = "Fireworks"
    override val bucket: VizEnergy = VizEnergy.High
    override val post: PostSpec = PostSpec.Off
    override val paintsWholeScreen: Boolean = true

    override val mapping: VizMapping = VizMapping(
        drives = listOf(
            // The page's bin 0, about 0 to 43 Hz: a rise of more than 15 in one 30 ms read fires a burst.
            VizDrive(VizDriver.Bass, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(TYPICAL_LIFETIME_SECONDS)),
            // The size of that rise sets the sparks' speed, so how far the burst spreads, and its lifetime.
            VizDrive(VizDriver.Bass, VizProperty.Size, VizCurve.Scaled, VizResponse.lifetime(TYPICAL_LIFETIME_SECONDS)),
        ),
        silence = VizSilence.Still,
        quality = listOf(VizQualityControl.BoundedPool),
    )

    /** The page's analyser: `fftSize = 1024`, `maxDecibels = -20`, `smoothingTimeConstant = 0.5`. */
    internal val analyser = WebAudioAnalyser(fftSize = 1024, smoothing = 0.5f, minDecibels = -100f, maxDecibels = -20f)

    /** How many 30 ms reads have run since the last reset. */
    internal var ticks: Long = 0L
        private set

    /** How many bursts have been fired since the last reset. */
    internal var fired: Int = 0
        private set

    /** The bursts alive now, oldest first: the page's `Render.scene`. */
    internal val bursts: ArrayList<Burst> = ArrayList()

    private val step = DisplayStep()
    private var random = Random(SEED)

    /** Heard milliseconds since the last reset: the page's `Date.now()`. */
    internal var nowMs: Double = 0.0
        private set

    /** Heard milliseconds not yet spent on 30 ms reads. */
    private var owedMs = 0.0

    // The trigger's `_last`: the previous reading of bin 0, and when the last burst fired.
    private var lastValue = 0
    private var lastBurstMs = Double.NEGATIVE_INFINITY

    /** The camera's angle round the origin, in radians: the page's `Date.now() / 3e4`. */
    internal var orbit: Double = 0.0
        private set

    // The page's 10,000 stars: a direction on the sphere of radius 1000, a grey and a size in CSS pixels.
    private val starX = FloatArray(STARS)
    private val starY = FloatArray(STARS)
    private val starZ = FloatArray(STARS)
    private val starGrey = FloatArray(STARS)
    private val starSize = FloatArray(STARS)
    private val starMesh = TriangleMesh(maxVertices = STAR_BATCH * 4, maxIndices = STAR_BATCH * 6)

    /** One sprite batch per set size. The page's sets hold 100, 200, 500 or 1000 sparks. */
    private val sprites = HashMap<Int, SparkSprites>()

    /** Sets of dead bursts, by size, for the next bursts to reuse. */
    private val spareSets = HashMap<Int, ArrayList<SparkSet>>()

    init {
        makeStars()
    }

    override fun DrawScope.draw(state: VizRenderState) {
        advance(state)
        val light = state.lightScale.coerceIn(0f, 1f)
        drawRect(Color.Black)
        drawStars(light)
    }

    override fun DrawScope.drawFront(state: VizRenderState) {
        advance(state)
        drawBursts(state.lightScale.coerceIn(0f, 1f))
    }

    private fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        val heardSeconds = dt.toDouble() * state.frame.audible
        orbit += heardSeconds * state.motionScale / ORBIT_SECONDS_PER_RADIAN
        nowMs += heardSeconds * 1000.0
        owedMs += heardSeconds * 1000.0
        var reads = 0
        while (owedMs >= READ_MS - 1e-6 && reads < MAX_READS) {
            owedMs -= READ_MS
            reads++
            ticks++
            analyser.read(state.frame)
            listen(analyser.frequencyBytes[0], nowMs - owedMs)
        }
        if (reads == MAX_READS) owedMs = min(owedMs, READ_MS)
        // Explosion.render: a burst older than its lifetime is dropped.
        var index = 0
        while (index < bursts.size) {
            val burst = bursts[index]
            if (nowMs - burst.startMs > burst.lifetimeMs) {
                bursts.removeAt(index)
                recycle(burst)
            } else {
                index++
            }
        }
    }

    /** Jukebox._tick after `getByteFrequencyData`: [value] is bin 0, read at [now] heard milliseconds. */
    internal fun listen(value: Int, now: Double) {
        val delta = value - lastValue
        val timeDiff = now - lastBurstMs
        lastValue = value
        if (timeDiff < HOLD_MS) return
        if (delta > RISE) {
            lastBurstMs = now
            val force = delta / FORCE_DIVISOR
            explode(force, now)
            // "one more!"
            if (force > SECOND_BURST_FORCE) explode(1.0, now)
        }
    }

    /** The Explosion constructor, fired at [now] with the page's [force]. */
    private fun explode(force: Double, now: Double) {
        var push = if (force == 0.0) 0.5 else force
        push = 0.1 + push + 0.3 * random.nextDouble()
        val lifetime = 1500.0 + 1000.0 * push + 1500.0 * random.nextDouble()
        val centreX = 10.0 * (random.nextDouble() - 0.5)
        val centreY = 10.0 * (random.nextDouble() - 0.5)
        val centreZ = 10.0 * (random.nextDouble() - 0.5)
        // mat4.translate, then rotateX and rotateY: the model matrix is T * Rx * Ry.
        val angleX = random.nextDouble() * PI
        val angleY = random.nextDouble() * PI
        val burst = Burst(now, lifetime, centreX, centreY, centreZ, rotationXY(angleX, angleY))
        for (recipe in recipeFor(random.nextDouble())) buildSet(burst, recipe.sphere, push * recipe.forceScale, recipe.amount)
        bursts += burst
        fired++
        while (bursts.size > MAX_BURSTS) recycle(bursts.removeAt(0))
    }

    /** Explosion._buildSet and the ParticleSet constructor: a colour, then one velocity a spark. */
    private fun buildSet(burst: Burst, sphere: Boolean, force: Double, amount: Double) {
        val red = 0.4 + 0.6 * random.nextDouble()
        val green = 0.3 + 0.6 * random.nextDouble()
        val blue = 0.2 + 0.6 * random.nextDouble()
        val count = sparkCount(sphere, amount)
        val set = spareSets[count]?.removeLastOrNull() ?: SparkSet(count)
        set.red = red.toFloat()
        set.green = green.toFloat()
        set.blue = blue.toFloat()
        val r = burst.rotation
        for (spark in 0 until count) {
            val diff = 1.0 + (random.nextDouble() - 0.5) * 0.05
            val scale = force + diff
            val x: Double
            val y: Double
            val z: Double
            if (sphere) {
                // vec3.random: a uniform direction on the sphere.
                val turn = random.nextDouble() * 2.0 * PI
                val height = random.nextDouble() * 2.0 - 1.0
                val across = sqrt(1.0 - height * height) * scale
                x = cos(turn) * across
                y = sin(turn) * across
                z = height * scale
            } else {
                // vec2.random, in the burst's own x-y plane.
                val turn = random.nextDouble() * 2.0 * PI
                x = cos(turn) * scale
                y = sin(turn) * scale
                z = 0.0
            }
            // Turned into the world once, here, rather than by the model matrix every frame.
            set.velocity[spark * 3] = (r[0] * x + r[1] * y + r[2] * z).toFloat()
            set.velocity[spark * 3 + 1] = (r[3] * x + r[4] * y + r[5] * z).toFloat()
            set.velocity[spark * 3 + 2] = (r[6] * x + r[7] * y + r[8] * z).toFloat()
        }
        burst.sets += set
    }

    private fun recycle(burst: Burst) {
        for (set in burst.sets) spareSets.getOrPut(set.count) { ArrayList() } += set
        burst.sets.clear()
    }

    /** Stars._build: `vec3.random(tmp, 1000)`, a grey of 0.2 to 0.7 and a size of 1 to 3 CSS pixels. */
    private fun makeStars() {
        for (star in 0 until STARS) {
            val turn = random.nextDouble() * 2.0 * PI
            val height = random.nextDouble() * 2.0 - 1.0
            val across = sqrt(1.0 - height * height) * STAR_RADIUS
            starX[star] = (cos(turn) * across).toFloat()
            starY[star] = (sin(turn) * across).toFloat()
            starZ[star] = (height * STAR_RADIUS).toFloat()
            starGrey[star] = (0.2 + random.nextDouble() * 0.5).toFloat()
            starSize[star] = (1.0 + 2.0 * random.nextDouble()).toFloat()
        }
    }

    /** The points program: square stars of their own grey, added onto the black clear. */
    private fun DrawScope.drawStars(light: Float) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return
        val view = View(orbit, width / height)
        val dp = density
        starMesh.clear()
        for (star in 0 until STARS) {
            val x = starX[star]
            val y = starY[star]
            val z = starZ[star]
            val viewX = view.sin * x - view.cos * z
            val viewZ = view.cos * x + view.sin * z - CAMERA_RADIUS
            val w = -viewZ
            if (w < NEAR || w > FAR) continue
            val clipX = view.focalX * viewX
            val clipY = view.focal * y
            // WebGL drops a point whose centre is outside the clip volume.
            if (abs(clipX) > w || abs(clipY) > w) continue
            val screenX = (clipX / w * 0.5f + 0.5f) * width
            val screenY = (0.5f - clipY / w * 0.5f) * height
            val half = starSize[star] * dp * 0.5f
            val grey = channel(starGrey[star] * light)
            val argb = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            if (starMesh.vertexCount + 4 > starMesh.maxVertices) {
                drawMesh(starMesh, BlendMode.Plus)
                starMesh.clear()
            }
            val a = starMesh.vertex(screenX - half, screenY - half, argb)
            val b = starMesh.vertex(screenX + half, screenY - half, argb)
            val c = starMesh.vertex(screenX + half, screenY + half, argb)
            val d = starMesh.vertex(screenX - half, screenY + half, argb)
            starMesh.quad(a, b, c, d)
        }
        drawMesh(starMesh, BlendMode.Plus)
    }

    /** The particle set program, one call a set: each spark at its place, size and fade this instant. */
    private fun DrawScope.drawBursts(light: Float) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return
        val view = View(orbit, width / height)
        val dp = density
        for (burst in bursts) {
            val age = nowMs - burst.startMs
            val spread = spreadAt(age).toFloat()
            val sag = sagAt(age)
            // The gravity is added before the model matrix, so it pulls along the burst's own turned y axis.
            val r = burst.rotation
            val originX = (burst.centreX + r[1] * sag).toFloat()
            val originY = (burst.centreY + r[4] * sag).toFloat()
            val originZ = (burst.centreZ + r[7] * sag).toFloat()
            val fade = (1.0 - age / burst.lifetimeMs).toFloat().coerceIn(0f, 1f)
            for (set in burst.sets) {
                val argb = (channel(fade) shl 24) or (channel(set.red * light) shl 16) or
                    (channel(set.green * light) shl 8) or channel(set.blue * light)
                val batch = sprites.getOrPut(set.count) { SparkSprites(set.count) }
                val velocity = set.velocity
                for (spark in 0 until set.count) {
                    val x = originX + spread * velocity[spark * 3]
                    val y = originY + spread * velocity[spark * 3 + 1]
                    val z = originZ + spread * velocity[spark * 3 + 2]
                    val viewX = view.sin * x - view.cos * z
                    val viewZ = view.cos * x + view.sin * z - CAMERA_RADIUS
                    val w = -viewZ
                    val clipX = view.focalX * viewX
                    val clipY = view.focal * y
                    if (w < NEAR || w > FAR || abs(clipX) > w || abs(clipY) > w) {
                        batch.hide(spark)
                        continue
                    }
                    val screenX = (clipX / w * 0.5f + 0.5f) * width
                    val screenY = (0.5f - clipY / w * 0.5f) * height
                    val half = pointSize(viewX * viewX + y * y + viewZ * viewZ + 1f) * dp * 0.5f
                    batch.put(spark, screenX, screenY, half, argb)
                }
                drawSparkSprites(batch)
            }
        }
    }

    override fun reset() {
        step.reset()
        analyser.reset()
        for (burst in bursts) recycle(burst)
        bursts.clear()
        random = Random(SEED)
        makeStars()
        ticks = 0L
        fired = 0
        nowMs = 0.0
        owedMs = 0.0
        lastValue = 0
        lastBurstMs = Double.NEGATIVE_INFINITY
        orbit = 0.0
    }

    /** One Explosion: when it fired, how long it lives, where it is and how it is turned. */
    internal class Burst(
        val startMs: Double,
        val lifetimeMs: Double,
        val centreX: Double,
        val centreY: Double,
        val centreZ: Double,
        /** Rx * Ry, row by row. */
        val rotation: DoubleArray,
    ) {
        val sets: ArrayList<SparkSet> = ArrayList(3)
    }

    /** One ParticleSet: its colour and each spark's velocity, already turned into the world. */
    internal class SparkSet(val count: Int) {
        val velocity: FloatArray = FloatArray(count * 3)
        var red: Float = 0f
        var green: Float = 0f
        var blue: Float = 0f
    }

    /** One set of an explosion's recipe: a sphere or a ring, its force against the burst's, its share of the full count. */
    internal class SetRecipe(val sphere: Boolean, val forceScale: Double, val amount: Double)

    /** The camera this instant: lookAt from (20 cos t, 0, 20 sin t) to the origin, and the 45 degree perspective. */
    private class View(orbit: Double, aspect: Float) {
        val sin = sin(orbit).toFloat()
        val cos = cos(orbit).toFloat()
        val focal = FOCAL
        val focalX = FOCAL / aspect
    }

    internal companion object {
        const val STARS = 10_000
        const val STAR_RADIUS = 1000.0

        /** Stars drawn per call, so every corner number fits the triangle call's 16 bits. */
        const val STAR_BATCH = 4096

        /** The camera circles the origin at this radius. */
        const val CAMERA_RADIUS = 20f

        /** `t = Date.now() / 3e4`: 30 seconds a radian, so a turn takes 188.5 seconds. */
        const val ORBIT_SECONDS_PER_RADIAN = 30.0

        const val NEAR = 0.1f
        const val FAR = 3000f

        /** `1 / tan(fovy / 2)` for the page's 45 degree field of view. */
        val FOCAL: Float = (1.0 / tan(PI / 8.0)).toFloat()

        /** `setInterval(this._tick, 30)`. */
        const val READ_MS = 30.0

        /** At most this many reads in one frame, so a stalled frame does not spin. */
        const val MAX_READS = 10

        /** `_decay`: no burst within 300 ms of the last one. */
        const val HOLD_MS = 300.0

        /** A burst needs bin 0 to rise by more than this in one read. */
        const val RISE = 15

        /** `force = delta / 50`. */
        const val FORCE_DIVISOR = 50.0

        /** Above this force a second, standard burst follows. */
        const val SECOND_BURST_FORCE = 1.1

        /** `uGravity = (0, -1e-7, 0)`, in units per millisecond squared. */
        const val GRAVITY = -1e-7

        /** A ceiling on live bursts; the page's 300 ms hold keeps the count far lower on real songs. */
        const val MAX_BURSTS = 48

        /** A burst of force 1 lives about three seconds. */
        const val TYPICAL_LIFETIME_SECONDS = 3f

        const val SEED = 20_141_222L

        /** How far a spark has flown, in units of its speed, [ageMs] after the burst: `log(1 + 0.02 * age)`. */
        fun spreadAt(ageMs: Double): Double = ln(1.0 + ageMs * 0.02)

        /** The drop under gravity [ageMs] after the burst: `0.5 * age^2 * -1e-7`. */
        fun sagAt(ageMs: Double): Double = 0.5 * ageMs * ageMs * GRAVITY

        /**
         * `gl_PointSize = 7 + 1000 / clamp(d2, 1, 10000)` in CSS pixels, where [distanceSquared] is the
         * squared length of the camera-space position with its w of 1 included.
         */
        fun pointSize(distanceSquared: Float): Float = 7f + 1000f / distanceSquared.coerceIn(1f, 10_000f)

        /** `Math.round((type == "sphere" ? 1000 : 200) * (amount || 1))`. */
        fun sparkCount(sphere: Boolean, amount: Double): Int = floor((if (sphere) 1000.0 else 200.0) * amount + 0.5).toInt()

        /** The switch on `r = Math.random()` in the Explosion constructor. */
        fun recipeFor(r: Double): List<SetRecipe> = when {
            r > 0.7 -> listOf(SetRecipe(true, 1.0, 0.5), SetRecipe(true, 1.0, 0.5))
            r > 0.4 -> listOf(SetRecipe(true, 1.0, 1.0))
            r > 0.35 -> listOf(SetRecipe(false, 1.0, 0.5), SetRecipe(false, 1.0, 0.5))
            r > 0.3 -> listOf(SetRecipe(false, 1.0, 1.0))
            r > 0.2 -> listOf(SetRecipe(false, 0.7, 1.0), SetRecipe(false, 1.2, 1.0))
            r > 0.1 -> listOf(SetRecipe(false, 0.7, 1.0), SetRecipe(false, 1.0, 1.0), SetRecipe(false, 1.3, 1.0))
            r > 0.05 -> listOf(SetRecipe(true, 0.7, 1.0), SetRecipe(false, 1.0, 1.0), SetRecipe(false, 1.3, 1.0))
            else -> listOf(SetRecipe(true, 0.7, 1.0), SetRecipe(false, 1.2, 1.0))
        }

        /** mat4.rotateX then mat4.rotateY on the identity: Rx(a) * Ry(b), row by row. */
        fun rotationXY(angleX: Double, angleY: Double): DoubleArray {
            val sx = sin(angleX)
            val cx = cos(angleX)
            val sy = sin(angleY)
            val cy = cos(angleY)
            return doubleArrayOf(
                cy, 0.0, sy,
                sx * sy, cx, -sx * cy,
                -cx * sy, sx, cx * cy,
            )
        }

        private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
