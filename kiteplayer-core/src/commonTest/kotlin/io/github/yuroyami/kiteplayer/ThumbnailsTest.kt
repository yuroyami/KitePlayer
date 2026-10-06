package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.parseThumbnailVtt
import io.github.yuroyami.kiteplayer.spi.PlayerThumbnails
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seek bar pictures (#433): the stream's own, which the player lists and asks for a position, and
 * an item's WebVTT thumbnail file, whose cues name a region of a sprite image for each stretch.
 * Times count from the item's start, a clipped item's too.
 */
class ThumbnailsTest {

    /** A stream's pictures: one tile of 160x90 for each 10 s, ten to an image, as the issue lays them out. */
    private class StreamPictures : PlayerThumbnails {
        val asked = mutableListOf<Long>()
        override val set = ThumbnailSet(width = 160, height = 90, interval = 10.seconds)

        override suspend fun at(position: Pts): StreamThumbnail {
            asked += position.micros
            val index = position.micros / 10_000_000L
            return StreamThumbnail(
                image = byteArrayOf((index / 10).toByte()),
                mimeType = "image/jpeg",
                x = (index % 10).toInt() * 160,
                y = 0,
                width = 160,
                height = 90,
                start = (index * 10).seconds,
                end = (index * 10 + 10).seconds,
            )
        }
    }

    private val vtt = """
        WEBVTT

        00:00.000 --> 00:10.000
        sprite.jpg#xywh=0,0,160,90

        1
        00:00:10.000 --> 00:00:20.000
        sprite.jpg#xywh=160,0,160,90

        00:00:20.000 --> 00:00:30.000
        sprite.jpg#xywh=320,0,160,90

        00:00:30.000 --> 00:00:40.000
        sprite.jpg#xywh=480,0,160,90

        00:00:40.000 --> 00:00:50.000
        other/whole.png
    """.trimIndent()

    private val sprite = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)

    /** A thumbnail file read through a reader of its own, which also reads the images it names. */
    private class ThumbnailFiles(private val text: String, private val images: Map<String, ByteArray>) {
        val opened = mutableListOf<String>()
        val factory = MediaIoFactory {
            val root = MediaIo.ofBytes(text.encodeToByteArray()).open()!!
            object : MediaIo by root {
                override suspend fun openRelated(uri: String): MediaIo? {
                    opened += uri
                    return images[uri]?.let { MediaIo.ofBytes(it).open() }
                }
            }
        }
    }

    private suspend fun TestScope.opened(item: MediaItem, script: MediaScript = MediaScript(durationUs = 60_000_000)): CoreHarness {
        val harness = CoreHarness(this, script = script)
        harness.attachRenderer()
        harness.core.open(item)
        return harness
    }

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(10.milliseconds)
            waited += 10.milliseconds
        }
        return true
    }

    @Test
    fun aStreamsPicturesAreListedAndAnswerForAPosition() = runTest {
        val pictures = StreamPictures()
        val harness = opened(MediaItem("scripted://media"), MediaScript(durationUs = 60_000_000, thumbnails = pictures))
        assertEquals(ThumbnailSet(160, 90, 10.seconds), harness.core.snapshots.value.tracks.thumbnails)
        val picture = assertNotNull(harness.core.thumbnailAt(35.seconds))
        assertEquals(480, picture.x, "the fourth tile")
        assertEquals(30.seconds, picture.start)
        assertEquals(40.seconds, picture.end)
        assertNull(harness.core.thumbnailAt(70.seconds), "nothing past the item's end")
        harness.close()
    }

    @Test
    fun aClippedItemsPicturesCountFromItsStart() = runTest {
        val pictures = StreamPictures()
        val harness = opened(
            MediaItem("scripted://media", clip = MediaClip(start = 25.seconds, end = 45.seconds)),
            MediaScript(durationUs = 60_000_000, thumbnails = pictures),
        )
        val picture = assertNotNull(harness.core.thumbnailAt(10.seconds))
        assertEquals(listOf(35_000_000L), pictures.asked, "the item's 10 s is the file's 35 s")
        assertEquals(5.seconds, picture.start, "the file's 30 s is the item's 5 s")
        assertEquals(15.seconds, picture.end)
        val first = assertNotNull(harness.core.thumbnailAt(Duration.ZERO))
        assertEquals(Duration.ZERO, first.start, "a tile that began before the clip starts with it")
        assertNull(harness.core.thumbnailAt(20.seconds), "the clip's end is the item's end")
        harness.close()
    }

    @Test
    fun aThumbnailFileAnswersBeforeTheStreamsOwnPictures() = runTest {
        val files = ThumbnailFiles(vtt, mapOf("https://cdn.test/thumbs/sprite.jpg" to sprite))
        val harness = opened(
            MediaItem("scripted://media", thumbnails = ThumbnailSource("https://cdn.test/thumbs/index.vtt", io = files.factory)),
            MediaScript(durationUs = 60_000_000, thumbnails = StreamPictures()),
        )
        assertTrue(
            harness.runUntil(5.seconds) { harness.core.snapshots.value.tracks.thumbnails == ThumbnailSet(160, 90, 10.seconds) },
            "the file's pictures were not listed: ${harness.core.snapshots.value.tracks.thumbnails}",
        )
        val picture = assertNotNull(harness.core.thumbnailAt(35.seconds))
        assertContentEquals(sprite, picture.image)
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals(listOf(480, 0, 160, 90), listOf(picture.x, picture.y, picture.width, picture.height))
        assertEquals(30.seconds, picture.start)
        assertEquals(40.seconds, picture.end)
        harness.core.thumbnailAt(5.seconds)
        assertEquals(listOf("https://cdn.test/thumbs/sprite.jpg"), files.opened, "the sprite was read once")
        harness.close()
    }

    @Test
    fun aThumbnailFileThatHoldsNoPictureWarnsAndTheStreamsOwnStand() = runTest {
        val files = ThumbnailFiles("WEBVTT\n\nNOTE nothing here\n", emptyMap())
        val harness = opened(
            MediaItem("scripted://media", thumbnails = ThumbnailSource("https://cdn.test/thumbs/index.vtt", io = files.factory)),
            MediaScript(durationUs = 60_000_000, thumbnails = StreamPictures()),
        )
        assertTrue(
            harness.runUntil(5.seconds) {
                harness.core.warningHistory().any { it.warning is PlaybackWarning.ThumbnailsUnreadable }
            },
            "the empty file did not warn",
        )
        assertEquals(ThumbnailSet(160, 90, 10.seconds), harness.core.snapshots.value.tracks.thumbnails)
        assertEquals(480, harness.core.thumbnailAt(35.seconds)?.x)
        harness.close()
    }

    @Test
    fun aCueWithoutARegionIsTheWholeImageAndAddressesResolveAgainstTheFile() {
        val cues = parseThumbnailVtt(vtt, "https://cdn.test/thumbs/index.vtt")
        assertEquals(5, cues.size)
        assertEquals("https://cdn.test/thumbs/other/whole.png", cues.last().image)
        assertEquals(listOf(0, 0, 0, 0), cues.last().let { listOf(it.x, it.y, it.width, it.height) })
        assertEquals(40_000_000L, cues.last().startUs)
    }
}
