package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The crossfade between queue items (#434): two 20 second tones, 440 Hz and then 660 Hz, whose
 * every sample the scripted device keeps. See the Crossfade section of `docs/gapless-queue.md`.
 *
 * The tones are at half scale. Two full scale ones would sum past full scale in the middle of the
 * fade, and the ring's limiter (#504) would turn them down there, as it should.
 */
class CrossfadeTest {

    private val level = 0.5
    private val low = MediaScript(durationUs = 20_000_000, hasVideo = false, audioToneHz = 440.0, audioMarker = 0.5f)
    private val high = MediaScript(durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f)
    private val items = listOf(MediaItem("scripted://low"), MediaItem("scripted://high"))
    private val rate = 48_000

    /** A harness that plays [low] then [high], keeping every sample the device plays. */
    private fun TestScope.queue(crossfade: Duration, highScript: MediaScript = high): CoreHarness {
        val harness = CoreHarness(this, script = low, config = PlayerConfig(queue = QueueConfig(crossfade = crossfade)))
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("high")) highScript else low }
        harness.sink.recordsSamples = true
        return harness
    }

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(10.milliseconds)
            waited += 10.milliseconds
        }
        return true
    }

    private suspend fun CoreHarness.playToTheEnd(items: List<MediaItem> = this@CrossfadeTest.items) {
        core.openQueue(items, 0)
        core.play()
        assertTrue(runUntil(60.seconds) { core.snapshots.value.status == PlaybackStatus.Ended }, "the queue did not end")
    }

    /** The heard position of [seconds] into the stream, as an index of the recorded samples. */
    private fun at(seconds: Double): Int = (seconds * rate).toInt()

    /**
     * A scripted item's sound at its frame [index], as its decoder makes it: each buffer's phase
     * from its time. The buffers run from [originUs], where a seek into the item landed.
     */
    private fun tone(hz: Double, index: Int, script: MediaScript = low, originUs: Long = 0): Double {
        val buffer = index / script.audioBufferFrames
        val within = index % script.audioBufferFrames
        val start = (originUs + buffer * script.audioBufferDurationUs) / 1_000_000.0
        return level * sin(2 * PI * hz * (start + within.toDouble() / rate))
    }

    /**
     * The frame of a scripted item at [us] of its time, as the engine places it: each decoder
     * buffer's time is a whole number of microseconds, so a frame index from it is a few frames
     * off the exact one.
     */
    private fun frameAt(us: Long, script: MediaScript = low, originUs: Long = 0): Int {
        var buffer = (us - originUs) / script.audioBufferDurationUs
        if ((buffer + 1) * script.audioBufferDurationUs <= us - originUs) buffer++
        val within = (us - originUs - buffer * script.audioBufferDurationUs) * rate / 1_000_000L
        return (buffer * script.audioBufferFrames + within).toInt()
    }

    /**
     * The sound frames of a scripted item of [script] from [originUs]: whole decoder buffers, so a
     * little past its length.
     */
    private fun framesOf(script: MediaScript, originUs: Long = 0): Int {
        val buffers = (script.durationUs - originUs + script.audioBufferDurationUs - 1) / script.audioBufferDurationUs
        return (buffers * script.audioBufferFrames).toInt()
    }

    /** The amplitude of the [hz] tone in [samples] from [from] for [count] samples. */
    private fun amplitude(samples: FloatArray, hz: Double, from: Int, count: Int): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until from + count) {
            val angle = 2 * PI * hz * i / rate
            re += samples[i] * cos(angle)
            im += samples[i] * sin(angle)
        }
        return 2 * sqrt(re * re + im * im) / count
    }

    private fun rms(samples: FloatArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }

    @Test
    fun aCrossfadeMixesTheTwoItemsWithEqualPowerCurvesSampleForSample() = runTest {
        val harness = queue(5.seconds)
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        val fadeFrames = 5 * rate
        // Five seconds before the stated length of 20.
        val fadeStart = frameAt(15_000_000)
        // The first item's sound, the five seconds of both, then the rest of the second.
        assertEquals(framesOf(low) + framesOf(high) - (framesOf(low) - fadeStart), heard.size, "the queue lasts 35 seconds")
        var worst = 0.0
        var worstAt = -1
        for (i in heard.indices) {
            val expected = when {
                i < fadeStart -> tone(440.0, i)
                i < framesOf(low) -> {
                    val k = i - fadeStart
                    val x = (k.toDouble() / fadeFrames).coerceAtMost(1.0)
                    tone(440.0, i) * (if (x >= 1.0) 0.0 else cos(x * PI / 2)) + tone(660.0, k, high) * (if (x >= 1.0) 1.0 else sin(x * PI / 2))
                }
                else -> tone(660.0, i - fadeStart, high)
            }
            val difference = abs(expected - heard[i])
            if (difference > worst) {
                worst = difference
                worstAt = i
            }
        }
        assertTrue(worst < 1e-5, "the output left the model by $worst at ${worstAt.toDouble() / rate} s")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    /** The largest difference between [heard] and [model], with where it is, as a message. */
    private fun worstAgainst(heard: FloatArray, model: (Int) -> Double): Pair<Double, String> {
        var worst = 0.0
        var worstAt = -1
        for (i in heard.indices) {
            val difference = abs(model(i) - heard[i])
            if (difference > worst) {
                worst = difference
                worstAt = i
            }
        }
        return worst to "the output left the model by $worst at ${worstAt.toDouble() / rate} s"
    }

    @Test
    fun aFirstItemThatEndsBeforeItsStatedLengthLetsTheSecondFinishFadingIn() = runTest {
        // It says 20 seconds and has sound for 19.5, so the fade is nine tenths done when it ends.
        val early = MediaScript(durationUs = 19_500_000, declaredDurationUs = 20_000_000, hasVideo = false, audioToneHz = 440.0, audioMarker = 0.5f)
        val harness = CoreHarness(this, script = early, config = PlayerConfig(queue = QueueConfig(crossfade = 5.seconds)))
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("high")) high else early }
        harness.sink.recordsSamples = true
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        val fadeFrames = 5 * rate
        val fadeStart = frameAt(15_000_000, early)
        val firstEnds = framesOf(early)
        assertEquals(firstEnds + framesOf(high) - (firstEnds - fadeStart), heard.size)
        val (worst, where) = worstAgainst(heard) { i ->
            val k = i - fadeStart
            val x = (k.toDouble() / fadeFrames).coerceAtMost(1.0)
            when {
                i < fadeStart -> tone(440.0, i, early)
                i < firstEnds -> tone(440.0, i, early) * cos(x * PI / 2) + tone(660.0, k, high) * sin(x * PI / 2)
                else -> tone(660.0, k, high) * sin(x * PI / 2)
            }
        }
        assertTrue(worst < 1e-5, where)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun eachItemKeepsItsOwnReplayGainThroughTheFade() = runTest {
        // The first item is tagged 6 dB down, the second carries no tag and plays at its level.
        val quiet = MediaScript(
            durationUs = 20_000_000, hasVideo = false, audioToneHz = 440.0, audioMarker = 0.5f,
            audioMetadata = mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.0206 dB"),
        )
        val config = PlayerConfig(audio = AudioConfig(replayGain = ReplayGainMode.Track), queue = QueueConfig(crossfade = 5.seconds))
        val harness = CoreHarness(this, script = quiet, config = config)
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("high")) high else quiet }
        harness.sink.recordsSamples = true
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        val window = rate / 10
        // Before, in the middle of and after the fade, each tone at its own gain.
        assertTrue(abs(amplitude(heard, 440.0, at(14.0), window) - 0.25) < 0.01, "the first item at half its level")
        val middle = at(17.45)
        assertTrue(abs(amplitude(heard, 440.0, middle, window) - 0.25 * 0.7071) < 0.01, "the first item's share of the fade")
        assertTrue(abs(amplitude(heard, 660.0, middle, window) - 0.5 * 0.7071) < 0.01, "the second item's share is at its own gain")
        assertTrue(abs(amplitude(heard, 660.0, at(22.0), window) - 0.5) < 0.01, "the second item at its level")
        harness.close()
    }

    @Test
    fun bothPitchesSoundThroughTheFadeAndTheLoudnessHolds() = runTest {
        val harness = queue(5.seconds)
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        val window = rate / 10
        // Halfway through the fade each sounds at cos(pi/4) of its level.
        val middle = at(17.45)
        val low = amplitude(heard, 440.0, middle, window)
        val high = amplitude(heard, 660.0, middle, window)
        assertTrue(abs(low - 0.7071 * level) < 0.01, "440 Hz in the middle of the fade: $low")
        assertTrue(abs(high - 0.7071 * level) < 0.01, "660 Hz in the middle of the fade: $high")
        assertTrue(amplitude(heard, 660.0, at(14.0), window) < 0.01, "the second item before the fade")
        assertTrue(amplitude(heard, 440.0, at(21.0), window) < 0.01, "the first item after the fade")
        // A sine is 0.707 of its level in RMS, and an equal power fade of two unrelated ones stays there.
        var position = at(14.0)
        while (position < at(21.0)) {
            val loudness = rms(heard, position, window)
            assertTrue(abs(loudness - 0.7071 * level) < 0.015, "the loudness at ${position.toDouble() / rate} s was $loudness")
            position += window
        }
        harness.close()
    }

    @Test
    fun theNextItemBecomesCurrentAtTheEndOfTheFadeFiveSecondsIn() = runTest {
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        var lastFirstItemPosition = Duration.ZERO
        assertTrue(
            harness.runUntil(30.seconds) {
                if (harness.core.snapshots.value.queueIndex == 0) lastFirstItemPosition = harness.core.position()
                harness.core.snapshots.value.queueIndex == 1
            },
        )
        assertTrue(lastFirstItemPosition > 19.9.seconds, "the first item stayed current to its end: $lastFirstItemPosition")
        val position = harness.core.position()
        assertTrue(position in 4.9.seconds..5.2.seconds, "the second item's position is the fade's length in: $position")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one device, never stopped")
        val events = harness.events.filter { it is PlayerEvent.Ended || it is PlayerEvent.Opened }
        assertTrue(events.size == 3 && events[1] is PlayerEvent.Ended, "opened, ended, opened: $events")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun withTheCrossfadeOffTheDeviceHearsBothItemsBackToBack() = runTest {
        val harness = queue(Duration.ZERO)
        harness.playToTheEnd()
        assertBackToBack(harness.sink.recorded.toFloatArray())
        harness.close()
    }

    /** The first item's every sample, then the second's: today's gapless join. */
    private fun assertBackToBack(heard: FloatArray) {
        val first = framesOf(low)
        assertEquals(first + framesOf(high), heard.size, "every sample of both items, and no more")
        var worst = 0.0
        for (i in heard.indices) {
            val expected = if (i < first) tone(440.0, i) else tone(660.0, i - first, high)
            worst = maxOf(worst, abs(expected - heard[i]))
        }
        assertTrue(worst < 1e-5, "the output left the gapless join by $worst")
    }

    @Test
    fun anItemThatRunsIntoTheNextJoinsGapless() = runTest {
        val harness = queue(5.seconds)
        harness.playToTheEnd(listOf(items[0].copy(runsIntoNext = true), items[1]))
        assertBackToBack(harness.sink.recorded.toFloatArray())
        harness.close()
    }

    @Test
    fun aNextItemWithAPictureJoinsGapless() = runTest {
        val pictured = MediaScript(durationUs = 20_000_000, hasVideo = true, audioToneHz = 660.0)
        val harness = queue(5.seconds, highScript = pictured)
        harness.attachRenderer()
        harness.playToTheEnd()
        assertEquals(framesOf(low) + framesOf(pictured), harness.sink.recorded.size, "no sample was overlapped")
        harness.close()
    }

    @Test
    fun aShortItemFadesForHalfItsLength() = runTest {
        val short = MediaScript(durationUs = 4_000_000, hasVideo = false, audioToneHz = 440.0, audioMarker = 0.5f)
        val harness = CoreHarness(this, script = short, config = PlayerConfig(queue = QueueConfig(crossfade = 5.seconds)))
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("high")) high else short }
        harness.sink.recordsSamples = true
        harness.playToTheEnd()
        // A first item of 4 seconds fades into a second of 20 for 2.
        assertEquals(framesOf(short) + framesOf(high) - (framesOf(short) - frameAt(2_000_000, short)), harness.sink.recorded.size)
        harness.close()
    }

    @Test
    fun aShortNextItemFadesInForHalfItsLength() = runTest {
        val short = MediaScript(durationUs = 4_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f)
        val harness = queue(5.seconds, highScript = short)
        harness.playToTheEnd()
        // A first item of 20 seconds fades into a second of 4 for 2.
        assertEquals(framesOf(low) + framesOf(short) - (framesOf(low) - frameAt(18_000_000)), harness.sink.recorded.size)
        harness.close()
    }

    @Test
    fun aSeekDuringAFadeEndsItAndTheItemPlaysAlone() = runTest {
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.position() >= 17.seconds })
        KitePlayer(harness.core).seek(5.seconds, SeekMode.Precise)
        val from = harness.sink.recorded.size
        harness.run(1.seconds)
        val heard = harness.sink.recorded.toFloatArray()
        val window = rate / 10
        val start = from + rate / 2
        assertTrue(amplitude(heard, 660.0, start, window) < 0.01, "the second item's share went with the seek")
        assertTrue(amplitude(heard, 440.0, start, window) > 0.2, "the first item plays on, unfaded")
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        // And fades again at its end, into a fresh preload.
        assertTrue(harness.runUntil(30.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun nextDuringAFadeOpensTheNextItemFromItsStart() = runTest {
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.position() >= 17.seconds })
        KitePlayer(harness.core).next()
        harness.run(200.milliseconds)
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        val position = harness.core.position()
        assertTrue(position < 1.seconds, "the second item started from its start: $position")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aQueueEditDuringAFadeLetsTheFirstItemFadeOutAlone() = runTest {
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.position() >= 16.seconds })
        harness.core.addToQueue(listOf(MediaItem("scripted://later")))
        val from = harness.sink.recorded.size
        harness.run(1.seconds)
        val heard = harness.sink.recorded.toFloatArray()
        val window = rate / 10
        // Past what the ring held when the share was taken back.
        val start = from + rate / 2
        assertTrue(amplitude(heard, 660.0, start, window) < 0.01, "the second item's share stopped")
        val fading = amplitude(heard, 440.0, start, window)
        assertTrue(fading in 0.1 * level..0.95 * level, "the first item goes on fading out: $fading")
        assertTrue(harness.runUntil(30.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue moves on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aPauseDuringAFadeKeepsIt() = runTest {
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.position() >= 17.seconds })
        harness.core.pause()
        harness.run(2.seconds)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended })
        assertEquals(framesOf(low) + framesOf(high) - (framesOf(low) - frameAt(15_000_000)), harness.sink.recorded.size, "the fade went on after the pause")
        harness.close()
    }

    @Test
    fun aSpeedChangeDuringAFadeLetsTheFirstItemFadeOutAlone() = runTest {
        // A speed change drops a preload, so it ends the fade the way a queue edit does.
        val harness = queue(5.seconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.position() >= 16.seconds })
        harness.core.setSpeed(1.5)
        assertTrue(harness.runUntil(30.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue moves on")
        val fallbacks = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()
        assertEquals(emptyList(), fallbacks)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemNotReadyInTimeJoinsGapless() = runTest {
        // The second item's decoder takes long to create, so it is not primed when the fade is due.
        val slow = MediaScript(durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioDecoderCreateDelayUs = 3_500_000)
        val harness = queue(5.seconds, highScript = slow)
        harness.playToTheEnd()
        assertEquals(framesOf(low) + framesOf(slow), harness.sink.recorded.size, "no sample was overlapped")
        harness.close()
    }

    @Test
    fun aNextItemThatOpensInTimeButFillsLateJoinsGapless() = runTest {
        // The second item opens at once, and its first reads are slow, so its queues are not
        // ready when the fade is due five seconds before the end; they are two seconds later.
        val late = MediaScript(
            durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f,
            slowStartReadUs = 100_000, slowStartReads = 40,
        )
        val harness = queue(5.seconds, highScript = late)
        harness.playToTheEnd()
        assertEquals(framesOf(low) + framesOf(late), harness.sink.recorded.size, "no sample was overlapped")
        harness.close()
    }

    @Test
    fun aNextItemReadyJustBeforeTheStartFadesFromTheFirstSampleNotYetWrittenToTheEnd() = runTest {
        // Ready about 100 ms before the fade is heard, when the first item's sound up to its start
        // is already written ahead of what is heard. Found by trying slow reads from 57 to 61 ms.
        val late = MediaScript(
            durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f,
            slowStartReadUs = 59_000, slowStartReads = 40,
        )
        val harness = queue(5.seconds, highScript = late)
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        // The second item is heard whole, so where it starts says where the fade started.
        val fadeStart = heard.size - framesOf(late)
        assertTrue(fadeStart > frameAt(15_000_000), "the fade started late, at ${fadeStart.toDouble() / rate} s")
        assertEquals(0, fadeStart % low.audioBufferFrames, "at the first buffer not yet written")
        // It still ends at the first item's end, so it is that much shorter.
        val startUs = fadeStart / low.audioBufferFrames * low.audioBufferDurationUs
        val fadeFrames = ((20_000_000 - startUs) * rate / 1_000_000L).toInt()
        val (worst, where) = worstAgainst(heard) { i ->
            val k = i - fadeStart
            val x = (k.toDouble() / fadeFrames).coerceAtMost(1.0)
            when {
                i < fadeStart -> tone(440.0, i)
                i < framesOf(low) -> tone(440.0, i) * cos(x * PI / 2) + tone(660.0, k, late) * sin(x * PI / 2)
                else -> tone(660.0, k, late)
            }
        }
        assertTrue(worst < 1e-5, where)
        harness.close()
    }

    @Test
    fun aNextItemWithAStartPositionFadesInFromIt() = runTest {
        val harness = queue(5.seconds)
        // Not on a buffer's edge, so the trim cuts inside one.
        val second = MediaItem("scripted://high", startPosition = 3_010.milliseconds)
        harness.playToTheEnd(listOf(items[0], second))
        val heard = harness.sink.recorded.toFloatArray()
        val fadeFrames = 5 * rate
        val fadeStart = frameAt(15_000_000)
        // The second item's buffers run from where its start's seek landed.
        val landing = high.keyframeAtOrBefore(3_010_000)
        val skipped = frameAt(3_010_000, high, landing)
        assertEquals(fadeStart + framesOf(high, landing) - skipped, heard.size)
        val (worst, where) = worstAgainst(heard) { i ->
            val k = i - fadeStart
            val x = (k.toDouble() / fadeFrames).coerceAtMost(1.0)
            when {
                i < fadeStart -> tone(440.0, i)
                i < framesOf(low) -> tone(440.0, i) * cos(x * PI / 2) + tone(660.0, skipped + k, high, landing) * sin(x * PI / 2)
                else -> tone(660.0, skipped + k, high, landing)
            }
        }
        assertTrue(worst < 1e-5, where)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemOfAnotherSampleRateJoinsWithoutAFade() = runTest {
        val other = MediaScript(durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f, sampleRate = 44_100)
        val harness = queue(5.seconds, highScript = other)
        harness.playToTheEnd()
        val heard = harness.sink.recorded.toFloatArray()
        val first = heard.copyOfRange(0, framesOf(low))
        val (worst, where) = worstAgainst(first) { i -> tone(440.0, i) }
        assertTrue(worst < 1e-5, "the first item is heard whole and unfaded: $where")
        harness.close()
    }

    @Test
    fun anItemSwitchedToATrackOfAnotherRateJoinsWithoutAFade() = runTest {
        // The switch keeps the device, which was opened for the first track's 48 kHz, so the next
        // item at 48 kHz fits the handoff; the mix comes before the rate conversion and cannot.
        val switched = MediaScript(
            durationUs = 20_000_000, hasVideo = false, audioMarker = 0.5f,
            additionalAudioTracks = listOf(ScriptedAudioTrack(index = 1, marker = 0.25f, sampleRate = 44_100)),
        )
        val harness = CoreHarness(this, script = switched, config = PlayerConfig(queue = QueueConfig(crossfade = 5.seconds)))
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("high")) high else switched }
        harness.sink.recordsSamples = true
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(10.seconds) { harness.core.position() >= 2.seconds })
        assertEquals(TrackChange.Applied(TrackKind.Audio, TrackId(1)), KitePlayer(harness.core).selectTrack(TrackKind.Audio, TrackId(1)))
        assertTrue(harness.runUntil(60.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the queue did not end")
        val heard = harness.sink.recorded.toFloatArray()
        val window = rate / 10
        val steady = rms(heard, at(10.0), window)
        assertTrue(abs(rms(heard, at(19.5), window) - steady) < 0.02, "the first item is unfaded near its end: ${rms(heard, at(19.5), window)} against $steady")
        harness.close()
    }

    @Test
    fun aNextItemThatReadsSlowerThanItPlaysStillTakesOverAtTheEndOfTheFade() = runTest {
        // Ready before the fade, then drained by it faster than it reads, so its queues are
        // empty when the first item ends. Opening it again would replay the start the fade used.
        val slow = MediaScript(durationUs = 20_000_000, hasVideo = false, audioToneHz = 660.0, audioMarker = 0.5f, readDelayUs = 60_000)
        val harness = queue(5.seconds, highScript = slow)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(30.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the second item became current")
        val fallbacks = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()
        assertEquals(emptyList(), fallbacks, "the fade handed the ring over")
        harness.close()
    }

    @Test
    fun aCrossfadeLongerThanThirtySecondsIsRefused() {
        assertFailsWith<IllegalArgumentException> { QueueConfig(crossfade = 31.seconds) }
        assertFailsWith<IllegalArgumentException> { QueueConfig(crossfade = (-1).seconds) }
        assertEquals(30.seconds, QueueConfig(crossfade = 30.seconds).crossfade)
    }
}
