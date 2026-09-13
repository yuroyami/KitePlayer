package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The snapshot to session mapping, which is the half that has no platform in it.
 *
 * Next and previous are the interesting ones: they have to answer for the order the listener is
 * hearing, so a shuffled queue asks the play order and not the list order.
 */
class MediaSessionStateTest {

    private fun snapshot(
        status: PlaybackStatus = PlaybackStatus.Paused,
        queueSize: Int = 0,
        queueIndex: Int = -1,
        queueOrder: List<Int> = emptyList(),
        loop: LoopMode = LoopMode.Off,
        metadata: Map<String, String> = emptyMap(),
    ) = PlayerSnapshot(
        status = status,
        media = MediaItem(uri = "/films/holiday.mkv"),
        duration = 60.seconds,
        seekable = true,
        metadata = metadata,
        loop = loop,
        queue = List(queueSize) { MediaItem(uri = "/films/$it.mkv") },
        queueIndex = queueIndex,
        queueOrder = queueOrder,
    )

    @Test
    fun `a paused player early in a two item queue`() {
        val state = snapshot(queueSize = 2, queueIndex = 0, queueOrder = listOf(0, 1))
            .toMediaSessionState(Progress(position = 12.seconds))
        assertFalse(state.playing)
        assertEquals(12.seconds, state.position)
        assertEquals(60.seconds, state.duration)
        assertTrue(state.hasNext)
        assertFalse(state.hasPrevious)
    }

    @Test
    fun `the last item has no next unless the whole queue loops`() {
        val at = snapshot(queueSize = 2, queueIndex = 1, queueOrder = listOf(0, 1))
        assertFalse(at.toMediaSessionState(Progress()).hasNext)
        assertTrue(at.copy(loop = LoopMode.All).toMediaSessionState(Progress()).hasNext)
    }

    @Test
    fun `next and previous follow the play order and not the list order`() {
        // Shuffled to 1, 0. Sitting on list item 1 is the FIRST thing being heard, so nothing is before it.
        val state = snapshot(queueSize = 2, queueIndex = 1, queueOrder = listOf(1, 0))
            .toMediaSessionState(Progress())
        assertTrue(state.hasNext)
        assertFalse(state.hasPrevious)
    }

    @Test
    fun `no queue means no next and no previous`() {
        val state = snapshot().toMediaSessionState(Progress())
        assertFalse(state.hasNext)
        assertFalse(state.hasPrevious)
    }

    @Test
    fun `the container's tags become the title the artist and the album`() {
        val state = snapshot(
            metadata = mapOf("TITLE" to "A Holiday", "artist" to "Someone", "album" to "Ours"),
        ).toMediaSessionState(Progress())
        assertEquals("A Holiday", state.title)
        assertEquals("Someone", state.artist)
        assertEquals("Ours", state.album)
    }

    @Test
    fun `a file with no title tag falls back to its name`() {
        assertEquals("holiday.mkv", snapshot().toMediaSessionState(Progress()).title)
    }

    @Test
    fun `a blank tag is not a title`() {
        assertEquals(
            "holiday.mkv",
            snapshot(metadata = mapOf("title" to "   ")).toMediaSessionState(Progress()).title,
        )
    }

    @Test
    fun `the album artist stands in when there is no artist`() {
        val state = snapshot(metadata = mapOf("album_artist" to "A Band")).toMediaSessionState(Progress())
        assertEquals("A Band", state.artist)
    }

    private fun phaseOf(status: PlaybackStatus) = snapshot(status = status).toMediaSessionState(Progress()).phase

    @Test
    fun `playing and paused are their own phases`() {
        assertEquals(MediaSessionPhase.Playing, phaseOf(PlaybackStatus.Playing))
        assertEquals(MediaSessionPhase.Paused, phaseOf(PlaybackStatus.Paused))
        assertTrue(snapshot(status = PlaybackStatus.Playing).toMediaSessionState(Progress()).playing)
        assertFalse(snapshot(status = PlaybackStatus.Paused).toMediaSessionState(Progress()).playing)
    }

    @Test
    fun `opening and buffering are the buffering phase and neither is playing`() {
        for (status in listOf(PlaybackStatus.Opening, PlaybackStatus.Buffering)) {
            assertEquals(MediaSessionPhase.Buffering, phaseOf(status), "$status")
            assertFalse(snapshot(status = status).toMediaSessionState(Progress()).playing, "$status")
        }
    }

    @Test
    fun `idle ended and failed are all stopped`() {
        for (status in listOf(PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed)) {
            assertEquals(MediaSessionPhase.Stopped, phaseOf(status), "$status")
        }
    }

    @Test
    fun `a file with a picture says so and a song does not`() {
        assertFalse(snapshot().toMediaSessionState(Progress()).hasVideo)
        val film = snapshot().copy(videoSize = VideoSize(1920, 1080))
        assertTrue(film.toMediaSessionState(Progress()).hasVideo)
        assertTrue(film.toMediaSessionState(Progress()).metadata().hasVideo)
    }

    @Test
    fun `a live stream carries no duration`() {
        val live = snapshot().copy(duration = null, seekable = false)
        val state = live.toMediaSessionState(Progress())
        assertNull(state.duration)
        assertFalse(state.canSeek)
    }

    @Test
    fun `the metadata half ignores a position that moved`() {
        val at = snapshot(metadata = mapOf("title" to "A Holiday"))
        val early = at.toMediaSessionState(Progress(position = 1.seconds)).metadata()
        val later = at.toMediaSessionState(Progress(position = 40.seconds)).metadata()
        assertEquals(early, later)
    }

    @Test
    fun `the metadata half notices a new title`() {
        val early = snapshot(metadata = mapOf("title" to "One")).toMediaSessionState(Progress()).metadata()
        val later = snapshot(metadata = mapOf("title" to "Two")).toMediaSessionState(Progress()).metadata()
        assertNotEquals(early, later)
    }
}
