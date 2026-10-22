package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.mesh.meanColour
import kotlin.test.Test
import kotlin.test.assertEquals

/** The colour a canvas without blended faces gives a triangle. */
class MeshColourTest {

    @Test
    fun aFadeToClearKeepsAThirdOfItsAlpha() {
        // The middle and two rim corners of a glow: full at the middle, the same colour clear at the rim.
        assertEquals(0x55204060, meanColour(0xFF204060.toInt(), 0x00204060, 0x00204060))
    }

    @Test
    fun aStreakTriangleFromAClearTailKeepsTwoThirds() {
        assertEquals(0xAA204060.toInt(), meanColour(0x00204060, 0xFF204060.toInt(), 0xFF204060.toInt()))
    }

    @Test
    fun oneColourStaysAsItIs() {
        val colour = 0xC8123456.toInt()
        assertEquals(colour, meanColour(colour, colour, colour))
    }

    @Test
    fun everyChannelIsAveragedAndRounded() {
        // 255, 0, 0 in the red channel is 85; 128, 128, 129 is a third above 128, which rounds down.
        assertEquals(0x00550000, meanColour(0x00FF0000, 0x00000000, 0x00000000))
        assertEquals(0x00808000, meanColour(0x00808000, 0x00808000, 0x00818000))
    }
}
