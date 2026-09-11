package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Comets
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.follow
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatHit
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.kickHit
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.snareHit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Electric blue orbit traps, a waveform corona and radial filaments, all set moving. The Julia
 * constant walks the edge of the main cardioid, so the filigree never stops
 * changing; the figure's centre orbits and breathes an octave each phrase; two corona rings turn against
 * each other; rays sweep once a bar and filaments stream outward. A drop jumps to another part of the
 * cardioid, a new shape.
 */
internal class Alchemy : ShaderPreset(
    source = SOURCE,
    name = "Alchemy",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
) {
    // The look relies on sharp blue filigree and black negative space. A wide bloom
    // erases both. The filaments have their own analytical glow in the one scene draw.
    override val post: PostSpec get() = PostSpec.Off

    private val lobe = genes.choice("Cardioid lobe", 3)
    private val nested = genes.toggle("Nested figure", start = true)
    private val rayDensity = genes.number("Ray density", 0.6f, 1.6f, 1f)
    private val rings = genes.choice("Corona rings", 2, start = 1)
    private val zoomPeriod = genes.choice("Zoom period", 2)

    private val centre = Orbiter(radiusX = 0.36f, radiusY = 0.28f, lapsPerBar = 0.25f)
    private val walkAngle = Slew(maxPerSecond = 1.5f)
    private var jump = 0f
    private var rayShift = 0f
    private val punch = Spring(stiffness = 160f, damping = 0.5f)
    private val sparks = Sprites(200, 1_701L)
    private val comets = Comets(size = 0.03f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        centre.advance(state, gestures)
        kit.place(1, centre.x, centre.y)
        if (gestures.drop) jump += TAU / 3f
        // One circuit of the cardioid every eight phrases, from where the lobe gene starts it.
        walkAngle.advance(genes.walk * TAU + lobe.value * TAU / 3f + jump, dt)
        if (gestures.snareHit > 0f) rayShift += 0.7f
        punch.kick(gestures.kickHit * 5f)
        punch.advance(dt)
        if (gestures.hatHit > 0f) {
            val a = random.next() * TAU
            sparks.burst(centre.x + cos(a) * 0.32f / kit.aspect, centre.y + sin(a) * 0.32f, 5, 0.25f, 0.5f, 0.01f, 0.55f, Sprite.SPARK)
        }
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val aspect = kit.aspect
        // A point on the edge of the main cardioid, just outside it, which is where the lace lives.
        val theta = walkAngle.value + PI.toFloat()
        program.uniform("uC", (0.5f * cos(theta) - 0.25f * cos(2f * theta)) * 1.03f, (0.5f * sin(theta) - 0.25f * sin(2f * theta)) * 1.03f)
        program.uniform("uCentre", (centre.x - 0.5f) * 2f * aspect, (centre.y - 0.5f) * 2f)
        val period = if (zoomPeriod.value == 0) 1f else 2f
        val breathe = ((gestures.phrases % period.toInt()) + gestures.phrasePhase) / period
        program.uniform("uZoom", 2f.pow(0.5f * sin(TAU * breathe)) * (1f + 0.08f * punch.value.coerceIn(0f, 1.5f)))
        program.uniform("uRayTurn", gestures.barPhase * TAU + rayShift)
        program.uniform("uRayDensity", rayDensity.value)
        program.uniform("uNested", nested.weight(1))
        program.uniform("uTwoRings", rings.weight(1))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
        with(comets) { drawComets(state.palette, genes.walk) }
    }

    override fun onReset() {
        centre.reset()
        walkAngle.reset()
        jump = 0f
        rayShift = 0f
        punch.reset()
        sparks.clear()
        comets.clear()
    }

    private companion object {
        const val SOURCE = """
uniform float2 uC;
uniform float2 uCentre;
uniform float uZoom;
uniform float uRayTurn;
uniform float uRayDensity;
uniform float uNested;
uniform float uTwoRings;

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
    float ringRadius = 0.64 + 0.08 * sin(angle * 3.0 + t) +
        0.05 * sin(angle * 7.0 - t * 1.3) + 0.10 * uBass +
        wave * 0.075 + spectrum * 0.055 +
        0.012 * sin(angle * 89.0 + spectrum * 17.0) * (0.3 + uTreble);
    float gap = abs(radius - ringRadius);
    float pixel = 2.0 / uResolution.y / uZoom;
    float corona = (pixel * 1.7) / (gap + pixel * 1.5);
    // A second ring outside the first, turning the other way.
    float ringTwo = 0.8 + 0.06 * sin(angle * 5.0 - t * 1.7) + 0.04 * sin(angle * 11.0 + t) + wave * 0.05;
    corona += uTwoRings * (pixel * 1.3) / (abs(radius - ringTwo) + pixel * 1.5);
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

    float3 colour = float3(0.005, 0.045, 1.0) * (filigree * 3.0 + halo * 0.25 + rays + stream * 0.8);
    colour += float3(0.01, 0.52, 1.0) * (electric + rays * 3.2 + core * 2.0);
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
