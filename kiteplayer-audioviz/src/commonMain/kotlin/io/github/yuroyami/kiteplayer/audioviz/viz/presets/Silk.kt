package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.FormReadout
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.sampleAt
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Silk, a port of "Silk" (codevember, day 21: "3D audio visualizer") by Matt DesLauriers.
 *
 * - Original: http://mattdesl.github.io/codevember/21.html
 * - Repository: https://github.com/mattdesl/codevember (src/21.js, src/shaders/21-line.vert,
 *   src/shaders/21-line.frag, src/gl/gl-line-3d.js)
 * - Licence: MIT, LICENSE.md: "Copyright (c) 2015 Matt DesLauriers"
 * - Year: 2015
 * - Also ported: Ashima simplex noise (MIT, Copyright (C) 2011 Ashima Arts), the background of
 *   gl-vignette-background 1.0.4 (MIT, Copyright (c) 2014 Matt DesLauriers) and glsl-hsl2rgb 1.1.0
 *   (MIT, Copyright (c) 2015 Jam3).
 * - Deviation: 50 ribbons, the author's own Safari setting, instead of 100, for phones at 1080p.
 * - Deviation: mouse orbit and zoom are dropped; the author's default camera is kept.
 * - Deviation: the twist and the noise run on audible seconds rather than the wall clock, so the
 *   strand stands still in silence and while paused; a reduced-motion setting slows that clock too.
 * - Deviation: on a frame narrower than 16:9 the picture is scaled down about its centre, so the
 *   strand keeps the share of the width it has at 16:9 (about 80 percent) instead of being cropped.
 * - Deviation: each ribbon edge is feathered over one pixel, in place of the browser's multisampling.
 * - Deviation: the spectrum is the engine's bands, one per ERB band, read every frame and lifted by a
 *   square root toward the analyser's decibel scale, not a browser analyser.
 * - Deviation: colours are multiplied by the flash guard's light share.
 * - Deviation: where runtime shaders cannot run, the background is the same gradient without grain.
 *
 * Translucent cyan, violet and magenta ribbons twist around one horizontal strand over a light
 * grey vignette. The spectrum lies along the strand, bass at the left: a loud frequency fans the
 * ribbons apart and fattens the strand where it sits.
 *
 * The Cloth form weaves the same ribbons into a sheet of 50 rows. The front row is the spectrum
 * now, with the live waveform along its edge; each row behind it is the spectrum a sixteenth of a
 * second earlier, so the sheet reaches about three seconds back. The stage, a 20 degree view over
 * a floor with a soft shadow, comes from Fluctus, which this drawing absorbed.
 */
