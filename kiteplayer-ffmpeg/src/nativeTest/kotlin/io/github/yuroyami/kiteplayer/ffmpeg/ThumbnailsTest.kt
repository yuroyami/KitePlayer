@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seek previews and library grids: one call, one open, one image per position, scaled to fit.
 * Each image is decoded back through KiteFFmpeg to read its size, which is the only proof that
 * the scale ran and the encoder wrote a picture.
 */
class ThumbnailsTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    private fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { pinned ->
                check(fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file).toInt() == bytes.size)
            }
        } finally {
            fclose(file)
        }
    }

    private fun decodedSize(bytes: ByteArray, name: String): Pair<Int, Int> {
        val path = "$mediaDir/../build/thumbnail-test-$name.jpg"
        writeFile(path, bytes)
        return MediaSource.open(path).use { image ->
            val video = assertNotNull(image.streams.first { it.type == MediaType.Video }.video)
            video.width to video.height
        }
    }

    @Test
    fun `three positions give three images no wider than asked and the far ones differ`() = runBlocking {
        val item = MediaItem("$mediaDir/sync1080p30.mp4")
        val thumbnails = Thumbnails.at(item, listOf(0.seconds, 3.seconds, 6.seconds), maxWidth = 320)

        assertEquals(3, thumbnails.size)
        assertEquals(listOf(0.seconds, 3.seconds, 6.seconds), thumbnails.map { it.position })
        thumbnails.forEachIndexed { index, thumbnail ->
            assertTrue(thumbnail.width <= 320, "thumbnail $index is ${thumbnail.width} wide")
            assertEquals(SnapshotFormat.Jpeg, thumbnail.format)
            val (width, height) = decodedSize(thumbnail.bytes, "$index")
            assertEquals(thumbnail.width, width, "the reported width must be the image's own")
            assertEquals(thumbnail.height, height)
            assertTrue(width <= 320)
        }
        // A 1080p source scaled to 320 wide keeps its 16:9.
        assertEquals(320, thumbnails[0].width)
        assertEquals(180, thumbnails[0].height)
        assertFalse(
            thumbnails[0].bytes.contentEquals(thumbnails[2].bytes),
            "six seconds apart in a clip that moves, the pictures must differ",
        )

        // Nothing is held after the call: the file opens again at once.
        MediaSource.open("$mediaDir/sync1080p30.mp4").use { again ->
            assertTrue(again.streams.isNotEmpty())
        }
    }

    @Test
    fun `an item with no video stream is refused typed`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            Thumbnails.at(MediaItem("$mediaDir/audio-flac.flac"), listOf(0.seconds))
        }
        Unit
    }
}
