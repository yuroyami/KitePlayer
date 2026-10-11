package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flow
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A ringed giant planet in black space, bigger than the frame, lit from one side.
 *
 * Its cloud stripes are the spectrum: each stripe of latitude is one band's recent past. The
 * newest column faces the camera and each stripe streams away from it on both sides, so the oldest
 * columns meet behind the planet. A great storm in the south grows and spins with the bass.
 *
 * The weather lives in a cloud field in the same frame: each kick breeds a storm at the facing
 * meridian, in the loudest low stripe, and the storm drifts with its stripe and fades over seconds.
 * Hats light an aurora at the poles on the night side, and the terminator softens as the music
 * grows loud. A ring of 64 ringlets crosses
 * in front, each lit by its band, and the ring and the planet shadow each other. A kick sends a
 * ripple out through the rings, a snare flickers lightning on the night side, and hats make the
 * stars twinkle and the rings glint. The inner moon crosses the planet once a bar.
 *
 * The camera orbits. Each morph eases it to the next of three views, Day side, Ring plane and
 * Night side, over at least one cycle, and turns the orbit the other way. A breakdown drifts into
 * the planet's Shadow. On top of the view the disc moves on a small ellipse at the music's drive,
 * its distance breathes on the slow cycle and its axis tilts with the walk. On a drop the sun rises
 * over the planet's edge, and each birth sends a comet across the sky.
 */
