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

    /**
     * When the first view of a state leaves, the one left takes over the state's own drawings
     * (#950). The role was decided once, as a view was composed, so the view left stayed a copy, and
     * with directed play the director never advanced again and the settings no longer reached it.
     */
    @Test
    fun theViewLeftTakesOverWhenTheFirstLeaves() {
        val state = AudioVizState(clock = { VizClockReading(0L) })
        state.framesPerSecond = 0
        val shared = Counting("count")
        state.drawing = shared
        val showFirst = androidx.compose.runtime.mutableStateOf(true)
        val scene = ImageComposeScene(320, 360, Density(1f), content = {
            Column {
                if (showFirst.value) {
                    KiteAudioVizOf(state, Modifier.size(160.dp, 90.dp), post = false) { listOf(Counting("count")) }
                }
                KiteAudioVizOf(state, Modifier.size(160.dp, 90.dp), post = false) { listOf(Counting("count")) }
            }
        })
        try {
            var nanos = 0L
            repeat(20) {
                scene.render(nanos).close()
                nanos += 16_666_667L
            }
            val beforeLeaving = shared.steps
            showFirst.value = false
            repeat(20) {
                scene.render(nanos).close()
                nanos += 16_666_667L
            }
            assertEquals(1, ShownStates.count(state), "one view is left")
            assertTrue(
                shared.steps - beforeLeaving > 10,
                "the view left kept drawing a copy: the state's own drawing stepped ${shared.steps - beforeLeaving} times after the first view left",
            )
        } finally {
            scene.close()
        }
        assertEquals(0, ShownStates.count(state), "a view that left the composition is still counted")
    }
}
