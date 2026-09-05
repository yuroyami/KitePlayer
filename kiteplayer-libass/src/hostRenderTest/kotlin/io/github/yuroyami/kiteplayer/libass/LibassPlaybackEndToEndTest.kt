package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole path on a real file: FFmpeg demuxes typeset.mkv, the engine sees an ASS track, the
 * installed provider is this module, and the images on the glass move because the sign does. Proven
 * by asking which engine drew the overlay, not by looking at pixels: `subtitleTypesetter` names the
 * provider, and the overlays it publishes differ from one instant to the next.
 *
 * Real media on real time, so a loaded machine can sample before it settles; the windows below are
 * generous and every wait is a poll with a deadline, never a fixed sleep.
 */
class LibassPlaybackEndToEndTest {

    private val fixture: String get() = "${mediaDir()}/typeset.mkv"

    @Test
    fun theContainerAttachesTheFontAndTheSourceReportsIt() = runBlocking {
        val source = KiteFFmpegSourceFactory().open(MediaItem(fixture))
        try {
            val fonts = source.attachments.filter { it.isFont }
            assertEquals(1, fonts.size, "expected one attached font, got ${source.attachments}")
            assertEquals("KiteTestSans.ttf", fonts.single().fileName)
            assertEquals("font/ttf", fonts.single().mimeType)
            assertTrue(fonts.single().data.size > 10_000, "the attachment carries ${fonts.single().data.size} bytes")
            assertTrue(source.streams.none { it.codec == "unknown" }, "an attachment leaked into the track list")
        } finally {
            source.close()
        }
    }

    @Test
    fun aRealAssTrackIsTypesetByLibassAndTheSignMoves() = runBlocking {
        val renderer = OverlayRecordingRenderer()
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = hostOutputBackend()),
                progressInterval = 50.milliseconds,
            ),
        )
        try {
            player.attachRenderer(renderer)
            player.open(MediaItem(fixture))
            withTimeout(10.seconds) {
                while (player.state.value.subtitleTypesetter != KiteLibass.PROVIDER_ID) delay(20)
            }
            assertNotNull(player.state.value.tracks.selectedSubtitle, "no subtitle track was selected")
            player.play()
            // Wait for the first picture, then watch the moving sign for a while.
            withTimeout(15.seconds) {
                while (renderer.overlays.none { it != null && it.images.isNotEmpty() }) delay(20)
            }
            delay(2.seconds)
            val shown = renderer.overlays.filterNotNull().filter { it.images.isNotEmpty() }
            assertTrue(shown.size >= 5, "only ${shown.size} non-empty overlays in two seconds of a moving sign")
            // The sign is the topmost image; its x must change as it moves right.
            val signXs = shown.map { overlay -> overlay.images.minByOrNull { it.y }!!.x }.distinct()
            assertTrue(signXs.size >= 3, "the sign never moved: x values $signXs")
            assertTrue(renderer.presented > 10, "the picture did not play: ${renderer.presented} frames presented")
        } finally {
            player.closeAndAwait()
        }
        // Closing clears the glass: the last overlay carries nothing.
        val last = renderer.overlays.filterNotNull().lastOrNull()
        assertTrue(last == null || last.images.isEmpty(), "the typeset images outlived the player")
    }
}
