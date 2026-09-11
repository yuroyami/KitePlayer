package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Aurora
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Plasma
import kotlin.math.cos
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
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.kickHit
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.snareHit
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.wrap
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.count
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.drawn
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.hatHit
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.presence
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.tempo
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.UP
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.argb
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Swarm
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glyph

/**
 * The classic plasma, as one program, bent round suns that orbit the screen.
 *
 * Its recipe changes as the song goes on: crossing waves, a swirl, or a lattice, sometimes folded six
 * ways. The suns push the field aside and light it, a kick flashes them and throws sparks, and a comet
 * crosses every bar.
 */
internal class PlasmaField : ShaderPreset(
    source = SOURCE,
    name = "Plasma Field",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
    seed = 3f,
) {
    // Where a shader cannot run, the drawn Plasma stands in. It is the same picture, coarser.
    private val stripes = Plasma()

    override val hasFallback: Boolean get() = true

    private val recipe = genes.choice("Recipe", 3)
    private val fold = genes.toggle("Fold", start = false)
    private val suns = genes.choice("Suns", 3, start = 1)
    private val veins = genes.number("Veins", 0.5f, 2f, 1f)

    private val orbits = Array(SUNS) {
        Orbiter(radiusX = 0.32f + 0.06f * it, radiusY = 0.28f + 0.05f * it, lapsPerBar = 0.6f + 0.12f * it, phase = it / SUNS.toFloat())
    }
    private val packed = FloatArray(SUNS * 4)
    private val flash = Spring(stiffness = 160f, damping = 0.45f)
    private var flow = 0f
    private val sparks = Sprites(240, 3_303L)
    private val travellers = Travellers(6)
    private val topMesh = TriangleMesh(maxVertices = 300)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        // Its own clock with a floor, so the field keeps flowing visibly under calm music.
        flow += dt * (0.45f + 1.3f * state.frame.motionRate)
        for (index in 0 until SUNS) {
            val orbit = orbits[index]
            orbit.direction = if (index == 1) -1f else 1f
            orbit.advance(state, gestures)
            kit.place(index, orbit.x, orbit.y)
        }
        flash.kick(state.frame.kick * 7f)
        flash.advance(dt)
        if (state.frame.kick > 0f) {
            sparks.burst(orbits[0].x, orbits[0].y, (8 + 12 * state.frame.kick).toInt(), 0.5f, 1f, 0.012f, 0.1f, Sprite.SPARK)
        }
        if (state.frame.hat > 0f) sparks.sprinkle(3, 0.4f, 0.006f, 0.6f, Sprite.GLOW)
        sparks.advance(dt, drag = 1f)
        if (gestures.bar) {
            travellers.across(random, gestures.barSeconds * 0.75f, PathShape.Arc, 0.18f, 0.05f, random.next(), 0f, Sprite.STREAK)
        }
        travellers.advance(dt)
        val newest = travellers.newest
        if (newest >= 0 && travellers.alive[newest]) {
            kit.place(SUNS, travellers.x[newest], travellers.y[newest])
        } else if (anchors.size <= SUNS) {
            kit.place(SUNS, 0.5f, 0.5f)
        }
    }

    /** How much of sun [index] to draw: the suns being added or taken away fade over a bar. */
    private fun presence(index: Int): Float {
        val now = suns.value + 1
        val before = suns.previous + 1
        return when {
            index < minOf(now, before) -> 1f
            index < now -> suns.mix
            index < before -> 1f - suns.mix
            else -> 0f
        }
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        for (index in 0 until SUNS) {
            packed[index * 4] = orbits[index].x
            packed[index * 4 + 1] = orbits[index].y
            packed[index * 4 + 2] = presence(index)
            packed[index * 4 + 3] = 0.6f + 0.4f * flash.value.coerceIn(0f, 1.5f)
        }
        program.uniforms("uSuns", packed)
        program.uniform("uRecipe", recipe.weight(0), recipe.weight(1), recipe.weight(2))
        program.uniform("uFold", fold.weight(1))
        program.uniform("uVeins", veins.value)
        program.uniform("uFlow", flow)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
        drawTravellers(travellers, topMesh, state.palette, genes.walk)
    }

    override fun DrawScope.drawFallback(state: VizRenderState) {
        with(stripes) { draw(state) }
    }

    override fun onReset() {
        orbits.forEach { it.reset() }
        flash.reset()
        flow = 0f
        sparks.clear()
        travellers.clear()
    }

    private companion object {
        const val SUNS = 3

        const val SOURCE = """
uniform float4 uSuns[3];
uniform float3 uRecipe;
uniform float uFold;
uniform float uVeins;
uniform float uFlow;

// Three recipes for the same field, blended by how far a change between them has come.
float field(float2 uv, float flow) {
    float waves = sin(uv.x * (3.5 + 2.0 * uMid) + flow) + sin(uv.y * 2.8 - flow * 0.7)
        + sin((uv.x + uv.y) * 2.3 + flow * 1.3) + sin(length(uv) * (3.5 + 2.0 * uBass) - flow * 2.0);
    float reach = length(uv);
    float swirl = 2.0 * sin(atan(uv.y, uv.x) * 3.0 + reach * 4.0 - flow * 1.5) + 2.0 * sin(uv.x * 2.0 - uv.y * 1.3 - flow);
    float2 g = uv * 2.2 + float2(flow * 0.3, -flow * 0.2);
    float lattice = 3.0 * sin(g.x * 3.14159) * sin(g.y * 3.14159) + sin(reach * 5.0 - flow * 2.0);
    return waves * uRecipe.x + swirl * uRecipe.y + lattice * uRecipe.z;
}

half4 main(float2 position) {
    float2 uv = centred(camPoint(position));
    if (uFold > 0.0) {
        float reach = length(uv);
        float wedge = 6.2831853 / 6.0;
        float angle = abs(mod(atan(uv.y, uv.x) + uPhrasePhase * wedge, wedge) - wedge * 0.5);
        uv = mix(uv, reach * float2(cos(angle), sin(angle)), uFold);
    }
    float aspect = uResolution.x / uResolution.y;
    float3 light = float3(0.0);
    for (int index = 0; index < 3; index++) {
        float4 sun = uSuns[index];
        if (sun.z <= 0.0) continue;
        float2 at = float2((sun.x - 0.5) * 2.0 * aspect, (sun.y - 0.5) * 2.0);
        float2 away = uv - at;
        float d = length(away);
        // Each sun pushes the field aside and lights it from inside.
        uv += away / max(d, 0.05) * sun.z * 0.22 * exp(-d * 2.5) * (0.5 + uKick);
        light += paletteLoop(float(index) * 0.33 + uWalk + 0.2) * sun.z * sun.w * 0.025 / (d * d + 0.025);
    }
    float f = field(uv, uFlow);
    // The spectrum is read along the field itself, so the loud parts move with it instead of sitting on one side.
    float energy = bandFolded(fract(f * 0.09 + length(uv) * 0.12 + uFlow * 0.05));
    float3 colour = paletteLoop(f / 8.0 + 0.5 + energy * 0.3 + uKeyHue * 0.1 + uWalk);
    float veins = pow(0.5 + 0.5 * sin(f * (5.0 + 4.0 * uTreble) * uVeins), 12.0);
    colour *= 0.12 + 0.45 * energy + 0.45 * uEnergy;
    colour += palette(0.92) * veins * (0.12 + 0.48 * uTreble);
    return half4(toneMap(colour + light * (0.5 + 0.8 * uEnergy)), 1.0);
}
"""
    }
}

