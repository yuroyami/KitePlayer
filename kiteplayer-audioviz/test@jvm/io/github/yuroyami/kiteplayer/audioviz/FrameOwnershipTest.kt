package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import kotlin.test.Test
import kotlin.test.assertTrue

/** Several views share one published frame, so no built-in drawing may write into its arrays. */
class FrameOwnershipTest {
    init { useSkiaGraphics() }

    @Test
    fun builtInDrawingsLeaveSharedFrameArraysUntouched() {
        val writers = RenderHarness.inParallel(VizCatalog.create().map { it.name }) { name ->
            val drawing = VizCatalog.create().first { it.name == name }
            var before: List<FloatArray> = emptyList()
            var frame: SpectrumFrame? = null
            var changed: String? = null
            RenderHarness.forEachFrame(drawing, 96, 54, 24, VizPalette.Prism, RenderHarness.Song.Lively,
                beforeDraw = { state ->
                    frame = state.frame
                    before = state.frame.arrays().map { it.copyOf() }
                },
            ) { _, step ->
                val after = checkNotNull(frame).arrays()
                if (changed == null && before.indices.any { !before[it].contentEquals(after[it]) }) {
                    changed = "$name at frame $step"
                }
            }
            changed
        }.filterNotNull()
        assertTrue(writers.isEmpty(), "drawings wrote into shared frame arrays: $writers")
    }

    private fun SpectrumFrame.arrays(): List<FloatArray> =
        listOf(bands, peaks, scope, bandsRel, scopeLeft, scopeRight, chroma)
}
