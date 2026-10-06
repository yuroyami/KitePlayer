package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The size a cover reaches the media session and the notification at (#425): no larger than the
 * system draws it, so a 3,000 pixel cover no longer crosses a binder transaction whole, and never
 * smaller, so it stays sharp where it is drawn largest.
 */
class SessionArtworkSizeTest {

    /** 320 dp on a phone of density 3.5. */
    private val side = 1_120

    @Test
    fun aLargeCoverIsDecodedAtAPowerOfTwoThatStaysAboveTheDrawnSize() {
        assertEquals(2, coverSampleSize(3_000, 3_000, side), "3,000 / 2 = 1,500 is the last step at or above 1,120")
        assertEquals(1, coverSampleSize(2_000, 2_000, side), "halving 2,000 would fall below the drawn size")
        assertEquals(1, coverSampleSize(600, 600, side))
        assertEquals(4, coverSampleSize(4_800, 2_400, side), "the longest side decides")
    }

    @Test
    fun itIsThenHeldToTheDrawnSizeWithItsShapeAndNeverGrown() {
        assertEquals(side to side, fittedSize(1_500, 1_500, side))
        assertEquals(side to 560, fittedSize(2_400, 1_200, side))
        assertEquals(600 to 600, fittedSize(600, 600, side), "a small cover was grown")
    }
}
