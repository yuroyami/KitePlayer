package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Each tile of the drawing browser sees every event once (#673). The browser took one frame source
 * for its grid and wrote what it took into one shared state on a timer that drifted from the tiles'
 * own clocks, so a tile stepped one frame twice with its events, punching twice for one kick, or
 * never saw a frame and lost its events.
 */
class BrowserTileEventsTest {
    init { useSkiaGraphics() }

    private val worker = ManualVizDispatcher()
    private val feed = AudioVizFeed(worker)

    @AfterTest
    fun closeFeed() {
        feed.close()
        worker.runAll()
    }

    /** Draws nothing, and keeps the events of each step it is drawn at, once per step. */
    private class Counting(override val name: String) : Visualization {
        override val transitions: List<VizTransition> get() = emptyList()
        private var lastInstant = Double.NaN
        val seen = ArrayList<String>()

        override fun DrawScope.draw(state: VizRenderState) {
            if (state.instant == lastInstant) return
            lastInstant = state.instant
            val events = state.frame.events ?: return
            for (i in 0 until events.size) seen += keyOf(events[i])
        }
    }

    @Test
    fun eachTileSeesEveryEventOnce() {
        var position = 0L
        val state = AudioVizState(feed = feed) { VizClockReading(position) }
        state.displayDelay = Duration.ZERO
        state.framesPerSecond = 60
        feed.hear(0L, 8f, afterBlock = worker::runAll)
        val reference = state.frameSource()
        val published = ArrayList<String>()
        val tiles = listOf(Counting("first"), Counting("second"))
        val scene = ImageComposeScene(960, 540, Density(1f), content = {
            AudioVizBrowserOf(state, Modifier.fillMaxSize(), onPick = {}, makeTiles = { tiles })
        })
        try {
            var nanos = 0L
            // A 60 Hz display with its frames a little uneven, as a busy main thread draws them.
            for (frame in 0 until 60 * 7) {
                position += 1_000_000L / 60
                state.nextFrame()
                reference.take().events?.let { for (i in 0 until it.size) published += keyOf(it[i]) }
                nanos += 16_666_667L + (frame % 3) * 1_000_000L
                scene.render(nanos).close()
            }
        } finally {
            scene.close()
        }
        assertTrue(published.size > 10, "the drum loop should deliver events, had ${published.size}")
        for (tile in tiles) {
            assertEquals(tile.seen.size, tile.seen.toSet().size, "${tile.name} stepped an event twice: ${tile.seen}")
            assertTrue(tile.seen.isNotEmpty(), "${tile.name} saw no event at all")
            // A tile steps at 15 fps, so it can end before the last events the reference took.
            val missing = published.toSet() - tile.seen.toSet()
            assertTrue(missing.size <= 2, "${tile.name} never saw ${missing.size} events: $missing")
        }
    }

    private companion object {
        fun keyOf(delivered: DeliveredAudioEvent): String {
            val detection = delivered.event.detection
            return "${detection.kind}:${detection.ptsMicros}:${delivered.event.sequence}"
        }
    }
}
