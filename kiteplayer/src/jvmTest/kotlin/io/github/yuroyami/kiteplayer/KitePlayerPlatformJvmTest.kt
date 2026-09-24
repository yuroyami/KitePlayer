package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import io.github.yuroyami.kiteplayer.view.KitePlayerAwtView
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop JVM is no longer a placeholder. It answers Available, and the default
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

    /**
     * The desktop answer is the floating window's own: true where the JVM has a screen whose
     * window system keeps a window on top, false on a headless build machine. So it must agree
     * with the environment and with whether the floating window can be built at all.
     */
    @Test
    fun desktopPictureInPictureFollowsTheFloatingWindow() {
        val environment = !GraphicsEnvironment.isHeadless() && Toolkit.getDefaultToolkit().isAlwaysOnTopSupported
        assertEquals(environment, KitePlayerPlatform.supportsPictureInPicture, "the answer must follow the screen")
        KitePlayerPictureInPicture.createOrNull(KitePlayerAwtView()).use { floating ->
            assertEquals(floating != null, KitePlayerPlatform.supportsPictureInPicture, "the answer must match createOrNull")
        }
    }
}
