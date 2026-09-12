package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.time.TimeSource

/**
 * The finishing pass laid over a drawing: light spilling out of the bright parts, colour fringes
 * towards the edges, darker corners, a little grain, and for a few drawings the lines of an old screen.
 *
 * Bloom is the one that matters. A real camera, and a real eye, sees a bright light bleed into what
 * is around it, and a picture without that looks like a diagram of light rather than light. It is
 * why the same drawing looks cheap on one screen and expensive on another.
 *
 * The amounts are fractions: 0 is off. [bloomRadius] is a share of the shorter side of the screen,
 * and [threshold] is how bright a part has to be before it spills at all. [glitch] only ever does
 * anything in the second after a drop.
 */
@AudioVizAuthoringApi
public data class PostSpec(
    public val bloom: Float = 0.8f,
    public val bloomRadius: Float = 0.04f,
    public val threshold: Float = 0.4f,
    public val vignette: Float = 0.28f,
    public val grain: Float = 0.012f,
    /** Whether the picture tears for a moment when a drop lands. Nothing happens at any other time. */
    public val glitch: Boolean = true,
    /**
     * How far red and blue separate at the edges of the picture, as a share of the way out from the
     * middle, the way a cheap lens splits colour. The treble and the kick widen it.
     */
    public val aberration: Float = 0.0025f,
    /** How dark the lines of an old screen are, 0 for none. Only drawings made to look like one use it. */
    public val scanlines: Float = 0f,
) {
    internal val isOff: Boolean
        get() = bloom <= 0f && vignette <= 0f && grain <= 0f && !glitch && aberration <= 0f && scanlines <= 0f

    public companion object {
        /** What most drawings get. */
        public val Default: PostSpec = PostSpec()

        /** For drawings that already glow through the feedback loop, so the two do not stack up. */
        public val Soft: PostSpec = PostSpec(bloom = 0.4f, bloomRadius = 0.045f, threshold = 0.5f)

        /** An old screen: the usual pass with faint lines across it. */
        public val Retro: PostSpec = PostSpec(scanlines = 0.3f)

        /** Nothing at all. */
        public val Off: PostSpec = PostSpec(bloom = 0f, vignette = 0f, grain = 0f, glitch = false, aberration = 0f)

        // The calm drawings never tear. A glitch under a piano would be a fault, not a moment.
        internal val DefaultCalm: PostSpec = Default.copy(glitch = false)
        internal val SoftCalm: PostSpec = Soft.copy(glitch = false)
    }
}

/**
 * Parts of the finishing pass a person can switch off for every drawing at once, whatever each
 * drawing asks for.
 */
public data class PostSwitches(
    public val bloom: Boolean = true,
    public val aberration: Boolean = true,
    public val vignette: Boolean = true,
    public val grain: Boolean = true,
    public val glitch: Boolean = true,
    public val scanlines: Boolean = true,
) {
    public companion object {
        /** Everything as the drawings ask for it. */
        public val All: PostSwitches = PostSwitches()
    }
}

/** This spec with whatever [switches] turns off taken out. */
internal fun PostSpec.masked(switches: PostSwitches): PostSpec =
    if (switches == PostSwitches.All) {
        this
    } else {
        copy(
            bloom = if (switches.bloom) bloom else 0f,
            aberration = if (switches.aberration) aberration else 0f,
            vignette = if (switches.vignette) vignette else 0f,
            grain = if (switches.grain) grain else 0f,
            glitch = glitch && switches.glitch,
            scanlines = if (switches.scanlines) scanlines else 0f,
        )
    }

/**
 * Whether this device can blur a layer.
 *
 * Android only gained layer effects in version 12. Below that a blur request is ignored, and a
 * brightened copy added on top without the blur is just a harsher picture, so bloom is left out.
 */
internal expect val blurAvailable: Boolean

/**
 * Draws [content] and then finishes it according to [spec].
 *
 * The drawing is recorded once. The normal finishing pass rasterizes that recording once and shares
 * the result between its effects. Replaying the recording independently for each glow and colour
 * channel would run full-screen scene shaders again for every pass.
 *
 * The glow keeps only highlights above the threshold and spreads them over four widths. On Skia,
 * successive levels use smaller images with filtering between reductions to preserve tiny sparks.
 * A kick makes the glow flare. Older Android devices and the brief tearing effect use separate layers.
 */
