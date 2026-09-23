package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.math.sin
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.PathShape
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Comets
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.follow
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.idle

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
