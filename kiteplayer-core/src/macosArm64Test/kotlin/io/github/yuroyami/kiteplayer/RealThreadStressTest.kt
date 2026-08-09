@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The same engine, on real threads, at real speed.
 *
 * Virtual time cannot find a race. Every worker in the simulation campaign shares one cooperative
 * thread, so the quiescence handshake, the seqlock the audio anchor is published through and the
 * generation checks at each hop are all exercised in an order that never interleaves. This test gives
 * every worker a thread of its own and pulls the device from a seventh, which is the shape production
 * runs in, and then hammers seeks and a close through it.
 *
 * The device is scripted rather than real, and deliberately so: it is what makes the strong assertion
 * possible. Every scripted sample carries the sign of the epoch it came from, and a volume multiply
 * cannot change a sign, so tapping what the device was handed proves that no audio from a superseded
 * epoch was ever heard. A real CoreAudio device offers no such tap, and the race being tested is
 * between a callback thread and the seek path, which this reproduces exactly.
 */
class RealThreadStressTest {

    /** One thread per worker, which is the confinement the engine's contracts assume. */
    private class RealDispatchers : PlaybackDispatchers {
        private val sessionContext = newSingleThreadContext("stress-session")
        private val demuxContext = newSingleThreadContext("stress-demux")
        private val videoDecodeContext = newSingleThreadContext("stress-video-decode")
        private val audioDecodeContext = newSingleThreadContext("stress-audio-decode")
        private val audioFeedContext = newSingleThreadContext("stress-audio-feed")
        private val videoScheduleContext = newSingleThreadContext("stress-video-schedule")

        override val session: CoroutineContext get() = sessionContext
        override val demux: CoroutineContext get() = demuxContext
        override val videoDecode: CoroutineContext get() = videoDecodeContext
        override val audioDecode: CoroutineContext get() = audioDecodeContext
        override val audioFeed: CoroutineContext get() = audioFeedContext
        override val videoSchedule: CoroutineContext get() = videoScheduleContext

        override fun close() {
            sessionContext.close()
            demuxContext.close()
            videoDecodeContext.close()
            audioDecodeContext.close()
            audioFeedContext.close()
            videoScheduleContext.close()
        }
    }

    @Test
    fun `seeks and a close hammered through a real thread pipeline hold every invariant`() = runBlocking {
        val ledger = LeakLedger()
        val renderer = RecordingRenderer()
        val script = MediaScript(durationUs = 8_000_000)
        val backend = ScriptedBackend(script, ledger)
        val sink = ScriptedSink()
        val dispatchers = RealDispatchers()
        val deviceContext = newSingleThreadContext("stress-device")
        val deviceScope = CoroutineScope(deviceContext + SupervisorJob())
        val core = PlaybackCore(
            config = PlayerConfig(),
            backend = backend,
            output = ScriptedOutput(MonotonicClock.System, sink),
            dispatchers = dispatchers,
            closeDispatchers = false,
        )

        // The device pulls on its own thread, at the rate the format implies, exactly as a real one does.
        val device: Job = deviceScope.launch { sink.runDevice(MonotonicClock.System) }

        val staleAudio = mutableListOf<String>()
        val random = Random(7)
        try {
            core.attachRenderer(renderer)
            core.open(MediaItem("scripted://real-threads"))
            core.play()
            delay(200.milliseconds)

            // Seeks fired into a pipeline that is genuinely running: the demuxer is mid-read, a decoder is
            // mid-send, the feeder is inside the ring and the device callback is on another thread reading
            // the samples the seek is about to discard.
            val answered = mutableListOf<SeekResult>()
            repeat(SEEKS) { index ->
                val target = Pts(random.nextLong(0, script.durationUs))
                val mode = SeekMode.entries[index % SeekMode.entries.size]
                answered += core.seek(target, mode)

                sink.audibleSigns.clear()
                delay(40.milliseconds)
                val epoch = core.snapshots.value.generation
                val stale = sink.audibleSigns - setOf(0, epochSign(epoch))
                if (stale.isNotEmpty()) staleAudio += "after seek $index to $target: signs $stale, $epoch"
            }

            assertEquals(SEEKS, answered.size, "every seek was answered exactly once")
            assertTrue(
                answered.any { it is SeekResult.Applied },
                "and at least one of them ran: ${answered.map { it::class.simpleName }}",
            )
            assertEquals(
                emptyList(),
                staleAudio,
                "audio from a superseded epoch reached the device on a real thread, which is the race " +
                    "generation tags alone cannot stop and quiescence exists to close",
            )

            // Presentation order, across every epoch the storm went through.
            var highest = renderer.presentations.firstOrNull()?.generation
            renderer.presentations.forEach { presentation ->
                val seen = highest
                assertTrue(
                    seen == null || presentation.generation >= seen,
                    "a frame from ${presentation.generation} was presented after $seen",
                )
                highest = presentation.generation
            }
            assertTrue(renderer.count > SEEKS, "the pipeline kept playing throughout: ${renderer.count} frames")

            // Close while everything is still running, which is the other half of the hammer.
            val closing = TimeSource.Monotonic.markNow()
            core.closeAndAwait()
            val closeTook = closing.elapsedNow()

            assertTrue(
                closeTook < CLOSE_BUDGET,
                "close took $closeTook, and it is bounded by design: a wedged teardown reports a " +
                    "compromised runtime rather than waiting",
            )
            assertEquals(emptyList(), core.illegalTransitions, "no status transition outside the machine")
            assertEquals(0, ledger.liveCount, "every frame and packet was released")
            assertEquals(0, ledger.doubleCloseCount, "and none of them twice")
            assertTrue(ledger.openCount > 100, "the run was real: ${ledger.openCount} frames and packets")
        } finally {
            device.cancel()
            deviceScope.cancel()
            deviceContext.close()
            dispatchers.close()
        }
    }

    private companion object {
        const val SEEKS = 25

        /** Wall time close may take. Teardown joins six real threads and closes as many dispatchers. */
        val CLOSE_BUDGET = 15.seconds
    }
}
