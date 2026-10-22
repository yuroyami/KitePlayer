package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the engine tells the audio device an item's sound is (#446): film for a picture, music for
 * sound alone or under cover art, and whatever the item itself asks for over either.
 */
class AudioContentTest {

    private suspend fun CoreHarness.openAndRead(item: MediaItem): List<AudioContent> {
        attachRenderer()
        core.open(item)
        return sink.declaredContents.toList()
    }

    @Test
    fun soundAloneDeclaresMusic() = runTest {
        val harness = CoreHarness(this, script = MediaScript(hasVideo = false))
        assertEquals(listOf(AudioContent.Music), harness.openAndRead(MediaItem("scripted://song")))
        assertEquals(AudioContent.Music, harness.core.snapshots.value.audioContent)
        harness.close()
    }

    @Test
    fun aPictureDeclaresAFilm() = runTest {
        val harness = CoreHarness(this)
        assertEquals(listOf(AudioContent.Movie), harness.openAndRead(MediaItem("scripted://film")))
        assertEquals(AudioContent.Movie, harness.core.snapshots.value.audioContent)
        harness.close()
    }

    @Test
    fun coverArtIsNotAPictureAndDeclaresMusic() = runTest {
        val harness = CoreHarness(this, script = MediaScript(videoIsCoverArt = true))
        assertEquals(listOf(AudioContent.Music), harness.openAndRead(MediaItem("scripted://album-track")))
        assertEquals(AudioContent.Music, harness.core.snapshots.value.audioContent)
        harness.close()
    }

    @Test
    fun anItemThatSaysWhatItIsWinsOverItsPicture() = runTest {
        val harness = CoreHarness(this)
        val lecture = mediaItem("scripted://lecture") { audioContent(AudioContent.Speech) }
        assertEquals(listOf(AudioContent.Speech), harness.openAndRead(lecture))
        assertEquals(AudioContent.Speech, harness.core.snapshots.value.audioContent)
        harness.close()
    }

    @Test
    fun eachOpenDeclaresItsOwnItem() = runTest {
        val harness = CoreHarness(this)
        harness.openAndRead(MediaItem("scripted://film"))
        harness.core.stop()
        val podcast = mediaItem("scripted://podcast") { audioContent(AudioContent.Speech) }
        assertEquals(listOf(AudioContent.Movie, AudioContent.Speech), harness.openAndRead(podcast))
        harness.close()
    }

    @Test
    fun automaticIsAnsweredFromTheSelectedVideoTrack() {
        val video = TrackInfo(TrackId(0), TrackKind.Video, "h264")
        val cover = video.copy(isCoverArt = true)
        fun snapshot(track: TrackInfo?, content: AudioContent = AudioContent.Automatic) = PlayerSnapshot(
            media = MediaItem("x", audioContent = content),
            tracks = Tracks(all = listOfNotNull(track), selectedVideo = track?.id),
        )
        assertEquals(AudioContent.Movie, snapshot(video).audioContent)
        assertEquals(AudioContent.Music, snapshot(cover).audioContent)
        assertEquals(AudioContent.Music, snapshot(null).audioContent)
        assertEquals(AudioContent.Speech, snapshot(video, AudioContent.Speech).audioContent)
        assertEquals(AudioContent.Music, PlayerSnapshot().audioContent)
    }
}
