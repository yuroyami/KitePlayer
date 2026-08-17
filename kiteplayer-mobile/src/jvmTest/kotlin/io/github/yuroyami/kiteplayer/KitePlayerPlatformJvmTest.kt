package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop JVM stopped being a placeholder in phase W. It answers Available, and the default
 * stack it builds is a real one, which is what a Compose Desktop consumer gets from one dependency
 * line.
 */
class KitePlayerPlatformJvmTest {

    @Test
    fun theDesktopStackIsRealAndComplete() {
        assertTrue(
            KitePlayerPlatform.isAvailable,
            "desktop availability refused: ${KitePlayerPlatform.availability}",
        )

        val backends = assertNotNull(KitePlayerPlatform.backendsOrNull(), "no default desktop backends")
        assertNotNull(backends.backend, "no media backend")
        val output = assertNotNull(backends.output, "no output backend")
        assertNotNull(output.audioSink, "the desktop output has no audio sink factory")
        assertNotNull(output.subtitleRasterizer, "the desktop output has no subtitle rasterizer")
        assertNotNull(mobileBackends().backend, "mobileBackends() found no desktop backend")
    }

    /** Not a limitation to fix: no desktop window manager offers a PiP the player could drive. */
    @Test
    fun desktopDeclaresNoPictureInPicture() {
        assertTrue(!KitePlayerPlatform.supportsPictureInPicture)
    }
}