internal class Silk : Layered(
    name = "Silk",
    bucket = VizEnergy.Calm,
    kit = Kit(seed = 2_115L, camera = Camera2D(wander = 0f, punch = 0f, roll = 0f,
        shake = 0f, cuts = false, minZoom = 1f, maxZoom = 1f)),
) {
    override val cameraOnEcho: Boolean get() = false
    override val frontParallax: Float get() = 0f
    override val post: PostSpec get() = PostSpec.Off
    override val paintsWholeScreen: Boolean get() = true
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Shape),
        VizDrive(VizDriver.Bands, VizProperty.Colour),
        silence = VizSilence.Still,
    )

    override val forms: FormReadout
        get() = FormReadout(FORMS[if (morph < 0.5f) formFrom else formTo], evolution.morphs, evolution.births)

    internal val strand = SilkStrand()
    private val mesh = TriangleMesh(maxVertices = BATCH * STEPS * 4, maxIndices = BATCH * (STEPS - 1) * 18)
    private val vignette: ShaderProgram by lazy { ShaderProgram(VIGNETTE) }
    private val fallback = VignetteGradient()
    private val stage = SilkStage()

    /** The page's `time`, in seconds. */
    private var time = 0.0

    // The spectrum's past, one row per heard sixtieth, and the bands and waveform of this frame.
    private val history = History()
    private var heard = 0.0
    private var liveBands = FloatArray(0)
    private var liveScope = FloatArray(0)

    // The forms: the one shown before the morph, the one it moves to, and how far it has gone.
    private var formFrom = STRAND
    private var formTo = STRAND
    private var morph = 1f

    // The cloth's lattice in world units, and each vertex's level from 0 to 1.
    private val worldX = FloatArray(ROWS * ACROSS)
    private val worldY = FloatArray(ROWS * ACROSS)
    private val worldZ = FloatArray(ROWS * ACROSS)
    private val rowLevel = FloatArray(ROWS * ACROSS)

    // One projected row of ribbon, reused for every row and its shadow.
    private val rowX = FloatArray(ACROSS)
    private val rowY = FloatArray(ACROSS)
    private val rowOffsetX = FloatArray(ACROSS)
    private val rowOffsetY = FloatArray(ACROSS)
    private val rowColour = IntArray(ACROSS)
    private val rowAlpha = FloatArray(ACROSS)

    /** The lowest screen y the cloth reached on the last frame, or -1 when it drew nothing. */
    private var clothBottom = -1f

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        val frame = state.frame
        if (liveBands.size != frame.bands.size) liveBands = FloatArray(frame.bands.size)
        frame.bands.copyInto(liveBands)
        if (liveScope.size != frame.scope.size) liveScope = FloatArray(frame.scope.size)
        frame.scope.copyInto(liveScope)
        strand.listen(liveBands)
        heard += state.stepSeconds
        history.push(liveBands, heard)
        // time += min(30, dt) / 1000, on audible seconds.
        time += minOf(dt, 0.03f) * frame.audible * state.motionScale
        placeLattice()
    }

    /** Shows [form] at once, with no morph. */
    internal fun jumpTo(form: Int) {
        formFrom = form
        formTo = form
        morph = 1f
    }

    /** The height of [row] at [across], 0 to 1 from bass to treble, in world units. Row 0 is now. */
    internal fun rowHeight(row: Int, across: Float): Float =
        if (row == 0) liveBands.sampleAt(across) * CLOTH_HEIGHT
        else history.sample(history.row(row * ROW_SECONDS), across) * CLOTH_HEIGHT

    /** The horizon's screen y on the last frame. */
    internal fun horizonY(): Float = stage.horizon

    internal fun clothBottomY(): Float = clothBottom

    private fun placeLattice() {
        for (row in 0 until ROWS) {
            val past = if (row == 0) -1 else history.row(row * ROW_SECONDS)
            for (step in 0 until ACROSS) {
                val across = step / (ACROSS - 1f)
                val level = if (row == 0) liveBands.sampleAt(across) else history.sample(past, across)
                val index = row * ACROSS + step
                rowLevel[index] = level.coerceIn(0f, 1f)
                placeCloth(row, step, level * CLOTH_HEIGHT, index)
            }
        }
    }

    /** The Cloth: across to x, time back along z, the level up. The front row carries the waveform. */
    private fun placeCloth(row: Int, step: Int, height: Float, index: Int) {
        val across = step / (ACROSS - 1f)
        worldX[index] = (across - 0.5f) * CLOTH_WIDTH
        worldZ[index] = -row / (ROWS - 1f) * CLOTH_DEPTH + CLOTH_DEPTH * 0.3f
        val edge = if (row == 0) liveScope.sampleAt(across) * WAVE_EDGE else 0f
        worldY[index] = CLOTH_Y + height + edge
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val light = state.lightScale.coerceIn(0f, 1f)
        drawPaper(light)
        if (shownForm() != STRAND) {
            stage.stageView(size.width, size.height, 1f, 0f, SilkStage.ELEVATION_DEGREES)
            stage.look(size.width, size.height)
            with(stage) { drawHorizon(light, SilkStage.paper(light)) }
        }
    }

    private fun DrawScope.drawPaper(light: Float) {
        if (vignette.available && canDrawRuntimeShaders()) {
            vignette.uniform("uResolution", size.width, size.height)
            vignette.uniform("uLight", light)
            val brush = vignette.brush()
            if (brush != null) {
                drawRect(brush)
                return
            }
        }
        drawRect(fallback.brush(size.width, size.height, light))
    }

    override fun DrawScope.drawTop(state: VizRenderState) {
        if (size.width <= 0f || size.height <= 0f) return
        val light = state.lightScale.coerceIn(0f, 1f)
        val eased = morph * morph * (3f - 2f * morph)
        // The strand's share of the ink: all of it in the Strand form, none in a sheet form.
        val strandWeight = when {
            formTo == STRAND -> if (formFrom == STRAND) 1f else eased
            formFrom == STRAND -> 1f - eased
            else -> 0f
        }
        if (strandWeight > 0f) drawStrand(light, strandWeight)
        clothBottom = -1f
        if (strandWeight < 1f) drawCloth(state, light, 1f - strandWeight)
    }

    private fun DrawScope.drawStrand(light: Float, weight: Float) {
        strand.frame(size.width, size.height, time.toFloat())
        rowAlpha.fill(OPACITY * weight)
        // Painter's order by line index, with no depth test, as on the page.
        for (first in 0 until strand.lines step BATCH) {
            mesh.clear()
            for (line in first until minOf(first + BATCH, strand.lines)) {
                strand.line(line)
                addRibbon(strand.centreX, strand.centreY, strand.offsetX, strand.offsetY, strand.colour, rowAlpha, light)
            }
            drawMesh(mesh)
        }
    }

    /** The sheet forms: every row's shadow on the floor first, then the rows from the back to the front. */
    private fun DrawScope.drawCloth(state: VizRenderState, light: Float, weight: Float) {
        stage.stageView(size.width, size.height, 1f, 0f, SilkStage.ELEVATION_DEGREES)
        stage.look(size.width, size.height)
        mesh.clear()
        var batched = 0
        rowColour.fill(SHADOW_RGB)
        rowAlpha.fill(SHADOW_INK * weight)
        for (row in ROWS - 1 downTo 0) {
            if (!projectRow(row, shadow = true)) continue
            addRibbon(rowX, rowY, rowOffsetX, rowOffsetY, rowColour, rowAlpha, light)
            if (++batched == BATCH) { drawMesh(mesh); mesh.clear(); batched = 0 }
        }
        if (batched > 0) { drawMesh(mesh); mesh.clear(); batched = 0 }
        for (row in ROWS - 1 downTo 0) {
            if (!projectRow(row, shadow = false)) continue
            val rgb = state.palette.argb(row / (ROWS - 1f) * 0.5f + genes.walk, saturation = 0.55f, value = 0.45f) and 0xFFFFFF
            rowColour.fill(rgb)
            for (step in 0 until ACROSS) rowAlpha[step] = OPACITY * (0.35f + 0.65f * rowLevel[row * ACROSS + step]) * weight
            addRibbon(rowX, rowY, rowOffsetX, rowOffsetY, rowColour, rowAlpha, light)
            for (step in 0 until ACROSS) if (!rowX[step].isNaN()) clothBottom = maxOf(clothBottom, rowY[step])
            if (++batched == BATCH) { drawMesh(mesh); mesh.clear(); batched = 0 }
        }
        if (batched > 0) drawMesh(mesh)
    }

    /**
     * Projects [row], or its floor shadow, into [rowX] and [rowY], with the ribbon's half thickness as
     * an offset across the row. A point outside the lens is NaN, which breaks the ribbon there.
     */
    private fun projectRow(row: Int, shadow: Boolean): Boolean {
        // The ribbon's thickness in pixels, from the middle of the row projected twice.
        val middle = row * ACROSS + ACROSS / 2
        val x = worldX[middle]; val y = worldY[middle]; val z = worldZ[middle]
        val lower = if (shadow) stage.shadow(x, y, z) else stage.project(x, y, z)
        if (!lower) return false
        val lowerY = stage.screenY
        val upper = if (shadow) stage.shadow(x, y, z - RIBBON_WORLD) else stage.project(x, y + RIBBON_WORLD, z)
        if (!upper) return false
        val thickness = maxOf(abs(stage.screenY - lowerY), 0.5f)
        var any = false
        for (step in 0 until ACROSS) {
            val index = row * ACROSS + step
            val seen = if (shadow) stage.shadow(worldX[index], worldY[index], worldZ[index])
                else stage.project(worldX[index], worldY[index], worldZ[index])
            if (seen) {
                rowX[step] = stage.screenX
                rowY[step] = stage.screenY
                any = true
            } else {
                rowX[step] = Float.NaN
                rowY[step] = Float.NaN
            }
        }
        for (step in 0 until ACROSS) {
            val previous = if (step > 0 && !rowX[step - 1].isNaN()) step - 1 else step
            val next = if (step < ACROSS - 1 && !rowX[step + 1].isNaN()) step + 1 else step
            val dx = rowX[next] - rowX[previous]
            val dy = rowY[next] - rowY[previous]
            val length = sqrt(dx * dx + dy * dy)
            if (length > 0f && length.isFinite()) {
                rowOffsetX[step] = -dy / length * thickness * 0.5f
                rowOffsetY[step] = dx / length * thickness * 0.5f
            } else {
                rowOffsetX[step] = 0f
                rowOffsetY[step] = thickness * 0.5f
            }
        }
        return any
    }

    private fun shownForm(): Int = if (morph < 0.5f) formFrom else formTo

    /**
     * One ribbon through the points of [centreX] and [centreY], [offsetX] and [offsetY] from the
     * centre to one edge. Each point gets a core at its [alpha] and a one-pixel fringe on both edges,
     * which keeps the ribbon's coverage when it is under a pixel. A NaN point breaks the ribbon.
     */
    private fun addRibbon(
        centreX: FloatArray, centreY: FloatArray, offsetX: FloatArray, offsetY: FloatArray,
        colour: IntArray, alpha: FloatArray, light: Float,
    ) {
        var before = -1
        for (i in 0 until STEPS) {
            val cx = centreX[i]
            val cy = centreY[i]
            if (cx.isNaN()) {
                before = -1
                continue
            }
            val ox = offsetX[i]
            val oy = offsetY[i]
            val half = sqrt(ox * ox + oy * oy)
            val ux = if (half > 0f) ox / half else 0f
            val uy = if (half > 0f) oy / half else 1f
            val inner = maxOf(half - 0.5f, 0f)
            val outer = half + 0.5f
            val opacity = alpha[i]
            val peak = if (half >= 0.5f) opacity else opacity * 4f * half / (2f * half + 1f)
            val rgb = colour[i]
            val red = channel((rgb shr 16 and 0xFF) / 255f * light)
            val green = channel((rgb shr 8 and 0xFF) / 255f * light)
            val blue = channel((rgb and 0xFF) / 255f * light)
            val solid = (channel(peak) shl 24) or (red shl 16) or (green shl 8) or blue
            val clear = (red shl 16) or (green shl 8) or blue
            val a = mesh.vertex(cx + ux * outer, cy + uy * outer, clear)
            mesh.vertex(cx + ux * inner, cy + uy * inner, solid)
            mesh.vertex(cx - ux * inner, cy - uy * inner, solid)
            mesh.vertex(cx - ux * outer, cy - uy * outer, clear)
            if (before >= 0 && a >= 0) {
                mesh.quad(before + 1, a + 1, a, before)
                mesh.quad(before + 1, a + 1, a + 2, before + 2)
                mesh.quad(before + 2, a + 2, a + 3, before + 3)
            }
            before = a
        }
    }

    override fun onReset() {
        strand.reset()
        time = 0.0
        history.clear()
        heard = 0.0
        liveBands.fill(0f)
        liveScope.fill(0f)
        formFrom = STRAND
        formTo = STRAND
        morph = 1f
        clothBottom = -1f
    }

    internal companion object {
        /** Ribbons drawn per call, so every corner number fits the triangle call's 16 bits. */
        const val BATCH = 25
        const val STEPS = SilkStrand.STEPS
        const val OPACITY = 0.5f

        // The cloth: 50 rows a sixteenth of a second apart, 200 points across, in world units.
        const val ROWS = 50
        const val ROW_SECONDS = 1f / 16f
        const val ACROSS = SilkStrand.STEPS
        const val CLOTH_WIDTH = 100f
        const val CLOTH_DEPTH = 120f
        const val CLOTH_HEIGHT = 28f
        const val CLOTH_Y = 0f
        /** A row's ribbon thickness. */
        const val RIBBON_WORLD = 1.4f
        /** How far the live waveform lifts the front row's edge. */
        const val WAVE_EDGE = 6f
        /** The shadow's ink, a dark grey. */
        const val SHADOW_INK = 0.12f
        const val SHADOW_RGB = 0x3A3A46

        val FORMS = listOf("Strand", "Cloth", "Tube", "Veil")
        const val STRAND = 0
        const val CLOTH = 1
        const val TUBE = 2
        const val VEIL = 3

        fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        /** gl-vignette-background 1.0.4 as the page styled it, with glsl-random's hash. */
        const val VIGNETTE = """
uniform float2 uResolution;
uniform float uLight;

float random(float2 co) {
    float a = 12.9898;
    float b = 78.233;
    float c = 43758.5453;
    float dt = dot(co.xy, float2(a, b));
    float sn = mod(dt, 3.14);
    return fract(sin(sn) * c);
}

float3 blendOverlay(float3 base, float3 blend) {
    return float3(
        base.r < 0.5 ? (2.0 * base.r * blend.r) : (1.0 - 2.0 * (1.0 - base.r) * (1.0 - blend.r)),
        base.g < 0.5 ? (2.0 * base.g * blend.g) : (1.0 - 2.0 * (1.0 - base.g) * (1.0 - blend.g)),
        base.b < 0.5 ? (2.0 * base.b * blend.b) : (1.0 - 2.0 * (1.0 - base.b) * (1.0 - blend.b)));
}

half4 main(float2 position) {
    // The page's quad puts texture v = 0 at the top, so vUv is the pixel over the size.
    float2 vUv = position / uResolution;
    float size = min(uResolution.x, uResolution.y) * 1.5;
    float2 scale = float2(size / uResolution.x, size / uResolution.y);
    float2 pos = vUv - 0.5;
    pos /= scale;
    pos -= float2(-0.05, -0.15);
    float dist = smoothstep(-0.5, 1.0, 1.0 - length(pos));
    float3 color = mix(float3(0.30980392), float3(1.0), dist);
    float3 noise = float3(random(vUv * 1.5), random(vUv * 2.5), random(vUv));
    color = mix(color, blendOverlay(color, noise), 0.1);
    return half4(half3(color * uLight), 1.0);
}
"""
    }
}

