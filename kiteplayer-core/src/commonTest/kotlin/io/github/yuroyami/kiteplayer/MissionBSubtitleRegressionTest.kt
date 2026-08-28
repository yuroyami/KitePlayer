package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Regression coverage for HANDOFF Mission B: dense subtitles must never monopolise the actor. */
class MissionBSubtitleRegressionTest {

    private val config = PlayerConfig(
        subtitles = SubtitleConfig(preferredLanguages = listOf("eng")),
        progressInterval = 50.milliseconds,
    )

    private fun cue(startMicros: Long, endMicros: Long, text: String): SubtitleCue.Text =
        SubtitleCue.Text(
            startMicros = startMicros,
            endMicros = endMicros,
            spans = listOf(StyledSpan(text)),
        )

    @Test
    fun equalStartCuesShareOnePacketAndAreDecodedExactlyOnce() = runTest {
        val probe = ScriptedSubtitleProbe()
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                subtitleCues = listOf(
                    cue(500_000, 1_500_000, "lower"),
                    cue(500_000, 2_000_000, "upper"),
                ),
                subtitleProbe = probe,
            ),
            config = config,
        )

        harness.openWithRenderer()
        harness.core.play()
        harness.run(750.milliseconds)

        val shown = harness.renderer!!.overlays.filterNotNull().last { it.images.isNotEmpty() }
        assertEquals(2, shown.images.size, "equal-start cues were duplicated or one was lost")
        assertEquals(1, probe.packetsSent, "equal-start cues must be represented by one container packet")
        assertEquals(1, probe.cueLookups, "one packet must perform one keyed cue lookup")
        assertEquals(2, probe.cuesReturned)
        harness.close()
    }

    @Test
    fun aSeekIntoASpanningCueLosesItUntilASeekBeforeItsStart() = runTest {
        // PINS A KNOWN LIMITATION, NOT A DESIRED OUTCOME. The real source redelivers packets from
        // the seek landing forward in file order only, so a cue that STARTED before the landing
        // and still spans it is not reconstructed (SALANKE S16, open). The scripted source used to
        // be more generous than the real one here, which made S16 unprovable; this test holds the
        // honest contract in place. If S16 is ever fixed with a bounded replay or a retained cue
        // cache, flip the first assertion.
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 7_000_000,
                subtitleCues = listOf(
                    cue(1_000_000, 2_000_000, "already ended"),
                    cue(1_000_000, 5_000_000, "still active"),
                ),
            ),
            config = config,
        )

        harness.openWithRenderer()
        harness.core.seek(Pts(3_000_000), SeekMode.Precise)
        harness.core.play()
        harness.run(100.milliseconds)

        val afterSeekInto = harness.renderer!!.overlays.filterNotNull().lastOrNull { it.images.isNotEmpty() }
        assertEquals(
            null,
            afterSeekInto,
            "the spanning cue came back after a seek past its start: S16 is fixed, update this test",
        )

        // The positive half: a seek to BEFORE the cue's start puts its packet ahead of the file
        // cursor again, so it is redelivered and both equal-start cues become active at 1s.
        harness.core.seek(Pts(500_000), SeekMode.Precise)
        harness.run(800.milliseconds)
        val afterSeekBefore = harness.renderer!!.overlays.filterNotNull().lastOrNull { it.images.isNotEmpty() }
        assertNotNull(afterSeekBefore, "a seek before the cue start no longer redelivers its packet")
        assertEquals(2, afterSeekBefore.images.size, "both equal-start cues should be active at 1s")
        harness.close()
    }

    @Test
    fun denseSubtitleBacklogCannotDelayPauseBeyondTheSparseCase() = runTest {
        data class Measurement(
            val pauseQueuedAtPacket: Int,
            val pauseDrainedAtPacket: Int,
            val maxPacketsPerPass: Int,
            val packetAttempts: Long,
            val cueLookups: Int,
            val mergeBatches: Long,
        )

        suspend fun measure(cueCount: Int): Measurement {
            // 48 packets/s, matching the 69,513-packet device file. Reusing the immutable span
            // keeps this a decoder/actor workload rather than an allocation benchmark.
            val spans = listOf(StyledSpan("dense"))
            val cues = List(cueCount) { index ->
                val start = index * 20_833L
                SubtitleCue.Text(
                    startMicros = start,
                    endMicros = start + 2_000_000L,
                    spans = spans,
                )
            }
            val probe = ScriptedSubtitleProbe()
            val script = MediaScript(
                durationUs = (cues.last().endMicros + 1_000_000L).coerceAtLeast(40_000_000L),
                subtitleCues = cues,
                subtitleProbe = probe,
            )
            val harness = CoreHarness(this@runTest, script = script, config = config)
            var playQueued = false
            var pauseQueuedAt: Int? = null
            var pauseDrainedAt: Int? = null

            // This callback runs from decoder.send(), inside handleSubtitles. It deterministically
            // models a UI command arriving while that handler owns the actor.
            probe.onPacketSent = { packetCount ->
                when {
                    !playQueued -> {
                        playQueued = true
                        harness.core.play()
                    }
                    pauseQueuedAt == null && harness.core.statusHistory.last() == PlaybackStatus.Playing -> {
                        pauseQueuedAt = packetCount
                        harness.core.pause()
                    }
                }
            }
            harness.core.onHandlerRun = { handler ->
                if (handler == "drainCommands" && pauseQueuedAt != null && pauseDrainedAt == null &&
                    harness.core.statusHistory.last() == PlaybackStatus.Playing
                ) {
                    pauseDrainedAt = probe.packetsSent
                }
            }

            harness.openWithRenderer()
            harness.run(1.milliseconds)
            harness.core.onHandlerRun = null
            val measurement = Measurement(
                pauseQueuedAtPacket = assertNotNull(pauseQueuedAt, "play never yielded to a dense subtitle drain"),
                pauseDrainedAtPacket = assertNotNull(pauseDrainedAt, "pause never reached the command handler"),
                maxPacketsPerPass = harness.core.subtitleMaxPacketAttemptsPerPass,
                packetAttempts = harness.core.subtitlePacketAttempts,
                cueLookups = probe.cueLookups,
                mergeBatches = harness.core.subtitleCueMergeBatches,
            )
            assertTrue(
                harness.core.statusHistory.contains(PlaybackStatus.Playing),
                "the setup never entered Playing, so it did not test pause during subtitle work",
            )
            assertEquals(PlaybackStatus.Paused, harness.core.statusHistory.last())
            harness.close()
            return measurement
        }

        val sparse = measure(100)
        val dense = measure(70_000)
        val sparseWait = sparse.pauseDrainedAtPacket - sparse.pauseQueuedAtPacket
        val denseWait = dense.pauseDrainedAtPacket - dense.pauseQueuedAtPacket

        assertEquals(sparseWait, denseWait, "70k cues changed command latency relative to 100 cues")
        assertTrue(denseWait <= 1, "pause waited behind $denseWait additional subtitle packets")
        assertTrue(dense.maxPacketsPerPass <= 32, "one actor pass decoded ${dense.maxPacketsPerPass} packets")
        assertTrue(dense.packetAttempts > 32, "the dense fixture never formed a multi-pass backlog")
        assertEquals(dense.packetAttempts.toInt(), dense.cueLookups, "the decoder did more than one lookup per packet")
        assertEquals(0, dense.mergeBatches, "timestamp-ordered packets took the cold reorder path")
    }
}
