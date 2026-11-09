package io.github.yuroyami.kiteplayer.compose

/**
 * Counts the player's holds on one platform flag that keeps the display awake, and puts back the
 * value the flag had before the first hold once the last one lets go (#932).
 *
 * Writing false at the last release cleared a flag that the app had set for its own reasons, a
 * navigation screen or a presentation, so the device could lock under it. A flag that was already
 * set is left alone throughout. Main thread only, like the flags it guards.
 */
internal class FlagHolds(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
    private var count = 0
    private var before = false

    /** How many holds are taken now. */
    val holds: Int get() = count

    fun acquire() {
        if (count++ > 0) return
        before = read()
        if (!before) write(true)
    }

    fun release() {
        if (count == 0) return
        if (--count > 0) return
        if (!before) write(false)
    }
}
