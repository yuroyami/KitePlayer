package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizNeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GlitchCatalogTest {
    init { useSkiaGraphics() }

    @Test fun oneIndependentGlitchOffersLayerAndMotionControls() {
        val entries = VizCatalog.create().filter { it.name == "Glitch" }
        assertEquals(1, entries.size, "Glitch must be one selectable preset")
        val a = entries.single()
        val b = VizCatalog.create().single { it.name == "Glitch" }
        assertNotSame(a, b)
        assertTrue(a.paintsWholeScreen)
        assertTrue(VizNeed.RuntimeShader !in checkNotNull(a.mapping).needs, "All platforms keep the same native geometry")
        val names = a.params.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("Composition", "Response", "Scene changes", "Travel", "Rotation",
            "Density", "Scale", "Wheel", "Crystals", "Eclipse", "Ribbons", "Tunnel", "Stars",
            "Colour spread", "Brightness", "Glow", "Chromatic split")))
        assertEquals(listOf("Auto", "Spectrum", "Crystal", "Ribbon", "Tunnel", "Eclipse"),
            a.params.single { it.name == "Composition" }.choices)
        val response = a.params.single { it.name == "Response" }
        response.value = response.max
        assertEquals(b.params.single { it.name == "Response" }.default,
            b.params.single { it.name == "Response" }.value, "Preview controls belong to their own instance")
    }
}
