package io.github.yuroyami.kiteplayer.audioviz.viz.field

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import kotlin.math.max
import kotlin.math.min

/**
 * Lace that grows in a memory field's extra channel: Marble's Gray-Scott reaction.
 *
 * Two chemicals meet. The feed is poured in everywhere and stays where it is, here. The lace eats
 * it, spreads, and lives in [MemoryField.extra], so the field's flow carries it like ink. The recipe
 * of [feed] and [kill] decides the pattern; the default grows calm coral. It grows only where the
 * mask is above zero; a cell outside the mask keeps no lace.
 */
internal class LaceReaction(
    var feed: Float = CORAL_FEED,
    var kill: Float = CORAL_KILL,
) {
    private var food = FloatArray(0)
    private var nextFood = FloatArray(0)
    private var nextLace = FloatArray(0)

    /** One step of the reaction over [field], inside [mask]: one value from 0 to 1 per cell, row by row. */
    fun step(field: MemoryField, mask: FloatArray) {
        val lace = field.extra ?: return
        val columns = field.columns
        val rows = field.rows
        val cells = columns * rows
        require(mask.size >= cells) { "the mask has ${mask.size} cells, the field $cells" }
        fit(cells)
        for (row in 0 until rows) {
            val here = row * columns
            val up = max(row - 1, 0) * columns
            val down = min(row + 1, rows - 1) * columns
            for (column in 0 until columns) {
                val left = max(column - 1, 0)
                val right = min(column + 1, columns - 1)
                val cell = here + column
                val a = food[cell]
                val b = lace[cell]
                val spreadA = 0.2f * (food[here + left] + food[here + right] + food[up + column] + food[down + column]) +
                    0.05f * (food[up + left] + food[up + right] + food[down + left] + food[down + right]) - a
                val spreadB = 0.2f * (lace[here + left] + lace[here + right] + lace[up + column] + lace[down + column]) +
                    0.05f * (lace[up + left] + lace[up + right] + lace[down + left] + lace[down + right]) - b
                val meeting = a * b * b
                val wet = mask[cell].coerceIn(0f, 1f)
                nextFood[cell] = ((a + spreadA - meeting + feed * (1f - a)) * wet + (1f - wet)).coerceIn(0f, 1f)
                nextLace[cell] = ((b + 0.5f * spreadB + meeting - (kill + feed) * b) * wet).coerceIn(0f, 1f)
            }
        }
        val oldFood = food
        food = nextFood
        nextFood = oldFood
        nextLace.copyInto(lace, 0, 0, cells)
        field.markChanged()
    }

    /** Plants [count] seeds of lace, each [radius] cells across, at random cells inside [mask]. */
    fun sprout(field: MemoryField, mask: FloatArray, count: Int, radius: Float, random: Rng) {
        val lace = field.extra ?: return
        val columns = field.columns
        val rows = field.rows
        fit(columns * rows)
        var planted = 0
        var tries = 0
        while (planted < count && tries < count * 8) {
            tries++
            val x = (random.next() * columns).toInt().coerceIn(0, columns - 1)
            val y = (random.next() * rows).toInt().coerceIn(0, rows - 1)
            if (mask[y * columns + x] < 0.5f) continue
            val reach = radius.toInt() + 1
            for (dy in -reach..reach) for (dx in -reach..reach) {
                if (dx * dx + dy * dy > radius * radius) continue
                val column = x + dx
                val row = y + dy
                if (column !in 0 until columns || row !in 0 until rows) continue
                if (mask[row * columns + column] <= 0f) continue
                lace[row * columns + column] = 0.9f
                food[row * columns + column] = 0.3f
            }
            planted++
        }
        field.markChanged()
    }

    /** Forgets the feed chemical; the next step starts from a full feed. */
    fun reset() {
        food = FloatArray(0)
    }

    private fun fit(cells: Int) {
        if (food.size == cells) return
        food = FloatArray(cells) { 1f }
        nextFood = FloatArray(cells)
        nextLace = FloatArray(cells)
    }

    companion object {
        /** Feed and kill for calm coral, the recipe Marble grew in a breakdown. */
        const val CORAL_FEED = 0.0545f
        const val CORAL_KILL = 0.062f
    }
}
