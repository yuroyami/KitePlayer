package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Comets
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatSpawn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.follow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Electric blue orbit traps, a waveform corona and radial filaments, all set moving. The Julia
 * constant walks the edge of the main cardioid, so the filigree never stops changing; the figure's
 * centre orbits inside the middle third of the frame and breathes an octave each slow cycle; two
 * corona rings turn against each other; rays sweep once a cycle and filaments stream outward. A
 * snare discharges one lightning arc from the corona into the lace. A drop jumps to another part of
 * the cardioid and turns lead into gold: the lace turns gold from the core out to the corona over
 * one beat, the rays turn pale gold, and after four cycles it cools back to blue from the rim in.
 */
internal class Alchemy : ShaderPreset(
    source = SOURCE,
    name = "Alchemy",
    bucket = VizEnergy.Mid,
    // The camera holds still: a camera that wandered would carry the core out of the middle third.
    kit = Kit(7_932L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 7_932)),
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.LowHit, VizProperty.Size, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        // The discharge: one arc for three frames, at most twice a second.
        VizDrive(VizDriver.BodyHit, VizProperty.Spawn, VizCurve.Discrete, VizResponse.lifetime(0.05f)),
        VizDrive(VizDriver.Drop, VizProperty.Colour, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Pulse, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
    )
    // The look relies on sharp blue filigree and black negative space. A wide bloom
    // erases both. The filaments have their own analytical glow in the one scene draw.
    override val post: PostSpec get() = PostSpec.Off

    private val lobe = genes.choice("Cardioid lobe", 3)
    private val nested = genes.toggle("Nested figure", start = true)
    private val rayDensity = genes.number("Ray density", 0.6f, 1.6f, 1f)
    private val rings = genes.choice("Corona rings", 2, start = 1)
    private val zoomPeriod = genes.choice("Zoom period", 2)

    // Small enough that the core never leaves the middle third of the frame.
    internal val centre = Orbiter(radiusX = 0.12f, radiusY = 0.1f, lapsPerBar = 0.25f)
    private val walkAngle = Slew(maxPerSecond = 1.5f)
    private var jump = 0f
    private var rayShift = 0f
    private val punch = Spring(stiffness = 160f, damping = 0.5f)
    private val sparks = Sprites(200, 1_701L)
    private val comets = Comets(size = 0.03f)

    // The transmutation: music seconds since the drop, or below zero while none runs.
    private var goldAge = -1f
    /** How far the gold has spread from the core, and how far in it has cooled from the rim, in the shader's units. */
    internal var goldSpread = 0f
        private set
    internal var goldCool = GOLD_REACH
        private set
    private var rayGold = 0f

    // The discharge, in the shader's own units round the figure, and how long it still shows.
    private val arcX = FloatArray(ARC_POINTS)
    private val arcY = FloatArray(ARC_POINTS)
    /** Frames the current arc still shows, and how many arcs have been struck since the start. */
    internal var arcFrames = 0
        private set
    internal var arcsStruck = 0
        private set
    private var sinceArc = 99f
    private val arcPath = Path()

    // What the shader was last handed, so the arc lands on the figure the shader drew.
    private var drawnZoom = 1f
    private var drawnCentreX = 0f
    private var drawnCentreY = 0f
    private var drawnTurn = 0f

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        centre.advance(state, gestures)
        kit.place(1, centre.x, centre.y)
        if (gestures.drop) jump += TAU / 3f
        // One circuit of the cardioid every eight phrases, from where the lobe gene starts it.
        walkAngle.advance(genes.walk * TAU + lobe.value * TAU / 3f + jump, dt)
        if (gestures.snare > 0f) rayShift += 0.7f * gestures.snare
        punch.kick(gestures.kick * 5f)
        punch.advance(dt)
        advanceGold(state)
        // One arc on a snare, for three frames, never more than two a second.
        val step = state.stepSeconds
        sinceArc += step
        if (arcFrames > 0) arcFrames--
        if (gestures.snare > 0f && sinceArc >= ARC_GAP && state.frame.audible > 0f) {
            buildArc(state.bassMotion)
            arcFrames = ARC_FRAMES
            arcsStruck++
            sinceArc = 0f
        }
        if (gestures.hat > 0f) {
            val a = random.next() * TAU
            sparks.burst(centre.x + cos(a) * 0.32f / kit.aspect, centre.y + sin(a) * 0.32f,
                gestures.hatSpawn(5), 0.25f, 0.5f, 0.01f, 0.55f, Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    /**
     * Lead into gold and back. The gold spreads from the core to past the corona over one beat,
     * holds for four cycles, then cools back to blue from the rim inward over two cycles. It runs
     * on the music's clock, so a pause holds it where it is.
     */
    private fun advanceGold(state: VizRenderState) {
        if (gestures.surge) goldAge = 0f
        if (goldAge < 0f) {
            goldSpread = 0f
            goldCool = GOLD_REACH
            rayGold = 0f
            return
        }
        goldAge += state.stepSeconds
        val beat = gestures.beatSeconds.coerceAtLeast(0.1f)
        val cycle = gestures.cycleSeconds.coerceAtLeast(0.4f)
        val spreading = (goldAge / beat).coerceIn(0f, 1f)
        val coolFrom = beat + GOLD_HOLD_CYCLES * cycle
        val cooling = ((goldAge - coolFrom) / (GOLD_COOL_CYCLES * cycle)).coerceIn(0f, 1f)
        goldSpread = spreading * GOLD_REACH
        goldCool = (1f - cooling) * GOLD_REACH
        rayGold = spreading * (1f - cooling)
        if (cooling >= 1f) goldAge = -1f
    }

    /** A jagged arc from the corona into the brighter of the lace's two lobes, in the shader's units. */
    private fun buildArc(bass: Float) {
        val lobe = if (random.next() < 0.5f) LOBE_ONE else LOBE_TWO
        val from = lobe + random.signed() * 0.35f
        val to = from + random.signed() * 0.25f
        val outer = 0.64f + 0.1f * bass
        val inner = 0.36f + 0.12f * random.next()
        arcX[0] = outer * cos(from)
        arcY[0] = outer * sin(from)
        arcX[ARC_POINTS - 1] = inner * cos(to)
        arcY[ARC_POINTS - 1] = inner * sin(to)
        // Midpoint displacement: each level halves the segments and the sideways jitter.
        var span = ARC_POINTS - 1
        var jitter = 0.32f
        while (span > 1) {
            val half = span / 2
            var at = 0
            while (at + span < ARC_POINTS) {
                val ax = arcX[at]; val ay = arcY[at]
                val bx = arcX[at + span]; val by = arcY[at + span]
                val dx = bx - ax; val dy = by - ay
                val push = random.signed() * jitter
                arcX[at + half] = (ax + bx) * 0.5f - dy * push
                arcY[at + half] = (ay + by) * 0.5f + dx * push
                at += span
            }
            span = half
            jitter *= 0.62f
        }
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val aspect = kit.aspect
        // A point on the edge of the main cardioid, just outside it, which is where the lace lives.
        val theta = walkAngle.value + PI.toFloat()
        program.uniform("uC", (0.5f * cos(theta) - 0.25f * cos(2f * theta)) * 1.03f, (0.5f * sin(theta) - 0.25f * sin(2f * theta)) * 1.03f)
        drawnCentreX = (centre.x - 0.5f) * 2f * aspect
        drawnCentreY = (centre.y - 0.5f) * 2f
        program.uniform("uCentre", drawnCentreX, drawnCentreY)
        val period = if (zoomPeriod.value == 0) 1f else 2f
        val breathe = ((gestures.slowCycles % period.toInt()) + gestures.slowCyclePhase) / period
        drawnZoom = 2f.pow(0.5f * sin(TAU * breathe)) * (1f + 0.08f * punch.value.coerceIn(0f, 1.5f))
        program.uniform("uZoom", drawnZoom)
        // The shader turns its picture by this much; the arc turns with it.
        drawnTurn = 0.16f * sin(state.musicTime * 0.13f)
        program.uniform("uGold", goldSpread, goldCool, rayGold, 0f)
        program.uniform("uRayTurn", gestures.cyclePhase * TAU + rayShift)
        program.uniform("uRayDensity", rayDensity.value)
        program.uniform("uNested", nested.weight(1))
        program.uniform("uTwoRings", rings.weight(1))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // Blue and white are the idea here, so the sparks and comets ignore the chosen palette too.
        with(sparks) { drawSprites(VizPalette.Ice, genes.walk) }
        with(comets) { drawComets(VizPalette.Ice, genes.walk) }
        if (arcFrames > 0) drawArc(state)
    }

    /** The discharge: blue-white, two pixels wide at 1080 lines, over a faint glow of its own. */
    private fun DrawScope.drawArc(state: VizRenderState) {
        val light = state.lightScale.coerceIn(0f, 1f)
        if (light <= 0f) return
        val half = size.height * 0.5f
        // The shader's point p lands at the middle plus half the height times (centre + zoom * p),
        // with p turned back by the shader's own turn.
        val c = cos(-drawnTurn)
        val s = sin(-drawnTurn)
        arcPath.reset()
        for (i in 0 until ARC_POINTS) {
            val px = arcX[i] * c - arcY[i] * s
            val py = arcX[i] * s + arcY[i] * c
            val x = size.width * 0.5f + half * (drawnCentreX + drawnZoom * px)
            val y = half + half * (drawnCentreY + drawnZoom * py)
            if (i == 0) arcPath.moveTo(x, y) else arcPath.lineTo(x, y)
        }
        val width = max(2f, size.height / 540f)
        drawPath(arcPath, ARC_COLOUR.copy(alpha = 0.28f * light), style = Stroke(width * 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arcPath, ARC_COLOUR.copy(alpha = light), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    override fun onReset() {
        centre.reset()
        walkAngle.reset()
        jump = 0f
        rayShift = 0f
        punch.reset()
        sparks.clear()
        comets.clear()
        goldAge = -1f
        goldSpread = 0f
        goldCool = GOLD_REACH
        rayGold = 0f
        arcFrames = 0
        arcsStruck = 0
        sinceArc = 99f
    }

    internal companion object {
        /** How far the gold reaches, in the shader's units: to the corona and no further. */
        const val GOLD_REACH = 0.9f
        const val GOLD_HOLD_CYCLES = 4f
        const val GOLD_COOL_CYCLES = 2f

        const val ARC_POINTS = 33
        const val ARC_FRAMES = 3
        const val ARC_GAP = 0.5f
        val ARC_COLOUR = Color(0.72f, 0.94f, 1f)

        /** Where the lace is brightest: the angles at which its two lobes peak, in the shader. */
        const val LOBE_ONE = -0.6854f
        const val LOBE_TWO = 2.4562f

        const val SOURCE = """
uniform float2 uC;
uniform float2 uCentre;
uniform float uZoom;
uniform float uRayTurn;
uniform float uRayDensity;
uniform float uNested;
uniform float uTwoRings;
uniform float4 uGold;

// A bounded Julia orbit: the minimum distance to its circular traps keeps detail at several scales.
float julia(float2 z, float2 c) {
    float trap = 10.0;
    float orbitLight = 0.0;
    for (int i = 0; i < 18; i++) {
        float d = dot(z, z);
        if (d > 16.0) break;
        if (i > 3) trap = min(trap, abs(d - (0.72 + 0.10 * uMid)) / (1.0 + d));
        orbitLight += 0.012 / (0.018 + abs(z.x * z.y));
        z = float2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
    }
    float lace = 0.008 / (0.008 + trap);
    return lace * lace * 3.8 + orbitLight * 0.22;
}

half4 main(float2 position) {
    float2 p = (centred(camPoint(position)) - uCentre) / uZoom;
    float t = uMusicTime * 0.13;
    p = rotate(p, 0.16 * sin(t));
    float radius = length(p);
    float angle = atan(p.y, p.x);
    float along = fract(angle / 6.2831853 + 0.5);
    float wave = scopeAt(along);
    float spectrum = bandFolded(along);
    // In silence the rings settle to clean circles; the music puts the waves back in.
    float live = uDrive;
    float ringRadius = 0.64 + live * (0.08 * sin(angle * 3.0 + t) + 0.05 * sin(angle * 7.0 - t * 1.3)) +
        0.10 * uBass + wave * 0.075 + spectrum * 0.055 +
        0.012 * sin(angle * 89.0 + spectrum * 17.0) * (0.3 * live + uTreble);
    float gap = abs(radius - ringRadius);
    float pixel = 2.0 / uResolution.y / uZoom;
    // A narrow line: about a pixel sharper than the soft ring it was.
    float corona = (pixel * 1.3) / (gap + pixel * 0.9);
    // A second ring outside the first, turning the other way.
    float ringTwo = 0.8 + live * (0.06 * sin(angle * 5.0 - t * 1.7) + 0.04 * sin(angle * 11.0 + t)) + wave * 0.05;
    corona += uTwoRings * (pixel * 1.0) / (abs(radius - ringTwo) + pixel * 0.9);
    float halo = 0.018 / (gap + 0.025);

    float2 z = rotate(p, -0.72) * 0.62;
    z += float2(sin(p.y * 4.0 + t), sin(p.x * 4.0 - t)) * 0.055 * uMid;
    float lobes = smoothstep(-0.6, 0.3, sin(angle * 2.0 + 3.0));
    float outside = smoothstep(0.24, 0.95, radius);
    float filigree = julia(z, uC) * lobes * outside;
    // A second figure at half the size, turned, inside the first.
    if (uNested > 0.01) {
        float2 inner = rotate(p, 1.2 + t * 0.3) * 1.24;
        filigree += uNested * julia(inner, float2(uC.x, -uC.y)) * 0.6 * (1.0 - smoothstep(0.1, 0.5, radius));
    }

    // Narrow rays belong to the current spectrum and sweep round once a bar.
    float strand = pow(0.5 + 0.5 * sin(angle * 173.0 * uRayDensity + spectrum * 23.0 + radius * 5.0 * sin(angle * 3.0 + t) + uRayTurn * 3.0), 24.0);
    strand += 0.7 * pow(0.5 + 0.5 * sin(angle * 51.0 * uRayDensity + radius * 9.0 + wave * 3.0 - uRayTurn), 10.0);
    float fan = pow(0.5 + 0.5 * sin(angle * 2.0 + 0.1 + uRayTurn), 4.0);
    float rays = strand * fan * (0.14 + 0.86 * spectrum) *
        exp(-gap * (1.1 - 0.5 * uBass)) * (0.35 + 0.65 * uEnergy);
    // Filaments streaming outward from the figure.
    float stream = pow(0.5 + 0.5 * sin(angle * 37.0 + spectrum * 9.0), 12.0) *
        pow(fract(radius * 2.5 - uMusicTime * 0.6), 6.0) * smoothstep(0.3, 0.9, radius);
    float core = pow(0.5 + 0.5 * sin(angle * 24.0 + radius * 27.0 - t * 4.0), 16.0) *
        exp(-radius * 6.0) * (0.4 + uKick);
    float nodes = pow(0.5 + 0.5 * cos(angle * 41.0 + t * 1.7), 16.0) *
        (pixel * 3.5) / (gap + pixel * 3.5);
    float electric = corona * (0.6 + spectrum * 0.85 + uSnare * 0.45);

    // The transmutation: gold inside the spreading front and inside the cooling one, blue elsewhere.
    // The corona itself stays electric blue, so the gold lace burns inside a blue ring.
    float gold = (1.0 - smoothstep(uGold.x - 0.08, uGold.x, radius)) * (1.0 - smoothstep(uGold.y - 0.08, uGold.y, radius));
    float3 blueDeep = float3(0.005, 0.045, 1.0);
    float3 blueMid = float3(0.01, 0.52, 1.0);
    float3 laceMid = mix(blueMid, float3(1.0, 0.78, 0.12), gold);
    float3 paleGold = float3(1.0, 0.9, 0.62);
    // Saturated blue is dark and saturated gold is bright, so gold is not the blue's twin: broad
    // fields of the figure turn a deep amber, and only the lace's bright lines burn pale gold.
    float lace = filigree * 3.0 + stream * 0.8;
    float3 goldLace = float3(0.45, 0.28, 0.01) * min(lace, 1.5) + float3(0.62, 0.6, 0.34) * smoothstep(1.5, 5.0, lace);
    float3 colour = mix(blueDeep * lace, goldLace, gold) + blueDeep * halo * 0.25;
    colour += mix(blueDeep, paleGold * 0.5, uGold.z) * rays;
    colour += blueMid * electric + mix(blueMid, paleGold, uGold.z) * rays * 3.2 + laceMid * core * 2.0;
    colour += float3(0.72, 0.94, 1.0) * (pow(clamp(electric, 0.0, 1.0), 5.0) + nodes * (0.5 + uHat));
    colour *= 0.28 + 0.72 * uDrive;
    // The negative space still breathes: dim rays from the figure over dim stars.
    float backRays = pow(0.5 + 0.5 * sin(angle * 12.0 + uRayTurn * 0.5), 8.0) * exp(-radius * 0.6) * 0.09;
    float star = pow(hash21(floor(position * 0.5) + uSeed), 80.0) * 0.35;
    // A faint blue haze over the whole of the negative space.
    colour += float3(0.01, 0.06, 0.3) * backRays + float3(star * 0.6, star * 0.8, star) + float3(0.012, 0.035, 0.09);
    return half4(clamp(colour, 0.0, 1.0), 1.0);
}
"""
    }
}
