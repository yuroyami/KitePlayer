package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AnimationUpscaler

/*
 * The animation upscaler's networks (#67): Anime4K v3.2's Upscale CNN x2 S and M, by bloc97, under
 * the MIT licence. The weights are in Anime4kNetworks.kt, generated from the original mpv shaders.
 *
 * Both networks have the same shape. A run of convolutions at the source's own size, each writing
 * four channels, and then one depth-to-space step that doubles the picture: every output pixel is
 * the source enlarged with bilinear plus one of the last convolution's four channels, added to red,
 * green and blue alike. The network learns only the detail that bilinear misses, which is why a
 * flat area comes out exactly as bilinear would draw it.
 *
 * Everything here is in the original's picture space: row 0 is the TOP of the picture and a tap one
 * row down is dy = +1. The GL passes keep that space in their textures, so the generated shaders
 * read the weights exactly as mpv does.
 */

/** Which half of a convolution's input a term reads. */
internal enum class Anime4kPart {
    /** The picture itself, as the first convolution reads it. */
    Raw,

    /** max(x, 0): the positive half of the previous layer, the first half of its CReLU. */
    Positive,

    /** max(-x, 0): the negative half, the second half of its CReLU. */
    Negative,
}

/**
 * One term of a convolution's sum: a 4x4 matrix times one tap of one input.
 *
 * [matrix] is the original's decimal text for the sixteen values, column after column as GLSL
 * reads a `mat4`, so a generated shader carries exactly the original's numbers.
 */
internal class Anime4kTerm(
    /** [SOURCE] for the picture, otherwise the index of an earlier layer. */
    val input: Int,
    val part: Anime4kPart,
    val dx: Int,
    val dy: Int,
    val matrix: String,
) {
    /** The sixteen values, column-major. */
    val values: FloatArray = parseFloats(matrix, 16)

    companion object {
        const val SOURCE: Int = -1
    }
}

/** One convolution: the sum of its terms in the original's order, then its bias. */
internal class Anime4kLayer(val terms: List<Anime4kTerm>, val bias: String) {
    val biasValues: FloatArray = parseFloats(bias, 4)

    /** What this layer reads, in the order its shader binds them. */
    val inputs: List<Int> = terms.map { it.input }.distinct()

    /** The distinct taps, each fetched once, in the order the sum first needs them. */
    val taps: List<Triple<Int, Int, Int>> = terms.map { Triple(it.input, it.dx, it.dy) }.distinct()
}

internal class Anime4kNetwork(val name: String, val layers: List<Anime4kLayer>) {
    init {
        require(layers.isNotEmpty()) { "$name has no layers" }
        layers.forEachIndexed { index, layer ->
            require(layer.terms.isNotEmpty()) { "$name layer $index has no terms" }
            layer.terms.forEach { term ->
                require(term.input == Anime4kTerm.SOURCE || term.input in 0 until index) {
                    "$name layer $index reads ${term.input}, which is not an earlier layer"
                }
                require(term.dx in -1..1 && term.dy in -1..1) { "$name layer $index taps outside 3x3" }
                require((term.input == Anime4kTerm.SOURCE) == (term.part == Anime4kPart.Raw)) {
                    "$name layer $index reads the picture through a CReLU or a layer without one"
                }
            }
        }
    }

    /** The index of the last layer that reads each layer, or the layer count for the last one. */
    val lastReaders: IntArray = IntArray(layers.size) { produced ->
        if (produced == layers.lastIndex) {
            layers.size
        } else {
            layers.indices.last { reader -> produced in layers[reader].inputs }
        }
    }

