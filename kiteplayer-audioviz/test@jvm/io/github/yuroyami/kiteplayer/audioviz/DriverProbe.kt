package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders one drawing twice, with one driver moved the second time, and measures what changed.
 *
 * Both runs start from a reset drawing and read the same frames apart from that one driver, and
 * the drawings are deterministic, so every difference between the two pictures is the driver's
 * doing. That is what lets a declaration be checked rather than believed.
 */
internal object DriverProbe {
    const val WIDTH = 128
    const val HEIGHT = 80

    /**
     * A shader draws every pixel on the processor here rather than on the graphics card, so it
     * gets a smaller canvas. Every measure below is a share of the picture, so the size does not
     * change what is compared: both runs of one drawing use the same one.
     */
    const val SHADER_WIDTH = 64
    const val SHADER_HEIGHT = 40
    const val FRAMES = 156

    /** Frames before this are the drawing settling from its reset. */
    const val FROM = 24

    /** A difference this big is a response rather than rounding. Shares of full luma. *Judgement.* */
    const val NOTICED = 0.002f

    /**
     * One measured answer of a picture to one driver.
     *
     * Everything is measured after the driver moved. [before] covers the part of the run where the
     * two renders read the same frames, so it must be zero: it proves the renders are repeatable
     * and that the difference after the change belongs to the driver.
     */
    class Response(
        val before: Float,
        val difference: Float,
        val brightness: Float,
        val ink: Float,
        val motion: Float,
        val colour: Float,
        val texture: Float,
        /** The first step that differs from the unchanged run, or -1 when nothing did. */
        val startStep: Int,
    ) {
        /** The measure that belongs to [property], signed where the property has a direction. */
        fun of(property: VizProperty): Float = when (property) {
            VizProperty.Size -> ink
            VizProperty.Brightness -> brightness
            VizProperty.Colour -> colour
            VizProperty.Speed -> motion
            VizProperty.Texture -> texture
            VizProperty.Shape, VizProperty.Spawn, VizProperty.Camera, VizProperty.Cut -> difference
        }
    }

    /** A fresh copy of the drawing at [index] in the catalogue. */
    fun drawing(index: Int) = VizCatalog.create()[index]

    /** Every frame of one run, from [FROM] onwards, as pixels. */
    fun run(index: Int, driver: VizDriver?, strength: Float = InjectedFrames.HIT_STRENGTH,
        confidence: Float = InjectedFrames.HIT_CONFIDENCE): Run {
        val drawing = drawing(index)
        val shader = drawing is ShaderPreset
        val width = if (shader) SHADER_WIDTH else WIDTH
        val height = if (shader) SHADER_HEIGHT else HEIGHT
        val frames = ArrayList<IntArray>(FRAMES - FROM)
        RenderHarness.forEachFrameOf(
            drawing, width, height, FRAMES, VizPalette.Prism,
            source = { step -> InjectedFrames.frame(driver, step, strength, confidence) },
        ) { bitmap, step ->
            if (step >= FROM) {
                val pixels = IntArray(width * height)
                bitmap.readPixels(pixels)
                frames += pixels
            }
        }
        return Run(frames, width, height)
    }

    /** One rendered run and the size it was drawn at. */
    class Run(val frames: List<IntArray>, val width: Int, val height: Int)

    /** What the variant run did that the baseline did not. */
    fun compare(baseRun: Run, variantRun: Run): Response {
        val base = baseRun.frames
        val variant = variantRun.frames
        require(base.size == variant.size && base.isNotEmpty())
        require(baseRun.width == variantRun.width && baseRun.height == variantRun.height)
        val width = baseRun.width
        val height = baseRun.height
        var before = 0f
        var difference = 0f
        var colour = 0f
        var baseLuma = 0f
        var variantLuma = 0f
        var baseInk = 0f
        var variantInk = 0f
        var baseMotion = 0f
        var variantMotion = 0f
        var baseTexture = 0f
        var variantTexture = 0f
        var start = -1
        var after = 0
        for (index in base.indices) {
            val step = index + FROM
            val a = base[index]
            val b = variant[index]
            val frame = meanLumaDifference(a, b)
            if (step < InjectedFrames.STEP_AT) {
                before = maxOf(before, frame)
                continue
            }
            if (start < 0 && frame > NOTICED) start = step
            after++
            difference += frame
            colour += meanColourDifference(a, b)
            baseLuma += meanLuma(a)
            variantLuma += meanLuma(b)
            baseInk += ink(a)
            variantInk += ink(b)
            baseTexture += texture(a, width, height)
            variantTexture += texture(b, width, height)
            if (index > 0) {
                baseMotion += meanLumaDifference(base[index - 1], a)
                variantMotion += meanLumaDifference(variant[index - 1], b)
            }
        }
        val frames = after.coerceAtLeast(1).toFloat()
        val pairs = (after - 1).coerceAtLeast(1).toFloat()
        return Response(
            before = before,
            difference = difference / frames,
            brightness = (variantLuma - baseLuma) / frames,
            ink = (variantInk - baseInk) / frames,
            motion = (variantMotion - baseMotion) / pairs,
            colour = colour / frames,
            texture = (variantTexture - baseTexture) / frames,
            startStep = start,
        )
    }

    private fun luma(pixel: Int): Float {
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun meanLuma(pixels: IntArray): Float {
        var sum = 0f
        for (pixel in pixels) sum += luma(pixel)
        return sum / pixels.size
    }

    private fun meanLumaDifference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    /** Mean distance between the two pictures in the red against green and blue against yellow axes. */
    private fun meanColourDifference(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) {
            val pa = a[index]
            val pb = b[index]
            val ar = (pa shr 16 and 0xFF) / 255f
            val ag = (pa shr 8 and 0xFF) / 255f
            val ab = (pa and 0xFF) / 255f
            val br = (pb shr 16 and 0xFF) / 255f
            val bg = (pb shr 8 and 0xFF) / 255f
            val bb = (pb and 0xFF) / 255f
            val dx = (ar - ag) - (br - bg)
            val dy = (ab - (ar + ag) / 2f) - (bb - (br + bg) / 2f)
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / a.size
    }

    /** The share of the picture away from the darkest fifth, which stands for how much is drawn. */
    private fun ink(pixels: IntArray): Float {
        var count = 0
        for (pixel in pixels) if (luma(pixel) > INK_LEVEL) count++
        return count.toFloat() / pixels.size
    }

    /** How much fine detail the picture holds, as the mean of a four-neighbour difference. */
    private fun texture(pixels: IntArray, width: Int, height: Int): Float {
        var sum = 0f
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val at = y * width + x
                val centre = luma(pixels[at]) * 4f
                sum += abs(centre - luma(pixels[at - 1]) - luma(pixels[at + 1]) -
                    luma(pixels[at - width]) - luma(pixels[at + width]))
            }
        }
        return sum / ((width - 2) * (height - 2))
    }

    /** Above this a pixel counts as drawn rather than background. */
    private const val INK_LEVEL = 0.12f
}