/**
 * The strand's geometry, one line at a time: the page's vertex shader and its `line3D`, run on
 * the processor for the 200 points of a ribbon.
 */
internal class SilkStrand(val lines: Int = 50) {
    /** Each point's x, `lerp(-1, 1, i / 199)`. */
    val x = FloatArray(STEPS) { (-1.0 * (1.0 - it / (STEPS - 1.0)) + it / (STEPS - 1.0)).toFloat() }

    /** `smoothstep(0, 0.5, 1 - |x|)`, which closes the strand at both ends. */
    private val pinch = FloatArray(STEPS) { smoothstep(0f, 0.5f, 1f - kotlin.math.abs(x[it])) }

    /** The spectrum under each point, sampled at `u = x * 0.5 + 0.5`. */
    val frequencies = FloatArray(STEPS)

    /** Where each point of the current line lands, in pixels. */
    val centreX = FloatArray(STEPS)
    val centreY = FloatArray(STEPS)

    /** The pixel offset from the centre to the ribbon's `direction = +1` side. */
    val offsetX = FloatArray(STEPS)
    val offsetY = FloatArray(STEPS)

    /** Each point's colour as packed RGB, from `hsl(mix(0.5, 0.9, sin(angle) * 0.5 + 0.5), 0.7, 0.6)`. */
    val colour = IntArray(STEPS)

