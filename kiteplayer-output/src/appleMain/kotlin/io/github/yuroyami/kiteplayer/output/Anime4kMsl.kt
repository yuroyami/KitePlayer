package io.github.yuroyami.kiteplayer.output

/**
 * The networks as Metal Shading Language (#421), generated from the same weights as
 * `Anime4kGlsl` on Android. [source] is one network's passes, compiled at runtime through the
 * Metal API when a picture first needs them.
 *
 * Every pass draws one quad over its whole target through `kp_vertex`, so `in.texcoord` at a
 * fragment is that texel's centre and texture row 0 is the picture's top row.
 */
internal object Anime4kMsl {
    /** The fragment function that doubles the picture. */
    const val DEPTH_TO_SPACE_NAME: String = "kp_upscale_double"

    /** The fragment function of [network]'s convolution number [layer]. */
    fun convolutionName(network: Anime4kNetwork, layer: Int): String = when {
        network === Anime4kNetworks.small -> "kp_upscale_s$layer"
        network === Anime4kNetworks.medium -> "kp_upscale_m$layer"
        else -> error("${network.name} has no Metal passes")
    }

    /**
     * One convolution, as a fragment function called [name]. Its inputs are textures 0 and up in
     * [Anime4kLayer.inputs] order, all at the source's size. The sum runs in the original's order
     * with the bias last, and each tap is fetched once however many terms read it.
     *
     * A tap is a texel offset on a nearest sampler that clamps at the edge, so it reads exactly one
     * texel, as the original's `texOff` does.
     */
    fun convolution(name: String, layer: Anime4kLayer): String = buildString {
        append("fragment float4 $name(\n    VertexOut in [[stage_in]]")
        layer.inputs.indices.forEach { append(",\n    texture2d<float> input$it [[texture($it)]]") }
        append("\n) {\n")
        append("    constexpr sampler s(mag_filter::nearest, min_filter::nearest, address::clamp_to_edge);\n")
        layer.taps.forEachIndexed { index, (input, dx, dy) ->
            val texture = "input" + layer.inputs.indexOf(input)
            val offset = if (dx == 0 && dy == 0) "" else ", int2($dx, $dy)"
            append("    float4 t$index = $texture.sample(s, in.texcoord$offset);\n")
        }
        layer.terms.forEachIndexed { index, term ->
            val tap = "t" + layer.taps.indexOf(Triple(term.input, term.dx, term.dy))
            val value = when (term.part) {
                Anime4kPart.Raw -> tap
                Anime4kPart.Positive -> "max($tap, 0.0)"
                Anime4kPart.Negative -> "max(-$tap, 0.0)"
            }
            val target = if (index == 0) "float4 result =" else "result +="
            append("    $target ${matrix(term)} * $value;\n")
        }
        append("    result += float4(${layer.bias});\n")
        append("    return result;\n")
        append("}\n")
    }

    /**
     * [term]'s matrix as four columns, which is how both languages read the sixteen values. The
     * numbers keep the original's decimal text.
     */
    fun matrix(term: Anime4kTerm): String =
        term.matrix.split(",").map { it.trim() }.chunked(4)
            .joinToString(", ", "float4x4(", ")") { "float4(${it.joinToString(", ")})" }

    /**
     * The step that doubles the picture. `source` is the picture, sampled with bilinear, and
     * `last` the last convolution at the source's size.
     *
     * Each texel of `last` holds the detail of the four doubled pixels it covers, top left first,
     * so the pixel's own position picks the texel and the channel. The original also adds the
     * detail to alpha. The picture here is opaque, so alpha stays one.
     */
    const val DEPTH_TO_SPACE: String = """
fragment float4 kp_upscale_double(
    VertexOut in [[stage_in]],
    texture2d<float> source [[texture(0)]],
    texture2d<float> last [[texture(1)]]
) {
    constexpr sampler s(mag_filter::linear, min_filter::linear, address::clamp_to_edge);
    uint2 at = uint2(in.position.xy);
    float detail = last.read(at / 2u)[(at.y & 1u) * 2u + (at.x & 1u)];
    return float4(source.sample(s, in.texcoord).rgb + detail, 1.0);
}
"""

    /** What `kp_vertex` hands a fragment, word for word as `METAL_SHADER_SOURCE` declares it. */
    const val VERTEX_OUT: String = """struct VertexOut {
    float4 position [[position]];
    float2 texcoord;
};"""

    /** Every pass of [network] and the doubling, as one library. */
    fun source(network: Anime4kNetwork): String = buildString {
        append("#include <metal_stdlib>\nusing namespace metal;\n\n")
        append(VERTEX_OUT)
        append('\n')
        network.layers.forEachIndexed { index, layer ->
            append('\n')
            append(convolution(convolutionName(network, index), layer))
        }
        append(DEPTH_TO_SPACE)
    }
}
