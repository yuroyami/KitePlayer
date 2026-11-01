package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the chroma sample sits, decided by the container and not by an image effect.
 *
 * The read moves TOWARDS where the sample sits from the texel centre, as libplacebo and mpv move
 * it: a left-sited sample is on the even luma column, left of its texel's centre, so the read
 * moves half a luma texel right. The first correction moved it left and doubled the half-pixel
 * fringe it was meant to remove, and this test then asserted that sign (#604).
 *
 * The shader used to move chroma half a luma texel left whenever debanding was on, for every
 * format, and not at all when it was off. So turning on a smoothing pass moved the colour, 4:4:4
 * was shifted despite having nothing to shift, and centre-sited content was shifted the wrong way.
 * The visible symptom is a coloured fringe on a hard vertical edge.
 */
class ChromaSampleOffsetTest {

    private fun offset(
        location: ChromaLocation,
        shiftX: Int = 1,
        shiftY: Int = 1,
        width: Int = 1920,
        height: Int = 1080,
    ) = chromaSampleOffset(location, shiftX, shiftY, width, height)

    @Test
    fun leftSitedFourTwoZeroReadsHalfALumaTexelRightAndNothingDown() {
        val (x, y) = offset(ChromaLocation.Left)
        assertEquals(0.5f / 1920f, x)
        assertEquals(0f, y)
    }

    @Test
    fun anUnspecifiedSitingReadsAsLeft() {
        assertEquals(offset(ChromaLocation.Left), offset(ChromaLocation.Unspecified))
    }

    @Test
    fun centreSitedContentIsNotMoved() {
        assertEquals(0f to 0f, offset(ChromaLocation.Center))
    }

    @Test
    fun fourFourFourIsNotMovedAtAll() {
        // Nothing is subsampled, so there is no offset to correct, whatever the siting says.
        for (location in ChromaLocation.entries) {
            assertEquals(0f to 0f, offset(location, shiftX = 0, shiftY = 0), "$location")
        }
    }

    @Test
    fun fourTwoTwoMovesHorizontallyAndNeverVertically() {
        val (x, y) = offset(ChromaLocation.Left, shiftX = 1, shiftY = 0)
        assertEquals(0.5f / 1920f, x)
        assertEquals(0f, y, "4:2:2 subsamples only across, so nothing moves down")
    }

    @Test
    fun topAndBottomSitingsMoveVerticallyInOppositeDirections() {
        assertEquals(0.5f / 1080f, offset(ChromaLocation.Top).second)
        assertEquals(-0.5f / 1080f, offset(ChromaLocation.Bottom).second)
        assertEquals(0.5f / 1080f, offset(ChromaLocation.TopLeft).second)
        assertEquals(-0.5f / 1080f, offset(ChromaLocation.BottomLeft).second)
    }

    @Test
    fun theCornerSitingsStillReadRightAsWellAsUpOrDown() {
        assertEquals(0.5f / 1920f, offset(ChromaLocation.TopLeft).first)
        assertEquals(0.5f / 1920f, offset(ChromaLocation.BottomLeft).first)
        // Top and Bottom name no horizontal rule, so they keep the centre's horizontal answer.
        assertEquals(0f, offset(ChromaLocation.Top).first)
        assertEquals(0f, offset(ChromaLocation.Bottom).first)
    }

    @Test
    fun aPictureWithNoSizeIsNotDividedByZero() {
        assertEquals(0f to 0f, offset(ChromaLocation.Left, width = 0, height = 0))
    }

    @Test
    fun theOffsetScalesWithThePicture() {
        // Half a luma texel is a smaller fraction of a wider picture, which is the whole point of
        // expressing it in normalized coordinates rather than in texels.
        assertEquals(0.5f / 640f, offset(ChromaLocation.Left, width = 640).first)
        assertEquals(0.5f / 3840f, offset(ChromaLocation.Left, width = 3840).first)
    }
}
