package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.EchoCopy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState

/**
 * How the previous frame is bent before it is laid back down.
 *
 * This is the piece that separates a smear from a trip. The feedback loop brings the last frame
 * back scaled and turned, which is enough to make a mark grow its own echo. But scaling and
 * turning are the only two things a whole picture can be put through at once, so every drawing
 * built on the loop ends up doing one of the same two things.
 *
 * Asking per pixel removes that limit. Each pixel of the new frame works out where in the old one
 * it came from, and that answer can be anything: a twist that tightens away from the middle,
 * ripples travelling outward, a lens, a flow read from noise. The picture keeps folding through
 * that shape, frame after frame, and grows detail nobody drew.
 *
 * [field] is a piece of SkSL declaring one function, which is handed a position in the range
 * roughly minus one to one and answers how far to shift. Everything in the shared library is
 * available inside it, including the spectrum, so the shape can answer the music:
 *
 * ```
 * float2 warpField(float2 uv) {
 *     return float2(-uv.y, uv.x) * (0.4 + uBass);
 * }
 * ```
 *
 * A field that needs more than the music reads `uWarpParams`, four numbers the drawing sets in
 * [params] every frame: where a second well is, what constant a fractal uses, and so on.
 */
@AudioVizAuthoringApi
public class WarpSpec(
    internal val field: String,
    /** How hard the bend is in calm music. */
    public val calmAmount: Float = 0.04f,
    /** And in lively music. Small numbers go a long way, because it happens every frame. */
    public val livelyAmount: Float = 0.11f,
    /**
     * How fast the echoes turn their hue as they age, in radians a second. The turn is made in OKLab,
     * so an echo keeps its brightness while its colour moves on. Zero leaves colours alone.
     */
    public val drift: Float = 0f,
) {
    /** Four numbers handed to the field as `uWarpParams`. All zero unless the drawing sets them. */
    internal val params: FloatArray = FloatArray(4)

    private val lazyRunner = lazy { WarpRunner(field, drift, params) }
    internal val runner: WarpRunner get() = lazyRunner.value

    /** Forgets the readings the warp keeps between frames. */
    internal fun restart() {
        if (lazyRunner.isInitialized()) runner.reset()
    }

    /** A multiplier on both amounts, for a setting a person can turn up or down. */
    public var strength: Float = 1f

    public fun amount(mood: Float): Float =
        (calmAmount + (livelyAmount - calmAmount) * mood.coerceIn(0f, 1f)) * strength
}

/**
 * The compiled warp program and the readings it needs, kept alive between frames.
 *
 * Compiling is far more expensive than running, so it happens once. The strips of spectrum are
 * rewritten every frame because a warp that answers the music has to read it.
 */
internal class WarpRunner(field: String, private val drift: Float = 0f, private val params: FloatArray = FloatArray(4)) {

    private val program = ShaderProgram(ShaderLibrary.HEADER + PARAMS + field + MAIN)
    private val data = ShaderData()

    val available: Boolean get() = program.available

    val error: String? get() = program.error

