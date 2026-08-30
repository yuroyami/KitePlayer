@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** KD-6 goldens: each profile's compiled configuration, pinned exactly. */
class PlaybackProfileTest {

    @Test
    fun scrubbingCompilesToTheSkipPairAndLateDrops() {
        val profile = PlaybackProfile.Scrubbing
        assertEquals(
            mapOf("skip_loop_filter" to "all", "skip_frame" to "nokey"),
            profile.decoderOptions,
        )
        assertFalse(profile.lowDelayDecode)
        val config = profile.applyTo(PlayerConfig())
        assertEquals(FrameDropPolicy.LateOnly, config.frameDrop)
    }

    @Test
    fun lowLatencyShrinksTheBuffersAndOpensLowDelay() {
        val profile = PlaybackProfile.LowLatency
        assertTrue(profile.decoderOptions.isEmpty())
        assertTrue(profile.lowDelayDecode)
        val config = profile.applyTo(PlayerConfig())
        assertEquals(200.milliseconds, config.buffer.readyDuration)
        assertEquals(5, config.buffer.readyPackets)
        assertEquals(1.seconds, config.buffer.softTarget)
        assertEquals(2, config.buffer.videoFrameQueue)
    }

    @Test
    fun batteryPrefersHardwareAndRelaxesReporting() {
        val profile = PlaybackProfile.Battery
        val config = profile.applyTo(PlayerConfig())
        assertEquals(HwdecPolicy.Auto, config.hardwareDecode)
        assertEquals(500.milliseconds, config.progressInterval)
        assertEquals(2.seconds, config.statsInterval)
    }

    @Test
    fun aProfilePrintsExactlyWhatItCompilesTo() {
        assertEquals(
            "PlaybackProfile(Scrubbing, decoderOptions={skip_loop_filter=all, skip_frame=nokey}, " +
                "lowDelayDecode=false)",
            PlaybackProfile.Scrubbing.toString(),
        )
    }
}
