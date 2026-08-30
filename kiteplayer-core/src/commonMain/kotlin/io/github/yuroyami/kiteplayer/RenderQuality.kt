package io.github.yuroyami.kiteplayer

/**
 * How much work a renderer spends on the picture beyond decoding it correctly.
 *
 * These are the passes a viewer notices and that FFmpeg cannot do for us, because they belong on
 * the GPU next to the draw: removing the banding an 8-bit write creates, removing the banding the
 * source already carries, and scaling with something better than the sampler's bilinear.
 *
 * Every field's default is the behaviour the engine had before this type existed, and a renderer
 * reads [isNeutral] to skip the work entirely. That is deliberate and is the ladder's first law:
 * DISABLED IS BIT-EXACT. A build that turns nothing on writes the same pixels it always did, which
 * is what makes each pass measurable on its own and what keeps the colour instruments honest.
 *
 * Not every renderer can honour these. A renderer applies what it can and ignores the rest; the
 * engine neither asks nor promises. On Android the shipping interop path decodes straight to a
 * Surface with no shader of its own, so nothing here reaches it (17.21 records why).
 */
public data class RenderQuality(
    /**
     * Adds a tiny ordered pattern before the 8-bit write, so a smooth ramp stops collapsing into
     * visible steps.
     *
     * The cheapest pass on the ladder and the one with the least to argue about: the renderers
     * write `BGRA8Unorm`, and 10-bit sources arrive in 16-bit textures, so without this the extra
     * precision is thrown away by truncation at the very last instruction.
     */
    public val dither: Boolean = false,
    /**
     * Smooths banding the SOURCE carries, which dithering cannot touch because it is already in
     * the decoded samples.
     *
     * Works like mpv's: sample a small ring around each texel, and where the neighbourhood is flat
     * enough to be a band rather than an edge, replace the sample with its average and add a little
     * grain. Costs real texture taps, so it is the pass to measure before defaulting on.
     *
     * Pairs with [dither], and the pairing is not a suggestion: into an 8-bit target this pass can
     * only redistribute a hard step into a mixed transition, because there is no value between two
     * adjacent 8-bit levels for the smoothed result to land on. [RenderQuality.Standard] turns both
     * on for that reason.
     */
    public val deband: Boolean = false,
    /** How flat a neighbourhood must be to count as a band, in 1/16384 of full scale. mpv's 48. */
    public val debandThreshold: Float = 48f,
    /** How far the ring reaches, in source pixels, at the first iteration. mpv's 16. */
    public val debandRange: Float = 16f,
    /** Grain added back after smoothing, in 1/16384 of full scale. mpv's 48. */
    public val debandGrain: Float = 48f,
    /** Which kernel resamples the picture when it is not drawn at its own size. */
    public val scaler: VideoScaler = VideoScaler.Bilinear,
    /**
     * Scales in LIGHT-linear space rather than in the transfer curve's space.
     *
     * More correct, and visibly so on high-contrast edges, but it is the only rung that changes the
     * shape of the pipeline: it needs a linear intermediate between decode and draw rather than the
     * single pass the renderers do today.
     */
    public val linearLight: Boolean = false,
) {
    /** True for the value that reproduces the pre-17.21 pipeline exactly, byte for byte. */
    public val isNeutral: Boolean
        get() = !dither && !deband && scaler == VideoScaler.Bilinear && !linearLight

    public companion object {
        /** Everything off: what the engine did before the ladder, and the default. */
        public val Off: RenderQuality = RenderQuality()

        /**
         * The two cheap passes, which is what most devices should run.
         *
         * Deliberately NOT a scaler change: a kernel costs more than these two together and its
         * default belongs to a measurement per device class, not to a convenience constant.
         */
        public val Standard: RenderQuality = RenderQuality(dither = true, deband = true)
    }
}

/** The kernel a renderer resamples the picture with when it is not drawn at its own size. */
public enum class VideoScaler {
    /** The sampler's own filtering. One fetch, and what every renderer did before 17.21. */
    Bilinear,

    /**
     * Catmull-Rom bicubic, evaluated as four bilinear fetches rather than sixteen point fetches.
     *
     * The visible difference is on UPSCALES, which is the ordinary case on a phone: a 800p film on
     * a 1125 pixel tall screen is a 1.4x enlargement that bilinear renders soft.
     */
    CatmullRom,
}
