package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Builds a [SongMap] from scanned audio, streaming: the decoded song is never retained.
 *
 * It runs the live analyser over the scan, so the map's programme readings, structural events and
 * keys use the same code and the same timestamp conventions as live analysis. It keeps a level
 * histogram for the reference, a 100 ms level curve for verification, the structural events, the
 * key segments, and thirty seconds of pitch-class profiles to place key changes.
 */
internal class SongMapBuilder(private val track: TrackId) {
    private var analyzer: SpectrumAnalyzer? = null
    private var format: AudioFormat? = null
    private var coveredThrough = 0L

    private val histogram = IntArray(HISTOGRAM_BINS)
    private var readings = 0
    private var curve = FloatArray(1_024)
    private var curveSize = 0
    private var curveStart: Long? = null

    private val structure = ArrayList<AudioDetection>()
    private val keyChanges = ArrayList<AudioDetection>()
    private val keys = ArrayList<KeySegment>()
    private var openKey: KeyEstimate? = null
    private var openStart = 0L
    private var openConfidence = 0f
    private var lastKnown: KeyEstimate? = null
    private var lastKnownEnd = 0L

    private val profiles = Array(PROFILE_STEPS) { FloatArray(12) }
    private val profileTimes = LongArray(PROFILE_STEPS)
    private var profileCount = 0
    private var profileNext = 0
    private var nextProfileAt = Long.MIN_VALUE

    fun feed(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
        if (frames <= 0 || format.channels !in 1..64 || format.sampleRate !in 1_000..768_000) return
        if (format != this.format) {
            // A format change starts a new analysis; what was learned so far stays in the map.
            closeKey(pts.micros)
            analyzer = SpectrumAnalyzer(sampleRate = format.sampleRate).also { it.onAnalysis = ::collect }
            this.format = format
        }
        checkNotNull(analyzer).feed(interleaved, frames, format, pts.micros)
        coveredThrough = maxOf(coveredThrough, pts.micros + frames * 1_000_000L / format.sampleRate)
    }

    private fun collect(frame: SpectrumFrame) {
        if (!frame.hasTimestamp) return
        val at = frame.ptsMicros
        frame.programme?.let { programme ->
            val window = programme.window
            val meanSquare = programme.meanSquare
            if (programme.availability == AnalysisAvailability.Ready && window?.referenceMicros != null && meanSquare != null) {
                record(window.referenceMicros, if (programme.digitalSilence) SILENT_DB else toDb(meanSquare))
            }
        }
        frame.structure?.let { batch -> for (index in 0 until batch.size) structure += batch[index] }
        followKey(frame.key, at)
        if (at >= nextProfileAt) {
            nextProfileAt = at + SongMap.CURVE_STEP_MICROS
            frame.chroma.copyInto(profiles[profileNext])
            profileTimes[profileNext] = at
            profileNext = (profileNext + 1) % PROFILE_STEPS
            profileCount = minOf(profileCount + 1, PROFILE_STEPS)
        }
    }

    /** One programme reading, kept once per 100 ms of the curve grid. */
    private fun record(atMicros: Long, db: Double) {
        val start = curveStart ?: (floor(atMicros.toDouble() / SongMap.CURVE_STEP_MICROS).toLong() * SongMap.CURVE_STEP_MICROS)
            .also { curveStart = it }
        val index = ((atMicros - start) / SongMap.CURVE_STEP_MICROS).toInt()
        if (index < curveSize) return
        while (curveSize <= index) {
            if (curveSize == curve.size) curve = curve.copyOf(curve.size * 2)
            curve[curveSize++] = db.toFloat()
        }
        if (db > HISTOGRAM_LOW_DB) {
            readings++
            histogram[((db - HISTOGRAM_LOW_DB) / HISTOGRAM_STEP_DB).toInt().coerceIn(0, HISTOGRAM_BINS - 1)]++
        }
    }

    private fun followKey(key: KeyEstimate?, at: Long) {
        val open = openKey
        val same = open != null && key != null && open.tonic == key.tonic && open.mode == key.mode
        if (same) {
            openConfidence = maxOf(openConfidence, checkNotNull(key).confidence)
            return
        }
        if (open != null) closeKey(at)
        if (key != null) {
            val previous = lastKnown
            if (previous != null && at - lastKnownEnd <= KEY_GAP_MICROS &&
                (previous.tonic != key.tonic || previous.mode != key.mode)) {
                placeKeyChange(previous, key, at)
            }
            openKey = key
            openStart = at
            openConfidence = key.confidence
        }
    }

