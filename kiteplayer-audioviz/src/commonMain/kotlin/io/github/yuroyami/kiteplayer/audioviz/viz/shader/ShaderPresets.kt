package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import kotlin.math.sin
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Comets
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.follow
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.wrap
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.count
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.drawn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.idle
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.presence
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.UP

/**
 * Sixteen blobs of light in three sizes on paths that span the screen, one lap every two visual cycles, over
 * water with light drifting across it. They weld into each other with a soft join, and the weld changes
 * at each supported section boundary, from separate drops to one mass. A kick pulls them together, bubbles rise off them on
 * the hats, and a drop gathers every blob to the middle and lets them go.
 *
 * Each blob is a circle, and the field is their distances welded together; because the join is
 * smooth, two blobs that approach grow a neck and become one shape.
 */
internal class BlobField : ShaderPreset(
    source = SOURCE,
    name = "Blob Field",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.Calm,
    seed = 7f,
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
    )
    private val count = genes.choice("Blobs", 3, start = 2)
    private val weld = genes.number("Weld", 0.05f, 0.5f, 0.2f)
    private val paths = genes.choice("Paths", 3)
    private val bubbleRate = genes.number("Bubbles", 0.5f, 1.8f, 1f)

    private var phase = 0f
    private var ripple = 0f
    private val merge = Spring(stiffness = 60f, damping = 0.6f)
    private var gather = 0f
    private val blobs = FloatArray(MOST * 4)
    private val bubbles = Sprites(200, 1_007L)
    private var bubbleCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        phase += dt * TAU / (gestures.cycleSeconds * 2f) * (0.12f + 1.7f * state.drive)
        ripple += dt * (0.5f * state.idle + 0.8f * state.frame.motionRate)
        if (gestures.section) weld.target = if (weld.value > 0.25f) 0.05f + 0.1f * random.next() else 0.35f + 0.15f * random.next()
        merge.kick(gestures.kick * 3f)
        merge.advance(dt)
        if (gestures.drop) gather = 1f
        gather = (gather - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        val spread = 1f - sin(gather * 3.1415927f)
        val aspect = kit.aspect
        for (index in 0 until MOST) {
            var x = 0f
            var y = 0f
            for (family in 0 until 3) {
                val share = paths.weight(family)
                if (share <= 0f) continue
                x += share * 0.42f * sin(FX[family] * phase + index * GOLDEN)
                y += share * 0.38f * sin(FY[family] * phase + index * GOLDEN * 1.7f + SHIFT[family])
            }
            // In the shader's own units: centred, one unit half the screen height.
            blobs[index * 4] = x * spread * 2f * aspect
            blobs[index * 4 + 1] = y * spread * 2f
            blobs[index * 4 + 2] = SIZES[index % 3] * (0.8f + 0.5f * split.level(index % 3))
            blobs[index * 4 + 3] = count.presence(index, 8, 4)
            if (index == 0) kit.place(0, 0.5f + x * spread, 0.5f + y * spread)
        }
        bubbleCredit += dt * (3f * state.idle + 10f * state.drive) * bubbleRate.value + gestures.hat * 3f
        while (bubbleCredit >= 1f) {
            bubbleCredit -= 1f
            val index = (random.next() * count.count(8, 4)).toInt().coerceIn(0, MOST - 1)
            bubbles.burst(0.5f + blobs[index * 4] / (2f * aspect), 0.5f + blobs[index * 4 + 1] / 2f, 1, 0.15f, 2f, 0.012f, random.next(), Sprite.RING, UP, 0.6f)
        }
        bubbles.advance(dt, drag = 0.3f, gravity = -0.1f)
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniforms("uBlobs", blobs)
        program.uniform("uWeld", weld.value + 0.25f * merge.value.coerceIn(0f, 1.5f))
        program.uniform("uRipple", ripple)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(bubbles) { drawSprites(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift, saturation = 0.3f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
    }

    override fun onReset() {
        phase = 0f
        ripple = 0f
        merge.reset()
        gather = 0f
        bubbles.clear()
        bubbleCredit = 0f
        comets.clear()
    }

    private companion object {
        const val MOST = 16
        const val GOLDEN = 2.3999632f
        val SIZES = floatArrayOf(0.34f, 0.22f, 0.14f)
        val FX = floatArrayOf(1f, 2f, 3f)
        val FY = floatArrayOf(2f, 3f, 2f)
        val SHIFT = floatArrayOf(0f, 0f, 1.5707964f)

        const val SOURCE = """
uniform float4 uBlobs[16];
uniform float uWeld;
uniform float uRipple;

half4 main(float2 position) {
    float2 uv = centred(position);
    float field = 10.0;
    float3 colour = float3(0.0);
    for (int index = 0; index < 16; index++) {
        float4 blob = uBlobs[index];
        if (blob.w <= 0.01) continue;
        float along = float(index) / 16.0;
        float radius = blob.z * (0.5 + 0.5 * blob.w) * (0.7 + 0.5 * band(along));
        float distance = length(uv - blob.xy) - radius;
        field = smoothMin(field, distance, uWeld);
        colour += paletteCycled(along + uWalk) * exp(-abs(distance) * 3.0) * blob.w;
    }
    // Water under the blobs: a dim surface with lines of light drifting across it.
    float caustic = pow(0.5 + 0.5 * sin(uv.x * 7.0 + sin(uv.y * 5.0 + uRipple) * 1.5 + uRipple * 0.7), 6.0)
        + pow(0.5 + 0.5 * sin(uv.y * 9.0 - uRipple * 0.9 + sin(uv.x * 4.0)), 8.0);
    float3 ground = palette(0.3) * 0.18 + palette(0.6) * 0.12 * caustic * (0.4 + 0.6 * uDrive);
    // Bright inside the shape, fading outside it, with a bright rim where the edge falls.
    float inside = smoothstep(0.02, -0.06, field);
    float rim = exp(-abs(field) * 9.0);
    float3 lit = colour * 0.25 * (0.3 + 0.9 * uDrive) + palette(0.85) * rim * 0.5;
    return half4(toneMap(mix(ground, lit, clamp(inside + rim, 0.0, 1.0))), 1.0);
}
"""
    }
}

/**
 * Layered cloud drifting one screen every three bars, lit from inside by a bright core on an orbit,
 * cut by dust lanes that bend with the spectrum's recent past, over stars on three planes that move
 * with the camera. Comets cross on the snare and once a bar.
 *
 * Noise added to itself at half the size, five times over, grows detail at every scale; looked up
 * through a warped copy of itself, it billows.
 */
internal class NebulaField : ShaderPreset(
    source = SOURCE,
    name = "Nebula Field",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Calm,
    seed = 19f,
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bass, VizProperty.Shape),
        VizDrive(VizDriver.Treble, VizProperty.Brightness),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Mood, VizProperty.Shape),
        VizDrive(VizDriver.Pulse, VizProperty.Shape),
    )
    private val direction = genes.choice("Drift", 4)
    private val lanes = genes.number("Lane scale", 0.6f, 1.8f, 1f)
    private val stars = genes.number("Stars", 0.5f, 1.5f, 1f)
    private val corePath = genes.choice("Core path", 2)

    private var driftX = 0f
    private var driftY = 0f
    private var flow = 0f
    private val core = Orbiter(radiusX = 0.3f, radiusY = 0.24f, lapsPerBar = 0.25f)
    private var coreX = 0.5f
    private var coreY = 0.5f
    private var toCentre = 0f
    private val flare = Spring(stiffness = 120f, damping = 0.5f)
    private val snareComets = Travellers(8)
    private val cometMesh = TriangleMesh(maxVertices = 8 * 12 + 8)
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val step = dt / (gestures.cycleSeconds * 3f)
        for (option in 0 until 4) {
            val share = direction.weight(option)
            driftX += share * DRIFT_X[option] * step
            driftY += share * DRIFT_Y[option] * step
        }
        flow += dt * (0.7f * state.idle + 0.6f * state.frame.motionRate)
        core.advance(state, gestures)
        val eight = corePath.weight(1)
        val figureX = 0.5f + 0.32f * sin(core.angle)
        val figureY = 0.5f + 0.2f * sin(2f * core.angle)
        if (gestures.drop) toCentre = 1f
        toCentre = (toCentre - dt / gestures.cycleSeconds).coerceAtLeast(0f)
        val x = core.x + (figureX - core.x) * eight
        val y = core.y + (figureY - core.y) * eight
        coreX = x + (0.5f - x) * toCentre
        coreY = y + (0.5f - y) * toCentre
        kit.place(0, coreX, coreY)
        flare.kick(gestures.kick * 6f)
        flare.advance(dt)
        if (gestures.section) lanes.target = 0.6f + 1.2f * random.next()
        if (gestures.snare > 0f) snareComets.across(random, gestures.beatSeconds * 3f, PathShape.Arc, 0.15f, 0.02f, random.next(), 0f, Sprite.GLOW)
        snareComets.advance(dt)
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val aspect = kit.aspect
        program.uniform("uDrift", driftX * 2f * aspect, driftY * 2f)
        program.uniform("uCore", (coreX - 0.5f) * 2f * aspect, (coreY - 0.5f) * 2f)
        program.uniform("uFlare", flare.value.coerceIn(0f, 1.5f))
        program.uniform("uLanes", lanes.value)
        program.uniform("uStars", stars.value)
        program.uniform("uFlow", flow)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        drawTravellers(snareComets, cometMesh, state.palette, genes.walk)
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun onReset() {
        driftX = 0f
        driftY = 0f
        flow = 0f
        core.reset()
        toCentre = 0f
        flare.reset()
        snareComets.clear()
        comets.clear()
    }

    private companion object {
        val DRIFT_X = floatArrayOf(1f, -1f, 0f, 0f)
        val DRIFT_Y = floatArrayOf(0f, 0f, -1f, 1f)

        const val SOURCE = """
uniform float2 uDrift;
uniform float2 uCore;
uniform float uFlare;
uniform float uLanes;
uniform float uStars;
uniform float uFlow;

half4 main(float2 position) {
    float2 world = centred(camPoint(position));
    float2 uv = (world + uDrift) * 2.2;
    float t = uFlow;

    // The field looked up through a distorted copy of itself, which turns a static cloud into one that curls.
    float2 warp = float2(fbm(uv + t), fbm(uv.yx - t * 0.7));
    float cloud = fbm(uv + warp * (0.6 + 0.8 * uBass) + t);
    // Dust lanes: noise at another scale, bent by the spectrum's recent past.
    float past = history(fract(uv.x * 0.1 + 0.5), fract(uv.y * 0.1 + 0.5));
    float lane = fbm(uv * uLanes * 2.3 + float2(past * 1.5, -t * 0.5));
    cloud *= 0.55 + 0.6 * smoothstep(0.45, 0.55, lane);

    float energy = bandFolded(length(uv) * 0.4);
    float3 colour = paletteCycled(cloud * 0.7 + uKeyHue * 0.2 + uWalk);
    colour *= pow(cloud, 2.2) * (0.4 + 1.6 * energy) * (0.25 + 0.9 * uEnergy);
    // The core lights the cloud from inside.
    float toCore = length(world - uCore);
    colour += paletteCycled(0.1 + uWalk) * cloud * (0.35 + 0.6 * uFlare) * exp(-toCore * 2.2);

    // Stars on three planes: the nearer ones move further with the camera.
    for (int plane = 0; plane < 3; plane++) {
        float depth = 1.0 + float(plane) * 0.6;
        // The stars drift with the cloud, the nearer planes faster.
        float2 cell = floor((position + uCam.xy * uResolution * depth * 0.3 - uDrift * uResolution.y * 0.9 / depth) * (0.5 / depth)) + float(plane) * 17.0;
        float sparkle = pow(hash21(cell + uSeed), 60.0 / uStars);
        colour += float3(sparkle) * (0.15 + 0.6 * uTreble) / depth;
    }

    float3 ground = palette(0.0) * 0.18;
    return half4(toneMap(colour + ground), 1.0);
}
"""
    }
}

