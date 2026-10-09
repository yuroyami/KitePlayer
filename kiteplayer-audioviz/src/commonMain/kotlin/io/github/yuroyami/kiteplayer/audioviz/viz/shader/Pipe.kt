package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
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
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.lightFor
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatSpawn
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A flight down a tube whose wall is lit square cells with black gaps.
 *
 * Each ring of cells is one past spectrum wrapped round the tube, with the bass along the floor,
 * the treble across the ceiling, and the two sides mirrored. A cell is lit against its band's own
 * recent peak, dark below half of it and bright near it, and white on a new peak. Its hue comes from
 * the palette, the cool end at the floor and the warm end at the ceiling, walked by the genes, with a
 * new accent at each section. The live waveform runs as two rails along the floor, bright at the
 * mouth. A new ring is born at the small white point far ahead, one for every sixteenth note, and the
 * rings stream toward the viewer. The viewer flies into the newest sound, and the rings that pass by
 * are the song's recent past. The nearest four rings are also lit by the live spectrum, in full at the mouth, so a hit is
 * felt at the camera. A gate ring passes at the start of each cycle while the beat is clear, and the rings
 * that start a beat have thicker gaps. A kick flares the nearest rings, squeezes the tube and
 * pushes the flight on for a moment. A snare sends a bright ring rushing in from the far point,
 * hats throw sparks. The tube bends with the stereo balance and the tune, the camera banks into the
 * bend, and past rings curve into view on the inside of a bend. A section glides the camera to
 * another lane over about a cycle. The cross section turns into a round, square or six-sided tube on
 * each morph of the evolution pacer, over one cycle. A breakdown halves the speed, narrows the tube to
 * a throat and leaves only the cells' lit edges. On a drop the tube opens to a chamber twice as wide
 * and goes to light speed for one cycle: every cell stretches into a streak, and on the next cycle's
 * first beat they snap back. A birth forks the far point into a second branch for one cycle, and the
 * lane glides to one branch. The form on screen is published through `forms`.
 */
