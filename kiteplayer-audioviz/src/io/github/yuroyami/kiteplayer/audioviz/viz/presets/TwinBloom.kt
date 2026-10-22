package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.AnticipatedImpulse
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
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
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.field.FieldShader
import io.github.yuroyami.kiteplayer.audioviz.viz.field.Flows
import io.github.yuroyami.kiteplayer.audioviz.viz.field.MemoryField
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.streak
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
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
 * A garden of flowers made of the spectrum. Each bloom is three rings of elements round a white core,
 * and every element is one band, from the bass in the inner ring to the treble in the outer one,
 * drawn as one outline that can be a spoke, a petal or a disc. A petal's length runs from a fifth of
 * its full length in silence to all of it at full level. The mood picks the shape and a section
 * steps it on, and each change runs ring by ring on the beat, so for a few beats a bloom wears two
 * shapes. A kick shoots, opens or swells the elements, a snare breaks a few off, a hat makes tips
 * sparkle, and a drop throws every element as a disc to the frame edges.
 *
 * The garden starts as one bud on a short stem over a faint ground in the palette's low colour.
 * Each birth plants a new bloom where the mix puts it: across from the stereo balance, and further
 * back the brighter the sound. A sixth birth replaces the oldest bloom. Each bloom grows up on its
 * stem as it hears energy, and the live waveform runs along the stem. Each bloom takes its own
 * place on the palette by birth order, walked by the genes and leaned toward the key, and its bass,
 * middle and treble rings sit a step apart there.
 *
 * Petals that a snare breaks off settle on the ground into a carpet: a memory field that fades with
 * a four second half life and drifts slowly sideways, drawn as lit ink in four flat steps. Where
 * runtime shaders cannot run, the carpet is not drawn.
 *
 * The forms are named by what the garden shows: Storm while a drop's discs fly, Bud in a breakdown
 * or a silence, Field when three or more blooms stand, and Bloom otherwise. At each change of form
 * the music asks for, the blooms open or wilt with the slow level over two cycles.
 *
 * The view is fixed. One sway on the cycle moves three layers by their depth: the carpet half as
 * far as the blooms, and a large faint petal in the foreground twice as far.
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
        // The hat's sparkles, the section's cascade and the opening at a change of form are too
        // small or too slow for the declaration test to measure, so they are not declared.
        VizDrive(VizDriver.Mood, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.6f, delaySeconds = 0.2f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(1f, delaySeconds = 0.2f)),
        VizDrive(VizDriver.Drop, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(2f)),
    )

    override val forms: FormReadout
        get() = FormReadout(formName, evolution.morphs, evolution.births)

    private val stagger = genes.number("Ring stagger", 0.25f, 0.5f, 0.33f)
    private val coreSize = genes.number("Core size", 0.1f, 0.15f, 0.12f)

    private val punch = Spring(stiffness = 260f, damping = 0.42f)
    private val impulse = AnticipatedImpulse(punch)

    // The screen in shorter sides: the long side is 1.78 on a 16:9 screen, either way up.
    private var screenWide = 16f / 9f
    private var screenTall = 1f
    private var perRing = 8

    private var breath = 0f
    private var lastBeat = -1
    private var beatsSinceChange = LOCK_BEATS
    private var shape = PETAL
    private var pendingStep = false
    private var cascades = 0
    private var inBreakdown = false
    private var breakdownBeats = 0
    private var budding = 0f
    private var throwAge = 1f
    private var flare = 0f
    private var colourSurge = 0f
    private var buildUp = 0f
    private var snareTurn = 0

    // Each ring's change of shape: the weights it started from, where it is heading, how far it has
    // come, and how many beats it still waits before it starts.
    private val fromWeight = FloatArray(BLOOMS * RINGS * SHAPES)
    private val target = IntArray(BLOOMS * RINGS) { PETAL }
    private val morph = FloatArray(BLOOMS * RINGS) { 1f }
    private val wait = IntArray(BLOOMS * RINGS)

    // The garden: which blooms live, where each was planted, how long ago, how tall its stem has
    // grown, and its birth order, which picks its place on the palette.
    private val alive = BooleanArray(BLOOMS)
    private val plantedX = FloatArray(BLOOMS)
    private val plantedDepth = FloatArray(BLOOMS)
    private val age = FloatArray(BLOOMS)
    private val stem = FloatArray(BLOOMS)
    private val order = IntArray(BLOOMS)
    private var births = 0
    /** The stereo balance, -1 all left to 1 all right, eased over half a second of heard time. */
    private var balance = 0f
    private val presence = Envelope(2f, 0.5f)
    private val keyLean = Slew(maxPerSecond = KEY_TURN_PER_SECOND)
    private var sway = 0f
    private var light = IDLE_LIGHT
    // The far-to-near drawing order, and the live waveform along a stem.
    private val drawOrder = IntArray(BLOOMS)
    private val stemWave = FloatArray(STEM_POINTS)
    private val stemColour = IntArray(BLOOMS)
    private val chosen = IntArray(BLOOMS)

    // How far each bloom is open, and where the last change of form asked it to go.
    private val unfold = FloatArray(BLOOMS) { 1f }
    private val unfoldTarget = FloatArray(BLOOMS) { 1f }
    private var slowLevel = 0f
    private var silent = true

    // The carpet of fallen petals.
    private val carpet = MemoryField(rows = CARPET_ROWS).also { it.halfLife = CARPET_HALF_LIFE }
    private val drift = Flows.Drift(CARPET_DRIFT_X, 0f)
    private val carpetProgram: ShaderProgram by lazy { ShaderProgram(CARPET) }

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

    private val centreX = FloatArray(BLOOMS)
    private val centreY = FloatArray(BLOOMS)
    private val coreRadius = FloatArray(BLOOMS)
    private val coreColour = IntArray(BLOOMS)
    private val coreGlow = IntArray(BLOOMS)
    private val sparkColour = IntArray(BLOOMS)
    // Lightness, chroma and hue of each ring's root and tip, fill and rim, at full light.
    private val family = FloatArray(BLOOMS * RINGS * 4 * 3)

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
    private val front = TriangleMesh(maxVertices = 32_000)
    private val glows = TriangleMesh(maxVertices = 7_000)

    init {
        restoreRings()
        plantFirstBud()
    }

    /** How many changes of shape have started, for tests. */
    internal val changes: Int get() = cascades

    /** The length of a bar as the drawing reads it, for tests. */
    internal val barSeconds: Float get() = gestures.cycleSeconds

    /** The named shape ring [ring] of [bloom] leans to most right now, for tests. */
    internal fun shapeOf(bloom: Int, ring: Int): TwinBloomShape {
        val at = bloom * RINGS + ring
        var best = 0
        for (candidate in 1 until SHAPES) if (weight(at, candidate) > weight(at, best)) best = candidate
        return TwinBloomShape.entries[best]
    }

    /** How many blooms the garden holds now. */
    internal val bloomCount: Int get() = alive.count { it }

    /** Where bloom [i] stands across the screen as drawn, 0 left to 1 right. */
    internal fun bloomX(i: Int): Float = centreX[i] / screenWide

    /** How far back bloom [i] was planted, 0 near to 1 far. */
    internal fun bloomDepth(i: Int): Float = plantedDepth[i]

    /** The heard seconds since bloom [i] was planted. */
    internal fun bloomAge(i: Int): Float = age[i]

    /** How tall bloom [i]'s stem has grown, 0 to 1. */
    internal fun stemHeight(i: Int): Float = stem[i]

    /** The light the blooms give this frame. */
    internal val lightValue: Float get() = light

    /** The form the garden shows: Storm, Bud, Field or Bloom. */
    private val formName: String
        get() = when {
            throwAge < 1f -> FORMS[STORM]
            budding > 0.5f || silent -> FORMS[BUD]
            bloomCount >= 3 -> FORMS[FIELD]
            else -> FORMS[BLOOM]
        }

    /** How far [layer] is moved by the sway, as a share of the screen's width: 0 the ground, 1 the blooms, 2 the foreground. */
    internal fun parallaxOf(layer: Int): Float = PARALLAX * sway * when (layer) {
        GROUND_LAYER -> 0.5f
        BLOOM_LAYER -> 1f
        else -> 2f
    }

    /** The share of the carpet's sample points that hold ink. */
    internal fun carpetInkShare(): Float {
        var lit = 0
        var count = 0
        forEachCarpetPoint { _, ink ->
            count++
            if (ink > 0.1f) lit++
        }
        return if (count == 0) 0f else lit.toFloat() / count
    }

    /** The ink-weighted mean x of the carpet, in field units, or 0 when it is empty. */
    internal fun carpetCentroidX(): Float {
        var weighted = 0f
        var total = 0f
        forEachCarpetPoint { x, ink ->
            weighted += x * ink
            total += ink
        }
        return if (total <= 0f) 0f else weighted / total
    }

    private inline fun forEachCarpetPoint(visit: (Float, Float) -> Unit) {
        var y = -0.99f
        while (y < 1f) {
            var x = -carpet.aspect + 0.01f
            while (x < carpet.aspect) {
                visit(x, carpet.inkAt(x, y))
                x += CARPET_SAMPLE
            }
            y += CARPET_SAMPLE
        }
    }

    /**
     * Plants a bloom: in a free slot, or in place of the oldest when all five live. The oldest's
     * elements are thrown off first. It stands where the stereo balance puts it, further back the
     * brighter the sound, and grows its stem from the ground.
     */
    internal fun birth(centroid: Float = lastCentroid) {
        var slot = alive.indexOfFirst { !it }
        if (slot < 0) {
            slot = 0
            for (i in 1 until BLOOMS) if (age[i] > age[slot]) slot = i
            throwCoins(slot)
        }
        alive[slot] = true
        plantedX[slot] = 0.5f + BALANCE_REACH * balance
        plantedDepth[slot] = centroid.coerceIn(0f, 1f)
        age[slot] = 0f
        stem[slot] = 0f
        unfold[slot] = 1f
        unfoldTarget[slot] = 1f
        order[slot] = ++births
        // A new bloom wears the garden's shape at once.
        for (ring in 0 until RINGS) {
            val at = slot * RINGS + ring
            for (candidate in 0 until SHAPES) fromWeight[at * SHAPES + candidate] = if (candidate == shape) 1f else 0f
            target[at] = shape
            morph[at] = 1f
            wait[at] = 0
        }
        for (element in 0 until RINGS * MOST) regrow[slot * RINGS * MOST + element] = 1f
    }

    private var lastCentroid = 0f

    private fun plantFirstBud() {
        alive.fill(false)
        alive[0] = true
        plantedX[0] = 0.5f
        plantedDepth[0] = FIRST_DEPTH
        age.fill(0f)
        stem.fill(0f)
        order.fill(0)
        births = 0
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
        // Every element is one band: each bloom reads the whole spectrum across its three rings.
        perRing = if (bands.size < 2) FEWEST else (bands.size.toFloat() / RINGS).roundToInt().coerceIn(FEWEST, MOST)

        // A beat counts only while something is heard, so a pause or a silence holds every change.
        val beatIndex = gestures.cycles * 4 + (gestures.cyclePhase * 4f).toInt().coerceIn(0, 3)
        val onBeat = lastBeat >= 0 && beatIndex > lastBeat && frame.audible > 0.5f
        if (beatIndex > lastBeat) lastBeat = beatIndex
        if (onBeat) {
            beatsSinceChange++
            breakdownBeats++
            for (ring in wait.indices) if (wait[ring] > 0) wait[ring]--
        }

        // Only the cores breathe when nothing plays. A paused player keeps its last picture.
        if (!frame.held) breath += dt * TAU / BREATH_SECONDS
        punch.advance(dt)
        impulse.apply(frame, state.future?.nextEvent(AudioEventKind.LowTransient), KICK * (0.25f + 0.75f * state.motionScale))
        val left = energyOf(frame.scopeLeft)
        val right = energyOf(frame.scopeRight)
        val leaning = if (left + right > 1e-9f) (right - left) / (right + left) else 0f
        balance += (leaning - balance) * (1f - exp(-step / BALANCE_SECONDS))
        lastCentroid = frame.centroid
        // The stems grow with the energy heard, and the blooms age, on heard time.
        for (i in 0 until BLOOMS) {
            if (!alive[i]) continue
            age[i] += step
            stem[i] = min(1f, stem[i] + step * frame.energy.coerceIn(0f, 1f) / STEM_SECONDS)
        }
        for (i in 0 until STEM_POINTS) stemWave[i] = frame.scope.sampleAt(i / (STEM_POINTS - 1f))
        // A pause holds the sway; a silence lets it settle.
        presence.advance(frame.audible, if (frame.held) 0f else dt)
        silent = frame.audible < 0.5f
        slowLevel += (frame.level.coerceIn(0f, 1f) - slowLevel) * (1f - exp(-step / SLOW_LEVEL_SECONDS))
        for (i in 0 until BLOOMS) unfold[i] += (unfoldTarget[i] - unfold[i]) * (1f - exp(-step / (2f * bar)))
        carpet.size(kit.aspect)
        carpet.advance(drift, step)
        sway = sin(gestures.cyclePhase * TAU) * presence.value * state.motionScale.coerceIn(0f, 1f)
        leanTowardsKey(frame, state.palette, step)
        light = state.lightScale.coerceIn(0f, 1f) * max(IDLE_LIGHT * (1f - frame.audible), lightFor(state.energy))
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

        // A breakdown closes the blooms into buds until the next turn, two bars at least.
        if (gestures.breakdown) {
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

        // The drop's throw and the breakdown's buds come from the gestures above; a plain change of
        // form opens or wilts the blooms.
        if (evolution.morph && !evolution.bloom && !evolution.collapse) {
            for (i in 0 until BLOOMS) if (alive[i]) unfoldTarget[i] = OPEN_LEAST + (1f - OPEN_LEAST) * slowLevel
        }
        if (evolution.birth) birth()
        twinkle(step)
        layout(state)
        if (gestures.snare > 0f) breakOff(state, (2 + gestures.snareSpawn(3)).coerceIn(3, 6))
        if (gestures.hat > 0f) sparkle(gestures.hatSpawn(5), gestures.hat)
        fly(step)
        for (i in 0 until BLOOMS) kit.place(i, centreX[i] / screenWide, centreY[i] / screenTall, parallax = 0f)
    }

    /** The mean square of a waveform. */
    private fun energyOf(wave: FloatArray): Float {
        var sum = 0f
        for (sample in wave) sum += sample * sample
        return if (wave.isEmpty()) 0f else sum / wave.size
    }

    /** Turns every bloom's hue up to [KEY_TURN] degrees towards the key, as sure as the key is, on heard time. */
    private fun leanTowardsKey(frame: io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame, palette: VizPalette, heard: Float) {
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - (palette.baseHue + 0.5f * palette.hueSpan)
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyLean.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, heard)
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
        for (bloom in 0 until BLOOMS) startCascade(bloom, to, bands)
    }

    /** Starts a change at the ring that holds the loudest band; each ring further out or in waits a beat more. */
    private fun startCascade(bloom: Int, to: Int, bands: FloatArray) {
        val first = loudestRing(bands)
        for (ring in 0 until RINGS) {
            val at = bloom * RINGS + ring
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

    private fun loudestRing(bands: FloatArray): Int {
        var best = 0
        var most = -1f
        for (ring in 0 until RINGS) {
            for (element in 0 until perRing) {
                val level = response(bands, ring, element)
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
    private fun response(bands: FloatArray, ring: Int, element: Int): Float {
        if (bands.isEmpty()) return 0f
        val along = (ring * perRing + element + 0.5f) / (RINGS * perRing)
        val at = along * bands.size - 0.5f
        val lower = floor(at).toInt().coerceIn(0, bands.size - 1)
        val upper = (lower + 1).coerceAtMost(bands.size - 1)
        val mix = (at - floor(at)).coerceIn(0f, 1f)
        val raw = bands[lower] + (bands[upper] - bands[lower]) * mix
        val top = TOPS[(along * TOPS.size).toInt().coerceIn(0, TOPS.size - 1)]
        return ((raw - BAND_FLOOR) / (top - BAND_FLOOR)).coerceIn(0f, 1f)
    }


    /** How loud the music is on the shared light curve, 0 in silence and 1 when loud. */
    private fun loudOf(state: VizRenderState): Float {
        val scale = state.lightScale
        if (scale <= 1e-3f) return 0f
        return ((state.lift / scale - LIGHT_FLOOR) / (1f - LIGHT_FLOOR)).coerceIn(0f, 1f)
    }

    /** Places every bloom and its elements for this frame, and colours them. */
    private fun layout(state: VizRenderState) {
        val bands = state.frame.bands
        paint(state.palette)
        val loud = loudOf(state)
        val kick = punch.value.coerceIn(-0.3f, 1.4f)
        val open = OPEN_REST + (1f - OPEN_REST) * loud
        for (bloom in 0 until BLOOMS) {
            if (!alive[bloom]) {
                coreRadius[bloom] = 0f
                for (slot in bloom * RINGS * MOST until (bloom + 1) * RINGS * MOST) length[slot] = 0f
                continue
            }
            // A drop on the way gathers the garden a little toward the middle.
            val planted = plantedX[bloom] + (0.5f - plantedX[bloom]) * GATHER * buildUp
            val x = (planted + parallaxOf(BLOOM_LAYER)) * screenWide
            val y = (GROUND_Y - BUD_HEIGHT - stem[bloom] * STEM_REACH) * screenTall
            centreX[bloom] = x
            centreY[bloom] = y
            // A bloom further back is smaller.
            val scale = RADIUS * (0.6f + 0.4f * (1f - plantedDepth[bloom]))
            coreRadius[bloom] = coreSize.value * scale * (1f + 0.06f * sin(breath)) *
                (1f + 0.3f * kick.coerceAtLeast(0f)) * (1f + 0.3f * flare) * (1f - 0.3f * budding)
            val coreLight = (light * (1f + 0.12f * sin(breath)) + 0.7f * flare * state.lightScale).coerceIn(0f, 1f)
            coreColour[bloom] = colourOf(cbrt(coreLight), 0f, 0f).toArgb()
            // The core's glow takes the bloom's own tip colour, so each bloom lights its own flower.
            val middle = bloom * RINGS + 1
            val tipHue = family[(middle * 4 + TIP_FILL) * 3 + 2]
            val glowAlpha = ((0.3f + 0.3f * loud) * light + 0.5f * flare * state.lightScale).coerceIn(0f, 1f)
            val glowChroma = min(0.5f * family[(middle * 4 + TIP_FILL) * 3 + 1], mostChroma(0.85f, tipHue))
            coreGlow[bloom] = colourOf(0.85f, glowChroma, tipHue, glowAlpha).toArgb()
            sparkColour[bloom] = colourOf(0.97f * cbrt(light), 0.05f * cbrt(light), tipHue).toArgb()
            stemColour[bloom] = withAlpha(shade(bloom * RINGS, ROOT_RIM, cbrt(light.coerceIn(0f, 1f)), 0f), STEM_ALPHA)
            for (ring in 0 until RINGS) {
                val at = bloom * RINGS + ring
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
                    val level = response(bands, ring, element)
                    val grow = regrow[slot]
                    val body = base * petalLengthFor(level) * unfold[bloom] * grow * swell
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
                    fillRoot[slot] = shade(at, ROOT_FILL, k, (glint[slot] * 0.35f + colourSurge * 0.2f).coerceAtMost(1f))
                    fillTip[slot] = shade(at, TIP_FILL, k, pale)
                    rimRoot[slot] = shade(at, ROOT_RIM, k, 0f)
                    rimTip[slot] = shade(at, TIP_RIM, k, pale)
                }
            }
        }
    }

    /**
     * Reads each ring's colour family from its place on the palette: the genes' walk plus a step for
     * each birth before the bloom and a step for the ring, leaned toward the key. The tip takes that
     * place and the root a little before it, both through the vivid read.
     */
    private fun paint(palette: VizPalette) {
        for (bloom in 0 until BLOOMS) {
            if (!alive[bloom]) continue
            // The bass ring sits a step before the bloom's place, the treble ring a step after it.
            for (ring in 0 until RINGS) {
                val place = FIRST_PLACE + genes.walk + order[bloom] * HUE_STEP + (ring - 1) * RING_STEP
                val tipHue = hueAt(palette, place) + keyLean.value
                val rootHue = hueAt(palette, place - ROOT_STEP) + keyLean.value
                val cusp = cuspOf(tipHue)
                val at = bloom * RINGS + ring
                store(at, ROOT_FILL, ROOT_LIGHTNESS, rootHue)
                store(at, TIP_FILL, vividLightness(tipHue, cusp, (cusp + FILL_LIFT).coerceIn(0f, 1f)), tipHue)
                store(at, ROOT_RIM, (ROOT_LIGHTNESS + ROOT_RIM_LIFT).coerceAtMost(1f), rootHue)
                store(at, TIP_RIM, vividLightness(tipHue, cusp, (cusp + RIM_LIFT).coerceIn(0f, 1f)), tipHue)
            }
        }
    }

    /** The hue at [place] on [palette], wrapped as the palette's own cycle wraps it. */
    private fun hueAt(palette: VizPalette, place: Float): Float = palette.baseHue + (place - floor(place)) * palette.hueSpan

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

    private fun store(ring: Int, which: Int, lightness: Float, hue: Float) {
        val at = (ring * 4 + which) * 3
        family[at] = lightness
        family[at + 1] = (mostChroma(lightness, hue) - HEADROOM).coerceAtLeast(0f)
        family[at + 2] = hue
    }

    /**
     * One colour of a ring at light [k], the cube root of the share of light, so the colour dims
     * without greying. [pale] moves it towards white, for a sparkle or the drop under reduced motion.
     */
    private fun shade(ring: Int, which: Int, k: Float, pale: Float): Int {
        val at = (ring * 4 + which) * 3
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
     * edge of the frame within a bar, while new elements grow from the cores.
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
            for (at in 0 until BLOOMS * RINGS) {
                for (candidate in 0 until SHAPES) fromWeight[at * SHAPES + candidate] = if (candidate == DISC) 1f else 0f
                target[at] = DISC
                morph[at] = 1f
                wait[at] = 0
            }
            regrow.fill(1f)
            layout(state)
            for (bloom in 0 until BLOOMS) if (alive[bloom]) throwCoins(bloom)
            regrow.fill(0f)
        } else {
            for (bloom in 0 until BLOOMS) startCascade(bloom, DISC, state.frame.bands)
            colourSurge = 1f
        }
    }

    /** Throws every element of [bloom] as a disc, out from its core and away from the rest of the garden. */
    private fun throwCoins(bloom: Int) {
        val bar = gestures.cycleSeconds.coerceAtLeast(0.4f)
        var meanX = 0f
        var meanY = 0f
        var count = 0
        for (i in 0 until BLOOMS) if (alive[i]) { meanX += centreX[i]; meanY += centreY[i]; count++ }
        if (count > 0) { meanX /= count; meanY /= count }
        var awayX = centreX[bloom] - meanX
        var awayY = centreY[bloom] - meanY
        val apart = sqrt(awayX * awayX + awayY * awayY)
        if (apart < 1e-3f) {
            awayX = 0f
            awayY = 0f
        } else {
            awayX /= apart
            awayY /= apart
        }
        run {
            for (ring in 0 until RINGS) {
                for (element in 0 until perRing) {
                    val slot = (bloom * RINGS + ring) * MOST + element
                    val long = length[slot]
                    if (long <= 0f) continue
                    val a = angle[slot]
                    val x = rootX[slot] + cos(a) * long * 0.5f
                    val y = rootY[slot] + sin(a) * long * 0.5f
                    // Out from the core and away from the rest of the garden, scattered a little like a handful of coins.
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
        val living = gather()
        if (living == 0) return
        var made = 0
        var tries = 0
        while (made < count && tries < count * 4) {
            tries++
            val bloom = chosen[(snareTurn + made) % living]
            val element = (random.next() * perRing).toInt().coerceIn(0, perRing - 1)
            val slot = (bloom * RINGS + RINGS - 1) * MOST + element
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
            val ground = GROUND_Y * screenTall
            // A broken-off petal settles on the carpet when it reaches the ground, or below the
            // place it fades if it fades first.
            if (!pieceCoin[at] && (pieceY[at] >= ground || pieceAge[at] >= pieceLife[at])) {
                settle(pieceX[at])
                pieceAlive[at] = false
                continue
            }
            val gone = pieceX[at] < -margin || pieceX[at] > screenWide + margin ||
                pieceY[at] < -margin || pieceY[at] > screenTall + margin
            if (pieceAge[at] >= pieceLife[at] || gone) pieceAlive[at] = false
        }
    }

    /** Inks the carpet where a petal at [x] meets the ground. */
    private fun settle(x: Float) {
        if (x < 0f || x > screenWide) return
        val fieldX = (x / screenWide * 2f - 1f) * carpet.aspect
        carpet.disc(fieldX, GROUND_Y * 2f - 1f + PETAL_INK_RADIUS, PETAL_INK_RADIUS, PETAL_INK)
    }

    /** A hat makes a few tips sparkle, the outer ones most often: they flash pale and a small star shines on each. */
    private fun sparkle(count: Int, strength: Float) {
        val living = gather()
        if (living == 0) return
        for (made in 0 until count) {
            val bloom = chosen[(random.next() * living).toInt().coerceIn(0, living - 1)]
            val ring = if (random.next() < 0.7f) RINGS - 1 else RINGS - 2
            val element = (random.next() * perRing).toInt().coerceIn(0, perRing - 1)
            val slot = (bloom * RINGS + ring) * MOST + element
            if (length[slot] <= 0f) continue
            sparkSlot[nextSpark] = slot
            sparkAge[nextSpark] = 0f
            sparkStrength[nextSpark] = strength.coerceIn(0.3f, 1f)
            sparkAlive[nextSpark] = true
            nextSpark = (nextSpark + 1) % SPARKS
        }
    }

    /** Writes the living blooms into [chosen] and returns how many there are. */
    private fun gather(): Int {
        var living = 0
        for (i in 0 until BLOOMS) if (alive[i]) chosen[living++] = i
        return living
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
        drawGround(state)
        drawCarpet(state)
        glows.clear()
        for (bloom in 0 until BLOOMS) {
            if (!alive[bloom]) continue
            val radius = coreRadius[bloom] * unit * (CORE_GLOW + 2f * flare)
            glows.glow(centreX[bloom] * unit, centreY[bloom] * unit, radius, coreGlow[bloom], 24)
        }
        // Only the tips past four fifths of their reach glow: the hottest tenth of the picture.
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

    /** The ground below the line the stems rise from: the palette's low colour, faint at the line and stronger below. */
    private fun DrawScope.drawGround(state: VizRenderState) {
        val groundPx = GROUND_Y * size.height
        val low = state.palette.low
        val near = low.copy(alpha = GROUND_ALPHA * light)
        drawRect(
            Brush.verticalGradient(0f to near.copy(alpha = 0.4f * near.alpha), 1f to near, startY = groundPx, endY = size.height),
            topLeft = Offset(0f, groundPx), size = Size(size.width, size.height - groundPx),
        )
    }

    /** The carpet below the ground line, lit ink in four flat steps, moved half as far as the blooms by the sway. */
    private fun DrawScope.drawCarpet(state: VizRenderState) {
        if (!carpetProgram.available || !canDrawRuntimeShaders()) return
        val groundPx = GROUND_Y * size.height
        val ink = state.palette.low
        carpet.bindTo(carpetProgram)
        carpetProgram.uniform("uResolution", size.width, size.height)
        carpetProgram.uniform("uLight", light)
        carpetProgram.uniform("uInk", ink.red, ink.green, ink.blue)
        carpetProgram.uniform("uShift", parallaxOf(GROUND_LAYER) * size.width)
        val brush = carpetProgram.brush() ?: return
        drawRect(brush, topLeft = Offset(0f, groundPx), size = Size(size.width, size.height - groundPx))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val unit = size.minDimension
        // A rim of about a pixel and a half where one pixel is a point, so it still reads on a dense screen.
        val rim = RIM * density.coerceIn(1f, 2f)
        front.clear()
        // A broken-off element leaves from the back ring, so it flies out from under the figures; a
        // thrown disc flies towards the viewer, over them.
        addPieces(unit, rim, coins = false)
        // Far blooms first, so the nearer ones lie over them; each stem just before its bloom.
        val living = gather()
        for (i in 0 until living) drawOrder[i] = chosen[i]
        for (i in 1 until living) {
            val bloom = drawOrder[i]
            var j = i - 1
            while (j >= 0 && plantedDepth[drawOrder[j]] < plantedDepth[bloom]) {
                drawOrder[j + 1] = drawOrder[j]
                j--
            }
            drawOrder[j + 1] = bloom
        }
        for (i in 0 until living) {
            addStem(drawOrder[i], unit)
            addFigure(drawOrder[i], unit, rim)
        }
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
            val bloom = slot / (RINGS * MOST)
            front.spark(x, y, arm, max(1f, arm * 0.14f), withAlpha(sparkColour[bloom], left))
        }
        addForegroundPetal(unit, rim)
        drawMesh(front)
    }

    /**
     * One large, faint petal at the lower left, nearest the viewer, moved twice as far as the blooms
     * by the sway. It comes in with the music and leaves in a silence.
     */
    private fun addForegroundPetal(unit: Float, rim: Float) {
        val bloom = alive.indexOfFirst { it }
        val alpha = FOREGROUND_ALPHA * presence.value
        if (bloom < 0 || alpha < 0.01f) return
        val k = cbrt(light.coerceIn(0f, 1f))
        val root = shade(bloom * RINGS + 1, ROOT_FILL, k, 0f)
        val tip = shade(bloom * RINGS + 1, TIP_FILL, k, 0f)
        val x = (FOREGROUND_X + parallaxOf(FOREGROUND_LAYER)) * screenWide
        val y = FOREGROUND_Y * screenTall
        addElement(
            unit, x, y, FOREGROUND_TURN, FOREGROUND_LENGTH, FOREGROUND_LENGTH * ASPECT[PETAL], WIDEST[PETAL],
            root, tip, root, tip, rim, alpha,
        )
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

    /** A stem from the ground up to [bloom], with the live waveform running along it. */
    private fun addStem(bloom: Int, unit: Float) {
        val groundY = GROUND_Y * screenTall * unit
        val topY = centreY[bloom] * unit
        if (groundY - topY < 1f || front.vertexCount + 2 * STEM_POINTS > front.maxVertices) return
        val half = max(STEM_WIDTH_PIXELS, STEM_WIDTH * screenTall * unit) * 0.5f
        val x = centreX[bloom] * unit
        val colour = stemColour[bloom]
        var before = -1
        for (i in 0 until STEM_POINTS) {
            val t = i / (STEM_POINTS - 1f)
            val y = groundY + (topY - groundY) * t
            val wave = stemWave[i] * STEM_WAVE * unit
            val a = front.vertex(x + wave - half, y, colour)
            front.vertex(x + wave + half, y, colour)
            if (before >= 0 && a >= 0) front.quad(before, before + 1, a + 1, a)
            before = a
        }
    }

    /** A bloom, far ring first, so the inner rings lie over the outer ones, and its core last. */
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
        for (at in 0 until BLOOMS * RINGS) fromWeight[at * SHAPES + PETAL] = 1f
        target.fill(PETAL)
        morph.fill(1f)
        wait.fill(0)
    }

    override fun onReset() {
        punch.reset()
        impulse.reset()
        breath = 0f
        lastBeat = -1
        beatsSinceChange = LOCK_BEATS
        shape = PETAL
        pendingStep = false
        cascades = 0
        inBreakdown = false
        breakdownBeats = 0
        budding = 0f
        throwAge = 1f
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
        plantFirstBud()
        balance = 0f
        lastCentroid = 0f
        presence.reset()
        keyLean.reset()
        sway = 0f
        light = IDLE_LIGHT
        coreRadius.fill(0f)
        unfold.fill(1f)
        unfoldTarget.fill(1f)
        slowLevel = 0f
        silent = true
        carpet.clear()
    }

    internal companion object {
        /** The most blooms the garden holds. */
        const val BLOOMS = 5
        const val RINGS = 3
        const val SHAPES = 3
        const val SPOKE = 0
        const val PETAL = 1
        const val DISC = 2

        /** The most elements a ring holds, so a figure has up to 72, and the fewest. */
        const val MOST = 24
        const val FEWEST = 4
        const val SLOTS = BLOOMS * RINGS * MOST
        const val PIECES = 176
        const val SPARKS = 32

        /** A near bloom's radius at full length, in shorter sides of the screen. */
        const val RADIUS = 0.42f

        /** How long an element is in silence, as a share of its full length; the band adds the rest. */
        const val PETAL_FLOOR = 0.2f

        /** An element's length for a band at [level], as a share of its full length. */
        fun petalLengthFor(level: Float): Float = PETAL_FLOOR + (1f - PETAL_FLOOR) * level.coerceIn(0f, 1f)

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

        // The garden: the first bud's depth, how far the balance moves a birth across, and how fast
        // the balance follows the mix.
        const val FIRST_DEPTH = 0.3f
        const val BALANCE_REACH = 0.35f
        const val BALANCE_SECONDS = 0.5f
        /** How far a coming drop gathers the blooms toward the middle. */
        const val GATHER = 0.35f
        /** How far the sway moves the blooms, as a share of the screen's width; the ground half, the foreground twice. */
        const val PARALLAX = 0.01f
        const val GROUND_LAYER = 0
        const val BLOOM_LAYER = 1
        const val FOREGROUND_LAYER = 2

        // The foreground petal: where its root stands, its turn, length in shorter sides, and opacity.
        const val FOREGROUND_X = 0.04f
        const val FOREGROUND_Y = 1.02f
        const val FOREGROUND_TURN = -1.1f
        const val FOREGROUND_LENGTH = 0.45f
        const val FOREGROUND_ALPHA = 0.15f

        // The forms, and how far a change of form can close a bloom, with the slow level behind it.
        val FORMS = listOf("Bud", "Bloom", "Field", "Storm")
        const val BUD = 0
        const val BLOOM = 1
        const val FIELD = 2
        const val STORM = 3
        const val OPEN_LEAST = 0.4f
        const val SLOW_LEVEL_SECONDS = 4f

        // The carpet: its grid rows, half life in heard seconds, drift in field units a second, a
        // fallen petal's ink and radius, and the spacing of the points its tests read.
        const val CARPET_ROWS = 54
        const val CARPET_HALF_LIFE = 4f
        const val CARPET_DRIFT_X = 0.03f
        const val PETAL_INK = 0.8f
        const val PETAL_INK_RADIUS = 0.03f
        const val CARPET_SAMPLE = 0.02f

        /** The carpet: the field's ink held to four flat steps, lit from the upper left, in the palette's low colour. */
        val CARPET: String = """
uniform float2 uResolution;
uniform float uLight;
uniform float3 uInk;
uniform float uShift;
""" + FieldShader.SOURCE + """
half4 main(float2 position) {
    float2 at = position - float2(uShift, 0.0);
    float ink = bands(fieldAt(at).x, 4.0);
    float shine = lit(fieldSlope(at));
    float alpha = clamp(ink * uLight, 0.0, 1.0);
    float3 colour = clamp(uInk * (0.6 + 0.8 * shine), 0.0, 1.0);
    return half4(half3(colour * alpha), half(alpha));
}
"""

        // The stems: the ground line, a new bud's height over it and the reach a full stem adds, as
        // shares of the height, the heard
        // seconds of full energy a stem takes to grow, and the waveform along it.
        const val GROUND_Y = 0.82f
        const val BUD_HEIGHT = 0.25f
        const val STEM_REACH = 0.35f
        const val STEM_SECONDS = 20f
        const val STEM_POINTS = 32
        const val STEM_WAVE = 0.02f
        const val STEM_WIDTH = 0.003f
        const val STEM_WIDTH_PIXELS = 1.5f
        const val STEM_ALPHA = 0.5f
        /** The ground's opacity at the bottom of the frame, at full light. */
        const val GROUND_ALPHA = 0.4f

        // Each bloom's colour: the step on the palette per birth, the root's place before the tip,
        // and the lean toward the key.
        const val HUE_STEP = 0.13f
        /** How far apart on the palette the bass, middle and treble rings of one bloom sit. */
        const val RING_STEP = 0.08f
        /** The first bloom's place on the palette: on Prism, a magenta tip on a violet root. */
        const val FIRST_PLACE = 0.9f
        const val ROOT_STEP = 0.1f
        const val ROOT_LIGHTNESS = 0.45f
        const val KEY_TURN = 20f
        const val KEY_TURN_PER_SECOND = 12f

        const val LOCK_BEATS = 16
        const val MORPH_BEATS = 0.8f
        const val REGROW_BEATS = 1.5f
        const val BUD_BEATS = 8
        const val BUD_BARS = 0.35f
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
