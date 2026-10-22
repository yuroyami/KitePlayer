package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.VideoScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Surface bounds behind fit, fill and stretch. On Android the picture is not drawn into a
 * canvas the library owns, so these numbers ARE the framing: a hardware decoder writes straight
 * into the Surface this rectangle describes.
 */
class VideoBoundsTest {

    private val wideVideo = 2f

    @Test
    fun fitLetterboxesInsideAViewWiderThanThePicture() {
        assertEquals(
            VideoBounds(left = 500, top = 0, width = 2000, height = 1000),
            videoBounds(3000, 1000, wideVideo, VideoScale.Fit),
        )
    }

    @Test
    fun fillCoversAViewWiderThanThePictureAndOverhangsTheHeight() {
        assertEquals(
            VideoBounds(left = 0, top = -250, width = 3000, height = 1500),
            videoBounds(3000, 1000, wideVideo, VideoScale.Fill),
        )
    }

    @Test
    fun fitPillarboxesInsideAViewTallerThanThePicture() {
        assertEquals(
            VideoBounds(left = 0, top = 250, width = 1000, height = 500),
            videoBounds(1000, 1000, wideVideo, VideoScale.Fit),
        )
    }

    @Test
    fun fillCoversAViewTallerThanThePictureAndOverhangsTheWidth() {
        assertEquals(
            VideoBounds(left = -500, top = 0, width = 2000, height = 1000),
            videoBounds(1000, 1000, wideVideo, VideoScale.Fill),
        )
    }

    @Test
    fun stretchTakesTheWholeViewWhateverThePictureIs() {
        assertEquals(
            VideoBounds(left = 0, top = 0, width = 3000, height = 1000),
            videoBounds(3000, 1000, wideVideo, VideoScale.Stretch),
        )
        assertEquals(
            VideoBounds(left = 0, top = 0, width = 1000, height = 1000),
            videoBounds(1000, 1000, 0.5f, VideoScale.Stretch),
        )
    }

    @Test
    fun aViewShapedLikeThePictureLooksTheSameInEveryMode() {
        val whole = VideoBounds(left = 0, top = 0, width = 2000, height = 1000)
        for (mode in VideoScale.entries) {
            assertEquals(whole, videoBounds(2000, 1000, wideVideo, mode), "mode $mode")
        }
    }

    @Test
    fun nothingToLayOutYet() {
        for (mode in VideoScale.entries) {
            assertNull(videoBounds(3000, 1000, 0f, mode), "no aspect, mode $mode")
            assertNull(videoBounds(0, 1000, wideVideo, mode), "no width, mode $mode")
            assertNull(videoBounds(3000, 0, wideVideo, mode), "no height, mode $mode")
            assertNull(videoBounds(3000, 1000, Float.NaN, mode), "nonsense aspect, mode $mode")
        }
    }
}
