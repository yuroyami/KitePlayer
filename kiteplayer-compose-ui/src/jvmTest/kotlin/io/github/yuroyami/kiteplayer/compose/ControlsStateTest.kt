package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.ThumbnailSet
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The state holders of the default controls, against a scripted player (#469). */
class ControlsStateTest {

    private val unconfined = CoroutineScope(Dispatchers.Unconfined)

    private fun playing(duration: Int = 100) = PlayerSnapshot(
        status = PlaybackStatus.Playing,
        duration = duration.seconds,
        seekable = true,
        playRequested = true,
    )

    @Test
    fun `play shows until the player is asked to play`() {
        val target = ScriptedTarget(PlayerSnapshot(status = PlaybackStatus.Paused, seekable = true))
        val transport = TransportState(target.source())
        assertTrue(transport.showsPlay)
        transport.togglePlay()

        // A player asked to play that is still buffering shows pause, the answer to the request.
        target.snapshot.value = target.snapshot.value.copy(status = PlaybackStatus.Buffering, playRequested = true)
        assertFalse(transport.showsPlay)
        transport.togglePlay()

        // An ended player offers play again, which starts the item over.
        target.snapshot.value = target.snapshot.value.copy(status = PlaybackStatus.Ended, playRequested = false)
        assertTrue(transport.showsPlay)
        assertEquals(listOf("play", "pause"), target.callsSoFar())
    }

    @Test
    fun `next and previous follow the play order and a repeating queue goes round`() {
        val items = List(3) { MediaItem("item$it.mp4") }
        val target = ScriptedTarget(PlayerSnapshot(queue = items, queueIndex = 0, queueOrder = listOf(2, 0, 1)))
        val transport = TransportState(target.source())
        // Item 0 plays second in this order, so both neighbours exist.
        assertTrue(transport.canGoPrevious)
        assertTrue(transport.canGoNext)

        target.snapshot.value = target.snapshot.value.copy(queueIndex = 1)
        assertTrue(transport.canGoPrevious)
        assertFalse(transport.canGoNext)

        target.snapshot.value = target.snapshot.value.copy(loop = LoopMode.All)
        assertTrue(transport.canGoNext)

        target.snapshot.value = PlayerSnapshot(queue = items.take(1), queueIndex = 0)
        assertFalse(transport.canGoPrevious)
        assertFalse(transport.canGoNext)

        transport.next()
        transport.previous()
        assertEquals(listOf("next", "previous"), target.callsSoFar())
    }

    @Test
    fun `a command the player refuses is dropped rather than thrown`() {
        val target = ScriptedTarget(playing()).apply { refuseEverything = true }
        val source = target.source()
        TransportState(source).togglePlay()
        TransportState(source).next()
        SeekBarState(source, unconfined).startScrub(0.5f)
        VolumeState(source).setLevel(0.5f)
        assertEquals(emptyList(), target.callsSoFar())
    }

    @Test
    fun `each scrub step seeks with keyframe then refine and the end asks nothing more`() {
        val target = ScriptedTarget(playing(), Progress(position = 10.seconds))
        val bar = SeekBarState(target.source(), unconfined)
        assertEquals(0.1f, bar.fraction)

        bar.startScrub(0.25f)
        bar.scrubTo(0.5f)
        // The same target twice asks once.
        bar.scrubTo(0.5f)
        assertTrue(bar.scrubbing)
        assertEquals(50.seconds, bar.scrubTarget)
        // The bar shows the target, not the position the player has not left yet.
        assertEquals(0.5f, bar.fraction)

        bar.endScrub()
        assertFalse(bar.scrubbing)
        assertEquals(0.1f, bar.fraction)
        assertEquals(listOf("seek 25s KeyframeThenRefine", "seek 50s KeyframeThenRefine"), target.callsSoFar())
    }

    @Test
    fun `a cancelled scrub goes back to where it started`() {
        val target = ScriptedTarget(playing(), Progress(position = 10.seconds))
        val bar = SeekBarState(target.source(), unconfined)
        bar.startScrub(0.8f)
        bar.cancelScrub()
        assertEquals(listOf("seek 1m 20s KeyframeThenRefine", "seek 10s KeyframeThenRefine"), target.callsSoFar())

        // A cancel with no scrub running moves nothing.
        bar.cancelScrub()
        assertEquals(2, target.callsSoFar().size)
    }

    @Test
    fun `a scrub needs a seekable item with a duration`() {
        val target = ScriptedTarget(playing().copy(seekable = false))
        val bar = SeekBarState(target.source(), unconfined)
        bar.startScrub(0.5f)
        bar.stepBy(10.seconds)
        assertFalse(bar.seekable)

        target.snapshot.value = playing().copy(duration = null)
        bar.startScrub(0.5f)
        assertFalse(bar.seekable)
        assertEquals(0f, bar.fraction)
        assertEquals(emptyList(), target.callsSoFar())
    }

    @Test
    fun `buffered ranges are parts of the duration and fall back to the read ahead`() {
        val target = ScriptedTarget(
            playing(),
            Progress(position = 40.seconds, bufferedAhead = 20.seconds, bufferedRanges = listOf(10.seconds..30.seconds)),
        )
        val bar = SeekBarState(target.source(), unconfined)
        assertEquals(listOf(0.1f..0.3f), bar.buffered)

        // HLS, and the byte cache off, report no ranges: the read ahead stands in.
        target.progress.value = Progress(position = 40.seconds, bufferedAhead = 20.seconds)
        assertEquals(listOf(0.4f..0.6f), bar.buffered)

        target.progress.value = Progress(position = 90.seconds, bufferedAhead = 30.seconds)
        assertEquals(listOf(0.9f..1f), bar.buffered)

        target.progress.value = Progress(position = 90.seconds)
        assertEquals(emptyList(), bar.buffered)
    }

    @Test
    fun `a step jumps from where the player is and stays inside the item`() {
        val target = ScriptedTarget(playing(), Progress(position = 95.seconds))
        val bar = SeekBarState(target.source(), unconfined)
        bar.stepBy(10.seconds)
        target.progress.value = Progress(position = 5.seconds)
        bar.stepBy((-10).seconds)
        assertEquals(listOf("seek 1m 40s KeyframeThenRefine", "seek 0s KeyframeThenRefine"), target.callsSoFar())
    }

    @Test
    fun `the preview follows the scrub target`() {
        val picture = StreamThumbnail(ByteArray(4), "image/jpeg", 0, 0, 160, 90, 20.seconds, 30.seconds)
        val target = ScriptedTarget(playing().copy(tracks = Tracks(thumbnails = ThumbnailSet())))
        target.thumbnails = { if (it in 20.seconds..30.seconds) picture else null }
        val bar = SeekBarState(target.source(), unconfined)

        bar.startScrub(0.25f)
        assertSame(picture, bar.preview)
        bar.scrubTo(0.5f)
        assertNull(bar.preview)
        bar.endScrub()
        assertNull(bar.preview)
        assertTrue("thumbnail 25s" in target.callsSoFar())

        // An item with no pictures is not asked for any.
        target.snapshot.value = playing()
        target.calls.clear()
        bar.startScrub(0.25f)
        assertEquals(listOf("seek 25s KeyframeThenRefine"), target.callsSoFar())
    }

    @Test
    fun `the volume follows a cube`() {
        val target = ScriptedTarget(playing())
        val volume = VolumeState(target.source())
        assertEquals(1f, volume.level)

        volume.setLevel(0.5f)
        assertEquals("volume 0.125", target.callsSoFar().last())
        assertEquals(0.5f, volume.level, 1e-6f)
        volume.setLevel(0f)
        assertEquals("volume 0.0", target.callsSoFar().last())
        volume.setLevel(1f)
        assertEquals("volume 1.0", target.callsSoFar().last())

        // A boost above unity shows as a full slider, and showing it changes nothing.
        target.snapshot.value = target.snapshot.value.copy(volume = 2f)
        target.calls.clear()
        assertEquals(1f, volume.level)
        assertEquals(emptyList(), target.callsSoFar())
    }

    @Test
    fun `moving the volume above zero takes the mute off`() {
        val target = ScriptedTarget(playing().copy(muted = true))
        val volume = VolumeState(target.source())
        volume.setLevel(0f)
        assertTrue(volume.muted)
        volume.setLevel(0.5f)
        assertFalse(volume.muted)
        volume.toggleMute()
        assertTrue(volume.muted)
        assertEquals(listOf("volume 0.0", "volume 0.125", "muted false", "muted true"), target.callsSoFar())
    }

    @Test
    fun `the track menus list every track and subtitles start with off`() {
        val tracks = Tracks(
            all = listOf(
                TrackInfo(TrackId(1), TrackKind.Audio, "aac", language = "ja"),
                TrackInfo(TrackId(2), TrackKind.Audio, "aac", language = "en", title = "Dub"),
                TrackInfo(TrackId(3), TrackKind.Subtitle, "ass", language = "en"),
                TrackInfo(TrackId(4), TrackKind.Subtitle, "ass", language = "en", isForced = true),
            ),
            selectedAudio = TrackId(2),
        )
        val target = ScriptedTarget(playing().copy(tracks = tracks))
        val source = target.source()

        val audio = trackOptions(source, TrackKind.Audio, DefaultControlsLabels)
        assertEquals(listOf("ja", "Dub (en)"), audio.map { it.label })
        assertEquals(listOf(false, true), audio.map { it.selected })

        val subtitles = trackOptions(source, TrackKind.Subtitle, DefaultControlsLabels)
        assertEquals(listOf("Off", "en", "en [forced]"), subtitles.map { it.label })
        assertEquals(listOf(true, false, false), subtitles.map { it.selected })

        TrackMenuState(source) { trackOptions(it, TrackKind.Subtitle, DefaultControlsLabels) }.apply {
            select(options[2])
            select(options[0])
        }
        assertEquals(listOf("select Subtitle stream4", "select Subtitle null"), target.callsSoFar())

        // No subtitle tracks, no menu: an off option alone chooses nothing.
        target.snapshot.value = playing()
        assertEquals(emptyList(), trackOptions(source, TrackKind.Subtitle, DefaultControlsLabels))
    }

    @Test
    fun `the quality menu starts with automatic and marks a chosen variant`() {
        val variants = listOf(
            StreamVariant(index = 0, bitrate = 800_000, height = 360),
            StreamVariant(index = 1, bitrate = 3_000_000, height = 720),
            StreamVariant(index = 2, bitrate = 2_500_000),
        )
        val snapshot = playing().copy(media = MediaItem("master.m3u8"), tracks = Tracks(variants = variants, selectedVariant = 1))
        val target = ScriptedTarget(snapshot)
        val source = target.source()

        val automatic = qualityOptions(source, DefaultControlsLabels)
        assertEquals(listOf("Automatic", "360p", "720p", "2.5 Mbps"), automatic.map { it.label })
        // The player picked variant 1 by itself, so automatic is still the choice.
        assertEquals(listOf(true, false, false, false), automatic.map { it.selected })

        target.snapshot.value = snapshot.copy(media = MediaItem("master.m3u8", demux = DemuxPolicy(variant = 1)))
        val chosen = qualityOptions(source, DefaultControlsLabels)
        assertEquals(listOf(false, false, true, false), chosen.map { it.selected })

        val menu = TrackMenuState(source) { qualityOptions(it, DefaultControlsLabels) }
        menu.select(menu.options[0])
        menu.select(menu.options[3])
        assertEquals(listOf("variant null", "variant 2"), target.callsSoFar())

        target.snapshot.value = playing()
        assertEquals(emptyList(), qualityOptions(source, DefaultControlsLabels))
    }

    @Test
    fun `the speed menu marks the speed that plays and keeps to the player's range`() {
        val target = ScriptedTarget(playing().copy(speed = 1.5))
        val source = target.source()
        val options = speedOptions(source, listOf(0.1, 0.5, 1.0, 1.5, 2.0, 8.0), DefaultControlsLabels)
        assertEquals(listOf("0.5x", "Normal", "1.5x", "2x"), options.map { it.label })
        assertEquals(listOf(false, false, true, false), options.map { it.selected })

        TrackMenuState(source) { speedOptions(it, DefaultSpeeds, DefaultControlsLabels) }.apply { select(options[3]) }
        assertEquals(listOf("speed 2.0"), target.callsSoFar())
        assertEquals(listOf("Normal", "2x"), speedOptions(source, listOf(1.0, 2.0), DefaultControlsLabels).map { it.label })
        assertTrue(speedOptions(source, listOf(1.0, 2.0), DefaultControlsLabels)[1].selected)
    }

    @Test
    fun `subtitles settle back only when the application left them alone`() {
        val target = ScriptedTarget(playing())
        val source = target.source()
        val lift = SubtitleLift()

        lift.raise(source, 0.8f)
        lift.settle(source)
        assertEquals(listOf("subtitles 0.8", "subtitles 1.0"), target.callsSoFar())

        // The application moves them while the controls show: its position stays.
        target.calls.clear()
        lift.raise(source, 0.8f)
        target.snapshot.value = target.snapshot.value.copy(subtitlePosition = 0.5f)
        lift.settle(source)
        assertEquals(listOf("subtitles 0.8"), target.callsSoFar())

        // Already above the controls: nothing to lift and nothing to restore.
        target.calls.clear()
        lift.raise(source, 0.8f)
        lift.settle(source)
        assertEquals(emptyList(), target.callsSoFar())
    }

    @Test
    fun `a settle sent before the lift shows still puts subtitles back`() {
        val target = ScriptedTarget(playing())
        val source = target.source()
        val lift = SubtitleLift()
        lift.raise(source, 0.8f)
        // The engine has not applied the lift yet, so the player still shows the old position.
        target.snapshot.value = target.snapshot.value.copy(subtitlePosition = 1f)
        lift.settle(source)
        assertEquals(listOf("subtitles 0.8", "subtitles 1.0"), target.callsSoFar())
    }

    @Test
    fun `the default words`() {
        assertEquals("0:05", clockText(5.seconds))
        assertEquals("1:05", clockText(65.seconds))
        assertEquals("1:02:03", clockText(3723.seconds))
        assertEquals("1:05 of 3:20", DefaultControlsLabels.positionOf(65.seconds, 200.seconds))
        assertEquals("1:05", DefaultControlsLabels.positionOf(65.seconds, null))
        assertEquals("50 percent", DefaultControlsLabels.volumeLevel(0.5f))
        assertEquals("1.25x", DefaultControlsLabels.speedName(1.25))
        assertEquals("0.75x", DefaultControlsLabels.speedName(0.75))
        assertEquals("640 kbps", DefaultControlsLabels.variantName(StreamVariant(index = 0, bitrate = 640_000)))
        assertEquals("1080p", DefaultControlsLabels.variantName(StreamVariant(index = 0, bitrate = 640_000, height = 1080)))
    }
}