    private fun closeKey(at: Long) {
        val open = openKey ?: return
        keys += KeySegment(openStart, at, open.tonic, open.mode, openConfidence)
        lastKnown = open
        lastKnownEnd = at
        openKey = null
    }

    /**
     * Places a key change where the lead of [to] over [from] steps up, over the retained thirty
     * seconds: the split of a least-squares fit of two constant segments, later segment higher.
     * A chord both keys share dips the lead inside a section, so the fit follows the mean, not the
     * sign of each step. Thirty seconds covers the key estimate's own lag, up to seventeen seconds
     * on a mode change.
     */
    private fun placeKeyChange(from: KeyEstimate, to: KeyEstimate, confirmedAt: Long) {
        if (profileCount < 2) return
        val first = (profileNext - profileCount + PROFILE_STEPS) % PROFILE_STEPS
        val lead = DoubleArray(profileCount) { offset ->
            val profile = profiles[(first + offset) % PROFILE_STEPS]
            KeyTracker.correlation(profile, to.tonic, to.mode) - KeyTracker.correlation(profile, from.tonic, from.mode)
        }
        val total = lead.sum()
        val totalSquares = lead.sumOf { it * it }
        var sum = 0.0
        var squares = 0.0
        var best = -1
        var bestError = Double.POSITIVE_INFINITY
        for (split in 1 until profileCount) {
            sum += lead[split - 1]
            squares += lead[split - 1] * lead[split - 1]
            val before = split
            val after = profileCount - split
            val meanBefore = sum / before
            val meanAfter = (total - sum) / after
            if (meanAfter <= meanBefore) continue
            val error = (squares - sum * sum / before) + (totalSquares - squares - (total - sum) * (total - sum) / after)
            if (error < bestError) { bestError = error; best = split }
        }
        if (best < 0) return
        val at = profileTimes[(first + best) % PROFILE_STEPS]
        val confidence = minOf(maxOf(from.confidence, openConfidence), to.confidence).coerceIn(0f, 1f)
        keyChanges += AudioDetection(AudioEventKind.SectionBoundary, at, maxOf(at, confirmedAt), 0f, confidence, 0f)
    }

    /** The map so far. [scanned] names the track the scan actually decoded, when it differs. */
    fun build(complete: Boolean, scanned: TrackId = track): SongMap {
        closeKey(coveredThrough)
        val reference = if (!complete || readings == 0) null else {
            var needed = kotlin.math.ceil(readings * 0.95).toInt().coerceAtLeast(1)
            var bin = 0
            while (bin < HISTOGRAM_BINS - 1) {
                needed -= histogram[bin]
                if (needed <= 0) break
                bin++
            }
            10.0.pow((HISTOGRAM_LOW_DB + (bin + 0.5) * HISTOGRAM_STEP_DB) / 10.0)
        }
        // The live detector's decisions stand; a key change adds a boundary only where none lies within four seconds.
        val merged = ArrayList(structure)
        for (change in keyChanges) {
            if (structure.none { abs(it.ptsMicros - change.ptsMicros) <= KEY_BOUNDARY_SPACING_MICROS }) merged += change
        }
        merged.sortBy { it.ptsMicros }
        return SongMap(SongMap.VERSION, scanned, coveredThrough, complete, reference, merged.toTypedArray(),
            keys.toTypedArray(), curve.copyOf(curveSize), curveStart ?: 0L)
    }

    private fun toDb(meanSquare: Double): Double = if (meanSquare <= 0.0) SILENT_DB else 10.0 * log10(meanSquare)

    private companion object {
        const val SILENT_DB = -100.0
        const val HISTOGRAM_LOW_DB = -70.0
        const val HISTOGRAM_STEP_DB = 0.1
        const val HISTOGRAM_BINS = 800
        const val PROFILE_STEPS = 300
        const val KEY_GAP_MICROS = 8_000_000L
        const val KEY_BOUNDARY_SPACING_MICROS = 4_000_000L
    }
}
