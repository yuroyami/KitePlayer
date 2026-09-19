package io.github.yuroyami.kiteplayer.audioviz

/** Major or minor, the two modes the key profiles describe. */
@AudioVizAuthoringApi
public enum class KeyMode { Major, Minor }

/**
 * A key estimated from pitched audio. Unknown keys are null, never a guess with low confidence.
 * Confidence and correlation are scores against published key profiles, not probabilities.
 */
@AudioVizAuthoringApi
public class KeyEstimate internal constructor(
    /** Tonic pitch class, 0 for C up to 11 for B. */
    public val tonic: Int,
    public val mode: KeyMode,
    /** Support for this key in 0..1, from its correlation and its lead over the next key. */
    public val confidence: Float,
    /** Correlation of the pitch-class profile with this key's profile, -1..1. */
    public val correlation: Float,
    /** How far this key's correlation leads the best different key. */
    public val lead: Float,
    /** Estimated tuning offset from A4 = 440 Hz, in cents, -50..50. */
    public val tuningCents: Float,
    /** The long window whose analysis last supported this estimate. */
    public val window: AnalysisWindow,
) {
    /**
     * The key on the circle of fifths, 0..1, for colour. A minor key sits at its relative major,
     * so keys that share most of their notes sit next to each other.
     */
    public val hue: Float get() {
        val major = if (mode == KeyMode.Major) tonic else (tonic + 3) % 12
        return CIRCLE_OF_FIFTHS.indexOf(major) / 12f
    }

    private companion object {
        val CIRCLE_OF_FIFTHS = intArrayOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)
    }
}
