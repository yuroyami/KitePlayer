package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlatCatalogTest {
    init { useSkiaGraphics() }

    @Test fun onlyTheRetainedPresetsAreSelectable() {
        assertEquals(listOf("Bars", "Ocean Mist", "Twin Bloom", "Contour", "Alchemy", "Nebula Field",
            "Kaleidoscope", "Pipe", "Thin Ice", "Fluctus", "Glitch", "Odyssey", "Neon Lo-Fi", "Marble",
            "Silk", "Lines", "Honeycomb", "Fracture", "Threads", "Iris", "Fireworks", "Wavy Spiral",
            "Musical Spectrum", "Muser"), VizCatalog.create().map { it.name })
    }

    @Test fun removedImplementationsAreNotPackaged() {
        val removed = listOf("Skidmark", "Sunburst", "Blur", "Fountain", "Gravity", "Sparkle",
            "Breath", "Tide", "Lantern", "Nebula", "Smoke", "Reactor", "RingFlight", "Mandala",
            "Drift", "Melt", "Lava", "PrismBurst", "OilSlick", "Ink", "LensRain", "Twist",
            "FlowField", "Phosphor", "FractalZoom", "BlobField", "Shatter", "RippleWell", "ReactionDiffusion", "SmokeRise", "SmokeVortices", "Aurora", "AuroraField", "Gemini", "Bloom", "Pulse",
            "Plasma", "PlasmaMaterialKt", "Stereogram")
        val root = "io.github.yuroyami.kiteplayer.audioviz.viz."
        for (name in removed) {
            val area = if (name == "BlobField" || name == "AuroraField") "shader" else "presets"
            assertTrue(runCatching { Class.forName("$root$area.$name") }.exceptionOrNull() is ClassNotFoundException,
                "$name must be deleted, not merely hidden from the picker")
        }
    }

    @Test fun drawingsAndCatalogueExposeNoCategoryContract() {
        assertFalse(Visualization::class.java.methods.any { it.name == "getFamily" })
        assertFalse(VizCatalog::class.java.methods.any { it.name == "byFamily" })
        assertTrue(runCatching { Class.forName("io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily") }
            .exceptionOrNull() is ClassNotFoundException)
    }
}
