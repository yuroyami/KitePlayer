package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.HdrPolicy
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Metal renderer says it shows HDR exactly where it draws HDR on the extended-range layer (#540). */
class MetalShowsHdrTest {

    private class FixedHeadroom(override var potential: Float) : HeadroomSource {
        override val current: Float = 1f
        override val known: Boolean = true
        var refreshes = 0
        override fun refresh() { refreshes += 1 }
        override fun close() = Unit
    }

    private fun renderer(headroom: HeadroomSource) =
        MetalVideoRenderer(CAMetalLayer(), MetalPictureResolver { null }) { headroom }

    @Test
    fun aDisplayThatGoesBeyondStandardWhiteShowsHdr() {
        val headroom = FixedHeadroom(potential = 2f)
        val renderer = renderer(headroom)
        try {
            // The current headroom is 1 until a layer asks for extended range: the potential one decides.
            assertTrue(renderer.showsHdr)
            assertTrue(headroom.refreshes > 0, "each answer asks the display again")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aStandardRangeDisplayDoesNot() {
        val renderer = renderer(FixedHeadroom(potential = 1f))
        try {
            assertFalse(renderer.showsHdr)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theAnswerFollowsTheLayerToAnotherDisplay() {
        val headroom = FixedHeadroom(potential = 1f)
        val renderer = renderer(headroom)
        try {
            assertFalse(renderer.showsHdr)
            headroom.potential = 8f
            assertTrue(renderer.showsHdr)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aRendererToldToToneMapDoesNotShowHdr() {
        val renderer = renderer(FixedHeadroom(potential = 2f))
        try {
            renderer.setHdrPolicy(HdrPolicy.ToneMap)
            assertFalse(renderer.showsHdr)
            renderer.setHdrPolicy(HdrPolicy.Auto)
            assertTrue(renderer.showsHdr)
        } finally {
            renderer.close()
        }
    }

    /** This Mac's own display, read through the real reader rather than a fixed one. */
    @Test
    fun theRealDisplayAnswersByItsPotentialHeadroom() {
        // Screens belong to the main thread, and off it the first reading waits for a run loop.
        if (!platform.Foundation.NSThread.isMainThread) return
        val layer = CAMetalLayer()
        val renderer = MetalVideoRenderer(layer, MetalPictureResolver { null })
        try {
            val potential = readScreenHeadroom(layer).first
            assertEquals(potential > 1.05f, renderer.showsHdr, "the display's potential headroom is $potential")
        } finally {
            renderer.close()
        }
    }
}