    private var aspect = 16f / 9f
    private var middleX = 0f
    private var middleY = 0f
    private var halfWidth = 0f
    private var halfHeight = 0f
    private var time = 0f

    /**
     * Reads the bands under each point at `u = x * 0.5 + 0.5`, where the page read its analyser's
     * bytes. The square root lifts the bands toward the analyser's decibel scale, where most bins of
     * music sit above half.
     */
    fun listen(bands: FloatArray) {
        for (i in 0 until STEPS) frequencies[i] = sqrt(bands.sampleAt(x[i] * 0.5f + 0.5f).coerceAtLeast(0f))
    }

    /** Sets the canvas and the page's `iGlobalTime` for the lines that follow. */
    fun frame(width: Float, height: Float, time: Float) {
        aspect = width / height
        // The fit for frames narrower than 16:9. It is 1 on wider frames, which the page drew as they are.
        val fit = minOf(1f, aspect / FRAMING_ASPECT)
        middleX = width * 0.5f
        middleY = height * 0.5f
        halfWidth = width * 0.5f * fit
        halfHeight = height * 0.5f * fit
        this.time = time
    }

    /** Works out line [index] of [lines] into [centreX], [centreY], [offsetX], [offsetY] and [colour]. */
    fun line(index: Int) {
        val position = index / (lines - 1f)
        val fx = FOCAL / aspect
        for (i in 0 until STEPS) {
            val px = x[i]
            val freq = frequencies[i]
            val computedThickness = THICKNESS * pinch[i]
            // angle = 2 PI x * twists + index * PI * mix(0.5, 1.5, freq) + time, with PI as 3.14.
            val angleOffset = position * PI_314 * mix(0.5f, 1.5f, freq)
            val angle = 2f * PI_314 * px * 0.5f + angleOffset + time
            var radius = RADIUS
            if (freq != 0f) radius += freq * 0.5f * AshimaNoise.simplex3(px, angle, time * 0.2f)
            radius += 0.1f * AshimaNoise.simplex3(px * 6f, angle, time * 0.25f)
            radius += 0.1f * AshimaNoise.simplex3(px * 2.5f, angle, time * 0.5f)
            radius *= pinch[i]
            radius *= mix(0.65f, 1f, freq)
            val sine = sin(angle)
            val y = cos(angle) * radius
            val z = sine * radius

            // line3D: the camera sits at (0, 0, 0.5) looking at the origin, so clip w is 0.5 - z.
            val clipX = fx * px
            val clipY = FOCAL * y
            val clipW = CAMERA_DISTANCE - z
            // The page adds this point's offset to previous and next as well, so all three share
            // its screen y and w, and the direction (miter off) always runs along x.
            val currentX = clipX / clipW * aspect
            val previousX = fx * x[maxOf(i - 1, 0)] / clipW * aspect
            val nextX = fx * x[minOf(i + 1, STEPS - 1)] / clipW * aspect
            var dirX = if (currentX == previousX) nextX - currentX else currentX - previousX
            var dirY = 0f
            val length = sqrt(dirX * dirX + dirY * dirY)
            if (length > 0f && length.isFinite()) {
                dirX /= length
                dirY /= length
            } else {
                dirX = 1f
                dirY = 0f
            }
            var normalX = -dirY * computedThickness / 2f
            val normalY = dirX * computedThickness / 2f
            normalX /= aspect
            // The page's quirk, kept: line3D returns currentProjected + vec4(offset, 0.0, 1.0).
            val w = clipW + 1f
            centreX[i] = middleX + halfWidth * silkNdc(clipX, clipW)
            centreY[i] = middleY - halfHeight * silkNdc(clipY, clipW)
            offsetX[i] = halfWidth * normalX / w
            offsetY[i] = -halfHeight * normalY / w
            colour[i] = hslRgb(mix(0.5f, 0.9f, sine * 0.5f + 0.5f), 0.7f, 0.6f)
        }
    }

