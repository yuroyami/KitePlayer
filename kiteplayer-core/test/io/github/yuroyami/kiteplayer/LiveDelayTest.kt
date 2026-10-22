package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The delay behind a sender that pushes in real time stays bounded (#395). A stall that the network
 * later makes good leaves the player that much further behind, and the player catches up through
 * the tempo stage. The delay is read from outside the engine, as the sender's time less the
 * player's position.
 *
 * The bounds are the engine's own, read with the default buffer policy, whose ready duration is
 * 1 s: the player catches up once it is more than half a second past that, and stops within a
 * tenth of a second of it. The delay read here can exceed the engine's by one video frame, 40 ms,
 * because the engine counts only the frames that have arrived and the newest one can be up to a
 * frame old. Catching up gains a tenth of a second each second, and three quarters of a second
 * more covers the 250 ms the engine waits between looks, as long again for the tempo stage to bring
 * the new speed to the output, and the 250 ms between this test's own readings.
 */
class LiveDelayTest {

    private fun liveScript(vararg holds: LongRange) =
        MediaScript(durationUs = 120_000_000, live = true, seekable = false, liveHolds = holds.toList())

    /** How far the player is behind the sender, in microseconds. */
    private fun CoreHarness.delayUs(): Long = senderUs() - core.position().inWholeMicroseconds

    private fun CoreHarness.senderUs(): Long = (clock.nanos() - source.liveOriginNanos) / 1_000

    /** Runs until [atUs] of the sender's time. */
    private suspend fun CoreHarness.runUntilSenderAt(atUs: Long) {
        val nowUs = senderUs()
        if (atUs > nowUs) run((atUs - nowUs).microseconds)
    }

    @Test
    fun aStallIsCaughtUpWithinItsBound() = runTest {
        // The network holds two seconds of the sender's media, from 15 s to 17 s, and then
        // delivers it all at once.
        val harness = CoreHarness(this, script = liveScript(STALL_FROM_US until STALL_UNTIL_US))
        harness.openWithRenderer()
        harness.core.play()
        harness.runUntilSenderAt(STALL_FROM_US - 500_000)
        val before = harness.delayUs()
        assertTrue(before <= CEILING_US, "the player started ${before / 1000} ms behind the sender")
        harness.runUntilSenderAt(STALL_UNTIL_US)
        // How far behind the player was, by the sender's time since the release.
        val series = (1..80).map {
            harness.run(250.milliseconds)
            harness.senderUs() - STALL_UNTIL_US to harness.delayUs()
        }
        val summary = "before the stall ${before / 1000} ms; after it ${series.joinToString { "${it.first / 1000}=${it.second / 1000}" }}"
        println("LiveDelayTest: $summary")
        val peak = series.maxOf { it.second }
        assertTrue(peak > CEILING_US, "the stall never took the player past the bound: $summary")
        assertBackWithin(series, peak, CEILING_US, summary)
        val floorAt = assertBackWithin(series, peak, FLOOR_US, summary)
        assertTrue(series.filter { it.first >= floorAt }.all { it.second <= FLOOR_US }, "the player did not stay caught up: $summary")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    /** A file is read as fast as the policy allows, so what is read ahead is no delay at all. */
    @Test
    fun aFileIsNeverSpedUp() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)
        val from = harness.core.position()
        harness.run(10.seconds)
        val moved = harness.core.position() - from
        assertTrue((moved - 10.seconds).absoluteValue <= 50.milliseconds, "a file played $moved in 10 s")
        harness.close()
    }

    /** A caller who chose a speed keeps it, even though a slower one lets the delay grow. */
    @Test
    fun theCallersSpeedIsLeftAlone() = runTest {
        val harness = CoreHarness(this, script = liveScript())
        harness.openWithRenderer()
        harness.core.setSpeed(0.9)
        harness.core.play()
        harness.run(3.seconds)
        val from = harness.core.position()
        harness.run(10.seconds)
        val moved = harness.core.position() - from
        assertTrue(harness.delayUs() > CEILING_US, "the delay never grew past the bound: ${harness.delayUs() / 1000} ms")
        assertTrue((moved - 9.seconds).absoluteValue <= 50.milliseconds, "0.9 times played $moved in 10 s")
        harness.close()
    }

    /**
     * Catching up on a turntable would raise every voice by a tenth, so a player that does not keep
     * the pitch stays behind.
     */
    @Test
    fun aPlayerThatMovesThePitchIsNotCaughtUp() = runTest {
        val harness = CoreHarness(
            this,
            config = PlayerConfig(audio = AudioConfig(preservePitch = false)),
            script = liveScript(STALL_FROM_US until STALL_UNTIL_US),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.runUntilSenderAt(STALL_UNTIL_US + 1_000_000)
        val after = harness.delayUs()
        harness.run(10.seconds)
        assertTrue(after > CEILING_US, "the stall never took the player past the bound: ${after / 1000} ms")
        assertTrue(abs(harness.delayUs() - after) <= 50_000, "the delay moved from ${after / 1000} to ${harness.delayUs() / 1000} ms")
        harness.close()
    }

    /**
     * Asserts that the delay in [series] falls to [bound] within the time its excess over [bound]
     * at the [peak] takes to clear at a tenth of a second each second, plus [SLACK_US], and returns
     * when it did.
     */
    private fun assertBackWithin(series: List<Pair<Long, Long>>, peak: Long, bound: Long, summary: String): Long {
        val allowedUs = (peak - bound) * 10 + SLACK_US
        val backAt = series.firstOrNull { it.second <= bound }?.first ?: Long.MAX_VALUE
        assertTrue(
            backAt <= allowedUs,
            "the delay was not back under ${bound / 1000} ms within ${allowedUs / 1000} ms of the release: $summary",
        )
        return backAt
    }

    private companion object {
        const val STALL_FROM_US = 15_000_000L
        const val STALL_UNTIL_US = 17_000_000L

        /** The ready duration and the half second the player lets build, plus one frame. */
        const val CEILING_US = 1_000_000L + 500_000L + 40_000L

        /** The ready duration and the tenth of a second the catching up stops within, plus one frame. */
        const val FLOOR_US = 1_000_000L + 100_000L + 40_000L

        const val SLACK_US = 750_000L
    }
}
