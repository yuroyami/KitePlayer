package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a screen reader and a keyboard meet in the settings panel (#959). The palette swatches were
 * unnamed clickable pictures with no selected state, and a slider took no focus, so the keyboard and
 * a television remote skipped it, on a touch target 22 dp high.
 */
class SettingsAccessibilityTest {
    init { useSkiaGraphics() }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return out
    }

    @Test
    fun eachSwatchIsANamedChoiceAndEachSliderTakesFocus() {
        val state = AudioVizState(clock = { VizClockReading(0L) })
        val palettes = VizPalette.entries.take(3)
        state.palette = palettes[1]
        val scene = ImageComposeScene(480, 6000, Density(1f), content = { AudioVizSettings(state, palettes = palettes) })
        try {
            scene.render(0L).close()
            val all = nodes(scene)
            for (palette in palettes) {
                val swatch = all.firstOrNull { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(palette.name) == true &&
                        node.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
                }
                assertTrue(swatch != null, "no named radio button for the palette ${palette.name}")
                assertEquals(palette == state.palette, swatch.config.getOrNull(SemanticsProperties.Selected), "${palette.name} says the wrong selected state")
            }
            val sliders = all.filter { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
            assertTrue(sliders.isNotEmpty(), "the panel shows no slider")
            for (slider in sliders) {
                assertTrue(slider.config.getOrNull(SemanticsProperties.Focused) != null, "a slider takes no focus")
                assertTrue(slider.boundsInRoot.height >= 48f, "a slider's touch target is ${slider.boundsInRoot.height} dp high")
            }
        } finally {
            scene.close()
        }
    }
}
