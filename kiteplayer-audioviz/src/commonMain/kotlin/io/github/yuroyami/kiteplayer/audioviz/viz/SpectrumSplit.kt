package io.github.yuroyami.kiteplayer.audioviz.viz

/**
 * The spectrum cut into [parts] equal runs of bars, each placed in its own recent range.
 *
 * One figure per part is the cheapest honest way to have several things on screen: the bass
 * figure answers the kick, the middle one the voice, the top one the hats.
 */
internal class SpectrumSplit(val parts: Int) {
    private val high = FloatArray(parts) { 0.3f }
    private val low = FloatArray(parts)
    private val levels = FloatArray(parts)
    private var bands = FloatArray(0)

    fun update(values: FloatArray, deltaSeconds: Float) {
        bands = values
        if (values.isEmpty()) return
        for (part in 0 until parts) {
            val from = values.size * part / parts
            val to = (values.size * (part + 1) / parts).coerceAtLeast(from + 1)
            var loudest = 0f
            var quietest = 1f
            var total = 0f
            for (index in from until to.coerceAtMost(values.size)) {
                val value = values[index]
                if (value > loudest) loudest = value
                if (value < quietest) quietest = value
                total += value
            }
            high[part] = if (loudest > high[part]) loudest else (high[part] - deltaSeconds * 0.25f)
            high[part] = high[part].coerceAtLeast(0.15f)
            low[part] += (quietest - low[part]) * (deltaSeconds / 3f).coerceIn(0f, 1f)
            if (high[part] - low[part] < 0.12f) low[part] = (high[part] - 0.12f).coerceAtLeast(0f)
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

    /** How loud the part is overall, 0 to 1 in its own range. */
    fun level(part: Int): Float = levels[part]

    private fun place(part: Int, value: Float): Float =
        ((value - low[part]) / (high[part] - low[part]).coerceAtLeast(0.05f)).coerceIn(0f, 1f)

    fun reset() {
        high.fill(0.3f)
        low.fill(0f)
        levels.fill(0f)
    }
}
