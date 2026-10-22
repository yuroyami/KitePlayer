package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.CorruptPackets
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.ProbeDepth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** Each typed demux setting against the exact FFmpeg options it becomes. No FFmpeg call is needed. */
class DemuxPolicyTranslationTest {

    @Test
    fun eachPolicyBecomesExactlyTheseOptions() {
        val golden = listOf(
            DemuxPolicy() to emptyMap(),
            DemuxPolicy(probe = ProbeDepth.Fast) to
                mapOf("probesize" to "524288", "analyzeduration" to "200000"),
            DemuxPolicy(probe = ProbeDepth.Thorough) to
                mapOf("probesize" to "67108864", "analyzeduration" to "20000000"),
            DemuxPolicy(probe = ProbeDepth.Custom(bytes = 1_000_000, duration = 3.seconds)) to
                mapOf("probesize" to "1000000", "analyzeduration" to "3000000"),
            DemuxPolicy(corruptPackets = CorruptPackets.Drop) to mapOf("fflags" to "+discardcorrupt"),
            DemuxPolicy(generateTimestamps = true) to mapOf("fflags" to "+genpts"),
            DemuxPolicy(lowLatency = true) to mapOf("fflags" to "+nobuffer", "max_delay" to "0"),
            DemuxPolicy(corruptPackets = CorruptPackets.Drop, generateTimestamps = true, lowLatency = true) to
                mapOf("fflags" to "+discardcorrupt+genpts+nobuffer", "max_delay" to "0"),
            DemuxPolicy(skipInitialBytes = 188) to mapOf("skip_initial_bytes" to "188"),
        )
        for ((policy, expected) in golden) {
            assertEquals(expected, policy.toFFmpegOptions(), "for $policy")
        }
    }

    @Test
    fun aDurationShorterThanOneMicrosecondStillLimitsTheProbe() {
        // FFmpeg reads an analyzeduration of 0 as its own default of five seconds.
        val policy = DemuxPolicy(probe = ProbeDepth.Custom(bytes = 4096, duration = 500.nanoseconds))
        assertEquals("1", policy.toFFmpegOptions()["analyzeduration"])
    }

    @Test
    fun aProbeSmallerThanFFmpegAcceptsIsRefusedWithATypedError() {
        val policy = DemuxPolicy(probe = ProbeDepth.Custom(bytes = 31, duration = 1.seconds))
        val refusal = assertFailsWith<PlaybackException> { policy.toFFmpegOptions() }
        val detail = assertIs<PlaybackError.ConfigurationInvalid>(refusal.error).detail
        assertTrue("32" in detail && "31" in detail, detail)
    }
}
