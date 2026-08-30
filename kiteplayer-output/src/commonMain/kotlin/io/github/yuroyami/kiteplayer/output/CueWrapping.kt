package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueWrap

/**
 * The width a rasterizer should break lines at, which is what [CueWrap] actually means.
 *
 * All three built-in rasterizers hand the safe width to their platform's line breaker and let it
 * decide. That is `CueWrap.None` and it was the only mode any of them did, whatever the cue said.
 * The three modes differ ONLY in the number handed over, so the rule lives here once instead of
 * three times:
 *
 * - [CueWrap.None] breaks at the safe width. Greedy: every line is filled before the next starts,
 *   so a two-line cue often reads as a full line and one short word.
 * - [CueWrap.Balanced] breaks at the NARROWEST width that still fits the text in the same space,
 *   so those two lines come out near-equal. This is the default and what viewers expect.
 * - [CueWrap.Never] does not break at all; only the author's own newlines do.
 *
 * [extentAt] must lay the text out at the given width and return how much VERTICAL room it took.
 * Any unit works as long as it only falls as the width grows: the desktop rasterizer counts lines,
 * the Apple one asks CoreText for a fitted height. It is only called for [CueWrap.Balanced].
 */
internal fun wrapWidthFor(
    wrap: CueWrap,
    safeWidth: Int,
    extentAt: (Int) -> Int,
): Int {
    if (safeWidth <= 0) return safeWidth
    return when (wrap) {
        CueWrap.None -> safeWidth
        CueWrap.Never -> NO_WRAP_WIDTH
        CueWrap.Balanced -> balancedWidth(safeWidth, extentAt)
    }
}

/**
 * Wide enough that no subtitle reaches it, so the platform breaker never fires.
 *
 * A sentinel rather than `Int.MAX_VALUE`: the platforms multiply this by a scale or turn it into a
 * float rect, and the largest int overflows both.
 */
internal const val NO_WRAP_WIDTH: Int = 1 shl 20

private fun balancedWidth(safeWidth: Int, extentAt: (Int) -> Int): Int {
    val target = extentAt(safeWidth)
    // The text is no taller at the safe width than it is with no limit at all, so nothing broke
    // and there is no break to move. This is most cues, and it is why the search below is rare.
    if (target <= extentAt(NO_WRAP_WIDTH)) return safeWidth
    // The extent only ever falls as the width grows, so the narrowest width holding this same
    // extent is a plain binary search.
    var low = 1
    var high = safeWidth
    while (low < high) {
        val mid = low + (high - low) / 2
        if (extentAt(mid) <= target) high = mid else low = mid + 1
    }
    return low
}
