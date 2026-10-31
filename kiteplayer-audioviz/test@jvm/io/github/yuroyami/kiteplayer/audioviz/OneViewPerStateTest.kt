package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.KiteLog
import kotlin.test.Test
import kotlin.test.assertEquals

/** A state shown by two views says so, because its drawings then step twice a frame (#674). */
class OneViewPerStateTest {
    init { useSkiaGraphics() }

    private fun shown(views: Int, lines: MutableList<String>): AudioVizState {
        val state = AudioVizState(clock = { VizClockReading(0L) })
        val scene = ImageComposeScene(320, 360, Density(1f), content = {
            Column {
                repeat(views) { KiteAudioViz(state, Modifier.size(160.dp, 90.dp)) }
            }
        })
        try {
            scene.render(0L).close()
            assertEquals(views, ShownStates.count(state))
        } finally {
            scene.close()
        }
        assertEquals(0, ShownStates.count(state), "a view that left the composition is still counted")
        return state
    }

    @Test
    fun aSecondViewOfOneStateIsReported() {
        val lines = mutableListOf<String>()
        KiteLog.install { tag, message -> if (tag == "KiteAudioViz") lines += message }
        try {
            shown(views = 1, lines)
            assertEquals(emptyList(), lines, "one view is the rule, not a warning")
            shown(views = 2, lines)
            assertEquals(1, lines.size, "two views of one state went unreported: $lines")
        } finally {
            KiteLog.install(null)
        }
    }
}
