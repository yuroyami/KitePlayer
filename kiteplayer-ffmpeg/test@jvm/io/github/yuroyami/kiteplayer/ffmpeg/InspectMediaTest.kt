@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.inspect
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Reading a file's own facts without playing it.
 *
 * A library screen wants the length and the tracks of a great many files and wants to play none of
 * them. The media library could always do this; the player had no way to ask, so an application
 * either opened every file for real or parsed names.
 */
class InspectMediaTest {

    private val mediaDir: String = System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"

    private val backend = KiteFFmpegMediaBackend()

    private fun fixture(name: String): MediaItem {
        val file = File(mediaDir, name)
        assertTrue(file.exists(), "the fixture is missing at ${file.absolutePath}")
        return MediaItem(file.absolutePath)
    }

    @Test
    fun `inspecting reports what the source itself reports`() = runTest {
        val media = fixture("subbed.mkv")
        val seen = inspect(media, backend)
        val session = backend.open(media)
        try {
            assertEquals(session.source.duration?.micros, seen.duration?.inWholeMicroseconds)
            assertEquals(
                session.source.streams.map { it.index },
                seen.tracks.all.map { it.id.value },
                "the track list disagreed with the container",
            )
            assertEquals(session.source.chapters, seen.chapters)
            assertEquals(session.source.seekable, seen.seekable)
            assertEquals(session.source.metadata, seen.metadata)
        } finally {
            session.close()
        }
    }

    @Test
    fun `inspecting needs no output device at all`() = runTest {
        // The point of the whole thing: a library screen listing a thousand files should not have
        // to ask a device for an audio route it is never going to use.
        val seen = inspect(fixture("subbed.mkv"), backend)
        assertTrue(seen.tracks.all.isNotEmpty(), "nothing was read, so nothing is being proved")
    }

    @Test
    fun `a subtitled file reports its subtitle track and its length`() = runTest {
        val seen = inspect(fixture("subbed.mkv"), backend)
        assertTrue(
            seen.tracks.all.any { it.kind == TrackKind.Subtitle },
            "the fixture carries subtitles and none was reported",
        )
        assertNotNull(seen.duration, "the fixture declares a duration and none was reported")
    }
}
