package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
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
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.vividColour
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatSpawn
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A flight down a tube whose wall is lit square cells with black gaps.
 *
 * Each ring of cells is one past spectrum wrapped round the tube, with the bass along the floor,
 * the treble across the ceiling, and the two sides mirrored. A cell is lit against its band's own
 * recent peak: dark ember below half of it, orange and gold near it, and white on a new peak. The
 * current spectrum lights the ring at the mouth, and one ring leaves the mouth for every sixteenth
 * note, so the tube is the song's recent past running away to a small white point far ahead. A gate
 * ring passes at the start of each cycle while the beat is clear, and the rings that start a beat
 * have thicker gaps. A kick flares the nearest rings and squeezes the tube, a snare sends a bright ring
 * out to the far point, hats throw sparks, and the far point follows the tune. A section cuts to
 * another lane and turns the tube round, square or six-sided over one cycle. A breakdown halves
 * the speed and leaves only the cells' lit edges. On a drop the tube goes to light speed for one
 * cycle and every cell stretches into a streak, and on the next cycle's first beat they snap back.
 */
internal class Pipe : ShaderPreset(
    source = SOURCE,
    name = "Pipe",
    bucket = VizEnergy.Mid,
    seed = 7f,
    // The camera holds still; the one camera move is the lane cut at a section, done here.
    kit = Kit(701L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 701)),
) {

    override val mapping: VizMapping by mappingOf(
        // The wall is the spectrum's recent past; the current bands light the ring at the mouth.
        VizDrive(VizDriver.Bands, VizProperty.Texture),
        // One ring per sixteenth note of the cycles, which without a pulse run at the mood's rate.
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled, VizResponse.envelope(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Brightness, VizCurve.Discrete, VizResponse.lifetime(0.5f)),
        VizDrive(VizDriver.Timbre, VizProperty.Shape),
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

    /** The light of every cell: ring 0 is the live ring at the mouth, cell 0 is the floor. */
    private val light = FloatArray(RINGS * CELLS)
    /** One for a gate ring. */
    private val gate = FloatArray(RINGS)
    /** One for a ring that starts a beat: its gaps are drawn thicker, so the flight shows on any sound. */
    private val beat = FloatArray(RINGS) { if (it % 4 == 0) 1f else 0f }
    /** Each cell's recent peak. A cell is lit against its own peak, so the treble lights as readily as the bass. */
    private val peak = FloatArray(CELLS) { PEAK_START }
    private val image = PixelImage(CELLS, RINGS)

    /** Where the music was last frame, in sixteenth notes, and how far the mouth ring has moved on. */
    private var position = -1.0
    /** How far the current ring has left the mouth, 0 to 1. Internal so a test can watch the flow. */
    internal var flow = 0f
        private set
    /** Rings that have left the mouth since the start. */
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

    // The section's two changes: the lane the camera flies in and the shape of the tube.
    internal var laneX = 0f
        private set
    internal var laneY = 0f
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
    private val debris = Sprites(160, 7_011L)

    private val ramp = Array(3) { FloatArray(3) }
    private var rampFor: VizPalette? = null

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val step = state.stepSeconds
        val frame = state.frame

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
        streak = if (lightSpeed) 1f else 0f
        val pace = (if (lightSpeed) 3f else 1f) * (1f - 0.5f * calm)

        // The live ring at the mouth follows the current spectrum until it leaves. On the first
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

        // A kick flares the nearest rings at once and squeezes the tube, which springs back.
        flare.kick(gestures.kick * 5f)
        flare.advance(dt)
        kickLight = maxOf(kickLight * exp(-dt / 0.15f), gestures.kick)

        // A snare sends a bright ring from the mouth to the far point in one beat.
        if (gestures.snare > 0f) snareAge = 0f
        if (snareAge >= 0f) {
            snareAge += step
            if (snareAge > gestures.beatSeconds) snareAge = -1f
        }

        // A section: one camera move, a cut to another lane, and on the same beat the tube starts
        // turning into another shape over one cycle.
        if (gestures.turn) {
            val angle = random.next() * TAU
            val reach = 0.15f + 0.3f * random.next()
            laneX = reach * cos(angle)
            laneY = reach * sin(angle)
            shapeFrom = shapeTo
            shapeTo = (shapeTo + 1 + (random.next() * 2f).toInt().coerceAtMost(1)) % SHAPES
            shapeMorph = 0f
            shapeSeconds = gestures.cycleSeconds.coerceAtLeast(0.5f)
        }
        if (shapeMorph < 1f) shapeMorph = (shapeMorph + step / shapeSeconds).coerceAtMost(1f)

        // The far point follows the tune: bright music pulls it right and up.
        val tune = (frame.centroid - 0.5f) * frame.audible
        bendX.advance(tune * 0.9f, dt)
        bendY.advance(-tune * 0.35f, dt)

        // Hats throw sparks out of the far point.
        if (gestures.hat > 0f) {
            val aspect = kit.aspect
            debris.burst(0.5f + bendX.value / (2f * aspect), 0.5f + bendY.value * 0.5f,
                gestures.hatSpawn(6), 0.9f, 0.7f, 0.006f, 0.8f, Sprite.SPARK)
        }
        debris.advance(dt, drag = 0.2f)

        quiet += ((1f - frame.audible) - quiet) * (1f - exp(-dt / 0.5f))
    }

    /** The current bands, folded into the live ring's cells: the floor holds the bass. */
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

    /** The live ring leaves the mouth: every ring moves one back, and a new live ring starts. */
    private fun push() {
        light.copyInto(light, CELLS, 0, (RINGS - 1) * CELLS)
        gate.copyInto(gate, 1, 0, RINGS - 1)
        gate[0] = 0f
        beat.copyInto(beat, 1, 0, RINGS - 1)
        ringsPassed++
        beat[0] = if (ringsPassed % 4 == 0L) 1f else 0f
    }

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
        program.uniform("uShape", weights[0], weights[1], weights[2], squeeze)
        program.uniform("uFlow", flow, streak, calm, quiet)
        val snare = if (snareAge >= 0f) snareAge / gestures.beatSeconds.coerceAtLeast(0.05f) else -1f
        program.uniform("uHits",
            (kickLight * 0.7f).coerceIn(0f, 1f),
            if (snare >= 0f) snare * RINGS else -99f,
            if (snare >= 0f) 1f - 0.6f * snare else 0f,
            1f + calm)
        program.uniform("uBend", bendX.value, bendY.value)
        updateRamp(state.palette)
        program.uniform("uRamp0", ramp[0][0], ramp[0][1], ramp[0][2])
        program.uniform("uRamp1", ramp[1][0], ramp[1][1], ramp[1][2])
        program.uniform("uRamp2", ramp[2][0], ramp[2][1], ramp[2][2])
        program.uniform("uGlow", state.lightScale.coerceIn(0f, 1f))
    }

    /** Ember, orange and gold when no palette is chosen, or the chosen palette's ramp. White is the shader's. */
    private fun updateRamp(palette: VizPalette) {
        if (palette === rampFor) return
        rampFor = palette
        val stops = if (palette.name == VizPalette.Prism.name) {
            listOf(colourOf(0.30f, 0.09f, 40f), vividColour(ORANGE_HUE), vividColour(GOLD_HUE))
        } else {
            listOf(0.12f, 0.5f, 0.88f).map { palette.vividRamp(it) }
        }
        for ((index, colour) in stops.withIndex()) {
            ramp[index][0] = colour.red
            ramp[index][1] = colour.green
            ramp[index][2] = colour.blue
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val palette = if (state.palette.name == VizPalette.Prism.name) VizPalette.Ember else state.palette
        with(debris) { drawSprites(palette, 0f, alpha = state.lightScale.coerceIn(0f, 1f)) }
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
        laneX = 0f
        laneY = 0f
        shapeFrom = 0
        shapeTo = 0
        shapeMorph = 1f
        bendX.reset()
        bendY.reset()
        debris.clear()
        rampFor = null
    }

    internal companion object {
        /** Rings in view, and cells from the floor to the ceiling on one side. */
        const val RINGS = 56
        const val CELLS = 16
        const val SHAPES = 3

        /** The most light a cell holds: a new peak burns past full. */
        private const val LIGHT_MAX = 1.25f
        /** The share of its own peak at which a cell starts to light, and at which it is full. */
        private const val LIGHT_FROM = 0.45f
        private const val LIGHT_TO = 1f
        /** The lowest peak a cell is measured against, so a faint band never burns. */
        private const val PEAK_FLOOR = 0.2f
        /** A loud band's usual height: every cell starts from it, so a song's first seconds are not all lit. */
        private const val PEAK_START = 0.6f
        private const val ORANGE_HUE = 50f
        private const val GOLD_HUE = 85f

        private val GLOW = PostSpec(bloom = 0.35f, bloomRadius = 0.03f, threshold = 0.9f, vignette = 0.2f,
            grain = 0f, glitch = false, aberration = 0f)

        const val SOURCE: String = """
uniform float4 uLane;
uniform float4 uShape;
uniform float4 uFlow;
uniform float4 uHits;
uniform float2 uBend;
uniform float3 uRamp0;
uniform float3 uRamp1;
uniform float3 uRamp2;
uniform float uGlow;
uniform shader uRings;

// The tube, one unit across, and where its rings sit: the mouth at a depth of 0.42 units, one
// ring every 0.26 units behind it.
const float RINGS = 56.0;
const float CELLS = 16.0;
const float MOUTH = 0.42;
const float SPACING = 0.26;
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

float3 ramp(float x) {
    float3 low = mix(uRamp0, uRamp1, clamp(x * 2.0, 0.0, 1.0));
    return mix(low, uRamp2, clamp(x * 2.0 - 1.0, 0.0, 1.0));
}

float4 ring(float cell, float index) {
    if (index < 0.0 || index >= RINGS) return float4(0.0);
    return uRings.eval(float2(cell + 0.5, index + 0.5));
}

half4 main(float2 position) {
    float2 p = centred(position);
    float pixel = 2.0 / uResolution.y;
    // The far end follows the tune: the nearer a pixel is to the middle, the more it moves.
    float2 d = p - uBend * exp(-length(p) * 2.2);
    float len = max(length(d), 1e-4);
    float2 dir = d / len;
    float reach = wallReach(uLane.xy, dir) * uShape.w;
    float depth = reach / len;
    float2 hit = uLane.xy + dir * reach;
    // Round the tube from the floor, which is down the screen, to the ceiling, the same both sides.
    float around = abs(atan(hit.x, hit.y)) / 3.14159265;
    float cell = min(floor(around * CELLS), CELLS - 1.0);
    // Along the tube: which ring, counted from the live ring at the mouth, and where in it.
    float along = (depth - MOUTH) / SPACING + 1.0 - uFlow.x;
    float index = floor(along);
    float v = along - index;

    float4 texel = ring(cell, index);
    float lit = texel.r * LIGHT_MAX;
    // Light speed: each cell smears back along the tube into a streak of its own colour.
    lit = max(lit, uFlow.y * 0.85 * LIGHT_MAX * ring(cell, index + 1.0).r);
    lit = max(lit, uFlow.y * 0.65 * LIGHT_MAX * ring(cell, index + 2.0).r);
    // A kick brightens the nearest rings in view and keeps their pattern. It stops short of white:
    // those rings are large, and a white flash over them on every kick would be a strobe. A snare's
    // ring runs out to the far point.
    float kick = uHits.x * clamp(1.0 - (index - 1.0) / 5.0, 0.0, 1.0);
    lit = min(lit * (1.0 + 2.0 * kick) + 0.4 * kick, max(lit, 0.85));
    // The snare's ring lights whole cells: the ring it has reached, and the one behind at half.
    float behind = floor(uHits.y) - index;
    lit += uHits.z * (behind == 0.0 ? 1.0 : (behind == 1.0 ? 0.5 : 0.0));
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
    // A lit cell shows its ramp colour at full strength and an unlit one glows dark ember, so the
    // wall reads as clean cells and not as a dim gradient, and the tube shows even where nothing plays.
    float3 colour = ramp(lit) * smoothstep(0.08, 0.45, lit) * body;
    colour += uRamp0 * 0.4 * body;
    // New peaks burn white once their ring has moved away from the mouth. The nearest rings cover
    // much of the screen, and a broadband hit turning them white on every beat would be a strobe.
    colour = mix(colour, float3(1.0), smoothstep(1.0, 1.2, lit) * smoothstep(2.0, 4.5, index) * body);
    colour += ramp(0.6) * rim * 0.3 * uFlow.w;
    // A gate ring: every edge of the ring lit pale gold, below the glow's threshold.
    colour = max(colour, mix(uRamp2, float3(1.0), 0.35) * rim * texel.g);
    // The tube fades into the distance.
    colour *= exp(-max(index, 0.0) * 0.045);
    if (index >= RINGS || index < 0.0) colour = float3(0.0);

    // The small white point far ahead.
    float star = length(p - uBend) / pixel;
    float size = 2.5 * gap * uHits.w;
    colour += float3(1.0) * (1.0 - smoothstep(size - 1.0, size + 1.0, star));
    colour += float3(1.0, 0.95, 0.85) * 0.35 * exp(-star / (size * 4.0));

    return half4(clamp(colour * uGlow, 0.0, 1.0), 1.0);
}
"""
    }
}
