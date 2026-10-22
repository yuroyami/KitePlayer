package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The playback channel asks for no launcher dot only where Android would otherwise draw one (#426). */
class PlaybackChannelTest {

    @Test
    fun androidEightAndEightOneHideTheDot() {
        assertTrue(playbackChannelHidesBadge(26))
        assertTrue(playbackChannelHidesBadge(27))
    }

    @Test
    fun androidNineAndLaterLeaveTheChannelAlone() {
        for (sdk in listOf(28, 29, 34, 35, 36)) assertFalse(playbackChannelHidesBadge(sdk), "SDK $sdk")
    }
}
