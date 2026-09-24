package io.github.yuroyami.kiteplayer.session

import android.content.pm.ServiceInfo
import android.media.session.PlaybackState
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.session.MediaNotificationCommand.Custom
import io.github.yuroyami.kiteplayer.session.MediaNotificationCommand.Next
import io.github.yuroyami.kiteplayer.session.MediaNotificationCommand.Pause
import io.github.yuroyami.kiteplayer.session.MediaNotificationCommand.Play
import io.github.yuroyami.kiteplayer.session.MediaNotificationCommand.Previous
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The decisions behind the media notification, read as plain values. Notification.Builder does not
 * run on a host JVM, so these are what the notification copies.
 */
class MediaNotificationLayoutTest {

    private fun content(
        status: PlaybackStatus = PlaybackStatus.Playing,
        hasPrevious: Boolean = false,
        hasNext: Boolean = false,
    ) = MediaNotificationContent(status, "A Holiday", "Skullbeatz", hasPrevious, hasNext, artwork = null)

    private val like = MediaNotificationAction("like", "Like", 17)
    private val shuffle = MediaNotificationAction("shuffle", "Shuffle", 18)

    private fun MediaNotificationLayout.commands() = buttons.map { it.command }

    // The session's custom actions reach the notification inside its content.
    private fun layoutFor(
        content: MediaNotificationContent,
        custom: List<MediaNotificationAction> = emptyList(),
    ) = mediaNotificationLayout(content.copy(customActions = custom))

    @Test
    fun aLoneItemShowsOnlyPlayOrPause() {
        val paused = layoutFor(content(PlaybackStatus.Paused))
        assertEquals(listOf(Play), paused.commands())
        assertEquals(listOf(0), paused.compact)

        val playing = layoutFor(content(PlaybackStatus.Playing))
        assertEquals(listOf(Pause), playing.commands())
    }

    @Test
    fun bufferingOffersPauseBecausePlaybackWasAskedFor() {
        assertEquals(listOf(Pause), layoutFor(content(PlaybackStatus.Buffering)).commands())
    }

    @Test
    fun theEndOffersPlay() {
        assertEquals(listOf(Play), layoutFor(content(PlaybackStatus.Ended)).commands())
    }

    @Test
    fun previousAndNextFollowTheQueue() {
        val both = layoutFor(content(hasPrevious = true, hasNext = true))
        assertEquals(listOf(Previous, Pause, Next), both.commands())
        assertEquals(listOf(0, 1, 2), both.compact)

        val nextOnly = layoutFor(content(hasNext = true))
        assertEquals(listOf(Pause, Next), nextOnly.commands())
        assertEquals(listOf(0, 1), nextOnly.compact)

        val previousOnly = layoutFor(content(PlaybackStatus.Paused, hasPrevious = true))
        assertEquals(listOf(Previous, Play), previousOnly.commands())
        assertEquals(listOf(0, 1), previousOnly.compact)
    }

    @Test
    fun customButtonsComeAfterTheTransportAndStayOutOfTheCollapsedView() {
        val layout = layoutFor(content(hasPrevious = true, hasNext = true), listOf(like, shuffle))
        assertEquals(listOf(Previous, Pause, Next, Custom, Custom), layout.commands())
        assertEquals(listOf("like", "shuffle"), layout.buttons.drop(3).map { it.customId })
        assertEquals(listOf<CharSequence>("Like", "Shuffle"), layout.buttons.drop(3).map { it.label })
        assertEquals(listOf(17, 18), layout.buttons.drop(3).map { it.icon })
        assertEquals(listOf(0, 1, 2), layout.compact)
    }

    @Test
    fun buttonsPastTheFifthAreLeftOut() {
        val many = (1..4).map { MediaNotificationAction("action$it", "Action $it", it) }
        val layout = layoutFor(content(hasPrevious = true, hasNext = true), many)
        assertEquals(MAX_NOTIFICATION_BUTTONS, layout.buttons.size)
        assertEquals(listOf("action1", "action2"), layout.buttons.mapNotNull { it.customId })
    }

    @Test
    fun everyCommandHasItsOwnRequestCodeAndAction() {
        val commands = MediaNotificationCommand.entries
        assertEquals(commands.size, commands.map { it.requestCode }.toSet().size)
        assertEquals(commands.size, commands.map { it.action }.toSet().size)
        for (command in commands) assertEquals(command, MediaNotificationCommand.forAction(command.action))
        assertNull(MediaNotificationCommand.forAction("something.else"))
        assertNull(MediaNotificationCommand.forAction(MediaNotificationIntents.ACTION_FOREGROUND))
    }

