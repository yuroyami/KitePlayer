package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import io.github.yuroyami.kiteplayer.audioviz.viz.actors.Orbiter
import io.github.yuroyami.kiteplayer.audioviz.viz.foldedAt
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.glow
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plasma's crossing grid and ridge-following dots, with Plasma Field's per-pixel field, folded
 * recipes and palette ramp. Every layer uses the same exposure and palette; the selected picture
 * has no extra bloom or colour splitting compared with its browser tile.
 */
internal class Plasma : ShaderPreset(
    source = SOURCE,
    name = "Plasma",
    family = VizFamily.Alchemy,
    bucket = VizEnergy.Mid,
    seed = 3f,
    kit = Kit(seed = 501L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false, seed = 501)),
) {
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Mid, VizProperty.Shape),
        VizDrive(VizDriver.Treble, VizProperty.Shape),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled, VizResponse.spring(0.3f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.Rate),
        VizDrive(VizDriver.Section, VizProperty.Shape, VizCurve.Discrete,
            VizResponse.envelope(0.5f, delaySeconds = 0.8f)),
    )
    override val post: PostSpec get() = PostSpec.Off
    override val frontParallax: Float get() = 0f
    override val hasFallback: Boolean get() = true

    private val brightness = VizParam("Brightness", 0.2f, 1.5f, 0.75f)
    private val saturation = VizParam("Saturation", 0f, 1.5f, 0.85f)
    private val colourRange = VizParam("Colour range", 0.1f, 1f, 0.55f)
    private val colourSpeed = VizParam("Colour speed", 0f, 2f, 0.2f)
    private val colourOffset = VizParam("Colour offset", 0f, 1f, 0f)
    private val speed = VizParam("Speed", 0.1f, 3f, 1f)
    private val backwards = VizParam("Reverse scroll", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val waves = VizParam("Wave density", 0.5f, 3f, 1.5f)
    private val swirl = VizParam("Swirl", 0f, 1f, 0f)
    private val lattice = VizParam("Lattice", 0f, 1f, 0f)
    private val fold = VizParam("Sixfold field", 0f, 1f, 0f).apply { step = 1f; toggle = true }
    private val veins = VizParam("Fine veins", 0f, 1f, 0.25f)
    private val veinDensity = VizParam("Vein density", 0.5f, 2f, 1f)
    private val gridOpacity = VizParam("Grid opacity", 0f, 0.6f, 0.18f)
    private val gridCount = VizParam("Grid density", 8f, 64f, 28f).apply { step = 1f }
    private val gridWidth = VizParam("Grid width", 0.02f, 0.3f, 0.065f)
    private val gridAngle = VizParam("Grid angle", -90f, 90f, 0f)
    private val sectionTurn = VizParam("Section rotation", 0f, 1f, 1f)
    private val dotCount = VizParam("Dots", 0f, DOTS.toFloat(), 6f).apply { step = 1f }
    private val dotSize = VizParam("Dot size", 0.3f, 3f, 1f)
    private val dotGlow = VizParam("Dot glow", 0f, 1f, 0.12f)
    private val dotSpeed = VizParam("Dot speed", 0.25f, 3f, 1f)
    private val dotOrbits = VizParam("Dot orbits", 0f, 1f, 0f)
    private val dotWarp = VizParam("Dot distortion", 0f, 2f, 1f)
    private val cometOpacity = VizParam("Comet opacity", 0f, 1f, 0.65f)
    private val cometSize = VizParam("Comet size", 0.3f, 3f, 1f)
    override val params: List<VizParam> = listOf(
        brightness, saturation, colourRange, colourSpeed, colourOffset,
        speed, backwards, waves, swirl, lattice, fold, veins, veinDensity,
        gridOpacity, gridCount, gridWidth, gridAngle, sectionTurn,
        dotCount, dotSize, dotGlow, dotSpeed, dotOrbits, dotWarp, cometOpacity, cometSize,
    )

    private var flow = 0f
    private var travel = 0f
    private var colourPhase = 0f
    private var turnTarget = 0f
    private val angle = Slew(maxPerSecond = 0.6f)
    private val bend = Spring(stiffness = 120f, damping = 0.5f)
    private val dotX = FloatArray(DOTS) { 0.5f }
    private val dotY = FloatArray(DOTS) { 0.5f }
    private val orbits = Array(DOTS) {
        Orbiter(radiusX = 0.24f + 0.025f * it, radiusY = 0.22f + 0.025f * it,
            lapsPerBar = 0.6f + 0.12f * it, phase = it.toFloat() / DOTS)
    }
    private val packedDots = FloatArray(DOTS * 2)
    private val samples = FloatArray(GRID_X * GRID_Y)
    private val taken = BooleanArray(GRID_X * GRID_Y)
    private val dotMesh = TriangleMesh(maxVertices = DOTS * 17)
    // Used only on devices without runtime shaders and when a software snapshot is requested.
    private val sheet by lazy {
        TriangleMesh(maxVertices = (FALLBACK_COLUMNS + 1) * (FALLBACK_ROWS + 1),
            maxIndices = FALLBACK_COLUMNS * FALLBACK_ROWS * 6)
    }
    private val comets = Comets()
    private var warpedX = 0f
    private var warpedY = 0f

    override fun advance(state: VizRenderState) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.1f) * state.frame.audible * state.motionScale
        flow += dt * 3.3f * state.tempo * speed.value
        travel += state.stepSeconds * speed.value / (gestures.cycleSeconds * 2f) *
            if (backwards.value >= 0.5f) -1f else 1f
        colourPhase = (colourPhase + dt * 0.025f * colourSpeed.value * state.tempo) % 1f
        bend.kick(gestures.kick * 5f)
        bend.advance(dt)
        if (gestures.section) turnTarget += 0.785f
        angle.advance(turnTarget * sectionTurn.value, dt)
        // Keep Plasma's dots riding distinct ridges, with Field's orbit motion available as a mix.
        for (index in samples.indices) {
            samples[index] = field((index % GRID_X + 0.5f) / GRID_X,
                (index / GRID_X + 0.5f) / GRID_Y, state, distort = false)
        }
        taken.fill(false)
        val ease = 1f - exp(-dt * 2f * dotSpeed.value)
        for (dot in 0 until DOTS) {
            var best = -1
            for (index in samples.indices) {
                if (!taken[index] && (best < 0 || samples[index] > samples[best])) best = index
            }
            if (best < 0) break
            val column = best % GRID_X
            val row = best / GRID_X
            for (dy in -1..1) for (dx in -1..1) {
                val x = column + dx
                val y = row + dy
                if (x in 0 until GRID_X && y in 0 until GRID_Y) taken[y * GRID_X + x] = true
            }
            val orbit = orbits[dot]
            orbit.direction = if (dot % 2 == 0) 1f else -1f
            orbit.lapsPerBar = (0.6f + 0.12f * dot) * dotSpeed.value
            orbit.advance(state, gestures)
            val ridgeX = (column + 0.5f) / GRID_X
            val ridgeY = (row + 0.5f) / GRID_Y
            dotX[dot] += (ridgeX + (orbit.x - ridgeX) * dotOrbits.value - dotX[dot]) * ease
            dotY[dot] += (ridgeY + (orbit.y - ridgeY) * dotOrbits.value - dotY[dot]) * ease
            packedDots[dot * 2] = dotX[dot]
            packedDots[dot * 2 + 1] = dotY[dot]
            kit.place(dot, dotX[dot], dotY[dot])
        }
        comets.advance(state, gestures, random)
        kit.follow(DOTS, comets.travellers)
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        program.uniform("uGrid", gridCount.value, gridWidth.value, gridOpacity.value, gridAngle.value * TAU / 360f)
        program.uniform("uFlow", flow)
        program.uniform("uTravel", travel)
        program.uniform("uAngle", angle.value)
        program.uniform("uBend", bend.value.coerceIn(-1f, 2f))
        program.uniform("uBody", state.body)
        program.uniform("uWaves", waves.value)
        program.uniform("uSwirl", swirl.value)
        program.uniform("uLattice", lattice.value)
        program.uniform("uFold", fold.value)
        program.uniform("uVeins", veins.value)
        program.uniform("uVeinDensity", veinDensity.value)
        program.uniform("uBrightness", brightness.value)
        program.uniform("uSaturation", saturation.value)
        program.uniform("uColourRange", colourRange.value)
        program.uniform("uColourPhase", colourPhase + colourOffset.value)
        program.uniform("uDotCount", dotCount.value)
        program.uniform("uDotWarp", dotWarp.value)
        program.uniforms("uDots", packedDots)
    }

    private fun deform(u: Float, v: Float, distort: Boolean) {
        var px = u - 0.5f
        var py = v - 0.5f
        if (distort && dotWarp.value > 0f) {
            for (dot in 0 until dotCount.value.toInt()) {
                val dx = px - (dotX[dot] - 0.5f)
                val dy = py - (dotY[dot] - 0.5f)
                val d = sqrt(dx * dx + dy * dy)
                val push = dotWarp.value * 0.12f * exp(-d * d / 0.018f) / maxOf(d, 0.045f)
                px += (dx - dy * 0.65f) * push
                py += (dy + dx * 0.65f) * push
            }
        }
        warpedX = px
        warpedY = py
    }

    // Mirrors the shader's field so the ridge followers and the non-shader fallback agree.
    private fun field(u: Float, v: Float, state: VizRenderState, distort: Boolean = true): Float {
        deform(u, v, distort)
        var px = warpedX
        var py = warpedY
        if (fold.value >= 0.5f) {
            val reach = sqrt(px * px + py * py)
            val wedge = TAU / 6f
            val a = atan2(py, px) + flow * 0.03f
            val folded = abs(a - floor(a / wedge) * wedge - wedge * 0.5f)
            px = reach * cos(folded)
            py = reach * sin(folded)
        }
        val c = cos(angle.value)
        val s = sin(angle.value)
        val x = px * c - py * s + 0.5f + travel
        val y = px * s + py * c + 0.5f
        val reach = sqrt(px * px + py * py)
        val k = waves.value
        var mixed = PlasmaMaterial.waves(x, y, reach, flow, state.body, bend.value.coerceIn(-1f, 2f), k)
        if (swirl.value > 0f) {
            val curled = 2f * sin(atan2(py, px) * 3f + reach * 8f * k - flow * 1.5f) +
                2f * sin((px * 4f - py * 2.6f) * k - flow)
            mixed += (curled - mixed) * swirl.value
        }
        if (lattice.value > 0f) {
            val gridded = 3f * sin((px * 4.4f * k + flow * 0.3f) * TAU / 2f) *
                sin((py * 4.4f * k - flow * 0.2f) * TAU / 2f) + sin(reach * 10f * k - flow * 2f)
            mixed += (gridded - mixed) * lattice.value
        }
        return mixed
    }

    private fun colour(state: VizRenderState, position: Float, light: Float = 1f, alpha: Float = 1f): Color {
        val at = 0.5f + (position - 0.5f) * colourRange.value + colourPhase + colourOffset.value
        val base = state.palette.ramp(abs((at - floor(at)) * 2f - 1f))
        val grey = base.red * 0.2126f + base.green * 0.7152f + base.blue * 0.0722f
        fun channel(value: Float): Float {
            val x = maxOf(0f, grey + (value - grey) * saturation.value) *
                light * brightness.value * state.lift
            return ((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)).coerceIn(0f, 1f)
        }
        return Color(channel(base.red), channel(base.green), channel(base.blue), alpha.coerceIn(0f, 1f))
    }

    override fun DrawScope.drawFallback(state: VizRenderState) {
        // A denser, size-aware mesh keeps old Android versions usable without allocating per frame.
        val columns = (size.width / 6f).toInt().coerceIn(24, FALLBACK_COLUMNS)
        val rows = (size.height / 6f).toInt().coerceIn(16, FALLBACK_ROWS)
        sheet.clear()
        for (row in 0..rows) for (column in 0..columns) {
            val u = column.toFloat() / columns
            val v = row.toFloat() / rows
            val f = field(u, v, state)
            val where = f * 0.09f + sqrt((u - 0.5f).pow(2) + (v - 0.5f).pow(2)) * 0.24f + flow * 0.05f
            val energy = state.frame.bandsRel.foldedAt(where - floor(where))
            val light = 0.12f + 0.45f * energy + 0.45f * state.lift
            // Bound the fine detail to what the fallback mesh can actually resolve.
            val frequency = minOf((5f + 4f * state.frame.trebleRel) * veinDensity.value, 2f)
            val vein = (0.5f + 0.5f * sin(f * frequency)).pow(12) * veins.value
            val angle = gridAngle.value * TAU / 360f
            val gx = warpedX * size.width / size.minDimension
            val gy = warpedY * size.height / size.minDimension
            val gridX = (gx * cos(angle) - gy * sin(angle)) * gridCount.value
            val gridY = (gx * sin(angle) + gy * cos(angle)) * gridCount.value
            val edge = minOf(abs(gridX - floor(gridX + 0.5f)), abs(gridY - floor(gridY + 0.5f)))
            val pixel = gridCount.value / minOf(columns.toFloat(), rows.toFloat())
            val line = (1f - ((edge - gridWidth.value * 0.5f) / pixel).coerceIn(0f, 1f)) * gridOpacity.value
            sheet.vertex(u * size.width, v * size.height,
                colour(state, f / 8f + 0.5f + energy * 0.15f, light + vein * 0.25f + line * 0.5f).toArgb())
        }
        for (row in 0 until rows) for (column in 0 until columns) {
            val corner = row * (columns + 1) + column
            sheet.quad(corner, corner + 1, corner + columns + 2, corner + columns + 1)
        }
        drawMesh(sheet)
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        // Native rectangles and circles keep their edges sharp at the actual canvas resolution.
        val bands = state.frame.bandsRel
        dotMesh.clear()
        val radius = maxOf(0.7f, size.minDimension * 0.0055f * dotSize.value)
        for (dot in 0 until dotCount.value.toInt()) {
            val at = dot.toFloat() / DOTS
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(at)
            val r = radius * (0.85f + 0.35f * energy)
            if (dotGlow.value > 0f) {
                dotMesh.glow(dotX[dot] * size.width, dotY[dot] * size.height, r * 4f,
                    colour(state, at, 1.4f, dotGlow.value).toArgb(), sides = 16)
            }
        }
        drawMesh(dotMesh)
        for (dot in 0 until dotCount.value.toInt()) {
            val at = dot.toFloat() / DOTS
            val energy = if (bands.isEmpty()) 0f else bands.sampleAt(at)
            drawCircle(colour(state, at, 1.5f), radius * (0.85f + 0.35f * energy),
                Offset(dotX[dot] * size.width, dotY[dot] * size.height))
        }
        // Preserve the crossing travellers, but give them a crisp head and bounded, translucent tail.
        val travellers = comets.travellers
        for (slot in 0 until travellers.capacity) {
            if (!travellers.alive[slot] || cometOpacity.value <= 0f) continue
            val t = travellers.progress[slot]
            val fade = minOf(t * 6f, (1f - t) * 6f).coerceIn(0f, 1f) * cometOpacity.value
            val head = Offset(travellers.x[slot] * size.width, travellers.y[slot] * size.height)
            val tail = Offset(
                (travellers.toX[slot] - travellers.fromX[slot]) * size.width / travellers.seconds[slot],
                (travellers.toY[slot] - travellers.fromY[slot]) * size.height / travellers.seconds[slot],
            ) * (0.035f * cometSize.value)
            val r = maxOf(0.7f, size.minDimension * 0.0035f * cometSize.value)
            drawLine(colour(state, travellers.tint[slot], 1.2f, fade * 0.5f), head - tail, head,
                strokeWidth = r, cap = StrokeCap.Round)
            drawCircle(colour(state, travellers.tint[slot], 1.5f, fade), r, head)
        }
    }

    override fun onReset() {
        flow = 0f
        travel = 0f
        colourPhase = 0f
        turnTarget = 0f
        angle.reset()
        bend.reset()
        dotX.fill(0.5f)
        dotY.fill(0.5f)
        packedDots.fill(0.5f)
        warpedX = 0f
        warpedY = 0f
        orbits.forEach { it.reset() }
        comets.clear()
    }

    private companion object {
        const val DOTS = 6
        const val GRID_X = 12
        const val GRID_Y = 8
        const val FALLBACK_COLUMNS = 128
        const val FALLBACK_ROWS = 96

        const val SOURCE = PlasmaMaterial.SOURCE + """
uniform float4 uGrid;
uniform float uFlow;
uniform float uTravel;
uniform float uAngle;
uniform float uBend;
uniform float uBody;
uniform float uWaves;
uniform float uSwirl;
uniform float uLattice;
uniform float uFold;
uniform float uVeins;
uniform float uVeinDensity;
uniform float uBrightness;
uniform float uSaturation;
uniform float uColourRange;
uniform float uColourPhase;
uniform float uDotCount;
uniform float uDotWarp;
uniform float2 uDots[6];

float2 deform(float2 p) {
    for (int i = 0; i < 6; i++) {
        if (float(i) >= uDotCount || uDotWarp <= 0.0) break;
        float2 away = p - (uDots[i] - 0.5);
        float d = length(away);
        p += float2(away.x - away.y * 0.65, away.y + away.x * 0.65) *
            uDotWarp * 0.12 * exp(-d * d / 0.018) / max(d, 0.045);
    }
    return p;
}

float field(float2 p) {
    if (uFold > 0.5) {
        float reach = length(p);
        float wedge = 6.2831853 / 6.0;
        float a = abs(mod(atan(p.y, p.x) + uFlow * 0.03, wedge) - wedge * 0.5);
        p = reach * float2(cos(a), sin(a));
    }
    float c = cos(uAngle);
    float s = sin(uAngle);
    float2 q = float2(p.x * c - p.y * s, p.x * s + p.y * c) + 0.5;
    q.x += uTravel;
    float reach = length(p);
    float mixed = plasmaWaves(q, reach, uFlow, uBody, uBend, uWaves);
    if (uSwirl > 0.0) {
        float curled = 2.0 * sin(atan(p.y, p.x) * 3.0 + reach * 8.0 * uWaves - uFlow * 1.5)
            + 2.0 * sin((p.x * 4.0 - p.y * 2.6) * uWaves - uFlow);
        mixed = mix(mixed, curled, uSwirl);
    }
    if (uLattice > 0.0) {
        float2 g = p * 4.4 * uWaves + float2(uFlow * 0.3, -uFlow * 0.2);
        float gridded = 3.0 * sin(g.x * 3.14159265) * sin(g.y * 3.14159265)
            + sin(reach * 10.0 * uWaves - uFlow * 2.0);
        mixed = mix(mixed, gridded, uLattice);
    }
    return mixed;
}

half4 main(float2 position) {
    float2 p = deform(position / uResolution - 0.5);
    float f = field(p);
    float energy = bandFolded(fract(f * 0.09 + length(p) * 0.24 + uFlow * 0.05));
    float at = 0.5 + (f / 8.0 + energy * 0.15) * uColourRange + uColourPhase;
    float3 colour = paletteLoop(at);
    float grey = dot(colour, float3(0.2126, 0.7152, 0.0722));
    colour = max(float3(0.0), mix(float3(grey), colour, uSaturation));
    float frequency = (5.0 + 4.0 * uTreble) * uVeinDensity;
    // Fade subpixel veins instead of letting them turn into shimmer or a blurred full-screen glow.
    float footprint = (18.0 + 7.0 * abs(uBend)) * uWaves * frequency / min(uResolution.x, uResolution.y);
    float resolved = 1.0 - smoothstep(0.35, 1.2, footprint);
    float veins = pow(0.5 + 0.5 * sin(f * frequency), 12.0) * uVeins * resolved;
    colour *= 0.12 + 0.45 * energy + 0.45 * uExposure + veins * (0.12 + 0.3 * uTreble);
    float2 grid = rotate(p * uResolution / min(uResolution.x, uResolution.y), uGrid.w) * uGrid.x;
    float2 edge = abs(fract(grid + 0.5) - 0.5);
    float pixel = uGrid.x / min(uResolution.x, uResolution.y);
    float line = 1.0 - smoothstep(uGrid.y * 0.5, uGrid.y * 0.5 + pixel, min(edge.x, edge.y));
    colour += paletteLoop(at + 0.12) * line * uGrid.z * (0.45 + 0.55 * energy);
    return half4(toneMap(colour * uBrightness), 1.0);
}
"""
    }
}
