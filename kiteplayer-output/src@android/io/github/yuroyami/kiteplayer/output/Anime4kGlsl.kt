package io.github.yuroyami.kiteplayer.output

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
