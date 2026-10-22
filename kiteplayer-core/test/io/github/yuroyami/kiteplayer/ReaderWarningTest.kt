package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A reader reports what it recovered from, a dropped connection above all, through
 * `MediaIo.setWarningSink`, and the engine installs its warning reporter there. The warning then
 * reaches the player's events and its warning history like any other.
 */
class ReaderWarningTest {

    @Test
    fun aReadersWarningReachesThePlayer() = runTest(timeout = 20.seconds) {
        val harness = CoreHarness(this)
        harness.openThroughIo { ReconnectingMediaIo() }
        harness.run(200.milliseconds)

        val told = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.SourceReconnecting>()
        assertEquals(1, told.size, "the reader's one warning must reach the player once, got $told")
        assertEquals(4_096L, told.single().position)
        assertEquals(
            told.single(),
            harness.events.filterIsInstance<PlayerEvent.Warning>().map { it.warning }
                .filterIsInstance<PlaybackWarning.SourceReconnecting>().single(),
            "the event feed carries the same warning",
        )
        harness.close()
    }

    /** Delivers bytes, and on its second read reports the reconnect a real network reader would. */
    private class ReconnectingMediaIo : MediaIo {
        private var sink: (PlaybackWarning) -> Unit = {}
        private var reads = 0

        override val size: Long? = null
        override val seekable: Boolean = false

        override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
            this.sink = sink
        }

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (reads == 2) sink(PlaybackWarning.SourceReconnecting(4_096, 1, "the scripted connection dropped"))
            return minOf(length, 4_096)
        }

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() = Unit
    }
}
