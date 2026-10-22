package io.github.yuroyami.kiteplayer.subtitle

/**
 * Gives each cue in [cues] that ends at [SubtitleCue.OPEN_END] the start of the next later cue as
 * its end. [cues] is sorted by start, and only the cues from [from] on are looked at.
 *
 * A cue stays open only while no later cue exists, so after one pass the only open cues left share
 * the latest start. A caller that appends a sorted batch therefore passes the index of that last
 * group as [from], and the pass stays short however long the table grows.
 */
internal fun closeOpenCues(cues: MutableList<SubtitleCue>, from: Int = 0) {
    var nextStart: Long? = null
    var followingStart: Long? = null
    for (index in cues.lastIndex downTo from.coerceAtLeast(0)) {
        val cue = cues[index]
        if (followingStart != null && followingStart != cue.startMicros) nextStart = followingStart
        if (cue.endMicros == SubtitleCue.OPEN_END && nextStart != null) cues[index] = cue.endingAt(nextStart)
        followingStart = cue.startMicros
    }
}

/** The index of the first cue that shares the latest start of [cues], or 0 when it is empty. */
internal fun lastStartGroup(cues: List<SubtitleCue>): Int {
    if (cues.isEmpty()) return 0
    val latest = cues.last().startMicros
    var index = cues.lastIndex
    while (index > 0 && cues[index - 1].startMicros == latest) index--
    return index
}

private fun SubtitleCue.endingAt(endMicros: Long): SubtitleCue = when (this) {
    is SubtitleCue.Text -> copy(endMicros = endMicros)
    is SubtitleCue.Bitmap -> copy(endMicros = endMicros)
}
