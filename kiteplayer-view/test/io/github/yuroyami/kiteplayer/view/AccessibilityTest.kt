package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the three views say about the player, decided once so they cannot drift apart. */
class AccessibilityTest {

    @Test
    fun `playing reads as the state then the position`() {
        assertEquals(
            "Playing, 1:23 of 4:56",
            accessibilityStateText(PlaybackStatus.Playing, 83.seconds, 296.seconds),
        )
    }

    @Test
    fun `paused reads the same way`() {
        assertEquals(
            "Paused, 0:05 of 4:56",
            accessibilityStateText(PlaybackStatus.Paused, 5.seconds, 296.seconds),
        )
    }

    @Test
    fun `a source with no duration says only what it is doing`() {
        // A live stream has nothing to place the position against, and "of 0:00" is worse than
        // silence about it.
        assertEquals("Playing", accessibilityStateText(PlaybackStatus.Playing, 42.seconds, null))
        assertEquals(
            "Playing",
            accessibilityStateText(PlaybackStatus.Playing, 42.seconds, Duration.ZERO),
        )
    }

    @Test
    fun `every status has a word`() {
        val texts = PlaybackStatus.entries.map {
            accessibilityStateText(it, 1.seconds, 10.seconds)
        }
        assertEquals(PlaybackStatus.entries.size, texts.distinct().size, "two statuses read alike: $texts")
        texts.forEach { assertEquals(false, it.isBlank(), "a status read as nothing") }
    }

    @Test
    fun `hours appear only once there are hours`() {
        assertEquals("0:00", clockText(Duration.ZERO))
        assertEquals("0:09", clockText(9.seconds))
        assertEquals("1:00", clockText(60.seconds))
        assertEquals("59:59", clockText(3599.seconds))
        assertEquals("1:00:00", clockText(3600.seconds))
        assertEquals("2:05:07", clockText((2 * 3600 + 5 * 60 + 7).seconds))
    }

    @Test
    fun `a negative position reads as the start rather than as nonsense`() {
        assertEquals("0:00", clockText((-5).seconds))
    }
}
