package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.MediaSink
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.VideoEncoderSpec
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A recording never costs the media it records (#471). Its file used to be emptied the moment the
 * recording started, so a recording into the file playing, or into another spelling of it,
 * destroyed that file. The clip is made here, so these run wherever FFmpeg does.
 */
class RecordingOverInputTest {

    /** Two seconds of 64 by 64 MPEG-4 Part 2 in Matroska, a keyframe every ten frames. */
    private fun clip(name: String): String {
        val path = recordingScratchPath("kiteplayer-over-input-${Random.nextLong().toULong()}-$name")
        MediaSink.open(path, format = "matroska").use { sink ->
            val video = sink.addVideoEncoder(
                VideoEncoderSpec(CodecId("mpeg4"), 64, 64, frameRate = Rational(25, 1), keyframeIntervalFrames = 10),
            )
            runBlocking {
                video.drive(
                    (0 until 50).asFlow().map { i ->
                        val pixels = ByteArray(64 * 64 * 3 / 2) { ((it + i * 7) % 251).toByte() }
                        Frame.ofVideo(pixels, 64, 64, PixelFormat.Yuv420p, i * 40_000L)
                    },
                )
            }
        }
        return path
    }

    private suspend fun openSource(path: String): KiteFFmpegSource =
        KiteFFmpegSourceFactory().open(MediaItem(path)) as KiteFFmpegSource

    private suspend fun KiteFFmpegSource.readAll() {
        while (true) readPacket()?.close() ?: return
    }

    /**
     * Records [input] into [destination] while reading it to the end, then closes it, which ends a
     * recording still running. Returns the warnings, and whether a recording still ran at the end.
     */
    private suspend fun recordInto(input: String, destination: String): Pair<List<PlaybackWarning>, Boolean> {
        val warnings = mutableListOf<PlaybackWarning>()
        val source = openSource(input)
        source.onWarning = { warnings += it }
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(destination)
            source.readAll()
            return warnings to (source.recordingPath != null)
        } finally {
            source.close()
        }
    }

    private fun assertRefusedAndWhole(input: String, destination: String) = runBlocking {
        val before = assertNotNull(readTestFile(input))
        val (warnings, running) = recordInto(input, destination)
        assertTrue(!running, "the refused recording still runs")
        val stopped = warnings.filterIsInstance<PlaybackWarning.RecordingStopped>().single()
        assertTrue("same file" in stopped.reason, "stopped for another reason: ${stopped.reason}")
        assertContentEquals(before, readTestFile(input), "the file playing changed")
        // And it still plays.
        val again = openSource(input)
        try {
            again.selectStreams(again.streams.map { it.index }.toSet())
            assertNotNull(again.readPacket(), "the file playing lost its packets").close()
        } finally {
            again.close()
        }
    }

    @Test
    fun aRecordingIntoTheFilePlayingLeavesItWhole() {
        val input = clip("same.mkv")
        assertRefusedAndWhole(input, input)
    }

    @Test
    fun aRecordingIntoAnotherSpellingOfTheFilePlayingLeavesItWhole() {
        val input = clip("spelled.mkv")
        val separator = input.lastIndexOfAny(charArrayOf('/', '\\'))
        assertRefusedAndWhole(input, input.substring(0, separator + 1) + "./" + input.substring(separator + 1))
    }

    @Test
    fun startingARecordingLeavesAFileAlreadyThereWholeUntilTheFirstPacket() = runBlocking {
        val input = clip("input.mkv")
        val existing = clip("existing.mkv")
        val before = assertNotNull(readTestFile(existing))
        val source = openSource(input)
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            source.startRecording(existing)
            assertContentEquals(before, readTestFile(existing), "the start emptied the file already there")
            source.stopRecording()
        } finally {
            source.close()
        }
        assertContentEquals(before, readTestFile(existing), "a recording that wrote no packet changed the file")
    }

    @Test
    fun aRecordingIntoAnotherFileReplacesItWithTheCopy() = runBlocking {
        val input = clip("copied.mkv")
        val existing = clip("replaced.mkv")
        val (warnings, running) = recordInto(input, existing)
        assertTrue(running, "the recording ended before the media did")
        assertEquals(emptyList(), warnings.filterIsInstance<PlaybackWarning.RecordingStopped>())
        val recorded = openSource(existing)
        try {
            val seconds = assertNotNull(recorded.duration, "the copy states no duration").micros / 1e6
            assertTrue(seconds in 1.5..2.5, "two seconds were recorded and the file holds $seconds")
        } finally {
            recorded.close()
        }
    }

    @Test
    fun aPathThatCannotBeCreatedIsStillRefusedAtTheStart() = runBlocking {
        val source = openSource(clip("nowhere.mkv"))
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
            assertFailsWith<IllegalArgumentException> {
                source.startRecording(recordingScratchPath("kiteplayer-no-such-directory-${Random.nextLong().toULong()}/recording.mkv"))
            }
            assertNull(source.recordingPath)
        } finally {
            source.close()
        }
    }
}
