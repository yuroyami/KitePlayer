package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The safe area's own bounds: fractions from 0 to 0.45, so a tenth of the output always stays for text. */
class SubtitleSafeAreaTest {

    @Test
    fun insetsFromZeroToFortyFiveHundredthsAreAccepted() {
        val widest = SubtitleSafeArea(left = 0.45f, top = 0.45f, right = 0.45f, bottom = 0.45f)
        assertEquals(0.45f, widest.left)
        assertEquals(SubtitleSafeArea(), SubtitleSafeArea.None)
    }

    @Test
    fun negativeOversizedAndNonFiniteInsetsAreRefused() {
        assertFailsWith<IllegalArgumentException> { SubtitleSafeArea(left = -0.01f) }
        assertFailsWith<IllegalArgumentException> { SubtitleSafeArea(top = 0.46f) }
        assertFailsWith<IllegalArgumentException> { SubtitleSafeArea(right = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SubtitleSafeArea(bottom = Float.POSITIVE_INFINITY) }
    }
}
