package io.github.yuroyami.kiteplayer.audioviz

/**
 * The structural events of one installed song map, sorted by original time.
 *
 * [identity] changes whenever a different map is installed. [coveredFromMicros] through
 * [coveredThroughMicros] is the media time with complete structural analysis; a live structural
 * event inside it is a duplicate of the map's own decision there.
 */
internal class SongMapEvents(
    val identity: Long,
    val coveredFromMicros: Long,
    val coveredThroughMicros: Long,
    detections: List<AudioDetection>,
) {
    val detections: Array<AudioDetection> = detections.sortedBy { it.ptsMicros }.toTypedArray()

    init {
        require(coveredFromMicros <= coveredThroughMicros)
        require(this.detections.size <= MAX_EVENTS) { "a song map holds at most $MAX_EVENTS structural events" }
        require(this.detections.all { it.kind in STRUCTURAL }) { "a song map delivers structural kinds only" }
    }

    fun covers(ptsMicros: Long): Boolean = ptsMicros in coveredFromMicros..coveredThroughMicros

    /** Index of the first event strictly after [ptsMicros]. */
    fun firstAfter(ptsMicros: Long): Int {
        var low = 0
        var high = detections.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (detections[middle].ptsMicros <= ptsMicros) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val MAX_EVENTS = 4096
        val STRUCTURAL = setOf(AudioEventKind.SectionBoundary, AudioEventKind.Drop, AudioEventKind.Breakdown)
    }
}
