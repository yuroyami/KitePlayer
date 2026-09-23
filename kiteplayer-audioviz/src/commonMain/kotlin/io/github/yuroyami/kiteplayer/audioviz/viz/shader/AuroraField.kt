package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import kotlin.math.sin
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Comets
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.follow
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.wrap
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.drawn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.idle

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
