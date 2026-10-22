package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A held picture drawn again (#438, #463). A paused or ended player decodes the picture on screen
 * once more when asked, or when its look changes on a renderer that cannot redraw from a copy, and
 * keeps its position and its status. A playing player and a renderer that redraws by itself get
 * nothing.
 */
class RedrawTest {

    private suspend fun CoreHarness.pausedAt(after: kotlin.time.Duration): KitePlayer {
        val player = KitePlayer(core)
        openWithRenderer()
        player.play()
        run(after)
        player.pause()
        run(300.milliseconds)
        return player
    }

    private fun CoreHarness.status() = core.snapshots.value.status

    private fun CoreHarness.seeksCompleted() = events.count { it is PlayerEvent.SeekCompleted }

    @Test
    fun aRedrawOfAPausedPlayerDrawsThePictureAgainAndMovesNothing() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        val player = harness.pausedAt(2.seconds)
        val renderer = assertNotNull(harness.renderer)
        val before = renderer.count
        val shown = renderer.timestamps.last()
        val position = player.position()
        val seeks = harness.seeksCompleted()

        player.redrawPicture()
        harness.run(1.seconds)

        assertTrue(renderer.count > before, "nothing was drawn again")
        assertEquals(shown, renderer.timestamps.last(), "another picture than the one on screen was drawn")
        assertEquals(position, player.position(), "the redraw moved the position")
        assertEquals(PlaybackStatus.Paused, harness.status())
        assertEquals(seeks, harness.seeksCompleted(), "the redraw announced itself as a seek")
        harness.close()
    }

    @Test
    fun aRedrawOfAnEndedPlayerDrawsItsLastFrameAndStaysEnded() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(5.seconds)
        assertEquals(PlaybackStatus.Ended, harness.status())
        val renderer = assertNotNull(harness.renderer)
        val before = renderer.count
        val last = renderer.timestamps.last()
        val position = player.position()
        val endedEvents = harness.events.count { it is PlayerEvent.Ended }

        player.redrawPicture()
        harness.run(2.seconds)

        assertTrue(renderer.count > before, "nothing was drawn again")
        assertEquals(last, renderer.timestamps.last(), "the last frame was not the one drawn again")
        assertEquals(PlaybackStatus.Ended, harness.status())
        assertEquals(position, player.position(), "the redraw moved the position of an ended player")
        assertEquals(endedEvents, harness.events.count { it is PlayerEvent.Ended }, "the end was announced again")

        // Play still starts it from the beginning.
        player.play()
        harness.run(500.milliseconds)
        assertEquals(PlaybackStatus.Playing, harness.status())
        assertTrue(player.position() < 1.seconds, "play went on from ${player.position()}")
        harness.close()
    }

    @Test
    fun aChangeOfLookWhilePausedRedrawsOnlyARendererThatCannotItself() = runTest {
        val keeps = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        val keepsPlayer = keeps.pausedAt(2.seconds)
        val keepsBefore = assertNotNull(keeps.renderer).count
        keepsPlayer.setVideoAdjustments(VideoAdjustments(brightness = 0.5f))
        keeps.run(1.seconds)
        assertEquals(keepsBefore, keeps.renderer?.count, "a renderer that keeps its picture was redrawn by decoding")
        keeps.close()

        val android = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        assertNotNull(android.renderer).redrawsHeldPictureOverride = false
        val player = android.pausedAt(2.seconds)
        val renderer = assertNotNull(android.renderer)
        val position = player.position()
        for ((change, apply) in listOf<Pair<String, () -> Unit>>(
            "adjustments" to { player.setVideoAdjustments(VideoAdjustments(brightness = 0.5f)) },
            "scale" to { player.setVideoScale(VideoScale.Fill) },
            "framing" to { player.setVideoTransform(VideoTransform(zoom = 1.5f)) },
        )) {
            val before = renderer.count
            apply()
            android.run(1.seconds)
            assertTrue(renderer.count > before, "a change of $change was not drawn")
        }
        assertEquals(position, player.position())
        assertEquals(PlaybackStatus.Paused, android.status())
        android.close()
    }

    /**
     * Thirty values in a row, as a slider gives, cost no more redraws than the engine can serve: one
     * runs and the next waits with the newest value, so the redraws never fall behind (#463). Each
     * read takes 10 ms here, so a redraw that decodes from a keyframe takes a while.
     */
    @Test
    fun aDraggedSliderCoalescesIntoAFewRedraws() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, readDelayUs = 10_000))
        assertNotNull(harness.renderer).redrawsHeldPictureOverride = false
        val player = harness.pausedAt(2.seconds)
        val renderer = assertNotNull(harness.renderer)
        val before = renderer.count
        val seeksBefore = harness.source.seeks
        repeat(30) { step ->
            player.setVideoAdjustments(VideoAdjustments(brightness = step / 60f))
            harness.run(10.milliseconds)
        }
        harness.run(3.seconds)
        val redraws = harness.source.seeks - seeksBefore
        assertTrue(redraws in 1..10, "$redraws redraws for thirty values")
        assertTrue(renderer.count > before, "nothing was drawn")
        assertEquals(VideoAdjustments(brightness = 29 / 60f), renderer.adjustments, "the last value did not stand")
        harness.close()
    }

    @Test
    fun aPlayingPlayerIsNeverRedrawnByDecoding() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        assertNotNull(harness.renderer).redrawsHeldPictureOverride = false
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(1.seconds)
        val seeksBefore = harness.backend.sessions.last().scriptedSource.seeks
        player.setVideoAdjustments(VideoAdjustments(brightness = 0.5f))
        player.redrawPicture()
        harness.run(1.seconds)
        assertEquals(seeksBefore, harness.source.seeks, "a playing player was sought to redraw")
        assertEquals(PlaybackStatus.Playing, harness.status())
        harness.close()
    }

    /** A subtitle setting changed while paused shows once its new overlay is out, on the picture (#463). */
    @Test
    fun aSubtitleSettingWhilePausedRedrawsAfterItsOverlay() = runTest {
        val script = MediaScript(
            durationUs = 20_000_000,
            subtitleCues = listOf(SubtitleCue.Text(0, 10_000_000, listOf(StyledSpan("a long line")))),
        )
        val harness = CoreHarness(this, script = script)
        assertNotNull(harness.renderer).redrawsHeldPictureOverride = false
        val player = harness.pausedAt(2.seconds)
        val renderer = assertNotNull(harness.renderer)
        val framesBefore = renderer.count
        val overlaysBefore = renderer.overlays.size

        player.setSubtitleScale(1.5f)
        harness.run(1.seconds)

        assertTrue(renderer.overlays.size > overlaysBefore, "the new size published no overlay")
        assertTrue(renderer.count > framesBefore, "the new size was not drawn on the paused picture")
        assertEquals(PlaybackStatus.Paused, harness.status())
        harness.close()
    }
}
