package io.github.yuroyami.kiteplayer.internal

import kotlinx.atomicfu.atomic
import kotlin.math.abs
import kotlin.math.pow

/**
 * How fast a source delivers media while the demuxer reads it, as seconds of media per second
 * spent waiting inside reads (#376).
 *
 * Only the time inside a read counts. When the read-ahead is full the demuxer waits outside any
 * read, so a link too slow for the variant shows as a figure below the playback speed. A reader
 * that fetches ahead on its own serves its reads at once, so the figure can run above the link but
 * never below it: it proves a link too slow, never one fast enough.
 *
 * Recent reads weigh more: each [HALF_LIFE_US] of media halves the weight of what came before, so
 * a link that changes speed shows in the figure within seconds.
 *
 * The demux lane alone calls [read] and [restart], and the actor alone calls
 * [mediaPerReadSecond]. The actor sees only the two published values, so it never reads a measure
 * that a restart has half cleared.
 */
internal class ReadRate {
    // Demux lane only.
    private var weightedMediaUs = 0.0
    private var weightedNanos = 0.0
    private var pendingNanos = 0L
    private var lastUs = NOT_SEEN
    private var countedUs = 0L

    // Published for the actor. A restart clears the span first, so an old span never pairs with a cleared rate.
    private val spanUs = atomic(0L)
    private val rateBits = atomic(Double.NaN.toRawBits())

    /** One read that took [nanos]. [ptsUs] is the packet's time when it belongs to the measured stream. */
    fun read(nanos: Long, ptsUs: Long?) {
        // Reads of the other streams carry no time of their own, so their wait joins the next step.
        pendingNanos += nanos.coerceAtLeast(0L)
        if (ptsUs == null) return
        val last = lastUs
        // The first packet only anchors the timeline, and a jump in the timestamps is not media read.
        if (last == NOT_SEEN || abs(ptsUs - last) > MAX_STEP_US) {
            lastUs = ptsUs
            pendingNanos = 0L
            return
        }
        // Packets come in decode order, so only progress past the newest time counts.
        if (ptsUs <= last) return
        val stepUs = ptsUs - last
        lastUs = ptsUs
        val keep = 0.5.pow(stepUs / HALF_LIFE_US)
        weightedMediaUs = weightedMediaUs * keep + stepUs
        weightedNanos = weightedNanos * keep + pendingNanos
        pendingNanos = 0L
        countedUs += stepUs
        val rate = if (weightedNanos <= 0.0) Double.POSITIVE_INFINITY else weightedMediaUs * 1_000.0 / weightedNanos
        rateBits.value = rate.toRawBits()
        spanUs.value = countedUs
    }

    /** Starts the measure again, after a seek moved the reads elsewhere on the timeline. */
    fun restart() {
        spanUs.value = 0L
        rateBits.value = Double.NaN.toRawBits()
        weightedMediaUs = 0.0
        weightedNanos = 0.0
        pendingNanos = 0L
        lastUs = NOT_SEEN
        countedUs = 0L
    }

    /**
     * Seconds of media per second of reading, recent reads weighing most, or null until at least
     * [minMediaUs] of media was read. Reads that took no measurable time answer an unbounded rate.
     */
    fun mediaPerReadSecond(minMediaUs: Long): Double? {
        if (spanUs.value < minMediaUs) return null
        val rate = Double.fromBits(rateBits.value)
        return if (rate.isNaN()) null else rate
    }

    private companion object {
        const val NOT_SEEN = Long.MIN_VALUE

        /** Media after which an earlier read counts half as much. */
        const val HALF_LIFE_US = 8_000_000.0

        /** The longest gap between two packets of one stream that still counts as media read. */
        const val MAX_STEP_US = 5_000_000L
    }
}
