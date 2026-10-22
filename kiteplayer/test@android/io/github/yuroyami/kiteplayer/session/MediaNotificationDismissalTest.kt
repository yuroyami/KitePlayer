package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * What the application is told when the user puts the media notification away (#427), read from
 * the answers the handle acts on, since the handle itself needs a running Android.
 */
class MediaNotificationDismissalTest {

    private fun pausedMachine() = MediaNotificationMachine(10.minutes).apply {
        on(MediaNotificationEvent.StatusChanged(PlaybackStatus.Playing))
        on(MediaNotificationEvent.StatusChanged(PlaybackStatus.Paused))
    }

    @Test
    fun aSwipeIsReportedAsSwipedOnce() {
        val machine = pausedMachine()
        assertEquals(MediaNotificationDismissal.Swiped, dismissalOf(machine.on(MediaNotificationEvent.Dismissed)))
        assertNull(dismissalOf(machine.on(MediaNotificationEvent.Dismissed)))
    }

    @Test
    fun aTaskRemovedWhilePausedIsReportedAsTaskRemoved() {
        assertEquals(
            MediaNotificationDismissal.TaskRemoved,
            dismissalOf(pausedMachine().on(MediaNotificationEvent.TaskRemoved)),
        )
    }

    @Test
    fun aTaskRemovedWhilePlayingIsNotReported() {
        val machine = MediaNotificationMachine(10.minutes)
        machine.on(MediaNotificationEvent.StatusChanged(PlaybackStatus.Playing))
        assertNull(dismissalOf(machine.on(MediaNotificationEvent.TaskRemoved)))
    }
}