    fun reset() {
        frequencies.fill(0f)
    }

    companion object {
        const val STEPS = 200
        const val RADIUS = 0.1f
        const val THICKNESS = 0.01f

        /** The shader's `#define PI 3.14`. */
        const val PI_314 = 3.14f

        /** The page's orbit distance: the camera sits this far from the origin on z. */
        const val CAMERA_DISTANCE = 0.5f

        /** `1 / tan(fov / 2)` for the page's vertical field of view of 50 degrees. */
        val FOCAL: Float = (1.0 / tan(25.0 * PI / 180.0)).toFloat()

        /** The widest frame the fit leaves alone. */
        const val FRAMING_ASPECT = 16f / 9f
    }
}

/**
 * A clip coordinate over the page's `w + 1`. The page's `line3D` adds 1.0 to w, which shrinks the
 * 2-unit strand to about a third of its perspective size and flattens it. It is the page's framing.
 */
internal fun silkNdc(clip: Float, clipW: Float): Float = clip / (clipW + 1f)

private fun mix(from: Float, to: Float, amount: Float): Float = from * (1f - amount) + to * amount

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** glsl-hsl2rgb 1.1.0, packed as RGB. */
internal fun hslRgb(hue: Float, saturation: Float, lightness: Float): Int {
    val red: Float
    val green: Float
    val blue: Float
    if (saturation == 0f) {
        red = lightness
        green = lightness
        blue = lightness
    } else {
        val f2 = if (lightness < 0.5f) lightness * (1f + saturation)
            else lightness + saturation - saturation * lightness
        val f1 = 2f * lightness - f2
        red = hueChannel(f1, f2, hue + 1f / 3f)
        green = hueChannel(f1, f2, hue)
        blue = hueChannel(f1, f2, hue - 1f / 3f)
    }
    fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
}

