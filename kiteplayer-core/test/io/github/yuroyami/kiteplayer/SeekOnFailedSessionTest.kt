package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A seek that waits while the session fails under it is not told it landed at zero (#787). It was:
 * the pass after the failure found no session and answered the seek as applied at the start.
 */
class SeekOnFailedSessionTest {

    @Test
    fun aSeekQueuedBehindAFailingRebuildIsNotAnsweredAsLandedAtZero() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)
        // The video rebuild opens the media again, and that open now fails.
        harness.backend.openFailure = IllegalStateException("the media went away")
        val change = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { harness.core.selectTrack(TrackKind.Video, TrackId(0)) }
        }
        val seek = async(start = CoroutineStart.UNDISPATCHED) { harness.core.seek(Pts(3_000_000), SeekMode.Precise) }
        harness.run(2.seconds)
        change.await()
        val result = seek.await()
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status, "the rebuild did not fail, so this proves nothing")
        assertNotEquals<SeekResult>(SeekResult.Applied(Pts.Zero), result, "a failed player said the seek landed at the start")
        harness.close()
    }
}
