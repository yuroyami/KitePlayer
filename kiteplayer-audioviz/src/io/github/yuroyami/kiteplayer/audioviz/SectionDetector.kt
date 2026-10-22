package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Causal section boundaries, drops and breakdowns from 100 ms blocks of analysis.
 *
 * Compares eight seconds before each candidate block with the 1.2 seconds after it, on timbre
 * shape, onset density and level. Level alone cannot make a boundary, and neither can harmony
 * alone: 1.2 seconds cannot tell a new key from a new chord, so that is left to the song scan. A decision is
 * published 1.6 seconds after its block, plus up to one block, with a watermark that promises
 * nothing earlier will follow. See docs/audioviz-structure-api.md.
 */
internal class SectionDetector(private val bandCount: Int, private val analysisSeconds: Double) {
    private val analysesPerBlock = kotlin.math.round(BLOCK_SECONDS / analysisSeconds).toInt().coerceAtLeast(1)

    private class Block {
        val shape = FloatArray(GROUPS)
        var silent = true
        var level = 0f
        var density = 0f
        var strength = 0f
        var startMicros = 0L
        var hasStart = false
    }

    private val blocks = Array(RING) { Block() }
    private val scores = FloatArray(RING)
    private var completed = 0L
    private var lastPublished = Long.MIN_VALUE / 2

    // The block being accumulated.
    private val bandSums = DoubleArray(bandCount)
    private var totalSum = 0.0
    private var strengthSum = 0.0
    private var onsets = 0
    private var analyses = 0
    private var level: Double? = null
    private var startMicros: Long? = null

    // Recent broadband onsets by their own time, for placing a boundary inside its block.
    private val onsetTimes = LongArray(ONSET_MEMORY)
    private val onsetStrengths = FloatArray(ONSET_MEMORY)
    private var onsetCount = 0
    private var onsetNext = 0

    private val groupOf = IntArray(bandCount) { (it * GROUPS / bandCount).coerceIn(0, GROUPS - 1) }

    /**
     * Feeds one analysis and answers a structural batch when a block completes and a decision is
     * due, else null. Times are media microseconds; a null [centreMicros] publishes nothing.
     */
    fun feed(
        bandPowers: FloatArray,
        totalPower: Float,
        programmeMeanSquare: Double?,
        onsetMicros: Long?,
        onsetStrength: Float,
        overallFast: Float,
        centreMicros: Long?,
        availableMicros: Long?,
    ): AudioDetections? {
        if (analyses == 0) startMicros = centreMicros
        for (band in 0 until bandCount) bandSums[band] += bandPowers[band].toDouble()
        totalSum += totalPower
        strengthSum += overallFast
        programmeMeanSquare?.let { level = it }
        if (onsetMicros != null) {
            onsets++
            onsetTimes[onsetNext] = onsetMicros
            onsetStrengths[onsetNext] = onsetStrength
            onsetNext = (onsetNext + 1) % ONSET_MEMORY
            onsetCount = minOf(onsetCount + 1, ONSET_MEMORY)
        }
        if (++analyses < analysesPerBlock) return null
        closeBlock()
        return decide(availableMicros)
    }

    private fun closeBlock() {
        val block = blocks[(completed % RING).toInt()]
        val groups = DoubleArray(GROUPS)
        for (band in 0 until bandCount) groups[groupOf[band]] += bandSums[band] / analyses
        var loudest = Double.NEGATIVE_INFINITY
        for (group in 0 until GROUPS) {
            groups[group] = 10.0 * log10(groups[group] + FLOOR_POWER)
            loudest = maxOf(loudest, groups[group])
        }
        // A floor relative to the loudest group keeps the shape independent of overall gain.
        var mean = 0.0
        for (group in 0 until GROUPS) {
            groups[group] = maxOf(groups[group], loudest - SHAPE_RANGE_DB)
            mean += groups[group]
        }
        mean /= GROUPS
        for (group in 0 until GROUPS) block.shape[group] = (groups[group] - mean).toFloat()
        val blockDb = 10.0 * log10(totalSum / analyses + FLOOR_POWER)
        block.silent = blockDb < SILENT_DB
        block.level = (level?.let { 10.0 * log10(it + FLOOR_POWER) } ?: blockDb).toFloat()
        block.density = (onsets / (analysesPerBlock * analysisSeconds)).toFloat()
        block.strength = (strengthSum / analyses).toFloat()
        block.hasStart = startMicros != null
        block.startMicros = startMicros ?: 0L
        completed++
        bandSums.fill(0.0)
        totalSum = 0.0
        strengthSum = 0.0
        onsets = 0
        analyses = 0
        startMicros = null
    }