internal class Pipe : ShaderPreset(
    source = SOURCE,
    name = "Pipe",
    bucket = VizEnergy.Mid,
    seed = 7f,
    // The camera holds still; the one camera move is the lane glide at a section, done here.
    kit = Kit(701L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 701)),
) {

    override val mapping: VizMapping by mappingOf(
        // The wall is the spectrum's recent past; the current bands light the newest ring, at the far
        // point, and the nearest ring.
        VizDrive(VizDriver.Bands, VizProperty.Texture),
        // The waveform runs as two rails along the floor, and the stereo balance bends the tube.
        VizDrive(VizDriver.Waveform, VizProperty.Shape),
        // Light follows the level.
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        // One ring per sixteenth note of the cycles, which without a pulse run at the mood's rate.
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Brightness, VizCurve.Discrete, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.Timbre, VizProperty.Shape),
        // A section glides the camera to another lane.
        VizDrive(VizDriver.Section, VizProperty.Camera, VizCurve.Discrete, VizResponse.envelope(0.5f)),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete, VizResponse.envelope(2f)),
        // A breakdown darkens every cell's body and leaves its lit edges.
        VizDrive(VizDriver.Breakdown, VizProperty.Brightness, VizCurve.Discrete, VizResponse.envelope(1f)),
        VizDrive(VizDriver.Drop, VizProperty.Brightness, VizCurve.Discrete, VizResponse.envelope(2f)),
        silence = VizSilence.Still,
    )

    // Only the white cells glow; everything below the threshold stays a clean cell.
    override val post: PostSpec get() = GLOW

    // The debris is drawn over the shader, which does not move with a camera.
    override val frontParallax: Float get() = 0f

    /** The light of every cell: ring 0 is the newest ring, born at the far point, and cell 0 is the floor. */
    private val light = FloatArray(RINGS * CELLS)
    /** The light of one cell, so a test can read the model. */
    internal fun ringLight(index: Int, cell: Int): Float = light[index * CELLS + cell]
    /** One for a gate ring. */
    private val gate = FloatArray(RINGS)
    /** One for a ring that starts a beat: its gaps are drawn thicker, so the flight shows on any sound. */
    private val beat = FloatArray(RINGS) { if (it % 4 == 0) 1f else 0f }
    /** Each cell's recent peak. A cell is lit against its own peak, so the treble lights as readily as the bass. */
    private val peak = FloatArray(CELLS) { PEAK_START }
    private val image = PixelImage(CELLS, RINGS)

    /** Where the music was last frame, in sixteenth notes, and how far the newest ring has moved on. */
    private var position = -1.0
    /** How far the newest ring has moved from the far point, 0 to 1. Internal so a test can watch the flow. */
    internal var flow = 0f
        private set
    /** Rings born at the far point since the start. */
    internal var ringsPassed = 0L
        private set
    private var lastCycle = -1

    private val flare = Spring(stiffness = 140f, damping = 0.55f)
    /** The kick's light on the nearest rings: at once on the hit, gone in a fraction of a second. */
    private var kickLight = 0f
    private var snareAge = -1f
    private var breakdown = false
    private var calm = 0f
    private var lightSpeedUntil = -1
    /** One while the tube runs at light speed after a drop. */
    internal var streak = 0f
        private set
    private var quiet = 0f

    /** The cycle count the chamber lasts to after a drop, or -1 when there is none. */
    private var chamberUntil = -1
    /** One while the tube is a chamber after a drop, easing in fast and out slowly. */
    private val chamber = Envelope(attackPerSecond = 8f, releasePerSecond = 3f)

    /** The tube's radius against its usual one: a chamber doubles it and a throat takes it to 0.6. */
    internal val radiusFactor: Float
        get() = (1f + CHAMBER_WIDEN * chamber.value) * (1f - THROAT_NARROW * calm)

    /** One when a birth forks the far point, falling to zero over a cycle. */
    private var fork = 0f
    internal val forkValue: Float get() = fork
    /** Where the second branch's far point sits against the first, in half screen heights. */
    private var forkX = 0f
    private var forkY = 0f
    /** The last frame's motion scale, so a fork made between frames is as small as a section's lane. */
    private var motionLast = 1f
    /** The cycle length in seconds, so a test can time the fork. */
    internal val cycleSecondsValue: Float get() = gestures.cycleSeconds

    // The lane the camera flies in glides to its target, and the shape of the tube turns on each morph.
    private val laneSlewX = Slew(maxPerSecond = 0.4f)
    private val laneSlewY = Slew(maxPerSecond = 0.4f)
    internal var laneX = 0f
        private set
    internal var laneY = 0f
        private set
    internal var laneTargetX = 0f
        private set
    internal var laneTargetY = 0f
        private set
    private var shapeFrom = 0
    internal var shapeTo = 0
        private set
    /** How far the tube has turned into its new shape, 0 to 1. */
    internal var shapeMorph = 1f
        private set
    private var shapeSeconds = 2f

    private val bendX = Slew(maxPerSecond = 0.5f)
    private val bendY = Slew(maxPerSecond = 0.5f)
    /** How far the bend reaches. A morph moves it, so it is read every frame. */
    private val bendGene = genes.number("bend", 0.5f, 1.3f, 0.9f)
    /** Where the far point is, in half screen heights from the middle, so a test can read the bend. */
    internal val bendXValue: Float get() = bendX.value
    internal val bendYValue: Float get() = bendY.value
    private val debris = Sprites(160, 7_011L)

    // A new accent hue at each section, glided in over a second.
    private var accentTarget = 0f
    private val accent = Slew(maxPerSecond = 0.3f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val step = state.stepSeconds
        val frame = state.frame
        // Reduced motion keeps the glide to another lane and the rush of light speed, but small.
        val motion = state.motionScale.coerceIn(0f, 1f)
        motionLast = motion

        // Where the music is, in sixteenth notes: sixteen to a cycle of four pulses. The flight
        // moves only while music is heard, so a silence or a pause holds the tube where it is.
        val now = (gestures.cycles + gestures.cyclePhase) * 16.0
        val moved = if (position < 0.0) 0.0 else (now - position).coerceIn(0.0, 4.0) * frame.audible
        position = now

        if (gestures.breakdown) breakdown = true else if (gestures.turn || gestures.surge) breakdown = false
        calm += ((if (breakdown) 1f else 0f) - calm) * (1f - exp(-step / 0.4f))

        // Light speed on a drop: three times the speed until the first beat of the cycle after next,
        // or of the next cycle when the drop lands early in one.
        if (gestures.surge) {
            lightSpeedUntil = gestures.cycles + if (gestures.cyclePhase > 0.5f) 2 else 1
        }
        val lightSpeed = lightSpeedUntil >= 0 && gestures.cycles < lightSpeedUntil
        if (!lightSpeed) lightSpeedUntil = -1
        streak = if (lightSpeed) motion else 0f

        // A drop also opens the tube to a chamber, for the same span as the light speed.
        if (evolution.bloom) {
            chamberUntil = gestures.cycles + if (gestures.cyclePhase > 0.5f) 2 else 1
        }
        val chambered = chamberUntil >= 0 && gestures.cycles < chamberUntil
        if (!chambered) chamberUntil = -1
        chamber.advance(if (chambered) 1f else 0f, dt)

        // A kick flares the nearest rings at once and squeezes the tube, which springs back.
        flare.kick(gestures.kick * 5f)
        flare.advance(dt)
        kickLight = maxOf(kickLight * exp(-dt / 0.15f), gestures.kick)

        // The same kick pushes the flight on for a moment.
        val pace = (if (lightSpeed) 1f + 2f * motion else 1f) * (1f - 0.5f * calm) *
            (1f + KICK_PACE * kickLight * motion)

        // The newest ring follows the current spectrum until the next ring is born. On the first
        // frame every ring takes it, so the tube is whole from the start rather than filling up.
        val first = moved == 0.0 && ringsPassed == 0L && flow == 0f
        cellsFrom(frame.bandsRel, frame.audible, step)
        if (first) for (ring in 1 until RINGS) light.copyInto(light, ring * CELLS, 0, CELLS)
        flow += (moved * pace).toFloat()
        while (flow >= 1f) {
            flow -= 1f
            push()
        }
        // A gate ring at the start of every cycle that is heard. Without a supported pulse the cycles
        // run free, and a ring on each of their edges would be a beat the music does not have.
        if (lastCycle >= 0 && gestures.cycles != lastCycle && frame.audible > 0.5f && gestures.pulseUsable) gate[0] = 1f
        lastCycle = gestures.cycles

        // A snare sends a bright ring rushing in from the far point in one beat.
        if (gestures.snare > 0f) snareAge = 0f
        if (snareAge >= 0f) {
            snareAge += step
            if (snareAge > gestures.beatSeconds) snareAge = -1f
        }

        // A section is the one camera move: the camera glides to another lane over about a cycle,
        // with no cut.
        if (gestures.turn) {
            glideToAnotherLane()
            accentTarget = random.next() * 0.3f
        }
        accent.advance(accentTarget, dt)
        laneX = laneSlewX.advance(laneTargetX, dt)
        laneY = laneSlewY.advance(laneTargetY, dt)

        // The cross section turns into another shape on each morph of the evolution pacer, over one cycle.
        if (evolution.morph) {
            shapeFrom = shapeTo
            shapeTo = (shapeTo + 1 + (random.next() * 2f).toInt().coerceAtMost(1)) % SHAPES
            shapeMorph = 0f
            shapeSeconds = gestures.cycleSeconds.coerceAtLeast(0.5f)
        }
        if (shapeMorph < 1f) shapeMorph = (shapeMorph + step / shapeSeconds).coerceAtMost(1f)

        // A birth forks the far point into a second branch that fades over one cycle.
        if (evolution.birth) fork()
        if (fork > 0f) fork = (fork - step / gestures.cycleSeconds.coerceAtLeast(0.5f)).coerceAtLeast(0f)

        // The bend follows the stereo balance and the tune: a right-heavy mix and bright music curve the
        // tube right, and bright music lifts the far point. A gene sets how far it goes.
        val balance = balanceOf(frame.scopeLeft, frame.scopeRight) * frame.audible
        val tune = (frame.centroid - 0.5f) * frame.audible
        bendX.advance((balance * BEND_BALANCE + tune * BEND_TUNE_X) * bendGene.value, dt)
        bendY.advance(-tune * BEND_TUNE_Y * bendGene.value, dt)

        // Hats throw sparks out of the far point.
        if (gestures.hat > 0f) {
            val aspect = kit.aspect
            debris.burst(0.5f + bendX.value / (2f * aspect), 0.5f + bendY.value * 0.5f,
                gestures.hatSpawn(6), 0.9f, 0.7f, 0.006f, 0.8f, Sprite.SPARK)
        }
        debris.advance(dt, drag = 0.2f)

        quiet += ((1f - frame.audible) - quiet) * (1f - exp(-dt / 0.5f))
    }

    /** Picks a lane the camera glides to, as far out as the last frame's motion scale allows. */
    private fun glideToAnotherLane() {
        val angle = random.next() * TAU
        val reach = 0.15f + 0.3f * random.next()
        laneTargetX = reach * cos(angle) * motionLast
        laneTargetY = reach * sin(angle) * motionLast
    }

    /** Forks the far point into a second branch for one cycle, and the lane glides to a branch. */
    internal fun fork() {
        fork = 1f
        val angle = random.next() * TAU
        val reach = 0.25f + 0.2f * random.next()
        forkX = reach * cos(angle) * motionLast
        forkY = reach * sin(angle) * motionLast
        glideToAnotherLane()
    }

    /**
     * The form on screen. A chamber, a throat and a fork show over the cross section while they last.
     * A drop is also a birth, so a chamber is named before a fork, or a drop would never read as a chamber.
     */
    private val formName: String
        get() = when {
            chamber.value > 0.5f -> "Chamber"
            calm > 0.5f -> "Throat"
            fork > 0.5f -> "Fork"
            else -> FORMS[shapeTo]
        }

    override val forms: FormReadout get() = FormReadout(formName, evolution.morphs, evolution.births)

    /** The current bands, folded into the newest ring's cells: the floor holds the bass. */
    private fun cellsFrom(bands: FloatArray, audible: Float, step: Float) {
        if (bands.isEmpty()) {
            for (cell in 0 until CELLS) light[cell] = 0f
            return
        }
        for (cell in 0 until CELLS) {
            val from = (cell * bands.size / CELLS).coerceAtMost(bands.size - 1)
            val to = ((cell + 1) * bands.size / CELLS).coerceIn(from + 1, bands.size)
            var sum = 0f
            for (band in from until to) sum += bands[band]
            // The peak rises within a few frames and falls over several seconds of music. A cell at
            // half its peak stays dark, and only a cell near its peak burns gold or white.
            val level = sum / (to - from)
            val held = peak[cell]
            peak[cell] = if (level > held) held + (level - held) * (1f - exp(-step / 0.1f))
            else maxOf(PEAK_FLOOR, held - (held - level) * (1f - exp(-step * audible / 6f)))
            // A band that jumps past its peak before the peak catches up is a new peak, and burns white.
            val ratio = level / peak[cell]
            val t = ((ratio - LIGHT_FROM) / (LIGHT_TO - LIGHT_FROM)).coerceIn(0f, 1f)
            light[cell] = (t * t * (3f - 2f * t) + (ratio - 1f).coerceIn(0f, 0.5f) * 0.5f) * audible
        }
    }

    /** A new ring is born at the far point: every ring ages by one, and a new newest ring starts. */
    private fun push() {
        light.copyInto(light, CELLS, 0, (RINGS - 1) * CELLS)
        gate.copyInto(gate, 1, 0, RINGS - 1)
        gate[0] = 0f
        beat.copyInto(beat, 1, 0, RINGS - 1)
        ringsPassed++
        beat[0] = if (ringsPassed % 4 == 0L) 1f else 0f
    }

    /**
     * The whole picture's light. It follows the level, so a quiet passage is a dark tube, with a floor
     * so a silence still shows the tube as an ember. The flash guard's scale multiplies all of it.
     */
    private fun glowOf(state: VizRenderState): Float =
        (state.lightScale * (GLOW_FLOOR + (1f - GLOW_FLOOR) * lightFor(state.frame.energy))).coerceIn(0f, 1f)

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        for (ring in 0 until RINGS) {
            for (cell in 0 until CELLS) {
                val red = (light[ring * CELLS + cell] / LIGHT_MAX * 255f + 0.5f).toInt().coerceAtMost(255)
                val green = (gate[ring] * 255f + 0.5f).toInt()
                val blue = (beat[ring] * 255f + 0.5f).toInt()
                image.pixels[ring * CELLS + cell] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        image.upload()
        program.child("uRings", image.image)

        val eased = shapeMorph * shapeMorph * (3f - 2f * shapeMorph)
        val weights = FloatArray(SHAPES)
        weights[shapeFrom] += 1f - eased
        weights[shapeTo] += eased
        val squeeze = 1f - 0.05f * flare.value.coerceIn(0f, 1.5f)
        program.uniform("uLane", laneX, laneY, 0f, 0f)
        program.uniform("uShape", weights[0], weights[1], weights[2], squeeze * radiusFactor)
        program.uniform("uFork", forkX, forkY, 0f, fork)
        program.uniform("uFlow", flow, streak, calm, quiet)
        val snare = if (snareAge >= 0f) snareAge / gestures.beatSeconds.coerceAtLeast(0.05f) else -1f
        program.uniform("uHits",
            (kickLight * 0.7f).coerceIn(0f, 1f),
            if (snare >= 0f) snare * RINGS else -99f,
            // The ring grows brighter as it nears, from 0.4 at the far point to full past the nearest rings.
            if (snare >= 0f) 0.4f + 0.6f * snare else 0f,
            1f + calm)
        program.uniform("uBend", bendX.value, bendY.value)
        program.uniform("uTone", accent.value, 0f, 0f, 0f)
        program.uniform("uGlow", glowOf(state))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(debris) { drawSprites(state.palette, 0f, alpha = glowOf(state)) }
    }

    override fun onReset() {
        light.fill(0f)
        gate.fill(0f)
        for (ring in 0 until RINGS) beat[ring] = if (ring % 4 == 0) 1f else 0f
        peak.fill(PEAK_START)
        position = -1.0
        flow = 0f
        ringsPassed = 0L
        lastCycle = -1
        flare.reset()
        kickLight = 0f
        snareAge = -1f
        breakdown = false
        calm = 0f
        lightSpeedUntil = -1
        streak = 0f
        quiet = 0f
        laneSlewX.reset()
        laneSlewY.reset()
        laneX = 0f
        laneY = 0f
        laneTargetX = 0f
        laneTargetY = 0f
        chamberUntil = -1
        chamber.reset()
        fork = 0f
        forkX = 0f
        forkY = 0f
        motionLast = 1f
        shapeFrom = 0
        shapeTo = 0
        shapeMorph = 1f
        bendX.reset()
        bendY.reset()
        debris.clear()
        accentTarget = 0f
        accent.reset()
    }

    internal companion object {
        /** Rings in view, and cells from the floor to the ceiling on one side. */
        const val RINGS = 56
        const val CELLS = 16
        const val SHAPES = 3

        /** The forms `forms` names: the three cross sections, then the four moments that override them. */
        val FORMS = listOf("Round", "Square", "Hex", "Chamber", "Throat", "Fork")

        /** A chamber widens the radius by this much: one doubles it. */
        private const val CHAMBER_WIDEN = 1f
        /** A throat narrows the radius by this much: 0.4 takes it to 0.6 of its size. */
        private const val THROAT_NARROW = 0.4f

        /** The most light a cell holds: a new peak burns past full. */
        private const val LIGHT_MAX = 1.25f
        /** The share of its own peak at which a cell starts to light, and at which it is full. */
        private const val LIGHT_FROM = 0.45f
        private const val LIGHT_TO = 1f
        /** The lowest peak a cell is measured against, so a faint band never burns. */
        private const val PEAK_FLOOR = 0.2f
        /** A loud band's usual height: every cell starts from it, so a song's first seconds are not all lit. */
        private const val PEAK_START = 0.6f
        /** A kick pushes the flight on by this share for a moment. */
        private const val KICK_PACE = 0.6f
        /** The light a silent tube keeps, as a share of full: the ember. Above it the light follows the level. */
        private const val GLOW_FLOOR = 0.25f
        /** A small difference between the channels is made visible. */
        private const val BALANCE_GAIN = 3f
        /** How far the far point moves sideways, in half screen heights, for a full stereo balance. */
        private const val BEND_BALANCE = 0.45f
        /** How far the far point moves sideways, in half screen heights, for a full centroid swing. */
        private const val BEND_TUNE_X = 0.5f
        /** How far the far point moves up, in half screen heights, for a full centroid swing. */
        private const val BEND_TUNE_Y = 0.35f

        /** The stereo balance, from -1 for all left to 1 for all right, from the two channels' loudness. */
        internal fun balanceOf(left: FloatArray, right: FloatArray): Float {
            val l = rootMeanSquare(left)
            val r = rootMeanSquare(right)
            return ((r - l) / (r + l + 1e-4f) * BALANCE_GAIN).coerceIn(-1f, 1f)
        }

        private fun rootMeanSquare(trace: FloatArray): Float {
            if (trace.isEmpty()) return 0f
            var sum = 0f
            for (sample in trace) sum += sample * sample
            return sqrt(sum / trace.size)
        }

        private val GLOW = PostSpec(bloom = 0.35f, bloomRadius = 0.03f, threshold = 0.9f, vignette = 0.2f,
            grain = 0f, glitch = false, aberration = 0f)

        const val SOURCE: String = """
uniform float4 uLane;
uniform float4 uShape;
uniform float4 uFlow;
uniform float4 uHits;
uniform float2 uBend;
uniform float4 uFork;
// The section's accent hue shift, in x.
uniform float4 uTone;
uniform float uGlow;
uniform shader uRings;

// The tube, one unit across, and where its rings sit: the mouth at a depth of 0.42 units, one
// ring every 0.26 units behind it.
const float RINGS = 56.0;
const float CELLS = 16.0;
const float MOUTH = 0.42;
const float SPACING = 0.26;
// The newest ring is born at this depth, far down the tube, and streams toward the mouth.
const float FAR_DEPTH = MOUTH + RINGS * SPACING;
// How far the camera rolls into a full bend, in radians.
const float BANK = 0.35;
// How many of the newest rings the fork's second branch shows.
const float FORK_RINGS = 10.0;
// How much of its palette colour an unlit cell glows, so the whole wall reads as one body even where
// nothing plays.
const float EMBER = 0.22;
// Where the two rails sit, as a share of the half turn from the floor.
const float RAIL = 0.09;
// How many of the nearest rings take the live spectrum.
const float LIVE_RINGS = 4.0;
const float LIGHT_MAX = 1.25;

// How far a ray from the lane runs across the tube before it meets the wall, for the tube's three
// shapes mixed by their weights: round, square and six-sided with a flat floor.
float wallReach(float2 from, float2 dir) {
    float b = dot(from, dir);
    float c = dot(from, from) - 1.0;
    float circle = -b + sqrt(max(b * b - c, 0.0));

    float2 facing = float2(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0);
    float2 steps = (facing * 0.9 - from) / (dir + facing * 1e-5);
    float square = min(steps.x, steps.y);

    float hex = 99.0;
    for (int k = 0; k < 3; k++) {
        float angle = 0.5235988 + 1.0471976 * float(k);
        float2 n = float2(cos(angle), sin(angle));
        float along = dot(dir, n);
        float side = along >= 0.0 ? 1.0 : -1.0;
        hex = min(hex, (side * 0.93 - dot(from, n)) / (along + side * 1e-5));
    }
    return uShape.x * circle + uShape.y * square + uShape.z * hex;
}

float4 ring(float cell, float index) {
    if (index < 0.0 || index >= RINGS) return float4(0.0);
    return uRings.eval(float2(cell + 0.5, index + 0.5));
}

// The tube seen from the camera: the wall's cells and gaps at the screen point q, for a tube whose far
// point is at bend. A farOnly of one keeps only the newest rings, which fade out toward the viewer.
float3 tube(float2 q, float2 bend, float farOnly) {
    float pixel = 2.0 / uResolution.y;
    // The wall hit is found for a straight tube and refined twice with the axis moved by the
    // depth, so the tube really bends and past rings curve into view.
    float2 d = q;
    float len = max(length(d), 1e-4);
    float2 dir = d / len;
    float reach = wallReach(uLane.xy, dir) * uShape.w;
    float depth = reach / len;
    for (int k = 0; k < 2; k++) {
        d = q - bend * (depth / FAR_DEPTH);
        len = max(length(d), 1e-4);
        dir = d / len;
        reach = wallReach(uLane.xy, dir) * uShape.w;
        depth = reach / len;
    }
    float2 hit = uLane.xy + dir * reach;
    // Round the tube from the floor, which is down the screen, to the ceiling, the same both sides.
    float around = abs(atan(hit.x, hit.y)) / 3.14159265;
    float cell = min(floor(around * CELLS), CELLS - 1.0);
    // Along the tube: which ring, counted from the newest ring at the far point, and where in it.
    float along = (FAR_DEPTH - depth) / SPACING - uFlow.x;
    float index = floor(along);
    float v = along - index;
    float fromMouth = RINGS - 1.0 - index;

    float4 texel = ring(cell, index);
    float lit = texel.r * LIGHT_MAX;
    // Light speed: each cell smears into a streak of its own colour, trailing toward the far point,
    // behind the ring's motion.
    lit = max(lit, uFlow.y * 0.85 * LIGHT_MAX * ring(cell, index - 1.0).r);
    lit = max(lit, uFlow.y * 0.65 * LIGHT_MAX * ring(cell, index - 2.0).r);
    // A kick brightens the nearest rings in view and keeps their pattern. It stops short of white:
    // those rings are large, and a white flash over them on every kick would be a strobe. A snare's
    // ring rushes in from the far point.
    float kick = uHits.x * clamp(1.0 - (fromMouth - 1.0) / 5.0, 0.0, 1.0);
    lit = min(lit * (1.0 + 2.0 * kick) + 0.4 * kick, max(lit, 0.85));
    // The nearest rings take the live spectrum, in full at the mouth and fading over four rings, so a
    // hit is felt at the camera and the live sound is never only a point far ahead.
    float live = ring(cell, 0.0).r * LIGHT_MAX;
    lit = max(lit, live * clamp(1.0 - fromMouth / LIVE_RINGS, 0.0, 1.0));
    // The snare's ring lights whole cells: the ring it has reached, and the one behind at half. It
    // rushes in from the far point and shows only past the nearest rings: those rings are large, and
    // hats and chord stabs are heard as snares too, so lighting them would be a strobe.
    float behind = floor(uHits.y) - index;
    lit += uHits.z * (behind == 0.0 ? 1.0 : (behind == 1.0 ? 0.5 : 0.0)) * smoothstep(3.0, 6.0, fromMouth);
    // Light speed gathers light along each streak, and the whole tube rushes brighter.
    lit = lit * (1.0 + 0.35 * uFlow.y) + 0.2 * uFlow.y;
    lit = clamp(lit, 0.0, 1.3);

    // The cells and their black gaps, in pixels, from the depth alone.
    float a = fract(around * CELLS);
    float across = len * 3.14159265 / CELLS / pixel;
    float lengthwise = len * len / max(reach, 1e-3) * SPACING / pixel;
    float gap = max(0.8, uResolution.y / 1080.0);
    // A ring that starts a beat has thicker gaps, so the rings visibly run away on any sound.
    float edge = min(min(a, 1.0 - a) * across,
        mix(min(v, 1.0 - v) * lengthwise - 2.5 * gap * texel.b, 99.0, uFlow.y));
    float fill = smoothstep(gap - 0.6, gap + 0.6, edge);
    float rim = fill * (1.0 - smoothstep(gap + 1.2, gap + 2.6, edge));
    // Far away the cells are smaller than their gaps; there they blend into an even glow, darker on
    // the rings that start a beat.
    float tiny = smoothstep(2.5, 6.0, min(across, lengthwise));
    fill = mix(0.55 * (1.0 - 0.7 * texel.b * (1.0 - uFlow.y)), fill, tiny);
    rim = mix(0.3, rim, tiny);

    // A breakdown leaves only the lit edges; a silence keeps the edges at a third.
    float body = mix(fill, rim, uFlow.z);
    // Each cell's hue comes from the palette: the cool end at the floor, the warm end at the ceiling,
    // walked by the genes and shifted by the section's accent. A lit cell shows it bright and an unlit
    // one glows dim, so the wall reads as clean cells and shows even where nothing plays.
    float where = 0.2 + 0.6 * cell / (CELLS - 1.0) + uWalk + uTone.x;
    float3 base = paletteCycled(where);
    float3 colour = base * (0.35 + 0.65 * lit) * smoothstep(0.08, 0.45, lit) * body;
    colour += base * EMBER * body;
    // New peaks burn white only past the nearest rings. The nearest rings cover much of the screen,
    // and a broadband hit turning them white on every beat would be a strobe.
    colour = mix(colour, float3(1.0), smoothstep(1.0, 1.2, lit) * smoothstep(2.0, 4.5, fromMouth) * body);
    colour += palette(0.6) * rim * 0.3 * uFlow.w;
    // A gate ring: every edge of the ring lit pale, below the glow's threshold.
    colour = max(colour, mix(palette(0.9), float3(1.0), 0.35) * rim * texel.g);
    // The tube fades into the distance.
    colour *= exp(-max(fromMouth, 0.0) * 0.045);
    // The live waveform as two rails along the floor, bright at the mouth and fading into the tube.
    // The second branch of a fork has none.
    float side = atan(hit.x, hit.y) / 3.14159265;
    float wave = scopeAt(clamp(fromMouth / RINGS, 0.0, 1.0));
    float railLine = 1.0 - smoothstep(0.0, 0.02 + 0.03 * abs(wave), abs(abs(side) - RAIL));
    float rail = railLine * (0.3 + 1.2 * abs(wave)) * exp(-max(fromMouth, 0.0) * 0.08) * (1.0 - 0.5 * uFlow.z) * (1.0 - farOnly);
    colour += palette(0.95) * rail;
    if (index >= RINGS || index < 0.0) colour = float3(0.0);
    colour *= mix(1.0, 1.0 - smoothstep(FORK_RINGS - 3.0, FORK_RINGS, index), farOnly);
    return colour;
}

half4 main(float2 position) {
    float2 p = centred(position);
    float pixel = 2.0 / uResolution.y;
    float gap = max(0.8, uResolution.y / 1080.0);
    // The camera banks into the bend.
    float2 q = rotate(p, -uBend.x * BANK);
    float3 colour = tube(q, uBend, 0.0);
    // A birth forks the far point: a second branch, bent to its own far point, shows the newest rings.
    if (uFork.w > 0.0) colour = max(colour, uFork.w * tube(q, uBend + uFork.xy, 1.0));

    // The small white point far ahead, and a second one at the end of the fork.
    float star = length(q - uBend) / pixel;
    float size = 2.5 * gap * uHits.w;
    colour += float3(1.0) * (1.0 - smoothstep(size - 1.0, size + 1.0, star));
    colour += float3(1.0, 0.95, 0.85) * 0.35 * exp(-star / (size * 4.0));
    float forkStar = length(q - uBend - uFork.xy) / pixel;
    colour += uFork.w * float3(1.0) * (1.0 - smoothstep(size - 1.0, size + 1.0, forkStar));
    colour += uFork.w * float3(1.0, 0.95, 0.85) * 0.35 * exp(-forkStar / (size * 4.0));

    return half4(clamp(colour * uGlow, 0.0, 1.0), 1.0);
}
"""
    }
}
