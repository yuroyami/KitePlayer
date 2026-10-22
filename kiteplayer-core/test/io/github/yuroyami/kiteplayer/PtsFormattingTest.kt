package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Pts] formats readably, including the one value that used to print nonsense.
 *
 * `Pts` has no sentinel for "absent" on purpose: a timestamp that may be missing is a `Pts?`. So a
 * `Pts` holding FFmpeg's `AV_NOPTS_VALUE` means one leaked through a backend boundary, and that is
 * exactly when a reader needs the string to say so.
 */
class PtsFormattingTest {

    @Test
    fun `the FFmpeg absent-timestamp value is named rather than formatted`() {
        // Negating Long.MIN_VALUE overflows back to itself, so every field came out negative and
        // this printed as "-2562047:-47:-16.-854": a string that reads like a timestamp.
        assertEquals("unset", Pts(Long.MIN_VALUE).toString())
    }

    @Test
    fun `ordinary timestamps still format the way they did`() {
        assertEquals("0s", Pts.Zero.toString())
        assertEquals("00:01.500", Pts(1_500_000).toString())
        assertEquals("1:02:03.004", Pts(3_723_004_000).toString())
        assertEquals("-00:02.000", Pts(-2_000_000).toString())
    }

    @Test
    fun `a large negative timestamp that is not the sentinel still formats`() {
        // One microsecond away from the sentinel is an ordinary value and must not be swallowed by
        // the special case: the guard has to be equality, never a magnitude threshold.
        val text = Pts(Long.MIN_VALUE + 1).toString()
        assertTrue(text != "unset", "only the exact sentinel is named, got $text")
        assertTrue(text.startsWith("-"), "and it is still a negative timestamp, got $text")
    }
}
