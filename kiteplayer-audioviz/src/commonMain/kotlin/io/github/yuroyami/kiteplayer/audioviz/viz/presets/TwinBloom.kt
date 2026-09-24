package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.AnticipatedImpulse
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.vividLightness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two flowers made of the spectrum circle each other like a binary star: the low half in magenta on
 * violet, the high half in gold on burnt orange, each three rings of elements round a white core.
 * Every element is one band, drawn as one outline that can be a spoke, a petal or a disc. The mood
 * picks the shape and a section steps it on, and each change runs ring by ring on the beat, so for
 * a few beats a figure wears two shapes. A kick shoots, opens or swells the elements, a snare breaks
 * a few off, a hat makes tips sparkle, and a drop throws every element as a disc to the frame edges.
 */
internal class TwinBloom : Layered(
    name = "Twin Bloom",
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 164L,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 164),
    ),
) {

    // The camera holds still: the music moves the figures, not the view.
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    // No grain, no tear and no general glow. The only glow is drawn here, on the cores and the hottest tips.
    override val post: PostSpec get() = PostSpec.Off

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Size),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.6f, delaySeconds = 0.2f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.6f, delaySeconds = 0.2f)),
        VizDrive(VizDriver.Width, VizProperty.Shape, response = VizResponse.envelope(0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f, delaySeconds = 0.2f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(2f)),
    )

    private val tilt = genes.number("Orbit tilt", 0.3f, 0.6f, 0.45f)
    private val backwards = genes.toggle("Turn backwards", start = false)
    private val stagger = genes.number("Ring stagger", 0.25f, 0.5f, 0.33f)
    private val coreSize = genes.number("Core size", 0.1f, 0.15f, 0.12f)

    private val punch = Spring(stiffness = 260f, damping = 0.42f)
    private val impulse = AnticipatedImpulse(punch)

    // The screen in shorter sides: the long side is 1.78 on a 16:9 screen, either way up.
    private var screenWide = 16f / 9f
    private var screenTall = 1f
    private var perRing = 8

    private var breath = 0f
    private var orbitTarget = START_TURN
    private var orbitShown = START_TURN
    private var lastBeat = -1
    private var beatsSinceChange = LOCK_BEATS
    private var shape = PETAL
    private var pendingStep = false
    private var cascades = 0
    private var stereo = 0f
    private var inBreakdown = false
    private var breakdownBeats = 0
    private var budSide = 1f
    private var budding = 0f
    private var throwAge = 1f
    private var throwMotion = 0f
    private var flare = 0f
    private var colourSurge = 0f
    private var buildUp = 0f
    private var snareTurn = 0

    // Each ring's change of shape: the weights it started from, where it is heading, how far it has
    // come, and how many beats it still waits before it starts.
    private val fromWeight = FloatArray(FIGURES * RINGS * SHAPES)
    private val target = IntArray(FIGURES * RINGS) { PETAL }
    private val morph = FloatArray(FIGURES * RINGS) { 1f }
    private val wait = IntArray(FIGURES * RINGS)

    // Every element as it stands this frame, in shorter sides of the screen, and its colours.
    private val regrow = FloatArray(SLOTS) { 1f }
    private val rootX = FloatArray(SLOTS)
    private val rootY = FloatArray(SLOTS)
    private val angle = FloatArray(SLOTS)
    private val length = FloatArray(SLOTS)
    private val breadth = FloatArray(SLOTS)
    private val widest = FloatArray(SLOTS)
    private val heat = FloatArray(SLOTS)
    private val glint = FloatArray(SLOTS)
    private val fillRoot = IntArray(SLOTS)
    private val fillTip = IntArray(SLOTS)
    private val rimRoot = IntArray(SLOTS)
    private val rimTip = IntArray(SLOTS)

    private val centreX = FloatArray(FIGURES)
    private val centreY = FloatArray(FIGURES)
    private val depth = FloatArray(FIGURES)
    private val coreRadius = FloatArray(FIGURES)
    private val coreColour = IntArray(FIGURES)
    private val coreGlow = IntArray(FIGURES)
    private val sparkColour = IntArray(FIGURES)
    // Lightness, chroma and hue of each figure's root and tip, fill and rim, at full light.
    private val family = FloatArray(FIGURES * 4 * 3)

    // Pieces in flight: elements a snare broke off, and discs a drop threw.
    private val pieceX = FloatArray(PIECES)
    private val pieceY = FloatArray(PIECES)
    private val pieceSpeedX = FloatArray(PIECES)
    private val pieceSpeedY = FloatArray(PIECES)
    private val pieceTurn = FloatArray(PIECES)
    private val pieceSpin = FloatArray(PIECES)
    private val pieceFlip = FloatArray(PIECES)
    private val pieceFlipRate = FloatArray(PIECES)
    private val pieceLength = FloatArray(PIECES)
    private val pieceBreadth = FloatArray(PIECES)
    private val pieceWidest = FloatArray(PIECES)
    private val pieceAge = FloatArray(PIECES)
    private val pieceLife = FloatArray(PIECES)
    private val pieceFillRoot = IntArray(PIECES)
    private val pieceFillTip = IntArray(PIECES)
    private val pieceRimRoot = IntArray(PIECES)
    private val pieceRimTip = IntArray(PIECES)
    private val pieceCoin = BooleanArray(PIECES)
    private val pieceAlive = BooleanArray(PIECES)
    private var nextPiece = 0

    // Sparkles, each held to the tip of one element.
    private val sparkSlot = IntArray(SPARKS)
    private val sparkAge = FloatArray(SPARKS)
    private val sparkStrength = FloatArray(SPARKS)
    private val sparkAlive = BooleanArray(SPARKS)
    private var nextSpark = 0

    private val outlineX = FloatArray(TwinBloomOutline.POINTS)
    private val outlineY = FloatArray(TwinBloomOutline.POINTS)
    private val normalX = FloatArray(TwinBloomOutline.POINTS)
    private val normalY = FloatArray(TwinBloomOutline.POINTS)
    private val front = TriangleMesh(maxVertices = 16_500)
    private val glows = TriangleMesh(maxVertices = 2_800)

    init {
        restoreRings()
    }

    /** How many changes of shape have started, for tests. */
    internal val changes: Int get() = cascades

    /** The length of a bar as the drawing reads it, for tests. */
    internal val barSeconds: Float get() = gestures.cycleSeconds

    /** The named shape ring [ring] of [figure] leans to most right now, for tests. */
    internal fun shapeOf(figure: Int, ring: Int): TwinBloomShape {
        val at = figure * RINGS + ring
        var best = 0
        for (candidate in 1 until SHAPES) if (weight(at, candidate) > weight(at, best)) best = candidate
        return TwinBloomShape.entries[best]
    }

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        val dt = state.deltaSeconds
        val step = state.stepSeconds
        val beat = gestures.beatSeconds.coerceAtLeast(0.1f)
        val bar = gestures.cycleSeconds.coerceAtLeast(0.4f)
        screenWide = max(kit.aspect, 1f)
        screenTall = max(1f / kit.aspect, 1f)
        val bands = frame.bands
        perRing = if (bands.size < 2) FEWEST else (bands.size / 2f / RINGS).roundToInt().coerceIn(FEWEST, MOST)

        // A beat counts only while something is heard, so a pause or a silence holds every change.
        val beatIndex = gestures.cycles * 4 + (gestures.cyclePhase * 4f).toInt().coerceIn(0, 3)
        val onBeat = lastBeat >= 0 && beatIndex > lastBeat && frame.audible > 0.5f
        if (beatIndex > lastBeat) lastBeat = beatIndex
        val way = if (backwards.on) -1f else 1f
        if (gestures.pulseUsable) {
            // The pair steps a sixteenth of a turn on each beat and eases into place: a tick.
            if (onBeat) orbitTarget += way / STEPS_PER_TURN
        } else {
            // Without a pulse there is no beat to tick on, so the pair turns at the same pace smoothly.
            orbitTarget += way * step / (beat * STEPS_PER_TURN)
        }
        orbitShown += (orbitTarget - orbitShown) * (1f - exp(-step * TICK / beat))
        if (onBeat) {
            beatsSinceChange++
            breakdownBeats++
            for (ring in wait.indices) if (wait[ring] > 0) wait[ring]--
        }

        // Only the cores breathe when nothing plays. A paused player keeps its last picture.
        if (!frame.held) breath += dt * TAU / BREATH_SECONDS
        punch.advance(dt)
        impulse.apply(frame, state.future?.nextEvent(AudioEventKind.LowTransient), KICK * (0.25f + 0.75f * state.motionScale))
        stereo += (frame.width.coerceIn(0f, 1f) - stereo) * (1f - exp(-step / WIDTH_SECONDS))
        val upcoming = state.future?.nextEvent(AudioEventKind.Drop)
        val nearing = if (upcoming != null && upcoming.secondsUntil in 0f..BUILD_SECONDS) 1f - upcoming.secondsUntil / BUILD_SECONDS else 0f
        buildUp += (nearing - buildUp) * (1f - exp(-step / 0.15f))
        for (ring in morph.indices) {
            if (wait[ring] == 0 && morph[ring] < 1f) morph[ring] = (morph[ring] + step / (MORPH_BEATS * beat)).coerceAtMost(1f)
        }
        val throwing = throwAge < 1f
        if (throwing) throwAge = (throwAge + step / bar).coerceAtMost(1f)
        // After a throw every element grows back from the core over the bar; after a snare, in a beat and a half.
        val rate = if (throwing) 1f / bar else 1f / (REGROW_BEATS * beat)
        for (slot in regrow.indices) if (regrow[slot] < 1f) regrow[slot] = (regrow[slot] + step * rate).coerceAtMost(1f)
        flare = (flare - step / FLARE_SECONDS).coerceAtLeast(0f)
        colourSurge = (colourSurge - step / bar).coerceAtLeast(0f)

        // A breakdown parts the twins and closes them into buds until the next turn, two bars at least.
        if (gestures.breakdown) {
            if (!inBreakdown) budSide = if (cos(orbitShown * TAU) >= 0f) 1f else -1f
            inBreakdown = true
            breakdownBeats = 0
        } else if (inBreakdown && breakdownBeats >= BUD_BEATS && gestures.turn) {
            inBreakdown = false
        }
        budding += ((if (inBreakdown) 1f else 0f) - budding) * (1f - exp(-step / (BUD_BARS * bar)))

        val moodShape = when {
            frame.mood < 1f / 3f -> PETAL
            frame.mood < 2f / 3f -> SPOKE
            else -> DISC
        }
        if (gestures.surge) {
            throwAll(state)
        } else {
            if (gestures.turn) pendingStep = true
            if (pendingStep && throwAge >= 1f) {
                pendingStep = false
                change(following(shape, frame.mood), bands)
            } else if (throwAge >= 1f && moodShape != shape && beatsSinceChange >= LOCK_BEATS && frame.audible > 0.5f) {
                change(moodShape, bands)
            }
        }

        twinkle(step)
        layout(state)
        if (gestures.snare > 0f) breakOff(state, (2 + gestures.snareSpawn(3)).coerceIn(3, 6))
        if (gestures.hat > 0f) sparkle(gestures.hatSpawn(5), gestures.hat)
        fly(step)
        val near = if (depth[0] > depth[1]) 0 else 1
        kit.place(0, centreX[near] / screenWide, centreY[near] / screenTall, parallax = 0f)
        kit.place(1, centreX[1 - near] / screenWide, centreY[1 - near] / screenTall, parallax = 0f)
    }

    /** The next shape a section steps to: spoke, petal, disc and round again. Calm music skips the disc. */
    private fun following(from: Int, mood: Float): Int = when (from) {
        SPOKE -> PETAL
        PETAL -> if (mood < 1f / 3f) SPOKE else DISC
        else -> SPOKE
    }

    private fun change(to: Int, bands: FloatArray) {
        shape = to
        beatsSinceChange = 0
        cascades++
        for (figure in 0 until FIGURES) startCascade(figure, to, bands)
    }

    /** Starts a change at the ring that holds the loudest band; each ring further out or in waits a beat more. */
    private fun startCascade(figure: Int, to: Int, bands: FloatArray) {
        val first = loudestRing(figure, bands)
        for (ring in 0 until RINGS) {
            val at = figure * RINGS + ring
            val spoke = weight(at, SPOKE)
            val petal = weight(at, PETAL)
            val disc = weight(at, DISC)
            fromWeight[at * SHAPES + SPOKE] = spoke
            fromWeight[at * SHAPES + PETAL] = petal
            fromWeight[at * SHAPES + DISC] = disc
            target[at] = to
            morph[at] = 0f
            wait[at] = abs(ring - first)
        }
    }

    private fun loudestRing(figure: Int, bands: FloatArray): Int {
        var best = 0
        var most = -1f
        for (ring in 0 until RINGS) {
            for (element in 0 until perRing) {
                val level = response(bands, figure, ring, element)
                if (level > most) {
                    most = level
                    best = ring
                }
            }
        }
        return best
    }

    /** How much of named shape [shape] ring [at] shows now. The three weights always add up to one. */
    private fun weight(at: Int, shape: Int): Float {
        val eased = if (wait[at] > 0) 0f else morph[at].let { it * it * (3f - 2f * it) }
        val arriving = if (target[at] == shape) eased else 0f
        return fromWeight[at * SHAPES + shape] * (1f - eased) + arriving
    }

    /**
     * The band an element reads, 0 to 1 through a fixed range for its sixth of the spectrum.
     *
     * The low sixths carry far more energy than the high ones, so each sixth has its own top: about
     * the level its loudest tenth reaches across six songs of different kinds. *Judgement.*
     */
    private fun response(bands: FloatArray, figure: Int, ring: Int, element: Int): Float {
        if (bands.isEmpty()) return 0f
        val half = bands.size / 2f
        val along = (ring * perRing + element + 0.5f) / (RINGS * perRing)
        val at = figure * half + along * half - 0.5f
        val lower = floor(at).toInt().coerceIn(0, bands.size - 1)
        val upper = (lower + 1).coerceAtMost(bands.size - 1)
        val mix = (at - floor(at)).coerceIn(0f, 1f)
        val raw = bands[lower] + (bands[upper] - bands[lower]) * mix
        val top = TOPS[figure * RINGS + ring]
        return ((raw - BAND_FLOOR) / (top - BAND_FLOOR)).coerceIn(0f, 1f)
    }

    /** The light the figures give: 35 percent in silence, all of it when loud, and held back by the flash guard. */
    private fun lightOf(state: VizRenderState): Float =
        state.lightScale.coerceIn(0f, 1f) * (IDLE_LIGHT + (1f - IDLE_LIGHT) * loudOf(state))

    /** How loud the music is on the shared light curve, 0 in silence and 1 when loud. */
    private fun loudOf(state: VizRenderState): Float {
        val scale = state.lightScale
        if (scale <= 1e-3f) return 0f
        return ((state.lift / scale - LIGHT_FLOOR) / (1f - LIGHT_FLOOR)).coerceIn(0f, 1f)
    }

    /** Places the twins and every element for this frame, and colours them. */
    private fun layout(state: VizRenderState) {
        val bands = state.frame.bands
        paint(state.palette)
        val light = lightOf(state)
        val loud = loudOf(state)
        val kick = punch.value.coerceIn(-0.3f, 1.4f)
        val theta = orbitShown * TAU
        val landscape = screenWide >= screenTall
        val longSide = max(screenWide, screenTall)
        var discs = 0f
        for (at in 0 until FIGURES * RINGS) discs += weight(at, DISC)
        discs /= FIGURES * RINGS
        // Mono music overlaps the twins and wide music parts them, as far as the long side allows.
        var apart = (SEP_MONO + SEP_WIDE * stereo).coerceAtMost((longSide - 2.1f * RADIUS).coerceAtLeast(SEP_MONO))
        apart += SHOVE * kick.coerceAtLeast(0f) * discs
        apart *= 1f - 0.35f * buildUp
        apart += FLING * fling(throwAge) * throwMotion
        val open = OPEN_REST + (1f - OPEN_REST) * loud
        for (figure in 0 until FIGURES) {
            val turn = theta + figure * PI.toFloat()
            val along = cos(turn) * apart * 0.5f
            val across = sin(turn) * apart * 0.5f * tilt.value
            var x = screenWide * 0.5f + if (landscape) along else across
            var y = screenTall * 0.5f + if (landscape) across else along
            val side = if (figure == 0) budSide else -budSide
            val sideX = screenWide * 0.5f + if (landscape) side * BUD_SIDE * screenWide else 0f
            val sideY = screenTall * 0.5f + if (landscape) 0f else side * BUD_SIDE * screenTall
            x += (sideX - x) * budding
            y += (sideY - y) * budding
            centreX[figure] = x
            centreY[figure] = y
            // Seen from a little above the orbit: the nearer twin is lower on screen, a touch larger, and in front.
            depth[figure] = sin(turn)
            val scale = RADIUS * (1f + 0.05f * depth[figure])
            coreRadius[figure] = coreSize.value * scale * (1f + 0.06f * sin(breath)) *
                (1f + 0.3f * kick.coerceAtLeast(0f)) * (1f + 0.3f * flare) * (1f - 0.3f * budding)
            val coreLight = (light * (1f + 0.12f * sin(breath)) + 0.7f * flare * state.lightScale).coerceIn(0f, 1f)
            coreColour[figure] = colourOf(cbrt(coreLight), 0f, 0f).toArgb()
            // The core's glow takes the figure's own tip colour, so each twin lights its own flower.
            val tipHue = family[(figure * 4 + TIP_FILL) * 3 + 2]
            val glowAlpha = ((0.3f + 0.3f * loud) * light + 0.5f * flare * state.lightScale).coerceIn(0f, 1f)
            val glowChroma = min(0.5f * family[(figure * 4 + TIP_FILL) * 3 + 1], mostChroma(0.85f, tipHue))
            coreGlow[figure] = colourOf(0.85f, glowChroma, tipHue, glowAlpha).toArgb()
            sparkColour[figure] = colourOf(0.97f * cbrt(light), 0.05f * cbrt(light), tipHue).toArgb()
            for (ring in 0 until RINGS) {
                val at = figure * RINGS + ring
                val spoke = weight(at, SPOKE)
                val petal = weight(at, PETAL)
                val disc = weight(at, DISC)
                // A disc is kept a little over its share of the ring, so a ring of many stays a ring of discs.
                val discBase = min(BASE[DISC][ring], DISC_FILL * TAU * PLACE[DISC][ring] / perRing)
                val base = spoke * BASE[SPOKE][ring] + petal * BASE[PETAL][ring] + disc * discBase
                val place = spoke * PLACE[SPOKE][ring] + petal * PLACE[PETAL][ring] + disc * PLACE[DISC][ring]
                val anchor = disc * 0.5f
                val aspect = spoke * ASPECT[SPOKE] + petal * ASPECT[PETAL] * open + disc * ASPECT[DISC]
                val middle = spoke * WIDEST[SPOKE] + petal * WIDEST[PETAL] + disc * WIDEST[DISC]
                // A kick shoots the spokes out, opens the petals wider, and swells the discs so they shove apart.
                val shoot = 1f + KICK_SPOKE * kick * spoke
                val opening = 1f + KICK_PETAL * kick * petal
                val swell = 1f + KICK_DISC * kick * disc
                val push = 1f + KICK_SHOVE * kick * disc
                for (element in 0 until perRing) {
                    val slot = at * MOST + element
                    val level = response(bands, figure, ring, element)
                    val grow = regrow[slot]
                    val body = base * (REST + (1f - REST) * level) * grow * swell
                    val long = body * shoot * (1f - 0.55f * budding)
                    val wide = body * aspect * opening * (1f - 0.3f * budding)
                    val zig = if (element % 2 == 0) 1f else -1f
                    val out = (place * push + 0.05f * kick * disc * zig) * (1f - 0.5f * budding) * (0.15f + 0.85f * grow)
                    val direction = -PI.toFloat() * 0.5f + (element + stagger.value * ring) * TAU / perRing
                    val reach = (out - anchor * long) * scale
                    rootX[slot] = x + cos(direction) * reach
                    rootY[slot] = y + sin(direction) * reach
                    angle[slot] = direction
                    // An element still regrowing from nothing is not drawn until it has some size.
                    length[slot] = if (long * scale < 0.002f) 0f else long * scale
                    breadth[slot] = wide * scale
                    widest[slot] = middle
                    heat[slot] = level
                    if (length[slot] <= 0f) continue
                    // A quiet band is a little darker; mostly it is shorter, so the colours stay vivid.
                    val k = cbrt((light * (0.85f + 0.15f * level)).coerceIn(0f, 1f))
                    val pale = (glint[slot] * 0.85f + colourSurge * 0.45f).coerceAtMost(1f)
                    fillRoot[slot] = shade(figure, ROOT_FILL, k, (glint[slot] * 0.35f + colourSurge * 0.2f).coerceAtMost(1f))
                    fillTip[slot] = shade(figure, TIP_FILL, k, pale)
                    rimRoot[slot] = shade(figure, ROOT_RIM, k, 0f)
                    rimTip[slot] = shade(figure, TIP_RIM, k, pale)
                }
            }
        }
    }

    /** How far the twins are flung, over the bar after a throw: out fast, then back together. */
    private fun fling(age: Float): Float = when {
        age >= 1f -> 0f
        age < FLING_OUT -> (age / FLING_OUT).let { 1f - (1f - it) * (1f - it) }
        else -> ((age - FLING_OUT) / (1f - FLING_OUT)).let { 1f - it * it * (3f - 2f * it) }
    }

    /**
     * Reads each figure's two colour families: the drawing's own swatches, turned with the key the way
     * the palette is, or a chosen palette's low and high halves, both through the vivid read.
     */
    private fun paint(palette: VizPalette) {
        val own = palette.name == VizPalette.Prism.name
        // The surface turns the default palette towards the key; the swatches turn by the same angle.
        var lean = palette.baseHue - VizPalette.Prism.baseHue
        while (lean > 180f) lean -= 360f
        while (lean < -180f) lean += 360f
        for (figure in 0 until FIGURES) {
            val rootHue = if (own) OWN_ROOT_HUE[figure] + lean else palette.baseHue + ROOT_AT[figure] * palette.hueSpan
            val tipHue = if (own) OWN_TIP_HUE[figure] + lean else palette.baseHue + TIP_AT[figure] * palette.hueSpan
            val rootLight = ROOT_LIGHTNESS[figure]
            val cusp = cuspOf(tipHue)
            store(figure, ROOT_FILL, rootLight, rootHue)
            store(figure, TIP_FILL, vividLightness(tipHue, cusp, (cusp + FILL_LIFT).coerceIn(0f, 1f)), tipHue)
            store(figure, ROOT_RIM, (rootLight + ROOT_RIM_LIFT).coerceAtMost(1f), rootHue)
            store(figure, TIP_RIM, vividLightness(tipHue, cusp, (cusp + RIM_LIFT).coerceIn(0f, 1f)), tipHue)
        }
    }

    /**
     * The lightness where [hue] is most colourful, searched from 0.2 upwards.
     *
     * The shared cusp table reads a false peak at black for magenta, red and cyan hues: every chroma
     * is close to no light at all there, so it passes the gamut test. Starting the search above the
     * dark end finds the real peak.
     */
    private fun cuspOf(hue: Float): Float {
        var best = CUSP_FROM
        var most = -1f
        var lightness = CUSP_FROM
        while (lightness <= 1f) {
            val chroma = mostChroma(lightness, hue)
            if (chroma > most) {
                most = chroma
                best = lightness
            }
            lightness += CUSP_STEP
        }
        return best
    }

    private fun store(figure: Int, which: Int, lightness: Float, hue: Float) {
        val at = (figure * 4 + which) * 3
        family[at] = lightness
        family[at + 1] = (mostChroma(lightness, hue) - HEADROOM).coerceAtLeast(0f)
        family[at + 2] = hue
    }

    /**
     * One colour of a figure at light [k], the cube root of the share of light, so the colour dims
     * without greying. [pale] moves it towards white, for a sparkle or the drop under reduced motion.
     */
    private fun shade(figure: Int, which: Int, k: Float, pale: Float): Int {
        val at = (figure * 4 + which) * 3
        var lightness = family[at]
        var chroma = family[at + 1]
        if (pale > 0f) {
            lightness += (0.98f - lightness) * pale
            chroma *= 1f - pale
        }
        return colourOf(lightness * k, chroma * k, family[at + 2]).toArgb()
    }

    /**
     * The drop. Every ring turns to discs at once and every disc is thrown, fast enough to reach the
     * edge of the frame within a bar, while the twins fling apart and new elements grow from the cores.
     * Under reduced motion the moment plays as colour and light instead.
     */
    private fun throwAll(state: VizRenderState) {
        shape = DISC
        beatsSinceChange = 0
        pendingStep = false
        inBreakdown = false
        cascades++
        flare = 1f
        throwAge = 0f
        if (state.motionScale >= 0.5f) {
            for (at in 0 until FIGURES * RINGS) {
                for (candidate in 0 until SHAPES) fromWeight[at * SHAPES + candidate] = if (candidate == DISC) 1f else 0f
                target[at] = DISC
                morph[at] = 1f
                wait[at] = 0
            }
            regrow.fill(1f)
            layout(state)
            throwCoins()
            regrow.fill(0f)
            throwMotion = state.motionScale
        } else {
            for (figure in 0 until FIGURES) startCascade(figure, DISC, state.frame.bands)
            colourSurge = 1f
            throwMotion = 0f
        }
    }

    private fun throwCoins() {
        val bar = gestures.cycleSeconds.coerceAtLeast(0.4f)
        for (figure in 0 until FIGURES) {
            var awayX = centreX[figure] - centreX[1 - figure]
            var awayY = centreY[figure] - centreY[1 - figure]
            val apart = sqrt(awayX * awayX + awayY * awayY)
            if (apart < 1e-3f) {
                awayX = if (figure == 0) -1f else 1f
                awayY = 0f
            } else {
                awayX /= apart
                awayY /= apart
            }
            for (ring in 0 until RINGS) {
                for (element in 0 until perRing) {
                    val slot = (figure * RINGS + ring) * MOST + element
                    val long = length[slot]
                    if (long <= 0f) continue
                    val a = angle[slot]
                    val x = rootX[slot] + cos(a) * long * 0.5f
                    val y = rootY[slot] + sin(a) * long * 0.5f
                    // Out from the core and away from the other twin, scattered a little like a handful of coins.
                    val scatter = random.signed() * 0.25f
                    val outX = cos(a + scatter) + 0.6f * awayX
                    val outY = sin(a + scatter) + 0.6f * awayY
                    val norm = sqrt(outX * outX + outY * outY).coerceAtLeast(1e-3f)
                    val dirX = outX / norm
                    val dirY = outY / norm
                    val speed = (exitDistance(x, y, dirX, dirY) + long) / (COIN_BAR * bar) * (0.9f + 0.2f * random.next())
                    launch(
                        x, y, dirX * speed, dirY * speed, a, random.signed() * 3f, 6f + 4f * random.next(),
                        long, breadth[slot], widest[slot], slot, bar, coin = true, flip = random.next() * TAU,
                    )
                }
            }
        }
    }

    /** How far a point travels in a direction before it leaves the screen. */
    private fun exitDistance(x: Float, y: Float, dirX: Float, dirY: Float): Float {
        val acrossX = when {
            dirX > 1e-4f -> (screenWide - x) / dirX
            dirX < -1e-4f -> -x / dirX
            else -> Float.MAX_VALUE
        }
        val acrossY = when {
            dirY > 1e-4f -> (screenTall - y) / dirY
            dirY < -1e-4f -> -y / dirY
            else -> Float.MAX_VALUE
        }
        return min(acrossX, acrossY).coerceAtLeast(0f)
    }

    /** A snare breaks a few outer elements off. They spin away and are gone within a beat; the gap grows back. */
    private fun breakOff(state: VizRenderState, count: Int) {
        val beat = gestures.beatSeconds.coerceAtLeast(0.1f)
        val motion = 0.3f + 0.7f * state.motionScale
        var made = 0
        var tries = 0
        while (made < count && tries < count * 4) {
            tries++
            val figure = (snareTurn + made) % FIGURES
            val element = (random.next() * perRing).toInt().coerceIn(0, perRing - 1)
            val slot = (figure * RINGS + RINGS - 1) * MOST + element
            if (length[slot] <= 0f || regrow[slot] < 0.7f) continue
            val a = angle[slot]
            val long = length[slot]
            val speed = (0.35f + 0.25f * random.next()) * motion
            val side = random.signed() * 0.6f
            val spin = (if (random.next() < 0.5f) -1f else 1f) * (5f + 4f * random.next()) * motion
            launch(
                rootX[slot] + cos(a) * long * 0.5f, rootY[slot] + sin(a) * long * 0.5f,
                (cos(a) - sin(a) * side) * speed, (sin(a) + cos(a) * side) * speed,
                a, spin, 0f, long, breadth[slot], widest[slot], slot, beat, coin = false,
            )
            regrow[slot] = 0f
            length[slot] = 0f
            made++
        }
        snareTurn++
    }

    private fun launch(
        x: Float, y: Float, speedX: Float, speedY: Float, turn: Float, spin: Float, flipRate: Float,
        long: Float, wide: Float, middle: Float, slot: Int, life: Float, coin: Boolean, flip: Float = 0f,
    ) {
        var at = -1
        for (offset in 0 until PIECES) {
            val candidate = (nextPiece + offset) % PIECES
            if (!pieceAlive[candidate]) {
                at = candidate
                break
            }
        }
        if (at < 0) at = nextPiece
        nextPiece = (at + 1) % PIECES
        pieceX[at] = x
        pieceY[at] = y
        pieceSpeedX[at] = speedX
        pieceSpeedY[at] = speedY
        pieceTurn[at] = turn
        pieceSpin[at] = spin
        pieceFlip[at] = flip
        pieceFlipRate[at] = flipRate
        pieceLength[at] = long
        pieceBreadth[at] = wide
        pieceWidest[at] = middle
        pieceAge[at] = 0f
        pieceLife[at] = life.coerceAtLeast(0.05f)
        pieceFillRoot[at] = fillRoot[slot]
        pieceFillTip[at] = fillTip[slot]
        pieceRimRoot[at] = rimRoot[slot]
        pieceRimTip[at] = rimTip[slot]
        pieceCoin[at] = coin
        pieceAlive[at] = true
    }

    private fun fly(step: Float) {
        for (at in 0 until PIECES) {
            if (!pieceAlive[at]) continue
            pieceAge[at] += step
            pieceX[at] += pieceSpeedX[at] * step
            pieceY[at] += pieceSpeedY[at] * step
            pieceTurn[at] += pieceSpin[at] * step
            pieceFlip[at] += pieceFlipRate[at] * step
            val margin = pieceLength[at]
            val gone = pieceX[at] < -margin || pieceX[at] > screenWide + margin ||
                pieceY[at] < -margin || pieceY[at] > screenTall + margin
            if (pieceAge[at] >= pieceLife[at] || gone) pieceAlive[at] = false
        }
    }

    /** A hat makes a few tips sparkle, the outer ones most often: they flash pale and a small star shines on each. */
    private fun sparkle(count: Int, strength: Float) {
        for (made in 0 until count) {
            val figure = (random.next() * FIGURES).toInt().coerceIn(0, FIGURES - 1)
            val ring = if (random.next() < 0.7f) RINGS - 1 else RINGS - 2
            val element = (random.next() * perRing).toInt().coerceIn(0, perRing - 1)
            val slot = (figure * RINGS + ring) * MOST + element
            if (length[slot] <= 0f) continue
            sparkSlot[nextSpark] = slot
            sparkAge[nextSpark] = 0f
            sparkStrength[nextSpark] = strength.coerceIn(0.3f, 1f)
            sparkAlive[nextSpark] = true
            nextSpark = (nextSpark + 1) % SPARKS
        }
    }

    private fun twinkle(step: Float) {
        glint.fill(0f)
        for (at in 0 until SPARKS) {
            if (!sparkAlive[at]) continue
            sparkAge[at] += step
            if (sparkAge[at] >= SPARKLE_SECONDS) {
                sparkAlive[at] = false
                continue
            }
            val slot = sparkSlot[at]
            glint[slot] = max(glint[slot], (1f - sparkAge[at] / SPARKLE_SECONDS) * sparkStrength[at])
        }
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val unit = size.minDimension
        glows.clear()
        for (figure in 0 until FIGURES) {
            val radius = coreRadius[figure] * unit * (CORE_GLOW + 2f * flare)
            glows.glow(centreX[figure] * unit, centreY[figure] * unit, radius, coreGlow[figure], 24)
        }
        // Only the tips past four fifths of their reach glow: the hottest tenth of the picture.
        val light = lightOf(state)
        for (slot in 0 until SLOTS) {
            val hot = (heat[slot] - HOT) / (1f - HOT)
            if (hot <= 0f || length[slot] <= 0f || slot % MOST >= perRing) continue
            val a = angle[slot]
            val tipX = (rootX[slot] + cos(a) * length[slot]) * unit
            val tipY = (rootY[slot] + sin(a) * length[slot]) * unit
            val radius = (0.09f * RADIUS + 0.6f * breadth[slot]) * unit
            glows.glow(tipX, tipY, radius, withAlpha(fillTip[slot], 0.6f * hot * light), 12)
        }
        // Short streaks behind the thrown discs.
        for (at in 0 until PIECES) {
            if (!pieceAlive[at] || !pieceCoin[at]) continue
            val headX = pieceX[at] * unit
            val headY = pieceY[at] * unit
            val tailX = headX - pieceSpeedX[at] * STREAK_SECONDS * unit
            val tailY = headY - pieceSpeedY[at] * STREAK_SECONDS * unit
            glows.streak(tailX, tailY, headX, headY, pieceLength[at] * unit * 0.8f, withAlpha(pieceFillTip[at], 0.55f * coinFade(at)))
        }
        drawMesh(glows, BlendMode.Plus)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val unit = size.minDimension
        // A rim of about a pixel and a half where one pixel is a point, so it still reads on a dense screen.
        val rim = RIM * density.coerceIn(1f, 2f)
        front.clear()
        // A broken-off element leaves from the back ring, so it flies out from under the figures; a
        // thrown disc flies towards the viewer, over them.
        addPieces(unit, rim, coins = false)
        val far = if (depth[0] <= depth[1]) 0 else 1
        addFigure(far, unit, rim)
        addFigure(1 - far, unit, rim)
        addPieces(unit, rim, coins = true)
        for (at in 0 until SPARKS) {
            if (!sparkAlive[at]) continue
            val slot = sparkSlot[at]
            if (length[slot] <= 0f) continue
            val left = 1f - sparkAge[at] / SPARKLE_SECONDS
            val a = angle[slot]
            val x = (rootX[slot] + cos(a) * length[slot]) * unit
            val y = (rootY[slot] + sin(a) * length[slot]) * unit
            val arm = SPARK_ARM * RADIUS * unit * (0.6f + 0.4f * sparkStrength[at]) * (0.4f + 0.6f * left)
            val figure = slot / (RINGS * MOST)
            front.spark(x, y, arm, max(1f, arm * 0.14f), withAlpha(sparkColour[figure], left))
        }
        drawMesh(front)
    }

    private fun addPieces(unit: Float, rim: Float, coins: Boolean) {
        for (at in 0 until PIECES) {
            if (!pieceAlive[at] || pieceCoin[at] != coins) continue
            val t = pieceAge[at] / pieceLife[at]
            val fade = if (pieceCoin[at]) coinFade(at) else 1f - t * t
            // A thrown coin turns over as it flies, so its width comes and goes.
            val wide = if (pieceCoin[at]) pieceBreadth[at] * max(0.3f, abs(cos(pieceFlip[at]))) else pieceBreadth[at]
            val turn = pieceTurn[at]
            val half = pieceLength[at] * 0.5f
            addElement(
                unit, pieceX[at] - cos(turn) * half, pieceY[at] - sin(turn) * half, turn,
                pieceLength[at], wide, pieceWidest[at],
                pieceFillRoot[at], pieceFillTip[at], pieceRimRoot[at], pieceRimTip[at], rim, fade,
            )
        }
    }

    private fun coinFade(at: Int): Float = 1f - ((pieceAge[at] / pieceLife[at] - 0.75f) / 0.25f).coerceIn(0f, 1f)

    /** A figure, far ring first, so the inner rings lie over the outer ones, and its core last. */
    private fun addFigure(figure: Int, unit: Float, rim: Float) {
        for (ring in RINGS - 1 downTo 0) {
            for (element in 0 until perRing) {
                val slot = (figure * RINGS + ring) * MOST + element
                if (length[slot] <= 0f) continue
                addElement(
                    unit, rootX[slot], rootY[slot], angle[slot], length[slot], breadth[slot], widest[slot],
                    fillRoot[slot], fillTip[slot], rimRoot[slot], rimTip[slot], rim, 1f,
                )
            }
        }
        val radius = coreRadius[figure]
        val core = coreColour[figure]
        addElement(unit, centreX[figure] - radius, centreY[figure], 0f, 2f * radius, 2f * radius, 0.5f, core, core, core, core, rim, 1f)
    }

    /**
     * Adds one element to the front: a silhouette in the lighter rim colour, a one pixel edge that fades
     * to nothing so the outline is smooth, and the opaque body inset by the rim. Colours run root to tip.
     */
    private fun addElement(
        unit: Float, fromX: Float, fromY: Float, turn: Float, long: Float, wide: Float, middle: Float,
        bodyRoot: Int, bodyTip: Int, edgeRoot: Int, edgeTip: Int, rim: Float, alpha: Float,
    ) {
        val points = TwinBloomOutline.POINTS
        val span = long * unit
        if (span < 1f || front.vertexCount + 3 * points + 2 > front.maxVertices) return
        val across = (wide * unit).coerceAtLeast(0.6f)
        TwinBloomOutline.trace(span, across, middle, outlineX, outlineY)
        for (index in 0 until points) {
            val before = (index - 1 + points) % points
            val after = (index + 1) % points
            val dx = outlineX[after] - outlineX[before]
            val dy = outlineY[after] - outlineY[before]
            val norm = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
            normalX[index] = -dy / norm
            normalY[index] = dx / norm
        }
        val c = cos(turn)
        val s = sin(turn)
        val originX = fromX * unit
        val originY = fromY * unit
        val hubAt = middle.coerceIn(0.1f, 0.9f) * span
        val along = TwinBloomOutline.along
        val hub = front.vertex(originX + hubAt * c, originY + hubAt * s, tone(edgeRoot, edgeTip, middle, alpha))
        val rimFirst = front.vertexCount
        for (index in 0 until points) {
            val x = outlineX[index]
            val y = outlineY[index]
            front.vertex(originX + x * c - y * s, originY + x * s + y * c, tone(edgeRoot, edgeTip, along[index], alpha))
        }
        for (index in 0 until points) front.triangle(hub, rimFirst + index, rimFirst + (index + 1) % points)
        val edgeFirst = front.vertexCount
        for (index in 0 until points) {
            val x = outlineX[index] + FRINGE * normalX[index]
            val y = outlineY[index] + FRINGE * normalY[index]
            front.vertex(originX + x * c - y * s, originY + x * s + y * c, tone(edgeRoot, edgeTip, along[index], 0f))
        }
        for (index in 0 until points) {
            val next = (index + 1) % points
            front.quad(rimFirst + index, edgeFirst + index, edgeFirst + next, rimFirst + next)
        }
        // Too small for a body inside its rim: it is all rim, which is what a thin line should be.
        if (span < 3f * rim || across < 2.5f * rim) return
        val body = front.vertex(originX + hubAt * c, originY + hubAt * s, tone(bodyRoot, bodyTip, middle, alpha))
        val bodyFirst = front.vertexCount
        for (index in 0 until points) {
            var x = outlineX[index] - rim * normalX[index]
            var y = outlineY[index] - rim * normalY[index]
            if (index in 1 until TwinBloomOutline.STEPS) y = y.coerceAtLeast(0f)
            if (index > TwinBloomOutline.STEPS) y = y.coerceAtMost(0f)
            x = x.coerceIn(rim, span - rim)
            front.vertex(originX + x * c - y * s, originY + x * s + y * c, tone(bodyRoot, bodyTip, along[index], alpha))
        }
        for (index in 0 until points) front.triangle(body, bodyFirst + index, bodyFirst + (index + 1) % points)
    }

    /** The colour at [t] of the length, [alpha] opaque: the root colour near the core, the tip colour from halfway out. */
    private fun tone(root: Int, tip: Int, t: Float, alpha: Float): Int {
        val x = ((t - 0.08f) / 0.72f).coerceIn(0f, 1f)
        val g = x * x * (3f - 2f * x)
        val red = channel(root shr 16, tip shr 16, g)
        val green = channel(root shr 8, tip shr 8, g)
        val blue = channel(root, tip, g)
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (a shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun channel(from: Int, to: Int, g: Float): Int {
        val a = from and 0xFF
        val b = to and 0xFF
        return (a + (b - a) * g + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun withAlpha(argb: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (argb and 0x00FFFFFF)

    private fun restoreRings() {
        fromWeight.fill(0f)
        for (at in 0 until FIGURES * RINGS) fromWeight[at * SHAPES + PETAL] = 1f
        target.fill(PETAL)
        morph.fill(1f)
        wait.fill(0)
    }

    override fun onReset() {
        punch.reset()
        impulse.reset()
        breath = 0f
        orbitTarget = START_TURN
        orbitShown = START_TURN
        lastBeat = -1
        beatsSinceChange = LOCK_BEATS
        shape = PETAL
        pendingStep = false
        cascades = 0
        stereo = 0f
        inBreakdown = false
        breakdownBeats = 0
        budSide = 1f
        budding = 0f
        throwAge = 1f
        throwMotion = 0f
        flare = 0f
        colourSurge = 0f
        buildUp = 0f
        snareTurn = 0
        restoreRings()
        regrow.fill(1f)
        length.fill(0f)
        glint.fill(0f)
        pieceAlive.fill(false)
        nextPiece = 0
        sparkAlive.fill(false)
        nextSpark = 0
    }

    private companion object {
        const val FIGURES = 2
        const val RINGS = 3
        const val SHAPES = 3
        const val SPOKE = 0
        const val PETAL = 1
        const val DISC = 2

        /** The most elements a ring holds, so a figure has up to 72, and the fewest. */
        const val MOST = 24
        const val FEWEST = 4
        const val SLOTS = FIGURES * RINGS * MOST
        const val PIECES = 176
        const val SPARKS = 32

        /**
         * A figure's radius, in shorter sides of the screen. An idle figure spans about 61 percent of
         * the shorter side, ordinary music about 68 percent, and a loud passage about 80.
         */
        const val RADIUS = 0.33f

        /**
         * How long an element is in silence, as a share of its full length; the band adds the rest.
         * High, so a silent figure still fills most of the shorter side.
         */
        const val REST = 0.74f

        /** How far open a petal is in silence, as a share of fully open. */
        const val OPEN_REST = 0.62f

        val ASPECT = FloatArray(SHAPES) { TwinBloomShape.entries[it].width }
        val WIDEST = FloatArray(SHAPES) { TwinBloomShape.entries[it].widest }

        /**
         * Where each ring's elements sit, per shape, in figure radii from the core: spokes and petals
         * by the root, discs by the middle.
         */
        val PLACE = arrayOf(
            floatArrayOf(0.13f, 0.13f, 0.13f),
            floatArrayOf(0.07f, 0.07f, 0.07f),
            floatArrayOf(0.3f, 0.54f, 0.78f),
        )

        /** Each ring's full length per shape, in figure radii; a disc's is its diameter. */
        val BASE = arrayOf(
            floatArrayOf(0.52f, 0.78f, 1.06f),
            floatArrayOf(0.6f, 0.86f, 1.16f),
            floatArrayOf(0.32f, 0.4f, 0.48f),
        )

        /** The widest a disc may be, as a share of its ring's spacing. */
        const val DISC_FILL = 1.15f

        /** The band height that reads as full for each sixth of the spectrum, low first. */
        val TOPS = floatArrayOf(0.85f, 0.5f, 0.42f, 0.34f, 0.34f, 0.3f)
        const val BAND_FLOOR = 0.02f

        /** A band above this share of its range glows at the tip. */
        const val HOT = 0.8f

        const val IDLE_LIGHT = 0.35f
        const val LIGHT_FLOOR = 0.06f

        const val KICK = 16f
        const val KICK_SPOKE = 0.8f
        const val KICK_PETAL = 1f
        const val KICK_DISC = 0.5f
        const val KICK_SHOVE = 0.25f

        /** How far apart the twins' centres are, in shorter sides: for mono, and added for a wide image. */
        const val SEP_MONO = 0.42f
        const val SEP_WIDE = 0.8f
        const val SHOVE = 0.08f
        const val FLING = 0.55f
        const val FLING_OUT = 0.12f
        const val WIDTH_SECONDS = 0.8f

        const val STEPS_PER_TURN = 16f
        const val START_TURN = 0.07f
        const val TICK = 10f
        const val LOCK_BEATS = 16
        const val MORPH_BEATS = 0.8f
        const val REGROW_BEATS = 1.5f
        const val BUD_BEATS = 8
        const val BUD_BARS = 0.35f
        const val BUD_SIDE = 0.27f
        const val BREATH_SECONDS = 4f
        const val BUILD_SECONDS = 1f

        /** Thrown discs cross to the edge of the frame in this share of a bar. */
        const val COIN_BAR = 0.55f
        const val STREAK_SECONDS = 0.08f
        const val FLARE_SECONDS = 0.35f
        const val SPARKLE_SECONDS = 0.5f
        const val SPARK_ARM = 0.26f
        const val CORE_GLOW = 3f

        const val RIM = 1.5f
        const val FRINGE = 1f

        const val ROOT_FILL = 0
        const val TIP_FILL = 1
        const val ROOT_RIM = 2
        const val TIP_RIM = 3

        /** The drawing's own swatches: violet and burnt orange roots, magenta and gold tips. */
        val OWN_ROOT_HUE = floatArrayOf(300f, 45f)
        val OWN_TIP_HUE = floatArrayOf(350f, 85f)
        val ROOT_LIGHTNESS = floatArrayOf(0.4f, 0.55f)

        /** Where a chosen palette's span is read: the low half for the first twin, the high half for the second. */
        val ROOT_AT = floatArrayOf(0.02f, 0.6f)
        val TIP_AT = floatArrayOf(0.38f, 0.97f)

        const val CUSP_FROM = 0.2f
        const val CUSP_STEP = 0.005f
        const val FILL_LIFT = -0.04f
        const val RIM_LIFT = 0.06f
        const val ROOT_RIM_LIFT = 0.12f
        const val HEADROOM = 0.03f
    }
}

/** Twin Bloom's three named shapes, as places on its one outline: width for a length of one, and where the widest point sits. */
internal enum class TwinBloomShape(val width: Float, val widest: Float) {
    /** Thin everywhere. */
    Spoke(0.09f, 0.45f),

    /** Widest past the middle and pointed at the tip. */
    Petal(0.46f, 0.64f),

    /** As wide as it is long and widest in the middle: round. */
    Disc(1f, 0.5f),
}

/**
 * The one outline every element of Twin Bloom is drawn with, from three numbers: its length, its
 * width, and how far from the root its widest point sits.
 *
 * Each side falls from the widest point to nothing at the root and at the tip along (1 - s²)^e, where
 * s runs from the widest point to that end. The exponent grows as the widest point leaves the middle.
 * So the same curve is a circle when the widest point is centred and the width equals the length, a
 * petal with a pointed tip when the widest point moves out, and a spoke when it is made thin.
 */
internal object TwinBloomOutline {
    /** Steps from root to tip along each side. */
    const val STEPS: Int = 8

    /** Points round the whole outline. */
    const val POINTS: Int = 2 * STEPS

    /**
     * How far along the length each point of the outline sits, 0 at the root and 1 at the tip. The
     * points crowd towards the ends, where the curve bends most, so a disc comes out a regular polygon.
     */
    val along: FloatArray = FloatArray(POINTS) { index ->
        val step = if (index <= STEPS) index else POINTS - index
        (1f - cos(PI.toFloat() * step / STEPS)) * 0.5f
    }

    /** The exponent of each side's curve: a half with the widest point in the middle, rising as it moves away. */
    fun sharpness(widest: Float): Float = (0.5f + 4f * abs(widest - 0.5f)).coerceAtMost(1.6f)

    /** Half the width at [t] of the length (0 root, 1 tip), for an outline [width] wide at [widest]. */
    fun halfWidth(t: Float, width: Float, widest: Float): Float {
        val middle = widest.coerceIn(0.05f, 0.95f)
        val s = if (t <= middle) (middle - t) / middle else (t - middle) / (1f - middle)
        if (s >= 1f) return 0f
        return 0.5f * width * (1f - s * s).pow(sharpness(middle))
    }

    /**
     * Writes the outline into [xs] and [ys], with x along the length from the root and y across it.
     * The loop starts at the root, runs up one side to the tip and back down the other.
     */
    fun trace(length: Float, width: Float, widest: Float, xs: FloatArray, ys: FloatArray) {
        for (index in 0..STEPS) {
            val t = along[index]
            xs[index] = t * length
            ys[index] = halfWidth(t, width, widest)
        }
        for (index in STEPS + 1 until POINTS) {
            val mirror = POINTS - index
            xs[index] = xs[mirror]
            ys[index] = -ys[mirror]
        }
    }
}
