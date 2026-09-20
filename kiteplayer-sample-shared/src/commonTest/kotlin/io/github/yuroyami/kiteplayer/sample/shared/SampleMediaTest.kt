package io.github.yuroyami.kiteplayer.sample.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which files the sample offers, and whether that is the clip standing in for songs it never found. */
class SampleMediaTest {

    private val clip = "/repo/testmedia/sync1080p30.mp4"
    private val song = "/music/bad-cat.mp3"
    private val other = "/music/acid-tunnel.mp3"
    private val standIn = SampleMedia(listOf(SampleTrack(clip, "sync1080p30")), songMissing = true)

    private fun one(path: String, label: String) = SampleMedia(listOf(SampleTrack(path, label)), songMissing = false)

    @Test
    fun `a file asked for by name wins`() {
        assertEquals(
            one("/films/any.mkv", "any"),
            sampleMedia(requested = "/films/any.mkv", songs = listOf(song), clip = clip) { true },
        )
    }

    @Test
    fun `the song plays when nothing is asked for`() {
        assertEquals(
            one(song, "bad-cat"),
            sampleMedia(requested = null, songs = listOf(song), clip = clip) { true },
        )
    }

    @Test
    fun `a song path that is gone falls back to the clip`() {
        assertEquals(standIn, sampleMedia(requested = null, songs = listOf(song), clip = clip) { it != song })
    }

    @Test
    fun `with no song set the clip plays`() {
        assertEquals(standIn, sampleMedia(requested = null, songs = emptyList(), clip = clip) { true })
        assertEquals(standIn, sampleMedia(requested = null, songs = listOf(" "), clip = clip) { true })
    }

    @Test
    fun `every song that is really there is offered, in the order given`() {
        val media = sampleMedia(requested = null, songs = listOf(song, other), clip = clip) { true }
        assertEquals(listOf(song, other), media.tracks.map { it.path })
        assertEquals(listOf("bad-cat", "acid-tunnel"), media.tracks.map { it.label })
        assertEquals(song, media.path, "the sample opens on the first song")
    }

    @Test
    fun `a song that is not on disk is dropped rather than offered`() {
        val media = sampleMedia(requested = null, songs = listOf(song, other), clip = clip) { it != song }
        assertEquals(listOf(other), media.tracks.map { it.path })
        assertEquals(false, media.songMissing, "a song was found, so nothing is standing in")
    }
}
