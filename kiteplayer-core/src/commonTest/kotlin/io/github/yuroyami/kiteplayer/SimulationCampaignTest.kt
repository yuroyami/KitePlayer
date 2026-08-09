package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.StatusMachine
import io.github.yuroyami.kiteplayer.internal.SyncLaw
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A hundred seeded sessions, each checked against invariants rather than against an expected outcome.
 *
 * Outcomes are not the point here. A session with a decoder that refuses packets, a device whose drain
 * never finishes and eight seeks in the wrong places will not present a fixed number of frames, and
 * asserting one would only produce a test that has to be updated whenever anything improves. What must
 * hold whatever happens is a short list:
 *
 * 1. No frame from a superseded generation is ever presented, and no sample from one is ever audible.
 * 2. Within one generation, presented timestamps never decrease.
 * 3. Every frame and every packet is closed exactly once.
 * 4. The session reaches a terminal state in bounded virtual time.
 * 5. Drift stays inside the sync law's own tolerance.
 * 6. Every status transition is one the state machine allows.
 * 7. Every command completes exactly once.
 *
 * Faults are seeded rather than random, so a session that breaks one of these is a name a test can be
 * written against instead of a story about a machine that was busy that day.
 */
class SimulationCampaignTest {

    @Test
    fun `one hundred seeded sessions hold every invariant`() = runTest(timeout = 20.minutes) {
        val broken = mutableListOf<String>()
        var worstDriftUs = 0L
        var presentedTotal = 0L

        for (seed in 1..SEEDS) {
            val report = runSeed(this, seed)
            worstDriftUs = maxOf(worstDriftUs, report.worstDriftUs)
            presentedTotal += report.presented
            if (report.violations.isNotEmpty()) {
                broken += "seed $seed: ${report.violations.joinToString("; ")}"
            }
        }

        assertTrue(
            presentedTotal > SEEDS * 4,
            "the campaign has to actually play something to prove anything: $presentedTotal frames over " +
                "$SEEDS seeds",
        )
        assertEquals(
            emptyList(),
            broken,
            "seeds broke an invariant. Each is reproducible on its own: add it by name next to " +
                "`seed 44 keeps every invariant` and fix the engine, never the seed.",
        )
        assertTrue(
            worstDriftUs <= SyncLaw.SYNC_THRESHOLD_MAX_US,
            "the worst drift over the campaign was $worstDriftUs us, and the sync law's own correction " +
                "threshold is ${SyncLaw.SYNC_THRESHOLD_MAX_US} us: past that the law is not keeping up",
        )
    }

    /**
     * Seed 44, by name, because it is the seed that found a wedge.
     *
     * Its media overshoots a seek, which sends the engine down the overshoot backoff ladder, and the ladder
     * flushes and restarts the pipeline a second time under the SAME epoch. The demuxer worker was watching
     * the epoch to decide when its own memory of having reached the end of the container was void, so on the
     * second restart it kept that memory, never read another packet, and never signalled the end of a stream
     * again. Every queue stayed empty and the start rendezvous could never be satisfied: playback stopped for
     * good with no error. Seeds 77 and 88 are the same fixture and found the same thing.
     *
     * The fix was to count restarts rather than compare epochs, because a restart is what voids a worker's
     * local state and an epoch change is only one of the reasons for one. This test is checked in on its own
     * so that case can never be lost among a hundred others.
     */
    @Test
    fun `seed 44 keeps every invariant`() = runTest(timeout = 2.minutes) {
        val report = runSeed(this, 44)
        assertEquals(emptyList(), report.violations, "seed 44 is the campaign's named regression")
    }

    // ---------------------------------------------------------------------------------------------

    private class SeedReport(
        val violations: List<String>,
        val worstDriftUs: Long,
        val presented: Long,
    )

