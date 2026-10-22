package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.AnticipatedImpulse
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.Particles
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
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.spark
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.strip
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Ease
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Iris: an eye that is a world. One drawing made of three, with the credit of the two ports.
 *
 * - Fibres: Iris, one of the seven visualisers of Vissonance by Tariq Soliman.
 *   Page https://tariqksoliman.github.io/Vissonance/, repository
 *   https://github.com/tariqksoliman/Vissonance (`scripts/visualizers/Iris.js` and
 *   `scripts/Spectrum.js`). Licence as found: MIT, "Copyright (c) 2020 Tariq Soliman". Written in
 *   March 2017 on three.js r84.
 * - Rings: the per dot sample mapping of "Audible Visuals" by Sonia Boller (its Wavy Spiral layout).
 *   Page https://soniaboller.github.io/audible-visuals/, repository
 *   https://github.com/soniaboller/soniaboller.github.io (`audible-visuals/scripts/spiral.js`);
 *   older copy https://github.com/soniaboller/audible-visuals. Licence as found: the Apache License
 *   2.0 text in `soniaboller/audible-visuals`, with the copyright line left as the unfilled template.
 *   2016.
 * - Petals: Kaleidoscope, this library's own drawing.
 *
 * The pupil is a dark tunnel. Round it, 128 fibres are the spectrum, mirrored left and right with
 * the bass at the top, bright where they leave the pupil and fading past the frame. Outside the
 * bright part of the fibres, petals are the stereo trace folded into wedges, as large as the trace
 * is loud. Further out, rings of dots spread and fade: each ring is the waveform at its birth, one
 * born every visual cycle, on every kick and on every change of form, so the distance from the
 * middle is how long ago. Two dark lids open with the music; a silence or a breakdown closes them
 * to a slit.
 *
 * Forms, at the pace of the evolution pacer: Eye (fibres and pupil, four folds, few rings), Flower
 * (petals, five to eight folds), Mandala (three to twelve folds, dense rings), taking turns. A morph
 * eases the fold count, the ring density and the pupil's rest over one to four cycles; a birth
 * blooms a new fold count out of the pupil while the old one fades. A drop or a surge is the Vortex
 * for one cycle: the pupil opens to most of the frame, the rings fly inward, and the petals part into
 * Kaleidoscope's magenta and cyan images and slide back together. A kick dilates the pupil, a snare
 * throws shards off the petal tips, and a hat sparkles there.
 *
 * Differences from the Vissonance page:
 * - The one hue of every spoke, `250 - loudness * 2.2`, is gone. Each fibre's hue comes from the
 *   palette, spread over at least 120 degrees, walked by the genes and leaned towards the key; its
 *   saturation and light follow its own band.
 * - The pupil's rest of 65 units is the Eye form's. The other forms ease it to their own, a kick
 *   dilates it and the Vortex opens it. In the Eye form at rest the law is the page's.
 * - Every colour is multiplied by the shared light. The analyser, the bars, the geometry and the
 *   depth shading are the page's, read sixty times a heard second.
 *
 * Differences from Audible Visuals:
 * - The dots lie on rings round the eye, not on a spiral, and the spiral's drifting angle is gone.
 * - A ring holds the frame's 5 ms waveform under the shared gain at 128 points, not the page's 2048.
 * - The dots take the palette's colours; a sample moves a dot's hue and light, where the page moved
 *   it between purple, red and azure.
 */
