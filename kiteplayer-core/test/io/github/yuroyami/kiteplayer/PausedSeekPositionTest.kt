@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * A paused player reports the time of the frame a seek showed, however long the renderer took to
 * draw it (#588). The video clock ran from the moment the frame was handed over, so a draw of
 * 400 ms put the position 400 ms ahead of the picture until playback resumed.
 */
class PausedSeekPositionTest {

    @Test
    fun aSlowFirstFrameAfterAPausedSeekDoesNotMoveThePosition() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 8_000_000),
            renderer = RecordingRenderer(presentDuration = 400.milliseconds),
        )
        harness.openWithRenderer()
        harness.run(200.milliseconds)

        assertIs<SeekResult.Applied>(harness.core.seek(Pts(4_000_000), SeekMode.Precise))
        val shown = checkNotNull(harness.renderer).timestamps.last()
        assertEquals(shown.micros, harness.core.position().inWholeMicroseconds, "the position when the seek returned")
        harness.run(600.milliseconds)
        assertEquals(shown.micros, harness.core.position().inWholeMicroseconds, "the position 600 ms into the pause")

        // Playback resumes from that frame.
        harness.core.play()
        harness.run(100.milliseconds)
        val resumed = harness.core.position().inWholeMicroseconds
        assertEquals(true, resumed in shown.micros..shown.micros + 150_000, "resumed at $resumed from ${shown.micros}")
        harness.close()
    }
}