    /**
     * Points the program at last frame's picture and this frame's music.
     *
     * [zoom] above one pulls the picture outward, below one drains it inward. [spin] turns it.
     * [decay] is how much survives, and the loop only stays stable while it stays under one.
     */
    fun prepare(
        previous: ImageBitmap,
        state: VizRenderState,
        width: Float,
        height: Float,
        zoomX: Float,
        zoomY: Float,
        spin: Float,
        amount: Float,
        decay: Float,
        shiftX: Float = 0f,
        shiftY: Float = 0f,
        centreX: Float = width / 2f,
        centreY: Float = height / 2f,
        copy: EchoCopy? = null,
    ): Boolean {
        if (!program.available) return false
        val frame = state.frame
        program.uniform("uResolution", width, height)
        program.uniform("uTime", state.timeSeconds)
        program.uniform("uMusicTime", state.musicTime)
        program.uniform("uDelta", state.deltaSeconds)
        program.uniform("uLevel", frame.levelRel)
        program.uniform("uBass", frame.bassRel)
        program.uniform("uMid", frame.midRel)
        program.uniform("uTreble", frame.trebleRel)
        program.uniform("uEnergy", frame.energy)
        program.uniform("uMood", frame.mood)
        program.uniform("uDrive", state.drive)
        program.uniform("uDensity", frame.density)
        program.uniform("uBeat", frame.beat)
        program.uniform("uPulse", frame.pulse)
        program.uniform("uKick", frame.kick)
        program.uniform("uSnare", frame.snare)
        program.uniform("uHat", frame.hat)
        program.uniform("uBarPhase", frame.barPhase)
        program.uniform("uPhrasePhase", frame.phrasePhase)
        program.uniform("uKeyHue", frame.keyHue * frame.keyConfidence)

        program.uniform("uWarpZoom", zoomX, zoomY)
        program.uniform("uWarpSpin", spin)
        program.uniform("uEchoShift", shiftX, shiftY)
        program.uniform("uEchoCentre", centreX, centreY)
        if (copy != null) {
            program.uniform(
                "uEchoCopy",
                copy.zoom.coerceAtLeast(0.05f),
                copy.angle,
                if (copy.mirrorX) -1f else 1f,
                if (copy.mirrorY) -1f else 1f,
            )
            program.uniform("uEchoCopyShare", copy.share.coerceIn(0f, 0.8f))
        } else {
            program.uniform("uEchoCopy", 1f, 0f, 1f, 1f)
            program.uniform("uEchoCopyShare", 0f)
        }
        program.uniform("uWarpAmount", amount)
        program.uniform("uWarpDecay", decay)
        program.uniform("uWarpDrift", drift * state.deltaSeconds)
        program.uniform("uWarpParams", params[0], params[1], params[2], params[3])

        data.writeBands(frame.bandsRel)
        data.writeScope(frame.scope)
        data.writePalette(state.palette)
        data.writeHistory(frame.bandsRel)
        data.bindTo(program)
        program.child("uPrevious", previous)
        return true
    }

    fun brush(): androidx.compose.ui.graphics.Brush? = program.brush()

    fun reset() {
        data.clearHistory()
    }

    private companion object {
        /** Declared ahead of the field, so the field can read it. */
        const val PARAMS = """
uniform float4 uWarpParams;
"""

        /**
         * The part every warp shares.
         *
         * Position is turned into the same centred, aspect corrected space the drawings work in,
         * moved by the preset's own field, and turned back into a place to read the old frame.
         */
        const val MAIN = """
uniform shader uPrevious;
uniform float2 uWarpZoom;
uniform float uWarpSpin;
uniform float uWarpAmount;
uniform float uWarpDecay;
uniform float uWarpDrift;
uniform float2 uEchoShift;
uniform float2 uEchoCentre;
uniform float4 uEchoCopy;
uniform float uEchoCopyShare;

// Turns the hue of a colour in OKLab, which moves the colour without changing how bright it looks.
half4 turnHue(half4 colour, float turn) {
    float alpha = colour.a;
    if (alpha <= 0.0 || turn == 0.0) return colour;
    float3 straight = pow(max(colour.rgb / alpha, float3(0.0)), float3(2.2));
    float3 cone = pow(max(float3(
        0.4122214708 * straight.r + 0.5363325363 * straight.g + 0.0514459929 * straight.b,
        0.2119034982 * straight.r + 0.6806995451 * straight.g + 0.1073969566 * straight.b,
        0.0883024619 * straight.r + 0.2817188376 * straight.g + 0.6299787005 * straight.b), float3(0.0)), float3(1.0 / 3.0));
    float light = 0.2104542553 * cone.x + 0.7936177850 * cone.y - 0.0040720468 * cone.z;
    float2 hue = float2(
        1.9779984951 * cone.x - 2.4285922050 * cone.y + 0.4505937099 * cone.z,
        0.0259040371 * cone.x + 0.7827717662 * cone.y - 0.8086757660 * cone.z);
    hue = rotate(hue, turn);
    float3 back = float3(
        light + 0.3963377774 * hue.x + 0.2158037573 * hue.y,
        light - 0.1055613458 * hue.x - 0.0638541728 * hue.y,
        light - 0.0894841775 * hue.x - 1.2914855480 * hue.y);
    back = back * back * back;
    float3 rgb = float3(
        4.0767416621 * back.x - 3.3077115913 * back.y + 0.2309699292 * back.z,
        -1.2684380046 * back.x + 2.6097574011 * back.y - 0.3413193965 * back.z,
        -0.0041960863 * back.x - 0.7034186147 * back.y + 1.7076147010 * back.z);
    rgb = pow(clamp(rgb, 0.0, 1.0), float3(1.0 / 2.2));
    return half4(rgb * alpha, alpha);
}

// The last frame at [source]. Off its edge there is nothing, rather than the edge smeared into streaks.
half4 readPrevious(float2 source) {
    if (source.x < 0.0 || source.y < 0.0 || source.x > uResolution.x || source.y > uResolution.y) {
        return half4(0.0);
    }
    return uPrevious.eval(source);
}

half4 main(float2 position) {
    float half_height = uResolution.y * 0.5;
    float2 uv = (position - uEchoShift - uEchoCentre) / half_height;

    // Read from where this pixel WAS, which means undoing the movement rather than doing it.
    uv = rotate(uv, -uWarpSpin);
    uv = uv / uWarpZoom;
    uv = uv + warpField(uv) * uWarpAmount;
    half4 colour = readPrevious(uv * half_height + uEchoCentre);

    // A second copy, turned and mirrored about the middle, out of the same budget.
    if (uEchoCopyShare > 0.0) {
        float2 turned = rotate((position - uResolution * 0.5) / half_height, -uEchoCopy.y) / uEchoCopy.x;
        half4 copy = readPrevious(turned * uEchoCopy.zw * half_height + uResolution * 0.5);
        colour = colour * (1.0 - uEchoCopyShare) + copy * uEchoCopyShare;
    }
    return turnHue(colour, uWarpDrift) * uWarpDecay;
}
"""
    }
}

