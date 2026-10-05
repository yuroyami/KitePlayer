@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A repeat of the current item follows its own end the way a gapless queue item follows the one
 * before it (#467): the next pass opens in the background, its first sample follows the last one
 * in the same ring, and the status stays Playing. See `docs/gapless-queue.md`.
 */
class GaplessRepeatTest {

    private val fourSeconds = MediaScript(durationUs = 4_000_000, hasVideo = false)

    /** Lets virtual time pass in small steps until [condition] holds, or [limit] has passed. */
    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    /** Lets [span] pass in small steps and counts the times the position falls back to the start. */
    private suspend fun CoreHarness.wrapsOver(span: Duration): Int {
        var wraps = 0
        var last = core.position()
        var waited = Duration.ZERO
        while (waited < span) {
            run(5.milliseconds)
            waited += 5.milliseconds
            val now = core.position()
            if (now < last - 1.seconds) wraps++
            last = now
        }
        return wraps
    }

    private fun CoreHarness.fallbacks(): List<PlaybackWarning.GaplessFallback> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()

    private fun CoreHarness.statusesAfterPlay(): List<PlaybackStatus> =
        core.statusHistory.dropWhile { it != PlaybackStatus.Playing }

    /** Frames one scripted item delivers: whole decoder buffers covering its duration. */
    private fun framesOf(script: MediaScript): Long {
        val buffers = (script.durationUs + script.audioBufferDurationUs - 1) / script.audioBufferDurationUs
        return buffers * script.audioBufferFrames
    }

    @Test
    fun aRepeatedItemFollowsItsOwnEndWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()

        assertEquals(4, harness.wrapsOver(18.seconds), "a 4 s item wraps four times in 18 s")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(4, harness.events.count { it is PlayerEvent.Ended }, "each pass ended, as a repeat that seeks says")
        assertEquals(1, harness.events.count { it is PlayerEvent.Opened }, "and the item did not open again: ${harness.events}")
        assertEquals(-1, harness.core.snapshots.value.queueIndex, "a plain open has no queue position")
        assertEquals(null, harness.core.snapshots.value.preloadedIndex, "and so no preloaded position")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun everySampleOfEachPassIsHeardAndTheLoopOffEndsTheItem() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(2, harness.wrapsOver(9.seconds))
        // The third pass is playing and the fourth is preloaded; turning the loop off drops it.
        harness.core.setLoop(LoopMode.Off)
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the third pass ends")
        assertEquals(3 * framesOf(fourSeconds), harness.sink.framesPlayed, "every sample of three passes was heard")
        assertTrue("drain" in harness.sink.calls, "the last pass drained the device as an ending item does: ${harness.sink.calls}")
        assertEquals(3, harness.events.count { it is PlayerEvent.Ended })
        assertEquals(emptyList(), harness.fallbacks(), "a dropped repeat warns nothing")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aQueueOfOneUnderLoopAllRepeatsWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.core.openQueue(listOf(MediaItem("scripted://only")), 0)
        harness.core.setLoop(LoopMode.All)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.preloadedIndex == 0 }, "the next pass of the item preloads")
        assertEquals(2, harness.wrapsOver(8.seconds))
        assertEquals(0, harness.core.snapshots.value.queueIndex, "the queue stays on its one item")
        assertEquals(1, harness.sink.openCount, "the device opened once")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aRepeatedVideoPresentsAcrossTheJoinAndRendersItsFirstFrameOnce() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(5.seconds))
        val renderer = harness.renderer!!
        val before = renderer.count
        harness.run(1.seconds)
        assertTrue(renderer.count > before + 10, "the next pass's pictures present after the join")
        assertEquals(1, harness.sink.openCount, "the sound followed without a gap")
        assertEquals(1, harness.events.count { it is PlayerEvent.FirstFrameRendered }, "the item's first frame is news once")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun theChosenTracksAndTheExternalSubtitleStayAcrossTheJoin() = runTest {
        var subtitleReads = 0
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                hasVideo = false,
                additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 0.5f, language = "fra")),
            ),
        )
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(
                    SubtitleSource(
                        uri = "memory://subs.srt",
                        io = {
                            subtitleReads++
                            RepeatSrtIo()
                        },
                    ),
                ),
            ),
        )
        val external = harness.core.snapshots.value.tracks.all.single { it.kind == TrackKind.Subtitle && it.id.value < 0 }.id
        assertTrue(harness.core.selectTrack(TrackKind.Audio, TrackId(2)) is TrackChange.Applied)
        assertTrue(harness.core.selectTrack(TrackKind.Subtitle, external) is TrackChange.Applied)
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(5.seconds))
        val tracks = harness.core.snapshots.value.tracks
        assertEquals(TrackId(2), tracks.selectedAudio, "the chosen audio track plays on")
        assertEquals(external, tracks.selectedSubtitle, "the chosen subtitle file stays selected")
        assertTrue(tracks.all.any { it.id == external }, "and listed")
        assertTrue(harness.runUntil(2.seconds) { harness.core.subtitleCues.value.isNotEmpty() }, "its cue shows on the next pass")
        assertEquals(1, subtitleReads, "the file was read once, not once a pass")
        assertEquals(1, harness.sink.openCount)
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aChosenContainerSubtitleStaysAcrossTheJoinOverTheAutomaticChoice() = runTest {
        val cue = io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
            100_000,
            3_900_000,
            listOf(io.github.yuroyami.kiteplayer.subtitle.StyledSpan("encore")),
        )
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                hasVideo = false,
                subtitleCues = listOf(cue),
                additionalSubtitleTracks = listOf(ScriptedSubtitleTrack(index = 5, cues = listOf(cue), language = "fra")),
            ),
            config = PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("eng"))),
        )
        harness.open()
        assertEquals(TrackId(harness.script.subtitleIndex), harness.core.snapshots.value.tracks.selectedSubtitle, "the automatic choice")
        assertTrue(harness.core.selectTrack(TrackKind.Subtitle, TrackId(5)) is TrackChange.Applied)
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(5.seconds))
        assertEquals(TrackId(5), harness.core.snapshots.value.tracks.selectedSubtitle, "the viewer's choice plays on")
        assertEquals(1, harness.sink.openCount)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aRecordingEndsAtTheJoinAndSaysTheItemRepeated() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000, hasVideo = false, recordable = true))
        harness.open()
        harness.core.startRecording("/tmp/kite-repeat.ts")
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(5.seconds))
        harness.run(100.milliseconds)
        val stopped = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.RecordingStopped>()
        assertEquals(listOf("the item repeated from its start"), stopped.map { it.reason })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekDuringTheRepeatPreloadDropsItQuietlyAndTheJoinStillFollows() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        harness.run(1.seconds)
        harness.core.seek(Pts(2_000_000), SeekMode.Precise)
        assertEquals(1, harness.wrapsOver(3.seconds), "the item still repeats")
        assertEquals(1, harness.sink.openCount, "and without a gap")
        assertEquals(emptyList(), harness.fallbacks(), "a dropped preload warns nothing")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aMarkerFiresOnEveryPass() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.core.setMarkers(listOf(Marker(1.seconds, "one")))
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(2, harness.wrapsOver(10.seconds))
        val reached = harness.events.filterIsInstance<PlayerEvent.MarkerReached>().map { it.marker.id }
        assertEquals(listOf("one", "one", "one"), reached, "once in each of three passes")
        assertEquals(1, harness.sink.openCount)
        harness.close()
    }

    @Test
    fun aLengthThatWasAGuessStaysTheLengthPlayedAfterTheJoin() = runTest {
        // The container guesses 3 s for an item that plays 4 s, as FFmpeg does from a bit rate (#422).
        val guessed = MediaScript(durationUs = 4_000_000, declaredDurationUs = 3_000_000, durationIsEstimate = true, hasVideo = false)
        val harness = CoreHarness(this, script = guessed)
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(4500.milliseconds))
        val duration = harness.core.snapshots.value.duration
        assertTrue(duration != null && duration >= 3900.milliseconds, "the next pass keeps the length the first one found: $duration")
        assertEquals(1, harness.sink.openCount, "the join had no gap")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun nextOnARepeatingQueueOfOneOpensTheItemAfreshWithItsSubtitleFile() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        val item = MediaItem(
            "scripted://only",
            externalSubtitles = listOf(SubtitleSource(uri = "memory://subs.srt", io = { RepeatSrtIo() })),
        )
        harness.core.openQueue(listOf(item), 0)
        harness.core.setLoop(LoopMode.All)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.preloadedIndex == 0 })
        harness.core.queueNext()
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertTrue(
            harness.core.snapshots.value.tracks.all.any { it.kind == TrackKind.Subtitle && it.id.value < 0 },
            "the reopened item lists its subtitle file: ${harness.core.snapshots.value.tracks.all}",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anItemWithAStartPositionRepeatsFromZeroWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.core.open(MediaItem("scripted://media", startPosition = 1.seconds))
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        var lowest = 4.seconds
        assertEquals(1, harness.wrapsOver(3500.milliseconds))
        harness.runUntil(500.milliseconds) {
            lowest = minOf(lowest, harness.core.position())
            false
        }
        assertTrue(lowest < 600.milliseconds, "the next pass starts at zero, as the repeat that seeks does: $lowest")
        assertEquals(1, harness.sink.openCount)
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aPassThatNoLongerFitsTheDeviceIsReleasedAndTheItemRepeatsTheOldWay() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        // The file was rewritten between passes: its second open has another sample rate.
        harness.backend.scriptFor = { _ ->
            if (harness.backend.openCalls >= 2) MediaScript(durationUs = 4_000_000, hasVideo = false, sampleRate = 44_100) else null
        }
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.fallbacks().isNotEmpty() }, "the pass that does not fit is refused")
        val fallback = harness.fallbacks().single()
        assertEquals(-1, fallback.index)
        assertTrue("44100 Hz" in fallback.reason, fallback.reason)
        harness.run(100.milliseconds)
        assertTrue(harness.backend.sessions[1].scriptedSource.closed, "and released at once rather than held to the end")
        assertEquals(1, harness.wrapsOver(4.seconds), "the item still repeats, by seeking back")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aRepeatedItemWithNoAudioSeeksBackAndSaysWhy() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000, hasAudio = false))
        harness.openWithRenderer()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(3.seconds), "the old path still repeats the item")
        val fallback = harness.fallbacks().single()
        assertEquals(-1, fallback.index, "a plain open has no queue position")
        assertTrue("current item has no selected audio track" in fallback.reason, fallback.reason)
        assertTrue(PlaybackStatus.Buffering in harness.statusesAfterPlay(), "by seeking back: ${harness.core.statusHistory}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }
}

private const val REPEAT_SRT = "1\n00:00:00,100 --> 00:00:03,900\nAgain\n\n"

/** Serves one fixed SRT file whose cue covers almost the whole item. */
private class RepeatSrtIo : MediaIo {
    private val bytes = REPEAT_SRT.encodeToByteArray()
    override val size: Long = bytes.size.toLong()
    override val seekable: Boolean = true
    private var at = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) return -1
        val n = minOf(length, bytes.size - at)
        bytes.copyInto(into, offset, at, at + n)
        at += n
        return n
    }

    override suspend fun seek(position: Long) {
        at = position.toInt()
    }

    override fun close() = Unit
}
