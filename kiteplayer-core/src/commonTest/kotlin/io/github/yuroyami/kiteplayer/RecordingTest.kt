package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * The engine side of a recording: which calls reach the source, and which end of a recording is a
 * surprise worth a warning. The scripted source writes no file, so the file itself is proven by the
 * FFmpeg backend's own tests.
 */
class RecordingTest {

    private val recordable = MediaScript(recordable = true)

    @Test
    fun `a source without the capability refuses to record`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        assertFailsWith<UnsupportedOperationException> { harness.core.startRecording(PATH) }
        harness.close()
    }

    @Test
    fun `recording needs an open media item`() = runTest {
        val harness = CoreHarness(this, script = recordable)

        assertFailsWith<IllegalStateException> { harness.core.startRecording(PATH) }
        harness.close()
    }

    @Test
    fun `start and stop reach the source without a warning`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.openWithRenderer()

        harness.core.startRecording(PATH)
        harness.run(300.milliseconds)
        harness.core.stopRecording()

        assertEquals(listOf("start $PATH", "stop"), harness.session.recordingSource!!.calls)
        assertEquals(emptyList(), recordingWarnings(harness))
        harness.close()
    }

    @Test
    fun `a second start while recording is refused`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.openWithRenderer()
        harness.core.startRecording(PATH)

        assertFailsWith<IllegalStateException> { harness.core.startRecording("$PATH.second") }
        assertEquals(listOf("start $PATH"), harness.session.recordingSource!!.calls)
        harness.close()
    }

    @Test
    fun `a seek ends the recording with a warning`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.openWithRenderer()
        harness.core.startRecording(PATH)

        harness.core.seek(Pts(2_000_000), SeekMode.Precise)

        assertEquals(listOf("start $PATH", "stop"), harness.session.recordingSource!!.calls)
        val warning = recordingWarnings(harness).single()
        assertEquals(PATH, warning.path)
        harness.close()
    }

    @Test
    fun `stopping the player ends the recording without a warning`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.openWithRenderer()
        harness.core.startRecording(PATH)
        val recording = harness.session

        harness.core.stop()

        assertEquals(listOf("start $PATH", "stop"), recording.recordingSource!!.calls)
        assertEquals(emptyList(), recordingWarnings(harness))
        harness.close()
    }

    @Test
    fun `moving to the next queue item ends the recording with a warning`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
        harness.core.startRecording(PATH)
        val recording = harness.session

        harness.core.queueNext()

        assertEquals(listOf("start $PATH", "stop"), recording.recordingSource!!.calls)
        assertEquals(PATH, recordingWarnings(harness).single().path)
        harness.close()
    }

    @Test
    fun `closing the player ends the recording without a warning`() = runTest {
        val harness = CoreHarness(this, script = recordable)
        harness.openWithRenderer()
        harness.core.startRecording(PATH)
        val recording = harness.session

        harness.close()

        assertEquals(listOf("start $PATH", "stop"), recording.recordingSource!!.calls)
        assertEquals(emptyList(), recordingWarnings(harness))
    }

    private fun recordingWarnings(harness: CoreHarness): List<PlaybackWarning.RecordingStopped> =
        harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.RecordingStopped>()

    private companion object {
        const val PATH = "/recordings/scripted.mkv"
    }
}