    private fun block(index: Long): Block = blocks[(index % RING).toInt()]

    /** Scores the newest candidate, then decides the candidate [PEAK] blocks before it. */
    private fun decide(availableMicros: Long?): AudioDetections? {
        val newest = completed - 1
        val candidate = newest - AFTER + 1
        if (candidate >= BEFORE) scores[(candidate % RING).toInt()] = score(candidate)
        val decided = candidate - PEAK
        if (decided < BEFORE + PEAK || availableMicros == null) return null
        val decidedBlock = block(decided)
        val nextBlock = block(decided + 1)
        if (!decidedBlock.hasStart || !nextBlock.hasStart) return null
        val watermark = nextBlock.startMicros - 1
        val value = scores[(decided % RING).toInt()]
        var peak = value >= PUBLISH_SCORE && decided - lastPublished >= REFRACTORY_BLOCKS
        if (peak) {
            for (other in decided - PEAK..decided + PEAK) {
                if (other == decided) continue
                val neighbour = scores[(other % RING).toInt()]
                if (neighbour > value || (other < decided && neighbour == value)) { peak = false; break }
            }
        }
        if (!peak) return AudioDetections(availableMicros, watermark, emptyArray(), AudioEventSource.LiveStructure)
        lastPublished = decided
        val at = strongestOnset(decidedBlock.startMicros, nextBlock.startMicros) ?: decidedBlock.startMicros
        val detection = AudioDetection(classify(decided), at, availableMicros, meanStrength(decided).coerceIn(0f, 1f),
            value.coerceIn(0f, 1f), timbreScores[(decided % RING).toInt()].coerceIn(0f, 1f))
        return AudioDetections(availableMicros, watermark, arrayOf(detection), AudioEventSource.LiveStructure)
    }

    private val timbreScores = FloatArray(RING)

    private fun score(candidate: Long): Float {
        var silentBefore = 0
        var silentAfter = 0
        for (index in candidate - BEFORE until candidate) if (block(index).silent) silentBefore++
        for (index in candidate until candidate + AFTER) if (block(index).silent) silentAfter++
        if (silentBefore * 2 > BEFORE || silentAfter * 2 > AFTER) {
            timbreScores[(candidate % RING).toInt()] = 0f
            return 0f
        }
        val timbre = timbreEvidence(candidate)
        timbreScores[(candidate % RING).toInt()] = timbre
        val before = density(candidate - BEFORE, candidate)
        val after = density(candidate, candidate + AFTER)
        val ratio = abs(ln((after + DENSITY_OFFSET) / (before + DENSITY_OFFSET)) / LN2)
        val rhythm = ((ratio - 0.6) / 1.0).coerceAtLeast(0.0).toFloat()
        val levelChange = abs(level(candidate, candidate + AFTER) - level(candidate - BEFORE, candidate))
        val levelEvidence = ((levelChange - 3.0) / 6.0).coerceIn(0.0, 1.0).toFloat()
        // Uncapped, so the peak lands where both windows are purest rather than at the start of a plateau.
        return sqrt(timbre * timbre + rhythm * rhythm) * (0.8f + 0.2f * levelEvidence)
    }

