package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two views of one state each step drawings of their own (#674). Both stepped the state's own
 * drawings, so every drawing stepped twice a frame and its motion ran at twice the speed.
 */
class OneViewPerStateTest {
    init { useSkiaGraphics() }

    /** Draws nothing, and counts the steps it is drawn at. */
    private class Counting(override val name: String) : Visualization {
        override val transitions: List<VizTransition> get() = emptyList()
        private var lastInstant = Double.NaN
        var steps = 0

        override fun DrawScope.draw(state: VizRenderState) {
            if (state.instant == lastInstant) return
            lastInstant = state.instant
            steps++
        }
    }

    @Test
    fun twoViewsOfOneStateEachStepADrawingOfTheirOwn() {
        val state = AudioVizState(clock = { VizClockReading(0L) })
        state.framesPerSecond = 0
        val shared = Counting("count")
        state.drawing = shared
        val copies = mutableListOf<Counting>()
        val scene = ImageComposeScene(320, 360, Density(1f), content = {
            Column {
                repeat(2) {
                    KiteAudioVizOf(state, Modifier.size(160.dp, 90.dp), post = false) {
                        listOf(Counting("count").also { copies += it })
                    }
                }
            }
        })
        try {
            var nanos = 0L
            repeat(31) {
                scene.render(nanos).close()
                nanos += 16_666_667L
            }
        } finally {
            scene.close()
        }
        assertEquals(1, copies.size, "the second view drew the state's own drawing, which then stepped ${shared.steps} times in 31 frames")
        val copy = copies.single()
        assertTrue(copy.steps > 10, "the second view did not draw its own copy, which stepped ${copy.steps} times")
        assertTrue(
            shared.steps <= copy.steps + 2,
            "the state's drawing stepped ${shared.steps} times to its copy's ${copy.steps}, so two views stepped it",
        )
        assertEquals(0, ShownStates.count(state), "a view that left the composition is still counted")
    }
}
