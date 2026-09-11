package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which media the visualiser is for: sound with no picture, where album art is not a picture. */
class IsAudioOnlyTest {

    private fun opened(vararg tracks: TrackInfo) = PlayerSnapshot(tracks = Tracks(all = tracks.toList()))

    private fun audio(id: Int) = TrackInfo(TrackId(id), TrackKind.Audio, codec = "mp3")

    private fun picture(id: Int, cover: Boolean = false) =
        TrackInfo(TrackId(id), TrackKind.Video, codec = if (cover) "mjpeg" else "h264", isCoverArt = cover)

    @Test
    fun `a song with no picture is audio only`() {
        assertTrue(opened(audio(0)).isAudioOnly)
    }

    @Test
    fun `album art is not a picture`() {
        assertTrue(opened(audio(0), picture(1, cover = true)).isAudioOnly)
    }

    @Test
    fun `a film is not audio only even with a cover in the container`() {
        assertFalse(opened(picture(0), audio(1)).isAudioOnly)
        assertFalse(opened(picture(0), audio(1), picture(2, cover = true)).isAudioOnly)
    }

    @Test
    fun `nothing open or a picture with no sound is not audio only`() {
        assertFalse(PlayerSnapshot().isAudioOnly)
        assertFalse(opened(picture(0)).isAudioOnly)
    }
}
