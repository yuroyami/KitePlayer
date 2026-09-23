package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class FluctusCatalogTest {
    init { useSkiaGraphics() }

    @Test fun floatingSheetIsAvailableOnceAndOwnsItsState() {
        val first = VizCatalog.create().filter { it.name == "Fluctus" }
        assertEquals(1, first.size, "The requested floating sheet must be selectable")
        val second = VizCatalog.create().single { it.name == "Fluctus" }
        assertNotSame(first.single(), second, "Menu and playback must not share animation state")
    }
}
