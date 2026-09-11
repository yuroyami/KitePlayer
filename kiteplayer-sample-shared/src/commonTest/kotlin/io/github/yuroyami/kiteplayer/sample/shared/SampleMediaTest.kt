package io.github.yuroyami.kiteplayer.sample.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which file the sample opens, and whether that is the clip standing in for a song that is not set up. */
class SampleMediaTest {

    private val clip = "/repo/testmedia/sync1080p30.mp4"
    private val song = "/music/bad-cat.mp3"
    private val standIn = SampleMedia(clip, songMissing = true)

    @Test
    fun `a file asked for by name wins`() {
        assertEquals(
            SampleMedia("/films/any.mkv", songMissing = false),
            sampleMedia(requested = "/films/any.mkv", song = song, clip = clip) { true },
        )
    }

    @Test
    fun `the song plays when nothing is asked for`() {
        assertEquals(
            SampleMedia(song, songMissing = false),
            sampleMedia(requested = null, song = song, clip = clip) { true },
        )
    }

    @Test
    fun `a song path that is gone falls back to the clip`() {
        assertEquals(standIn, sampleMedia(requested = null, song = song, clip = clip) { it != song })
    }

    @Test
    fun `with no song set the clip plays`() {
        assertEquals(standIn, sampleMedia(requested = null, song = null, clip = clip) { true })
        assertEquals(standIn, sampleMedia(requested = null, song = " ", clip = clip) { true })
    }
}