/** Warp fields worth having. Each is one function, and each folds the picture a different way. */
@AudioVizAuthoringApi
public object WarpFields {

    /** Turns more the further out you go, so straight marks become spirals that tighten. */
    public const val TWIST: String = """
float2 warpField(float2 uv) {
    float reach = length(uv);
    float turn = (0.5 + 1.5 * uBass + uWarpParams.x) * reach;
    float2 turned = rotate(uv, turn);
    return (turned - uv) * 1.2;
}
"""

    /**
     * Rings travelling out from two wells, one ring a beat. The second well sits at uWarpParams.xy
     * from the first, as much as uWarpParams.z of it, and uWarpParams.w sets how close the rings
     * are. Where the two sets of rings cross they interfere.
     */
    public const val WELLS: String = """
float2 ringsFrom(float2 uv, float count) {
    float reach = max(length(uv), 0.001);
    return uv / reach * sin(reach * count - uBarPhase * 25.1327 - uMusicTime * 2.0);
}

float2 warpField(float2 uv) {
    float count = 14.0 * max(uWarpParams.w, 0.3);
    float2 push = ringsFrom(uv, count) + ringsFrom(uv - uWarpParams.xy, count) * uWarpParams.z;
    return push * (0.25 + 0.9 * uKick + 0.4 * uEnergy);
}
"""

    /**
     * Up to four lenses falling down the screen, each bending whatever passes behind it.
     * uWarpParams.x is how many, uWarpParams.y the fall clock and uWarpParams.z how strong.
     */
    public const val FALLING_LENSES: String = """
float2 lensAt(int index, float clock) {
    float along = float(index) / 4.0;
    float fall = fract(clock * (1.1 - along * 0.5) + along * 0.37);
    float aspect = uResolution.x / uResolution.y;
    return float2(sin(float(index) * 2.4 + clock * 0.7) * 0.8 * aspect, fall * 2.6 - 1.3);
}

float2 warpField(float2 uv) {
    float2 push = float2(0.0);
    for (int index = 0; index < 4; index++) {
        float on = clamp(uWarpParams.x - float(index), 0.0, 1.0);
        float2 away = uv - lensAt(index, uWarpParams.y);
        float reach = length(away);
        push += away * exp(-reach * reach * 6.0) * (0.4 + band(float(index) / 4.0)) * on;
    }
    return push * uWarpParams.z;
}
"""

    /** Rings travelling outward from the middle, pushed harder on every kick. */
    public const val RIPPLE: String = """
float2 warpField(float2 uv) {
    float reach = max(length(uv), 0.001);
    float wave = sin(reach * 14.0 - uMusicTime * 4.0 - uBarPhase * 6.2831);
    return uv / reach * wave * (0.25 + 0.9 * uKick + 0.4 * uEnergy);
}
"""

