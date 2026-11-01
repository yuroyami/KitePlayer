package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.captionTrackIndex
import io.github.yuroyami.kiteplayer.internal.dtvccTriplets
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The closed captions inside a video stream become a subtitle track, CC1 (#236). The scripted
 * pictures carry them as broadcast pictures do, a few of them each changing the screen: HELLO at one
 * second, cleared at three, AGAIN at five. The engine makes the track from the first picture that
 * carries captions and reads every picture's captions into its cache, so it is chosen, cached,
 * timed and drawn as a container's own subtitle track is.
 */
class CaptionsInsideVideoTest {

    private fun captioned(durationUs: Long = 10_000_000) = MediaScript(
        durationUs = durationUs,
        videoCaptions = { pts ->
            when (pts) {
                1_000_000L -> "HELLO"
                3_000_000L -> SCRIPTED_CAPTION_CLEAR
                5_000_000L -> "AGAIN"
                else -> null
            }
        },
    )

    private suspend fun TestScope.opened(script: MediaScript, config: PlayerConfig = PlayerConfig()): CoreHarness {
        val harness = CoreHarness(this, script = script, config = config)
        harness.openWithRenderer()
        return harness
    }

    private val ccTrack = TrackId(captionTrackIndex(0))

    private val CoreHarness.tracks: Tracks get() = core.snapshots.value.tracks

    private val CoreHarness.shown: List<String>
        get() = core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }.filter { it.isNotEmpty() }

    @Test
    fun theCaptionsInsideThePicturesBecomeTheTrackCc1() = runTest {
        val harness = opened(captioned())
        assertEquals(null, harness.tracks.find(ccTrack), "no picture is decoded yet that carries captions")
        harness.core.play()
        harness.run(1500.milliseconds)

        val track = assertNotNull(harness.tracks.find(ccTrack), "the track was not made: ${harness.tracks.all}")
        assertEquals(TrackKind.Subtitle, track.kind)
        assertEquals("CC1", track.title)
        assertEquals(
            listOf(ccTrack),
            harness.events.filterIsInstance<PlayerEvent.TracksAdded>().flatMap { event -> event.tracks.map { it.id } },
        )
        // Like a television's, the captions show when asked for, not by default.
        assertEquals(null, harness.tracks.selectedSubtitle)
        assertTrue(harness.events.none { it is PlayerEvent.TrackChosenByPlayer })
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun choosingTheTrackShowsEachScreenInItsTurn() = runTest {
        val harness = opened(captioned())
        harness.core.play()
        harness.run(1500.milliseconds)
        // Chosen after HELLO went by: its picture's captions are in the track's cache.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, ccTrack))
        assertEquals(1, harness.backend.openCalls, "chosen in place")
        harness.run(500.milliseconds)
        assertEquals(listOf("HELLO"), harness.shown, "at ${harness.core.position()}")
        harness.run(1500.milliseconds)
        assertEquals(emptyList(), harness.shown, "the screen cleared at three seconds")
        harness.run(2.seconds)
        assertEquals(listOf("AGAIN"), harness.shown, "at ${harness.core.position()}")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSeekKeepsTheCaptionsInStep() = runTest {
        val harness = opened(captioned())
        harness.core.play()
        harness.run(1500.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, ccTrack))
        harness.run(200.milliseconds)
        assertEquals(listOf("HELLO"), harness.shown)

        harness.core.seek(Pts(4_000_000), SeekMode.Precise)
        harness.run(300.milliseconds)
        assertEquals(emptyList(), harness.shown, "nothing from before the seek stays on screen")
        harness.run(1500.milliseconds)
        assertEquals(listOf("AGAIN"), harness.shown, "the pictures after the seek still give their captions")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun theItemEndsWithTheCaptionsChosen() = runTest {
        val harness = opened(captioned(durationUs = 6_000_000))
        harness.core.play()
        harness.run(1500.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, ccTrack))
        harness.run(6.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status, harness.core.debugState)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aReopenKeepsTheCaptionsChosen() = runTest {
        // The hardware decoder dies at two seconds and the player reopens the item in software.
        // The new session makes the track again from its pictures and chooses it as it was.
        val faults = FaultPlan().apply { videoDecodeFailsAfterFrames = 50 }
        val harness = CoreHarness(this, script = captioned(), faults = faults, config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto))
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, ccTrack))
        harness.run(4.seconds)

        assertEquals(2, harness.backend.openCalls, "the item was reopened")
        assertTrue(harness.core.snapshots.value.status != PlaybackStatus.Failed, "${harness.core.snapshots.value.error}")
        assertEquals(ccTrack, harness.tracks.selectedSubtitle, "the captions stayed chosen: ${harness.core.debugState}")
        assertEquals(listOf("AGAIN"), harness.shown, "at ${harness.core.position()}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun picturesWithoutCaptionsMakeNoTrack() = runTest {
        val harness = opened(MediaScript(durationUs = 4_000_000))
        harness.core.play()
        harness.run(2.seconds)
        assertTrue(harness.tracks.all.none { it.kind == TrackKind.Subtitle }, "${harness.tracks.all}")
        assertTrue(harness.events.none { it is PlayerEvent.TracksAdded })
        harness.close()
    }

    /** HELLO! and AGAIN! in the SEI of an H.264 stream's packets, and none in its pictures (#611). */
    private fun inPackets() = MediaScript(
        durationUs = 10_000_000,
        videoCodec = "h264",
        videoCaptionsInPackets = true,
        videoCaptions = { pts ->
            when (pts) {
                1_000_000L -> "HELLO!"
                5_000_000L -> "AGAIN!"
                else -> null
            }
        },
    )

    // Android's MediaCodec, on the view's own route or FFmpeg's, gives pictures no captions.
    @Test
    fun aHardwareDecoderWhosePicturesCarryNoCaptionsStillMakesCc1FromThePackets() = runTest {
        val harness = CoreHarness(this, script = inPackets())
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertNotNull(harness.tracks.find(ccTrack), "no CC1 track from the packets: ${harness.tracks.all}")
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, ccTrack))
        harness.run(500.milliseconds)
        assertEquals(listOf("HELLO!"), harness.shown, "at ${harness.core.position()}")
        harness.run(4.seconds)
        assertEquals(listOf("AGAIN!"), harness.shown, "at ${harness.core.position()}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    // A software decoder gives the captions with its pictures, so its packets are never copied.
    @Test
    fun aSoftwareDecoderLeavesThePacketsUnread() = runTest {
        val harness = CoreHarness(this, script = inPackets())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1500.milliseconds)
        assertEquals(null, harness.tracks.find(ccTrack), "a software lane read its packets")
        harness.close()
    }

    /**
     * CEA-708 only, as some broadcasts carry a second language (#678): service 1 says HELLO and
     * service 2 HOLA at one second, and service 1 AGAIN at five.
     */
    private fun services(durationUs: Long = 10_000_000) = MediaScript(
        durationUs = durationUs,
        videoCaptionBytes = { pts ->
            when (pts) {
                1_000_000L -> dtvccTriplets(0, 1 to "HELLO".encodeToByteArray(), 2 to "HOLA".encodeToByteArray())
                5_000_000L -> dtvccTriplets(1, 1 to "AGAIN".encodeToByteArray())
                else -> null
            }
        },
    )

    private val service1 = TrackId(captionTrackIndex(0, service = 1))
    private val service2 = TrackId(captionTrackIndex(0, service = 2))

    @Test
    fun eachCea708ServiceBecomesATrackOfItsOwn() = runTest {
        val harness = opened(services())
        harness.core.play()
        harness.run(1500.milliseconds)

        val first = assertNotNull(harness.tracks.find(service1), "no track for service 1: ${harness.tracks.all}")
        val second = assertNotNull(harness.tracks.find(service2), "no track for service 2: ${harness.tracks.all}")
        assertEquals("Service 1", first.title)
        assertEquals("Service 2", second.title)
        assertEquals(TrackKind.Subtitle, second.kind)
        assertEquals(null, harness.tracks.find(ccTrack), "pictures with CEA-708 bytes alone make no CC1")
        assertEquals(null, harness.tracks.selectedSubtitle, "captions show when asked for")

        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, service2))
        harness.run(500.milliseconds)
        assertEquals(listOf("HOLA"), harness.shown, "at ${harness.core.position()}")
        harness.run(3500.milliseconds)
        assertEquals(listOf("HOLA"), harness.shown, "service 1's AGAIN is not service 2's")
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, service1))
        harness.run(500.milliseconds)
        assertEquals(listOf("AGAIN"), harness.shown, "at ${harness.core.position()}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun theItemEndsWithACea708ServiceChosen() = runTest {
        val harness = opened(services(durationUs = 6_000_000))
        harness.core.play()
        harness.run(1500.milliseconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, service2))
        harness.run(6.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status, harness.core.debugState)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }
}
