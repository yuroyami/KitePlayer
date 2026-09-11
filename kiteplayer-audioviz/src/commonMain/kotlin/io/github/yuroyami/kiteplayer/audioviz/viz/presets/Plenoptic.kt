package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Sprites
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Travellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.drawTravellers
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.sprite
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.polar
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.sceneRadius
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rings pushed outward across water by the drums, from sources that move: along the spectrum and down
 * with time, at random, or round an orbit. The floor under the water is the spectrum's own recent past,
 * glowing figures drift across the surface, droplets fall in and start small rings, and the light that
 * makes the glints moves. A drop sends one wave front across the whole screen.
 */
internal class Ripple : ShaderPreset(
    source = SOURCE,
    name = "Ripple",
    family = VizFamily.Plenoptic,
    bucket = VizEnergy.Mid,
    seed = 17f,
) {
    override val hasFallback: Boolean get() = true

    private val placement = genes.choice("Sources", 3, start = 2)
    private val stage = Stage(reachX = 0.22f, reachY = 0.16f, start = 0.8f)
    private val figures = genes.choice("Figures", 3, start = 2)
    private val floorGene = genes.toggle("Spectrum floor", start = true)
    private val lightJumps = genes.toggle("Light jumps", start = false)

    private val along = FloatArray(RINGS)
    private val strength = FloatArray(RINGS)
    private val tint = FloatArray(RINGS)
    private val weight = FloatArray(RINGS)
    private val ringX = FloatArray(RINGS) { 0.5f }
    private val ringY = FloatArray(RINGS) { 0.5f }
    private val ringReach = FloatArray(RINGS) { 1f }
    private val packed = FloatArray(RINGS * 4)
    private val places = FloatArray(RINGS * 4)
    private var next = 0
    private val source = Orbiter(radiusX = 0.3f, radiusY = 0.26f, lapsPerBar = 0.5f)
    private var lightGoal = 0f
    private val light = Slew(maxPerSecond = 3f)
    private var drift = 0f
    private var flow = 0f
    private val figureX = FloatArray(3) { 0.5f }
    private val figureY = FloatArray(3) { 0.5f }
    private val drops = Travellers(16)
    private val flying = BooleanArray(16)
    private val dropMesh = TriangleMesh(maxVertices = 16 * 12 + 8)
    private val shape = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        source.centreX = stage.x
        source.centreY = stage.y
        source.advance(state, gestures)
        flow += dt * (1.6f + 1.6f * state.frame.motionRate)
        drift += dt * TAU / (gestures.barSeconds * 1.8f) * state.tempo
        for (index in 0 until 3) {
            figureX[index] = stage.x + 0.36f * sin(drift * FX[index] + index * 2.1f)
            figureY[index] = stage.y + 0.3f * sin(drift * FY[index] + index * 1.3f)
            kit.place(index, figureX[index], figureY[index])
        }
        if (lightJumps.on) {
            if (gestures.bar) lightGoal += 1.3f
        } else {
            lightGoal += dt * 0.5f * state.tempo
        }
        light.advance(lightGoal, dt)
        // Rings on every onset, weak ones faint, from wherever the sources are now.
        if (state.frame.kick > 0f) born(state.frame.kick, 1f, state.bassMotion * 0.4f, sourceX(state), sourceY(), 1f)
        if (state.frame.snare > 0f) born(state.frame.snare, 0.5f, 0.55f + 0.35f * state.air, sourceX(state), sourceY(), 1f)
        if (gestures.drop) born(1f, 2f, 0.2f, -0.6f, 0.5f, 4f)
        for (slot in 0 until RINGS) {
            if (strength[slot] <= 0f) continue
            along[slot] += dt / gestures.barSeconds
            if (along[slot] >= 1f) strength[slot] = 0f
        }
        // Droplets fall in from the front and start small rings where they land.
        if (gestures.hatHit > 0f || (state.frame.snare > 0f && random.next() < 0.25f)) {
            val x = 0.1f + 0.8f * random.next()
            drops.spawn(x, -0.05f, x + random.signed() * 0.05f, 0.2f + 0.7f * random.next(), 0.5f, size = 0.018f, tint = random.next(), kind = Sprite.GLOW)
        }
        for (slot in 0 until drops.capacity) flying[slot] = drops.alive[slot]
        drops.advance(dt)
        for (slot in 0 until drops.capacity) {
            if (flying[slot] && !drops.alive[slot]) born(0.4f, 0.3f, drops.tint[slot], drops.toX[slot], drops.toY[slot], 0.35f)
        }
        kit.follow(3, drops)
    }

    private fun sourceX(state: VizRenderState): Float = when (placement.value) {
        0 -> {
            val bands = state.frame.bandsRel
            var loudest = 0
            for (index in bands.indices) if (bands[index] > bands[loudest]) loudest = index
            0.2f + 0.6f * loudest / (bands.size - 1).coerceAtLeast(1)
        }
        1 -> 0.15f + 0.7f * random.next()
        else -> source.x
    }

    // Down the screen with time over two bars, at random, or round the orbit.
    private fun sourceY(): Float = when (placement.value) {
        0 -> 0.2f + 0.6f * ((gestures.bars % 2) + gestures.barPhase) / 2f
        1 -> 0.15f + 0.7f * random.next()
        else -> source.y
    }

    private fun born(hit: Float, thickness: Float, colour: Float, x: Float, y: Float, reach: Float) {
        along[next] = 0.05f
        strength[next] = 0.08f + 0.92f * hit
        tint[next] = colour
        weight[next] = thickness
        ringX[next] = x
        ringY[next] = y
        ringReach[next] = reach
        next = (next + 1) % RINGS
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        val aspect = kit.aspect
        for (slot in 0 until RINGS) {
            packed[slot * 4] = along[slot]
            packed[slot * 4 + 1] = strength[slot]
            packed[slot * 4 + 2] = tint[slot]
            packed[slot * 4 + 3] = weight[slot]
            places[slot * 4] = (ringX[slot] - 0.5f) * 2f * aspect
            places[slot * 4 + 1] = (ringY[slot] - 0.5f) * 2f
            places[slot * 4 + 2] = ringReach[slot]
            places[slot * 4 + 3] = 0f
        }
        program.uniforms("uRings", packed)
        program.uniforms("uRingsAt", places)
        program.uniform("uTiles", floorGene.weight(0), floorGene.weight(1))
        program.uniform("uLightAngle", light.value)
        program.uniform("uFlow", flow)
    }

    override fun DrawScope.drawFallback(state: VizRenderState) {
        val reach = sceneRadius * 0.9f
        for (slot in 0 until RINGS) {
            if (strength[slot] <= 0f) continue
            val out = along[slot]
            drawCircle(
                color = state.palette.cycled(tint[slot], alpha = strength[slot] * (1f - out)),
                radius = reach * out * ringReach[slot],
                center = Offset(ringX[slot] * size.width, ringY[slot] * size.height),
                style = Stroke(((1f - out) * size.minDimension * 0.024f * weight[slot]).coerceAtLeast(1.4f)),
            )
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val bands = state.frame.bandsRel
        val lift = 0.4f + 0.6f * state.lift
        for (index in 0 until figures.drawn(1)) {
            val presence = figures.presence(index, 1)
            if (presence <= 0.01f) continue
            val at = Offset(figureX[index] * size.width, figureY[index] * size.height)
            val reach = size.minDimension * (0.08f + 0.06f * split.level(index % 3))
            shape.reset()
            for (point in 0..16) {
                val value = if (bands.isEmpty()) 0f else bands.foldedAt(point / 16f)
                val p = polar(at, TAU * point / 16f + drift * 2f + index, reach * (0.5f + 0.8f * value))
                if (point == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
            }
            shape.close()
            val colour = state.palette.cycled(index * 0.3f + genes.walk, value = 1f)
            drawPath(shape, colour.copy(alpha = (0.25f * presence * lift).coerceIn(0f, 1f)))
            drawPath(shape, colour.copy(alpha = (0.85f * presence).coerceIn(0f, 1f)), style = Stroke((size.minDimension * 0.006f).coerceAtLeast(1.5f)))
        }
        drawTravellers(drops, dropMesh, state.palette, genes.walk)
    }

    override fun onReset() {
        strength.fill(0f)
        next = 0
        stage.reset()
        source.reset()
        lightGoal = 0f
        light.reset()
        drift = 0f
        flow = 0f
        drops.clear()
        flying.fill(false)
    }

    private companion object {
        const val RINGS = 18
        val FX = floatArrayOf(1f, 1.4f, 0.75f)
        val FY = floatArrayOf(1.3f, 0.8f, 1.6f)

        const val SOURCE = """
uniform float4 uRings[18];
uniform float4 uRingsAt[18];
uniform float2 uTiles;
uniform float uLightAngle;
uniform float uFlow;

// Slope and colour in one pass over the rings. Each ring has its own centre and reach.
float4 water(float2 uv, float reach) {
    float2 gradient = float2(0.0);
    float crest = 0.0;
    float tint = 0.0;
    for (int index = 0; index < 18; index++) {
        float4 ring = uRings[index];
        if (ring.y <= 0.0) continue;
        float4 at = uRingsAt[index];
        float2 d = uv - at.xy;
        float away = length(d);
        float width = 0.03 + 0.05 * ring.w;
        float gap = (away - ring.x * reach * at.z) / width;
        float bell = ring.y * (1.0 - ring.x) * exp(-gap * gap);
        float slope = bell * (-2.0 * gap * cos(gap * 2.2) - 2.2 * sin(gap * 2.2)) / width;
        gradient += d / max(away, 0.001) * slope;
        crest += bell;
        tint += bell * ring.z;
    }
    return float4(gradient, crest, tint / max(crest, 0.001));
}

half4 main(float2 position) {
    float2 uv = centred(camPoint(position));
    float reach = 0.62 * min(uResolution.x, uResolution.y) / (uResolution.y * 0.5);
    float4 surface = water(uv, reach);
    // A swell that always travels, so the glints move over the whole surface between the rings.
    float2 swell = float2(cos(uv.y * 5.0 + uFlow), sin(uv.x * 4.0 - uFlow * 0.8)) * 0.35
        + float2(sin((uv.x + uv.y) * 7.0 + uFlow * 1.3), cos((uv.x - uv.y) * 6.0 - uFlow)) * 0.2;
    float3 normal = normalize(float3(-(surface.xy * 0.06 + swell * 1.1), 1.0));

    // The floor under the water, bent by the waves: tiles, or the spectrum's recent past.
    float2 seen = uv + normal.xy * 0.25;
    float tiles = pow(0.5 + 0.5 * sin(seen.x * 11.0 + sin(seen.y * 8.0 + uMusicTime * 0.3)), 4.0);
    float past = history(fract(seen.x * 0.25 + 0.5), fract(0.5 - seen.y * 0.25));
    float lit = tiles * uTiles.x + past * 1.6 * uTiles.y;
    float3 below = palette(0.0) * 0.18 + palette(0.3) * 0.16 * lit * (0.4 + 0.6 * uEnergy);

    float3 light = normalize(float3(cos(uLightAngle) * 0.7, sin(uLightAngle) * 0.7, 0.7));
    float glint = pow(max(dot(reflect(-light, normal), float3(0.0, 0.0, 1.0)), 0.0), 20.0);
    float3 colour = below + paletteCycled(surface.w + uWalk) * surface.z * 0.65 + palette(1.0) * glint * (0.35 + 0.65 * uEnergy);
    return half4(toneMap(colour), 1.0);
}
"""
    }
}

/**
 * One dot per band on its own orbit round a centre that wanders the screen, inner orbits faster and the
 * outer ones wider than the screen, so their dots leave and come back. Dots leave comet tails, sparks
 * fly when neighbours line up, a kick throws the orbits wide and a snare turns one dot round.
 */
internal class DanceOfTheFreq : Layered(
    name = "Dance of the Freq",
    family = VizFamily.Plenoptic,
    bucket = VizEnergy.Mid,
    kit = Kit(seed = 401L, groundKind = GroundKind.Rings, detailKind = DetailKind.Specks, camera = Camera2D(wander = 0.08f, seed = 401)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.84f, livelyTrail = 0.8f, calmSpin = 0.1f, livelySpin = 0.3f)

    private val systems = genes.toggle("Second system", start = true)
    private val spacing = genes.number("Orbit spacing", 0.8f, 1.5f, 1.15f)
    private val dotKind = genes.choice("Dot kind", 3, start = 1)
    private val tail = genes.number("Tail", -0.1f, 0.08f, 0f)

    private val stage = Stage(reachX = 0.24f, reachY = 0.18f, start = 0.4f)
    private val flip = FloatArray(MOST) { 1f }
    private val angles = FloatArray(MOST) { it * 0.7f }
    private val expand = Spring(stiffness = 70f, damping = 0.55f)
    private var fling = 0f
    private var sparkWait = 0f
    private val sparks = Sprites(260, 1_401L)
    private val comets = Comets()
    private val mesh = TriangleMesh(maxVertices = MOST * 4 * 14 + 16)

    override fun trailAt(mood: Float): Float = (super.trailAt(mood) + tail.value).coerceIn(0f, 0.97f)

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        stage.advance(dt)
        expand.kick(gestures.kickHit * 3f)
        expand.advance(dt)
        if (gestures.drop) fling = 1f
        fling = (fling - dt / gestures.barSeconds).coerceAtLeast(0f)
        val count = state.frame.bandsRel.size.coerceAtMost(MOST)
        if (gestures.snareHit > 0f && count > 0) {
            val dot = (random.next() * count).toInt().coerceIn(0, count - 1)
            flip[dot] = -flip[dot]
        }
        for (index in 0 until count) {
            val along = (index + 1f) / count
            // Inner rings run faster, the way a planet close to its star does.
            angles[index] += dt * 1f * state.tempo * (1.6f / along) * flip[index]
        }
        val aspect = kit.aspect
        val height = 0.5f * sqrt(aspect * aspect + 1f)
        kit.place(1, stage.x, stage.y)
        if (count > 0) {
            val index = (count * 0.6f).toInt().coerceIn(0, count - 1)
            val reach = radius(index, count) * height
            kit.place(0, stage.x + cos(angles[index]) * reach / aspect, stage.y + sin(angles[index]) * reach)
        }
        // Sparks where two neighbours line up.
        sparkWait -= dt
        if (sparkWait <= 0f) {
            for (index in 0 until count - 1) {
                var gap = (angles[index] - angles[index + 1]) % TAU
                if (gap < 0f) gap += TAU
                if (gap < 0.04f || gap > TAU - 0.04f) {
                    val reach = radius(index, count) * height
                    sparks.burst(stage.x + cos(angles[index]) * reach / aspect, stage.y + sin(angles[index]) * reach, 6, 0.3f, 0.5f, 0.012f, index.toFloat() / count, Sprite.SPARK)
                    sparkWait = 0.1f
                    break
                }
            }
        }
        sparks.advance(dt, drag = 1f)
        comets.advance(state, gestures, random)
        kit.follow(2, comets.travellers)
    }

    /** How far out dot [index] circles, in scene radii: a kick throws them wide and a drop flings them out and back. */
    private fun radius(index: Int, count: Int): Float {
        val along = (index + 1f) / count
        return along * spacing.value * (1f + 0.25f * expand.value.coerceIn(-0.5f, 1.5f) + 0.8f * sin(fling * 3.1415927f))
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        val count = bands.size.coerceAtMost(MOST)
        if (count == 0) return
        mesh.clear()
        val lift = 1.3f * state.lift
        val kind = when (dotKind.value) {
            0 -> Sprite.GLOW
            1 -> Sprite.DIAMOND
            else -> Sprite.HEX
        }
        val second = systems.weight(1)
        for (system in 0 until 2) {
            val share = if (system == 0) 1f else second
            if (share <= 0.01f) continue
            val cx = (if (system == 0) stage.x else 1f - stage.x) * size.width
            val cy = (if (system == 0) stage.y else 1f - stage.y) * size.height
            for (index in 0 until count) {
                val value = bands[index]
                val reach = radius(index, count) * sceneRadius
                val angle = angles[index] + system * 3.1415927f
                val dot = size.minDimension * (0.018f + 0.036f * value) * (0.5f + 0.8f * state.lift)
                val colour = state.palette.argb((index + 1f) / count + genes.walk, value = 0.45f + 0.55f * value, alpha = (0.3f + 0.5f * value) * lift * share)
                mesh.sprite(kind, cx + cos(angle) * reach, cy + sin(angle) * reach, dot, angle, colour)
                // A small moon round every dot.
                mesh.sprite(kind, cx + cos(angle) * reach + cos(angle * 3f) * dot * 2.4f, cy + sin(angle) * reach + sin(angle * 3f) * dot * 2.4f, dot * 0.5f, -angle, colour)
            }
        }
        drawMesh(mesh, BlendMode.Plus)
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        with(sparks) { drawSprites(state.palette, genes.walk) }
    }

    override fun onReset() {
        stage.reset()
        flip.fill(1f)
        for (index in 0 until MOST) angles[index] = index * 0.7f
        expand.reset()
        fling = 0f
        sparkWait = 0f
        sparks.clear()
        comets.clear()
    }

    private companion object {
        const val MOST = 64
    }
}

