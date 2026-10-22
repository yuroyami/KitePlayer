package io.github.yuroyami.kiteplayer

/**
 * How much work a renderer spends on the picture beyond decoding it correctly.
 *
 * These are the passes a viewer notices and that FFmpeg cannot do for us, because they belong on
 * the GPU next to the draw: removing the banding an 8-bit write creates, removing the banding the
 * source already carries, scaling with something better than the sampler's bilinear, and enlarging
 * animation with a network trained on it.
 *
 * Every field's default is the behaviour the engine had before this type existed, and a renderer
 * reads [isNeutral] to skip the work entirely. That is deliberate and is the ladder's first law:
 * DISABLED IS BIT-EXACT. A build that turns nothing on writes the same pixels it always did, which
 * is what makes each pass measurable on its own and what keeps the colour instruments honest.
 *
 * Not every renderer can honour these. A renderer applies what it can and ignores the rest; the
 * engine neither asks nor promises. On Android the shipping interop path decodes straight to a
 * Surface with no shader of its own, so nothing here reaches it.
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
     * Averaging code values darkens thin bright detail as a picture is scaled, which shows most on
     * high-contrast edges such as subtitle text and animation lines. The Metal renderer converts
     * the picture to light at its own size, in a half-float texture, and scales that. The Android
     * GPU blit decodes each tap to light before it weighs it. Both use the sRGB curve, and both
     * leave a picture drawn at its own size within one level of the plain write.
     */
    public val linearLight: Boolean = false,
    /**
     * Enlarges animation with a small neural network trained on line art and flat colour, which
     * is what a photographic kernel renders soft.
     *
     * It runs only where the picture is drawn at more than 1.2 times its own size on both axes,
     * which is the network's own rule: below that it would cost every frame and change little.
     * It doubles the picture, and [scaler] then takes the doubled picture the rest of the way, up
     * or down, so the two compose rather than compete. It works on the transfer curve's code
     * values, which is what the network was trained on, so [linearLight] reaches only that second
     * step. Debanding runs before it, on the source, where the bands are.
     *
     * The intermediate pictures are half-float textures at the source's size, and the doubled
     * picture is one more at twice that size, so this costs memory as well as time: about 50 MB for
     * a 720p film with [AnimationUpscaler.Fast], and several times that for 1080p with
     * [AnimationUpscaler.Quality]. A GPU that cannot render to half floats skips it.
     *
     * The Android GPU renderer and the Metal renderer run it. The Metal renderer scales an HDR
     * picture that it shows as HDR without it, because the networks were trained on standard
     * range.
     */
    public val animationUpscaler: AnimationUpscaler = AnimationUpscaler.Off,
) {
    /** True for the value that reproduces the plain decoded picture exactly, byte for byte. */
    public val isNeutral: Boolean
        get() = !dither && !deband && scaler == VideoScaler.Bilinear && !linearLight &&
            animationUpscaler == AnimationUpscaler.Off

    public companion object {
        /** Everything off: what the engine did before the ladder, and the default. */
        public val Off: RenderQuality = RenderQuality()

        /**
         * The two cheap passes, which is what most devices should run.
         *
         * Deliberately NOT a scaler change, and not the animation upscaler: either costs more than
         * these two together and its default belongs to a measurement per device class, not to a
         * convenience constant.
         */
        public val Standard: RenderQuality = RenderQuality(dither = true, deband = true)
    }
}

/** The kernel a renderer resamples the picture with when it is not drawn at its own size. */
public enum class VideoScaler {
    /** The sampler's own filtering. One fetch, and what every renderer did before these passes. */
    Bilinear,

    /**
     * Catmull-Rom bicubic, evaluated as four bilinear fetches rather than sixteen point fetches.
     *
     * The visible difference is on UPSCALES, which is the ordinary case on a phone: a 800p film on
     * a 1125 pixel tall screen is a 1.4x enlargement that bilinear renders soft.
     */
    CatmullRom,
}

/**
 * The two tiers of the animation upscaler: a port of Anime4K v3.2's CNN x2 networks, by bloc97,
 * under the MIT licence.
 *
 * Only the two curated networks ship. Anime4K's wider family of user shaders is out of scope.
 */
public enum class AnimationUpscaler {
    /** No network. The picture is scaled by [VideoScaler] alone, as it always was. */
    Off,

    /**
     * The small network, Anime4K's S: four convolutions at the source's size. The tier for phones.
     */
    Fast,

    /**
     * The medium network, Anime4K's M: seven convolutions and a merge of all seven at the source's
     * size, about twice the work of [Fast] and sharper on fine lines. The tier for tablets and
     * desktops.
     */
    Quality,
}
