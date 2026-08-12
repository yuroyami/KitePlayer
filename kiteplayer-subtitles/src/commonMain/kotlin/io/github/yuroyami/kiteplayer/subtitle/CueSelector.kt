package io.github.yuroyami.kiteplayer.subtitle

/**
 * The active-cue rule, as one pure function: which cues are visible at a media time.
 *
 * Purity is the whole design. Because the answer depends on nothing but (cues, time), a seek
 * needs no reconstruction machinery at all: asking for the new time IS the reconstruction, in
 * both directions, which old B3 demanded and stateful subtitle engines get wrong. The engine
 * keeps the sorted list and asks on every timing edge; nothing here remembers anything.
 *
 * Overlap policy: every cue whose window contains the instant is active, ordered by layer then
 * start time, so a two-speaker overlap shows both lines and the later speaker stacks above the
 * earlier one (the layout engine stacks bottom-up in this order).
 */
public object CueSelector {

    /** Cues visible at [atMicros], in draw order (lowest first). [cues] must be start-sorted. */
    public fun activeAt(cues: List<SubtitleCue>, atMicros: Long): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val active = ArrayList<SubtitleCue>(2)
        for (cue in cues) {
            if (cue.startMicros > atMicros) break
            if (atMicros < cue.endMicros) active.add(cue)
        }
        active.sortWith(compareBy({ it.layer }, { it.startMicros }))
        return active
    }

    /**
     * The next instant after [atMicros] at which the active set changes, or null when nothing
     * ever changes again. This is what lets the engine sleep between cue edges instead of
     * polling: the visible set is constant on the half-open interval up to this boundary.
     */
    public fun nextChangeAfter(cues: List<SubtitleCue>, atMicros: Long): Long? {
        var next: Long? = null
        for (cue in cues) {
            if (cue.startMicros > atMicros) {
                next = minOf(next ?: cue.startMicros, cue.startMicros)
                // The list is start-sorted, so no later cue can start earlier than this one.
                break
            }
            if (cue.endMicros > atMicros) {
                next = minOf(next ?: cue.endMicros, cue.endMicros)
            }
        }
        return next
    }
}