internal class Iris : Layered(
    name = "Iris",
    bucket = VizEnergy.Mid,
    kit = Kit(
        seed = 1_207L,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f, seed = 1_207),
    ),
) {

    override val mapping: VizMapping by mappingOf(
        // Each fibre's saturation and light follow its own band, and its inner end follows the page's
        // analyser. The petals and the rings lean their hue with the register, where the spectrum's weight sits.
        VizDrive(VizDriver.Bands, VizProperty.Colour),
        // The petals are the stereo trace, and every new ring keeps the waveform it was born with.
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        // A kick dilates the pupil, pushed early by the queued audio, and gives birth to a ring.
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(DILATE_SECONDS)),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(RING_SECONDS)),
        // A snare throws shards off the petal tips. A hat sparkles there too, but a sparkle is a few
        // pixels for under half a second, under what the declaration probe can see, so it is not declared.
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(SHARD_LIFE)),
        // A drop is the Vortex: the pupil opens, the rings fly in, the petals part into magenta and cyan.
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(0.05f)),
        VizDrive(VizDriver.Drop, VizProperty.Colour, VizCurve.Discrete, VizResponse.envelope(0.05f)),
        // A breakdown closes the eye to a slit.
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(LID_SECONDS)),
        // A section changes the form at once and sends out a ring.
        VizDrive(VizDriver.Section, VizProperty.Cut, VizCurve.Discrete),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        // The petals turn two wedges a visual cycle, which the pulse or, without one, the mood paces.
        // The pulse's share is not declared: against the free cycle its turn differs by too little
        // at the probe's size, where the petals are a few pixels long.
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        silence = VizSilence.Still,
    )

    // Sharp lines on black are the look; nothing should haze them.
    override val post: PostSpec get() = PostSpec.Off
    override val paintsWholeScreen: Boolean get() = true
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f

    /** Draws the fibres alone, for the tests that pin the page's geometry. */
    internal var fibresOnly: Boolean = false

    /** The page's analyser: `fftSize = 4096`, the Web Audio defaults for the rest, bins laid out for 44.1 kHz. */
    internal val analyser = WebAudioAnalyser(
        fftSize = FFT_SIZE, smoothing = 0.8f, minDecibels = -100f, maxDecibels = -30f, sampleRate = PAGE_SAMPLE_RATE,
    )

    /** `spectrum.GetVisualBins(dataArray, 128, 4, 1300)`. Only the first 64 bars are drawn. */
    internal val bins = VissonanceBins(NUM_BARS, SPECTRUM_START, SPECTRUM_END)

    /** `visualArray` from the last read. */
    internal val visual = FloatArray(NUM_BARS)

    /** The loudness of the last read: the plain mean of every byte. Iris does not smooth it. */
    internal var loudness = 0f
        private set

    private var owed = 0f
    private var started = false

    // Each fibre's colour this frame, 0 to 1 a channel, before the depth shading.
    private val barRed = FloatArray(NUM_BARS / 2)
    private val barGreen = FloatArray(NUM_BARS / 2)
    private val barBlue = FloatArray(NUM_BARS / 2)

    private val petals = IrisPetals()
    private val rings = IrisRings(RING_POOL, RING_DOTS)
    private val particles = Particles(POOL)
    private val tones = IntArray(IrisPetals.TONES)
    private val tip = FloatArray(2)

    private val fibres = TriangleMesh(maxVertices = FIBRE_VERTICES, maxIndices = FIBRE_INDICES)
    private val petalMesh = TriangleMesh(
        maxVertices = PETAL_BATCH * IrisPetals.SEED * 4,
        maxIndices = PETAL_BATCH * (IrisPetals.SEED - 1) * 18,
    )
    private val dotMesh = TriangleMesh(maxVertices = RING_POOL * RING_DOTS * 7 + 8, maxIndices = RING_POOL * RING_DOTS * 18 + 8)
    private val sparkMesh = TriangleMesh(maxVertices = POOL * 10, maxIndices = POOL * 24)
    private val lidMesh = TriangleMesh(maxVertices = LID_POINTS * 8 + 8, maxIndices = LID_POINTS * 24 + 8)
    private val lidXs = FloatArray(LID_POINTS)
    private val lidYs = FloatArray(LID_POINTS)

    // The settled form and the numbers it eases towards. The slews count in visual cycles.
    private var form = EYE
    private val folds = Slew(maxPerSecond = FOLDS_PER_CYCLE, initial = EYE_FOLDS)
    private var foldTarget = EYE_FOLDS
    private val density = Slew(maxPerSecond = DENSITY_PER_CYCLE, initial = EYE_DENSITY)
    private var densityTarget = EYE_DENSITY
    private val rest = Slew(maxPerSecond = REST_PER_CYCLE, initial = INNER_BASE)
    private var restTarget = INNER_BASE
    private val petalWeight = Slew(maxPerSecond = WEIGHT_PER_CYCLE, initial = EYE_PETALS)
    private var petalTarget = EYE_PETALS
    private val fibreWeight = Slew(maxPerSecond = WEIGHT_PER_CYCLE, initial = 1f)
    private var fibreTarget = 1f

    // The Vortex: cycles of it still to run, and how far it has opened, 0 to 1.
    private var vortexLeft = 0f
    private val vortex = Slew(maxPerSecond = VORTEX_PER_CYCLE)

    // A birth: the old fold count fades while the new one grows out of the pupil over a cycle.
    private var bornFrom = EYE_FOLDS.toInt()
    private var growth = 1f

    // The petals turn one wedge a visual cycle while the music is heard; every morph turns them back.
    private var petalTurn = 0f
    private var spinSign = 1f
    private var lastPhase = -1f
    private var lastCycles = 0
    private var ringHue = 0f

    // The pupil's kick: a spring pushed early by the queued audio.
    private val dilation = Spring(stiffness = DILATE_STIFFNESS, damping = DILATE_DAMPING)
    private val impulse = AnticipatedImpulse(dilation)

    // The lids: open with the music, a slit in a silence, closed by a breakdown until the next turn.
    private val lids = Envelope(attackPerSecond = LID_OPEN, releasePerSecond = LID_CLOSE, initial = OPEN_SLIT)
    private var lidTarget = OPEN_SLIT
    private var closed = false
    private var closedTurns = 0

    // The drop's two lens images, which part and slide back together over a cycle.
    private var splitting = false
    private var splitFrom = 0f
    private var splitTo = 0
    private var meet = 0f
    private var build = 0f
    private var apart = 0f
    private var cyanDim = 0f
    private val anaglyph: Boolean get() = splitting || build > 0.01f

    private val keyLean = Slew(maxPerSecond = KEY_TURN_PER_SECOND)

    // Where the spectrum's weight sits, 0 low to 1 high, settled over REGISTER_SECONDS. Below zero until the first frame.
    private var register = -1f

    // The pupil's mean radius in centred units, from the last advance.
    private var pupilUnits = FOCAL * INNER_BASE / FAR_DEPTH

    // The cached page background, rebuilt when the frame's size changes.
    private var backgroundWidth = -1f
    private var backgroundHeight = -1f
    private var backgroundBrush: Brush? = null

    /** The form on screen: Eye, Flower, Mandala or Vortex. */
    internal val formName: String get() = if (vortexLeft > 0f) FORMS[VORTEX] else FORMS[form]

    override val forms: FormReadout get() = FormReadout(formName, evolution.morphs, evolution.births)

    /** How open the lids are: near 0 a slit, 1 out of sight. */
    internal val openness: Float get() = lids.value
    internal val ringsAlive: Int get() = rings.alive
    internal val ringsBorn: Long get() = rings.born

    /** How far the furthest petal point reaches, in petal lengths. */
    internal val petalReach: Float get() = petals.reach
    internal val foldCount: Float get() = folds.value

    override fun advance(state: VizRenderState) {
        val frame = state.frame
        val dt = state.deltaSeconds
        val heard = state.stepSeconds
        val cycles = heard / gestures.cycleSeconds.coerceAtLeast(0.4f)
        val live = !frame.held

        readAnalyser(frame, dt)
        if (live) petals.read(frame.scopeLeft, frame.scopeRight, frame.waveformGain)
        leanTowardsKey(frame, state.palette, heard)
        followRegister(frame, heard)

        // A breakdown closes the eye until the next turn; a drop or a surge opens the Vortex for a cycle.
        if (evolution.collapse) {
            closed = true
            closedTurns = gestures.turns
        } else if (closed && live && gestures.turns != closedTurns) {
            closed = false
        }
        if (evolution.morph) morph()
        if (evolution.birth) birth()
        if (evolution.bloom || gestures.surge) {
            vortexLeft = VORTEX_CYCLES
            closed = false
            startSplit()
        }
        vortexLeft = (vortexLeft - cycles).coerceAtLeast(0f)
        vortex.advance(if (vortexLeft > 0f) 1f else 0f, cycles)
        folds.advance(foldTarget, cycles)
        density.advance(densityTarget, cycles)
        rest.advance(restTarget, cycles)
        petalWeight.advance(petalTarget, cycles)
        fibreWeight.advance(fibreTarget, cycles)
        growth = (growth + cycles / BIRTH_CYCLES).coerceAtMost(1f)

        // The petals turn two wedges a visual cycle, as fast as the pulse or the mood runs the cycle.
        val phase = gestures.cyclePhase
        if (lastPhase >= 0f) {
            var step = phase - lastPhase
            if (step < -0.5f) step += 1f
            petalTurn += step * frame.audible * TAU / folds.value.coerceAtLeast(3f) * spinSign * TURN_WEDGES
        }
        lastPhase = phase

        impulse.apply(frame, state.future?.nextEvent(AudioEventKind.LowTransient), DILATE_KICK * (0.25f + 0.75f * state.motionScale))
        dilation.advance(dt)
        pupilUnits = FOCAL * meanInner() / FAR_DEPTH

        // Rings: one each visual cycle, one on every kick, one on every change of form.
        val wrapped = gestures.cycles != lastCycles
        lastCycles = gestures.cycles
        if (frame.audible > 0f) {
            val most = (RING_KEEP_LEAST + (RING_POOL - RING_KEEP_LEAST) * density.value).roundToInt()
            val start = pupilUnits + RIM_UNITS + PETAL_UNITS * petalWeight.value * petals.reach
            if (wrapped) ringOf(frame, start, CYCLE_RING, most)
            if (gestures.kick > 0f) ringOf(frame, start, KICK_RING + (1f - KICK_RING) * gestures.kick, most)
            if (evolution.morph) ringOf(frame, start, MORPH_RING, most)
        }
        val outward = 1f - (1f + VORTEX_PULL) * vortex.value
        rings.advance(dt * state.paced(RING_SPEED) * outward, cycles, pupilUnits)

        if (live) {
            lidTarget = when {
                vortexLeft > 0f -> 1f
                closed -> OPEN_SLIT
                else -> openFor(frame)
            }
        }
        lids.advance(lidTarget, dt)

        if (splitting && live && gestures.cycles >= splitTo) splitting = false
        if (live) signature(state, gestures.cycles + gestures.cyclePhase, dt)

        spawn()
        particles.advance(dt, drag = DRAG)
        kit.place(0, 0.5f, 0.5f, parallax = 0f)
    }

    /** Runs the page's reads this display frame owes: one per sixtieth of a heard second. */
    private fun readAnalyser(frame: SpectrumFrame, dt: Float) {
        val heard = dt * frame.audible
        val reads = if (!started) {
            // The page renders once as soon as the visualiser is made, whatever is playing.
            started = true
            1
        } else {
            owed = minOf(owed + heard, MAX_OWED)
            var due = 0
            while (owed >= READ_PERIOD / 2f) {
                owed -= READ_PERIOD
                due++
            }
            due
        }
        if (reads > 0) {
            if (frame.power != null) {
                analyser.update(frame)
                val bytes = analyser.frequencyBytes
                loudness = VissonanceBins.loudness(bytes)
                bins.visualBins(bytes, visual)
            } else {
                repeat(reads) { barsFromBands(frame.bandsRel) }
            }
        }
    }

    /**
     * A frame without a fine spectrum, such as one an embedder builds from bands alone, feeds the bars
     * from its bands instead of leaving the fibres still. Each bar takes the band its fibre's colour
     * takes, through the page's gate, and settles as the page's analyser smooths.
     */
    private fun barsFromBands(bands: FloatArray) {
        val keep = analyser.smoothing
        val last = NUM_BARS / 2 - 1f
        var sum = 0f
        for (bar in 0 until NUM_BARS / 2) {
            val band = bands.sampleAt(bar / last).coerceIn(0f, 1f)
            val byte = max((band.toDouble().pow(bins.exponent(bar)) * VissonanceBins.HEIGHT).toFloat(), 1f)
            visual[bar] += (byte - visual[bar]) * (1f - keep)
            sum += band
        }
        val mean = sum / (NUM_BARS / 2) * VissonanceBins.HEIGHT.toFloat()
        loudness += (mean - loudness) * (1f - keep)
    }

    /** Turns every hue up to [KEY_TURN] degrees towards the key, as sure as the key is. */
    private fun leanTowardsKey(frame: SpectrumFrame, palette: VizPalette, heard: Float) {
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - (palette.baseHue + 0.5f * spanOf(palette))
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyLean.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, heard)
    }

    /** Follows where the weight of the spectrum sits, so the petals and the rings can take the register's colour. */
    private fun followRegister(frame: SpectrumFrame, heard: Float) {
        val bands = frame.bandsRel
        if (bands.size < 2) return
        var total = 0f
        var weighted = 0f
        for (band in bands.indices) {
            val value = bands[band].coerceIn(0f, 1f)
            total += value
            weighted += value * band
        }
        if (total < REGISTER_LEAST) return
        val target = weighted / total / (bands.size - 1)
        register = if (register < 0f) target else register + (target - register) * (1f - exp(-heard / REGISTER_SECONDS))
    }

    /** The hue lean of the petals and the rings, in degrees: dark music turns one way round the wheel, bright music the other. */
    private fun registerLean(): Float = if (register < 0f) 0f else (register - REGISTER_MIDDLE) * REGISTER_TURN

    /** The next settled form in turn, with targets chosen inside it. Eye, Flower and Mandala take turns. */
    private fun morph() {
        form = (form + 1) % SETTLED_FORMS
        spinSign = -spinSign
        when (form) {
            EYE -> {
                foldTarget = EYE_FOLDS
                densityTarget = EYE_DENSITY
                restTarget = INNER_BASE
                petalTarget = EYE_PETALS
                fibreTarget = 1f
            }
            FLOWER -> {
                foldTarget = (5 + (random.next() * 4f).toInt()).coerceAtMost(8).toFloat()
                densityTarget = 0.4f + 0.2f * random.next()
                restTarget = FLOWER_REST + 10f * random.signed()
                petalTarget = 1f
                fibreTarget = FLOWER_FIBRES
            }
            else -> {
                foldTarget = (3 + (random.next() * 10f).toInt()).coerceAtMost(12).toFloat()
                densityTarget = 1f
                restTarget = MANDALA_REST + 10f * random.signed()
                petalTarget = MANDALA_PETALS
                fibreTarget = MANDALA_FIBRES
            }
        }
    }

    /** A new fold symmetry blooms out of the pupil: the old count fades as the new one grows over a cycle. */
    private fun birth() {
        bornFrom = folds.value.roundToInt().coerceIn(3, MOST_FOLDS)
        val next = when (form) {
            EYE -> if (bornFrom != 4) 4 else 6
            FLOWER -> {
                val pick = 5 + (random.next() * 4f).toInt().coerceAtMost(3)
                if (pick == bornFrom) (if (pick < 8) pick + 1 else 5) else pick
            }
            else -> {
                val pick = 3 + (random.next() * 10f).toInt().coerceAtMost(9)
                if (pick == bornFrom) (if (pick < 12) pick + 1 else 3) else pick
            }
        }
        foldTarget = next.toFloat()
        folds.reset(foldTarget)
        growth = 0f
    }

    private fun startSplit() {
        splitting = true
        splitFrom = gestures.cycles + gestures.cyclePhase
        // A drop late in a cycle meets on the cycle after next, so the slide always takes most of a cycle.
        splitTo = gestures.cycles + if (gestures.cyclePhase > 0.5f) 2 else 1
    }

    // The drop: two images a quarter of the frame apart that slide back together over a cycle.
    private fun signature(state: VizRenderState, bar: Float, dt: Float) {
        val progress = if (splitting) ((bar - splitFrom) / (splitTo - splitFrom)).coerceIn(0f, 1f) else 1f
        val eased = progress * progress * (3f - 2f * progress)
        // Where the queued audio already holds the drop, the images start to part before it lands.
        val coming = if (splitting) null else state.future?.nextEvent(AudioEventKind.Drop)
        val approach = coming?.let { (1f - it.secondsUntil / BUILD_SECONDS).coerceIn(0f, 1f) } ?: 0f
        build = if (approach > build) approach else (build - dt * BUILD_FALL).coerceAtLeast(0f)
        val spread = if (splitting) 1f - eased else BUILD_SHARE * build
        apart = SPLIT * spread * state.motionScale
        meet = if (splitting) IrisPetals.smooth(MEET_FROM, 1f, progress) else 0f
        // Under reduced motion the images barely part, so the drop shows as colour.
        cyanDim = if (splitting) (1f - state.motionScale) * COLOUR_SIGNATURE * (1f - eased) else 0f
    }

    private fun ringOf(frame: SpectrumFrame, start: Float, strength: Float, most: Int) {
        ringHue += RING_HUE_STEP
        rings.birth(frame.scope, frame.waveformGain, start, ringHue, petalTurn, strength, most)
    }

    /** How far the lids open for this frame: a slit in a silence, wide open from a moderate level up. */
    private fun openFor(frame: SpectrumFrame): Float {
        val t = ((frame.energy - QUIET_ENERGY) / (LOUD_ENERGY - QUIET_ENERGY)).coerceIn(0f, 1f)
        val open = OPEN_SLIT + (1f - OPEN_SLIT) * t * t * (3f - 2f * t)
        return OPEN_SLIT + (open - OPEN_SLIT) * frame.audible
    }

    /** The page's law for bar [bar]'s inner end, with the form's rest, the Vortex and the kick's dilation. */
    private fun innerOf(bar: Int): Float =
        visual[bar] / 2f + restNow() + loudness / LOUDNESS_DIVISOR + DILATE_UNITS * dilation.value.coerceIn(-0.5f, 1.5f)

    private fun restNow(): Float = rest.value + (VORTEX_REST - rest.value) * vortex.value

    private fun meanInner(): Float {
        var sum = 0f
        for (bar in 0 until NUM_BARS / 2) sum += innerOf(bar)
        return sum / (NUM_BARS / 2)
    }

    // Shards on a snare and sparkles on a hat, at the tip of every petal.
    private fun spawn() {
        val shards = gestures.snareSpawn(SHARDS)
        val sparkles = gestures.hatSpawn(SPARKLES)
        if (shards == 0 && sparkles == 0) return
        val count = folds.value.roundToInt().coerceIn(1, MOST_FOLDS)
        val aspect = kit.aspect
        val base = pupilUnits + RIM_UNITS
        val length = PETAL_UNITS * petalWeight.value
        for (mirror in 0 until count) {
            petals.tipAt(mirror, count, petalTurn, base, length, tip)
            // During the drop the tips alternate between the two images.
            val image = if (!anaglyph) 0f else if (mirror % 2 == 0) -apart else apart
            val colour = if (!anaglyph) petals.tone[petals.tip] else if (mirror % 2 == 0) 1f else -1f
            // From centred units to shares of the screen: one unit is half the height.
            val x = 0.5f + image + tip[0] / (2f * aspect)
            val y = 0.5f + tip[1] / 2f
            val outward = atan2(tip[1], tip[0])
            repeat(shards) {
                val angle = outward + (random.next() - 0.5f) * SHARD_SPREAD
                val speed = SHARD_SPEED * (0.6f + 0.8f * random.next())
                particles.spawn(
                    atX = x, atY = y, speedX = cos(angle) * speed / (2f * aspect), speedY = sin(angle) * speed / 2f,
                    seconds = SHARD_LIFE * (0.7f + 0.6f * random.next()), tintPosition = colour,
                    radius = SHARD_SIZE * (0.7f + 0.6f * random.next()), spin = random.signed() * 5f,
                    kind = SHARD, angle = angle,
                )
            }
            // A sparkle sits just past the tip, where the line does not already fill it with light.
            val beyondX = x + tip[0] * (BEYOND - 1f) / (2f * aspect)
            val beyondY = y + tip[1] * (BEYOND - 1f) / 2f
            repeat(sparkles) {
                particles.spawn(
                    atX = beyondX + random.signed() * JITTER / aspect, atY = beyondY + random.signed() * JITTER,
                    speedX = 0f, speedY = 0f, seconds = SPARKLE_LIFE * (0.7f + 0.6f * random.next()),
                    tintPosition = colour, radius = SPARKLE_SIZE * (0.6f + 0.8f * random.next()), kind = SPARKLE,
                )
            }
        }
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        drawRect(Color.Black)
        if (size.width != backgroundWidth || size.height != backgroundHeight) {
            backgroundWidth = size.width
            backgroundHeight = size.height
            // `radial-gradient(circle, #060606, #010101)`: a circle reaching the farthest corner.
            val reach = sqrt(size.width * size.width + size.height * size.height) / 2f
            backgroundBrush = Brush.radialGradient(
                colors = listOf(Color(0xFF060606), Color(0xFF010101)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = reach.coerceAtLeast(1f),
            )
        }
        backgroundBrush?.let { drawRect(it, alpha = state.lightScale.coerceIn(0f, 1f)) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (size.width <= 0f || size.height <= 0f) return
        val light = state.lift.coerceIn(0f, 1f)
        val middleX = size.width / 2f
        val middleY = size.height / 2f
        paintFibres(state.palette, state.frame)
        drawFibres(middleX, middleY, light)
        if (fibresOnly) return
        paintTones(state.palette)
        drawPetalSets(middleX, middleY, light)
        with(rings) {
            drawRings(dotMesh, middleX, middleY, middleY, state.palette.baseHue, spanOf(state.palette), keyLean.value + registerLean(), genes.walk, light)
        }
        drawSparks(light)
        drawLids(state.palette, light)
    }

    /**
     * Each fibre's colour: a hue from the palette spread across the bars, walked by the genes and
     * leaned towards the key, with its saturation and light following its own band.
     */
    private fun paintFibres(palette: VizPalette, frame: SpectrumFrame) {
        val span = spanOf(palette)
        val bands = frame.bandsRel
        val last = NUM_BARS / 2 - 1f
        for (bar in 0 until NUM_BARS / 2) {
            val share = bar / last
            val band = bands.sampleAt(share).coerceIn(0f, 1f)
            val hue = palette.baseHue + Ease.pingPong(FIBRE_HUE + genes.walk + share * FIBRE_SPREAD + band * FIBRE_HEAT) * span + keyLean.value
            val chroma = (FIBRE_GREY + (1f - FIBRE_GREY) * band) * mostChroma(FIBRE_LIGHTNESS, hue)
            val colour = colourOf(FIBRE_LIGHTNESS, chroma, hue)
            // The light rises with the square of the band, so a loud band's fibre stands out from the rest.
            val value = FIBRE_FLOOR + (1f - FIBRE_FLOOR) * band * band
            barRed[bar] = colour.red * value
            barGreen[bar] = colour.green * value
            barBlue[bar] = colour.blue * value
        }
    }

    /** The petals' colours, from right-leaning through centred, which is pale, to left-leaning. */
    private fun paintTones(palette: VizPalette) {
        val span = spanOf(palette)
        for (step in 0 until IrisPetals.TONES) {
            val code = step * 2f / (IrisPetals.TONES - 1) - 1f
            val hue = palette.baseHue + Ease.pingPong(PETAL_HUE + genes.walk + code * TONE_SPREAD) * span + keyLean.value + registerLean()
            val lightness = PETAL_LIGHTNESS - 0.1f * abs(code)
            val chroma = (0.65f + 0.35f * abs(code)) * mostChroma(lightness, hue)
            tones[step] = colourOf(lightness, chroma, hue).toArgb() and 0xFFFFFF
        }
    }

    private fun DrawScope.drawFibres(middleX: Float, middleY: Float, light: Float) {
        val focal = middleY * FOCAL
        // A spoke must run past the farthest corner: the page's strips always do.
        val corner = sqrt(middleX * middleX + middleY * middleY)
        val weight = light * fibreWeight.value
        fibres.clear()
        for (bar in 0 until NUM_BARS / 2) {
            val inner = innerOf(bar)
            val angle = bar * (2.0 * PI / NUM_BARS) + PI / NUM_BARS
            for (side in 0..1) {
                val turn = if (side == 0) angle else -angle
                addSpoke(inner, turn, middleX, middleY, focal, corner, barRed[bar], barGreen[bar], barBlue[bar], weight)
            }
        }
        drawMesh(fibres)
    }

    /**
     * One strip, 3 units wide, from its inner end [inner] units off the axis at the far edge to 16.6
     * units off the axis 3.8 units in front of the camera, turned [turn] radians about the axis. Its
     * steps are spaced evenly on the screen, from the pupil to beyond [corner], so the colour, which
     * follows the depth, is sampled where it shows.
     */
    private fun addSpoke(
        inner: Float, turn: Double, middleX: Float, middleY: Float, focal: Float, corner: Float,
        red: Float, green: Float, blue: Float, light: Float,
    ) {
        val cosTurn = cos(turn).toFloat()
        val sinTurn = sin(turn).toFloat()
        val first = fibres.vertexCount
        val start = focal * inner / FAR_DEPTH
        val end = max(corner * OVERSHOOT, start * 2f)
        for (index in 0 until STEPS) {
            val radius = start + (end - start) * index / (STEPS - 1)
            val t = along(radius, inner, focal)
            val y = inner + t * (NEAR_Y - inner)
            val depth = FAR_DEPTH - t * (FAR_DEPTH - NEAR_DEPTH)
            val colour = colourAt(depth, red, green, blue, light)
            // The strip's two edges, 1.5 units either side of its centre line, turned with it.
            for (edge in 0..1) {
                val x = if (edge == 0) -HALF_WIDTH else HALF_WIDTH
                val worldX = x * cosTurn - y * sinTurn
                val worldY = x * sinTurn + y * cosTurn
                fibres.vertex(middleX + focal * worldX / depth, middleY - focal * worldY / depth, colour)
            }
        }
        for (index in 0 until STEPS - 1) {
            val a = first + index * 2
            fibres.quad(a, a + 1, a + 3, a + 2)
        }
    }

    private fun DrawScope.drawPetalSets(middleX: Float, middleY: Float, light: Float) {
        val line = LINE * (size.minDimension / UNIT_SIDE).coerceAtLeast(1f)
        val length = PETAL_UNITS * petalWeight.value * middleY
        if (growth < 1f) {
            petalImages(bornFrom, middleX, middleY, 1f, length, light * (1f - growth), line)
            petalImages(folds.value.roundToInt(), middleX, middleY, growth, length, light, line)
            return
        }
        // Between two whole counts, both are drawn, each with its share of the light.
        val low = floor(folds.value).toInt()
        val share = folds.value - low
        if (share < 0.98f) petalImages(low, middleX, middleY, 1f, length, light * (1f - share), line)
        if (share > 0.02f) petalImages(low + 1, middleX, middleY, 1f, length, light * share, line)
    }

    /** One set of petals, [grow] of the way out of the pupil; during a drop, its two lens images. */
    private fun DrawScope.petalImages(count: Int, middleX: Float, middleY: Float, grow: Float, length: Float, alpha: Float, line: Float) {
        val base = (pupilUnits + RIM_UNITS * grow) * middleY
        with(petals) {
            if (!anaglyph) {
                drawPetals(petalMesh, count, middleX, middleY, base, length * grow, petalTurn, tones, alpha, IrisPetals.NORMAL, 0f, line)
                return
            }
            // The two images each carry half the light as they meet, so together they come out white.
            val shift = apart * size.width
            val each = alpha * (1f - 0.5f * meet)
            drawPetals(petalMesh, count, middleX - shift, middleY, base, length * grow, petalTurn, tones, each, IrisPetals.MAGENTA_IMAGE, meet, line)
            drawPetals(petalMesh, count, middleX + shift, middleY, base, length * grow, petalTurn, tones, each * (1f - cyanDim), IrisPetals.CYAN_IMAGE, meet, line)
        }
    }

    private fun DrawScope.drawSparks(light: Float) {
        if (light <= 0f) return
        sparkMesh.clear()
        val unit = size.minDimension
        for (slot in 0 until particles.capacity) {
            val life = particles.remaining(slot)
            if (life <= 0f) continue
            val x = particles.x[slot] * size.width
            val y = particles.y[slot] * size.height
            val rgb = IrisPetals.toneOf(tones, particles.tint[slot])
            val length = particles.size[slot] * unit
            // Both hold their full light for the first part of their life and then fade.
            if (particles.kind[slot] == SHARD) {
                sliver(x, y, length, particles.angle[slot], IrisPetals.argb(light * (life * 2f).coerceAtMost(1f), rgb))
            } else {
                val grown = length * (0.6f + 0.4f * life)
                sparkMesh.spark(x, y, grown, (grown * SPARKLE_WIDTH).coerceAtLeast(0.7f), IrisPetals.argb(light * (life * 1.6f).coerceAtMost(1f), rgb))
            }
        }
        drawMesh(sparkMesh, BlendMode.Plus)
    }

    // A shard of glass: a slim triangle in one flat colour, point first along [turn].
    private fun sliver(x: Float, y: Float, length: Float, turn: Float, argb: Int) {
        val c = cos(turn)
        val s = sin(turn)
        val wide = length * SLIVER
        val point = sparkMesh.vertex(x + c * length * 0.6f, y + s * length * 0.6f, argb)
        val left = sparkMesh.vertex(x - c * length * 0.4f - s * wide, y - s * length * 0.4f + c * wide, argb)
        val right = sparkMesh.vertex(x - c * length * 0.4f + s * wide, y - s * length * 0.4f - c * wide, argb)
        sparkMesh.triangle(point, left, right)
    }

    /**
     * The lids: two dark shapes that meet in an almond. Wide open they are out of sight; in a silence
     * or a breakdown they close to a slit, edged by a thin line of the palette.
     */
    private fun DrawScope.drawLids(palette: VizPalette, light: Float) {
        val open = lids.value
        if (open >= LID_GONE) return
        val middleX = size.width / 2f
        val middleY = size.height / 2f
        val halfWidth = size.width * LID_REACH
        val slit = max(SLIT_PIXELS, size.height * SLIT_SHARE)
        val height = slit + open * open * LID_SPAN * middleY
        val lash = palette.argb(PETAL_HUE + genes.walk, 0.6f, 0.8f, LASH_LIGHT * light)
        lidMesh.clear()
        for (side in 0..1) {
            val sign = if (side == 0) -1f else 1f
            val outside = if (side == 0) -1f else size.height + 1f
            val first = lidMesh.vertexCount
            for (point in 0 until LID_POINTS) {
                val t = point / (LID_POINTS - 1f) * 2f - 1f
                val x = middleX + t * halfWidth
                val y = middleY + sign * height * (1f - t * t)
                lidMesh.vertex(x, outside, LID_ARGB)
                lidMesh.vertex(x, y, LID_ARGB)
                lidXs[point] = x
                lidYs[point] = y
            }
            for (point in 0 until LID_POINTS - 1) {
                val a = first + point * 2
                lidMesh.quad(a, a + 2, a + 3, a + 1)
            }
            lidMesh.strip(lidXs, lidYs, LID_POINTS, max(1f, size.height / 720f), lash)
        }
        drawMesh(lidMesh)
    }

    override fun onReset() {
        analyser.reset()
        visual.fill(0f)
        loudness = 0f
        owed = 0f
        started = false
        register = -1f
        petals.rest()
        rings.clear()
        particles.clear()
        form = EYE
        folds.reset(EYE_FOLDS)
        foldTarget = EYE_FOLDS
        density.reset(EYE_DENSITY)
        densityTarget = EYE_DENSITY
        rest.reset(INNER_BASE)
        restTarget = INNER_BASE
        petalWeight.reset(EYE_PETALS)
        petalTarget = EYE_PETALS
        fibreWeight.reset(1f)
        fibreTarget = 1f
        vortexLeft = 0f
        vortex.reset(0f)
        bornFrom = EYE_FOLDS.toInt()
        growth = 1f
        petalTurn = 0f
        spinSign = 1f
        lastPhase = -1f
        lastCycles = 0
        ringHue = 0f
        dilation.reset()
        impulse.reset()
        lids.reset(OPEN_SLIT)
        lidTarget = OPEN_SLIT
        closed = false
        closedTurns = 0
        splitting = false
        splitFrom = 0f
        splitTo = 0
        meet = 0f
        build = 0f
        apart = 0f
        cyanDim = 0f
        keyLean.reset()
        pupilUnits = FOCAL * INNER_BASE / FAR_DEPTH
    }

    internal companion object {
        const val FFT_SIZE = 4096
        const val PAGE_SAMPLE_RATE = 44_100

        /** `numBars`: bars per read. Only the first half are drawn, each as two mirrored spokes. */
        const val NUM_BARS = 128
        const val SPECTRUM_START = 4
        const val SPECTRUM_END = 1300

        /** The far edge's y: `visualArray[i] / 2 + (65 + loudness / 1.5)`. 65 is the Eye form's rest. */
        const val INNER_BASE = 65f
        const val LOUDNESS_DIVISOR = 1.5f

        /**
         * The strip's far and near edges after `rotateX(PI / 1.8)` and the 60-unit lift, seen from the camera at
         * z = 250: the far edge 496.2 units away, the near edge 3.8 units away and 16.6 units off the axis.
         */
        val FAR_DEPTH: Float = (250.0 + 250.0 * sin(PI / 1.8)).toFloat()
        val NEAR_DEPTH: Float = (250.0 - 250.0 * sin(PI / 1.8)).toFloat()
        val NEAR_Y: Float = (60.0 + 250.0 * cos(PI / 1.8)).toFloat()

        /** Half a fibre's width. The page's strip is 3 units wide; a sixth more keeps a fibre visible on a small screen. */
        const val HALF_WIDTH = 1.75f

        /** The fragment shader's `-pos.z / 180.0`: a spoke is full colour 180 units away and brighter beyond. */
        const val BRIGHTNESS_DEPTH = 180f

        /** `1 / tan(35 degrees)` for the page's vertical field of view of 70 degrees. */
        val FOCAL: Float = (1.0 / tan(35.0 * PI / 180.0)).toFloat()

        /** Steps along a spoke, and how far past the farthest corner the last one lies. */
        const val STEPS = 16
        const val OVERSHOOT = 1.25f

        const val PAGE_RATE = 60f
        const val READ_PERIOD = 1f / PAGE_RATE
        private const val MAX_OWED = 6f * READ_PERIOD

        private const val FIBRE_VERTICES = NUM_BARS * STEPS * 2 + 16
        private const val FIBRE_INDICES = NUM_BARS * (STEPS - 1) * 6 + 16

        // The forms. Eye, Flower and Mandala settle; Vortex runs for a cycle on a drop or a surge.
        const val EYE = 0
        const val FLOWER = 1
        const val MANDALA = 2
        const val VORTEX = 3
        const val SETTLED_FORMS = 3
        val FORMS = listOf("Eye", "Flower", "Mandala", "Vortex")
        const val EYE_FOLDS = 4f
        const val EYE_DENSITY = 0.25f
        const val EYE_PETALS = 0.6f
        const val FLOWER_REST = 45f
        const val FLOWER_FIBRES = 0.6f
        const val MANDALA_REST = 55f
        const val MANDALA_PETALS = 0.8f
        const val MANDALA_FIBRES = 0.85f
        const val MOST_FOLDS = 13

        /** How fast a form's numbers ease, per visual cycle: a jump across a whole range takes one to four cycles. */
        const val FOLDS_PER_CYCLE = 3f
        const val DENSITY_PER_CYCLE = 0.4f
        const val REST_PER_CYCLE = 15f
        const val WEIGHT_PER_CYCLE = 0.3f
        const val BIRTH_CYCLES = 1f

        /** The Vortex: the pupil's rest while it runs, how long, how fast it opens, and how fast the rings fall in. */
        const val VORTEX_REST = 220f
        const val VORTEX_CYCLES = 1f
        const val VORTEX_PER_CYCLE = 4f
        const val VORTEX_PULL = 2f

        /** The kick's dilation: a full kick widens the pupil by about this many world units. */
        const val DILATE_UNITS = 30f
        const val DILATE_KICK = 35f
        const val DILATE_STIFFNESS = 220f
        const val DILATE_DAMPING = 0.5f
        const val DILATE_SECONDS = 0.3f

        /** Centred units from the pupil's edge to where the petals start, a petal's full length, and wedges a cycle. */
        const val RIM_UNITS = 0.2f
        const val PETAL_UNITS = 0.45f
        const val TURN_WEDGES = 2f

        const val RING_POOL = 12
        const val RING_DOTS = 128
        const val RING_KEEP_LEAST = 6

        /** How far a ring spreads in a second at a motion rate of 1, in centred units. */
        const val RING_SPEED = 0.3f
        const val RING_SECONDS = 16f
        const val RING_HUE_STEP = 0.13f
        const val CYCLE_RING = 0.6f
        const val KICK_RING = 0.5f
        const val MORPH_RING = 0.8f

        /** The lids: how fast they open and close, the slit, the opening range, and how far they reach. */
        const val LID_OPEN = 3f
        const val LID_CLOSE = 1.5f
        const val LID_SECONDS = 0.7f
        const val OPEN_SLIT = 0.05f
        const val QUIET_ENERGY = 0.04f
        const val LOUD_ENERGY = 0.5f
        const val LID_GONE = 0.98f
        const val LID_REACH = 0.55f
        const val LID_SPAN = 6f
        const val LID_POINTS = 33
        const val SLIT_PIXELS = 1.5f
        const val SLIT_SHARE = 0.004f
        const val LASH_LIGHT = 0.5f
        val LID_ARGB: Int = 0xFF010101.toInt()

        /** Colour: the least span of hue, where the fibres and petals sit on it, and how far each moves. */
        const val MIN_SPAN = 120f
        const val FIBRE_HUE = 0.05f
        const val FIBRE_SPREAD = 0.45f

        /** How far along the palette a loud band slides its fibre. */
        const val FIBRE_HEAT = 0.2f

        const val FIBRE_LIGHTNESS = 0.62f
        const val FIBRE_GREY = 0.75f
        const val FIBRE_FLOOR = 0.3f
        const val PETAL_HUE = 0.35f
        const val PETAL_LIGHTNESS = 0.78f
        const val TONE_SPREAD = 0.12f
        const val KEY_TURN = 30f
        const val KEY_TURN_PER_SECOND = 12f

        /**
         * The register: where the spectrum's weight sits, 0 to 1, settled over [REGISTER_SECONDS]. The petals
         * and the rings turn their hue [REGISTER_TURN] degrees for each unit it moves from [REGISTER_MIDDLE].
         * A spectrum lighter than [REGISTER_LEAST] in total, which is silence, keeps the register it had.
         */
        const val REGISTER_SECONDS = 0.5f
        const val REGISTER_MIDDLE = 0.35f
        const val REGISTER_TURN = 240f
        const val REGISTER_LEAST = 0.05f

        /** The drop's images: how far each moves as a share of the width, and the lookahead. */
        const val SPLIT = 0.125f
        const val BUILD_SECONDS = 0.4f
        const val BUILD_FALL = 3f
        const val BUILD_SHARE = 0.15f
        const val MEET_FROM = 0.7f
        const val COLOUR_SIGNATURE = 0.85f

        /** The petal line, in pixels on a shorter side of [UNIT_SIDE], and wedges built into one mesh batch. */
        const val LINE = 2f
        const val UNIT_SIDE = 540f
        const val PETAL_BATCH = 16

        /** Shards and sparkles. Speeds are in centred units a second; sizes and jitter in shares of the shorter side. */
        const val POOL = 400
        const val SHARD = 0
        const val SPARKLE = 1
        const val SHARDS = 4
        const val SPARKLES = 3
        const val SHARD_SPEED = 0.7f
        const val SHARD_SPREAD = 0.7f
        const val SHARD_LIFE = 0.7f
        const val SHARD_SIZE = 0.1f
        const val SPARKLE_LIFE = 0.45f
        const val SPARKLE_SIZE = 0.055f
        const val SPARKLE_WIDTH = 0.3f
        const val SLIVER = 0.16f
        const val BEYOND = 1.05f
        const val JITTER = 0.012f
        const val DRAG = 0.6f

        /**
         * How far along a spoke, 0 at its far edge and 1 at its near edge, lies the point that lands [radius] pixels
         * from the middle, for an inner end [inner] units off the axis and a lens of [focal] pixels.
         */
        fun along(radius: Float, inner: Float, focal: Float): Float {
            val t = (radius * FAR_DEPTH - focal * inner) / (radius * (FAR_DEPTH - NEAR_DEPTH) + focal * (NEAR_Y - inner))
            return t.coerceIn(0f, 1f)
        }

        /** A step's colour at [depth]: `-viewZ / 180` times the fibre's colour, clamped as the frame buffer does. */
        fun colourAt(depth: Float, red: Float, green: Float, blue: Float, light: Float): Int {
            val shade = depth / BRIGHTNESS_DEPTH
            return (0xFF shl 24) or (channel(shade * red, light) shl 16) or
                (channel(shade * green, light) shl 8) or channel(shade * blue, light)
        }

        private fun channel(value: Float, light: Float): Int = (value.coerceIn(0f, 1f) * light * 255f + 0.5f).toInt()

        /** The span of hue Iris walks over: the palette's own, but never less than [MIN_SPAN]. */
        fun spanOf(palette: VizPalette): Float = max(palette.hueSpan, MIN_SPAN)
    }
}
