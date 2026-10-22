package io.github.yuroyami.kiteplayer.audioviz.viz

/**
 * The spectrum cut into [parts] equal runs of bars, each read through a fixed artistic range.
 *
 * One figure per part is the cheapest honest way to have several things on screen: the bass
 * figure answers the low band, the middle one the voice, the top one the cymbals.
 *
 * The ranges are fixed, so a quiet passage reads lower than a loud one and the same passage always
 * reads the same. They differ between parts because the parts hold different amounts of energy: on
 * a loud drum fixture the mean height is 0.31 in the low third, 0.08 in the middle and 0.13 at the
 * top. The tops below are about two and a half times those means, which is the tilt compensation
 * the standard asks to be named rather than learned while the song plays. *Judgement.*
 */
internal class SpectrumSplit(val parts: Int) {
    private val levels = FloatArray(parts)
    private var bands = FloatArray(0)

    fun update(values: FloatArray) {
        bands = values
        if (values.isEmpty()) return
        for (part in 0 until parts) {
            val from = values.size * part / parts
            val to = (values.size * (part + 1) / parts).coerceAtLeast(from + 1)
            var total = 0f
            for (index in from until to.coerceAtMost(values.size)) total += values[index]
            levels[part] = place(part, total / (to - from))
        }
    }

    /** The part's bars read at [position], 0 to 1 along that part, in its own range. */
    fun sample(part: Int, position: Float): Float {
        if (bands.isEmpty()) return 0f
        val from = bands.size * part / parts
        val to = (bands.size * (part + 1) / parts).coerceAtLeast(from + 1)
        val at = from + position.coerceIn(0f, 1f) * (to - from - 1)
        val lower = at.toInt().coerceIn(0, bands.size - 1)
        val upper = (lower + 1).coerceAtMost(bands.size - 1)
        val value = bands[lower] + (bands[upper] - bands[lower]) * (at - lower)
        return place(part, value)
    }

    /** Folded in half, so a shape drawn round a circle closes without a seam. */
    fun folded(part: Int, position: Float): Float {
        val wrapped = position - kotlin.math.floor(position)
        return sample(part, if (wrapped <= 0.5f) wrapped * 2f else (1f - wrapped) * 2f)
    }

    /** How loud the part is overall, 0 to 1 in its fixed range. */
    fun level(part: Int): Float = levels[part]

    private fun place(part: Int, value: Float): Float {
        val top = TOPS[part.coerceIn(0, TOPS.size - 1)]
        return ((value - FLOOR) / (top - FLOOR)).coerceIn(0f, 1f)
    }

    fun reset() {
        levels.fill(0f)
    }

    private companion object {
        /** Below this a band holds nothing worth drawing. */
        const val FLOOR = 0.02f

        /** The height that fills a part, from the low third upwards. */
        val TOPS = floatArrayOf(0.75f, 0.22f, 0.35f, 0.35f, 0.35f)
    }
}