    /** A handful of lenses drifting about, each bending whatever passes behind it. */
    public const val LENS: String = """
float2 warpField(float2 uv) {
    float2 push = float2(0.0);
    for (int index = 0; index < 4; index++) {
        float along = float(index) / 4.0;
        float phase = uMusicTime * (0.3 + along * 0.4) + float(index) * 2.4;
        float2 at = float2(sin(phase) * 0.7, cos(phase * 0.8 + float(index)) * 0.55);
        float2 away = uv - at;
        float reach = length(away);
        float strength = exp(-reach * reach * 6.0) * (0.4 + band(along));
        push += away * strength;
    }
    return push;
}
"""

    /** The field read from noise, so the picture is dragged the way ink is dragged through water. */
    public const val FLOW: String = """
float2 warpField(float2 uv) {
    float2 at = uv * 1.4 + float2(uMusicTime * 0.05, uMusicTime * 0.03);
    float here = fbm(at);
    float across = fbm(at + float2(0.03, 0.0));
    float down = fbm(at + float2(0.0, 0.03));
    // Turned side on, which makes the flow circle rather than run straight downhill.
    return float2(-(down - here), across - here) * (14.0 + 30.0 * uEnergy);
}
"""

    /**
     * Folds the picture round the middle into six mirrored wedges, the way a kaleidoscope does.
     *
     * Each pixel reads from the matching point in the first wedge, so whatever is drawn anywhere is
     * repeated six times round the centre and refolded frame after frame. It keeps its distance from
     * the middle while it does it, so a centred drawing stays centred. Mirroring into one quarter
     * of the screen instead would drag any figure into a corner.
     */
    public const val FOLD: String = """
float2 warpField(float2 uv) {
    float reach = length(uv);
    float wedge = 6.2831 / 6.0;
    // One wedge per phrase, so the turn wraps round with the phrase without a visible jump.
    float angle = atan(uv.y, uv.x) + uPhrasePhase * wedge + uKeyHue;
    angle = abs(mod(angle, wedge) - wedge * 0.5);
    float2 folded = reach * float2(cos(angle), sin(angle));
    return (folded - uv) * (0.3 + 0.4 * uBass);
}
"""

    /**
     * The Julia set's own rule: square the position and add a constant.
     *
     * Fed back into itself every frame, it folds whatever is drawn into the shape of that Julia set,
     * and the shape into smaller copies of itself. The constant walks round the edge of the main body
     * of the Mandelbrot set, one place per key, so every key gives a set that holds together, and a
     * change of key changes the shape.
     */
    public const val JULIA: String = """
float2 warpField(float2 uv) {
    float turn = 6.2831 * uKeyHue + 0.25 * sin(uMusicTime * 0.07);
    float2 onEdge = 0.5 * float2(cos(turn), sin(turn)) - 0.25 * float2(cos(2.0 * turn), sin(2.0 * turn));
    float2 z = uv * 1.1;
    float2 folded = float2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + onEdge * 0.99;
    return folded / 1.1 - uv;
}
"""

    /**
     * The Julia rule with the constant handed in as uWarpParams.xy, so the drawing can walk it round
     * the edge of the Mandelbrot set and jump it from one bulb to another.
     */
    public const val JULIA_WALK: String = """
float2 warpField(float2 uv) {
    float2 z = uv * 1.1;
    float2 folded = float2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + uWarpParams.xy;
    return folded / 1.1 - uv;
}
"""

    /**
     * A swirling flow shaped partly by the spectrogram, so the music's own past steers the picture.
     *
     * The flow runs along the contours of a smooth landscape, which is what makes it swirl without
     * ever gathering into one place or leaving an empty patch. The landscape is slow noise with the
     * spectrogram laid over it: across the screen is the spectrum, low on the left, and distance from
     * the middle line is how long ago. Loud moments of the last few seconds become hills the flow
     * circles round.
     */
    public const val SPECTRO: String = """
float landscape(float2 uv) {
    float2 turned = rotate(uv, 0.6 + uWarpParams.x) * 1.1 + float2(0.0, uMusicTime * 0.03 + uWarpParams.y);
    float where = clamp(uv.x * 0.28 + 0.5, 0.0, 1.0);
    float age = clamp(abs(uv.y) * 0.9, 0.0, 1.0);
    return fbm(turned) + 0.35 * history(where, age);
}

float2 warpField(float2 uv) {
    float nudge = 0.03;
    float here = landscape(uv);
    float2 slope = float2(landscape(uv + float2(nudge, 0.0)) - here, landscape(uv + float2(0.0, nudge)) - here) / nudge;
    // Along the contours rather than down them.
    return float2(slope.y, -slope.x) * 0.8;
}
"""
}
