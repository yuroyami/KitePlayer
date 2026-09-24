package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JourneyCatalogTest {
    init { useSkiaGraphics() }

    @Test
    fun relatedFormsLiveInsideTheirJourneys() {
        val catalog = VizCatalog.create()
        val names = catalog.map { it.name }
        assertEquals(1, names.count { it == "Pipe" })
        for (name in listOf("Mandala", "Wireframe", "Strobe Web", "Strands", "Wave", "Tunnel", "Radar", "Terrain", "Ripple", "Terrain March")) {
            assertFalse(name in names, "$name is a form of its journey rather than a duplicate menu entry")
        }
    }
}
