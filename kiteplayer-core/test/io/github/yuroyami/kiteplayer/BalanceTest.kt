@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stereo balance, the last thing the facade's documentation listed as absent.
 *
 * It rides the same per-channel stage ReplayGain uses, which is why this item is small: the stage
 * was already per channel because this was coming. The two combine by multiplying, so a balanced
 * track that also carries a ReplayGain tag gets both.
 *
 * Only the first two channels move. On a 5.1 mix, panning the centre or the surrounds is a
 * different feature with a different name, and quietly doing it here would surprise anyone who
 * asked for what the word means.
 */
class BalanceTest {

    private suspend fun channelPeaks(
        balance: Float,
        scope: kotlinx.coroutines.test.TestScope,
    ): Pair<Float, Float> {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        harness.core.post(
            io.github.yuroyami.kiteplayer.internal.CoreCommand.SetBalance(
                balance,
                kotlinx.coroutines.CompletableDeferred(),
            ),
        )
        // Two waits, and the first one is the point. The trim is applied as audio is WRITTEN, so a
        // live change cannot reach what is already in the ring: everything buffered at the old
        // balance has to drain first, which is the ring's whole depth. Measuring during that drain
        // reads the old setting and looks like a bug in the code rather than one in the test.
        harness.run(400.milliseconds)
        // And the peaks are running maxima, so what was heard before now has to be forgotten too.
        harness.sink.clearChannelPeaks()
        harness.run(200.milliseconds)
        val left = harness.sink.channelPeak(0)
        val right = harness.sink.channelPeak(1)
        harness.close()
        return left to right
    }

    @Test
    fun `centre leaves both channels alone`() = runTest {
        val (left, right) = channelPeaks(0f, this)
        assertTrue(left > 0f && right > 0f, "the harness heard nothing, so the comparison is empty")
        assertEquals(left, right, absoluteTolerance = 0.001f)
    }

    @Test
    fun `full left silences the right channel and leaves the left at unity`() = runTest {
        val (left, right) = channelPeaks(-1f, this)
        assertEquals(0f, right, absoluteTolerance = 0.001f, message = "the right channel was not silenced")
        assertTrue(left > 0.5f, "the left channel was attenuated too, and balance must not do that")
    }

    @Test
    fun `full right silences the left channel`() = runTest {
        val (left, right) = channelPeaks(1f, this)
        assertEquals(0f, left, absoluteTolerance = 0.001f, message = "the left channel was not silenced")
        assertTrue(right > 0.5f, "the right channel was attenuated too")
    }

    @Test
    fun `half left halves the right channel and keeps the left`() = runTest {
        // The law is an attenuation of the channel being turned away from, never a boost of the
        // other: a balance that amplified could clip material that was already at full scale.
        val (left, right) = channelPeaks(-0.5f, this)
        val (centreLeft, centreRight) = channelPeaks(0f, this)
        assertEquals(centreLeft, left, absoluteTolerance = 0.001f, message = "the near channel moved")
        assertTrue(
            abs(right / centreRight - 0.5f) < 0.02f,
            "expected half on the far channel, heard $right against $centreRight",
        )
    }

    @Test
    fun `the snapshot carries what was set`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(50.milliseconds)
        assertEquals(0f, harness.core.snapshots.value.balance)
        harness.core.post(
            io.github.yuroyami.kiteplayer.internal.CoreCommand.SetBalance(
                -0.25f,
                kotlinx.coroutines.CompletableDeferred(),
            ),
        )
        harness.run(50.milliseconds)
        assertEquals(-0.25f, harness.core.snapshots.value.balance)
        harness.close()
    }

    @Test
    fun `a balance outside its range is refused`() = runTest {
        // Refused twice: once at the facade so a caller mistake throws on its own line, and once
        // inside the actor so a command posted from anywhere else cannot get past it either.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        for (bad in listOf(-1.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
            harness.core.post(io.github.yuroyami.kiteplayer.internal.CoreCommand.SetBalance(bad, reply))
            harness.run(20.milliseconds)
            assertFailsWith<IllegalArgumentException>("balance $bad was accepted") {
                reply.await()
            }
        }
        assertEquals(0f, harness.core.snapshots.value.balance, "a refused balance still moved the state")
        harness.close()
    }
}
