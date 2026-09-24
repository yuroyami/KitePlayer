package io.github.yuroyami.kiteplayer

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `KitePlayerPlatform` answers on each Kotlin/Native target.
 *
 * Apple has the FFmpeg backend and an audio output, so it builds a player. Linux and Windows have
 * the FFmpeg backend and no audio output, so they refuse and say what to pass instead.
 */
@OptIn(ExperimentalNativeApi::class)
class NativePlatformDefaultsTest {

    @Test
    fun appleBuildsTheWholeStackAndTheOthersSayWhatIsMissing() {
        when (Platform.osFamily) {
            OsFamily.MACOSX, OsFamily.IOS -> {
                assertTrue(KitePlayerPlatform.isAvailable, "refused: ${KitePlayerPlatform.availability}")
                val backends = assertNotNull(KitePlayerPlatform.backendsOrNull(), "no default backends")
                assertNotNull(backends.backend, "no media backend")
                val output = assertNotNull(backends.output, "no output backend")
                assertNotNull(output.subtitleRasterizer, "the Apple output has no subtitle rasterizer")
            }
            OsFamily.LINUX, OsFamily.WINDOWS -> {
                val unavailable = assertIs<KitePlayerAvailability.Unavailable>(KitePlayerPlatform.availability)
                assertTrue("OutputBackend" in unavailable.reason, "the reason does not say what to pass: ${unavailable.reason}")
                assertNull(KitePlayerPlatform.backendsOrNull())
                assertNull(KitePlayerPlatform.createOrNull())
            }
            else -> println("SKIP: no default stack is declared for ${Platform.osFamily}")
        }
    }

    @Test
    fun linuxAndWindowsClaimNoPictureInPicture() {
        when (Platform.osFamily) {
            OsFamily.LINUX, OsFamily.WINDOWS -> assertFalse(KitePlayerPlatform.supportsPictureInPicture)
            // Apple answers the system's own static; ApplePlatformDefaultsTest checks that answer.
            else -> println("SKIP: ${Platform.osFamily} answers picture in picture from the system")
        }
    }
}