    @Test
    fun noTwoButtonsShareARequestCode() {
        val layout = layoutFor(content(hasPrevious = true, hasNext = true), listOf(like, shuffle))
        val codes = layout.buttons.map { it.requestCode }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(listOf(Previous.requestCode, Pause.requestCode, Next.requestCode), codes.take(3))
        assertEquals(listOf(Custom.requestCode, Custom.requestCode + 1), codes.drop(3))
        val fixed = MediaNotificationCommand.entries.filter { it != Custom }.maxOf { it.requestCode }
        assertTrue(Custom.requestCode > fixed)
    }

    @Test
    fun theForegroundTypeIsNamedFromAndroid10() {
        assertNull(foregroundServiceTypeFor(26))
        assertNull(foregroundServiceTypeFor(28))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, foregroundServiceTypeFor(29))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, foregroundServiceTypeFor(36))
    }

    @Test
    fun theArtworkRidesTheNotificationOnlyBeforeAndroid13() {
        assertTrue(notificationCarriesArtwork(26))
        assertTrue(notificationCarriesArtwork(32))
        assertFalse(notificationCarriesArtwork(33))
        assertFalse(notificationCarriesArtwork(36))
    }

    @Test
    fun theNotificationAsksToShowAtOnceFromAndroid12() {
        assertFalse(notificationAsksToShowAtOnce(26))
        assertFalse(notificationAsksToShowAtOnce(30))
        assertTrue(notificationAsksToShowAtOnce(31))
        assertTrue(notificationAsksToShowAtOnce(36))
    }

    @Test
    fun theNotificationReadsTheSnapshot() {
        val snapshot = PlayerSnapshot(
            status = PlaybackStatus.Buffering,
            media = MediaItem("/music/a-holiday.mp3"),
            metadata = mapOf("TITLE" to "A Holiday", "artist" to "Skullbeatz"),
            queue = listOf(MediaItem("/music/one.mp3"), MediaItem("/music/a-holiday.mp3")),
            queueIndex = 1,
            queueOrder = listOf(0, 1),
        )
        val read = snapshot.toMediaNotificationContent(artwork = null, customActions = listOf(like, shuffle))
        assertEquals("A Holiday", read.title)
        assertEquals("Skullbeatz", read.artist)
        assertTrue(read.hasPrevious)
        assertFalse(read.hasNext)
        assertTrue(read.playing)
        // The notification shows the session's own buttons, the same list the system controls show.
        assertEquals(listOf(like, shuffle), read.customActions)
        val shown = mediaNotificationLayout(read).buttons.mapNotNull { it.customId }
        assertEquals(listOf("like", "shuffle"), shown)
    }

    @Test
    fun theCustomActionsRideThePlaybackStateInOrder() {
        val state = MediaSessionState(
            phase = MediaSessionPhase.Paused,
            position = 5.seconds,
            duration = 60.seconds,
            speed = 1.5,
            canSeek = true,
            hasVideo = false,
            title = "A Holiday",
            artist = null,
            album = null,
            hasNext = false,
            hasPrevious = false,
        )
        val playback = sessionPlaybackFor(state, listOf(shuffle, like))
        assertEquals(listOf("shuffle", "like"), playback.customActions.map { it.id })
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(5_000L, playback.positionMillis)
        assertEquals(0f, playback.speed)
        assertEquals(actionsFor(state), playback.actions)

        assertTrue(sessionPlaybackFor(state, emptyList()).customActions.isEmpty())
        val playing = state.copy(phase = MediaSessionPhase.Playing)
        assertEquals(1.5f, sessionPlaybackFor(playing, emptyList()).speed)
    }

    @Test
    fun optionsAndActionsRefuseWhatAndroidWouldRefuseLater() {
        assertFailsWith<IllegalArgumentException> { MediaNotificationOptions(smallIcon = 0) }
        assertFailsWith<IllegalArgumentException> {
            MediaNotificationOptions(smallIcon = 1, notificationId = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaNotificationOptions(smallIcon = 1, pausedForegroundTimeout = (-1).seconds)
        }
        assertFailsWith<IllegalArgumentException> { MediaNotificationAction("", "Like", 17) }
        assertFailsWith<IllegalArgumentException> { MediaNotificationAction("like", "", 17) }
        assertFailsWith<IllegalArgumentException> { MediaNotificationAction("like", "Like", 0) }
    }
}
