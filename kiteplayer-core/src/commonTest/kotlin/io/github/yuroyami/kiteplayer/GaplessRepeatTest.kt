@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A repeat of the current item follows its own end the way a gapless queue item follows the one
 * before it (#467): the next pass opens in the background, its first sample follows the last one
 * in the same ring, and the status stays Playing. An A-B loop's next pass opens at A and follows
 * the current pass's last sample before B the same way. See `docs/gapless-queue.md`.
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

    /** Lets [span] pass in small steps and counts the times the position falls back by more than [drop]. */
    private suspend fun CoreHarness.wrapsOver(span: Duration, drop: Duration = 1.seconds): Int {
        var wraps = 0
        var last = core.position()
        var waited = Duration.ZERO
        while (waited < span) {
            run(5.milliseconds)
            waited += 5.milliseconds
            val now = core.position()
            if (now < last - drop) wraps++
            last = now
        }
        return wraps
    }

    /** Arms or clears the A-B loop and waits for the player to take it. */
    private suspend fun CoreHarness.setAbLoop(a: Duration?, b: Duration? = null) {
        val reply = CompletableDeferred<Unit>()
        core.post(CoreCommand.SetAbLoop(a, b, reply))
        reply.await()
    }

    /** Arms or clears the timer that stops the player. */
    private suspend fun CoreHarness.setSleepTimer(timer: SleepTimer?) {
        val reply = CompletableDeferred<Unit>()
        core.post(CoreCommand.SetSleepTimer(timer, 3.seconds, reply))
        reply.await()
    }

    private fun CoreHarness.fallbacks(): List<PlaybackWarning.GaplessFallback> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()

    private fun CoreHarness.statusesAfterPlay(): List<PlaybackStatus> =
        core.statusHistory.dropWhile { it != PlaybackStatus.Playing }

    /**
     * What a precise seek to [a] hands the device when the item then plays to its end, which is
     * what a pass that starts at A owes it too. The scripted file lays its buffers out from the
     * keyframe a seek lands on, so this is not the item's own count less the frames before A.
     */
    private suspend fun TestScope.framesFromSeekTo(script: MediaScript, a: Duration): Long {
        val harness = CoreHarness(this, script = script)
        harness.open()
        harness.core.seek(Pts(a.inWholeMicroseconds), SeekMode.Precise)
        harness.run(100.milliseconds)
        val before = harness.sink.framesPlayed
        harness.core.play()
        check(harness.runUntil(10.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        val played = harness.sink.framesPlayed - before
        harness.close()
        return played
    }

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

    @Test
    fun anAbLoopRepeatsItsSectionWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()

        assertEquals(4, harness.wrapsOver(10.seconds), "the section from 1 s to 3 s wraps at 3, 5, 7 and 9 s")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(0, harness.events.count { it is PlayerEvent.Ended }, "B is not the end of the item, as the seek back to A never said it was")
        assertEquals(emptyList(), harness.fallbacks())
        // Cleared half way through a pass: it plays on past B to the end of the item.
        harness.setAbLoop(null)
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the last pass ends")
        // 0 to 3 s, three sections of 2 s, and the last pass from 1 s to the end of the item.
        val expected = 3 * 48_000L + 3 * 2 * 48_000L + framesFromSeekTo(fourSeconds, 1.seconds)
        assertTrue(
            abs(harness.sink.framesPlayed - expected) <= 16,
            "every sample of each section was heard once, cut at A and B to within a few samples: " +
                "${harness.sink.framesPlayed} frames against $expected",
        )
        assertEquals(1, harness.events.count { it is PlayerEvent.Ended })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anAbLoopWithNoBWrapsFromTheEndToAWithNoGap() = runTest { wrapsFromTheEnd(this, b = null) }

    @Test
    fun anAbLoopWhoseBIsPastTheEndWrapsFromTheEndToAWithNoGap() = runTest { wrapsFromTheEnd(this, b = 10.seconds) }

    private suspend fun wrapsFromTheEnd(scope: TestScope, b: Duration?) {
        val harness = CoreHarness(scope, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, b)
        harness.core.play()
        assertEquals(3, harness.wrapsOver(11.seconds), "the item wraps from its end to 1 s at 4, 7 and 10 s")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(3, harness.events.count { it is PlayerEvent.Ended }, "each pass ended at the end of the item, as the seek back to A said")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aLoopedSectionOfVideoShowsNoPictureFromPastBOrBeforeA() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertEquals(2, harness.wrapsOver(6.seconds), "the section wraps at 3 and 5 s")
        val shown = harness.renderer!!.timestamps.map { it.micros }
        assertTrue(shown.none { it >= 3_000_000 }, "no picture at or after B ever showed: ${shown.filter { it >= 2_800_000 }}")
        val afterTheFirstWrap = shown.dropWhile { it < 2_800_000 }.dropWhile { it >= 2_800_000 }
        assertTrue(afterTheFirstWrap.size > 25, "the next pass's pictures present after the join: ${afterTheFirstWrap.size}")
        assertTrue(afterTheFirstWrap.all { it >= 1_000_000 }, "and none from before A: ${afterTheFirstWrap.take(5)}")
        assertEquals(1, harness.sink.openCount, "the sound followed without a gap")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekInsideTheSectionDropsThePassQuietlyAndTheNextWrapHasNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(4.seconds))
        harness.core.seek(Pts(1_500_000), SeekMode.Precise)
        harness.run(100.milliseconds)
        val stopsAfterTheSeek = harness.sink.stopCount
        assertEquals(3, harness.wrapsOver(6.seconds), "the section wraps at 1.5, 3.5 and 5.5 s after the seek")
        assertEquals(stopsAfterTheSeek, harness.sink.stopCount, "and the device never stopped for one")
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        assertEquals(emptyList(), harness.fallbacks(), "a dropped pass warns nothing")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekPastBGoesBackToAAndTheLoopGoesOnWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        harness.run(1.seconds)
        harness.core.seek(Pts(3_500_000), SeekMode.Precise)
        assertTrue(harness.runUntil(1.seconds) { harness.core.position() < 2.seconds }, "a position past B goes back to A at once")
        harness.run(200.milliseconds)
        val stopsAfterTheSeeks = harness.sink.stopCount
        assertEquals(2, harness.wrapsOver(4.5.seconds), "then the section wraps every 2 s")
        assertEquals(stopsAfterTheSeeks, harness.sink.stopCount, "with no gap")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSectionShorterThanTheRingStillRepeatsWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 1250.milliseconds)
        harness.core.play()
        assertEquals(8, harness.wrapsOver(3150.milliseconds, drop = 150.milliseconds), "a quarter second section wraps eight times from 1.25 s to 3.15 s")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertTrue(harness.statusesAfterPlay().all { it == PlaybackStatus.Playing }, "the status stayed Playing: ${harness.core.statusHistory}")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNewBTakesHoldOnThePassThatPlays() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(3.5.seconds))
        // Half a second into the second pass, B moves earlier, then later than the old one.
        harness.setAbLoop(1.seconds, 2.seconds)
        var highest = Duration.ZERO
        assertTrue(harness.runUntil(1.seconds) {
            highest = maxOf(highest, harness.core.position())
            harness.core.position() < 1500.milliseconds
        }, "the pass wraps at the new B")
        assertTrue(highest < 2100.milliseconds, "and not at the old one: $highest")
        harness.setAbLoop(1.seconds, 3500.milliseconds)
        highest = Duration.ZERO
        assertTrue(harness.runUntil(3.seconds) {
            highest = maxOf(highest, harness.core.position())
            harness.core.position() < highest - 1.seconds
        }, "the pass wraps again")
        assertTrue(highest > 3400.milliseconds && highest < 3600.milliseconds, "at the B set last: $highest")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "both wraps were joins, not seeks")
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun clearingTheLoopAfterTheNextPassTookTheRingPlaysOnFromWhereTheSoundWas() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        // The next pass takes the ring when the current one's last sample before B is written,
        // which is a ring's depth before B is heard.
        assertTrue(harness.runUntil(4.seconds) { harness.core.position() >= 2970.milliseconds }, "the first pass nears B")
        val clearedAt = harness.core.position()
        harness.setAbLoop(null)
        var lowest = 4.seconds
        var highest = Duration.ZERO
        assertTrue(harness.runUntil(3.seconds) {
            lowest = minOf(lowest, harness.core.position())
            highest = maxOf(highest, harness.core.position())
            harness.core.snapshots.value.status == PlaybackStatus.Ended
        }, "the item ends")
        assertTrue(lowest >= clearedAt - 100.milliseconds, "from where it was, without going back to A: $lowest after $clearedAt")
        assertTrue(highest >= 3900.milliseconds, "having played on past B to its end: $highest")
        assertEquals(1, harness.events.count { it is PlayerEvent.Ended })
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }
    @Test
    fun aPassStillOpeningWhenTheSoundReachesBGoesBackByTheSeekAndSaysWhy() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        // The next pass never finishes opening.
        harness.backend.openGate = CompletableDeferred()
        harness.core.play()
        var highest = Duration.ZERO
        assertTrue(harness.runUntil(4.seconds) {
            highest = maxOf(highest, harness.core.position())
            harness.core.position() < highest - 1.seconds
        }, "the section still wraps")
        assertTrue(highest < 3100.milliseconds, "at B: $highest")
        assertEquals(
            0L,
            harness.core.stats.value.audioUnderruns,
            "the sound held at B went on as soon as the pass was given up, so the device never ran dry",
        )
        val fallbacks = harness.fallbacks()
        assertEquals(1, fallbacks.size, "the late pass says why once: $fallbacks")
        assertTrue("still opening" in fallbacks.single().reason, fallbacks.single().reason)
        assertEquals(2, harness.wrapsOver(5.seconds), "and the turns after it go back by the seek")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun clearingTheLoopWhileTheSoundWaitsAtBPlaysOnWithEverySampleOnce() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.backend.openGate = CompletableDeferred()
        harness.core.play()
        // The feeder wrote the last sample before B a ring's depth before B is heard, and holds
        // the rest for a pass that is still opening.
        assertTrue(harness.runUntil(4.seconds) { harness.core.position() >= 2900.milliseconds }, "the first pass nears B")
        harness.setAbLoop(null)
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the item plays on to its end")
        assertEquals(framesOf(fourSeconds), harness.sink.framesPlayed, "every sample was heard once, the ones held at B included")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "with no gap at B")
        assertEquals(1, harness.events.count { it is PlayerEvent.Ended })
        assertEquals(emptyList(), harness.fallbacks(), "a dropped pass warns nothing")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anAudioTrackChosenWhileTheSoundWaitsAtBIsApplied() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 4_000_000,
                hasVideo = false,
                additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 2f)),
            ),
        )
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.backend.openGate = CompletableDeferred()
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.position() >= 2900.milliseconds }, "the first pass nears B")
        // The switch parks the audio workers and finds nothing still owned, the held sound included.
        val outcome = harness.core.selectTrack(TrackKind.Audio, TrackId(2))
        assertIs<TrackChange.Applied>(outcome, "the switch at B must apply, got $outcome")
        harness.backend.openGate?.complete(Unit)
        assertEquals(1, harness.wrapsOver(2.seconds), "and the section goes on wrapping")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
        assertEquals(0, harness.ledger.doubleCloseCount, "and nothing was closed twice")
    }

    @Test
    fun aSeekInsideASectionShorterThanTheRingCostsOneTurnAndTheRestFollowWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 1150.milliseconds)
        harness.core.play()
        assertTrue(harness.wrapsOver(1500.milliseconds, drop = 100.milliseconds) >= 2)
        // Too near B for a pass: that turn plays on to B and goes back by the seek.
        harness.core.seek(Pts(1_050_000), SeekMode.Precise)
        harness.run(300.milliseconds)
        val stops = harness.sink.stopCount
        // Each turn after it is given its end before its first sample is written, though the
        // ring holds more than the whole section.
        assertTrue(harness.wrapsOver(1500.milliseconds, drop = 100.milliseconds) >= 9, "a 150 ms section wraps every 150 ms")
        assertEquals(stops, harness.sink.stopCount, "each wrap after the seek's turn is a join, not a seek")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aTurnThatStartsTooNearBGoesBackByTheSeekAndTheNextOnesFollowWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        // Each pass takes 400 ms to open, which a turn from A has and one from 300 ms before B has not.
        harness.backend.openDelay = 400.milliseconds
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        harness.run(1500.milliseconds)
        val opens = harness.backend.openCalls
        harness.core.seek(Pts(2_700_000), SeekMode.Precise)
        assertTrue(harness.runUntil(1.seconds) { harness.core.position() >= 2850.milliseconds }, "the turn nears B")
        assertEquals(opens, harness.backend.openCalls, "having opened no pass, which the seek back to A would only drop")
        assertTrue(harness.runUntil(1.seconds) { harness.core.position() < 2.seconds }, "the turn goes back to A by the seek")
        harness.run(100.milliseconds)
        val stops = harness.sink.stopCount
        assertEquals(2, harness.wrapsOver(4.5.seconds), "then the section wraps every 2 s")
        assertEquals(stops, harness.sink.stopCount, "with no gap")
        assertEquals(emptyList(), harness.fallbacks(), "and no pass was started too late to follow, so none gave up")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aTimerThatStopsAtTheEndOfTheItemLetsTheSectionGoBackByTheSeek() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertEquals(1, harness.wrapsOver(3.5.seconds))
        // No pass follows an item that is to stop at its end, so the turn that plays must not
        // wait at B for one.
        harness.setSleepTimer(SleepTimer.EndOfItem)
        assertEquals(1, harness.wrapsOver(2.seconds), "the section still wraps")
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }
    @Test
    fun aSectionArmedBehindTheSoundAlreadyWrittenGoesBackByTheSeekOnce() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        // Paused 170 ms before B, with the ring already holding the sound past it.
        harness.core.seek(Pts(980_000), SeekMode.Precise)
        harness.run(200.milliseconds)
        harness.setAbLoop(1.seconds, 1150.milliseconds)
        // The pass that this turn opens is not ready before the sound already written runs out,
        // which must not count as the pass being late: the turn never waited for it.
        harness.backend.openDelay = 300.milliseconds
        val stopsAtPlay = harness.sink.stopCount
        harness.core.play()
        var highest = Duration.ZERO
        assertTrue(harness.runUntil(1.seconds) {
            highest = maxOf(highest, harness.core.position())
            harness.core.position() < highest - 100.milliseconds
        }, "the section wraps")
        assertTrue(highest < 1170.milliseconds, "at B, rather than after the sound past it that was already written: $highest")
        assertTrue(harness.sink.stopCount > stopsAtPlay, "by the seek, as a pass would have followed the sound past B")
        harness.backend.openDelay = Duration.ZERO
        harness.run(100.milliseconds)
        val stops = harness.sink.stopCount
        assertTrue(harness.wrapsOver(1500.milliseconds, drop = 100.milliseconds) >= 9, "then every 150 ms")
        assertEquals(stops, harness.sink.stopCount, "with no gap")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun withTheGaplessHandoffOffTheSectionGoesBackByTheSeek() = runTest {
        val harness = CoreHarness(this, script = fourSeconds, config = PlayerConfig(queue = QueueConfig(gapless = false)))
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertEquals(2, harness.wrapsOver(6.seconds), "the section wraps at 3 and 5 s")
        assertEquals(0, harness.backend.openCalls - 1, "and no pass was opened for it")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aBBetweenTwoBuffersStillFollowsWithNoGap() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        // 1.024 s is 48 buffers of 1024 samples at 48 kHz from A at zero, so each turn stops at the
        // start of a buffer, with none of that buffer written.
        harness.setAbLoop(Duration.ZERO, 1024.milliseconds)
        harness.core.play()
        assertEquals(4, harness.wrapsOver(4500.milliseconds, drop = 500.milliseconds), "the section wraps at 1.024, 2.048, 3.072 and 4.096 s")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across a join")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aLoopArmedBeforeAnUnseekableOpenPlaysOnThroughBWithNoPass() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000, hasVideo = false, seekable = false))
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.open()
        harness.core.play()
        var highest = Duration.ZERO
        assertTrue(harness.runUntil(6.seconds) {
            highest = maxOf(highest, harness.core.position())
            harness.core.snapshots.value.status == PlaybackStatus.Ended
        }, "the item plays to its end")
        assertTrue(highest >= 3900.milliseconds, "through B, as a source that cannot seek cannot go back to A: $highest")
        assertEquals(1, harness.backend.openCalls, "and no pass was opened for it")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekOfThePlayersOwnWhileTheSoundWaitsAtBOwnsThePosition() = runTest {
        val harness = CoreHarness(this, script = fourSeconds)
        harness.open()
        harness.setAbLoop(1.seconds, 3.seconds)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.position() >= 2600.milliseconds }, "the first pass plays")
        val external = object : ExternalClock {
            val originNanos = harness.clock.nanos()
            var startUs = harness.core.position().inWholeMicroseconds
            override fun positionAt(atNanos: Long): Duration? = (startUs + (atNanos - originNanos) / 1_000).microseconds
        }
        harness.core.setExternalClock(external)
        assertTrue(harness.runUntil(1.seconds) { harness.core.position() >= 2850.milliseconds }, "the first pass nears B, with the next pass in the ring")
        // The clock jumps back. Following it is the player's own seek, which takes the ring back
        // from the next pass, so nothing may then seek back to where the sound was.
        external.startUs -= 2_500_000
        assertTrue(harness.runUntil(1.seconds) { harness.core.position() < 1.seconds }, "the player follows the clock")
        var highest = Duration.ZERO
        repeat(140) {
            harness.run(5.milliseconds)
            highest = maxOf(highest, harness.core.position())
        }
        assertTrue(highest < 2.seconds, "and stays with it, with no seek back to B: $highest")
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
