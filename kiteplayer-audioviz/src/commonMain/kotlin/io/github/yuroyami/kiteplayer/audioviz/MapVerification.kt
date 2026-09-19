package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.log10

/**
 * Checks a song map against live audio: over the first five seconds of live programme readings
 * the map covers, the mean absolute level difference must stay within 1.5 dB. A map built from
 * different content, or with different timestamps, fails.
 */
internal class MapVerification(private val map: SongMap) {
    enum class Verdict { Pending, Verified, Rejected }

    private var verdict = Verdict.Pending
    private var firstAt: Long? = null
    private var sum = 0.0
    private var count = 0

    fun observe(frame: SpectrumFrame): Verdict {
        if (verdict != Verdict.Pending) return verdict
        val programme = frame.programme ?: return verdict
        val at = programme.window?.referenceMicros ?: return verdict
        val meanSquare = programme.meanSquare ?: return verdict
        if (programme.availability != AnalysisAvailability.Ready || programme.digitalSilence) return verdict
        val mapped = map.levelAt(at) ?: return verdict
        val live = if (meanSquare > 0.0) 10.0 * log10(meanSquare) else SILENT_DB
        if (live <= AUDIBLE_DB && mapped <= AUDIBLE_DB) return verdict
        sum += abs(live - mapped)
        count++
        val start = firstAt ?: at.also { firstAt = it }
        if (at - start >= SPAN_MICROS) verdict = if (sum / count > TOLERANCE_DB) Verdict.Rejected else Verdict.Verified
        return verdict
    }

    private companion object {
        const val SPAN_MICROS = 5_000_000L
        const val TOLERANCE_DB = 1.5
        const val AUDIBLE_DB = -70.0
        const val SILENT_DB = -100.0
    }
}