/**
 * Ribbons on up to three depths filling the whole height, twisting and travelling across it one screen
 * every two bars, braiding where they cross. Beads slide along them, a kick sends a pulse down every
 * ribbon, and a drop snaps them straight for a moment before they curl back.
 */
internal class Strands : Layered(
    name = "Strands",
    family = VizFamily.Plenoptic,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 402L, groundKind = GroundKind.Fog, detailKind = DetailKind.Hatch, detailStrength = 0.7f, camera = Camera2D(wander = 0.06f, seed = 402)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.5f, livelyTrail = 0.5f)

    private val ribbons = genes.choice("Ribbons", 3, start = 2)
    private val depths = genes.choice("Depths", 3, start = 2)
    private val twist = genes.number("Twist", 0.12f, 0.4f, 0.26f)
    private val beadRate = genes.choice("Beads", 3, start = 1)

    private var travel = 0f
    private var flow = 0f
    private val rise = Slew(maxPerSecond = 0.05f)
    private val pulseAt = FloatArray(PULSES) { -1f }
    private var nextPulse = 0
    private var straight = 0f
    private val beadRibbon = IntArray(BEADS)
    private val beadAlong = FloatArray(BEADS) { -1f }
    private var nextBead = 0
    private var leadRibbon = 0
    private var leadAlong = 0f
    private var lastBeat = -1
    private val beadMesh = TriangleMesh(maxVertices = (BEADS + 1) * 8 + 8)
    private val path = Path()

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        travel += dt / (gestures.barSeconds * 2f)
        flow += dt * 0.6f * state.tempo
        rise.advance((state.frame.loudLong - 0.5f) * 0.1f, dt)
        if (gestures.kickHit > 0f) {
            pulseAt[nextPulse] = 0f
            nextPulse = (nextPulse + 1) % PULSES
        }
        for (slot in 0 until PULSES) {
            if (pulseAt[slot] < 0f) continue
            pulseAt[slot] += dt / (gestures.beatSeconds * 2f)
            if (pulseAt[slot] > 1.1f) pulseAt[slot] = -1f
        }
        if (gestures.drop) straight = 1f
        straight = (straight - dt / gestures.barSeconds).coerceAtLeast(0f)
        val count = ribbons.count(12, 6)
        // Beads: every other ribbon, every ribbon, or two on every ribbon, each beat.
        val beat = (gestures.barPhase * 4f).toInt()
        if (beat != lastBeat) {
            lastBeat = beat
            val step = if (beadRate.value == 0) 2 else 1
            val repeats = if (beadRate.value == 2) 2 else 1
            for (ribbon in 0 until count step step) {
                repeat(repeats) {
                    beadRibbon[nextBead] = ribbon
                    beadAlong[nextBead] = -0.02f - 0.1f * it
                    nextBead = (nextBead + 1) % BEADS
                }
            }
        }
        for (slot in 0 until BEADS) {
            if (beadAlong[slot] < -0.5f) continue
            beadAlong[slot] += dt / (gestures.barSeconds * 2f)
            if (beadAlong[slot] > 1.02f) beadAlong[slot] = -1f
        }
        // One lead bead the anchors follow, relaunched as soon as it leaves the screen.
        leadAlong += dt / (gestures.barSeconds * 1.4f)
        if (leadAlong > 1.02f) {
            leadAlong = -0.02f
            leadRibbon = (leadRibbon + 5) % count
        }
        kit.place(0, leadAlong, ribbonY(leadRibbon.coerceAtMost(count - 1), leadAlong, count, state))
    }

    /** Where ribbon [index] of [count] crosses [x], as a share of the height. */
    private fun ribbonY(index: Int, x: Float, count: Int, state: VizRenderState): Float {
        val seat = (index + 0.5f) / count
        val bands = state.frame.bandsRel
        val energy = if (bands.isEmpty()) 0f else bands.sampleAt(x.coerceIn(0f, 1f))
        var bend = sin((x + travel) * TAU * 1.2f + index * 1.7f + flow * (0.5f + seat)) * twist.value * (0.6f + 0.6f * energy)
        for (slot in 0 until PULSES) {
            val at = pulseAt[slot]
            if (at < 0f) continue
            val distance = (x - at) / 0.06f
            bend += 0.05f * kotlin.math.exp(-distance * distance)
        }
        return seat + rise.value + bend * (1f - straight)
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val count = ribbons.count(12, 6)
        val layers = depths.count(1)
        val lift = 1.3f * state.lift
        // Far ribbons first, thinner and dimmer, so the near ones cross over them.
        for (layer in 0 until layers) {
            for (index in layer until ribbons.drawn(12, 6) step layers) {
                val presence = ribbons.presence(index, 12, 6)
                if (presence <= 0.01f) continue
                val near = (layer + 1f) / layers
                path.reset()
                for (step in 0..STEPS) {
                    val x = step.toFloat() / STEPS
                    val y = ribbonY(index, x, count, state) * size.height
                    if (step == 0) path.moveTo(0f, y) else path.lineTo(x * size.width, y)
                }
                drawPath(
                    path,
                    state.palette.cycled((index + 0.5f) / count + genes.walk, value = 0.5f + 0.5f * near, alpha = ((0.15f + 0.6f * near) * lift * presence).coerceIn(0f, 1f)),
                    style = Stroke((size.minDimension * (0.003f + 0.006f * near) * (0.6f + 0.8f * state.lift)).coerceAtLeast(1f), cap = StrokeCap.Round),
                )
            }
        }
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        beadMesh.clear()
        val count = ribbons.count(12, 6)
        val unit = size.minDimension * 0.009f
        for (slot in 0 until BEADS) {
            val x = beadAlong[slot]
            if (x < -0.5f || beadRibbon[slot] >= count) continue
            val y = ribbonY(beadRibbon[slot], x, count, state)
            beadMesh.glow(x * size.width, y * size.height, unit, state.palette.argb(beadRibbon[slot].toFloat() / count + genes.walk, saturation = 0.4f, value = 1f, alpha = 0.4f + 0.5f * state.lift))
        }
        val lead = leadRibbon.coerceAtMost(count - 1)
        beadMesh.glow(leadAlong * size.width, ribbonY(lead, leadAlong, count, state) * size.height, unit * 2.2f, state.palette.argb(genes.walk, saturation = 0.3f, value = 1f, alpha = 0.9f))
        drawMesh(beadMesh, BlendMode.Plus)
    }

    override fun onReset() {
        travel = 0f
        flow = 0f
        rise.reset()
        pulseAt.fill(-1f)
        nextPulse = 0
        straight = 0f
        beadAlong.fill(-1f)
        nextBead = 0
        leadRibbon = 0
        leadAlong = 0f
        lastBeat = -1
    }

    private companion object {
        const val PULSES = 6
        const val BEADS = 160
        const val STEPS = 96
    }
}

