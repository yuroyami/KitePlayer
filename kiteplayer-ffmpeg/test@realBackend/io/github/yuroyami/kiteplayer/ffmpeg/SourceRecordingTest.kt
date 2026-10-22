package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FFmpeg source's recording, written from real media and read back. The house clip has a
 * keyframe every second.
 */
class SourceRecordingTest {

    @Test
    fun aRecordingStartsAtTheNextKeyframeAndHoldsWhatWasRead() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-keyframe.mkv")
        val source = openSource("$mediaDir/sync1080p30.mp4")
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            // Half a second in, so the file has to wait for the keyframe at one second.
            source.readUntil(500_000)
            source.startRecording(path)
            assertEquals(path, source.recordingPath)
            source.readUntil(3_000_000)
            source.stopRecording()
            assertNull(source.recordingPath)
        } finally {
            source.close()
        }

        val recorded = openSource(path)
        try {
            assertEquals(listOf(TrackKind.Video, TrackKind.Audio), recorded.streams.map { it.kind })
            val seconds = assertNotNull(recorded.duration, "the file must state its duration").micros / 1e6
            assertTrue(seconds in 1.5..2.5, "two seconds were recorded and the file holds $seconds")
            val video = recorded.streams.first { it.kind == TrackKind.Video }
            recorded.selectStreams(setOf(video.index))
            val first = assertNotNull(recorded.readPacket(), "the file holds no picture")
            first.use { assertTrue(it.isKeyframe, "the file must start on a keyframe") }
        } finally {
            recorded.close()
        }
    }

    @Test
    fun aRecordingHoldsEveryAudioAndSubtitleTrack() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-tracks.mkv")
        val source = openSource("$mediaDir/multitrack.mkv")
        val codecs = source.streams.map { it.codec }
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(path)
            source.readUntil(2_000_000)
            source.stopRecording()
        } finally {
            source.close()
        }

        val recorded = openSource(path)
        try {
            assertEquals(listOf("h264", "aac", "aac", "subrip", "subrip"), codecs)
            assertEquals(codecs, recorded.streams.map { it.codec })
        } finally {
            recorded.close()
        }
    }

    @Test
    fun aSubtitleFormatMatroskaCannotHoldIsLeftOut() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-movtext.mkv")
        val source = openSource("$mediaDir/movtext.mp4")
        val warnings = mutableListOf<PlaybackWarning>()
        source.onWarning = { warnings += it }
        try {
            assertEquals(listOf("h264", "aac", "mov_text"), source.streams.map { it.codec })
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(path)
            source.readUntil(2_000_000)
            source.stopRecording()
        } finally {
            source.close()
        }

        assertEquals(emptyList(), warnings.filterIsInstance<PlaybackWarning.RecordingStopped>())
        val recorded = openSource(path)
        try {
            assertEquals(listOf("h264", "aac"), recorded.streams.map { it.codec })
        } finally {
            recorded.close()
        }
    }

    @Test
    fun aSeekEndsTheRecordingWithAWarningAndLeavesAValidFile() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-seek.mkv")
        val source = openSource("$mediaDir/sync1080p30.mp4")
        val warnings = mutableListOf<PlaybackWarning>()
        source.onWarning = { warnings += it }
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(path)
            source.readUntil(1_500_000)
            source.seekToKeyframe(Pts(6_000_000))
            assertNull(source.recordingPath, "a seek must end the recording")
            assertEquals(path, warnings.filterIsInstance<PlaybackWarning.RecordingStopped>().single().path)
            // Nothing read after the seek may reach the file.
            source.readUntil(8_000_000)
        } finally {
            source.close()
        }

        val recorded = openSource(path)
        try {
            val seconds = assertNotNull(recorded.duration, "the file must state its duration").micros / 1e6
            assertTrue(seconds in 1.0..2.0, "a second and a half was recorded and the file holds $seconds")
        } finally {
            recorded.close()
        }
    }

    @Test
    fun closingTheSourceFinishesTheFile() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-close.mkv")
        val source = openSource("$mediaDir/sync1080p30.mp4")
        source.selectStreams(source.streams.map { it.index }.toSet())
        source.startRecording(path)
        source.readUntil(2_000_000)
        source.close()

        val recorded = openSource(path)
        try {
            val seconds = assertNotNull(recorded.duration, "the file must state its duration").micros / 1e6
            assertTrue(seconds in 1.5..2.5, "two seconds were recorded and the file holds $seconds")
        } finally {
            recorded.close()
        }
    }

    @Test
    fun aSecondStartIsRefusedAndTheFirstRecordingGoesOn() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val path = recordingScratchPath("kiteplayer-recording-first.mkv")
        val source = openSource("$mediaDir/sync1080p30.mp4")
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(path)
            assertFailsWith<IllegalStateException> {
                source.startRecording(recordingScratchPath("kiteplayer-recording-second.mkv"))
            }
            assertEquals(path, source.recordingPath)
        } finally {
            source.close()
        }
    }

    @Test
    fun aPathThatCannotBeCreatedIsRefused() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = openSource("$mediaDir/sync1080p30.mp4")
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            assertFailsWith<IllegalArgumentException> {
                source.startRecording(recordingScratchPath("kiteplayer-no-such-directory/recording.mkv"))
            }
            assertNull(source.recordingPath)
        } finally {
            source.close()
        }
    }

    private suspend fun openSource(path: String): KiteFFmpegSource =
        KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource

    /** Reads and drops packets until one is timed at [micros] or later, or the media ends. */
    private suspend fun KiteFFmpegSource.readUntil(micros: Long) {
        while (true) {
            val packet = readPacket() ?: return
            val pts = packet.pts?.micros
            packet.close()
            if (pts != null && pts >= micros) return
        }
    }
}
