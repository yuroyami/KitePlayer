package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/** The `PlayerConfig { }` block builds the same values the constructors do. */
class PlayerConfigBuilderTest {

    @Test
    fun `an empty block builds the defaults`() {
        assertEquals(PlayerConfig(), PlayerConfig {})
    }

    @Test
    fun `nested blocks set nested fields and keep every other default`() {
        val built = PlayerConfig {
            hdrPolicy = HdrPolicy.ToneMap
            subtitles { preferredLanguages = listOf("ja") }
            audio { replayGain = ReplayGainMode.Track }
            network { ioCache { forwardWindowBytes = 64L * 1024 * 1024 } }
            buffer { softTarget = 8.seconds }
            queue { gapless = false }
        }
        val expected = PlayerConfig(
            hdrPolicy = HdrPolicy.ToneMap,
            subtitles = SubtitleConfig(preferredLanguages = listOf("ja")),
            audio = AudioConfig(replayGain = ReplayGainMode.Track),
            network = NetworkConfig(ioCache = IoCachePolicy(forwardWindowBytes = 64L * 1024 * 1024)),
            buffer = BufferPolicy(softTarget = 8.seconds),
            queue = QueueConfig(gapless = false),
        )
        assertEquals(expected, built)
    }

    @Test
    fun `a nested block starts from what the outer block already set`() {
        val built = PlayerConfig {
            subtitles = SubtitleConfig(fontScale = 1.5f)
            subtitles { preferredLanguages = listOf("en") }
        }
        assertEquals(SubtitleConfig(fontScale = 1.5f, preferredLanguages = listOf("en")), built.subtitles)
    }

    @Test
    fun `the data classes still refuse a value they do not accept`() {
        assertFailsWith<IllegalArgumentException> {
            PlayerConfig { buffer { videoFrameQueue = 1 } }
        }
    }
}
