package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizNeed
import io.github.yuroyami.kiteplayer.audioviz.viz.VizSilence
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GlitchCatalogTest {
    init { useSkiaGraphics() }

    @Test
    fun glitchIsOneSelectablePresetWhoseMappingNeedsNoRuntimeShader() {
        val entries = VizCatalog.create().filter { it.name == "Glitch" }
        assertEquals(1, entries.size, "Glitch must be one selectable preset")
        val glitch = entries.single()
        assertTrue(glitch is Glitch)
        assertNotSame(glitch, VizCatalog.create().single { it.name == "Glitch" }, "each use gets its own drawing")
        val mapping = checkNotNull(glitch.mapping)
        assertTrue(VizNeed.RuntimeShader !in mapping.needs, "the whole picture draws on every platform")
        assertTrue(VizNeed.ShaderLayers !in mapping.needs, "the datamosh needs no shader, so it runs on software canvases")
        assertEquals(VizSilence.Still, mapping.silence)
        val named = listOf(VizDriver.Bands, VizDriver.Waveform, VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit,
            VizDriver.Pulse, VizDriver.Section, VizDriver.Breakdown, VizDriver.Drop)
        assertTrue(mapping.drivers.containsAll(named), "the declared drivers ${mapping.drivers} miss ${named - mapping.drivers}")
    }
}
