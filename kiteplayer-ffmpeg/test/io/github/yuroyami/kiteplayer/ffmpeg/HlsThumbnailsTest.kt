package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The seek bar pictures of an HLS stream (#433): an image stream in the master playlist, and its
 * image playlist of grid images, each of tiles in reading order. The layout is the one the issue
 * names, ten tiles by one covering 100 s, so the picture for 35 s is the fourth tile of the first
 * image.
 */
class HlsThumbnailsTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
        video/index.m3u8
        #EXT-X-IMAGE-STREAM-INF:BANDWIDTH=8000,RESOLUTION=80x45,CODECS="jpeg",URI="images/small.m3u8"
        #EXT-X-IMAGE-STREAM-INF:BANDWIDTH=16000,RESOLUTION=160x90,CODECS="jpeg",URI="images/index.m3u8"
    """.trimIndent()

    private val imagePlaylist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:100
        #EXT-X-VERSION:7
        #EXT-X-MEDIA-SEQUENCE:1
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-IMAGES-ONLY
        #EXT-X-TILES:RESOLUTION=160x90,LAYOUT=10x1,DURATION=10.000
        #EXTINF:100.000,
        sprite-1.jpg
        #EXTINF:100.000,
        sprite-2.jpg
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun theMasterNamesItsImageStreamsAndTheLargestTilesAreShown() {
        val streams = hlsImageStreams(master)
        assertEquals(listOf("images/small.m3u8", "images/index.m3u8"), streams.map { it.uri })
        val chosen = assertNotNull(HlsThumbnails.choose(streams))
        assertEquals("images/index.m3u8", chosen.uri)
        assertEquals(160, chosen.tileWidth)
        assertEquals(90, chosen.tileHeight)
    }

    @Test
    fun thePictureForAPositionIsItsTileOfItsGridImage() {
        val images = assertNotNull(parseHlsImagePlaylist(imagePlaylist, "https://cdn.test/images/index.m3u8"))
        assertEquals(listOf("https://cdn.test/images/sprite-1.jpg", "https://cdn.test/images/sprite-2.jpg"), images.map { it.uri })
        val tile = assertNotNull(hlsTileAt(images, 35_000_000L))
        assertEquals("https://cdn.test/images/sprite-1.jpg", tile.uri)
        assertEquals(480, tile.x, "the fourth tile")
        assertEquals(0, tile.y)
        assertEquals(160, tile.width)
        assertEquals(90, tile.height)
        assertEquals(30_000_000L, tile.startUs)
        assertEquals(40_000_000L, tile.endUs)
        // The second image's first tile, and nothing past the last image.
        assertEquals("https://cdn.test/images/sprite-2.jpg", hlsTileAt(images, 100_000_000L)?.uri)
        assertEquals(0, hlsTileAt(images, 100_000_000L)?.x)
        assertNull(hlsTileAt(images, 200_000_000L))
    }

    @Test
    fun aGridOfRowsIsReadInReadingOrder() {
        val playlist = imagePlaylist.replace("LAYOUT=10x1,DURATION=10.000", "LAYOUT=5x2,DURATION=10.000")
        val images = assertNotNull(parseHlsImagePlaylist(playlist, "https://cdn.test/images/index.m3u8"))
        val tile = assertNotNull(hlsTileAt(images, 75_000_000L))
        assertEquals(2 * 160, tile.x, "the eighth tile is the third of the second row")
        assertEquals(90, tile.y)
    }

    @Test
    fun aLivePlaylistGivesNoPictures() {
        assertNull(parseHlsImagePlaylist(imagePlaylist.replace("#EXT-X-ENDLIST", ""), "https://cdn.test/images/index.m3u8"))
    }

    @Test
    fun anImageIsReadWhenAskedForAndKept() = runTest {
        val sprite = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val asked = mutableListOf<String>()
        val root = assertNotNull(MediaIo.ofBytes(master.encodeToByteArray()).open())
        val io = object : MediaIo by root {
            override suspend fun openRelated(uri: String): MediaIo? {
                asked += uri
                return when (uri) {
                    "https://cdn.test/images/index.m3u8" -> MediaIo.ofBytes(imagePlaylist.encodeToByteArray()).open()
                    "https://cdn.test/images/sprite-1.jpg" -> MediaIo.ofBytes(sprite).open()
                    else -> null
                }
            }
        }
        val thumbnails = HlsThumbnails(io, "https://cdn.test/master.m3u8", assertNotNull(HlsThumbnails.choose(hlsImageStreams(master))))
        assertEquals(160, thumbnails.set.width)
        assertEquals(emptyList(), asked, "nothing is read before a picture is asked for")
        val picture = assertNotNull(thumbnails.at(Pts(35_000_000L)))
        assertContentEquals(sprite, picture.image)
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals(480, picture.x)
        assertEquals(30.seconds, picture.start)
        assertEquals(40.seconds, picture.end)
        thumbnails.at(Pts(12_000_000L))
        assertEquals(
            listOf("https://cdn.test/images/index.m3u8", "https://cdn.test/images/sprite-1.jpg"),
            asked,
            "the playlist and the image were each read once",
        )
    }
}
