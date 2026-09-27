package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayAwakeHolderTest {

    private val calls = mutableListOf<String>()
    private val holder = DisplayAwakeHolder(hold = { calls += "hold" }, release = { calls += "release" })

    @Test
    fun aViewOnScreenHoldsWhileItsPlayerPlaysAndReleasesWhenItStops() {
        holder.onScreen = true
        holder.playing = true
        assertTrue(holder.holding)
        holder.playing = false
        assertFalse(holder.holding)
        assertEquals(listOf("hold", "release"), calls)
    }

    @Test
    fun leavingTheScreenReleasesAndComingBackHoldsAgain() {
        holder.playing = true
        holder.onScreen = true
        holder.onScreen = false
        holder.onScreen = true
        assertEquals(listOf("hold", "release", "hold"), calls)
    }

    @Test
    fun aDisabledViewNeverHolds() {
        holder.enabled = false
        holder.onScreen = true
        holder.playing = true
        assertEquals(emptyList(), calls)
        holder.enabled = true
        assertEquals(listOf("hold"), calls)
        holder.enabled = false
        assertEquals(listOf("hold", "release"), calls)
    }

    @Test
    fun aRepeatedReportChangesNothing() {
        holder.onScreen = true
        holder.playing = true
        holder.playing = true
        holder.onScreen = true
        assertEquals(listOf("hold"), calls)
    }

    @Test
    fun onlyPlayingVideoCounts() {
        val video = VideoSize(1920, 1080)
        assertTrue(playsVideo(PlayerSnapshot(status = PlaybackStatus.Playing, videoSize = video)))
        assertFalse(playsVideo(PlayerSnapshot(status = PlaybackStatus.Paused, videoSize = video)))
        assertFalse(playsVideo(PlayerSnapshot(status = PlaybackStatus.Buffering, videoSize = video)))
        assertFalse(playsVideo(PlayerSnapshot(status = PlaybackStatus.Playing, videoSize = null)), "audio only")
        assertFalse(playsVideo(null))
    }
}
