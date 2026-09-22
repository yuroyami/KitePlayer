package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JourneyCatalogTest {
    init { useSkiaGraphics() }

    @Test
    fun relatedFormsLiveInsideTheirJourneys() {
        val catalog = VizCatalog.create()
        val names = catalog.map { it.name }
        for (name in listOf("Mandala", "Pipe")) {
            assertEquals(1, names.count { it == name })
            assertTrue(catalog.single { it.name == name }.params.any { it.name == "Journey" },
                "$name needs automatic travel and selectable former forms")
        }
        for (name in listOf("Wireframe", "Strobe Web", "Strands", "Wave", "Tunnel", "Radar", "Terrain", "Ripple", "Terrain March")) {
            assertFalse(name in names, "$name is a form of its journey rather than a duplicate menu entry")
        }
    }
}
