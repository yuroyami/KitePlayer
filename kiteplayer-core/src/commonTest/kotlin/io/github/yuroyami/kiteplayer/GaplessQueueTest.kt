@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gapless handoff between queue items: the next item opens in the background, its sound
 * follows the last sample of the current item on the same device, and every case that cannot do
 * that falls back to the old path with a typed warning. See `docs/gapless-queue.md`.
 */
class GaplessQueueTest {

    private val threeSeconds = MediaScript(durationUs = 3_000_000, hasVideo = false)
    private val items = listOf(MediaItem("scripted://first"), MediaItem("scripted://second"))

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

    private fun CoreHarness.fallbacks(): List<PlaybackWarning.GaplessFallback> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>()

    @Test
    fun twoItemsShareOneDeviceOpenWithNoStopBetweenThem() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.preloadedIndex == 1 }, "the second item preloads")
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue moved on")

        val position = harness.core.position()
        assertTrue(position < 30.milliseconds, "the second item's position starts within one buffer of zero: $position")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertEquals(null, harness.core.snapshots.value.preloadedIndex, "the preload became the current item")
        assertEquals(listOf("open", "resume", "start"), harness.sink.calls, "one open and one start, no stop, pause or drain")
        assertEquals(0L, harness.core.stats.value.audioUnderruns, "the device never ran dry across the join")
        assertEquals(2, harness.backend.openCalls, "each item opened its source once")

        val events = harness.events.filter { it is PlayerEvent.Ended || it is PlayerEvent.Opened }
        assertEquals(3, events.size, "opened, ended, opened: $events")
        assertTrue(events[1] is PlayerEvent.Ended, "the first item ended before the second opened: $events")
        assertEquals("scripted://second", (events[2] as PlayerEvent.Opened).media.uri)
        assertFalse(PlaybackStatus.Opening in harness.core.statusHistory.drop(2), "no second open: ${harness.core.statusHistory}")

        // Both items play to their last sample: every sample of each item reaches the device.
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the queue ends")
        val frames = 2 * framesOf(threeSeconds)
        assertEquals(frames, harness.sink.framesPlayed, "every sample of both items was heard")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun theFirstItemEndsOnTimeAndTheSecondStartsAtItsFirstSample() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        var lastFirstItemPosition = Duration.ZERO
        assertTrue(
            harness.runUntil(4.seconds) {
                val snapshot = harness.core.snapshots.value
                if (snapshot.queueIndex == 0) lastFirstItemPosition = harness.core.position()
                snapshot.queueIndex == 1
            },
        )
        // The scripted item's sound is whole decoder buffers, so it ends a little after its duration.
        val soundEnds = (framesOf(threeSeconds) * 1_000_000L / threeSeconds.sampleRate).microseconds
        assertTrue(lastFirstItemPosition <= soundEnds, "the first item never reads past its end: $lastFirstItemPosition")
        assertTrue(lastFirstItemPosition > 2.9.seconds, "the first item played to its end: $lastFirstItemPosition")
        harness.run(500.milliseconds)
        val position = harness.core.position()
        assertTrue(position in 450.milliseconds..550.milliseconds, "the second item's clock runs from its start: $position")
        harness.close()
    }

    @Test
    fun aVideoQueueSwapsThePictureAtTheJoin() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        val renderer = assertNotNull(harness.renderer)
        val before = renderer.count
        harness.run(1.seconds)
        assertTrue(renderer.count > before + 10, "the second item's pictures present after the swap")
        assertEquals(1, harness.sink.openCount, "the device opened once")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun withGaplessOffTheDeviceStopsAndTheNextItemOpensFromScratch() = runTest {
        val harness = CoreHarness(this, script = threeSeconds, config = PlayerConfig(queue = QueueConfig(gapless = false)))
        harness.core.openQueue(items, 0)
        harness.core.play()
        var preloaded = false
        assertTrue(
            harness.runUntil(5.seconds) {
                if (harness.core.snapshots.value.preloadedIndex != null) preloaded = true
                harness.core.snapshots.value.queueIndex == 1 && harness.core.snapshots.value.status == PlaybackStatus.Playing
            },
        )
        assertFalse(preloaded, "nothing preloads with gapless off")
        assertEquals(2, harness.sink.openCount, "each item opened the device")
        assertTrue("drain" in harness.sink.calls, "the first item drained the device: ${harness.sink.calls}")
        val afterFirstPlay = harness.core.statusHistory.dropWhile { it != PlaybackStatus.Playing }
        assertTrue(PlaybackStatus.Ended in afterFirstPlay && PlaybackStatus.Opening in afterFirstPlay, "the old path: ${harness.core.statusHistory}")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
    }

    @Test
    fun aNextItemWithAnotherSampleRateFallsBackAndSaysSo() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.backend.scriptFor = { item ->
            if (item.uri.endsWith("second")) MediaScript(durationUs = 3_000_000, hasVideo = false, sampleRate = 44_100) else null
        }
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        val fallback = harness.fallbacks().single()
        assertEquals(1, fallback.index)
        assertTrue("44100 Hz" in fallback.reason, fallback.reason)
        assertTrue(harness.runUntil(1.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Playing }, "the old path plays the item")
        assertEquals(2, harness.sink.openCount, "the old path opened the device again, for the new rate")
        assertEquals(2, harness.backend.openCalls, "and played the preloaded item without opening it again")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemThatFailsToOpenFallsBackAndTheOldPathReportsTheFailure() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.backend.openFailureFor = { item -> if (item.uri.endsWith("second")) IllegalStateException("no such file") else null }
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Failed })
        val fallback = harness.fallbacks().single()
        assertTrue("did not open" in fallback.reason && "no such file" in fallback.reason, fallback.reason)
        harness.close()
    }

    @Test
    fun aPreloadStillOpeningWhenTheSoundRunsOutFallsBack() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        // Every later open waits, the preload included, until the gate opens.
        val gate = CompletableDeferred<Unit>()
        harness.backend.openGate = gate
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.fallbacks().isNotEmpty() })
        assertTrue("still opening" in harness.fallbacks().single().reason, harness.fallbacks().single().reason)
        gate.complete(Unit)
        assertTrue(harness.runUntil(2.seconds) { harness.core.snapshots.value.queueIndex == 1 && harness.core.snapshots.value.status == PlaybackStatus.Playing })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemWithNoAudioFallsBack() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.backend.scriptFor = { item ->
            if (item.uri.endsWith("second")) MediaScript(durationUs = 3_000_000, hasAudio = false) else null
        }
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        assertTrue("no selected audio track" in harness.fallbacks().single().reason, harness.fallbacks().single().reason)
        assertEquals(2, harness.backend.openCalls, "the old path played the preloaded item without opening it again")
        harness.close()
    }

    @Test
    fun itemsWithNoAudioJoinByTheirPictures() = runTest {
        val silent = MediaScript(durationUs = 3_000_000, hasAudio = false)
        val harness = CoreHarness(this, script = silent)
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(8.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Ended }, "the queue ends")

        assertEquals(2, harness.backend.openCalls, "each item opened its source once")
        assertEquals(emptyList(), harness.fallbacks())
        val afterPlay = harness.core.statusHistory.dropWhile { it != PlaybackStatus.Playing }
        assertEquals(listOf(PlaybackStatus.Playing, PlaybackStatus.Ended), afterPlay, "no Buffering at the join")
        val renderer = assertNotNull(harness.renderer)
        val whole = List(75) { it * 40L }
        assertEquals(whole + whole, renderer.timestamps.map { it.micros / 1_000 }, "every picture of both items, once each")
        // The open shows the first picture and play starts from it, so the cadence is read after it.
        val steps = renderer.targets.zipWithNext { a, b -> b - a }.drop(1)
        assertEquals(List(steps.size) { 40_000_000L }, steps, "one frame period between pictures, across the join too")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun anItemWithNoAudioFollowedByOneWithSoundFallsBack() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, hasAudio = false))
        harness.backend.scriptFor = { item -> if (item.uri.endsWith("second")) threeSeconds else null }
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        val fallback = harness.fallbacks().single()
        assertTrue("current item has no selected audio track" in fallback.reason, fallback.reason)
        assertEquals(2, harness.backend.openCalls, "the old path played the preloaded item without opening it again")
        harness.close()
    }

    @Test
    fun aPreloadStillOpeningWhenThePicturesRunOutFallsBack() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, hasAudio = false))
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        val gate = CompletableDeferred<Unit>()
        harness.backend.openGate = gate
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.fallbacks().isNotEmpty() })
        assertTrue("ran out of pictures" in harness.fallbacks().single().reason, harness.fallbacks().single().reason)
        gate.complete(Unit)
        assertTrue(harness.runUntil(2.seconds) { harness.core.snapshots.value.queueIndex == 1 && harness.core.snapshots.value.status == PlaybackStatus.Playing })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemWithAStartPositionFallsBack() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(listOf(items[0], MediaItem("scripted://second", startPosition = 1.seconds)), 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        assertTrue("start position" in harness.fallbacks().single().reason, harness.fallbacks().single().reason)
        harness.close()
    }

    @Test
    fun aRendererThatDecodesItsOwnVideoGetsTheNextDecoderAtTheSwap() = runTest {
        var harness: CoreHarness? = null
        var created = 0
        var open = 0
        var mostOpen = 0
        val ownDecoders = object : VideoDecoderFactory {
            override val name: String = "renderer decoder"
            override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? {
                // The backend's scripted decoder, standing in for one that draws into the
                // renderer's surface, which only one decoder at a time can hold.
                val inner = harness!!.backend.sessions.last().videoDecoders.first().create(stream, hwdec) ?: return null
                created++
                open++
                mostOpen = maxOf(mostOpen, open)
                return object : VideoDecoder by inner {
                    override fun close() {
                        open--
                        inner.close()
                    }
                }
            }
        }
        val renderer = RecordingRenderer(decoderFactories = listOf(ownDecoders))
        val started = CoreHarness(this, script = MediaScript(durationUs = 3_000_000), renderer = renderer)
        harness = started
        started.attachRenderer()
        started.core.openQueue(items, 0)
        started.core.play()
        assertTrue(started.runUntil(5.seconds) { started.core.snapshots.value.queueIndex == 1 })
        val before = renderer.count
        started.run(300.milliseconds)
        assertTrue(renderer.count > before, "the second item's pictures present within 300 ms of the swap")
        assertEquals(2, created, "the renderer's factory made one decoder for each item")
        assertEquals(1, mostOpen, "and never while the other item's decoder was open")
        assertEquals(1, started.sink.openCount, "the sound followed without a gap")
        assertEquals(emptyList(), started.fallbacks())
        started.close()
        assertEquals(0, started.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aNextItemWhoseVideoNoDecoderTakesAtTheSwapPlaysOnWithoutIt() = runTest {
        val faults = FaultPlan()
        var harness: CoreHarness? = null
        val ownDecoders = object : VideoDecoderFactory {
            override val name: String = "renderer decoder"
            override suspend fun create(stream: PlayerStreamInfo, hwdec: HwdecPolicy): VideoDecoder? =
                harness!!.backend.sessions.last().videoDecoders.first().create(stream, hwdec)
        }
        val started = CoreHarness(
            this,
            script = MediaScript(durationUs = 3_000_000),
            faults = faults,
            renderer = RecordingRenderer(decoderFactories = listOf(ownDecoders)),
        )
        harness = started
        started.attachRenderer()
        started.core.openQueue(items, 0)
        // The first item has its decoder. From here no decoder takes a video stream.
        faults.videoDecodersRefuse = true
        started.core.play()
        assertTrue(started.runUntil(5.seconds) { started.core.snapshots.value.queueIndex == 1 })
        assertTrue(
            started.runUntil(2.seconds) {
                val snapshot = started.core.snapshots.value
                snapshot.tracks.selectedVideo == null && snapshot.status == PlaybackStatus.Playing
            },
            "the second item plays on without its video: ${started.core.snapshots.value.status}",
        )
        val deselected = started.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.TrackDeselected>()
        assertTrue(deselected.isNotEmpty(), "and says why")
        started.close()
        assertEquals(0, started.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekDuringThePreloadDropsItQuietlyAndTheHandoffStillFollows() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.preloadedIndex == 1 })
        harness.core.seek(Pts(500_000), SeekMode.Precise)
        assertEquals(null, harness.core.snapshots.value.preloadedIndex, "the seek dropped the preload")
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue still moves on")
        assertEquals(1, harness.sink.openCount, "and without a gap")
        assertEquals(emptyList(), harness.fallbacks(), "a dropped preload warns nothing")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aSeekDuringTheHandoffTakesTheRingBackAndPlaysTheCurrentItemOn() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        // The ring holds the last 200 ms of the first item once its sound is all written, so the
        // next item's samples are in the ring from about 2.8 s until the swap at 3 s.
        assertTrue(harness.runUntil(3.seconds) { harness.core.position() >= 2.9.seconds })
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        harness.run(200.milliseconds)
        assertEquals(0, harness.core.snapshots.value.queueIndex, "the seek stayed in the first item")
        val position = harness.core.position()
        assertTrue(position in 1.seconds..1.3.seconds, "it plays on from the seek target: $position")
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue still moves on")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aLoopChangeDuringTheHandoffRepeatsTheCurrentItem() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.position() >= 2.9.seconds })
        harness.core.setLoop(LoopMode.One)
        harness.run(1.seconds)
        assertEquals(0, harness.core.snapshots.value.queueIndex, "the current item repeats")
        assertTrue(harness.core.snapshots.value.status == PlaybackStatus.Playing, "and plays: ${harness.core.snapshots.value.status}")
        assertTrue(harness.core.position() < 1.seconds, "from its start: ${harness.core.position()}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun stopDuringThePreloadReleasesBothItems() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.preloadedIndex == 1 })
        harness.core.stop()
        assertEquals(0, harness.ledger.liveCount, "the stop released the current and the preloaded item")
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aQueueEditDuringThePreloadPreloadsTheNewNextItem() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.preloadedIndex == 1 })
        harness.core.addToQueue(listOf(MediaItem("scripted://inserted")), 1)
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        assertEquals("scripted://inserted", harness.core.snapshots.value.media?.uri, "the inserted item plays next")
        assertEquals(1, harness.sink.openCount, "without a gap")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun loopAllWrapsWithoutAGap() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000, hasVideo = false))
        harness.core.openQueue(items, 0)
        harness.core.setLoop(LoopMode.All)
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.backend.openCalls >= 4 }, "the queue wrapped")
        assertEquals(1, harness.sink.openCount, "the device opened once for the whole loop")
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aPauseDuringTheHandoffKeepsItAndPlayResumesIntoTheNextItem() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.position() >= 2.9.seconds })
        harness.core.pause()
        harness.run(1.seconds)
        assertEquals(0, harness.core.snapshots.value.queueIndex, "a paused device plays nothing, so nothing crosses")
        harness.core.play()
        assertTrue(harness.runUntil(1.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "play carries on into the next item")
        assertEquals(1, harness.sink.openCount)
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun nextDuringThePreloadPlaysThePreloadedItemWithoutOpeningItAgain() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.preloadedIndex == 1 })
        harness.core.queueNext()
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        assertEquals(null, harness.core.snapshots.value.preloadedIndex)
        assertEquals(2, harness.backend.openCalls, "the preload became the current item without a second open")
        assertEquals(2, harness.sink.openCount, "with a device of its own, as next() always gives")
        assertEquals(emptyList(), harness.fallbacks(), "the caller asked for the move, so nothing is warned")
        harness.run(500.milliseconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the play intent carries on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aQueueAtTwiceTheSpeedJoinsWithoutAGap() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.setSpeed(2.0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        val position = harness.core.position()
        assertTrue(position < 100.milliseconds, "the second item starts near zero at twice the speed: $position")
        assertEquals(1, harness.sink.openCount)
        assertEquals(0L, harness.core.stats.value.audioUnderruns)
        harness.run(500.milliseconds)
        val later = harness.core.position()
        assertTrue(later in 900.milliseconds..1100.milliseconds, "and runs at twice the speed: $later")
        assertEquals(emptyList(), harness.fallbacks())
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun aShuffledQueuePreloadsTheItemItsOrderPlaysNext() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        val three = items + MediaItem("scripted://third")
        harness.core.openQueue(three, 0)
        harness.core.setShuffle(true, seed = 7)
        val order = harness.core.snapshots.value.queueOrder
        val expected = order[order.indexOf(harness.core.snapshots.value.queueIndex) + 1]
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex != order.first() })
        assertEquals(expected, harness.core.snapshots.value.queueIndex, "the order's next item played: $order")
        assertEquals(1, harness.sink.openCount)
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun closeDuringTheHandoffReleasesBothItems() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.core.position() >= 2.9.seconds })
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "the close released the current and the next item")
        assertTrue(harness.sink.closed, "and the device")
    }

    @Test
    fun aTapHearsTheCurrentItemThenADiscontinuityThenTheNextItem() = runTest {
        val harness = CoreHarness(this, script = threeSeconds)
        val heard = mutableListOf<String>()
        val tap = object : AudioTap {
            override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: io.github.yuroyami.kiteplayer.spi.AudioFormat) = Unit

            override fun onAudio(generation: Generation, pts: Pts, interleaved: FloatArray, frames: Int, format: io.github.yuroyami.kiteplayer.spi.AudioFormat) {
                heard += "audio ${generation.value} ${pts.micros}"
            }

            override fun onDiscontinuity(generation: Generation) {
                heard += "break ${generation.value}"
            }
        }
        harness.core.openQueue(items, 0)
        harness.core.post(io.github.yuroyami.kiteplayer.internal.CoreCommand.AttachAudioTap(tap, CompletableDeferred()))
        harness.core.play()
        assertTrue(harness.runUntil(4.seconds) { harness.core.snapshots.value.queueIndex == 1 })
        harness.run(300.milliseconds)
        // The last break is the handoff's: after it, the next item's blocks run from its start.
        val lastBreak = heard.indexOfLast { it.startsWith("break") }
        val after = heard.drop(lastBreak + 1).map { it.split(" ") }
        val generation = heard[lastBreak].split(" ")[1]
        assertTrue(after.isNotEmpty() && after.all { it[1] == generation }, "every later block is the new generation: $after")
        assertEquals("0", after.first()[2], "the next item's first block comes first: ${after.take(3)}")
        val before = heard.take(lastBreak).filter { it.startsWith("audio") }.map { it.split(" ")[2].toLong() }
        assertTrue(before.last() > 2_900_000, "the current item's last block came before the break: ${before.takeLast(3)}")
        harness.close()
    }

    @Test
    fun cancellingThePlayerDuringAPreloadStillReleasesTheCurrentItem() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job])
        val harness = CoreHarness(this, script = threeSeconds, parent = parent)
        harness.core.openQueue(items, 0)
        // The preload's open waits here, so the cancellation meets a build still in flight.
        val gate = CompletableDeferred<Unit>()
        harness.backend.openGate = gate
        harness.core.play()
        assertTrue(harness.runUntil(3.seconds) { harness.backend.openCalls == 2 }, "the preload started its open")
        parent.cancel()
        harness.run(100.milliseconds)
        gate.complete(Unit)
        harness.run(100.milliseconds)
        assertEquals(0, harness.ledger.liveCount, "the current item was released")
        assertTrue(harness.sink.closed, "and the device closed")
        harness.stopDevice()
    }

    /** Frames one scripted item delivers: whole decoder buffers covering its duration. */
    private fun framesOf(script: MediaScript): Long {
        val buffers = (script.durationUs + script.audioBufferDurationUs - 1) / script.audioBufferDurationUs
        return buffers * script.audioBufferFrames
    }
}
