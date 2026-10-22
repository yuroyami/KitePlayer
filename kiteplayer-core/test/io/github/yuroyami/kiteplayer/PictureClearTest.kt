package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The renderer is told when no picture plays, so the last one leaves the screen rather than staying
 * there frozen while the sound goes on (#530): a picture turned off, an item with no picture after
 * one with a picture, a stopped player, and a renderer attached while nothing plays. An item that
 * ended keeps its last picture, as other players keep it.
 */
class PictureClearTest {

    private val film = MediaScript(durationUs = 2_000_000)
    private val song = MediaScript(durationUs = 3_000_000, hasVideo = false)

    private val CoreHarness.screen: RecordingRenderer get() = checkNotNull(renderer)

    /** A harness whose `song` items have no picture and whose other items are [film]s. */
    private fun TestScope.harness(): CoreHarness {
        val harness = CoreHarness(this, script = film)
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("song")) song else null }
        return harness
    }

    /** Lets virtual time pass in small steps until [condition] holds, or [limit] has passed. */
    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    @Test
    fun aPictureTurnedOffLeavesTheScreenUntilItIsChosenAgain() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 12_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)
        assertTrue(harness.screen.showsPicture)

        harness.core.selectTrack(TrackKind.Video, null)
        assertFalse(harness.screen.showsPicture, "the renderer was told no picture plays")
        harness.run(1.seconds)
        assertFalse(harness.screen.showsPicture, "and no frame of the old picture followed")

        harness.core.selectTrack(TrackKind.Video, TrackId(0))
        harness.run(500.milliseconds)
        assertTrue(harness.screen.showsPicture, "the picture came back")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSongAfterAFilmInAQueueTakesTheFilmsPictureOffTheScreen() = runTest {
        val harness = harness()
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://film"), MediaItem("scripted://song")), 0)
        harness.core.play()
        harness.run(1.seconds)
        assertTrue(harness.screen.showsPicture)

        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue moved on")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertFalse(harness.screen.showsPicture, "the film's last picture left with it")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun skippingFromAFilmToASongTakesTheFilmsPictureOffTheScreen() = runTest {
        val harness = harness()
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://film"), MediaItem("scripted://song")), 0)
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.screen.showsPicture)

        harness.core.queueNext()
        assertFalse(harness.screen.showsPicture)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aFilmAfterAFilmKeepsThePictureUntilItsOwnArrives() = runTest {
        val harness = harness()
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
        harness.core.play()
        harness.run(500.milliseconds)
        val cleared = harness.screen.clearedAt.size

        harness.core.queueNext()
        harness.run(500.milliseconds)
        assertEquals(cleared, harness.screen.clearedAt.size, "no blank between two pictures")
        assertTrue(harness.screen.showsPicture)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aStoppedPlayerTakesItsPictureOffTheScreen() = runTest {
        val harness = harness()
        harness.openWithRenderer("scripted://film")
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.screen.showsPicture)

        harness.core.stop()
        assertFalse(harness.screen.showsPicture)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun anItemThatCannotOpenAfterAFilmTakesTheFilmsPictureOffTheScreen() = runTest {
        val harness = harness()
        harness.openWithRenderer("scripted://film")
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the film ends")
        assertTrue(harness.screen.showsPicture)

        harness.backend.openFailure = IllegalStateException("the server answered 404")
        runCatching { harness.core.open(MediaItem("scripted://missing")) }
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertFalse(harness.screen.showsPicture, "the failure is not shown over the film's last picture")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun anEndedFilmKeepsItsLastPicture() = runTest {
        val harness = harness()
        harness.openWithRenderer("scripted://film")
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the film ends")
        assertTrue(harness.screen.showsPicture, "the last picture stays: ${harness.screen.clearedAt}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aRendererAttachedWhileASongPlaysIsCleared() = runTest {
        val harness = harness()
        harness.open("scripted://song")
        harness.core.play()
        harness.run(500.milliseconds)

        harness.attachRenderer()
        assertEquals(listOf(0), harness.screen.clearedAt, "whatever its surface held is not this song's")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aRendererAttachedWhileAFilmPlaysIsNotCleared() = runTest {
        val harness = harness()
        harness.open("scripted://film")
        harness.core.play()
        harness.run(500.milliseconds)

        harness.attachRenderer()
        harness.run(200.milliseconds)
        assertEquals(emptyList(), harness.screen.clearedAt)
        assertTrue(harness.screen.showsPicture)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aRendererThatThrowsWhenClearedIsWarnedAboutAndKept() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 12_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        harness.screen.clearFailure = IllegalStateException("no surface to clear")

        harness.core.selectTrack(TrackKind.Video, null)
        harness.run(500.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.RendererFailed>()
        assertTrue(warnings.any { "clearPicture" in it.detail }, "warned: $warnings")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the sound plays on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }
}