/**
 * How far a camera has flown, added up one frame at a time.
 *
 * The tempting version, music time multiplied by a speed that follows the music, goes wrong more
 * the longer a song plays. Any change in the speed moves the camera by that change times all the
 * time so far, so a few minutes in, a flicker of loudness throws it several arches forward or back
 * in one frame. Speed times frame time, added up, cannot jump.
 */
private class Flight(private val speed: Float) {
    private var travelled = 0f

    fun advance(state: VizRenderState): Float {
        travelled += state.deltaSeconds * speed * (0.25f + state.drive)
        return travelled
    }

    fun reset() {
        travelled = 0f
    }
}

/**
 * A flight down a corridor of arches, banking into its turns. Six lights float on paths ahead of the
 * camera and light the stone, banners hang under the arches and sway with the middle of the spectrum,
 * stripes of light race along the floor, and dust drifts in front. Every phrase the corridor changes:
 * arches close together, far apart and tall, or under a vault. A kick lights the stripes, a snare
 * snaps a banner, and a drop pulls every light to the middle.
 *
 * There is no geometry: for each pixel a ray is walked forward in steps as long as the distance to
 * the nearest wall, which is safe because nothing can be closer than that.
 */
internal class Cathedral : ShaderPreset(
    source = SOURCE,
    name = "Cathedral",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.Mid,
    seed = 11f,
) {
    private val corridor = genes.choice("Corridor", 3)
    private val lightCount = genes.choice("Lights", 3, start = 2)
    private val banners = genes.toggle("Banners", start = true)
    private val speed = genes.number("Flight speed", 0.7f, 1.4f, 1f)

    private var travelled = 0f
    private var stripes = 0f
    private var drift = 0f
    private var snap = 0f
    private var gather = 0f
    private val flash = Envelope(attackPerSecond = 30f, releasePerSecond = 3f)
    private val lights = FloatArray(LIGHTS * 4)
    private val dust = Sprites(160, 1_011L)
    private var dustCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        // Fast enough to read as flight, and a new corridor every phrase.
        travelled += dt * 18f * (0.25f + state.drive) * speed.value
        stripes += dt * 20f * (0.4f + state.drive)
        drift += dt * (0.6f + 0.8f * state.frame.motionRate)
        if (gestures.phrase) corridor.choose((corridor.value + 1) % 3)
        if (gestures.drop) gather = 1f
        gather = (gather - dt / gestures.barSeconds).coerceAtLeast(0f)
        if (gestures.snareHit > 0f) snap = 1f
        snap = (snap - dt * 3f).coerceAtLeast(0f)
        flash.hit(gestures.kickHit)
        flash.advance(0f, dt)
        val pull = 1f - sin(gather * 3.1415927f)
        for (index in 0 until LIGHTS) {
            val phase = drift * (0.5f + 0.13f * index) + index * 1.9f
            lights[index * 4] = sin(phase) * 2.6f * pull
            lights[index * 4 + 1] = 0.6f + 1.4f * sin(phase * 0.7f + index) * pull
            lights[index * 4 + 2] = travelled + 5f + 3.2f * index
            lights[index * 4 + 3] = lightCount.presence(index, 2, 2)
        }
        // The first light as the viewer sees it, through the same lens the shader uses.
        val eyeX = sin(drift * 0.3f) * 1.4f
        val eyeY = 0.1f + 0.4f * sin(drift * 0.21f)
        val depth = (lights[2] - travelled).coerceAtLeast(0.5f)
        kit.place(1, 0.5f + (lights[0] - eyeX) * 1.5f / depth / (2f * kit.aspect), 0.5f - (lights[1] - eyeY) * 1.5f / depth / 2f)
        dustCredit += dt * (60f + 120f * state.drive)
        while (dustCredit >= 1f) {
            dustCredit -= 1f
            dust.sprinkle(1, 1.5f, 0.008f, 0.1f + 0.2f * random.next(), Sprite.GLOW, drift = 0.06f)
        }
        dust.advance(dt, drag = 0.2f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uFlight", travelled)
        program.uniform("uDrift", drift)
        program.uniform("uStripes", stripes)
        program.uniforms("uLights", lights)
        program.uniform("uCorridor", corridor.weight(0), corridor.weight(1), corridor.weight(2))
        program.uniform("uBanner", banners.weight(1), snap)
        program.uniform("uFlash", flash.value)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(dust) { drawSprites(state.palette, genes.walk, alpha = 0.5f + 0.4f * state.lift, saturation = 0.3f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    override fun onReset() {
        travelled = 0f
        stripes = 0f
        drift = 0f
        snap = 0f
        gather = 0f
        flash.reset()
        dust.clear()
        dustCredit = 0f
        comets.clear()
    }

    private companion object {
        const val LIGHTS = 6

        const val SOURCE = """
uniform float uFlight;
uniform float uDrift;
uniform float uStripes;
uniform float4 uLights[6];
uniform float3 uCorridor;
uniform float2 uBanner;
uniform float uFlash;

// How far apart the arches stand: close, far apart and tall, or close under a vault.
float spacing() {
    return 4.0 * uCorridor.x + 6.0 * uCorridor.y + 4.0 * uCorridor.z;
}

// One arch: a slab with an opening cut through it, straight at the sides and round at the top.
float archFrame(float3 point, float height, float gap) {
    float3 cell = point;
    cell.z = mod(cell.z, gap) - gap * 0.5;
    float groundY = -2.2;
    float spring = height - 1.6;
    float top = height + 1.2;
    float slab = sdBox(cell - float3(0.0, (top + groundY) * 0.5, 0.0), float3(3.4, (top - groundY) * 0.5, 0.45));
    float roundTop = length(float2(cell.x, cell.y - spring)) - 1.9;
    float straight = sdBox(cell - float3(0.0, (spring + groundY) * 0.5, 0.0), float3(1.9, (spring - groundY) * 0.5, 2.0));
    return max(slab, -min(roundTop, straight));
}

// Which arch this is, turned into a position in the spectrum.
float archHeight(float z, float gap) {
    return (1.8 + 2.2 * bandFolded(fract(floor(z / gap) * 0.17 + uPhrasePhase))) * (1.0 + 0.4 * uCorridor.y);
}

// A banner under each arch, swaying with the middle of the spectrum and snapping on a snare.
float banner(float3 point, float gap) {
    float index = floor(point.z / gap);
    float3 cell = point;
    cell.z = mod(cell.z, gap) - gap * 0.5;
    float sway = sin(uDrift * 1.7 + index * 1.3 + cell.y * 1.5) * (0.2 + 0.5 * uMid) + uBanner.y * 0.6 * sin(index);
    return sdBox(cell - float3(sway * (0.5 - 0.2 * cell.y), 0.9, 0.0), float3(0.7, 1.3, 0.03));
}

float scene(float3 point) {
    float gap = spacing();
    float shape = min(archFrame(point, archHeight(point.z, gap), gap), min(point.y + 2.2, 4.4 - abs(point.x)));
    if (uCorridor.z > 0.01) shape = min(shape, mix(40.0, 6.5 - point.y, uCorridor.z));
    if (uBanner.x > 0.5) shape = min(shape, banner(point, gap));
    return shape;
}

half4 main(float2 position) {
    float2 uv = view(position);
    float3 eye = float3(sin(uDrift * 0.3) * 1.4, 0.1 + 0.4 * sin(uDrift * 0.21), uFlight);
    float3 ray = normalize(float3(uv.x, uv.y, 1.5));
    // Banking into the turns of the flight.
    ray.xy = rotate(ray.xy, sin(uDrift * 0.27) * 0.8 + uKick * 0.06);

    float travelled = 0.0;
    float hit = 0.0;
    for (int step = 0; step < 80; step++) {
        float distance = scene(eye + ray * travelled);
        if (distance < 0.003) {
            hit = 1.0;
            break;
        }
        travelled += distance * 0.85;
        if (travelled > 45.0) break;
    }

    // The lights themselves, as glows wherever the ray passes close to one before it hits anything.
    float3 glow = float3(0.0);
    for (int index = 0; index < 6; index++) {
        float4 light = uLights[index];
        if (light.w <= 0.0) continue;
        float ahead = max(dot(light.xyz - eye, ray), 0.0);
        if (hit > 0.5 && ahead > travelled) continue;
        float miss = length(eye + ray * ahead - light.xyz);
        glow += paletteCycled(float(index) * 0.17 + uWalk) * exp(-miss * 6.0) * light.w * (0.2 + 1.3 * uDrive);
    }
    float3 haze = mix(palette(0.08) * 0.35, palette(0.65) * 0.6, clamp(uv.y * 0.6 + 0.4, 0.0, 1.0)) * (0.5 + 0.7 * uDrive);
    float3 sky = haze + palette(1.0) * pow(hash21(floor(position * 0.5) + uSeed), 80.0) * (0.3 + uTreble);
    if (hit < 0.5) return half4(toneMap(sky + glow), 1.0);

    float3 at = eye + ray * travelled;
    float2 nudge = float2(1.0, -1.0) * 0.003;
    float3 normal = normalize(
        nudge.xyy * scene(at + nudge.xyy) +
        nudge.yyx * scene(at + nudge.yyx) +
        nudge.yxy * scene(at + nudge.yxy) +
        nudge.xxx * scene(at + nudge.xxx)
    );

    // How open the space round this point is: corners and joins come out darker, which is most of
    // what makes a render read as solid.
    float occlusion = clamp(scene(at + normal * 0.35) / 0.35, 0.0, 1.0);
    float lamp = max(dot(normal, -ray), 0.0) / (1.0 + travelled * 0.06);
    float key = max(dot(normal, normalize(float3(0.35, 0.85, -0.4))), 0.0);
    float carving = pow(0.5 + 0.5 * sin(at.y * 11.0 + sin(at.x * 8.0)), 16.0);
    float3 stone = mix(palette(0.12), palette(0.55), key * 0.7 + 0.15);
    float3 colour = stone * (0.12 + 0.6 * lamp + 0.3 * key) * (0.35 + 0.65 * occlusion) * (0.3 + 1.0 * uDrive);
    colour += paletteCycled(at.z * 0.04 + uWalk) * carving * (0.1 + 0.8 * uDrive) * occlusion;
    // The floating lights light the stone.
    for (int index = 0; index < 6; index++) {
        float4 light = uLights[index];
        if (light.w <= 0.0) continue;
        float3 toLight = light.xyz - at;
        float reach = length(toLight);
        colour += paletteCycled(float(index) * 0.17 + uWalk) * max(dot(normal, toLight / reach), 0.0) * light.w * 3.0 / (1.0 + reach * reach) * (0.2 + 1.2 * uDrive);
    }
    // Stripes of light racing along the floor, brighter on the kick.
    if (at.y < -2.15) {
        float stripe = 1.0 - smoothstep(0.0, 0.25, abs(mod(at.z - uStripes, 3.0) - 1.5));
        colour += palette(0.9) * stripe * (0.1 + 0.5 * uDrive + 0.8 * uFlash);
    }
    colour = fogged(colour, haze, travelled, 0.045 + 0.03 * (1.0 - uEnergy)) + glow;
    // Darker at the corners of the screen, the way a lens darkens them.
    colour *= 1.0 - 0.35 * dot(uv * 0.55, uv * 0.55);
    return half4(toneMap(colour), 1.0);
}
"""
    }
}

/**
 * A flight down a street between buildings lit by the music, the street turning every two phrases.
 * Traffic streams past both ways, signs flicker on the hats, searchlights sweep the sky, rain falls in
 * front, and the wet street mirrors it all. A kick flashes the windows, a snare swings a searchlight,
 * and a drop puts every window on.
 *
 * The city is one building repeated across the ground by folding position into a grid, each building
 * as tall as its own part of the spectrum. The street is the gap the fold leaves between two rows.
 */
internal class NeonCity : ShaderPreset(
    source = SOURCE,
    name = "Neon City",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.High,
    seed = 23f,
) {
    private val bendGene = genes.number("Bend", 0.1f, 0.45f, 0.28f)
    private val traffic = genes.number("Traffic", 0.5f, 1.6f, 1f)
    private val signs = genes.number("Signs", 0.1f, 0.5f, 0.25f)
    private val searchlights = genes.choice("Searchlights", 3, start = 1)

    private var travelled = 0f
    private var cars = 0f
    private var sway = 0f
    private var way = 1f
    private val bend = Slew(maxPerSecond = 0.25f)
    private val beamPhase = FloatArray(3) { it * 2.1f }
    private val beams = FloatArray(3)
    private val flash = Envelope(attackPerSecond = 40f, releasePerSecond = 4f)
    private var allOn = 0f
    private val rain = Sprites(400, 1_023L)
    private var rainCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        travelled += dt * 4f * (0.25f + state.drive)
        cars += dt * (6f + 10f * state.drive) * traffic.value
        sway += dt * (0.4f + 0.6f * state.frame.motionRate)
        // The street turns the other way every two phrases.
        if (gestures.phrase && gestures.phrases % 2 == 0) way = -way
        bend.advance(way * bendGene.value, dt)
        flash.hit(gestures.kickHit)
        flash.advance(0f, dt)
        if (gestures.drop) allOn = 1f
        allOn = (allOn - dt / gestures.barSeconds).coerceAtLeast(0f)
        if (gestures.snareHit > 0f) beamPhase[(random.next() * searchlights.count(1)).toInt().coerceIn(0, 2)] += 1.2f
        for (index in 0 until 3) {
            beamPhase[index] += dt * (0.7f + 0.25f * index) * state.tempo
            beams[index] += (sin(beamPhase[index]) * 0.9f - beams[index]) * (dt * 4f).coerceAtMost(1f)
        }
        kit.place(1, 0.5f + 0.4f * sin(beams[0]), 0.12f)
        rainCredit += dt * (60f + 140f * state.drive)
        while (rainCredit >= 1f) {
            rainCredit -= 1f
            rain.burst(random.next() * 1.1f - 0.05f, -0.02f, 1, 1.4f, 0.8f, 0.01f, 0.6f, Sprite.STREAK, 1.69f, 0.05f)
        }
        rain.advance(dt, drag = 0f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uFlight", travelled)
        program.uniform("uCars", cars)
        program.uniform("uSway", sway)
        program.uniform("uBend", bend.value)
        program.uniform("uBeams", beams[0], beams[1], beams[2])
        program.uniform("uBeamCount", searchlights.count(1).toFloat())
        program.uniform("uSigns", signs.value)
        program.uniform("uFlash", maxOf(flash.value, allOn))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(rain) { drawSprites(state.palette, genes.walk, alpha = 0.35f + 0.4f * state.lift, saturation = 0.2f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    override fun onReset() {
        travelled = 0f
        cars = 0f
        sway = 0f
        way = 1f
        bend.reset()
        for (index in 0 until 3) beamPhase[index] = index * 2.1f
        beams.fill(0f)
        flash.reset()
        allOn = 0f
        rain.clear()
        rainCredit = 0f
        comets.clear()
    }

    private companion object {
        const val SOURCE = """
uniform float uFlight;
uniform float uCars;
uniform float uSway;
uniform float uBend;
uniform float3 uBeams;
uniform float uBeamCount;
uniform float uSigns;
uniform float uFlash;

float cityHeight(float2 cell) {
    return 0.8 + 3.2 * bandFolded(fract(cell.x * 0.37 + cell.y * 0.13 + 0.05));
}

float scene(float3 point) {
    float2 cell = floor(point.xz / 3.0);
    float2 local = mod(point.xz, 3.0) - 1.5;
    float tall = cityHeight(cell);
    float building = sdBox(float3(local.x, point.y - tall * 0.5, local.y), float3(0.95, tall * 0.5, 0.95));
    return min(building, point.y);
}

// Head and tail lights in two lanes running opposite ways, drawn stretched along the street.
float3 traffic(float3 eye, float3 ray, float limit) {
    float3 light = float3(0.0);
    for (int index = 0; index < 8; index++) {
        float lane = index < 4 ? 1.0 : -1.0;
        float along = fract(float(index) * 0.37 + uCars * (0.03 + 0.004 * float(index)) * lane);
        float3 car = float3(lane * 0.3, 0.15, eye.z + 2.0 + along * 40.0);
        float ahead = max(dot(car - eye, ray), 0.0);
        if (ahead > limit) continue;
        float3 off = eye + ray * ahead - car;
        off.z *= 0.15;
        float3 tint = lane > 0.0 ? palette(0.95) : paletteCycled(0.02 + uWalk);
        light += tint * exp(-length(off) * 30.0) * exp(-ahead * 0.04) * (0.6 + uEnergy);
    }
    return light;
}

half4 main(float2 position) {
    float2 uv = view(position);
    float3 eye = float3(sin(uSway * 0.2) * 0.3, 1.1 + 0.3 * sin(uSway * 0.13), uFlight);
    float3 ray = normalize(float3(uv.x, uv.y - 0.12, 1.3));
    ray.xz = rotate(ray.xz, uBend);
    ray.xy = rotate(ray.xy, sin(uSway * 0.19) * 0.12);

    float travelled = 0.0;
    float hit = 0.0;
    for (int march = 0; march < 70; march++) {
        float distance = scene(eye + ray * travelled);
        if (distance < 0.004) {
            hit = 1.0;
            break;
        }
        // Never a step longer than a building is wide, or a ray can jump clean through the next one.
        travelled += min(distance * 0.8, 1.2);
        if (travelled > 60.0) break;
    }

    // Night over a city: dark overhead, a glow along the horizon from all the windows, and beams.
    float glow = exp(-abs(uv.y + 0.05) * 3.0);
    float3 sky = palette(0.05) * 0.2 + palette(0.35) * 0.5 * glow;
    for (int index = 0; index < 3; index++) {
        float on = clamp(uBeamCount - float(index), 0.0, 1.0);
        float angle = index == 0 ? uBeams.x : (index == 1 ? uBeams.y : uBeams.z);
        float2 away = uv - float2(-0.9 + 0.9 * float(index), -0.1);
        float across = abs(atan(away.x, away.y) - angle);
        sky += palette(0.9) * exp(-across * 40.0) * smoothstep(0.0, 0.1, away.y) * exp(-length(away) * 0.8) * on * (0.3 + 0.5 * uEnergy);
    }
    float3 cars = traffic(eye, ray, hit > 0.5 ? travelled : 60.0);
    if (hit < 0.5) return half4(toneMap(sky + cars), 1.0);

    float3 at = eye + ray * travelled;
    float2 nudge = float2(1.0, -1.0) * 0.003;
    float3 normal = normalize(
        nudge.xyy * scene(at + nudge.xyy) +
        nudge.yyx * scene(at + nudge.yyx) +
        nudge.yxy * scene(at + nudge.yxy) +
        nudge.xxx * scene(at + nudge.xxx)
    );

    float3 colour;
    if (at.y < 0.01) {
        // A wet street: streaks of the glow above, and the traffic mirrored in it.
        colour = palette(0.15) * 0.18 + paletteCycled(at.x * 0.12 + uWalk) * 0.5 * (0.4 + uEnergy) * pow(0.5 + 0.5 * sin(at.z * 3.0 + at.x * 7.0), 3.0);
        colour += traffic(at, float3(ray.x, -ray.y, ray.z), 40.0) * 0.6;
    } else {
        float2 cell = floor(at.xz / 3.0);
        float strength = bandFolded(fract(cell.x * 0.37 + cell.y * 0.13 + 0.05));
        float across = abs(normal.x) > 0.5 ? at.z : at.x;
        float panes = step(0.48, fract(at.y * 5.0)) * step(0.38, fract(across * 3.0));
        float lamp = max(dot(normal, -ray), 0.0);
        colour = palette(0.12) * (0.15 + 0.35 * lamp);
        colour += paletteCycled(cell.x * 0.21 + cell.y * 0.13 + uWalk) * panes * (0.25 + 1.4 * strength) * (0.4 + 0.6 * uEnergy + 0.8 * uFlash);
        colour += palette(0.95) * exp(-abs(at.y - cityHeight(cell)) * 40.0) * (0.4 + uHat);
        // Signs: a band of light on some buildings, flickering on the hats.
        float chosen = step(1.0 - uSigns, hash21(cell * 1.7 + 3.0));
        float band = step(0.55, fract(at.y * 0.5 + hash21(cell))) * (1.0 - step(0.75, fract(at.y * 0.5 + hash21(cell))));
        float flicker = 0.6 + 0.8 * uHat * step(0.5, hash21(cell + floor(uSway * 8.0)));
        colour += paletteCycled(hash21(cell) + uWalk) * chosen * band * flicker * 1.6;
    }
    colour = fogged(colour, sky, travelled, 0.035) + cars;
    return half4(toneMap(colour), 1.0);
}
"""
    }
}

/**
 * A flight down the tunnels of a Menger sponge: a cube with its middle cut out, and the middle of every
 * smaller cube cut out again, repeated in every direction so the tunnel never ends. Three lights ride
 * ahead of the camera, one each for the kick, the snare and the hat, and flash with their drums. Sparks
 * stream out of the holes past the camera, the number of folds changes over each phrase, and a drop
 * rolls the camera a full turn and rushes it forward for a bar.
 */
internal class Menger : ShaderPreset(
    source = SOURCE,
    name = "Menger",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.Mid,
    seed = 29f,
) {
    private val folds = genes.number("Folds", 2f, 4f, 3f)
    private val path = genes.toggle("Corkscrew", start = false)
    private val lightCount = genes.choice("Lights", 3, start = 2)
    private val particleRate = genes.number("Particles", 0.5f, 1.8f, 1f)

    private var travelled = 0f
    private var turn = 0f
    private var roll = 0f
    private var rush = 0f
    private val drums = Array(3) { Envelope(attackPerSecond = 40f, releasePerSecond = 3f) }
    private val lights = FloatArray(12)
    private val particles = Sprites(300, 1_029L)
    private var credit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        if (gestures.phrase) folds.target = if (folds.value > 3f) 2f + random.next() else 3f + random.next()
        if (gestures.drop) rush = gestures.barSeconds
        rush -= dt
        travelled += dt * 1.2f * (0.25f + state.drive) * if (rush > 0f) 3f else 1f
        turn += dt * (0.5f + 0.7f * state.frame.motionRate)
        if (rush > 0f) roll += dt * TAU / gestures.barSeconds
        drums[0].hit(gestures.kickHit)
        drums[1].hit(gestures.snareHit)
        drums[2].hit(gestures.hatHit)
        for (index in 0 until 3) {
            drums[index].advance(0f, dt)
            val angle = turn * (1f + 0.3f * index) + index * TAU / 3f
            lights[index * 4] = cos(angle) * 0.22f
            lights[index * 4 + 1] = sin(angle) * 0.22f
            lights[index * 4 + 2] = 0.6f + 0.5f * index
            lights[index * 4 + 3] = lightCount.presence(index, 1) * (0.3f + 1.2f * drums[index].value)
        }
        // Sparks out of the holes, speeding up as they rush past the camera.
        credit += dt * (30f + 80f * state.drive) * particleRate.value
        while (credit >= 1f) {
            credit -= 1f
            particles.burst(0.5f, 0.5f, 1, 0.5f + 0.6f * state.drive, 1f, 0.01f, random.next(), Sprite.STREAK)
        }
        particles.advance(dt, drag = -0.8f)
        kit.place(1, 0.5f + cos(turn) * 0.15f, 0.5f + sin(turn) * 0.15f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uFlight", travelled)
        program.uniform("uTurn", turn)
        program.uniform("uRoll", roll + 0.3f * sin(turn * 0.6f))
        program.uniform("uFolds", folds.value)
        program.uniform("uCorkscrew", path.weight(1))
        program.uniforms("uLights", lights)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(particles) { drawSprites(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    override fun onReset() {
        travelled = 0f
        turn = 0f
        roll = 0f
        rush = 0f
        drums.forEach { it.reset() }
        particles.clear()
        credit = 0f
        comets.clear()
    }

    private companion object {
        const val SOURCE = """
uniform float uFlight;
uniform float uTurn;
uniform float uRoll;
uniform float uFolds;
uniform float uCorkscrew;
uniform float4 uLights[3];

// The sponge repeated in every direction. The last fold fades in as the gene rises, so the count glides.
float sponge(float3 point) {
    float3 cell = mod(point + 1.0, 2.0) - 1.0;
    float distance = sdBox(cell, float3(1.0));
    float scale = 1.0;
    for (int fold = 0; fold < 4; fold++) {
        float3 inner = mod(cell * scale, 2.0) - 1.0;
        scale *= 3.0;
        float3 away = abs(1.0 - 3.0 * abs(inner));
        float hole = (min(max(away.x, away.y), min(max(away.y, away.z), max(away.z, away.x))) - 1.0) / scale;
        distance = mix(distance, max(distance, hole), clamp(uFolds - float(fold), 0.0, 1.0));
    }
    return distance;
}

half4 main(float2 position) {
    float2 uv = view(position);
    float3 eye = float3(uCorkscrew * 0.18 * cos(uTurn), uCorkscrew * 0.18 * sin(uTurn), uFlight);
    float3 ray = normalize(float3(uv.x, uv.y, 1.4));
    ray.xy = rotate(ray.xy, uRoll);

    float travelled = 0.0;
    float hit = 0.0;
    for (int march = 0; march < 72; march++) {
        float distance = sponge(eye + ray * travelled);
        if (distance < 0.0015 * (1.0 + travelled)) {
            hit = 1.0;
            break;
        }
        travelled += distance;
        if (travelled > 14.0) break;
    }

    // The lights ride ahead of the camera; where a ray passes close to one before a wall, it glows.
    float3 flare = float3(0.0);
    for (int index = 0; index < 3; index++) {
        float4 light = uLights[index];
        if (light.w <= 0.0) continue;
        float3 place = eye + light.xyz;
        float ahead = max(dot(place - eye, ray), 0.0);
        if (hit > 0.5 && ahead > travelled) continue;
        flare += paletteCycled(float(index) * 0.33 + uWalk) * exp(-length(eye + ray * ahead - place) * 22.0) * light.w;
    }
    float3 far = palette(0.0) * 0.25 + palette(0.25) * 0.1;
    if (hit < 0.5) return half4(toneMap(far + flare), 1.0);

    float3 at = eye + ray * travelled;
    float2 nudge = float2(1.0, -1.0) * 0.002;
    float3 normal = normalize(
        nudge.xyy * sponge(at + nudge.xyy) +
        nudge.yyx * sponge(at + nudge.yyx) +
        nudge.yxy * sponge(at + nudge.yxy) +
        nudge.xxx * sponge(at + nudge.xxx)
    );
    float occlusion = clamp(sponge(at + normal * 0.05) / 0.05, 0.0, 1.0);
    float3 colour = paletteCycled(at.z * 0.05 + length(at.xy) * 0.3 + uWalk) * (0.08 + 0.3 * max(dot(normal, -ray), 0.0)) * (0.3 + 0.7 * occlusion);
    for (int index = 0; index < 3; index++) {
        float4 light = uLights[index];
        if (light.w <= 0.0) continue;
        float3 toLight = eye + light.xyz - at;
        float reach = length(toLight);
        colour += paletteCycled(float(index) * 0.33 + uWalk) * max(dot(normal, toLight / reach), 0.0) * light.w * 0.6 / (0.05 + reach * reach) * (0.3 + 0.7 * occlusion);
    }
    colour += palette(0.9) * pow(1.0 - max(dot(normal, -ray), 0.0), 3.0) * (0.15 + 0.5 * uEnergy);
    colour = fogged(colour, far, travelled, 0.18);
    return half4(toneMap(colour + flare), 1.0);
}
"""
    }
}

/**
 * The Mandelbox: a cube folded into itself over and over, which grows rooms, bridges and towers at every
 * scale. The camera circles it and closes in over each phrase, then cuts back out, and the fold scale
 * walks to a new value every phrase so the structure itself changes. Two lights circle it, a halo of
 * sparks rushes at the camera on the kick, stars sweep past, and a drop cuts straight back out.
 *
 * Each fold mirrors anything outside a box back inside it and pushes anything near the middle out
 * through a sphere. Eight rounds of those two folds build the whole structure.
 */
internal class Mandelbox : ShaderPreset(
    source = SOURCE,
    name = "Mandelbox",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.High,
    seed = 37f,
) {
    private val scaleGene = genes.number("Scale", -2.1f, -1.6f, -2.1f)
    private val depth = genes.number("Flight depth", 5.5f, 7.5f, 6.5f)
    private val lightCount = genes.toggle("Second light", start = true)
    private val haloRate = genes.number("Halo", 0.5f, 1.8f, 1f)

    private val orbit = MusicClock(beatsPerCycle = 6f)
    private var look = 0f
    private var dive = 0f
    private var cut = 0f
    private val halo = Envelope(attackPerSecond = 30f, releasePerSecond = 2.5f)
    private var lightTurn = 0f
    private val sparks = Sprites(300, 1_037L)
    private var sparkCredit = 0f
    private val streaks = Sprites(80, 2_037L)
    private var streakCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        // In over each phrase, and straight back out at the next one, or at once on a drop.
        if (gestures.drop) cut = gestures.barSeconds
        cut -= dt
        dive = if (cut > 0f) 0f else gestures.phrasePhase
        if (gestures.phrase) scaleGene.target = -2.1f + 0.5f * random.next()
        halo.hit(gestures.kickHit)
        halo.advance(0f, dt)
        lightTurn += dt * (0.8f + 0.8f * state.frame.motionRate)
        look += dt * TAU / 16f
        sparkCredit += (dt * (30f + 70f * state.drive) + gestures.kickHit * 0.35f) * haloRate.value * 3f
        while (sparkCredit >= 1f) {
            sparkCredit -= 1f
            sparks.burst(0.5f, 0.45f, 1, 0.4f + 0.5f * state.drive, 1f, 0.012f, random.next(), Sprite.STREAK)
        }
        sparks.advance(dt, drag = -0.6f)
        streakCredit += dt * (1.5f + 3f * state.drive)
        while (streakCredit >= 1f) {
            streakCredit -= 1f
            streaks.burst(random.next(), random.next() * 0.4f, 1, 0.8f, 0.6f, 0.008f, 0.6f, Sprite.STREAK, if (random.next() < 0.5f) 0.2f else 2.9f, 0.1f)
        }
        streaks.advance(dt, drag = 0f)
        kit.place(1, 0.5f + 0.3f * cos(lightTurn), 0.45f + 0.2f * sin(lightTurn))
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val frame = state.frame
        val turn = orbit.advance(state.deltaSeconds, frame.bpm, frame.beatConfidence, frame.phrasePhase, 0.04f + state.paced(0.06f))
        program.uniform("uOrbit", turn * TAU)
        program.uniform("uLook", look)
        program.uniform("uDistance", 7f + (depth.value - 7f) * dive)
        program.uniform("uScale", scaleGene.value)
        program.uniform("uHalo", halo.value)
        program.uniform("uLightTurn", lightTurn)
        program.uniform("uLights", lightCount.weight(1))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(streaks) { drawSprites(state.palette, genes.walk, alpha = 0.5f, saturation = 0.2f) }
        with(sparks) { drawSprites(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.5f + 0.5f * state.lift) }
    }

    override fun onReset() {
        orbit.reset()
        look = 0f
        dive = 0f
        cut = 0f
        halo.reset()
        lightTurn = 0f
        sparks.clear()
        sparkCredit = 0f
        streaks.clear()
        streakCredit = 0f
        comets.clear()
    }

    private companion object {
        const val SOURCE = """
uniform float uOrbit;
uniform float uLook;
uniform float uDistance;
uniform float uScale;
uniform float uHalo;
uniform float uLightTurn;
uniform float uLights;

float mandelbox(float3 point, float scale) {
    float3 z = point;
    float grow = 1.0;
    for (int fold = 0; fold < 8; fold++) {
        // Anything outside the unit box is mirrored back in.
        z = clamp(z, -1.0, 1.0) * 2.0 - z;
        // Close to the middle it is pushed out, and a shell round that is turned inside out.
        float reach = dot(z, z);
        if (reach < 0.25) {
            z *= 4.0;
            grow *= 4.0;
        } else if (reach < 1.0) {
            z /= reach;
            grow /= reach;
        }
        z = z * scale + point;
        grow = grow * abs(scale) + 1.0;
    }
    return length(z) / abs(grow);
}

// The sky, with stars fixed in the world so they sweep across as the camera circles.
float3 skyFor(float3 ray) {
    float3 colour = mix(palette(0.02) * 0.25, palette(0.4) * 0.35, clamp(ray.y * 0.5 + 0.5, 0.0, 1.0));
    float around = atan(ray.z, ray.x);
    colour += palette(1.0) * pow(hash21(floor(float2(around * 40.0, ray.y * 40.0)) + uSeed), 16.0) * (0.6 + uTreble);
    colour += paletteCycled(ray.x * 0.3 + uWalk) * fbm(float2(around * 2.0, ray.y * 3.0)) * 0.12;
    return colour;
}

half4 main(float2 position) {
    float2 uv = view(position);
    float scale = uScale - 0.2 * uBass;
    float3 eye = float3(sin(uOrbit) * uDistance, 0.5 + uDistance * 0.2 + 1.2 * sin(uOrbit * 0.5), cos(uOrbit) * uDistance);
    // The camera looks round the box rather than always at its middle.
    float3 forward = normalize(float3(2.4 * sin(uLook), 0.3 + 1.2 * cos(uLook * 0.8), 0.0) - eye);
    float3 right = normalize(cross(forward, float3(0.0, 1.0, 0.0)));
    float3 upward = cross(right, forward);
    float3 ray = normalize(forward * 2.5 + right * uv.x + upward * uv.y);

    float travelled = 0.0;
    float hit = 0.0;
    float skim = 0.0;
    float last = 1.0;
    for (int march = 0; march < 80; march++) {
        float distance = mandelbox(eye + ray * travelled, scale);
        last = distance;
        skim += exp(-distance * 8.0);
        if (distance < 0.001 * travelled + 0.0005) {
            hit = 1.0;
            break;
        }
        travelled += distance;
        if (travelled > uDistance + 17.0) break;
    }
    // A ray that ran out of steps right against the surface counts as a hit, or deep folds show specks.
    if (hit < 0.5 && last < 0.02) hit = 1.0;

    float3 sky = skyFor(ray);
    float3 halo = palette(0.85) * skim * 0.012 * (0.3 + uEnergy + 1.2 * uHalo);
    if (hit < 0.5) return half4(toneMap(sky + halo), 1.0);

    float3 at = eye + ray * travelled;
    float2 nudge = float2(1.0, -1.0) * 0.0015;
    float3 normal = normalize(
        nudge.xyy * mandelbox(at + nudge.xyy, scale) +
        nudge.yyx * mandelbox(at + nudge.yyx, scale) +
        nudge.yxy * mandelbox(at + nudge.yxy, scale) +
        nudge.xxx * mandelbox(at + nudge.xxx, scale)
    );
    float occlusion = clamp(mandelbox(at + normal * 0.08, scale) / 0.08, 0.0, 1.0);
    float fill = max(dot(normal, -ray), 0.0);
    float strength = bandFolded(fract(length(at) * 0.18 + uPhrasePhase));
    // Two lights circling the box, the second one turning the other way.
    float3 lightA = float3(cos(uLightTurn) * 7.0, 4.0, sin(uLightTurn) * 7.0);
    float3 lightB = float3(cos(2.0 - uLightTurn * 1.3) * 6.0, -2.0, sin(2.0 - uLightTurn * 1.3) * 6.0);
    float litA = max(dot(normal, normalize(lightA - at)), 0.0);
    float litB = max(dot(normal, normalize(lightB - at)), 0.0) * uLights;

    float3 stone = paletteCycled(length(at) * 0.1 + uKeyHue * 0.2 + uWalk);
    float3 colour = stone * (0.1 + (0.45 + 0.7 * uDrive) * litA + 0.3 * fill) * (0.25 + 0.75 * occlusion);
    colour += paletteCycled(0.5 + uWalk) * litB * 0.8 * (0.25 + 0.75 * occlusion);
    colour += palette(0.95) * strength * 0.35 * (1.0 - occlusion) * (0.4 + uEnergy);
    // A rim of light where the surface turns away, which draws every fold as a bright line.
    colour += paletteCycled(0.3 + uWalk) * pow(1.0 - fill, 3.0) * (0.15 + 0.6 * uDrive);
    colour = fogged(colour, sky, travelled, 0.06);
    return half4(toneMap(colour + halo), 1.0);
}
"""
    }
}

/**
 * Flying low over hills and water at dusk, on a path that swings wide and banks into its turns. Clouds
 * scroll overhead, a flock of thirty to fifty birds crosses the sky and turns back on the snare, rain falls
 * in loud stretches, and a kick sends a river of light along the valley floors. The sun swings along
 * the horizon once a phrase, and a drop brings the dawn.
 *
 * The ground is noise added to itself at smaller and smaller sizes, and each pixel's ray steps forward
 * until it drops below the ground.
 */
internal class TerrainMarch : ShaderPreset(
    source = SOURCE,
    name = "Terrain March",
    family = VizFamily.Raymarch,
    bucket = VizEnergy.Calm,
    seed = 31f,
) {
    private val curve = genes.number("Path curve", 0.5f, 1.5f, 1f)
    private val clouds = genes.number("Clouds", 0.2f, 0.8f, 0.5f)
    private val flockSize = genes.choice("Flock", 3, start = 2)
    private val rainGene = genes.toggle("Rain", start = true)

    private val flight = Flight(speed = 8f)
    private var travelled = 0f
    private val sun = MusicClock(beatsPerCycle = 16f)
    private val lift = Slew(maxPerSecond = 0.25f)
    private var cloudScroll = 0f
    private val river = Envelope(attackPerSecond = 20f, releasePerSecond = 1.5f)
    private var dawnHold = 0f
    private val dawn = Envelope(attackPerSecond = 4f, releasePerSecond = 0.5f)
    private val flock = Swarm(50, 3_031L)
    private var flockTravel = 0f
    private var flockWay = 1f
    private val birdMesh = TriangleMesh(maxVertices = 50 * 8 + 8)
    private val rain = Sprites(300, 4_031L)
    private var rainCredit = 0f
    private val comets = Comets(size = 0.02f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        travelled = flight.advance(state)
        cloudScroll += dt * (0.15f + 0.1f * state.drive)
        river.hit(gestures.kickHit)
        river.advance(0f, dt)
        if (gestures.drop) dawnHold = gestures.barSeconds * 2f
        dawnHold -= dt
        dawn.advance(if (dawnHold > 0f) 1f else 0f, dt)
        // The flock crosses the sky, turning back on the snare.
        if (gestures.snareHit > 0f) flockWay = -flockWay
        flockTravel += dt / (gestures.barSeconds * 2f) * flockWay
        flock.targetX = -0.1f + 1.2f * wrap(flockTravel)
        flock.targetY = 0.18f + 0.06f * sin(flockTravel * TAU * 2f)
        flock.advance(dt, speed = 0.45f + 0.4f * state.drive)
        val birds = flockSize.count(30, 10)
        var sumX = 0f
        var sumY = 0f
        for (index in 0 until birds) {
            sumX += flock.x[index]
            sumY += flock.y[index]
        }
        kit.place(1, sumX / birds, sumY / birds)
        // Rain only in the loud stretches.
        rainCredit += dt * 300f * rainGene.weight(1) * (state.frame.loudLong * 1.6f - 0.6f).coerceAtLeast(0f)
        while (rainCredit >= 1f) {
            rainCredit -= 1f
            rain.burst(random.next() * 1.1f - 0.05f, -0.02f, 1, 1.3f, 0.8f, 0.009f, 0.55f, Sprite.STREAK, 1.75f, 0.05f)
        }
        rain.advance(dt, drag = 0f)
        comets.advance(state, gestures, random)
        kit.follow(0, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val frame = state.frame
        program.uniform("uFlight", travelled)
        val turn = sun.advance(state.deltaSeconds, frame.bpm, frame.beatConfidence, frame.phrasePhase, 0.01f + state.paced(0.03f))
        program.uniform("uSun", turn * TAU)
        program.uniform("uLift", lift.advance(frame.loudLong, state.deltaSeconds))
        program.uniform("uCurve", curve.value)
        program.uniform("uClouds", clouds.value, cloudScroll)
        program.uniform("uRiver", river.value)
        program.uniform("uDawn", dawn.value)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        birdMesh.clear()
        val colour = state.palette.argb(genes.walk + 0.1f, saturation = 0.2f, value = 0.15f, alpha = 0.9f)
        for (index in 0 until flockSize.drawn(30, 10)) {
            val presence = flockSize.presence(index, 30, 10)
            if (presence <= 0.01f) continue
            val heading = kotlin.math.atan2(flock.vy[index], flock.vx[index])
            birdMesh.glyph(2, flock.x[index] * size.width, flock.y[index] * size.height, size.minDimension * 0.014f, heading + 1.5707964f, colour)
        }
        drawMesh(birdMesh)
        with(rain) { drawSprites(state.palette, genes.walk, alpha = 0.3f + 0.4f * state.lift, saturation = 0.2f) }
        with(comets) { drawComets(state.palette, genes.walk, alpha = 0.4f + 0.5f * state.lift) }
    }

    override fun onReset() {
        flight.reset()
        travelled = 0f
        sun.reset()
        lift.reset()
        cloudScroll = 0f
        river.reset()
        dawnHold = 0f
        dawn.reset()
        flock.scatter()
        flockTravel = 0f
        flockWay = 1f
        rain.clear()
        rainCredit = 0f
        comets.clear()
    }

    private companion object {
        const val SOURCE = """
uniform float uFlight;
uniform float uSun;
uniform float uLift;
uniform float uCurve;
uniform float2 uClouds;
uniform float uRiver;
uniform float uDawn;

float ground(float2 p) {
    float height = 0.0;
    float weight = 0.5;
    for (int octave = 0; octave < 4; octave++) {
        height += weight * noise2(p);
        p = rotate(p, 0.8) * 2.03;
        weight *= 0.5;
    }
    return height * 3.2 - 1.2;
}

// The first two layers only, so the camera rides the shape of the hills without shaking over every
// small bump. The constant is what the missing layers add on average.
float groundBroad(float2 p) {
    float height = 0.5 * noise2(p) + 0.25 * noise2(rotate(p, 0.8) * 2.03);
    return height * 3.2 - 1.2 + 0.3;
}

float3 skyColour(float3 ray, float3 sun, float3 eye) {
    float up = clamp(ray.y * 2.5, 0.0, 1.0);
    float3 colour = mix(palette(0.55) * 0.55, palette(0.1) * 0.25, up);
    float toward = max(dot(ray, sun), 0.0);
    colour += palette(0.95) * (pow(toward, 400.0) * 3.0 + pow(toward, 12.0) * 0.35);
    // Clouds: noise on a sheet high above, scrolling, lit on the side that faces the sun.
    if (ray.y > 0.02) {
        float2 sheet = (eye.xz + ray.xz * (6.0 - eye.y) / ray.y) * 0.05 + float2(uClouds.y, uClouds.y * 0.4);
        float cloud = smoothstep(1.0 - uClouds.x, 1.2 - uClouds.x * 0.6, fbm(sheet * 3.0) * 1.6);
        float3 lit = mix(palette(0.3) * 0.5, palette(0.9) * 0.9, pow(toward, 4.0));
        colour = mix(colour, lit, cloud * clamp(ray.y * 6.0, 0.0, 1.0) * 0.8);
    }
    return colour;
}

half4 main(float2 position) {
    float2 uv = view(position);
    float water = -0.35 + 0.6 * uLevel;
    // The path swings wide, the camera banks into it and looks along the curve.
    float2 overhead = float2(sin(uFlight * 0.04) * 12.0 * uCurve, uFlight);
    float below = max(groundBroad(overhead * 0.22), water);
    float3 eye = float3(overhead.x, below + 1.0 + 1.2 * uLift, overhead.y);
    float3 ray = normalize(float3(uv.x, uv.y - 0.28, 1.6));
    ray.xy = rotate(ray.xy, -cos(uFlight * 0.04) * 0.36 * uCurve);
    ray.xz = rotate(ray.xz, -atan(0.48 * uCurve * cos(uFlight * 0.04)));
    float3 sun = normalize(float3(sin(uSun) * 0.9 * (1.0 - uDawn), mix(0.12 + 0.1 * cos(uSun), 0.015, uDawn), 0.8));

    float travelled = 0.05;
    float hit = 0.0;
    for (int march = 0; march < 64; march++) {
        float3 at = eye + ray * travelled;
        float gap = at.y - max(ground(at.xz * 0.22), water);
        if (gap < 0.003 * travelled) {
            hit = 1.0;
            break;
        }
        travelled += max(gap * 0.5, 0.02 * travelled);
        if (travelled > 70.0) break;
    }

    float3 sky = skyColour(ray, sun, eye);
    if (hit < 0.5) return half4(toneMap(sky), 1.0);

    float3 at = eye + ray * travelled;
    float2 p = at.xz * 0.22;
    float3 colour;
    if (ground(p) < water) {
        // Water: the sky mirrored, broken up by ripples that a kick sends across it.
        float2 ripple = float2(noise2(at.xz * 2.0 + uMusicTime * 0.4), noise2(at.xz * 2.0 - uMusicTime * 0.3)) - 0.5;
        float roughness = 0.15 + 0.6 * uKick;
        float3 normal = normalize(float3(ripple.x * roughness, 1.0, ripple.y * roughness));
        colour = skyColour(reflect(ray, normal), sun, eye) * 0.7 + palette(0.2) * 0.08;
    } else {
        float2 nudge = float2(0.02, 0.0);
        float3 normal = normalize(float3(
            ground(p - nudge.xy) - ground(p + nudge.xy),
            2.0 * nudge.x / 0.22,
            ground(p - nudge.yx) - ground(p + nudge.yx)
        ));
        float lit = max(dot(normal, sun), 0.0);
        float slope = 1.0 - normal.y;
        float3 grass = mix(palette(0.25), palette(0.6), clamp((at.y - water) * 0.5, 0.0, 1.0));
        float3 surface = mix(grass, palette(0.12), clamp(slope * 2.5, 0.0, 1.0));
        colour = surface * (0.12 + 0.9 * lit) + skyColour(normal, sun, eye) * 0.1;
        // The highest ground catches light from the music, the way snow catches the moon.
        colour += palette(1.0) * smoothstep(1.2, 1.8, at.y) * (0.2 + 0.6 * uEnergy);
        // A river of light along the valley floors on the kick.
        colour += palette(0.9) * (1.0 - smoothstep(water, water + 0.35, at.y)) * uRiver * 1.2;
    }
    colour = fogged(colour, sky, travelled, 0.035) + palette(0.95) * uDawn * 0.08;
    return half4(toneMap(colour), 1.0);
}
"""
    }
}

/**
 * Sixteen blobs of light in three sizes on paths that span the screen, one lap every two bars, over
 * water with light drifting across it. They weld into each other with a soft join, and the weld changes
 * every phrase, from separate drops to one mass. A kick pulls them together, bubbles rise off them on
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
        phase += dt * TAU / (gestures.barSeconds * 2f) * (0.6f + 0.6f * state.drive)
        ripple += dt * (0.5f + 0.8f * state.frame.motionRate)
        if (gestures.phrase) weld.target = if (weld.value > 0.25f) 0.05f + 0.1f * random.next() else 0.35f + 0.15f * random.next()
        merge.kick(gestures.kickHit * 3f)
        merge.advance(dt)
        if (gestures.drop) gather = 1f
        gather = (gather - dt / gestures.barSeconds).coerceAtLeast(0f)
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
        bubbleCredit += dt * (3f + 10f * state.drive) * bubbleRate.value + gestures.hatHit * 3f
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
        val step = dt / (gestures.barSeconds * 3f)
        for (option in 0 until 4) {
            val share = direction.weight(option)
            driftX += share * DRIFT_X[option] * step
            driftY += share * DRIFT_Y[option] * step
        }
        flow += dt * (0.7f + 0.6f * state.frame.motionRate)
        core.advance(state, gestures)
        val eight = corePath.weight(1)
        val figureX = 0.5f + 0.32f * sin(core.angle)
        val figureY = 0.5f + 0.2f * sin(2f * core.angle)
        if (gestures.drop) toCentre = 1f
        toCentre = (toCentre - dt / gestures.barSeconds).coerceAtLeast(0f)
        val x = core.x + (figureX - core.x) * eight
        val y = core.y + (figureY - core.y) * eight
        coreX = x + (0.5f - x) * toCentre
        coreY = y + (0.5f - y) * toCentre
        kit.place(0, coreX, coreY)
        flare.kick(gestures.kickHit * 6f)
        flare.advance(dt)
        if (gestures.phrase) lanes.target = 0.6f + 1.2f * random.next()
        if (gestures.snareHit > 0f) snareComets.across(random, gestures.beatSeconds * 3f, PathShape.Arc, 0.15f, 0.02f, random.next(), 0f, Sprite.GLOW)
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
        sway += dt * (0.8f + 0.8f * state.frame.motionRate)
        for (layer in 0 until 4) scroll[layer] += 2f * dt / (gestures.barSeconds * BARS_PER_SCREEN[layer])
        lean += dt * TAU / 16f
        moonOrbit.advance(state, gestures)
        if (gestures.kickHit > 0f) wave = 0f
        if (wave >= 0f) {
            wave += dt / (gestures.beatSeconds * 2f)
            if (wave > 1.2f) wave = -1f
        }
        if (gestures.drop) dropHold = gestures.barSeconds
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
