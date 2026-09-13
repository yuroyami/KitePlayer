package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * What a platform session actually gets written, tick by tick. The metadata half is written when
 * the title, the length or the artwork changes; the playback half when the push rule says so.
 */
class MediaSessionMirrorTest {

    private val clock = TestTimeSource()
    private val metadataWrites = mutableListOf<Pair<MediaSessionMetadata, String?>>()
    private val playbackWrites = mutableListOf<MediaSessionState>()
    private val mirror = MediaSessionMirror<String>(
        writeMetadata = { metadata, artwork -> metadataWrites += metadata to artwork },
        writePlayback = { playbackWrites += it },
        clock = clock,
    )

    private fun state(position: Duration = 10.seconds, title: String = "A Holiday") = MediaSessionState(
        phase = MediaSessionPhase.Playing,
        position = position,
        duration = 60.seconds,
        speed = 1.0,
        canSeek = true,
        hasVideo = false,
        title = title,
        artist = null,
        album = null,
        hasNext = false,
        hasPrevious = false,
    )

    @Test
    fun `the first state writes both halves`() {
        mirror.update(state(), artwork = null)
        assertEquals(1, metadataWrites.size)
        assertEquals(1, playbackWrites.size)
    }

    @Test
    fun `steady playback writes nothing more`() {
        mirror.update(state(position = 10.seconds), artwork = null)
        clock += 1.seconds
        mirror.update(state(position = 11.seconds), artwork = null)
        clock += 1.seconds
        mirror.update(state(position = 12.seconds), artwork = null)
        assertEquals(1, metadataWrites.size)
        assertEquals(1, playbackWrites.size)
    }

    @Test
    fun `a seek writes the playback half only`() {
        mirror.update(state(position = 10.seconds), artwork = null)
        clock += 1.seconds
        mirror.update(state(position = 40.seconds), artwork = null)
        assertEquals(1, metadataWrites.size)
        assertEquals(listOf(10.seconds, 40.seconds), playbackWrites.map { it.position })
    }

    @Test
    fun `a new title writes the metadata half`() {
        mirror.update(state(title = "One"), artwork = null)
        mirror.update(state(title = "Two"), artwork = null)
        assertEquals(listOf("One", "Two"), metadataWrites.map { it.first.title })
    }

    @Test
    fun `new artwork rewrites the metadata with it`() {
        mirror.update(state(), artwork = null)
        mirror.update(state(), artwork = "cover.png")
        assertEquals(listOf(null, "cover.png"), metadataWrites.map { it.second })
        assertEquals(1, playbackWrites.size)
    }
}
