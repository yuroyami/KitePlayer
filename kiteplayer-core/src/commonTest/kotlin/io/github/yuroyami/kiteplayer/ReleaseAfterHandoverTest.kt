@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A hardware renderer does not send a frame to the display itself. It queues the release, and the
 * decoder performs it the next time the decode worker calls it (#139). So the worker must call the
 * decoder as soon as the schedule hands a frame over, not whenever its own wait happens to end.
 */
class ReleaseAfterHandoverTest {

    /** Records when each frame asked for its release and when the next decoder call served it. */
    private class ReleaseServingDecoder(
        private val inner: VideoDecoder,
        private val clock: MonotonicClock,
    ) : VideoDecoder by inner {
        private val waiting = ArrayDeque<Long>()
        val lagsNanos = mutableListOf<Long>()

        private fun serve() {
            val now = clock.nanos()
            while (waiting.isNotEmpty()) lagsNanos += now - waiting.removeFirst()
        }

        override suspend fun send(packet: PlayerPacket?): Boolean {
            serve()
            return inner.send(packet)
        }

        override suspend fun receive(): VideoFrame? {
            serve()
            val frame = inner.receive() ?: return null
            return object : VideoFrame by frame {
                override fun close() {
                    waiting.addLast(clock.nanos())
                    frame.close()
                }
            }
        }
    }

    private class ServingFactory(
        private val script: MediaScript,
        private val ledger: LeakLedger,
        private val clock: MonotonicClock,
    ) : VideoDecoderFactory {
        var decoder: ReleaseServingDecoder? = null
        override val name: String = "release serving"
        override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
            if (stream.kind != TrackKind.Video) return null
            return ReleaseServingDecoder(ScriptedVideoDecoder(script, ledger, FaultPlan.None), clock)
                .also { decoder = it }
        }
    }

    @Test
    fun `the decoder serves each release at the instant the frame is handed over`() = runTest {
        // 23.976 fps, so the frame grid cannot line up with any fixed poll of the worker by chance.
        val script = MediaScript(durationUs = 30_000_000, videoFrameDurationUs = 41_708)
        val ledger = LeakLedger()
        val factory = ServingFactory(script, ledger, VirtualClock(testScheduler))
        val harness = CoreHarness(
            this,
            script = script,
            ledger = ledger,
            renderer = RecordingRenderer(decoderFactories = listOf(factory)),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(10.seconds)

        val lags = requireNotNull(factory.decoder).lagsNanos.drop(24)
        assertTrue(lags.size > 150, "only ${lags.size} releases were served")
        val late = lags.filter { it > 0L }
        assertEquals(
            emptyList(),
            late.map { it / 1_000_000L }.distinct().sorted(),
            "${late.size} of ${lags.size} releases waited for the worker, in whole milliseconds",
        )
        harness.close()
    }
}