    private fun timbreEvidence(candidate: Long): Float {
        val meanBefore = DoubleArray(GROUPS)
        val meanAfter = DoubleArray(GROUPS)
        var countBefore = 0
        var countAfter = 0
        for (index in candidate - BEFORE until candidate + AFTER) {
            val block = block(index)
            if (block.silent) continue
            val target = if (index < candidate) meanBefore else meanAfter
            for (group in 0 until GROUPS) target[group] += block.shape[group]
            if (index < candidate) countBefore++ else countAfter++
        }
        if (countBefore < 2 || countAfter < 2) return 0f
        for (group in 0 until GROUPS) {
            meanBefore[group] /= countBefore
            meanAfter[group] /= countAfter
        }
        // The past context's own spread, which includes ordinary chord-to-chord variation, is the yardstick.
        var varianceBefore = 0.0
        for (index in candidate - BEFORE until candidate) {
            val block = block(index)
            if (block.silent) continue
            for (group in 0 until GROUPS) {
                val offset = block.shape[group] - meanBefore[group]
                varianceBefore += offset * offset
            }
        }
        val spread = sqrt(varianceBefore / ((countBefore - 1) * GROUPS)).coerceAtLeast(MIN_SPREAD_DB)
        var difference = 0.0
        for (group in 0 until GROUPS) {
            val offset = meanAfter[group] - meanBefore[group]
            difference += offset * offset
        }
        val shift = sqrt(difference / GROUPS)
        return (((shift / spread) - 2.0) / 3.0).coerceAtLeast(0.0).toFloat()
    }

    private fun density(from: Long, until: Long): Double {
        var sum = 0.0
        for (index in from until until) sum += block(index).density
        return sum / (until - from)
    }

    private fun level(from: Long, until: Long): Double {
        var sum = 0.0
        var count = 0
        for (index in from until until) {
            val block = block(index)
            if (block.silent) continue
            sum += block.level
            count++
        }
        return if (count == 0) SILENT_DB else sum / count
    }

    private fun meanStrength(candidate: Long): Float {
        var sum = 0f
        for (index in candidate until candidate + AFTER) sum += block(index).strength
        return sum / AFTER
    }

    private fun classify(candidate: Long): AudioEventKind {
        val rise = level(candidate, candidate + AFTER) - level(candidate - BEFORE, candidate)
        val before = density(candidate - BEFORE, candidate)
        val after = density(candidate, candidate + AFTER)
        return when {
            rise >= LEVEL_STEP_DB && after >= DROP_DENSITY -> AudioEventKind.Drop
            rise <= -LEVEL_STEP_DB && after < before * 0.5 -> AudioEventKind.Breakdown
            else -> AudioEventKind.SectionBoundary
        }
    }

    private fun strongestOnset(from: Long, until: Long): Long? {
        var best: Long? = null
        var strongest = -1f
        for (offset in 0 until onsetCount) {
            val slot = ((onsetNext - 1 - offset) % ONSET_MEMORY + ONSET_MEMORY) % ONSET_MEMORY
            val time = onsetTimes[slot]
            if (time in from until until && onsetStrengths[slot] > strongest) {
                strongest = onsetStrengths[slot]
                best = time
            }
        }
        return best
    }

    fun reset() {
        completed = 0L
        lastPublished = Long.MIN_VALUE / 2
        scores.fill(0f)
        timbreScores.fill(0f)
        bandSums.fill(0.0)
        totalSum = 0.0
        strengthSum = 0.0
        onsets = 0
        analyses = 0
        level = null
        startMicros = null
        onsetCount = 0
        onsetNext = 0
    }

    private companion object {
        const val BLOCK_SECONDS = 0.1
        const val GROUPS = 10
        const val BEFORE = 80
        const val AFTER = 12
        const val PEAK = 4
        const val RING = 128
        const val REFRACTORY_BLOCKS = 40
        const val PUBLISH_SCORE = 0.5f
        const val ONSET_MEMORY = 256
        const val SILENT_DB = -60.0
        const val FLOOR_POWER = 1e-12
        const val MIN_SPREAD_DB = 1.0
        const val SHAPE_RANGE_DB = 60.0
        const val DENSITY_OFFSET = 1.0
        const val LEVEL_STEP_DB = 6.0
        const val DROP_DENSITY = 2.0
        val LN2 = ln(2.0)
    }
}