@Composable
internal fun PostProcessedBox(
    spec: () -> PostSpec,
    frame: () -> SpectrumFrame,
    modifier: Modifier = Modifier,
    stats: RenderStats? = null,
    content: @Composable () -> Unit,
) {
    val scene = rememberGraphicsLayer()
    val bright = rememberGraphicsLayer()
    val levels = List(BLOOM_LEVELS) { rememberGraphicsLayer() }
    val fringe = List(3) { rememberGraphicsLayer() }
    val redSplit = rememberGraphicsLayer()
    val blueSplit = rememberGraphicsLayer()
    val grain = remember { lazy { ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated)) } }
    val lines = remember { lazy { ShaderBrush(ImageShader(scanlineTile(), TileMode.Repeated, TileMode.Repeated)) } }
    val random = remember { Rng(4_096L) }
    val filters = remember { HashMap<Float, ColorFilter>() }
    val hold = remember { Hold() }
    val effect = remember { lazy { ScenePostEffect() } }
    DisposableEffect(effect) { onDispose { if (effect.isInitialized()) effect.value.close() } }

    Box(
        modifier.drawWithContent {
            val post = spec()
            if (post.isOff) {
                drawContent()
                return@drawWithContent
            }
            val current = frame()
            // The moment a drop lands the picture sticks for two frames, the way a signal catches,
            // and then it tears.
            if (!(post.glitch && hold.holding(current.dropPulse))) {
                scene.record { this@drawWithContent.drawContent() }
                hold.recorded = true
            }
            val started = if (stats != null) TimeSource.Monotonic.markNow() else null

            val split = post.aberration * (0.25f + 0.75f * current.trebleRel + current.kick)
            val strength = (post.bloom * (0.6f + 0.6f * current.energy + 0.5f * current.kick)).coerceIn(0f, 1f)
            val tearing = post.glitch && current.dropPulse > 0.02f
            val combined = if (!tearing && (post.bloom > 0f || split > MIN_SPLIT)) {
                effect.value.prepare(post, size.width, size.height, strength, if (split > MIN_SPLIT) split else 0f)
            } else null
            scene.renderEffect = combined
            if (combined != null) {
                drawLayer(scene)
            } else {
                if (split > MIN_SPLIT) drawFringed(scene, fringe, split) else drawLayer(scene)
            }

            // The tear. Only in the second after a drop, and fading with it: the red and blue parts
            // of the picture pulled apart, and a few strips slid sideways the way a signal breaks.
            if (post.glitch && current.dropPulse > 0.02f) {
                val amount = current.dropPulse.coerceIn(0f, 1f)
                val shift = size.width * 0.012f * amount
                redSplit.colorFilter = RED_ONLY
                redSplit.blendMode = BlendMode.Plus
                redSplit.alpha = 0.55f * amount
                redSplit.record { translate(shift, 0f) { drawLayer(scene) } }
                drawLayer(redSplit)
                blueSplit.colorFilter = BLUE_ONLY
                blueSplit.blendMode = BlendMode.Plus
                blueSplit.alpha = 0.55f * amount
                blueSplit.record { translate(-shift, 0f) { drawLayer(scene) } }
                drawLayer(blueSplit)
                for (strip in 0 until TORN_STRIPS) {
                    val top = random.next() * size.height
                    val tall = size.height * (0.015f + 0.05f * random.next())
                    val slide = random.signed() * size.width * 0.06f * amount
                    clipRect(0f, top, size.width, top + tall) {
                        translate(slide, 0f) { drawLayer(scene) }
                    }
                }
            }

            if (combined == null && post.bloom > 0f && blurAvailable) {
                bright.colorFilter = filters.getOrPut(post.threshold) { thresholdFilter(post.threshold) }
                bright.record { drawLayer(scene) }
                // Four widths, each twice the last and half as strong. One blur reads as a soft edge;
                // a pyramid reads as light, because that is roughly how a lens scatters it.
                var radius = (size.minDimension * post.bloomRadius).coerceAtLeast(1f)
                var weight = FIRST_WEIGHT
                for (level in levels) {
                    level.renderEffect = BlurEffect(null, radius, radius, TileMode.Decal)
                    level.blendMode = BlendMode.Plus
                    level.alpha = (strength * weight).coerceIn(0f, 1f)
                    level.record { drawLayer(bright) }
                    drawLayer(level)
                    radius *= 2f
                    weight *= 0.5f
                }
            }

            if (post.vignette > 0f) {
                drawRect(
                    Brush.radialGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = post.vignette.coerceIn(0f, 1f)),
                        center = center,
                        radius = size.maxDimension * 0.72f,
                    ),
                )
            }

            if (post.scanlines > 0f) {
                drawRect(lines.value, alpha = post.scanlines.coerceIn(0f, 1f), blendMode = BlendMode.Multiply)
            }

            if (post.grain > 0f) {
                // Moved to a new place every frame, so the grain crawls the way film does instead of
                // sitting still like a dirty screen.
                val shiftX = -random.next() * GRAIN_SIZE
                val shiftY = -random.next() * GRAIN_SIZE
                translate(shiftX, shiftY) {
                    drawRect(
                        brush = grain.value,
                        size = Size(size.width + GRAIN_SIZE, size.height + GRAIN_SIZE),
                        alpha = (post.grain * 6f).coerceIn(0f, 1f),
                        blendMode = BlendMode.Overlay,
                    )
                }
            }
            if (started != null) stats?.addPost(started.elapsedNow().inWholeMicroseconds / 1000f)
        },
    ) {
        content()
    }
}

