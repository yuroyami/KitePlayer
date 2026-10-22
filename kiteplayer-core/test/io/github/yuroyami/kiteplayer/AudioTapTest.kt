package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.test.runTest
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A caller sees the decoded audio on its way to the speaker.
 *
 * The scripted decoder fills every block with one value whose sign flips with each seek
 * generation, so a block from before a seek can be told from one after it, however the gain stage
 * scaled it.
 */
class AudioTapTest {

    private sealed interface Seen

    private data class Heard(
        val pts: Pts,
        val frames: Int,
        val sampleRate: Int,
        val channels: Int,
        val first: Float,
        val last: Float,
    ) : Seen

    private data object Cut : Seen

    private class RecordingTap : AudioTap {
        val seen = mutableListOf<Seen>()
        val heard: List<Heard> get() = seen.filterIsInstance<Heard>()

        override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
            seen += Heard(pts, frames, format.sampleRate, format.channels, interleaved[0], interleaved[frames * format.channels - 1])
        }

        override fun onDiscontinuity() {
            seen += Cut
        }
    }

    private suspend fun playing(harness: CoreHarness, vararg taps: AudioTap): KitePlayer {
        val player = KitePlayer(harness.core)
        taps.forEach(player::attachAudioTap)
        harness.openWithRenderer()
        player.play()
        return player
    }

    @Test
    fun `a tap sees each decoded block with its time and its samples`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000, sampleRate = 44_100, channels = 2))
        val tap = RecordingTap()
        playing(harness, tap)
        harness.run(500.milliseconds)

        val heard = tap.heard
        assertTrue(heard.size >= 10, "half a second of playback reached the tap as ${heard.size} blocks")
        assertTrue(heard.all { it.frames == 1024 }, "a block lost its decoded size: ${heard.map { it.frames }.distinct()}")
        assertTrue(heard.all { it.sampleRate == 44_100 && it.channels == 2 }, "a block arrived in another format than the decoder's")
        assertTrue(heard.zipWithNext().all { (a, b) -> b.pts > a.pts }, "timestamps must rise block by block")
        assertTrue(heard.all { it.first != 0f && it.first == it.last }, "the tap saw something other than the decoded samples")
        harness.close()
    }

    @Test
    fun `a seek tells the tap to let go and the next block starts at the target`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        val tap = RecordingTap()
        val player = playing(harness, tap)
        harness.run(300.milliseconds)
        val before = tap.seen.size

        player.seek(2.seconds)
        harness.run(300.milliseconds)

        val after = tap.seen.drop(before)
        val cut = after.indexOf(Cut)
        assertTrue(cut >= 0, "the seek never reached the tap as a discontinuity")
        val last = (tap.seen.take(before) + after.take(cut)).filterIsInstance<Heard>().last()
        val resumed = after.drop(cut + 1).filterIsInstance<Heard>()
        val next = assertNotNull(resumed.firstOrNull(), "nothing reached the tap after the seek")
        assertTrue(
            next.pts.micros in 2_000_000L until 2_025_000L,
            "the first block after the seek must start at the target, started at ${next.pts}",
        )
        assertTrue(next.first.sign != last.first.sign, "a sample from before the seek reached the tap after it")
        assertTrue(resumed.all { it.first.sign == next.first.sign }, "an old block arrived after the new ones")
        harness.close()
    }

    @Test
    fun `a tap that throws is detached and warned about while the sound carries on`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        var calls = 0
        val broken = object : AudioTap {
            override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
                calls++
                error("this tap is broken")
            }
        }
        val steady = RecordingTap()
        val player = playing(harness, broken, steady)
        harness.run(500.milliseconds)

        assertEquals(1, calls, "a tap that threw was called again")
        val failures = harness.events
            .filterIsInstance<PlayerEvent.Warning>()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.AudioTapFailed>()
        assertEquals(1, failures.size, "the failure must be reported exactly once: $failures")
        assertTrue("this tap is broken" in failures.single().detail, "the warning lost the tap's own message")
        assertTrue(steady.heard.size >= 10, "one broken tap starved the other")
        assertEquals(PlaybackStatus.Playing, player.state.value.status, "a broken tap stopped playback")
        harness.close()
    }

    @Test
    fun `a detached tap hears nothing more`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        val tap = RecordingTap()
        val player = playing(harness, tap)
        harness.run(300.milliseconds)
        player.detachAudioTap(tap)
        harness.run(50.milliseconds)
        val atDetach = tap.heard.size
        assertTrue(atDetach > 0, "the tap heard nothing before it was detached")

        harness.run(500.milliseconds)
        assertEquals(atDetach, tap.heard.size, "a detached tap was still handed audio")
        harness.close()
    }
}
