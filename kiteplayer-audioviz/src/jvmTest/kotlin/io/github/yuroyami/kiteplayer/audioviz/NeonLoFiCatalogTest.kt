package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFi
import kotlin.test.*

class NeonLoFiCatalogTest {
    init { useSkiaGraphics() }
    @Test fun oneReplacementResolvesTheOldNameAndSearch() {
        val catalog = VizCatalog.create()
        assertEquals(1, catalog.count { it.name == "Neon Lo-Fi" })
        assertFalse(catalog.any { it.name == "Terrain March" })
        val viz = catalog.single { it.name == "Neon Lo-Fi" } as NeonLoFi
        assertTrue(viz.hasFallback); assertEquals(13, viz.params.size)
        val director = VizDirector(catalog, seed = 4)
        director.startWith("Terrain March")
        assertSame(viz, director.current)
        assertTrue(VizCatalog.matchesSearch(viz.name, "terrain"))
        assertTrue(VizCatalog.matchesSearch(viz.name, "neon"))
        assertFalse(VizCatalog.matchesSearch(viz.name, "Flux"))
    }
    @Test fun actualVariationActionAndBrowserRecipeTransferCarryTheLayout() {
        val state = AudioVizState(clock = { VizClockReading(0L) })
        state.directed = false
        val source = state.catalogue.filterIsInstance<NeonLoFi>().single()
        state.drawing = source
        source.params[1].value = 1.7f; source.params[6].value = 0f
        val targetBefore = source.layoutGene.target
        state.mutate()
        assertNotEquals(targetBefore, source.layoutGene.target)
        val image = ImageBitmap(64, 64); val scope = CanvasDrawScope()
        for (i in 1..240) scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(64f, 64f)) {
            with(source) { drawFront(neonState(i)) }
        }
        val preview = NeonLoFi()
        preview.copyRecipeFrom(source)
        assertEquals(source.params.map { it.value }, preview.params.map { it.value })
        assertEquals(source.configurationLayout, preview.configurationLayout)
        assertEquals(source.world.flight.layout, preview.world.flight.layout)
        assertNotSame(source.world.history, preview.world.history)
        assertEquals(0, preview.world.history.count, "A menu instance does not invent or steal past spectra")
    }
    @Test fun aNewFrameAtAnAlreadyDrawnInstantKeepsTheSurfaceSafetyScales() {
        val viz = NeonLoFi(); val scope = CanvasDrawScope(); val image = ImageBitmap(32, 32)
        val first = neonState(1, motion = 0f)
        val second = VizRenderState(neonState(2).frame, first.timeSeconds, 1f / 60, first.palette).also { it.motionScale = 0f; it.lightScale = 0.4f }
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(32f, 32f)) {
            with(viz) { drawFront(first); drawFront(second) }
        }
        assertEquals(0f, viz.world.flight.motion)
        assertEquals(0.0, viz.world.flight.travel)
    }
}