internal class Odyssey : ShaderPreset(
    source = SOURCE,
    name = "Odyssey",
    bucket = VizEnergy.Mid,
    seed = 29f,
    kit = Kit(seed = 2_029L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 2_029)),
) {

    override val mapping: VizMapping by mappingOf(
        // The cloud stripes are the bands' recent past; the ringlets are the bands now.
        VizDrive(VizDriver.Bands, VizProperty.Colour),
        VizDrive(VizDriver.Bands, VizProperty.Brightness),
        // Loud music lights the planet and streams its clouds faster. The bass grows and spins the
        // storm and churns the stripes' edges: fine detail, so it is not declared as a whole-picture change.
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        // A kick breeds a bright storm in the clouds that outlives the ring's ripple. A snare's
        // lightning needs a night spot in view, so it is too rare to declare as a whole-picture change.
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.lifetime(8f)),
        VizDrive(VizDriver.HighHit, VizProperty.Texture, VizCurve.Scaled, VizResponse.lifetime(0.3f)),
        // A new view eases in over a bar, so its first change shows a few frames after the boundary.
        VizDrive(VizDriver.Section, VizProperty.Camera, VizCurve.Discrete, VizResponse.envelope(2f, delaySeconds = 0.15f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Camera, VizCurve.Discrete, VizResponse.envelope(2f, delaySeconds = 0.15f)),
        // The orbit's speed.
        VizDrive(VizDriver.Level, VizProperty.Camera, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Brightness, VizCurve.Discrete, VizResponse.lifetime(1f)),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        silence = VizSilence.Still,
    )

    // Glow only where light is hottest: the atmosphere's rim and the rising sun.
    override val post: PostSpec get() = GLOW

    override val forms: FormReadout get() = FormReadout(FORM_NAMES[viewTo], evolution.morphs, evolution.births)

    private val planetSize = VizParam("Planet size", 0.8f, 1.2f, 1f)
    private val ringLight = VizParam("Ring light", 0.4f, 1.5f, 1f)
    /** The churn of the stripes' edges and the eddies' drift; the stripes themselves stream with the history. */
    private val cloudSpeed = VizParam("Cloud speed", 0f, 2f, 1f)
    private val starLight = VizParam("Stars", 0f, 1.5f, 1f)
    override val params: List<VizParam> = listOf(planetSize, ringLight, cloudSpeed, starLight)

    // The cloud history: one column of stripe levels every eighth of a second of music, in a ring.
    private val history = FloatArray(COLUMNS * STRIPES)
    private val historyImage = PixelImage(COLUMNS, STRIPES)
    private var head = 0
    private var columnCredit = 0f
    private var historyDirty = true
    private val ringlets = FloatArray(RINGLETS)
    private val ringletImage = PixelImage(RINGLETS, 1)
    private val stripeLevels = FloatArray(STRIPES)

    // The view: where the planet sits on screen and how big, its ring axis and the sun, eased from
    // one view to the next over a bar.
    private var viewFrom = 0
    private var viewTo = 0
    private var viewBlend = 1f
    private var viewSeconds = 2f
    private var inBreakdown = false
    private val shown = FloatArray(VIEW_SIZE)
    private val from = FloatArray(VIEW_SIZE)
    private val target = FloatArray(VIEW_SIZE)
    /** The eased view before the orbit is added; a new move starts from here. */
    private val eased = FloatArray(VIEW_SIZE)
    private var lastDay = 0

    // The orbit on top of the view: its phase in turns, its direction, and how present the music is.
    private var orbitPhase = 0f
    private var orbitSign = 1f
    private val presence = Envelope(2f, 0.5f)
    private val tiltX = Slew(TILT_SPEED)
    private val tiltZ = Slew(TILT_SPEED)

    // The comet a birth sends across the sky: its age in cycles, its path, and its head and direction now.
    private var cometAge = LONG_AGO
    private var cometSeed = 0f
    private var comet = 0f
    private val cometHead = FloatArray(4)

    private var cloudPhase = 0f
    private var spin = 0f
    private var stormSize = 1f
    private var stormSpin = 0f
    private var stormLongitude = -0.4f
    /** The bass, eased: it grows and spins the storm and stirs the stripes' edges. */
    private var stir = 0.5f
    private var rippleAge = 99f
    private var rippleStrength = 0f
    private var lightning = 0f
    /** Where on the planet the last flash struck, as a unit vector from its centre. */
    private val flash = FloatArray(3)
    private var sinceLightning = 99f
    private var glint = 0f
    private var sunrise = 1f
    private var flare = 0f
    private var light = IDLE_LIGHT
    private val keyTurn = Slew(maxPerSecond = 20f)
    private val colours = Array(5) { FloatArray(3) }
    private var colourFor: VizPalette? = null
    private var colourTurn = Float.NaN

    // Worked out once a frame for the shader.
    private val planet = FloatArray(4)
    private val axis = FloatArray(3)
    private val east = FloatArray(3)
    private val north = FloatArray(3)
    private val sun = FloatArray(3)
    private val moonA = FloatArray(4)
    private val moonB = FloatArray(4)
    private var aspect = 16f / 9f

    // The weather: a cloud field in the view's frame, x the view longitude and y the latitude.
    private val clouds = MemoryField(rows = FIELD_ROWS).also { it.halfLife = STORM_HALF_LIFE }
    override val field: MemoryField get() = clouds
    private val stormX = FloatArray(STORMS)
    private val stormY = FloatArray(STORMS)
    private val stormSpeed = FloatArray(STORMS)
    private val stormAge = FloatArray(STORMS) { LONG_AGO }
    private val stormDrift = FloatArray(STORMS)

    /** How many storms live now; they sit at the front of the pools. */
    internal var stormCount = 0
        private set
    private val vortices = Flows.Vortices({ stormCount }, stormX, stormY, stormSpeed, core = STORM_CORE)
    private val spin2 = FloatArray(2)

    /**
     * The stream away from the facing meridian, the history's own pace, so a storm stays on its
     * stripe. Near a storm the flow takes the storm's drift, so its ink moves as one piece rather
     * than tearing along the stripes it spans.
     */
    private val cloudFlow = Flow { x, y, a, out ->
        var nearest = -1
        var best = Float.MAX_VALUE
        for (i in 0 until stormCount) {
            val dx = x - stormX[i]
            val dy = y - stormY[i]
            val squared = dx * dx + dy * dy
            if (squared < best) {
                best = squared
                nearest = i
            }
        }
        val stream = if (x < 0f) -STREAM else STREAM
        var flowX = stream
        if (nearest >= 0) {
            val hold = exp(-best / (2f * STORM_HOLD * STORM_HOLD))
            flowX = stream * (1f - hold) + stormDrift[nearest] * hold
        }
        vortices.at(x, y, a, spin2)
        out[0] = flowX + spin2[0]
        out[1] = spin2[1]
    }
    private var aurora = 0f
    private var faceLon = 0f
    private var softness = SOFT_QUIET

    init {
        views(0, shown)
        views(0, eased)
        startingClouds()
    }

    /** Clouds and ringlets at a gentle middle level, so the planet shows its colours from the first frame. */
    private fun startingClouds() {
        for (stripe in 0 until STRIPES) for (column in 0 until COLUMNS) {
            history[stripe * COLUMNS + column] = 0.35f + 0.2f * sin(stripe * 1.7f + column * 0.13f)
        }
        ringlets.fill(0.3f)
        for (stripe in 0 until STRIPES) stripeLevels[stripe] = history[stripe * COLUMNS]
        historyDirty = true
    }

    /** Which view the planet is in or heading to, for tests. */
    internal val view: Int get() = viewTo

    /** How far the sun has risen after a drop, 0 to 1, for tests. */
    internal val risen: Float get() = sunrise

    /** Cloud stripe levels written since the start, for tests. */
    internal var columns = 0L
        private set

    /** Each ringlet's light now, 0 to 1, for tests. */
    internal fun ringletLevels(): FloatArray = ringlets.copyOf()

    /** Each stripe's newest level, 0 to 1, for tests. */
    internal fun newestStripes(): FloatArray = FloatArray(STRIPES) { history[it * COLUMNS + head] }

    /** The aurora's strength, 0 to 1. */
    internal val auroraValue: Float get() = aurora

    /** Where storm [i] sits in the cloud field, in field units across. */
    internal fun stormX(i: Int): Float = stormX[i]

    /** The share of the cloud field's cells holding ink above a tenth. */
    internal fun cloudInkShare(): Float {
        var lit = 0
        var count = 0
        var y = -0.95f
        while (y < 1f) {
            var x = -FIELD_ASPECT + 0.05f
            while (x < FIELD_ASPECT) {
                count++
                if (clouds.inkAt(x, y) > 0.1f) lit++
                x += 0.1f
            }
            y += 0.1f
        }
        return if (count == 0) 0f else lit.toFloat() / count
    }

    /** The ink weighted mean of x over the cloud field, 0 when it is empty. */
    internal fun cloudInkCentroidX(): Float {
        var total = 0f
        var weighted = 0f
        for (row in 0 until clouds.rows) for (column in 0 until clouds.columns) {
            val x = clouds.xOf(column)
            val ink = clouds.inkAt(x, clouds.yOf(row))
            total += ink
            weighted += ink * x
        }
        return if (total <= 0f) 0f else weighted / total
    }

    /** The disc's nearest point on a [width] by [height] picture, in pixels. */
    internal fun facingPixel(width: Float, height: Float): Pair<Float, Float> =
        Pair(width / 2f + shown[0] * aspect * height / 2f, height / 2f - shown[1] * height / 2f)

    /** The disc's radius on a picture [height] pixels tall. */
    internal fun planetRadiusPixels(height: Float): Float = shown[2] * min(1f, aspect) * planetSize.value * height / 2f

    /** Stands the camera in view [index] at once, with no move. */
    internal fun jumpTo(index: Int) {
        viewFrom = index
        viewTo = index
        views(index, shown)
        views(index, from)
        views(index, eased)
        viewBlend = 1f
    }

    internal val orbitSignValue: Float get() = orbitSign
    internal val orbitPhaseValue: Float get() = orbitPhase
    internal val cometValue: Float get() = comet
    internal val lightValue: Float get() = light

    /** Entry [i] of the view as drawn: 0 and 1 place the disc, 2 is its size. */
    internal fun shownValue(i: Int): Float = shown[i]

    /** Sends a comet across the sky, as the music's own birth does. */
    internal fun birth() {
        cometAge = 0f
        cometSeed = random.next()
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        val step = state.stepSeconds
        // Reduced motion slows the camera moves and every turn of the planet, the storm and the clouds.
        val pace = 0.3f + 0.7f * state.motionScale.coerceIn(0f, 1f)
        aspect = kit.aspect
        // The light follows the level; the idle share stands only while nothing is heard.
        light = state.lightScale.coerceIn(0f, 1f) * max(IDLE_LIGHT * (1f - frame.audible), lightFor(frame.energy))
        updateColours(state, step)

        // The forms: one frame can carry several events, so they are read in one chain. A morph eases
        // to the next day view and turns the orbit; a breakdown drifts into the planet's shadow until
        // the next turn or drop.
        if (evolution.bloom) {
            inBreakdown = false
            moveTo(nextDay())
        } else if (evolution.collapse) {
            if (!inBreakdown) {
                inBreakdown = true
                moveTo(BREAKDOWN_VIEW)
            }
        } else if (inBreakdown && (gestures.turn || gestures.surge)) {
            inBreakdown = false
            moveTo(nextDay())
        } else if (evolution.morph) {
            orbitSign = -orbitSign
            moveTo(nextDay())
        }
        if (evolution.birth) birth()
        if (viewBlend < 1f) viewBlend = (viewBlend + step * pace / viewSeconds).coerceAtMost(1f)
        // A pause holds the orbit where it is; a silence lets the presence fall and the orbit settle home.
        presence.advance(frame.audible, if (frame.held) 0f else state.deltaSeconds)
        orbitPhase += step * pace * ORBIT_RATE * (0.3f + 0.7f * state.drive) * orbitSign
        // The walk is not wrapped, so it is read only through a sine; the tilt follows it on heard time.
        tiltX.advance(ORBIT_TILT * sin(genes.walk * TAU), step)
        tiltZ.advance(ORBIT_TILT * cos(genes.walk * TAU), step)
        blendViews(presence.value * state.motionScale.coerceIn(0f, 1f))

        cometAge += step / gestures.cycleSeconds.coerceAtLeast(0.5f)
        comet = if (cometAge < 1f) 1f - cometAge else 0f
        if (comet > 0f) {
            val startY = 0.9f - 0.6f * cometSeed
            val endY = 0.3f - 0.6f * cometSeed
            cometHead[0] = -aspect + 2f * aspect * cometAge
            cometHead[1] = startY + (endY - startY) * cometAge
            val dx = 2f * aspect
            val dy = endY - startY
            val length = sqrt(dx * dx + dy * dy)
            cometHead[2] = dx / length
            cometHead[3] = dy / length
        }

        // On a drop the sun rises over the planet's edge: its light runs across the clouds and
        // the rings over one beat, after a short flare over the rim.
        if (gestures.surge) {
            sunrise = 0f
            flare = 1f
        }
        if (sunrise < 1f) sunrise = (sunrise + step / gestures.beatSeconds.coerceAtLeast(0.15f)).coerceAtMost(1f)
        // When the audio ahead already holds the drop, the sun sets behind the planet over the last
        // bar, so the drop only has to bring it up again.
        val upcoming = state.future?.nextEvent(AudioEventKind.Drop)
        val bar = gestures.cycleSeconds.coerceAtLeast(0.5f)
        if (upcoming != null && upcoming.secondsUntil <= bar) sunrise = min(sunrise, (upcoming.secondsUntil / bar).coerceIn(0f, 1f))
        flare *= exp(-step / 0.35f)

        // The clouds stream at the pace of the music, and nothing moves in a silence.
        cloudPhase += step * pace * (0.01f + 0.12f * frame.energy) * cloudSpeed.value
        spin += step * pace * 0.012f
        stormSize += ((0.6f + 1.4f * frame.bass) - stormSize) * (1f - exp(-step / 0.3f))
        stir += (frame.bass - stir) * (1f - exp(-step / 0.3f))
        stormSpin += step * pace * (0.6f + 2.4f * frame.bass)
        stormLongitude += step * pace * 0.02f

        recordHistory(frame.bands, step)
        readRinglets(frame.bands, step)
        clouds.size(FIELD_ASPECT)
        followWeather(state, step)

        // A kick sends a bright ripple outward through the rings.
        if (gestures.kick > 0f) {
            rippleAge = 0f
            rippleStrength = gestures.kick.coerceIn(0f, 1f)
        }
        rippleAge += step
        // A snare flickers lightning on the night side, two a second at most.
        sinceLightning += step
        if (gestures.snare > 0f && sinceLightning >= 0.5f && nightSpot()) {
            sinceLightning = 0f
            lightning = gestures.snare.coerceIn(0.4f, 1f)
        }
        lightning *= exp(-step / 0.12f)
        if (gestures.hat > 0f) glint = max(glint, gestures.hat)
        glint *= exp(-step / 0.25f)

        placeScene(state)
    }

    /** The cloud field on heard time: it streams, a kick breeds a storm, and the storms drift, slow and die. */
    private fun followWeather(state: VizRenderState, step: Float) {
        val frame = state.frame
        clouds.advance(cloudFlow, step)
        if (gestures.kick > 0f && frame.audible > 0f) breedStorm(gestures.kick.coerceIn(0f, 1f))
        val slowing = 2f.pow(-step / STORM_SLOWING)
        var i = 0
        while (i < stormCount) {
            stormX[i] += stormDrift[i] * step
            stormAge[i] += step
            stormSpeed[i] *= slowing
            if (stormAge[i] > STORM_LIFE || abs(stormX[i]) > FIELD_ASPECT) {
                // Behind the planet or spent: the last live storm takes this slot.
                val last = stormCount - 1
                stormX[i] = stormX[last]; stormY[i] = stormY[last]; stormSpeed[i] = stormSpeed[last]
                stormAge[i] = stormAge[last]; stormDrift[i] = stormDrift[last]
                stormAge[last] = LONG_AGO; stormSpeed[last] = 0f
                stormCount--
            } else {
                i++
            }
        }
        if (gestures.hat > 0f) aurora = max(aurora, gestures.hat.coerceIn(0f, 1f))
        aurora *= exp(-step / AURORA_SECONDS)
        softness = SOFT_QUIET + SOFT_LOUD * frame.energy.coerceIn(0f, 1f)
    }

    /** A storm at the facing meridian, in the loudest low stripe, north or south. */
    private fun breedStorm(kick: Float) {
        var loudest = 0
        for (stripe in 1 until STRIPES / 4) if (stripeLevels[stripe] > stripeLevels[loudest]) loudest = stripe
        val side = if (random.next() < 0.5f) 1f else -1f
        val y = side * (loudest + 0.5f) / STRIPES
        // The shader streams even stripes one way and odd ones the other.
        val way = if (loudest % 2 == 0) 1f else -1f
        clouds.disc(0f, y, STORM_RADIUS + 0.1f * kick, 0.9f)
        clouds.markChanged()
        var slot = stormCount
        if (slot >= STORMS) {
            slot = 0
            for (other in 1 until STORMS) if (stormAge[other] > stormAge[slot]) slot = other
        } else {
            stormCount++
        }
        stormX[slot] = 0f
        stormY[slot] = y
        stormSpeed[slot] = STORM_SPIN * kick
        stormAge[slot] = 0f
        stormDrift[slot] = way * STREAM
    }

    /** Starts the move to view [index] from wherever the eased view is now. */
    private fun moveTo(index: Int) {
        eased.copyInto(from)
        viewFrom = viewTo
        viewTo = index
        viewBlend = 0f
        // At least a cycle, and never under three seconds: the Shadow view is far, and a faster ease would cut.
        viewSeconds = gestures.cycleSeconds.coerceIn(3f, 8f)
    }

    /** The next of the three day views, in order. */
    private fun nextDay(): Int {
        lastDay = (lastDay + 1) % 3
        return lastDay
    }

    /** Eases from the last view to the target, then adds the orbit, scaled by [orbit]. */
    private fun blendViews(orbit: Float) {
        views(viewTo, target)
        val t = viewBlend * viewBlend * (3f - 2f * viewBlend)
        for (i in 0 until VIEW_SIZE) eased[i] = from[i] + (target[i] - from[i]) * t
        if (viewBlend >= 1f) target.copyInto(from)
        eased.copyInto(shown)
        // The disc moves on a small ellipse, its distance breathes on the slow cycle, and its axis tilts with the walk.
        shown[0] += ORBIT_X * cos(orbitPhase * TAU) * orbit
        shown[1] += ORBIT_Y * sin(orbitPhase * TAU) * orbit
        shown[2] *= 1f + ORBIT_DISTANCE * sin(gestures.slowCyclePhase * TAU) * orbit
        shown[3] += tiltX.value
        shown[5] += tiltZ.value
    }

    /** A column of stripe levels every eighth of a second of music: the stripes' recent past. */
    private fun recordHistory(bands: FloatArray, step: Float) {
        // Each stripe eases toward its band, so the clouds flow rather than step from column to column.
        val ease = 1f - exp(-step / 0.2f)
        for (stripe in 0 until STRIPES) {
            val level = bandMean(bands, stripe, STRIPES)
            val t = ((level - 0.04f) / 0.45f).coerceIn(0f, 1f)
            stripeLevels[stripe] += (t * t * (3f - 2f * t) - stripeLevels[stripe]) * ease
        }
        columnCredit += step * COLUMNS_PER_SECOND
        while (columnCredit >= 1f) {
            columnCredit -= 1f
            head = (head + 1) % COLUMNS
            for (stripe in 0 until STRIPES) history[stripe * COLUMNS + head] = stripeLevels[stripe]
            columns++
            historyDirty = true
        }
    }

    /** The ringlets follow the bands now, each easing a little so a single frame does not flicker. */
    private fun readRinglets(bands: FloatArray, step: Float) {
        val ease = 1f - exp(-step / 0.08f)
        for (ringlet in 0 until RINGLETS) {
            val level = bandMean(bands, ringlet, RINGLETS)
            val t = ((level - 0.03f) / 0.45f).coerceIn(0f, 1f)
            ringlets[ringlet] += (t - ringlets[ringlet]) * ease
        }
    }

    private fun bandMean(bands: FloatArray, index: Int, count: Int): Float {
        if (bands.isEmpty()) return 0f
        val first = (index * bands.size / count).coerceAtMost(bands.size - 1)
        val last = ((index + 1) * bands.size / count).coerceIn(first + 1, bands.size)
        var sum = 0f
        for (band in first until last) sum += bands[band]
        return sum / (last - first)
    }

    /**
     * A spot for the next flash: a point of the planet's disc that is inside the frame and on the
     * night side. A few random tries across the disc; with none that fits, this snare stays dark.
     */
    private fun nightSpot(): Boolean {
        val r = planet[3]
        val cx = shown[0] * aspect
        val cy = shown[1]
        val rho = shown[2] * min(1f, aspect) * planetSize.value
        for (attempt in 0 until 24) {
            val angle = random.next() * 2f * PI.toFloat()
            val reach = sqrt(random.next()) * rho * 0.97f
            val px = cx + cos(angle) * reach
            val py = cy + sin(angle) * reach
            if (abs(px) > aspect * 0.95f || abs(py) > 0.95f) continue
            val l = sqrt(px * px + py * py + FOCAL * FOCAL)
            val dx = px / l; val dy = py / l; val dz = -FOCAL / l
            // Where the line of sight through that point meets the planet.
            val b = -(dx * planet[0] + dy * planet[1] + dz * planet[2])
            val c = planet[0] * planet[0] + planet[1] * planet[1] + planet[2] * planet[2] - r * r
            val h = b * b - c
            if (h < 0f) continue
            val t = -b - sqrt(h)
            if (t <= 0f) continue
            val nx = (dx * t - planet[0]) / r; val ny = (dy * t - planet[1]) / r; val nz = (dz * t - planet[2]) / r
            if (nx * sun[0] + ny * sun[1] + nz * sun[2] > -0.08f) continue
            flash[0] = nx; flash[1] = ny; flash[2] = nz
            return true
        }
        return false
    }

    /**
     * Places the planet, its axis, the sun and the moons for this frame. The planet is placed by
     * where its disc should sit on screen and how big, so it can overhang the frame edge.
     */
    private fun placeScene(state: VizRenderState) {
        val cx = shown[0] * aspect
        val cy = shown[1]
        val rho = shown[2] * min(1f, aspect) * planetSize.value
        // The disc's centre direction and its angular size give its place in space.
        val length = sqrt(cx * cx + cy * cy + FOCAL * FOCAL)
        planet[0] = cx / length * DISTANCE
        planet[1] = cy / length * DISTANCE
        planet[2] = -FOCAL / length * DISTANCE
        planet[3] = DISTANCE * rho / sqrt(rho * rho + FOCAL * FOCAL)
        normalizeInto(shown[3], shown[4], shown[5], axis)
        // The planet's zero longitude, square to its axis. It does not spin: the stripes are time, not surface.
        val refX = 0f; val refY = 0f; val refZ = 1f
        val dot = refX * axis[0] + refY * axis[1] + refZ * axis[2]
        normalizeInto(refX - dot * axis[0], refY - dot * axis[1], refZ - dot * axis[2], east)
        north[0] = axis[1] * east[2] - axis[2] * east[1]
        north[1] = axis[2] * east[0] - axis[0] * east[2]
        north[2] = axis[0] * east[1] - axis[1] * east[0]
        // The longitude that faces the camera, which sits at the origin: there the history is now.
        val toX = -planet[0] / DISTANCE; val toY = -planet[1] / DISTANCE; val toZ = -planet[2] / DISTANCE
        faceLon = atan2(toX * north[0] + toY * north[1] + toZ * north[2], toX * east[0] + toY * east[1] + toZ * east[2])
        // The sun: this view's, swung round from behind the planet while it rises after a drop.
        val rise = sunrise * sunrise * (3f - 2f * sunrise)
        val hiddenX = planet[0] / DISTANCE; val hiddenY = planet[1] / DISTANCE; val hiddenZ = planet[2] / DISTANCE
        normalizeInto(hiddenX + (shown[6] - hiddenX) * rise, hiddenY + (shown[7] - hiddenY) * rise,
            hiddenZ + (shown[8] - hiddenZ) * rise, sun)
        // The inner moon goes round once a bar in the ring plane; the outer one takes much longer.
        val bar = (gestures.cycles + gestures.cyclePhase) * 2f * PI.toFloat()
        moon(bar + 1.2f, 2.8f, 0.07f, moonA)
        moon(bar * 0.29f + 3.9f, 4.4f, 0.05f, moonB)
    }

    private fun moon(angle: Float, orbit: Float, size: Float, into: FloatArray) {
        // The orbit's own axes in the ring plane, fixed rather than spinning with the clouds.
        val refDot = axis[2]
        val ux0 = -refDot * axis[0]; val uy0 = -refDot * axis[1]; val uz0 = 1f - refDot * axis[2]
        val ul = sqrt(ux0 * ux0 + uy0 * uy0 + uz0 * uz0).coerceAtLeast(1e-4f)
        val ux = ux0 / ul; val uy = uy0 / ul; val uz = uz0 / ul
        val vx = axis[1] * uz - axis[2] * uy; val vy = axis[2] * ux - axis[0] * uz; val vz = axis[0] * uy - axis[1] * ux
        val r = planet[3] * orbit
        into[0] = planet[0] + r * (cos(angle) * ux + sin(angle) * vx)
        into[1] = planet[1] + r * (cos(angle) * uy + sin(angle) * vy)
        into[2] = planet[2] + r * (cos(angle) * uz + sin(angle) * vz)
        into[3] = planet[3] * size
    }

    private fun normalizeInto(x: Float, y: Float, z: Float, into: FloatArray) {
        val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        into[0] = x / l; into[1] = y / l; into[2] = z / l
    }

    /**
     * The views: where the planet's disc sits (in screen half-heights, across as a share of the
     * half-width), its size, its ring axis and the sun's direction.
     */
    private fun views(index: Int, into: FloatArray) {
        val v = VIEWS[index.coerceIn(0, VIEWS.size - 1)]
        v.copyInto(into)
    }

    private fun updateColours(state: VizRenderState, step: Float) {
        val frame = state.frame
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - CORAL_HUE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyTurn.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, step)
        val degrees = kotlin.math.round(keyTurn.value)
        val palette = state.palette
        if (palette === colourFor && degrees == colourTurn) return
        colourFor = palette
        colourTurn = degrees
        if (palette.name == VizPalette.Prism.name) {
            swatch(0.35f, 0.16f, 320f + degrees).into(colours[0])
            swatch(0.65f, 0.21f, CORAL_HUE + degrees).into(colours[1])
            swatch(0.84f, 0.17f, 85f + degrees).into(colours[2])
            swatch(0.92f, 0.06f, 210f).into(colours[3])
            swatch(0.86f, 0.14f, 205f).into(colours[4])
        } else {
            palette.vividRamp(0.1f).into(colours[0])
            palette.vividRamp(0.5f).into(colours[1])
            palette.vividRamp(0.9f).into(colours[2])
            // The ring is the palette's brightest end, paled as ice is; the rim its middle.
            palette.vividRamp(0.95f).into(colours[3])
            for (c in 0..2) colours[3][c] = colours[3][c] * 0.6f + 0.35f
            palette.vividRamp(0.7f).into(colours[4])
        }
    }

    private fun swatch(lightness: Float, chroma: Float, hue: Float): Color =
        colourOf(lightness, min(chroma, mostChroma(lightness, hue) - 0.004f).coerceAtLeast(0f), hue)

    private fun Color.into(target: FloatArray) {
        target[0] = red; target[1] = green; target[2] = blue
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        if (historyDirty) {
            for (stripe in 0 until STRIPES) for (column in 0 until COLUMNS) {
                val byte = (history[stripe * COLUMNS + column] * 255f + 0.5f).toInt()
                historyImage.pixels[stripe * COLUMNS + column] = (0xFF shl 24) or (byte shl 16)
            }
            historyImage.upload()
            historyDirty = false
        }
        for (ringlet in 0 until RINGLETS) {
            val byte = (ringlets[ringlet].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            ringletImage.pixels[ringlet] = (0xFF shl 24) or (byte shl 16)
        }
        ringletImage.upload()
        program.child("uHistory", historyImage.image, tiled = true)
        program.child("uRinglets", ringletImage.image)
        program.uniform("uPlanet", planet[0], planet[1], planet[2], planet[3])
        program.uniform("uAxis", axis[0], axis[1], axis[2])
        program.uniform("uEast", east[0], east[1], east[2])
        program.uniform("uNorth", north[0], north[1], north[2])
        program.uniform("uSun", sun[0], sun[1], sun[2], NIGHT)
        // The churn's phase, and the great storm, the one thing on the planet that shows its spin.
        program.uniform("uFlow", cloudPhase, stormLongitude + spin, stormSize, stormSpin)
        program.uniform("uWeather", aurora, faceLon, softness, comet)
        program.uniform("uComet", cometHead[0], cometHead[1], cometHead[2], cometHead[3])
        val rippleRadius = RING_IN + rippleAge * 1.8f
        program.uniform("uRing", rippleRadius, if (rippleRadius > RING_OUT + 0.3f) 0f else rippleStrength, glint, stir)
        program.uniform("uFlash", flash[0], flash[1], flash[2], lightning)
        program.uniform("uMoonA", moonA[0], moonA[1], moonA[2], moonA[3])
        program.uniform("uMoonB", moonB[0], moonB[1], moonB[2], moonB[3])
        // The head moves smoothly between columns, so the clouds glide rather than step each eighth of a second.
        program.uniform("uLook", light, glint, head + columnCredit, FOCAL)
        program.uniform("uTune", ringLight.value, starLight.value)
        // The sun rises over the limb in the direction of the view's own sun.
        val sx = shown[6]; val sy = shown[7]
        val reach = sqrt(sx * sx + sy * sy).coerceAtLeast(1e-4f)
        val radius = shown[2] * min(1f, aspect) * planetSize.value
        program.uniform("uScreen", shown[0] * aspect + sx / reach * radius, shown[1] + sy / reach * radius, radius, flare)
        program.uniform("uCloud0", colours[0][0], colours[0][1], colours[0][2])
        program.uniform("uCloud1", colours[1][0], colours[1][1], colours[1][2])
        program.uniform("uCloud2", colours[2][0], colours[2][1], colours[2][2])
        program.uniform("uIce", colours[3][0], colours[3][1], colours[3][2])
        program.uniform("uRim", colours[4][0], colours[4][1], colours[4][2])
    }

    override fun onReset() {
        head = 0
        columnCredit = 0f
        startingClouds()
        viewFrom = 0; viewTo = 0; viewBlend = 1f; inBreakdown = false
        views(0, shown); views(0, from); views(0, eased)
        lastDay = 0; orbitPhase = 0f; orbitSign = 1f; presence.reset(); tiltX.reset(); tiltZ.reset()
        cometAge = LONG_AGO; cometSeed = 0f; comet = 0f; cometHead.fill(0f)
        cloudPhase = 0f; spin = 0f; stormSize = 1f; stormSpin = 0f; stormLongitude = -0.4f; stir = 0.5f
        rippleAge = 99f; rippleStrength = 0f
        lightning = 0f; sinceLightning = 99f; glint = 0f; flash.fill(0f)
        sunrise = 1f; flare = 0f
        light = IDLE_LIGHT
        keyTurn.reset(); colourFor = null
        columns = 0L
        clouds.clear()
        stormCount = 0
        stormAge.fill(LONG_AGO); stormSpeed.fill(0f)
        aurora = 0f; faceLon = 0f; softness = SOFT_QUIET
    }

    internal companion object {
        const val COLUMNS = 96
        const val STRIPES = 24
        const val RINGLETS = 64
        const val COLUMNS_PER_SECOND = 8f
        const val RING_IN = 1.3f
        const val RING_OUT = 2.35f
        const val FOCAL = 1.8f
        const val DISTANCE = 12f
        const val VIEW_SIZE = 9
        const val BREAKDOWN_VIEW = 3
        /** The share of full light the planet keeps in a silence, and the night side's lift from ringshine. */
        const val IDLE_LIGHT = 0.35f
        const val NIGHT = 0.045f

        // The cloud field: 96 by 48 cells over the view longitude and the latitude, and how long its ink lasts.
        const val FIELD_ROWS = 48
        const val FIELD_ASPECT = 2f
        const val STORM_HALF_LIFE = 6f
        /** The stream in field units a heard second: the facing meridian to the back in the history's twelve seconds. */
        const val STREAM = 2f * COLUMNS_PER_SECOND / COLUMNS
        const val STORMS = 6
        const val STORM_CORE = 0.12f
        /** How far round a storm its drift holds the flow, in field units. */
        const val STORM_HOLD = 0.3f
        const val STORM_LIFE = 12f
        const val STORM_RADIUS = 0.12f
        const val STORM_SPIN = 0.6f
        /** Seconds for a storm's spin to halve. */
        const val STORM_SLOWING = 4f
        const val LONG_AGO = 99f
        const val AURORA_SECONDS = 0.6f

        /** The form named by each row of [VIEWS]; the three day views run from the most lit to the least. */
        val FORM_NAMES = arrayOf("Day side", "Ring plane", "Night side", "Shadow")

        // The orbit: turns a heard second at full drive, the ellipse's reach, the breathing of the distance, the axis tilt.
        const val ORBIT_RATE = 0.05f
        const val ORBIT_X = 0.06f
        const val ORBIT_Y = 0.04f
        const val ORBIT_DISTANCE = 0.06f
        const val ORBIT_TILT = 0.12f
        /** How fast the tilt follows the walk: about twice the walk's own fastest pace. */
        const val TILT_SPEED = 0.02f
        /** The terminator's softness in quiet music, and what loud music adds. */
        const val SOFT_QUIET = 0.02f
        const val SOFT_LOUD = 0.10f
        private const val KEY_TURN = 30f
        private const val CORAL_HUE = 30f

        private val GLOW = PostSpec(bloom = 0.35f, bloomRadius = 0.025f, threshold = 0.86f, vignette = 0.15f,
            grain = 0f, glitch = false, aberration = 0f)

        /**
         * Screen place across (a share of the half-width) and up (half-heights, up positive), the
         * disc's radius in shorter half-sides, the ring axis, and the direction to the sun. The
         * planet always overhangs the frame by about a third, in landscape and in portrait. The
         * axis points down, so the south, where the storm spins, is the visible top of the disc.
         * Each sun is set against the planet's own direction from the viewer, which is off to the
         * lower right, so the terminator crosses the part of the disc the frame shows.
         */
        val VIEWS: Array<FloatArray> = arrayOf(
            // Just under the ring plane, so the ring's near half crosses the visible top of the
            // planet; lit from the upper left and half lit, so the terminator crosses the visible disc.
            floatArrayOf(0.45f, -0.6f, 1.05f, -0.3f, -0.9f, 0.3f, -0.7f, 0.55f, -0.46f),
            // Nearly edge on, the ring a thin bright line across the planet; half lit from the upper
            // right, so the night side shows lower left.
            floatArrayOf(0.42f, -0.62f, 1.05f, -0.1f, -0.99f, 0.08f, 0.7f, 0.7f, 0.05f),
            // From the night side: the sun behind and to the left, a crescent on the visible limb.
            floatArrayOf(0.45f, -0.6f, 1.05f, 0.25f, -0.9f, 0.3f, -0.6f, 0.35f, -0.75f),
            // A breakdown: drifted into the shadow, the night side filling the frame with a thin
            // crescent on its right limb.
            floatArrayOf(0.05f, -0.55f, 1.5f, -0.18f, -0.94f, 0.26f, 0.6f, 0.3f, -0.74f),
        )

        const val SOURCE: String = """
uniform float4 uPlanet;   // centre, radius
uniform float3 uAxis;     // the ring and spin axis
uniform float3 uEast;     // zero longitude
uniform float3 uNorth;
uniform float4 uSun;      // direction to the sun, night-side lift
uniform float4 uFlow;     // the churn's phase, storm longitude, storm size, storm spin
uniform float4 uWeather;  // aurora, the longitude facing the camera, the terminator's softness, comet
uniform float4 uComet;    // the comet's head on screen and its direction
uniform float4 uRing;     // ripple radius, ripple strength, glint, the bass that stirs the clouds
uniform float4 uFlash;    // where the lightning strikes, as a unit vector from the centre, and its strength
uniform float4 uMoonA;
uniform float4 uMoonB;
uniform float4 uLook;     // light, star twinkle, history head, focal length
uniform float2 uTune;     // the viewer's ring light and star light
uniform float4 uScreen;   // where the sun rises over the limb on screen, the disc's radius, and the flare
uniform float3 uCloud0;
uniform float3 uCloud1;
uniform float3 uCloud2;
uniform float3 uIce;
uniform float3 uRim;
uniform shader uHistory;  // 96 columns of time by 24 stripes, wrapped
uniform shader uRinglets; // 64 ringlets

const float COLUMNS = 96.0;
const float STRIPES = 24.0;
const float RINGLETS = 64.0;
const float RING_IN = 1.3;
const float RING_OUT = 2.35;

// The nearest point ahead where a ray meets a sphere, or -1.
float sphereHit(float3 o, float3 d, float4 s) {
    float3 oc = o - s.xyz;
    float b = dot(oc, d);
    float h = b * b - (dot(oc, oc) - s.w * s.w);
    if (h < 0.0) return -1.0;
    float t = -b - sqrt(h);
    return t > 0.0 ? t : -1.0;
}

float wrapAngle(float a) {
    return a - 6.2831853 * floor((a + 3.1415927) / 6.2831853);
}

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

// How much of the sun the ring blocks at a radius (in planet radii), with its dark gaps between
// ringlets; [blur] is how many ringlets one pixel covers, so thin ringlets average out.
float ringCover(float r, float blur) {
    if (r < RING_IN || r > RING_OUT) return 0.0;
    float u = (r - RING_IN) / (RING_OUT - RING_IN) * RINGLETS;
    float f = fract(u);
    float body = smoothstep(0.0, 0.14, f) * (1.0 - smoothstep(0.8, 0.98, f));
    body = mix(body, 0.72, clamp(blur * 2.0 - 0.5, 0.0, 1.0));
    float edge = smoothstep(RING_IN, RING_IN + 0.05, r) * (1.0 - smoothstep(RING_OUT - 0.1, RING_OUT, r));
    return body * edge * 0.9;
}

half4 main(float2 position) {
    float2 p = centred(position);
    p.y = -p.y;
    float pixel = 2.0 / uResolution.y;
    float3 d = normalize(float3(p, -uLook.w));
    float3 S = uPlanet.xyz;
    float R = uPlanet.w;
    float3 L = uSun.xyz;
    float3 N = uAxis;

    // Black space, with sparse sharp stars that twinkle on the hats.
    float2 cell = floor(position / 3.0);
    float star = step(0.9965, hash(cell)) * step(length(fract(position / 3.0) - 0.5), 0.34);
    float3 colour = float3(star * (0.35 + 0.4 * hash(cell + 7.0)) * (1.0 + 1.6 * uLook.y * step(0.5, hash(cell + 3.0))) * uTune.y);

    // A birth's comet: a thin tail behind a bright head. The planet, drawn after it, hides it.
    if (uWeather.w > 0.001) {
        float behind = dot(p - uComet.xy, -uComet.zw);
        float2 nearest = uComet.xy - uComet.zw * clamp(behind, 0.0, 0.5);
        float dc = length(p - nearest);
        colour += float3(0.9, 0.95, 1.0) * uWeather.w * exp(-dc / 0.006) * (1.0 - clamp(behind, 0.0, 0.5) / 0.5);
    }

    float tP = sphereHit(float3(0.0), d, uPlanet);
    float tA = sphereHit(float3(0.0), d, uMoonA);
    float tB = sphereHit(float3(0.0), d, uMoonB);

    // The planet: clouds lit from one side with a hard terminator.
    if (tP > 0.0) {
        float3 X = d * tP;
        float3 n = (X - S) / R;
        float lat = asin(clamp(dot(n, N), -1.0, 1.0));
        float lon = atan(dot(n, uNorth), dot(n, uEast));
        // Each stripe of latitude is one band, bass at the equator, mirrored north and south.
        float u = abs(lat) / 1.5707963 * STRIPES;
        // The stripes' edges churn, deeper when the bass is strong.
        u += (0.15 + 0.5 * uRing.w) * sin(lon * 9.0 + lat * 23.0 + uFlow.x * 4.0);
        float stripe = clamp(floor(u), 0.0, STRIPES - 1.0);
        float way = mod(stripe, 2.0) < 1.0 ? 1.0 : -1.0;
        // "Now" is the longitude facing the camera; each stripe streams away from it on both sides.
        float lonV = wrapAngle(lon - uWeather.y);
        float age = clamp(abs(lonV) / 3.1415927 + way * 0.035 * sin(lat * 41.0 + lon * 3.0 + uFlow.x * 6.0), 0.0, 1.0);
        float v = uHistory.eval(float2(uLook.z - age * (COLUMNS - 2.0) + 0.5, stripe + 0.5)).r;
        float3 cloud = v < 0.5 ? mix(uCloud0, uCloud1, v * 2.0) : mix(uCloud1, uCloud2, v * 2.0 - 1.0);
        cloud *= 0.45 + 0.7 * v;
        float fu = fract(u);
        cloud *= 0.78 + 0.22 * smoothstep(0.0, 0.1, min(fu, 1.0 - fu));
        // The cloud field: storms as bright swirls with slope lighting, drifting with the stripes.
        float2 fc = float2((lonV / 6.2831853 + 0.5) * uFieldSize.x, (lat / 3.1415927 + 0.5) * uFieldSize.y);
        float ink = uField.eval(fc).r;
        float2 slope = float2(uField.eval(fc + float2(1.0, 0.0)).r - uField.eval(fc - float2(1.0, 0.0)).r,
                              uField.eval(fc + float2(0.0, 1.0)).r - uField.eval(fc - float2(0.0, 1.0)).r) * 0.5;
        float shade = clamp(0.5 + 0.5 * dot(normalize(float3(-slope.x * 6.0, -slope.y * 6.0, 1.0)), normalize(float3(-0.5, -0.6, 0.7))), 0.0, 1.0);
        cloud = mix(cloud, mix(uCloud2, float3(1.0), 0.5), ink) * (0.7 + 0.6 * shade * ink + 0.3 * (1.0 - ink));
        // The great storm in the south, as big and as fast as the bass.
        float2 st = float2(wrapAngle(lon - uFlow.y) * cos(lat), lat + 0.5);
        float2 e = st / (float2(0.3, 0.17) * uFlow.z);
        float sr = length(e);
        if (sr < 1.0) {
            float swirl = 0.5 + 0.5 * sin(atan(e.y, e.x) * 2.0 + uFlow.w + sr * 7.0);
            float3 storm = mix(uCloud1, uCloud2, swirl) * (0.8 + 0.35 * uFlow.z - 0.5 * sr) + float3(0.3) * (1.0 - smoothstep(0.0, 0.3, sr));
            cloud = mix(cloud, storm, 1.0 - smoothstep(0.82, 1.0, sr));
        }
        float ndl = dot(n, L);
        // The terminator softens as the music grows loud.
        float soft = uWeather.z;
        float sunlit = smoothstep(-soft, soft * 1.5, ndl) * (0.2 + 0.8 * max(ndl, 0.0));
        float warm = smoothstep(-soft * 0.5, soft, ndl) * (1.0 - smoothstep(soft, soft * 4.0, ndl));
        // The ring's shadow on the clouds, and the inner moon's.
        float toRing = dot(S - X, N) / dot(L, N);
        // The shadow is the ringlets' average: their fine gaps would only shimmer across the clouds.
        if (toRing > 0.0) sunlit *= 1.0 - 0.85 * ringCover(length(X + L * toRing - S) / R, 1.0);
        if (sphereHit(X, L, uMoonA) > 0.0) sunlit *= 0.04;
        float3 surface = cloud * sunlit + mix(uCloud1, uCloud2, 0.4) * warm * 0.35;
        // The night side, lifted by light off the rings so the dark disc reads against space.
        surface += (cloud * 0.6 + uIce * 0.4) * uSun.w * (1.0 - smoothstep(-0.05, 0.1, ndl));
        // A snare's lightning, small and local, only where it is night.
        float away = length(n - uFlash.xyz);
        surface += float3(0.85, 0.9, 1.0) * uFlash.w * (exp(-away / 0.05) + 0.35 * exp(-away / 0.18))
            * (1.0 - smoothstep(-0.05, 0.1, ndl));
        // Aurora at the poles on the hats, on the night side only.
        float polar = smoothstep(1.1, 1.45, abs(lat));
        float curtain = 0.5 + 0.5 * sin(lon * 11.0 + uFlow.x * 7.0 + lat * 5.0);
        surface += mix(uIce, uCloud2, 0.5) * uWeather.x * polar * curtain * 0.9 * (1.0 - smoothstep(-0.05, 0.1, ndl));
        // The atmosphere's rim, on the lit edge only.
        float edgeOn = 1.0 - max(dot(n, -d), 0.0);
        surface += uRim * pow(edgeOn, 4.0) * smoothstep(-0.15, 0.35, ndl) * 0.9;
        colour = surface;
    }

    // The ring crosses in front of the planet or passes behind it.
    float dn = dot(d, N);
    float tR = abs(dn) > 0.0001 ? dot(S, N) / dn : -1.0;
    if (tR > 0.0 && (tP < 0.0 || tR < tP)) {
        float3 H = d * tR;
        float r = length(H - S) / R;
        float blur = tR * pixel / uLook.w / max(abs(dn), 0.03) / R * RINGLETS / (RING_OUT - RING_IN);
        float cover = ringCover(r, blur);
        if (cover > 0.0) {
            float u = (r - RING_IN) / (RING_OUT - RING_IN) * RINGLETS;
            float level = uRinglets.eval(float2(floor(u) + 0.5, 0.5)).r;
            float3 ring = uIce * (0.35 + 0.7 * level) * (0.75 + 0.25 * abs(dot(L, N))) * uTune.x;
            // The planet's shadow across the ring.
            if (sphereHit(H, L, uPlanet) > 0.0) ring *= 0.07;
            // A kick's ripple running outward, and the glint of a hat.
            ring += uIce * uRing.y * exp(-abs(r - uRing.x) * 9.0);
            float along = atan(dot(H - S, uNorth), dot(H - S, uEast));
            ring += float3(uRing.z) * step(0.97, hash(float2(floor(u), floor(along * 40.0))));
            colour = mix(colour, ring, cover);
        }
    }

    // The two small moons, lit by the same sun.
    float tMoon = tA > 0.0 && (tB < 0.0 || tA < tB) ? tA : tB;
    float4 moon = tA > 0.0 && (tB < 0.0 || tA < tB) ? uMoonA : uMoonB;
    if (tMoon > 0.0 && (tP < 0.0 || tMoon < tP) && (tR < 0.0 || tMoon < tR)) {
        float3 mn = (d * tMoon - moon.xyz) / moon.w;
        colour = float3(0.62, 0.6, 0.66) * (smoothstep(-0.05, 0.1, dot(mn, L)) * max(dot(mn, L), 0.0) + 0.04);
    }

    // The sunrise: a white star bursts over the rim where the sun comes up, with a short flare.
    if (uScreen.w > 0.001) {
        float2 q = (p - uScreen.xy) / uScreen.w;
        float burst = exp(-length(q) / 0.035) + 0.35 * exp(-length(q) / 0.16)
            + 0.6 * exp(-abs(q.x) / 0.005) * exp(-abs(q.y) / 0.3) + 0.6 * exp(-abs(q.y) / 0.005) * exp(-abs(q.x) / 0.3);
        colour += float3(1.0, 0.97, 0.9) * burst * uScreen.w;
    }

    return half4(clamp(colour * uLook.x, 0.0, 1.0), 1.0);
}
"""
    }
}