/**
 * The picture with its red pushed outward from the middle and its blue pulled in.
 *
 * Green goes down first, then red and blue are added on top, each scaled slightly around the middle.
 * At the centre nothing moves; towards the edges the colours part, which is what a lens does.
 */
private fun DrawScope.drawFringed(scene: GraphicsLayer, passes: List<GraphicsLayer>, amount: Float) {
    val green = passes[0]
    green.colorFilter = GREEN_ONLY
    green.record { drawLayer(scene) }
    drawLayer(green)
    val red = passes[1]
    red.colorFilter = RED_ONLY
    red.blendMode = BlendMode.Plus
    red.record { scale(1f + amount, center) { drawLayer(scene) } }
    drawLayer(red)
    val blue = passes[2]
    blue.colorFilter = BLUE_ONLY
    blue.blendMode = BlendMode.Plus
    blue.record { scale(1f - amount, center) { drawLayer(scene) } }
    drawLayer(blue)
}

/** Counts the two frames the picture sticks for when a drop lands. */
private class Hold {
    var recorded = false
    private var left = 0
    private var armed = true

    fun holding(dropPulse: Float): Boolean {
        if (dropPulse > 0.95f && armed && recorded) {
            armed = false
            left = HOLD_FRAMES
        }
        if (dropPulse < 0.5f) armed = true
        if (left <= 0) return false
        left--
        return true
    }
}

/**
 * Keeps only the part of the picture brighter than [threshold], stretched back to full range.
 *
 * A straight line through the colour values: everything below the threshold lands below zero and
 * is clamped away, and the threshold itself maps to black while full white stays full white.
 */
internal fun thresholdFilter(threshold: Float): ColorFilter {
    val cut = threshold.coerceIn(0f, 0.95f)
    val gain = 1f / (1f - cut)
    // The last column is an offset measured on the 0 to 255 scale, which is the convention here.
    val shift = -cut * gain * 255f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, shift,
                0f, gain, 0f, 0f, shift,
                0f, 0f, gain, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/** A small square of random grey, tiled across the screen for the grain. Built once. */
private fun grainTile(): ImageBitmap {
    val tile = ImageBitmap(GRAIN_SIZE, GRAIN_SIZE)
    val random = Rng(1_234L)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(tile),
        Size(GRAIN_SIZE.toFloat(), GRAIN_SIZE.toFloat()),
    ) {
        for (y in 0 until GRAIN_SIZE) {
            for (x in 0 until GRAIN_SIZE) {
                val grey = random.next()
                drawRect(Color(grey, grey, grey), Offset(x.toFloat(), y.toFloat()), Size(1f, 1f))
            }
        }
    }
    return tile
}

/** Three rows, the last one dark, tiled down the screen for the lines of an old tube. */
private fun scanlineTile(): ImageBitmap {
    val tile = ImageBitmap(1, 3)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(tile), Size(1f, 3f)) {
        drawRect(Color.White, Offset.Zero, Size(1f, 2f))
        drawRect(Color(0.45f, 0.45f, 0.45f), Offset(0f, 2f), Size(1f, 1f))
    }
    return tile
}

private const val GRAIN_SIZE = 128

/** How many strips of the picture slide sideways when it tears. */
private const val TORN_STRIPS = 5

/** How many frames the picture sticks for when a drop lands. */
private const val HOLD_FRAMES = 2

/** How many widths of glow are added, and how strong the tightest is. Each next is half as strong. */
private const val BLOOM_LEVELS = 4
private const val FIRST_WEIGHT = 0.8f

/** Below this the colour fringe moves nothing by a visible amount, so the plain picture is drawn. */
private const val MIN_SPLIT = 0.0004f

/** Only the red of the picture. */
private val RED_ONLY: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)

/** Only the green of the picture. */
private val GREEN_ONLY: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)

/** Only the blue of the picture. */
private val BLUE_ONLY: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)