    companion object {
        fun of(upscaler: AnimationUpscaler): Anime4kNetwork? = when (upscaler) {
            AnimationUpscaler.Off -> null
            AnimationUpscaler.Fast -> Anime4kNetworks.small
            AnimationUpscaler.Quality -> Anime4kNetworks.medium
        }

        /**
         * The original's own condition, `OUTPUT.w / MAIN.w > 1.2` and the same for the heights:
         * below that the network would cost every frame and change little.
         */
        fun runsAt(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Boolean =
            sourceWidth > 0 && sourceHeight > 0 &&
                outputWidth.toDouble() / sourceWidth > 1.2 &&
                outputHeight.toDouble() / sourceHeight > 1.2
    }
}

/** The networks as GLSL ES 1.00, the language a GLES2 context and WebGL 1 both compile. */
internal object Anime4kGlsl {
    /**
     * Every pass draws one quad over its whole target. `vPos` runs 0 to 1 across it, so at a
     * fragment it is that texel's centre, and texture row 0 is the picture's top row.
     */
    const val VERTEX_SHADER: String = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vPos;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vPos = aTexCoord;
        }
    """

    /** The name of the sampler a convolution reads its [n]th input through. */
    fun inputSampler(n: Int): String = "uInput$n"

    /**
     * One convolution. Its inputs are bound to [inputSampler] in [Anime4kLayer.inputs] order, all
     * at the source's size, and `uPt` is one texel. The sum runs in the original's order with the
     * bias last, and each tap is fetched once however many terms read it.
     */
    fun convolution(layer: Anime4kLayer): String = buildString {
        append("precision highp float;\n")
        layer.inputs.indices.forEach { append("uniform sampler2D ${inputSampler(it)};\n") }
        append("uniform vec2 uPt;\n")
        append("varying vec2 vPos;\n")
        append("void main() {\n")
        layer.taps.forEachIndexed { index, (input, dx, dy) ->
            val sampler = inputSampler(layer.inputs.indexOf(input))
            val at = if (dx == 0 && dy == 0) "vPos" else "vPos + vec2(${dx}.0, ${dy}.0) * uPt"
            append("    vec4 t$index = texture2D($sampler, $at);\n")
        }
        layer.terms.forEachIndexed { index, term ->
            val tap = "t" + layer.taps.indexOf(Triple(term.input, term.dx, term.dy))
            val value = when (term.part) {
                Anime4kPart.Raw -> tap
                Anime4kPart.Positive -> "max($tap, 0.0)"
                Anime4kPart.Negative -> "max(-$tap, 0.0)"
            }
            val target = if (index == 0) "vec4 result =" else "result +="
            append("    $target mat4(${term.matrix}) * $value;\n")
        }
        append("    result += vec4(${layer.bias});\n")
        append("    gl_FragColor = result;\n")
        append("}\n")
    }

    /**
     * The step that doubles the picture. `uSource` is the picture, sampled with bilinear, and
     * `uLast` the last convolution at the source's size, `uLastSize` texels across.
     *
     * The original picks the channel with `vec4[i0.y * 2 + i0.x]`. GLSL ES 1.00 only promises a
     * constant index in a fragment shader, so the channel is picked by a dot product with a mask of
     * ones and zeros, which is exact. The original also adds the detail to alpha; the picture here
     * is opaque, so alpha stays one.
     */
    const val DEPTH_TO_SPACE: String = """
        precision highp float;
        uniform sampler2D uSource;
        uniform sampler2D uLast;
        uniform vec2 uLastSize;
        varying vec2 vPos;
        void main() {
            vec2 f0 = fract(vPos * uLastSize);
            vec2 i0 = floor(f0 * 2.0);
            vec4 detail = texture2D(uLast, (vec2(0.5) - f0) / uLastSize + vPos);
            vec4 pick = vec4((1.0 - i0.x) * (1.0 - i0.y), i0.x * (1.0 - i0.y), (1.0 - i0.x) * i0.y, i0.x * i0.y);
            gl_FragColor = vec4(texture2D(uSource, vPos).rgb + dot(detail, pick), 1.0);
        }
    """
}

/**
 * The networks on the CPU, in Float, as mpv runs the original shaders: taps clamp at the edge, the
 * enlargement under the detail is bilinear at the doubled picture's texel centres, and the sum of
 * each convolution runs in the original's order.
 *
 * This is the oracle the GL passes are tested against, on the host and on a device. It is not a
 * fallback: a CPU doing this work for every frame could never keep up with video.
 */
internal object Anime4kReference {
    /**
     * Doubles [rgba], [width] by [height] pixels of four floats each with row 0 at the top, through
     * [network]. The result is 2 [width] by 2 [height] pixels in the same layout, alpha one, and is
     * not clamped: the detail can carry a value a little past 0 or 1, as it does on the GPU.
     */
    fun upscale(network: Anime4kNetwork, rgba: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && rgba.size == width * height * 4) {
            "a ${width}x$height picture needs ${width * height * 4} floats"
        }
        val outputs = ArrayList<FloatArray>(network.layers.size)
        for (layer in network.layers) {
            outputs += convolve(layer, rgba, outputs, width, height)
        }
        val last = outputs.last()
        val outWidth = width * 2
        val outHeight = height * 2
        val result = FloatArray(outWidth * outHeight * 4)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val detail = last[((y / 2) * width + x / 2) * 4 + (y % 2) * 2 + x % 2]
                val at = (y * outWidth + x) * 4
                for (c in 0 until 3) {
                    result[at + c] = bilinearAtDoubledCentre(rgba, width, height, x, y, c) + detail
                }
                result[at + 3] = 1f
            }
        }
        return result
    }

    private fun convolve(
        layer: Anime4kLayer,
        source: FloatArray,
        outputs: List<FloatArray>,
        width: Int,
        height: Int,
    ): FloatArray {
        val out = FloatArray(width * height * 4)
        val sum = FloatArray(4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                sum.fill(0f)
                for (term in layer.terms) {
                    val input = if (term.input == Anime4kTerm.SOURCE) source else outputs[term.input]
                    val tx = (x + term.dx).coerceIn(0, width - 1)
                    val ty = (y + term.dy).coerceIn(0, height - 1)
                    val at = (ty * width + tx) * 4
                    val m = term.values
                    for (row in 0 until 4) {
                        var dot = 0f
                        for (column in 0 until 4) {
                            val v = input[at + column]
                            val part = when (term.part) {
                                Anime4kPart.Raw -> v
                                Anime4kPart.Positive -> maxOf(v, 0f)
                                Anime4kPart.Negative -> maxOf(-v, 0f)
                            }
                            dot += m[column * 4 + row] * part
                        }
                        sum[row] += dot
                    }
                }
                val at = (y * width + x) * 4
                for (row in 0 until 4) out[at + row] = sum[row] + layer.biasValues[row]
            }
        }
        return out
    }

    /** The source at the centre of doubled pixel ([x], [y]): a quarter and three quarters. */
    private fun bilinearAtDoubledCentre(
        rgba: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        channel: Int,
    ): Float {
        val u = (x + 0.5f) / 2f - 0.5f
        val v = (y + 0.5f) / 2f - 0.5f
        val x0 = kotlin.math.floor(u).toInt()
        val y0 = kotlin.math.floor(v).toInt()
        val fx = u - x0
        val fy = v - y0
        fun at(px: Int, py: Int): Float =
            rgba[(py.coerceIn(0, height - 1) * width + px.coerceIn(0, width - 1)) * 4 + channel]
        val top = at(x0, y0) * (1f - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1f - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1f - fy) + bottom * fy
    }
}

private fun parseFloats(text: String, count: Int): FloatArray {
    val values = text.split(",").map { it.trim().toFloat() }
    require(values.size == count) { "expected $count numbers, got ${values.size}" }
    return values.toFloatArray()
}
