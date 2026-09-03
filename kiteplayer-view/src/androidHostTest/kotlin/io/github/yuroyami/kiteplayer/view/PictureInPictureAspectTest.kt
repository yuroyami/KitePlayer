package io.github.yuroyami.kiteplayer.view

import kotlin.test.Test
import kotlin.test.assertEquals

/** The aspect a picture-in-picture window asks for: the video's own, turned with it, within the OS limits. */
class PictureInPictureAspectTest {

    @Test
    fun aQuarterTurnSwapsTheAspect() {
        assertEquals(PipAspect(9, 16), pictureInPictureAspect(1920, 1080, 90))
        assertEquals(PipAspect(9, 16), pictureInPictureAspect(1920, 1080, 270))
    }

    @Test
    fun anUprightFrameKeepsItsAspectReduced() {
        assertEquals(PipAspect(16, 9), pictureInPictureAspect(1920, 1080, 0))
        assertEquals(PipAspect(16, 9), pictureInPictureAspect(1920, 1080, 180))
        assertEquals(PipAspect(4, 3), pictureInPictureAspect(640, 480, 0))
    }

    @Test
    fun aRibbonIsClampedToTheWidestTheSystemAllows() {
        // Android refuses anything wider than 2.39:1 or taller than 1:2.39.
        assertEquals(PipAspect(239, 100), pictureInPictureAspect(4000, 100, 0))
        assertEquals(PipAspect(100, 239), pictureInPictureAspect(4000, 100, 90))
    }

    @Test
    fun anUnknownSizeFallsBackToSixteenNine() {
        assertEquals(PipAspect(16, 9), pictureInPictureAspect(0, 0, 0))
        assertEquals(PipAspect(16, 9), pictureInPictureAspect(-1, 5, 0))
    }
}
