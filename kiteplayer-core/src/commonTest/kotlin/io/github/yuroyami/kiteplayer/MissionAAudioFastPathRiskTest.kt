package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Focused adversarial coverage for the SOL-AB-A audio-only transaction. */
class MissionAAudioFastPathRiskTest {

    private fun fixture(
        seekable: Boolean = true,
        durationUs: Long = 30_000_000,
        alternateSampleRate: Int? = null,
        alternateChannels: Int? = null,
        alternatePacketEndUs: Long? = null,
        alternateDecoderCreateDelayUs: Long = 0,
    ): MediaScript = MediaScript(
        durationUs = durationUs,
        seekable = seekable,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(
                index = ALTERNATE_AUDIO_INDEX,
                marker = ALTERNATE_AUDIO_MARKER,
                language = "jpn",
                title = "audio-B",
                sampleRate = alternateSampleRate,
                channels = alternateChannels,
                packetEndUs = alternatePacketEndUs,
                decoderCreateDelayUs = alternateDecoderCreateDelayUs,
            ),
        ),
    )

    private fun assertOnlyTrack(
        values: Set<Float>,
        expected: Float,
        label: String,
        tolerance: Float = SAMPLE_TOLERANCE,
    ) {
        assertTrue(
            values.any { abs(it - expected) <= tolerance },
            "$label never became audible: heard $values, expected $expected",
        )
        assertTrue(
            values.all { abs(it) <= tolerance || abs(it - expected) <= tolerance },
            "$label leaked another track or epoch: heard $values, expected only $expected",
        )
    }

    private suspend fun CoreHarness.selectAudio(track: TrackId?) {
        assertIs<TrackChange.Applied>(core.selectTrack(TrackKind.Audio, track))
    }

    @Test
    fun `A to B after a precise seek keeps the new epoch and leaks no A samples`() = runTest {
        val harness = CoreHarness(this, script = fixture())
        try {
            harness.openWithRenderer()
            harness.core.play()
            harness.run(400.milliseconds)

            assertIs<SeekResult.Applied>(
                harness.core.seek(Pts(3_000_000), SeekMode.Precise),
            )
            harness.run(350.milliseconds)
            val soughtEpoch = harness.core.snapshots.value.generation
            assertTrue(soughtEpoch > Generation.Initial, "the seek did not establish a new epoch")
            val source = harness.source
            val seeksAfterSeek = source.seeks

            harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
            harness.sink.audibleValues.clear()
            harness.run(350.milliseconds)

            assertEquals(soughtEpoch, harness.core.snapshots.value.generation, "the live audio swap changed epoch")
            assertEquals(seeksAfterSeek, source.seeks, "the post-seek audio swap moved the demux cursor")
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(soughtEpoch, ALTERNATE_AUDIO_MARKER),
                "post-seek audio B",
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `different decoder format reuses the one already-open sink path`() = runTest {
        val harness = CoreHarness(
            this,
            script = fixture(alternateSampleRate = 44_100, alternateChannels = 1),
        )
        try {
            harness.openWithRenderer()
            assertEquals(1, harness.sink.openCount)
            assertEquals(listOf(harness.script.format()), harness.sink.openRequests)
            harness.core.play()
            harness.run(400.milliseconds)

            harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
            harness.sink.audibleValues.clear()
            harness.run(350.milliseconds)

            assertEquals(1, harness.sink.openCount, "a format change opened a second device path")
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(harness.core.snapshots.value.generation, ALTERNATE_AUDIO_MARKER),
                "44.1 kHz mono audio B through the existing sink",
                tolerance = FORMAT_SAMPLE_TOLERANCE,
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `a paused A to B switch stays paused and starts with only B on play`() = runTest {
        val harness = CoreHarness(this, script = fixture())
        try {
            harness.openWithRenderer()
            assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
            val callbacksBefore = harness.sink.callbacks
            val startsBefore = harness.sink.startCount

            harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
            harness.run(150.milliseconds)

            assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
            assertEquals(startsBefore, harness.sink.startCount, "a paused track swap started the device")
            assertEquals(callbacksBefore, harness.sink.callbacks, "a paused track swap rendered audio")
            assertEquals(TrackId(ALTERNATE_AUDIO_INDEX), harness.core.snapshots.value.tracks.selectedAudio)

            harness.sink.audibleValues.clear()
            harness.core.play()
            harness.run(350.milliseconds)
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(harness.core.snapshots.value.generation, ALTERNATE_AUDIO_MARKER),
                "paused-switch audio B",
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `an unseekable A to B switch is live and never touches the source cursor`() = runTest {
        val harness = CoreHarness(this, script = fixture(seekable = false))
        try {
            harness.openWithRenderer()
            harness.core.play()
            harness.run(400.milliseconds)
            val source = harness.source

            harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
            harness.sink.audibleValues.clear()
            harness.run(350.milliseconds)

            assertEquals(0, source.seeks)
            assertEquals(1, harness.backend.openCalls)
            assertSame(source, harness.source)
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(harness.core.snapshots.value.generation, ALTERNATE_AUDIO_MARKER),
                "unseekable audio B",
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `disabling the only audio timeline is a typed refusal with no mutation`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, durationUs = 10_000_000),
        )
        try {
            harness.open()
            val source = harness.source

            val refusal = assertFailsWith<UnsupportedOperationException> {
                harness.core.selectTrack(TrackKind.Audio, null)
            }

            assertTrue(refusal.message?.contains("only timeline-carrying stream") == true, refusal.message)
            assertEquals(TrackId(harness.script.audioIndex), harness.core.snapshots.value.tracks.selectedAudio)
            assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
            assertEquals(1, harness.backend.openCalls)
            assertSame(source, harness.source)
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `insufficient alternate cache is refused without disturbing audio A`() = runTest {
        val harness = CoreHarness(
            this,
            script = fixture(durationUs = 60_000_000),
            config = PlayerConfig(
                buffer = BufferPolicy(
                    readyDuration = 1.seconds,
                    readyPackets = 1,
                    softTarget = 5.seconds,
                    totalBytes = 2_048,
                    totalDuration = 30.seconds,
                ),
            ),
        )
        try {
            harness.openWithRenderer()
            val source = harness.source
            val result = assertIs<TrackChange.Discarded>(
                harness.core.selectTrack(TrackKind.Audio, TrackId(ALTERNATE_AUDIO_INDEX)),
            )

            assertTrue(result.reason.contains("cached") || result.reason.contains("cache"), result.reason)
            assertEquals(TrackId(harness.script.audioIndex), harness.core.snapshots.value.tracks.selectedAudio)
            assertEquals(1, harness.backend.openCalls)
            assertEquals(0, source.seeks)
            assertSame(source, harness.source)
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `cache coverage is revalidated at the post-park commit position`() = runTest {
        val harness = CoreHarness(
            this,
            script = fixture(
                durationUs = 120_000_000,
                alternatePacketEndUs = 2_000_000,
                alternateDecoderCreateDelayUs = 1_500_000,
            ),
        )
        try {
            harness.openWithRenderer()
            harness.core.play()
            harness.run(350.milliseconds)
            val before = harness.core.position()

            val result = assertIs<TrackChange.Discarded>(
                harness.core.selectTrack(TrackKind.Audio, TrackId(ALTERNATE_AUDIO_INDEX)),
            )
            val after = harness.core.position()

            assertTrue(result.reason.contains("cached ahead"), result.reason)
            assertTrue(after > before + 1.seconds, "audio A did not advance during slow preparation: $before to $after")
            assertEquals(TrackId(harness.script.audioIndex), harness.core.snapshots.value.tracks.selectedAudio)
            assertEquals(2, harness.session.audioDecoderInstances.size)
            assertEquals(
                1,
                harness.session.audioDecoderInstances.last().closeCount,
                "the decoder rejected by commit-time revalidation was not retired",
            )

            harness.sink.audibleValues.clear()
            harness.run(250.milliseconds)
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(harness.core.snapshots.value.generation, 1f),
                "audio A after commit-time cache refusal",
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `same-pass play and audio switch wait for data and start the sink once`() = runTest {
        val harness = CoreHarness(this, script = fixture(durationUs = 8_000_000))
        try {
            harness.openWithRenderer()
            assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
            val startsBefore = harness.sink.startCount

            val play = async(start = CoroutineStart.UNDISPATCHED) { harness.core.play() }
            val switch = async(start = CoroutineStart.UNDISPATCHED) {
                harness.core.selectTrack(TrackKind.Audio, TrackId(ALTERNATE_AUDIO_INDEX))
            }
            play.await()
            assertIs<TrackChange.Applied>(switch.await())
            harness.sink.audibleValues.clear()
            harness.run(300.milliseconds)

            assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
            assertEquals(startsBefore + 1, harness.sink.startCount, "one restart pass started the sink twice")
            assertTrue(harness.sink.callbacks > 0, "the sink never started after B reached the ring")
            assertOnlyTrack(
                harness.sink.audibleValues,
                trackSample(harness.core.snapshots.value.generation, ALTERNATE_AUDIO_MARKER),
                "same-pass play and audio B",
            )
        } finally {
            harness.close()
        }
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun `repeated A B off on cycles close every decoder exactly once`() = runTest {
        val harness = CoreHarness(this, script = fixture(durationUs = 60_000_000))
        val decoderInstances = harness.run {
            openWithRenderer()
            session.audioDecoderInstances
        }
        try {
            harness.core.play()
            harness.run(400.milliseconds)

            repeat(3) {
                harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
                harness.run(80.milliseconds)
                harness.selectAudio(TrackId(harness.script.audioIndex))
                harness.run(80.milliseconds)

                harness.selectAudio(null)
                harness.sink.audibleValues.clear()
                harness.run(80.milliseconds)
                assertEquals(emptySet(), harness.sink.audibleValues, "disabled audio produced audible samples")

                harness.selectAudio(TrackId(ALTERNATE_AUDIO_INDEX))
                harness.run(80.milliseconds)
                harness.selectAudio(TrackId(harness.script.audioIndex))
                harness.run(80.milliseconds)
            }
        } finally {
            harness.close()
        }

        assertTrue(decoderInstances.size > 1, "the cycle did not construct replacement decoders")
        assertTrue(
            decoderInstances.all { it.closeCount == 1 },
            "decoder close counts were ${decoderInstances.map { it.closeCount }}",
        )
        assertEquals(1, harness.sink.openCount, "off/on cycles reopened the existing device path")
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
        assertTrue(!harness.sink.isRunning, "the sink remained running after session teardown")
    }

    @Test
    fun `play while audio is disabled does not restart the dormant sink`() = runTest {
        val harness = CoreHarness(this, script = fixture())
        try {
            harness.openWithRenderer()
            harness.core.play()
            harness.run(300.milliseconds)
            harness.selectAudio(null)
            val startsWhileEnabled = harness.sink.startCount
            val callbacksWhileEnabled = harness.sink.callbacks

            harness.core.pause()
            harness.core.play()
            harness.run(80.milliseconds)

            assertEquals(startsWhileEnabled, harness.sink.startCount, "play() restarted a sink with no audio lane")
            assertEquals(callbacksWhileEnabled, harness.sink.callbacks, "the disabled sink resumed device callbacks")
            assertTrue(!harness.sink.isRunning, "the disabled sink was left running")
        } finally {
            harness.close()
        }
    }

    private companion object {
        const val ALTERNATE_AUDIO_INDEX = 3
        const val ALTERNATE_AUDIO_MARKER = 0.25f
        const val SAMPLE_TOLERANCE = 0.0001f
        /** The resampler's reset transient rings around a constant input without approaching A's marker. */
        const val FORMAT_SAMPLE_TOLERANCE = 0.03f
    }
}
