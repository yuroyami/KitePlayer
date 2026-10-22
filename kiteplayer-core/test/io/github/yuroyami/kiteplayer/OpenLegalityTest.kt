package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/** Both open calls replace a session only from Idle, Ended or Failed, so a stop comes first. */
class OpenLegalityTest {

    @Test
    fun `open and openQueue both refuse to replace a playing session`() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        harness.attachRenderer()
        player.open(MediaItem("scripted://first"))
        player.play()
        harness.run(300.milliseconds)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        assertFailsWith<IllegalStateException> { player.open(MediaItem("scripted://second")) }
        assertFailsWith<IllegalStateException> { player.openQueue(listOf(MediaItem("scripted://third"))) }
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Playing, player.state.value.status, "a refused open stopped the player")
        assertEquals(1, harness.backend.openCalls, "a refused open reached the backend")

        player.stop()
        player.openQueue(listOf(MediaItem("scripted://third")))
        assertEquals(2, harness.backend.openCalls, "openQueue after a stop opens")
        harness.close()
    }
}