private fun hueChannel(f1: Float, f2: Float, hueIn: Float): Float {
    var hue = hueIn
    if (hue < 0f) hue += 1f else if (hue > 1f) hue -= 1f
    return when {
        6f * hue < 1f -> f1 + (f2 - f1) * 6f * hue
        2f * hue < 1f -> f2
        3f * hue < 2f -> f1 + (f2 - f1) * ((2f / 3f) - hue) * 6f
        else -> f1
    }
}

/** The vignette as a radial gradient, for canvases where the shader cannot run. Kept between frames. */
private class VignetteGradient {
    private var width = -1f
    private var height = -1f
    private var light = -1f
    private var cached: Brush? = null

    fun brush(width: Float, height: Float, light: Float): Brush {
        val kept = cached
        if (kept != null && width == this.width && height == this.height && light == this.light) return kept
        val size = minOf(width, height) * 1.5f
        val stops = Array(STOPS + 1) { k ->
            val fraction = k / STOPS.toFloat()
            // smoothstep(-0.5, 1.0, 1 - dist) with dist = 1.5 * fraction, in units of size.
            val shade = smoothstep(-0.5f, 1f, 1f - 1.5f * fraction)
            val grey = (DARK + (1f - DARK) * shade) * light
            fraction to Color(grey, grey, grey)
        }
        val made = Brush.radialGradient(
            *stops,
            center = Offset(width * 0.5f - 0.05f * size, height * 0.5f - 0.15f * size),
            radius = 1.5f * size,
        )
        this.width = width
        this.height = height
        this.light = light
        cached = made
        return made
    }

    private companion object {
        const val STOPS = 16
        const val DARK = 79f / 255f
    }
}
