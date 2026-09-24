package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WaveformResampler
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.retentionOf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A kaleidoscope whose seed is the stereo image: the middle of the two channels runs along each wedge
 * and their difference across it. Mono draws a clean white star, and a wide mix opens it into a lacy
 * snowflake coloured like the lenses of 3D glasses: magenta where the left is louder, cyan where the
 * right is, white where they agree. How busy the music is sets the mirror count on the first beat of a
 * bar, a kick punches the figure out, snares throw shards off the tips and hats sparkle there, and a
 * breakdown opens the fold to the raw figure. A drop splits the figure into its magenta and cyan
 * images, which slide back together over a bar and meet in white.
 */
internal class Kaleidoscope : Layered(
    name = "Kaleidoscope",
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 504L,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 504),
    ),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Width, VizProperty.Shape),
        // How busy the music is picks the mirror count, which changes on the first beat of a bar.
        VizDrive(VizDriver.Mood, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.05f, delaySeconds = 2f)),
        // The mood also sets how long the afterimages last.
        VizDrive(VizDriver.Mood, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.25f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.7f)),
        VizDrive(VizDriver.HighHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.45f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.05f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.05f)),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        silence = VizSilence.Still,
    )
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.93f, livelyTrail = 0.88f, calmZoom = 0.99f, livelyZoom = 0.985f)

    // The camera holds still, so the figure itself is what answers the music.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    // The echo layer is laid on black, so the ground stays black under any palette.
    override val paintsWholeScreen: Boolean get() = true

    // The glow and the afterimages are the drawing's own; a finishing bloom would haze the black.
    override val post: PostSpec get() = PostSpec.Off

    /** A breakdown keeps the afterimages far longer, so the open figure builds up into a picture of the mix. */
    override fun trailAt(mood: Float): Float = if (open) OPEN_TRAIL else moodSpec.trail(mood)

    private val resampler = WaveformResampler()
    private val coarseLeft = FloatArray(DETAIL)
    private val coarseRight = FloatArray(DETAIL)
    private val left = FloatArray(SEED)
    private val right = FloatArray(SEED)

    // The seed, folded to the outer side of the centre: shares of the radius along the wedge and
    // across it, the line's own normal, and its colour from -1 (cyan) through 0 (white) to 1 (magenta).
    private val along = FloatArray(SEED)
    private val across = FloatArray(SEED)
    private val normalAlong = FloatArray(SEED)
    private val normalAcross = FloatArray(SEED)
    private val tone = FloatArray(SEED)
    private val distance = FloatArray(SEED)
    // Which half of the trace a point folded from. The line breaks where the trace crosses over.
    private val folded = BooleanArray(SEED)
    private var tip = SEED - 1
    private var fatness = 0f

    private var chosen = START_MIRRORS

    /** How many times the seed repeats round the centre this frame. */
    internal var mirrors: Int = START_MIRRORS
        private set

    /** True on a frame that counts as the first beat of a bar, the only frames the mirror count changes on. */
    internal var firstBeat: Boolean = false
        private set

    /** How far each image of the drop sits off the middle, as a share of the width. */
    internal var apart: Float = 0f
        private set

    /** The figure's radius as a share of the shorter side, with the kick's punch. */
    internal var reach: Float = RADIUS
        private set

    private var started = false
    private var lastCycles = 0
    private var open = false
    private var openTurns = 0
    private var splitting = false
    private var splitFrom = 0f
    private var splitTo = 0
    private var meet = 0f
    private var afterglow = 0f
    private var build = 0f
    private var cyanDim = 0f
    private val anaglyph: Boolean get() = splitting || build > 0.01f

    private val punch = Spring(stiffness = PUNCH_STIFFNESS, damping = PUNCH_DAMPING)
    private val particles = Particles(POOL)
    private val figure = TriangleMesh(maxVertices = BATCH * SEED * 4, maxIndices = BATCH * (SEED - 1) * 18)
    private val sparks = TriangleMesh(maxVertices = POOL * 10, maxIndices = POOL * 24)

    init {
        resetSeed()
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        // A paused player keeps the last picture, so nothing of the drawing's own moves on, although
        // the shared bar clock keeps turning.
        val live = !frame.held
        val dt = if (live) state.deltaSeconds else 0f
        val heard = frame.audible >= HEARD
        if (heard) {
            readSeed(frame)
            fatness = frame.width
        }
        val wrapped = gestures.cycles != lastCycles
        lastCycles = gestures.cycles
        val bar = gestures.cycles + gestures.cyclePhase
        if (gestures.breakdown) {
            open = true
            openTurns = gestures.turns
            splitting = false
        } else if (open && live && gestures.turns != openTurns) {
            open = false
        }
        if (gestures.surge) {
            open = false
            splitting = true
            splitFrom = bar
            // A drop late in a bar meets on the first beat after next, so the slide always takes most of a bar.
            splitTo = gestures.cycles + if (gestures.cyclePhase > 0.5f) 2 else 1
        }
        if (splitting && live && gestures.cycles >= splitTo) {
            splitting = false
            afterglow = 1f
        }
        // An accepted boundary is a first beat as well, since that is where a new section starts.
        firstBeat = !started || wrapped || gestures.section || gestures.surge
        if (heard && firstBeat) chosen = mirrorsFor(frame.density, chosen)
        started = true
        mirrors = when {
            splitting -> DROP_MIRRORS
            open -> OPEN_MIRRORS
            else -> chosen
        }
        if (live) signature(state, bar, dt)
        punch.kick(gestures.kick * PUNCH_KICK)
        punch.advance(dt)
        reach = RADIUS * (1f + PUNCH * punch.value.coerceIn(-0.5f, 1.5f) * state.motionScale)
        spawn()
        particles.advance(dt, drag = DRAG)
        val wide = minOf(1f, 1f / kit.aspect)
        val tall = minOf(1f, kit.aspect)
        kit.place(0, 0.5f, 0.5f, parallax = 0f)
        kit.place(1, 0.5f - across[tip] * reach * wide, 0.5f - along[tip] * reach * tall, parallax = 0f)
    }

    /**
     * Reads the stereo trace into the seed. Each point is the middle of the two channels and their
     * difference, turned to the outer side of the centre, so every wedge holds a whole figure. The
     * figure is scaled to its own furthest point: loudness shows as light, and the shape is the mix.
     *
     * The trace is cut to its lower frequencies first. Cymbals and noise make a raw trace scribble
     * back and forth at every sample, and mirrored a dozen times that fills the figure solid white.
     * Below about 2 kHz it draws loops, which is the lace.
     */
    private fun readSeed(frame: SpectrumFrame) {
        val lefts = frame.scopeLeft
        val rights = frame.scopeRight
        if (lefts.size < 2 || rights.size != lefts.size) return
        resampler.resample(lefts, coarseLeft)
        resampler.resample(rights, coarseRight)
        curve(coarseLeft, left)
        curve(coarseRight, right)
        var furthest = 0f
        for (index in 0 until SEED) {
            val middle = left[index] + right[index]
            val side = left[index] - right[index]
            furthest = maxOf(furthest, middle * middle + side * side)
        }
        val peak = sqrt(furthest) * 0.5f
        if (!(peak > QUIET_TRACE)) return
        val scale = 0.5f / peak
        var outermost = -1f
        for (index in 0 until SEED) {
            val l = left[index]
            val r = right[index]
            var middle = (l + r) * scale
            var side = (l - r) * scale
            val below = middle < 0f
            if (below) {
                middle = -middle
                side = -side
            }
            along[index] = middle
            across[index] = side
            folded[index] = below
            val out = middle * middle + side * side
            distance[index] = sqrt(out)
            if (out > outermost) {
                outermost = out
                tip = index
            }
            // Left-heavy points are magenta and right-heavy ones cyan. Near the hub a point is too
            // small for its balance to mean anything, so it stays white.
            val sum = abs(l) + abs(r)
            val balance = if (sum > 0f) (abs(l) - abs(r)) / sum else 0f
            val strength = smooth(COLOUR_FROM, COLOUR_TO, abs(balance)) * smooth(0f, HUB, distance[index])
            tone[index] = if (balance >= 0f) strength else -strength
        }
        for (index in 0 until SEED) {
            val first = index == 0 || folded[index] != folded[index - 1]
            val last = index == SEED - 1 || folded[index + 1] != folded[index]
            val from = if (first) index else index - 1
            val to = if (last) index else index + 1
            val stepAlong = along[to] - along[from]
            val stepAcross = across[to] - across[from]
            val length = sqrt(stepAlong * stepAlong + stepAcross * stepAcross)
            if (length < 1e-6f) {
                normalAlong[index] = 0f
                normalAcross[index] = 1f
            } else {
                normalAlong[index] = -stepAcross / length
                normalAcross[index] = stepAlong / length
            }
        }
    }

    // A smooth curve through the band-limited points, back at the seed's full count, so the lines have no corners.
    private fun curve(points: FloatArray, into: FloatArray) {
        val last = points.size - 1
        for (index in into.indices) {
            val at = index * last.toFloat() / (into.size - 1)
            val step = at.toInt().coerceAtMost(last - 1)
            val t = at - step
            val p0 = points[(step - 1).coerceAtLeast(0)]
            val p1 = points[step]
            val p2 = points[step + 1]
            val p3 = points[(step + 2).coerceAtMost(last)]
            into[index] = 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                (3f * p1 - p0 - 3f * p2 + p3) * t * t * t)
        }
    }

    // Before anything has been heard the seed is a mono line, so the first frame already shows a star.
    private fun resetSeed() {
        for (index in 0 until SEED) {
            along[index] = index / (SEED - 1f)
            across[index] = 0f
            distance[index] = along[index]
            normalAlong[index] = 0f
            normalAcross[index] = 1f
            tone[index] = 0f
            folded[index] = false
        }
        tip = SEED - 1
    }

    // Four, six, eight or twelve mirrors as the music gets busier, with a margin so a density that
    // sits on an edge does not flip the count every bar.
    private fun mirrorsFor(density: Float, current: Int): Int {
        val at = COUNTS.indexOf(current).coerceAtLeast(0)
        val wanted = stepOf(density)
        val next = when {
            wanted > at -> maxOf(at, stepOf(density - HYSTERESIS))
            wanted < at -> minOf(at, stepOf(density + HYSTERESIS))
            else -> at
        }
        return COUNTS[next]
    }

    private fun stepOf(density: Float): Int {
        var step = 0
        for (edge in EDGES) if (density >= edge) step++
        return step
    }

    // The drop: two images a quarter of the frame apart that slide back together over a bar.
    private fun signature(state: VizRenderState, bar: Float, dt: Float) {
        val progress = if (splitting) ((bar - splitFrom) / (splitTo - splitFrom)).coerceIn(0f, 1f) else 1f
        val eased = progress * progress * (3f - 2f * progress)
        // Where the queued audio already holds the drop, the images start to part before it lands.
        val coming = if (splitting) null else state.future?.nextEvent(AudioEventKind.Drop)
        val approach = coming?.let { (1f - it.secondsUntil / BUILD_SECONDS).coerceIn(0f, 1f) } ?: 0f
        build = if (approach > build) approach else (build - dt * BUILD_FALL).coerceAtLeast(0f)
        val spread = if (splitting) 1f - eased else BUILD_SHARE * build
        apart = SPLIT * spread * state.motionScale
        meet = if (splitting) smooth(MEET_FROM, 1f, progress) else 0f
        // Under reduced motion the images barely part, so the drop shows as colour: the figure turns
        // magenta and whitens again as the cyan image comes back.
        cyanDim = if (splitting) (1f - state.motionScale) * COLOUR_SIGNATURE * (1f - eased) else 0f
        afterglow = (afterglow - dt / (0.5f * gestures.cycleSeconds)).coerceAtLeast(0f)
    }

    // Shards on the snare and sparkle on the hats, at the tip of every mirror.
    private fun spawn() {
        val shards = gestures.snareSpawn(SHARDS)
        val sparkles = gestures.hatSpawn(SPARKLES)
        if (shards == 0 && sparkles == 0) return
        val wide = minOf(1f, 1f / kit.aspect)
        val tall = minOf(1f, kit.aspect)
        for (mirror in 0 until mirrors) {
            val theta = TAU * mirror / mirrors
            val flip = flipOf(mirror, mirrors)
            val outX = along[tip] * sin(theta) - across[tip] * cos(theta) * flip
            val outY = -along[tip] * cos(theta) - across[tip] * sin(theta) * flip
            // During the drop the tips alternate between the two images.
            val image = if (!anaglyph) 0f else if (mirror % 2 == 0) -apart else apart
            val colour = if (!anaglyph) tone[tip] else if (mirror % 2 == 0) 1f else -1f
            val x = 0.5f + image + outX * reach * wide
            val y = 0.5f + outY * reach * tall
            val outward = atan2(outY, outX)
            repeat(shards) {
                val angle = outward + (random.next() - 0.5f) * SHARD_SPREAD
                val speed = SHARD_SPEED * (0.6f + 0.8f * random.next())
                particles.spawn(
                    atX = x, atY = y, speedX = cos(angle) * speed * wide, speedY = sin(angle) * speed * tall,
                    seconds = SHARD_LIFE * (0.7f + 0.6f * random.next()), tintPosition = colour,
                    radius = SHARD_SIZE * (0.7f + 0.6f * random.next()), spin = random.signed() * 5f,
                    kind = SHARD, angle = angle,
                )
            }
            // A sparkle sits just past the tip, where the line does not already fill it with light.
            val beyondX = 0.5f + image + outX * reach * wide * BEYOND
            val beyondY = 0.5f + outY * reach * tall * BEYOND
            repeat(sparkles) {
                particles.spawn(
                    atX = beyondX + random.signed() * JITTER * wide, atY = beyondY + random.signed() * JITTER * tall,
                    speedX = 0f, speedY = 0f, seconds = SPARKLE_LIFE * (0.7f + 0.6f * random.next()),
                    tintPosition = colour, radius = SPARKLE_SIZE * (0.6f + 0.8f * random.next()), kind = SPARKLE,
                )
            }
        }
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        // Black goes under what came back from the last frame, never over it.
        drawRect(Color.Black, blendMode = BlendMode.DstOver)
        val light = lightOf(state)
        // Scaled by what the trail keeps, so an afterimage settles at the same light however long it lasts.
        val fresh = 1f - retentionOf(trailAt(state.mood), state.deltaSeconds)
        val unit = (size.minDimension / UNIT_SIDE).coerceAtLeast(1f)
        drawImages(GLOW_CORE * unit, (GLOW_FEATHER + GLOW_WIDEN * fatness) * unit, GLOW * fresh * light, glow = true)
        drawSparks(PARTICLE_GLOW * fresh * light, echo = true)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val light = lightOf(state)
        drawImages(LINE, FEATHER, light, glow = false)
        drawSparks(light, echo = false)
    }

    // A floor of 35 percent, so a silence and the first frame still show the figure, over the shared light.
    private fun lightOf(state: VizRenderState): Float =
        (IDLE_LIGHT * state.lightScale + (1f - IDLE_LIGHT) * state.lift).coerceIn(0f, 1f)

    private fun DrawScope.drawImages(core: Float, feather: Float, alpha: Float, glow: Boolean) {
        if (!anaglyph) {
            drawFigure(0f, NORMAL, core, feather, alpha, glow)
            return
        }
        // The two images each carry half the light as they meet, so together they come out white.
        val shift = apart * size.width
        val each = alpha * (1f - 0.5f * meet)
        drawFigure(-shift, MAGENTA_IMAGE, core, feather, each, glow)
        drawFigure(shift, CYAN_IMAGE, core, feather, each * (1f - cyanDim), glow)
    }

    /**
     * The seed repeated round the centre, every other copy mirrored so the seams meet: a magenta edge
     * and a cyan edge on each wedge round a white spine. Each line is a flat core with soft edges, so it
     * stays sharp without stair steps, and it adds its light to what is under it.
     *
     * The glow copy for the echo layer lights only the outer part. Every mirror crosses the hub, and
     * afterimages of a figure that changes each frame pile up there into a white disc.
     */
    private fun DrawScope.drawFigure(shift: Float, image: Int, core: Float, feather: Float, alpha: Float, glow: Boolean) {
        if (alpha <= 0f) return
        val centreX = size.width * 0.5f + shift
        val centreY = size.height * 0.5f
        val radius = size.minDimension * reach
        val inner = core * 0.5f
        val outer = inner + feather
        val fixed = when (image) {
            MAGENTA_IMAGE -> mix(MAGENTA, WHITE, meet)
            CYAN_IMAGE -> mix(CYAN, WHITE, meet)
            else -> WHITE
        }
        figure.clear()
        for (mirror in 0 until mirrors) {
            if (figure.vertexCount + SEED * 4 > figure.maxVertices) {
                drawMesh(figure, BlendMode.Plus)
                figure.clear()
            }
            val theta = TAU * mirror / mirrors
            val flip = flipOf(mirror, mirrors)
            val alongX = sin(theta)
            val alongY = -cos(theta)
            val acrossX = -cos(theta) * flip
            val acrossY = -sin(theta) * flip
            var previous = -1
            for (index in 0 until SEED) {
                val u = along[index]
                val v = across[index]
                val x = centreX + radius * (u * alongX + v * acrossX)
                val y = centreY + radius * (u * alongY + v * acrossY)
                val normalX = normalAlong[index] * alongX + normalAcross[index] * acrossX
                val normalY = normalAlong[index] * alongY + normalAcross[index] * acrossY
                val code = tone[index]
                val rgb: Int
                val weight: Float
                when (image) {
                    NORMAL -> {
                        rgb = colourAt(code * (1f - afterglow))
                        weight = 1f
                    }
                    // Each lens passes the points that lean its way and the centred ones.
                    MAGENTA_IMAGE -> {
                        rgb = fixed
                        weight = lens(if (code >= 0f) 1f else 1f + code)
                    }
                    else -> {
                        rgb = fixed
                        weight = lens(if (code <= 0f) 1f else 1f - code)
                    }
                }
                val halo = if (glow) smooth(GLOW_FROM, GLOW_FROM + GLOW_RAMP, distance[index]) else 1f
                val lit = argb(alpha * weight * halo, rgb)
                // The same colour with no alpha, so the edge fades out rather than stepping.
                val first = figure.vertex(x - normalX * outer, y - normalY * outer, rgb)
                figure.vertex(x - normalX * inner, y - normalY * inner, lit)
                figure.vertex(x + normalX * inner, y + normalY * inner, lit)
                figure.vertex(x + normalX * outer, y + normalY * outer, rgb)
                if (previous >= 0 && folded[index] == folded[index - 1]) {
                    figure.quad(previous, previous + 1, first + 1, first)
                    figure.quad(previous + 1, previous + 2, first + 2, first + 1)
                    figure.quad(previous + 2, previous + 3, first + 3, first + 2)
                }
                previous = first
            }
        }
        drawMesh(figure, BlendMode.Plus)
    }

    // As the two images meet, both lenses open to every point, so the meeting comes out whole.
    private fun lens(weight: Float): Float = weight + (1f - weight) * meet

    // The echo keeps a halo round each sparkle. Shards stay out of it: their afterimages smear into grey.
    private fun DrawScope.drawSparks(alpha: Float, echo: Boolean) {
        if (alpha <= 0f) return
        sparks.clear()
        val unit = size.minDimension
        for (slot in 0 until particles.capacity) {
            val life = particles.remaining(slot)
            if (life <= 0f || (echo && particles.kind[slot] == SHARD)) continue
            val x = particles.x[slot] * size.width
            val y = particles.y[slot] * size.height
            val rgb = colourAt(particles.tint[slot])
            val length = particles.size[slot] * unit
            // Both hold their full light for the first part of their life and then fade; the alpha is
            // that fade, not a see-through fill.
            if (particles.kind[slot] == SHARD) {
                sliver(x, y, length, particles.angle[slot], argb(alpha * (life * 2f).coerceAtMost(1f), rgb))
            } else {
                val grown = length * (0.6f + 0.4f * life)
                sparks.spark(x, y, grown, (grown * SPARKLE_WIDTH).coerceAtLeast(0.7f), argb(alpha * (life * 1.6f).coerceAtMost(1f), rgb))
            }
        }
        drawMesh(sparks, BlendMode.Plus)
    }

    // A shard of glass: a slim triangle in one flat colour, point first along [turn].
    private fun sliver(x: Float, y: Float, length: Float, turn: Float, argb: Int) {
        val c = cos(turn)
        val s = sin(turn)
        val wide = length * SLIVER
        val point = sparks.vertex(x + c * length * 0.6f, y + s * length * 0.6f, argb)
        val left = sparks.vertex(x - c * length * 0.4f - s * wide, y - s * length * 0.4f + c * wide, argb)
        val right = sparks.vertex(x - c * length * 0.4f + s * wide, y - s * length * 0.4f - c * wide, argb)
        sparks.triangle(point, left, right)
    }

    override fun onReset() {
        resetSeed()
        fatness = 0f
        chosen = START_MIRRORS
        mirrors = START_MIRRORS
        firstBeat = false
        apart = 0f
        reach = RADIUS
        started = false
        lastCycles = 0
        open = false
        openTurns = 0
        splitting = false
        splitFrom = 0f
        splitTo = 0
        meet = 0f
        afterglow = 0f
        build = 0f
        cyanDim = 0f
        punch.reset()
        particles.clear()
    }

    private companion object {
        /** Points in the seed, the stereo trace's own length. */
        const val SEED = 512

        /** Points the trace is band-limited to before the curve is drawn back through [SEED]: about 2 kHz. */
        const val DETAIL = 48

        /** Mirrors built into one batch of the mesh, which holds at most 32 767 corners. */
        const val BATCH = 8

        /** The figure's radius as a share of the shorter side, so it is 90 percent of it across. */
        const val RADIUS = 0.45f
        const val PUNCH = 0.08f
        const val PUNCH_STIFFNESS = 220f
        const val PUNCH_DAMPING = 0.5f

        /** The push that takes the spring to a peak of one, so a full-strength kick punches out [PUNCH]. */
        const val PUNCH_KICK = 35f

        const val START_MIRRORS = 6
        const val DROP_MIRRORS = 16
        const val OPEN_MIRRORS = 2
        val COUNTS = intArrayOf(4, 6, 8, 12)

        /** Densities where the count steps up, with a margin of [HYSTERESIS] either way. *Judgement.* */
        val EDGES = floatArrayOf(0.25f, 0.5f, 0.75f)
        const val HYSTERESIS = 0.05f

        /** How audible a frame must be for its trace to become the seed. Below it the figure holds. */
        const val HEARD = 0.5f
        const val QUIET_TRACE = 1e-5f
        const val IDLE_LIGHT = 0.35f
        const val OPEN_TRAIL = 0.975f

        /** The line's solid core and its soft edge, in pixels. */
        const val LINE = 2f
        const val FEATHER = 0.75f

        /** The glow is sized in pixels for this shorter side and grows with larger screens. */
        const val UNIT_SIDE = 540f
        const val GLOW = 0.45f
        const val GLOW_FROM = 0.3f
        const val GLOW_RAMP = 0.4f
        const val GLOW_CORE = 1f
        const val GLOW_FEATHER = 2.5f
        const val GLOW_WIDEN = 6f
        const val PARTICLE_GLOW = 1.2f

        /** How far each image of the drop moves, as a share of the width: a quarter of it apart. */
        const val SPLIT = 0.125f
        const val BUILD_SECONDS = 0.4f
        const val BUILD_FALL = 3f
        const val BUILD_SHARE = 0.15f
        const val MEET_FROM = 0.7f
        const val COLOUR_SIGNATURE = 0.85f

        /** How far a point must lean left or right before it takes colour, and where it is fully coloured. */
        const val COLOUR_FROM = 0.06f
        const val COLOUR_TO = 0.45f
        const val HUB = 0.12f

        const val POOL = 400
        const val SHARD = 0
        const val SPARKLE = 1
        const val SHARDS = 3
        const val SPARKLES = 3
        const val SHARD_SPEED = 0.35f
        const val SHARD_SPREAD = 0.7f
        const val SHARD_LIFE = 0.7f
        const val SHARD_SIZE = 0.085f
        const val SPARKLE_LIFE = 0.45f
        const val SPARKLE_SIZE = 0.055f
        const val SPARKLE_WIDTH = 0.3f
        const val SLIVER = 0.16f
        const val BEYOND = 1.05f
        const val JITTER = 0.012f
        const val DRAG = 0.6f

        const val NORMAL = 0
        const val MAGENTA_IMAGE = 1
        const val CYAN_IMAGE = 2

        const val WHITE = 0xFFFFFF

        /** The magenta and cyan swatches, packed without alpha. These colours are the idea, so no palette changes them. */
        val MAGENTA = colourOf(0.64f, 0.26f, 350f).toArgb() and 0xFFFFFF
        val CYAN = colourOf(0.86f, 0.14f, 205f).toArgb() and 0xFFFFFF

        const val TONES = 65
        val TONE_TABLE = IntArray(TONES) { step ->
            val code = step * 2f / (TONES - 1) - 1f
            mix(WHITE, if (code >= 0f) MAGENTA else CYAN, abs(code))
        }

        fun colourAt(code: Float): Int =
            TONE_TABLE[((code.coerceIn(-1f, 1f) + 1f) * 0.5f * (TONES - 1) + 0.5f).toInt()]

        fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or rgb

        fun mix(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            val red = (from shr 16 and 0xFF) + ((to shr 16 and 0xFF) - (from shr 16 and 0xFF)) * t
            val green = (from shr 8 and 0xFF) + ((to shr 8 and 0xFF) - (from shr 8 and 0xFF)) * t
            val blue = (from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t
            return ((red + 0.5f).toInt() shl 16) or ((green + 0.5f).toInt() shl 8) or (blue + 0.5f).toInt()
        }

        fun smooth(from: Float, to: Float, value: Float): Float {
            val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Every other copy is mirrored, except with two, where the second is the first turned round: the raw figure. */
        fun flipOf(mirror: Int, count: Int): Float = if (count == OPEN_MIRRORS || mirror % 2 == 0) 1f else -1f
    }
}
