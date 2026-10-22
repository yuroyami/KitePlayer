package io.github.yuroyami.kiteplayer.subtitle

/**
 * A start-sorted cue list plus the small amount of derived data that makes lookups fast.
 *
 * [CueSelector] answers the same questions and is the definition of what the answers ARE, but it
 * walks the list from the beginning every time. That is free for a film with a few hundred lines
 * and it is not free for the dense typeset ASS tracks this engine is expected to survive, which run
 * to about seventy thousand cues: near the end of one of those, every timing edge costs seventy
 * thousand comparisons, and there is a timing edge on every subtitle pass.
 *
 * This holds the same list, unchanged, next to one extra array: the largest end time seen among the
 * cues up to each position. That is what makes a backward walk terminable. Finding the last cue
 * that could have started is a binary search; from there the walk backward can stop the moment the
 * prefix maximum says no earlier cue can still be showing, which for ordinary subtitles is after a
 * step or two and in the worst case is no worse than the scan it replaces.
 *
 * Built once per cue table. The engine replaces that table wholesale on a track change, so nothing
 * here is ever invalidated in place, and [CueSelector] stays pure for anyone who wants the rule
 * without the index.
 */
public class CueIndex {

    private var cues: List<SubtitleCue> = emptyList()

    /** `maxEndUpTo[i]` is the largest end time among cues 0..i, for the first [built] entries. */
    private var maxEndUpTo: LongArray = LongArray(0)
    private var built: Int = 0
    private var lastBuiltCue: SubtitleCue? = null

    /**
     * Points this index at [cues], reusing what it already computed when it can.
     *
     * The engine's cue table grows by appending decoded batches, which is the hot path and is
     * extended in place. It is also merged when a decoder emits out of order, pruned from the front
     * as playback moves on, and cleared on a track change, and each of those invalidates every
     * position. The identity check on the last cue this index was built from is what tells the two
     * apart: after a pure append it still sits where it did, and after anything else it does not.
     */
    public fun syncTo(cues: List<SubtitleCue>) {
        val appended = built > 0 &&
            cues.size >= built &&
            cues.getOrNull(built - 1) === lastBuiltCue
        if (!appended) {
            this.cues = cues
            maxEndUpTo = LongArray(cues.size)
            built = 0
            extend()
            return
        }
        this.cues = cues
        if (cues.size > maxEndUpTo.size) {
            maxEndUpTo = maxEndUpTo.copyOf(maxOf(cues.size, maxEndUpTo.size * 2 + 1))
        }
        extend()
    }

    private fun extend() {
        var running = if (built > 0) maxEndUpTo[built - 1] else Long.MIN_VALUE
        for (i in built until cues.size) {
            val end = cues[i].endMicros
            if (end > running) running = end
            maxEndUpTo[i] = running
        }
        built = cues.size
        lastBuiltCue = cues.lastOrNull()
    }

    /** Cues visible at [atMicros], in draw order. The same answer [CueSelector.activeAt] gives. */
    public fun activeAt(atMicros: Long): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        val firstAfter = firstStartingAfter(atMicros)
        if (firstAfter == 0) return emptyList()
        val active = ArrayList<SubtitleCue>(2)
        var i = firstAfter - 1
        while (i >= 0) {
            // No cue at or before i reaches this instant, so nothing earlier can be showing either.
            if (maxEndUpTo[i] <= atMicros) break
            val cue = cues[i]
            if (atMicros < cue.endMicros) active.add(cue)
            i--
        }
        // Collected backward, so put it back into list order before sorting. The sort is STABLE and
        // two cues can share both a layer and a start time, so ties keep whatever order they were
        // given: leaving them reversed would produce the right SET in a different draw order, and
        // draw order is what stacks one speaker's line above another's.
        active.reverse()
        active.sortWith(compareBy({ it.layer }, { it.startMicros }))
        return active
    }

    /**
     * The next instant after [atMicros] at which the active set changes, or null when it never does.
     *
     * The same answer [CueSelector.nextChangeAfter] gives: the earliest of the next start and the
     * earliest end among the cues showing now.
     */
    public fun nextChangeAfter(atMicros: Long): Long? {
        if (cues.isEmpty()) return null
        val firstAfter = firstStartingAfter(atMicros)
        var next: Long? = if (firstAfter < cues.size) cues[firstAfter].startMicros else null
        var i = firstAfter - 1
        while (i >= 0) {
            if (maxEndUpTo[i] <= atMicros) break
            val end = cues[i].endMicros
            if (end > atMicros && (next == null || end < next)) next = end
            i--
        }
        return next
    }

    /**
     * The index of the first cue starting strictly after [atMicros], or `cues.size` when none does.
     *
     * An upper bound rather than a plain binary search, because several cues may share a start time
     * and the answer has to be the one past all of them.
     */
    private fun firstStartingAfter(atMicros: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMicros <= atMicros) low = mid + 1 else high = mid
        }
        return low
    }
}
