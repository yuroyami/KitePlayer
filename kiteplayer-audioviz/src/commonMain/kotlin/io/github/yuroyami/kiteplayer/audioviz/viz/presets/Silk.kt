package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
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
import io.github.yuroyami.kiteplayer.audioviz.viz.WebAudioAnalyser
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.TriangleMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.drawMesh
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
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
 * - Deviation: the spectrum is rebuilt from the frame by [WebAudioAnalyser], not read from a browser.
 * - Deviation: colours are multiplied by the flash guard's light share.
 * - Deviation: where runtime shaders cannot run, the background is the same gradient without grain.
 *
 * Translucent cyan, violet and magenta ribbons twist around one horizontal strand over a light
 * grey vignette. The spectrum lies along the strand, bass at the left: a loud frequency fans the
 * ribbons apart and fattens the strand where it sits.
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

    /** The page's default AnalyserNode: 2048-point FFT, smoothing 0.8, -100 to -30 dB, 44.1 kHz. */
    private val analyser = WebAudioAnalyser()
    internal val strand = SilkStrand()
    private val mesh = TriangleMesh(maxVertices = BATCH * STEPS * 4, maxIndices = BATCH * (STEPS - 1) * 18)
    private val vignette: ShaderProgram by lazy { ShaderProgram(VIGNETTE) }
    private val fallback = VignetteGradient()

    // The page read its analyser once per 60 Hz frame, and smoothing applies once per read.
    private var readClock = READ_PERIOD

    /** The page's `time`, in seconds. */
    private var time = 0.0

    override fun advance(state: VizRenderState) {
        val dt = state.deltaSeconds
        readClock = minOf(readClock + dt, 2f * READ_PERIOD)
        if (readClock >= READ_PERIOD * 0.999f) {
            readClock -= READ_PERIOD
            analyser.update(state.frame)
            strand.listen(analyser.frequencyBytes)
        }
        // time += min(30, dt) / 1000, on audible seconds.
        time += minOf(dt, 0.03f) * state.frame.audible * state.motionScale
    }

    override fun DrawScope.drawEcho(state: VizRenderState) {
        val light = state.lightScale.coerceIn(0f, 1f)
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
        strand.frame(size.width, size.height, time.toFloat())
        // Painter's order by line index, with no depth test, as on the page.
        for (first in 0 until strand.lines step BATCH) {
            mesh.clear()
            for (line in first until minOf(first + BATCH, strand.lines)) {
                strand.line(line)
                addRibbon(light)
            }
            drawMesh(mesh)
        }
    }

    /**
     * One ribbon from the strand's current line. Each point gets a core at the page's opacity and
     * a one-pixel fringe on both edges, which keeps the ribbon's coverage when it is under a pixel.
     */
    private fun addRibbon(light: Float) {
        var before = -1
        for (i in 0 until STEPS) {
            val ox = strand.offsetX[i]
            val oy = strand.offsetY[i]
            val half = sqrt(ox * ox + oy * oy)
            val ux = if (half > 0f) ox / half else 0f
            val uy = if (half > 0f) oy / half else 1f
            val inner = maxOf(half - 0.5f, 0f)
            val outer = half + 0.5f
            val peak = if (half >= 0.5f) OPACITY else OPACITY * 4f * half / (2f * half + 1f)
            val rgb = strand.colour[i]
            val red = channel((rgb shr 16 and 0xFF) / 255f * light)
            val green = channel((rgb shr 8 and 0xFF) / 255f * light)
            val blue = channel((rgb and 0xFF) / 255f * light)
            val solid = (channel(peak) shl 24) or (red shl 16) or (green shl 8) or blue
            val clear = (red shl 16) or (green shl 8) or blue
            val cx = strand.centreX[i]
            val cy = strand.centreY[i]
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
        analyser.reset()
        strand.reset()
        readClock = READ_PERIOD
        time = 0.0
    }

    private companion object {
        /** Ribbons drawn per call, so every corner number fits the triangle call's 16 bits. */
        const val BATCH = 25
        const val STEPS = SilkStrand.STEPS
        const val OPACITY = 0.5f
        const val READ_PERIOD = 1f / 60f

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

    /** The spectrum under each point, `byte / 256`, sampled nearest at `u = x * 0.5 + 0.5`. */
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

    /** Reads `getByteFrequencyData` the way the page's float texture did: nearest bin, clamped. */
    fun listen(bytes: IntArray) {
        val bins = bytes.size
        for (i in 0 until STEPS) {
            val u = x[i] * 0.5f + 0.5f
            val bin = floor(u * bins).toInt().coerceIn(0, bins - 1)
            frequencies[i] = bytes[bin] / 256f
        }
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
