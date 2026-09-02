package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.ReplayGainMode
import kotlin.math.pow

/**
 * What a container said about its own loudness, in the two vocabularies that exist.
 *
 * Gains are decibels to apply; peaks are the highest sample magnitude in the material, where 1 is
 * full scale. A peak above 1 is legal and common: it means the encoder measured the decoded signal
 * and found intersample peaks that the source's own samples never reached.
 */
internal data class ReplayGainTags(
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val trackPeak: Float?,
    val albumPeak: Float?,
)

/** How many dB of R128 sit in one unit of its fixed-point encoding. */
private const val R128_UNITS_PER_DB = 256f

/**
 * R128 targets -23 LUFS and ReplayGain targets -18, so an R128 gain lands five decibels quieter
 * than the same file's ReplayGain would. Forgetting this plays every Opus file noticeably quiet.
 */
private const val R128_TO_REPLAYGAIN_DB = 5f

/**
 * Reads one gain value.
 *
 * ReplayGain writes a decimal with an optional `dB` suffix, and encoders disagree about the space
 * before it and the case of it. R128 writes a Q7.8 fixed-point INTEGER instead, so a decimal there
 * is not a lenient spelling of the same thing, it is a different tag being misread; it is refused.
 *
 * @return null for anything unparseable. Never zero as a stand-in: zero is a real measurement
 *         meaning the file needs no change, and a caller must be able to tell that from silence.
 */
internal fun parseGainDb(value: String, r128: Boolean): Float? {
    val text = value.trim()
    if (text.isEmpty()) return null
    if (r128) {
        val units = text.toIntOrNull() ?: return null
        return units / R128_UNITS_PER_DB + R128_TO_REPLAYGAIN_DB
    }
    val number = text.removeSuffix("dB").removeSuffix("DB").removeSuffix("db").removeSuffix("Db").trim()
    val parsed = number.toFloatOrNull() ?: return null
    return if (parsed.isFinite()) parsed else null
}

/** Reads one peak. Negative is impossible for a magnitude, so it is refused rather than clamped. */
internal fun parsePeak(value: String): Float? {
    val parsed = value.trim().toFloatOrNull() ?: return null
    return if (parsed.isFinite() && parsed >= 0f) parsed else null
}

/**
 * Finds the four values in a container's and a stream's tags.
 *
 * Keys are matched case-insensitively because containers disagree, and the STREAM wins: FFmpeg
 * surfaces ReplayGain on the audio stream for some containers and on the format for others, and
 * when both carry one the stream is the more specific answer.
 */
internal fun parseReplayGain(container: Map<String, String>, stream: Map<String, String>): ReplayGainTags {
    val merged = HashMap<String, String>(container.size + stream.size)
    for ((key, value) in container) merged[key.uppercase()] = value
    for ((key, value) in stream) merged[key.uppercase()] = value

    fun gain(replayGainKey: String, r128Key: String): Float? =
        merged[replayGainKey]?.let { parseGainDb(it, r128 = false) }
            ?: merged[r128Key]?.let { parseGainDb(it, r128 = true) }

    return ReplayGainTags(
        trackGainDb = gain("REPLAYGAIN_TRACK_GAIN", "R128_TRACK_GAIN"),
        albumGainDb = gain("REPLAYGAIN_ALBUM_GAIN", "R128_ALBUM_GAIN"),
        trackPeak = merged["REPLAYGAIN_TRACK_PEAK"]?.let { parsePeak(it) },
        albumPeak = merged["REPLAYGAIN_ALBUM_PEAK"]?.let { parsePeak(it) },
    )
}

/**
 * The linear gain to apply, already clamped so it cannot clip.
 *
 * The clamp is the part that matters. A positive gain over material that already peaks near full
 * scale would push samples past it, and the peak in the tag is exactly the number that says by how
 * much. So the gain is reduced until `peak * gain` sits on [ceiling], and a tag asking for +6 dB
 * can legitimately deliver less. An attenuation is never touched: turning something down cannot
 * clip, so the clamp must not interfere with it.
 *
 * With no peak in the tags nothing can be clamped, and the gain is applied as asked. That is the
 * standard's own behaviour, and it is why [ceiling] should stay at unity unless a consumer has
 * deliberately allowed a boost.
 */
internal fun replayGainLinear(
    tags: ReplayGainTags,
    mode: ReplayGainMode,
    preampDb: Float,
    fallbackDb: Float,
    ceiling: Float,
): Float {
    if (mode == ReplayGainMode.Off) return 1f

    val (gainDb, peak) = when (mode) {
        ReplayGainMode.Album -> (tags.albumGainDb ?: tags.trackGainDb) to (tags.albumPeak ?: tags.trackPeak)
        else -> (tags.trackGainDb ?: tags.albumGainDb) to (tags.trackPeak ?: tags.albumPeak)
    }

    val decibels = (gainDb ?: fallbackDb) + preampDb
    val linear = 10f.pow(decibels / 20f)
    if (!linear.isFinite() || linear <= 0f) return 1f
    if (linear <= 1f) return linear

    // Only an amplification can clip, and only a peak can say when.
    val headroom = peak?.takeIf { it > 0f }?.let { ceiling / it } ?: return linear
    return if (linear > headroom) maxOf(headroom, 0f) else linear
}
