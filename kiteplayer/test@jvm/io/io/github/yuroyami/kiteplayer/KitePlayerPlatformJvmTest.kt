package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.mobile.DesktopAwtPlayerViewRendererFactory
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.view.KitePlayerAwtView
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
import io.github.yuroyami.kiteplayer.view.PlayerViewDefaults
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The desktop JVM is no longer a placeholder. It answers Available, and the default
 * stack it builds is a real one, which is what a Compose Desktop consumer gets from one dependency
 * line.
 */
class KitePlayerPlatformJvmTest {

    @Test
    fun theDesktopStackIsRealAndComplete() {
        assertTrue(KitePlayer.isAvailable, "desktop availability refused: ${KitePlayer.availability}")

        val backends = assertNotNull(KitePlayerPlatform.backendsOrNull(), "no default desktop backends")
        assertNotNull(backends.backend, "no media backend")
        val output = assertNotNull(backends.output, "no output backend")
        assertNotNull(output.audioSink, "the desktop output has no audio sink factory")
        assertNotNull(output.subtitleRasterizer, "the desktop output has no subtitle rasterizer")
        assertNotNull(mobileBackends().backend, "mobileBackends() found no desktop backend")
    }

    @Test
    fun kitePlayerBuildsTheDefaultStack() {
        KitePlayer().use { player ->
            val dump = player.diagnosticsDump()
            assertTrue(dumpLine(dump, "backend").contains("KiteFFmpeg"), dump)
            assertTrue(dumpLine(dump, "output").contains("DesktopOutputBackend"), dump)
        }
    }

    /** A backend the config names is kept, and only the missing one comes from the defaults. */
    @Test
    fun aNamedBackendIsKeptAndAMissingOneIsFilled() {
        KitePlayer(PlayerConfig(backends = Backends(output = NamedOutputBackend))).use { player ->
            val dump = player.diagnosticsDump()
            assertTrue(dumpLine(dump, "output").contains("NamedOutputBackend"), dump)
            assertTrue(dumpLine(dump, "backend").contains("KiteFFmpeg"), dump)
        }
    }

    /** A default player gives the views their renderer, so a view needs only `view.player = player`. */
    @Test
    fun aDefaultPlayerGivesTheViewsTheirRenderer() {
        val before = PlayerViewDefaults.rendererFactory
        PlayerViewDefaults.rendererFactory = null
        try {
            KitePlayer().use {
                assertSame<Any?>(DesktopAwtPlayerViewRendererFactory, PlayerViewDefaults.rendererFactory)
            }
        } finally {
            PlayerViewDefaults.rendererFactory = before
        }
    }

    /** A stack of the app's own may make frames the default renderer cannot show, so it sets nothing. */
    @Test
    fun aStackOfYourOwnLeavesTheViewDefaultAlone() {
        val before = PlayerViewDefaults.rendererFactory
        PlayerViewDefaults.rendererFactory = null
        try {
            val own = Backends(backend = KitePlayerPlatform.backendsOrNull()?.backend, output = NamedOutputBackend)
            KitePlayer(PlayerConfig(backends = own)).use {
                assertNull(PlayerViewDefaults.rendererFactory)
            }
        } finally {
            PlayerViewDefaults.rendererFactory = before
        }
    }

    /** The old door answers as it did, for apps that have not moved yet. */
    @Suppress("DEPRECATION")
    @Test
    fun theDeprecatedDoorStillAnswers() {
        assertEquals(KitePlayer.availability, KitePlayerPlatform.availability)
        assertNotNull(KitePlayerPlatform.createOrNull()).close()
    }

    /**
     * The desktop answer is the floating window's own: true where the JVM has a screen whose
     * window system keeps a window on top, false on a headless build machine. So it must agree
     * with the environment and with whether the floating window can be built at all.
     */
    @Test
    fun desktopPictureInPictureFollowsTheFloatingWindow() {
        val environment = !GraphicsEnvironment.isHeadless() && Toolkit.getDefaultToolkit().isAlwaysOnTopSupported
        assertEquals(environment, KitePlayer.supportsPictureInPicture, "the answer must follow the screen")
        KitePlayerPictureInPicture.createOrNull(KitePlayerAwtView()).use { floating ->
            assertEquals(floating != null, KitePlayer.supportsPictureInPicture, "the answer must match createOrNull")
        }
    }

    private fun dumpLine(dump: String, key: String): String =
        dump.lineSequence().firstOrNull { it.trimStart().startsWith("$key ") } ?: "no $key line"
}

/** Never asked for sound: these tests only build and close players. */
private object NamedOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "named"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
