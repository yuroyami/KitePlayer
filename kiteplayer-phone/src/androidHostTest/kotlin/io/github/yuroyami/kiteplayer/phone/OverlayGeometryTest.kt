package io.github.yuroyami.kiteplayer.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayGeometryTest {
    @Test
    fun quarterTurnMapsCueIntoRotatedViewportWithoutRotatingItsGlyphs() {
        assertEquals(
            OverlayRect(left = 560, top = 180, right = 760, bottom = 220),
            overlayRect(
                rotationDegrees = 90,
                viewportWidth = 1920,
                viewportHeight = 1080,
                x = 100,
                y = 400,
                imageWidth = 200,
                imageHeight = 40,
            ),
        )
    }

    @Test
    fun threeQuarterTurnMapsCueIntoRotatedViewport() {
        assertEquals(
            OverlayRect(left = 320, top = 1700, right = 520, bottom = 1740),
            overlayRect(
                rotationDegrees = 270,
                viewportWidth = 1920,
                viewportHeight = 1080,
                x = 100,
                y = 400,
                imageWidth = 200,
                imageHeight = 40,
            ),
        )
    }
}