/**
 * A contour map of the last few seconds of music laid out in rings round a centre on an orbit, its
 * outer rings wider than the screen. Each level sits at its own depth, hikers walk round the lines,
 * shooting stars cross above, and a kick sends a ring out from the core. On a drop time runs backwards
 * for a bar and the map turns inside out.
 */
internal class Contour : Layered(
    name = "Contour",
    family = VizFamily.Plenoptic,
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 403L, groundKind = GroundKind.Spectrogram, groundDim = 0.6f, detailKind = DetailKind.Dots, camera = Camera2D(wander = 0.06f, seed = 403)),
) {
    override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.6f, livelyTrail = 0.5f)

    private val levels = genes.choice("Levels", 3, start = 1)
    private val maps = genes.toggle("Second map", start = false)
    private val backwards = genes.toggle("Time backwards", start = false)
    private val lineKind = genes.choice("Line kind", 2)

    private val grid = Array(ROWS) { FloatArray(COLUMNS) }
    private var newest = 0
    private var owed = 0f
    private val sorted = FloatArray(ROWS * COLUMNS)
    private val paths = Array(7) { Path() }
    private val turn = MusicClock(beatsPerCycle = 16f)
    private var spin = 0f
    private val core = Spring(stiffness = 220f, damping = 0.45f)
    private val stage = Stage(reachX = 0.22f, reachY = 0.16f, start = 1.9f)
    private val centre = Orbiter(radiusX = 0.3f, radiusY = 0.24f, lapsPerBar = 0.25f)
    private var inside = 0f
    private val hikerAngle = FloatArray(HIKERS) { it * TAU / HIKERS }
    private val ringAge = FloatArray(6)
    private var nextRing = 0
    private val comets = Comets(size = 0.025f)
    private val hikerMesh = TriangleMesh(maxVertices = HIKERS * 8 + 8)
    private val dashes = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val bands = state.frame.bandsRel
        if (bands.isNotEmpty()) {
            owed += dt * (6f + 8f * state.tempo)
            while (owed >= 1f) {
                owed -= 1f
                newest = (newest - 1 + ROWS) % ROWS
                val row = grid[newest]
                for (column in 0 until COLUMNS) row[column] = bands.foldedAt(column.toFloat() / COLUMNS)
            }
        }
        spin = turn.advance(dt, state.frame.bpm, state.frame.beatConfidence, state.frame.phrasePhase, state.paced(0.05f)) * TAU
        core.kick(gestures.kickHit * 7f)
        core.advance(dt)
        if (gestures.kickHit > 0f) {
            ringAge[nextRing] = 0.001f
            nextRing = (nextRing + 1) % ringAge.size
        }
        for (slot in ringAge.indices) {
            if (ringAge[slot] <= 0f) continue
            ringAge[slot] += dt / (gestures.beatSeconds * 2f)
            if (ringAge[slot] >= 1f) ringAge[slot] = 0f
        }
        if (gestures.drop) inside = 1f
        inside = (inside - dt / gestures.barSeconds).coerceAtLeast(0f)
        stage.advance(dt)
        centre.centreX = stage.x
        centre.centreY = stage.y
        centre.advance(state, gestures)
        kit.place(0, centre.x, centre.y)
        for (hiker in 0 until HIKERS) hikerAngle[hiker] += dt * (0.9f + 0.2f * hiker) * state.tempo * if (hiker % 2 == 0) 1f else -1f
        comets.advance(state, gestures, random)
        kit.follow(1, comets.travellers)
        val aspect = kit.aspect
        val reach = hikerReach(0)
        kit.place(2, centre.x + cos(hikerAngle[0]) * reach / aspect, centre.y + sin(hikerAngle[0]) * reach)
    }

    /** How far hiker [index] walks from the centre, as a share of the height. */
    private fun hikerReach(index: Int): Float = 0.18f + 0.03f * index

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val bands = state.frame.bandsRel
        if (bands.isEmpty()) return
        var count = 0
        for (row in grid) for (value in row) sorted[count++] = value
        sorted.sort()
        val at = Offset(centre.x * size.width, centre.y * size.height)
        val inner = size.minDimension * 0.07f
        val outer = sceneRadius * 1.3f
        val set = LEVELS[levels.value]
        val lift = 0.35f + 0.65f * state.lift
        val second = maps.weight(1)
        for (level in set.indices) {
            val threshold = sorted[((sorted.size - 1) * set[level]).toInt()]
            val path = paths[level]
            path.reset()
            if (threshold <= 0f) continue
            trace(path, threshold, at, spin, inner, outer)
            val share = level.toFloat() / (set.size - 1)
            val colour = state.palette.cycled(share * 0.6f + genes.walk, alpha = ((0.35f + 0.55f * share) * lift).coerceIn(0f, 1f))
            val style = Stroke(
                (size.minDimension * (0.006f + 0.004f * share)).coerceAtLeast(1.2f),
                cap = StrokeCap.Round,
                pathEffect = if (lineKind.value == 1) dashes else null,
            )
            // Each level a little nearer than the one below, so the map reads as terrain seen from above.
            val depth = 0.2f * share
            translate(camera.panX * size.width * depth, camera.panY * size.height * depth) {
                drawPath(path, colour, style = style)
                if (second > 0.01f) scale(-1f, -1f, center) { drawPath(path, colour.copy(alpha = colour.alpha * second), style = style) }
            }
        }
        drawCircle(
            state.palette.cap.copy(alpha = (0.15f + 0.6f * core.value).coerceIn(0f, 1f)),
            size.minDimension * 0.025f * (0.5f + abs(core.value).coerceAtMost(1.5f)),
            at,
        )
        with(comets) { drawComets(state.palette, genes.walk, alpha = lift) }
    }

    /**
     * Marching squares: each cell of four readings is crossed by the line where the level falls between
     * them, and one of sixteen patterns says which of its edges the line joins.
     */
    private fun DrawScope.trace(path: Path, level: Float, at: Offset, spin: Float, inner: Float, outer: Float) {
        for (row in 0 until ROWS - 1) {
            val near = grid[(newest + row) % ROWS]
            val far = grid[(newest + row + 1) % ROWS]
            for (column in 0 until COLUMNS) {
                val right = (column + 1) % COLUMNS
                val a = near[column]
                val b = near[right]
                val c = far[right]
                val d = far[column]
                val pattern = (if (a > level) 1 else 0) or (if (b > level) 2 else 0) or
                    (if (c > level) 4 else 0) or (if (d > level) 8 else 0)
                if (pattern == 0 || pattern == 15) continue
                val x = column.toFloat()
                val y = row.toFloat()
                val topX = x + crossing(a, b, level)
                val rightY = y + crossing(b, c, level)
                val bottomX = x + crossing(d, c, level)
                val leftY = y + crossing(a, d, level)
                when (pattern) {
                    1, 14 -> segment(path, topX, y, x, leftY, at, spin, inner, outer)
                    2, 13 -> segment(path, topX, y, x + 1f, rightY, at, spin, inner, outer)
                    3, 12 -> segment(path, x, leftY, x + 1f, rightY, at, spin, inner, outer)
                    4, 11 -> segment(path, x + 1f, rightY, bottomX, y + 1f, at, spin, inner, outer)
                    6, 9 -> segment(path, topX, y, bottomX, y + 1f, at, spin, inner, outer)
                    7, 8 -> segment(path, x, leftY, bottomX, y + 1f, at, spin, inner, outer)
                    5 -> {
                        segment(path, topX, y, x, leftY, at, spin, inner, outer)
                        segment(path, x + 1f, rightY, bottomX, y + 1f, at, spin, inner, outer)
                    }
                    10 -> {
                        segment(path, topX, y, x + 1f, rightY, at, spin, inner, outer)
                        segment(path, x, leftY, bottomX, y + 1f, at, spin, inner, outer)
                    }
                }
            }
        }
    }

    private fun crossing(from: Float, to: Float, level: Float): Float {
        val span = to - from
        return if (abs(span) < 1e-6f) 0.5f else ((level - from) / span).coerceIn(0f, 1f)
    }

    /** One piece of line: columns go round, rows go outward, or inward when time runs backwards. */
    private fun segment(path: Path, x1: Float, y1: Float, x2: Float, y2: Float, at: Offset, spin: Float, inner: Float, outer: Float) {
        val from = polar(at, TAU * x1 / COLUMNS + spin, radiusOf(y1, inner, outer))
        val to = polar(at, TAU * x2 / COLUMNS + spin, radiusOf(y2, inner, outer))
        path.moveTo(from.x, from.y)
        path.lineTo(to.x, to.y)
    }

    private fun radiusOf(row: Float, inner: Float, outer: Float): Float {
        val out = row / (ROWS - 1)
        val flipped = if (backwards.on) 1f - out else out
        // A drop turns the map inside out for a bar.
        val shown = flipped + (1f - 2f * flipped) * inside
        return inner + (outer - inner) * shown
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        val at = Offset(centre.x * size.width, centre.y * size.height)
        val thin = (size.minDimension * 0.003f).coerceAtLeast(1f)
        for (slot in ringAge.indices) {
            val age = ringAge[slot]
            if (age <= 0f) continue
            drawCircle(
                state.palette.cap.copy(alpha = (0.5f * (1f - age)).coerceIn(0f, 1f)),
                sceneRadius * 0.9f * age,
                at,
                style = Stroke(thin * (1f + 3f * (1f - age))),
            )
        }
        hikerMesh.clear()
        for (hiker in 0 until HIKERS) {
            val reach = hikerReach(hiker) * size.height
            hikerMesh.glow(
                at.x + cos(hikerAngle[hiker]) * reach,
                at.y + sin(hikerAngle[hiker]) * reach,
                size.minDimension * 0.016f,
                state.palette.argb(0.15f * hiker + genes.walk, saturation = 0.4f, value = 1f, alpha = 0.5f + 0.4f * state.lift),
            )
        }
        drawMesh(hikerMesh, BlendMode.Plus)
    }

    override fun onReset() {
        for (row in grid) row.fill(0f)
        newest = 0
        owed = 0f
        turn.reset()
        spin = 0f
        core.reset()
        stage.reset()
        centre.reset()
        inside = 0f
        ringAge.fill(0f)
        nextRing = 0
        comets.clear()
    }

    private companion object {
        const val ROWS = 36
        const val COLUMNS = 48
        const val HIKERS = 12

        /** The levels traced, as shares of the grid's own readings from low to high, for three, five or seven lines. */
        val LEVELS = arrayOf(
            floatArrayOf(0.5f, 0.72f, 0.9f),
            floatArrayOf(0.4f, 0.58f, 0.72f, 0.84f, 0.93f),
            floatArrayOf(0.3f, 0.45f, 0.58f, 0.7f, 0.8f, 0.88f, 0.95f),
        )
    }
}
