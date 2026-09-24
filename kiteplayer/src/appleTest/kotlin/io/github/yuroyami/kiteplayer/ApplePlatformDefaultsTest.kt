package io.github.yuroyami.kiteplayer

import platform.AVKit.AVPictureInPictureController
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `KitePlayerPlatform` answers about picture in picture on Apple.
 *
 * iOS and macOS both have the system controller, so both pass on the system's own answer rather
 * than a guess made here.
 */
class ApplePlatformDefaultsTest {

    @Test
    fun pictureInPictureIsTheSystemsOwnAnswer() {
        assertEquals(
            AVPictureInPictureController.isPictureInPictureSupported(),
            KitePlayerPlatform.supportsPictureInPicture,
        )
    }
}
