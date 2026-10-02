package io.github.yuroyami.kiteplayer.spi

/**
 * The static HDR metadata of a stream or a frame: the display it was graded on, as SMPTE ST 2086
 * describes it, and how bright the content gets, as CTA-861.3 describes it. A tone mapper reads the
 * content's real peak from it instead of assuming one. Every field is null when the source did not
 * say.
 */
public data class HdrStaticMetadata(
    /** The mastering display's primaries and white point. */
    val masteringPrimaries: DisplayPrimaries? = null,
    /** The mastering display's darkest black, in nits. */
    val masteringMinNits: Float? = null,
    /** The mastering display's peak white, in nits. */
    val masteringMaxNits: Float? = null,
    /** The brightest pixel of the content, in nits: MaxCLL. */
    val maxContentLightNits: Int? = null,
    /** The brightest frame average of the content, in nits: MaxFALL. */
    val maxFrameAverageNits: Int? = null,
) {
    /**
     * The content's peak for a tone map, in nits: the brightest pixel when it is between 100 and
     * 10000 nits, else the mastering display's peak in that range, else null. The brightest pixel
     * comes first because it measures the content, where the mastering peak describes a display, as
     * mpv's renderer also takes it.
     */
    val peakNits: Float?
        get() = maxContentLightNits?.toFloat()?.takeIf { it in USABLE_PEAK }
            ?: masteringMaxNits?.takeIf { it in USABLE_PEAK }

    private companion object {
        val USABLE_PEAK = 100f..10_000f
    }
}

/** Red, green and blue primaries and a white point, as CIE 1931 xy chromaticity coordinates. */
public data class DisplayPrimaries(
    val redX: Float,
    val redY: Float,
    val greenX: Float,
    val greenY: Float,
    val blueX: Float,
    val blueY: Float,
    val whiteX: Float,
    val whiteY: Float,
)