    /**
     * One session, driven by the seed and checked by it.
     *
     * The action sequence is deliberately hostile: it seeks while opening, pauses in the middle of a
     * seek, detaches the renderer during playback and asks for the end of the file. All of it is legal,
     * and all of it is what a user with a seek bar does.
     */
    private suspend fun runSeed(scope: TestScope, seed: Int): SeedReport {
        val random = Random(seed)
        val faults = FaultPlan(
            seed = seed,
            refuseSendPercent = if (seed % 3 == 0) 12 else 0,
            emptyDecodePercent = if (seed % 4 == 0) 8 else 0,
            refusePresentPercent = if (seed % 5 == 0) 10 else 0,
        ).also {
            if (seed % 17 == 0) it.drainHangs = true
            if (seed % 23 == 0) it.videoDecodersRefuse = true
            if (seed % 29 == 0) it.sessionCloseThrows = true
        }
        val script = MediaScript(
            durationUs = 1_500_000L + (seed % 5) * 500_000L,
            hasVideo = seed % 19 != 0,
            hasAudio = seed % 13 != 0,
            keyframeIntervalUs = if (seed % 7 == 0) 200_000 else 400_000,
            seekOvershootUs = if (seed % 11 == 0) 350_000 else 0,
        )
        val renderer = RecordingRenderer(accepts = seed % 31 != 0)
        val harness = CoreHarness(scope, script = script, faults = faults, renderer = renderer)
        val violations = mutableListOf<String>()
        var worstDriftUs = 0L

        try {
            harness.attachRenderer()
            val opened = runCatching { harness.open() }
            if (opened.isFailure && opened.exceptionOrNull() !is PlaybackException) {
                violations += "open failed with ${opened.exceptionOrNull()} instead of a typed error"
            }

            if (opened.isSuccess) {
                harness.core.play()
                repeat(ACTIONS) {
                    when (random.nextInt(10)) {
                        0, 1, 2, 3 -> {
                            // A seek, with the audible-staleness check wrapped around it. The window opens
                            // once the seek has returned: what was heard while it was still running is
                            // audio from before it, which was legal until the device was stopped.
                            val to = Pts(random.nextLong(0, script.durationUs + 400_000))
                            val mode = SeekMode.entries[random.nextInt(SeekMode.entries.size)]
                            runCatching { harness.core.seek(to, mode) }
                            harness.sink.audibleSigns.clear()
                            harness.run((20 + random.nextInt(200)).milliseconds)
                            val epoch = harness.core.snapshots.value.generation
                            val allowed = setOf(0, epochSign(epoch))
                            val stale = harness.sink.audibleSigns - allowed
                            if (stale.isNotEmpty()) {
                                violations += "audio from a superseded epoch was heard: signs $stale, " +
                                    "current $epoch wants ${epochSign(epoch)}"
                            }
                        }
                        4 -> {
                            harness.core.pause()
                            harness.run((20 + random.nextInt(120)).milliseconds)
                            harness.core.play()
                        }
                        5 -> {
                            harness.core.seekLater(
                                Pts(random.nextLong(0, script.durationUs)),
                                SeekMode.KeyframeThenRefine,
                            )
                        }
                        6 -> runCatching { harness.core.detachRenderer() }
                        7 -> runCatching { harness.core.attachRenderer(renderer) }
                        8 -> runCatching { harness.core.setVolume(random.nextInt(0, 101) / 100f) }
                        else -> harness.core.setMuted(random.nextBoolean())
                    }
                    harness.run((10 + random.nextInt(150)).milliseconds)
                    worstDriftUs = maxOf(worstDriftUs, abs(harness.core.stats.value.avDrift.inWholeMicroseconds))
                }

                // Let it run to its own end, which is the bounded-termination half of the campaign.
                var settled = false
                repeat(40) {
                    if (harness.core.snapshots.value.status.let {
                            it == PlaybackStatus.Ended || it == PlaybackStatus.Failed
                        }
                    ) {
                        settled = true
                        return@repeat
                    }
                    delay(1.seconds)
                    worstDriftUs = maxOf(worstDriftUs, abs(harness.core.stats.value.avDrift.inWholeMicroseconds))
                }
                if (!settled) {
                    violations += "the session did not reach a terminal state in 40 s of virtual time: " +
                        harness.core.debugState
                }
            }

            // Invariant 1 and 2, at the renderer.
            val presentations = renderer.presentations
            var highest = presentations.firstOrNull()?.generation
            presentations.forEach { presentation ->
                val seen = highest
                if (seen != null && presentation.generation < seen) {
                    violations += "a frame from ${presentation.generation} was presented after $seen"
                }
                highest = presentation.generation
            }
            presentations.groupBy { it.generation }.forEach { (generation, group) ->
                group.zipWithNext().forEach { (earlier, later) ->
                    if (later.pts < earlier.pts) {
                        violations += "$generation presented ${later.pts} after ${earlier.pts}"
                    }
                }
            }

            // Invariant 6.
            if (harness.core.illegalTransitions.isNotEmpty()) {
                violations += "illegal status transitions ${harness.core.illegalTransitions}"
            }
            harness.core.statusHistory.zipWithNext().forEach { (from, to) ->
                if (!StatusMachine.isLegal(from, to)) violations += "$from to $to"
            }

            // Invariant 7: close resolves everything, and it either succeeds or says why.
            val closed = runCatching { harness.close() }
            val closeFailure = closed.exceptionOrNull()
            if (closeFailure != null && closeFailure !is PlaybackException) {
                violations += "close failed with $closeFailure instead of a typed error"
            }

            // Invariant 3, once nothing is running any more.
            if (harness.ledger.liveCount != 0) {
                violations += "${harness.ledger.liveCount} frames or packets were never closed"
            }
            if (harness.ledger.doubleCloseCount != 0) {
                violations += "${harness.ledger.doubleCloseCount} were closed twice"
            }
            if (harness.core.snapshots.value.status != PlaybackStatus.Idle) {
                violations += "after close the status was ${harness.core.snapshots.value.status}"
            }
        } catch (failure: AssertionError) {
            throw failure
        } catch (failure: Throwable) {
            violations += "the harness threw $failure"
            runCatching { harness.close() }
        }

        return SeedReport(violations, worstDriftUs, renderer.count.toLong())
    }

    private companion object {
        const val SEEDS = 100

        /** Actions per session. Enough to reach every rule, few enough that a hundred runs stay quick. */
        const val ACTIONS = 8
    }
}
