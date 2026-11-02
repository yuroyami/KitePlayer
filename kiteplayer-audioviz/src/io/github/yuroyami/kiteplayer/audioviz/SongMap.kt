package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.TrackId

/** One stretch of a song map with one supported key. */
@AudioVizAuthoringApi
public class KeySegment internal constructor(
    public val startMicros: Long,
    public val endMicros: Long,
    /** Tonic pitch class, 0 for C. */
    public val tonic: Int,
    public val mode: KeyMode,
    /** The highest key confidence reached inside the segment. */
    public val confidence: Float,
)

/**
 * What a background scan of one audio track found. Every field may be absent, and a partial map
 * describes only its covered range. See docs/audioviz-song-scan-api.md.
 */
@AudioVizAuthoringApi
public class SongMap internal constructor(
    /** The analysis version that produced this map; another version is never reused. */
    public val version: Int,
    /** The audio track that was scanned. */
    public val track: TrackId,
    /** Media time analysed, from the start of the stream. */
    public val coveredThroughMicros: Long,
    /** Whether the scan reached the end of the stream. */
    public val complete: Boolean,
    /**
     * The 95th percentile of valid ungated 400 ms K-weighted programme powers, in the causal
     * reference's linear convention. Null for a partial or all-silent map.
     */
    public val referencePower: Double?,
    private val structure: Array<AudioDetection>,
    private val keys: Array<KeySegment>,
    /** Programme level in dB, one value per [CURVE_STEP_MICROS] from [curveStartMicros]. */
    internal val levelCurve: FloatArray,
    internal val curveStartMicros: Long,
) {
    /** Section boundaries, drops and breakdowns, in time order. */
    public val structureCount: Int get() = structure.size
    public fun structure(index: Int): AudioDetection = structure[index]

    /** Key segments, in time order. Gaps are stretches with no supported key. */
    public val keyCount: Int get() = keys.size
    public fun key(index: Int): KeySegment = keys[index]

    internal fun structureList(): List<AudioDetection> = structure.asList()

    /** The programme level in dB at [ptsMicros], interpolated, or null outside the curve. */
    internal fun levelAt(ptsMicros: Long): Float? {
        if (levelCurve.isEmpty() || ptsMicros < curveStartMicros) return null
        val position = (ptsMicros - curveStartMicros).toDouble() / CURVE_STEP_MICROS
        val index = position.toInt()
        if (index >= levelCurve.size) return null
        if (index == levelCurve.size - 1) return levelCurve[index]
        val mix = (position - index).toFloat()
        return levelCurve[index] + (levelCurve[index + 1] - levelCurve[index]) * mix
    }

    public companion object {
        /**
         * The analysis version of maps built by this release. It rises with every change to what a
         * map holds, so a map stored by an older analysis is a miss and is scanned again.
         *
         * 2: the fixes of #322 and #323 (the merge of scanned parts and the key boundaries), #336
         * (event times after a timestamp jump) and #339 (the level reference for layouts the
         * loudness meter cannot read) changed maps while this stayed 1, so maps stored by 0.0.26
         * and 0.0.27 were trusted (#659). `SongMapGoldenTest` holds the bytes of one map, and turns
         * red when they change, until this and its hash both change.
         */
        public const val VERSION: Int = 2
        internal const val CURVE_STEP_MICROS: Long = 100_000L
    }
}
