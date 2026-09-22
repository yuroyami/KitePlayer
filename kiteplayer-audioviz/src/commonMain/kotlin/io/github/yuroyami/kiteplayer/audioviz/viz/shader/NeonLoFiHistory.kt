package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.*
import kotlin.math.*

/** Heard music, on a media-time lattice. NaN is unknown; zero is measured silence. */
internal class NeonLoFiHistory {
    val bands = FloatArray(ROWS * BANDS)
    val timestamps = LongArray(ROWS) { Long.MIN_VALUE }
    val valid = BooleanArray(ROWS)
    val profile = FloatArray(PROFILE * 2)
    val profileTimes = LongArray(PROFILE * 2) { Long.MIN_VALUE }
    var count = 0; private set
    var newestMicros = Long.MIN_VALUE; private set
    var epoch = 0L; private set
    var revision = 0L; private set
    private var generation: Generation? = null
    private var analysisRevision = -1L
    private var previousTime = Long.MIN_VALUE
    private var previousReady = false
    private val previous = FloatArray(BANDS)
    private val current = FloatArray(BANDS)
    private var lastReset: AudioEventDelivery? = null

    fun record(frame: SpectrumFrame) {
        if (!frame.hasTimestamp) return
        val t = frame.ptsMicros
        val reset = frame.events?.takeIf { it.reset && it !== lastReset }
        if (generation != frame.generation || analysisRevision != frame.analysisRevision ||
            (previousTime != Long.MIN_VALUE && t < previousTime) || reset != null) {
            clear(); generation = frame.generation; analysisRevision = frame.analysisRevision
            lastReset = reset
        }
        if (frame.held || t == previousTime) return
        val ready = frame.availability == AnalysisAvailability.Ready
        resample(frame.bands, current)
        val tick = floor(t / STEP.toDouble()).toLong()
        val first = if (newestMicros == Long.MIN_VALUE) {
            if (previousTime == Long.MIN_VALUE) tick + if (t % STEP == 0L) 0 else 1
            else floor(previousTime / STEP.toDouble()).toLong() + 1
        } else newestMicros / STEP + 1
        // A long missed interval costs at most one ring. It never repeats the newest spectrum.
        for (at in max(first, tick - ROWS + 1)..tick) {
            val time = at * STEP
            val slot = slot(time)
            val endpoint = time == t && ready
            val supported = previousReady && ready && previousTime != Long.MIN_VALUE &&
                t - previousTime <= STEP && time >= previousTime
            valid[slot] = endpoint || supported
            timestamps[slot] = time
            if (valid[slot]) {
                val mix = if (endpoint) 1f else ((time - previousTime).toDouble() / (t - previousTime)).toFloat()
                for (b in 0 until BANDS) bands[slot * BANDS + b] = previous[b] + (current[b] - previous[b]) * mix
            }
            count = min(ROWS, count + 1); newestMicros = time; revision++
        }
        current.copyInto(previous); previousTime = t; previousReady = ready
    }

    fun valueAt(time: Long, band: Int): Float {
        val s = slot(time)
        return if (timestamps[s] == time && valid[s]) bands[s * BANDS + band.coerceIn(0, BANDS - 1)] else Float.NaN
    }

    /** Explicit age-to-azimuth: recent in the opening, older toward either outer horizon. */
    fun project() {
        if (newestMicros == Long.MIN_VALUE) return
        for (ridge in 0..1) for (i in 0 until PROFILE) {
            val across = abs(i / (PROFILE - 1f) * 2f - 1f)
            val age = across.pow(0.85f) * if (ridge == 0) 30f else 89.75f
            val time = newestMicros - (age * 4).roundToLong() * STEP
            val s = slot(time); val p = ridge * PROFILE + i
            profileTimes[p] = if (timestamps[s] == time && valid[s]) time else Long.MIN_VALUE
            var height = 0f
            if (profileTimes[p] != Long.MIN_VALUE) {
                var low = 0f; var mid = 0f; var high = 0f; var detail = 0f
                for (b in 0 until BANDS) {
                    val v = bands[s * BANDS + b]
                    when { b < 16 -> low += v / 16f; b < 42 -> mid += v / 26f; else -> high += v / 22f }
                    if (b > 42) detail += abs(v - bands[s * BANDS + b - 1]) / 21f
                }
                height = 0.50f * low + 0.35f * mid + 0.15f * high + 0.08f * detail
            }
            profile[p] = height * smooth(0.035f, 0.27f, across)
        }
    }

    fun clear() {
        timestamps.fill(Long.MIN_VALUE); valid.fill(false); bands.fill(0f); profile.fill(0f)
        profileTimes.fill(Long.MIN_VALUE); count = 0; newestMicros = Long.MIN_VALUE
        previousTime = Long.MIN_VALUE; previousReady = false; epoch++; revision++
    }
    private fun slot(time: Long): Int = (((time / STEP) % ROWS + ROWS) % ROWS).toInt()

    companion object {
        const val BANDS = 64
        const val ROWS = 360
        const val PROFILE = 256
        const val STEP = 250_000L
        /** Area averaging, covering every band even when the display has fewer lanes. */
        fun resample(source: FloatArray, target: FloatArray) {
            if (source.isEmpty()) { target.fill(0f); return }
            val width = source.size.toFloat() / target.size
            for (i in target.indices) {
                val a = i * width; val b = (i + 1) * width
                var sum = 0f
                for (j in floor(a).toInt() until ceil(b).toInt()) {
                    val overlap = (min(b, j + 1f) - max(a, j.toFloat())).coerceAtLeast(0f)
                    val v = source[j.coerceIn(source.indices)]
                    sum += (if (v.isFinite()) v.coerceIn(0f, 1f) else 0f) * overlap
                }
                target[i] = (sum / width).coerceIn(0f, 1f)
            }
        }
        fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