/**
 * The northern lights: curtains hanging from the top of the sky on up to four depths, each sliding
 * sideways at its own rate, their lower edges set by the spectrum, over a ridge of mountains drawn from
 * it. Fine rays run through the curtains, a faint band of light travels across the sky, stars move with
 * the camera and a moon crosses slowly. A kick
 * runs a wave along the front curtain and a drop pulls every curtain to the floor. Where shaders cannot
 * run, the plain Aurora drawing stands in.
 */
internal class AuroraField : ShaderPreset(
    source = SOURCE,
    name = "Aurora Field",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Calm,
    seed = 41f,
) {

    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Key, VizProperty.Colour),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
        VizDrive(VizDriver.Bands, VizProperty.Shape),
    )
    private val stand = Aurora()

    override val hasFallback: Boolean get() = true

    private val layers = genes.choice("Layers", 2, start = 1)
    private val foldScale = genes.number("Fold scale", 0.6f, 1.6f, 1f)
    private val colourRule = genes.choice("Curtain colour", 2)
    private val moon = genes.toggle("Moon", start = true)

    private var sway = 0f
    private var lean = 0f
    private val scroll = FloatArray(4)
    private val moonOrbit = Orbiter(centreY = 0.16f, radiusX = 0.36f, radiusY = 0.06f, lapsPerBar = 0.1f)
    private var wave = -1f
    private var dropHold = 0f
    private val floorPull = Envelope(attackPerSecond = 3f, releasePerSecond = 1f)
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        sway += dt * (0.8f * state.idle + 0.8f * state.frame.motionRate)
        for (layer in 0 until 4) scroll[layer] += 2f * dt / (gestures.cycleSeconds * BARS_PER_SCREEN[layer])
        lean += state.stepSeconds * TAU / 16f
        moonOrbit.advance(state, gestures)
        if (gestures.kick > 0f) wave = 0f
        if (wave >= 0f) {
            wave += state.stepSeconds / (gestures.beatSeconds * 2f)
            if (wave > 1.2f) wave = -1f
        }
        if (gestures.drop) dropHold = gestures.cycleSeconds
        dropHold -= dt
        floorPull.advance(if (dropHold > 0f) 1f else 0f, dt)
        comets.advance(state, gestures, random)
        kit.place(0, wrap(scroll[0]), 0.35f)
        kit.place(1, moonOrbit.x, moonOrbit.y)
        kit.follow(2, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uSway", sway)
        program.uniform("uScroll", scroll[0], scroll[1], scroll[2], scroll[3])
        program.uniform("uLayer4", layers.weight(1))
        program.uniform("uFold", foldScale.value)
        program.uniform("uRule", colourRule.weight(1))
        program.uniform("uMoon", moonOrbit.x, moonOrbit.y, moon.weight(1))
        program.uniform("uWave", wave)
        program.uniform("uFloor", floorPull.value)
        program.uniform("uShift", 0.12f * sin(lean))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun DrawScope.drawFallback(state: VizRenderState) {
        with(stand) { draw(state) }
    }

    override fun onReset() {
        sway = 0f
        lean = 0f
        scroll.fill(0f)
        moonOrbit.reset()
        wave = -1f
        dropHold = 0f
        floorPull.reset()
        comets.clear()
        stand.reset()
    }

    private companion object {
        val BARS_PER_SCREEN = floatArrayOf(2f, 4f, 8f, 3f)

        const val SOURCE = """
uniform float uSway;
uniform float4 uScroll;
uniform float uLayer4;
uniform float uFold;
uniform float uRule;
uniform float3 uMoon;
uniform float uWave;
uniform float uFloor;
uniform float uShift;

half4 main(float2 position) {
    float2 uv = position / uResolution;
    float3 colour = mix(palette(0.55) * 0.3, palette(0.0) * 0.25, uv.y);
    // Stars that move a little with the camera, fading towards the horizon.
    float2 starCell = floor((position + uCam.xy * uResolution * 0.4) * 0.5);
    colour += float3(pow(hash21(starCell + uSeed + floor(uSway * 3.0)), 90.0)) * (0.5 + 0.6 * uTreble) * (1.0 - uv.y * 0.5);
    // A faint band of light travelling across the whole sky, so no part of it holds still.
    colour += palette(0.6) * 0.06 * (0.5 + 0.5 * sin(uv.x * 7.0 - uv.y * 3.0 + uSway * 2.0));
    // The moon.
    float moonAway = length((position - uMoon.xy * uResolution) / uResolution.y);
    colour += palette(1.0) * uMoon.z * (smoothstep(0.05, 0.045, moonAway) * 0.8 + 0.15 * exp(-moonAway * 12.0));
    for (int layer = 0; layer < 4; layer++) {
        float weight = layer < 3 ? 1.0 : uLayer4;
        if (weight <= 0.01) continue;
        float depth = float(layer) / 4.0;
        float slide = layer == 0 ? uScroll.x : (layer == 1 ? uScroll.y : (layer == 2 ? uScroll.z : uScroll.w));
        float along = (uv.x + slide) * (1.2 + depth * 0.8) + float(layer) * 0.37;
        float fold = fbm(float2(along * 3.0 * uFold + uSway * (0.6 + depth), float(layer) * 4.0 + uSway * 0.2));
        float strength = band(fract(along * 0.8 + 0.1 * float(layer)));
        float top = 0.02 + 0.08 * depth + uShift;
        float bottom = top + 0.38 + 0.48 * strength * (0.5 + fold);
        if (layer == 0 && uWave >= 0.0) {
            float d = (uv.x - uWave) / 0.08;
            bottom += 0.12 * exp(-d * d);
        }
        bottom = mix(bottom, 0.9, uFloor);
        float x = uv.x + (fold - 0.5) * 0.08;
        float above = smoothstep(bottom + 0.02, bottom - 0.06, uv.y);
        float down = smoothstep(top, bottom, uv.y);
        float rays = 0.35 + 0.65 * noise2(float2(x * 90.0 + float(layer) * 13.0, uv.y * 1.5 - uSway * 0.5));
        float light = above * down * down * rays;
        float3 low = paletteCycled(0.2 + uKeyHue * 0.2 + uWalk);
        float3 high = paletteCycled(0.55 + uKeyHue * 0.2 + depth * 0.15 + uWalk);
        float3 tint = mix(mix(high, low, down), paletteCycled(along * 0.3 + uWalk), uRule);
        colour += tint * light * (0.35 + 0.9 * uEnergy) * (1.0 - depth * 0.45) * weight;
    }
    // A ridge of mountains along the bottom, its outline the spectrum.
    float ridge = 1.0 - 0.12 * (0.4 + 0.6 * bandFolded(fract(uv.x + uScroll.w * 0.5)));
    // The lake under the ridge shimmers with the curtains above it.
    if (uv.y > ridge) colour = palette(0.1) * 0.25 + colour * 0.45 + palette(0.4) * 0.1 * (0.5 + 0.5 * sin(uv.x * 40.0 + uv.y * 30.0 + uSway * 6.0));
    return half4(toneMap(colour), 1.0);
}
"""
    }
}
