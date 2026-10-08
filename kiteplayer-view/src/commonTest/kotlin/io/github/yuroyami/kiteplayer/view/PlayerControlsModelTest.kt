package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A player that writes down every command, and answers as a player would: a setting shows in its state at once. */
internal class ScriptedControlsPlayer(snapshot: PlayerSnapshot = PlayerSnapshot(), progress: Progress = Progress()) : ControlsPlayer {
    override val state = MutableStateFlow(snapshot)
    override val progress = MutableStateFlow(progress)
    val calls = mutableListOf<String>()
    var refuseEverything = false

    private fun record(call: String) {
        if (refuseEverything) throw IllegalStateException("the player is closed")
        calls += call
    }

    // Whole thousandths, because a Float prints differently on each target.
    private fun thousandths(value: Float): Int = (value * 1000).roundToInt()

    override fun position(): Duration = progress.value.position

    override fun play() {
        record("play")
        state.value = state.value.copy(playRequested = true)
    }

    override fun pause() {
        record("pause")
        state.value = state.value.copy(playRequested = false)
    }

    override fun requestSeek(to: Duration, mode: SeekMode) = record("seek $to $mode")

    override fun setSpeed(value: Double) {
        record("speed ${thousandths(value.toFloat())}")
        state.value = state.value.copy(speed = value)
    }

    override fun setVolume(value: Float) {
        record("volume ${thousandths(value)}")
        state.value = state.value.copy(volume = value)
    }

    override fun setMuted(value: Boolean) {
        record("muted $value")
        state.value = state.value.copy(muted = value)
    }

    override fun setSubtitlePosition(value: Float) {
        record("subtitles ${thousandths(value)}")
        state.value = state.value.copy(subtitlePosition = value)
    }

    override suspend fun next() = record("next")
    override suspend fun previous() = record("previous")
    override suspend fun selectTrack(kind: TrackKind, track: TrackId?) = record("select $kind $track")
    override suspend fun selectVariant(index: Int?) = record("variant $index")
}

internal fun playingSnapshot(duration: Int = 100): PlayerSnapshot = PlayerSnapshot(
    status = PlaybackStatus.Playing,
    duration = duration.seconds,
    seekable = true,
    playRequested = true,
)

/** A model over [player] whose timeout runs on this test's clock and whose commands run at once. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.controlsModel(
    player: ScriptedControlsPlayer,
    strings: PlayerControlsStrings = PlayerControlsStrings.Default,
): PlayerControlsModel = PlayerControlsModel(
    target = player,
    scope = backgroundScope,
    strings = strings,
    hideAfter = 3.seconds,
    speeds = DefaultSpeeds,
    commands = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
)

/** The model behind the default controls of the native views, against a scripted player (#469). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControlsModelTest {

    private val tracks = Tracks(
        all = listOf(
            TrackInfo(TrackId(1), TrackKind.Audio, "aac", language = "en"),
            TrackInfo(TrackId(2), TrackKind.Audio, "aac", language = "fr", title = "Commentary"),
            TrackInfo(TrackId(3), TrackKind.Subtitle, "subrip", language = "en"),
        ),
        selectedAudio = TrackId(2),
        variants = listOf(StreamVariant(index = 0, bitrate = 800_000, height = 360), StreamVariant(index = 1, bitrate = 2_500_000)),
    )

    @Test
    fun everyActionReachesThePlayer() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot().copy(tracks = tracks), Progress(position = 10.seconds))
        val model = controlsModel(player)
        var fullScreen = 0
        var floating = 0
        model.onFullScreen = { fullScreen++ }
        model.onPictureInPicture = { floating++ }

        model.togglePlay()
        model.togglePlay()
        model.previous()
        model.next()
        model.stepBy(PlayerControlsModel.SeekStep)
        model.toggleMute()
        model.select(PlayerControlsMenu.Audio, 0)
        model.select(PlayerControlsMenu.Subtitles, 0)
        model.select(PlayerControlsMenu.Subtitles, 1)
        model.select(PlayerControlsMenu.Quality, 0)
        model.select(PlayerControlsMenu.Quality, 2)
        model.select(PlayerControlsMenu.Speed, 4)
        // An index the menu does not have asks for nothing.
        model.select(PlayerControlsMenu.Speed, 40)
        model.toggleFullScreen()
        model.enterPictureInPicture()

        assertEquals(
            listOf(
                "pause", "play", "previous", "next", "seek 20s KeyframeThenRefine", "muted true",
                "select Audio stream1", "select Subtitle null", "select Subtitle stream3",
                "variant null", "variant 1", "speed 1500",
            ),
            player.calls,
        )
        assertEquals(1, fullScreen)
        assertEquals(1, floating)
    }

    @Test
    fun theSnapshotFollowsThePlayer() = runTest {
        val player = ScriptedControlsPlayer(PlayerSnapshot(status = PlaybackStatus.Paused, seekable = true, duration = 100.seconds))
        val model = controlsModel(player)
        assertTrue(model.state.value.showsPlay)
        assertEquals("0:00", model.state.value.positionText)
        assertEquals("1:40", model.state.value.durationText)
        assertFalse(model.state.value.canFullScreen)

        player.state.value = player.state.value.copy(status = PlaybackStatus.Buffering, playRequested = true, muted = true)
        player.progress.value = Progress(position = 40.seconds, bufferedAhead = 20.seconds, bufferedRanges = listOf(10.seconds..30.seconds))
        runCurrent()
        val shown = model.state.value
        // A player asked to play that is still buffering shows pause, the answer to the request.
        assertFalse(shown.showsPlay)
        assertTrue(shown.muted)
        assertEquals(40.seconds, shown.position)
        assertEquals(0.4f, shown.fraction)
        assertEquals("0:40", shown.positionText)
        assertEquals("0:40 of 1:40", shown.seekBarValueText)
        assertEquals(listOf(0.1f..0.3f), shown.buffered)

        // HLS, and the byte cache off, report no ranges: the read ahead stands in.
        player.progress.value = Progress(position = 40.seconds, bufferedAhead = 20.seconds)
        runCurrent()
        assertEquals(listOf(0.4f..0.6f), model.state.value.buffered)

        // A live item has no duration, so no seek bar and no ranges.
        player.state.value = player.state.value.copy(duration = null)
        runCurrent()
        assertFalse(model.state.value.seekable)
        assertNull(model.state.value.durationText)
        assertEquals(emptyList(), model.state.value.buffered)

        model.onFullScreen = {}
        assertTrue(model.state.value.canFullScreen)
    }

    @Test
    fun previousAndNextFollowThePlayOrderAndARepeatingQueueGoesRound() = runTest {
        val items = List(3) { MediaItem("item$it.mp4") }
        val player = ScriptedControlsPlayer(PlayerSnapshot(queue = items, queueIndex = 0, queueOrder = listOf(2, 0, 1)))
        val model = controlsModel(player)
        // Item 0 plays second in this order, so both neighbours exist.
        assertTrue(model.state.value.hasQueue)
        assertTrue(model.state.value.canGoPrevious)
        assertTrue(model.state.value.canGoNext)

        player.state.value = player.state.value.copy(queueIndex = 1)
        runCurrent()
        assertTrue(model.state.value.canGoPrevious)
        assertFalse(model.state.value.canGoNext)

        player.state.value = player.state.value.copy(loop = LoopMode.All)
        runCurrent()
        assertTrue(model.state.value.canGoNext)

        player.state.value = PlayerSnapshot(queue = items.take(1), queueIndex = 0)
        runCurrent()
        assertFalse(model.state.value.hasQueue)
        assertFalse(model.state.value.canGoPrevious)
    }

    @Test
    fun theControlsHideAfterTheTimeoutOnlyWhileThePlayerPlays() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val model = controlsModel(player)
        assertTrue(model.state.value.visible)
        advanceTimeBy(2_999.milliseconds)
        assertTrue(model.state.value.visible)
        advanceTimeBy(2.milliseconds)
        assertFalse(model.state.value.visible)

        // A paused player keeps them up for as long as it stays paused.
        model.togglePlay()
        runCurrent()
        assertTrue(model.state.value.visible)
        advanceTimeBy(60.seconds)
        assertTrue(model.state.value.visible)

        // Playing again starts the timeout.
        model.togglePlay()
        runCurrent()
        advanceTimeBy(3_001.milliseconds)
        assertFalse(model.state.value.visible)
    }

    @Test
    fun aPokeStartsTheTimeoutAgain() = runTest {
        val model = controlsModel(ScriptedControlsPlayer(playingSnapshot()))
        advanceTimeBy(2.seconds)
        model.poke()
        advanceTimeBy(2.seconds)
        assertTrue(model.state.value.visible)
        advanceTimeBy(1_001.milliseconds)
        assertFalse(model.state.value.visible)
    }

    @Test
    fun aScrubAndAHoldKeepTheControlsUp() = runTest {
        val model = controlsModel(ScriptedControlsPlayer(playingSnapshot()))
        model.beginScrub(0.5f)
        advanceTimeBy(10.seconds)
        assertTrue(model.state.value.visible)
        model.endScrub()
        advanceTimeBy(3_001.milliseconds)
        assertFalse(model.state.value.visible)

        model.poke()
        model.hold(true)
        advanceTimeBy(10.seconds)
        assertTrue(model.state.value.visible)
        model.hold(false)
        advanceTimeBy(3_001.milliseconds)
        assertFalse(model.state.value.visible)
    }

    @Test
    fun theFirstKeyOnlyShowsHiddenControlsAndATapTogglesThem() = runTest {
        val model = controlsModel(ScriptedControlsPlayer(playingSnapshot()))
        assertFalse(model.wake())
        model.toggleVisible()
        assertFalse(model.state.value.visible)
        assertTrue(model.wake())
        assertTrue(model.state.value.visible)
        model.hide()
        model.toggleVisible()
        assertTrue(model.state.value.visible)
    }

    @Test
    fun eachScrubStepSeeksWithKeyframeThenRefineAndTheEndAsksNothingMore() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 10.seconds))
        val model = controlsModel(player)
        assertEquals(0.1f, model.state.value.fraction)

        model.beginScrub(0.25f)
        model.moveScrub(0.5f)
        // The same target twice asks once.
        model.moveScrub(0.5f)
        assertEquals(50.seconds, model.state.value.scrubTarget)
        assertEquals("0:50", model.state.value.scrubText)
        // The bar shows the target, not the position the player has not left yet.
        assertEquals(0.5f, model.state.value.fraction)
        assertEquals("0:10", model.state.value.positionText)

        model.endScrub()
        assertNull(model.state.value.scrubTarget)
        assertEquals(0.1f, model.state.value.fraction)
        assertEquals(listOf("seek 25s KeyframeThenRefine", "seek 50s KeyframeThenRefine"), player.calls)
    }

    @Test
    fun aCancelledScrubGoesBackToWhereItStarted() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 10.seconds))
        val model = controlsModel(player)
        model.beginScrub(0.8f)
        model.cancelScrub()
        assertEquals(listOf("seek 1m 20s KeyframeThenRefine", "seek 10s KeyframeThenRefine"), player.calls)

        // A cancel with no scrub running moves nothing.
        model.cancelScrub()
        assertEquals(2, player.calls.size)
    }

    @Test
    fun aScrubNeedsASeekableItemWithADuration() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot().copy(seekable = false))
        val model = controlsModel(player)
        model.beginScrub(0.5f)
        model.moveScrub(0.6f)
        model.stepBy(10.seconds)
        assertFalse(model.state.value.seekable)
        assertNull(model.state.value.scrubTarget)
        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun aStepStaysInsideTheItemAndMovesARunningScrub() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot(), Progress(position = 95.seconds))
        val model = controlsModel(player)
        model.stepBy(10.seconds)
        player.progress.value = Progress(position = 5.seconds)
        model.stepBy((-10).seconds)
        model.beginScrub(0.5f)
        model.stepBy(10.seconds)
        assertEquals(60.seconds, model.state.value.scrubTarget)
        assertEquals(
            listOf("seek 1m 40s KeyframeThenRefine", "seek 0s KeyframeThenRefine", "seek 50s KeyframeThenRefine", "seek 1m KeyframeThenRefine"),
            player.calls,
        )
    }

    @Test
    fun aDragAcrossThePictureStandsForTheItemOrTenMinutesOfALongOne() = runTest {
        val short = controlsModel(ScriptedControlsPlayer(playingSnapshot(duration = 100)))
        assertEquals(0.75f, short.pictureScrubFraction(from = 0.25f, dragShare = 0.5f))
        val long = controlsModel(ScriptedControlsPlayer(playingSnapshot(duration = 6000)))
        assertTrue(abs(long.pictureScrubFraction(from = 0.25f, dragShare = 0.5f) - 0.3f) < 1e-6f)
        val live = controlsModel(ScriptedControlsPlayer(playingSnapshot().copy(duration = null)))
        assertEquals(0.25f, live.pictureScrubFraction(from = 0.25f, dragShare = 0.5f))
    }

    @Test
    fun theMenusListEveryChoiceWithItsNameAndItsMark() = runTest {
        val player = ScriptedControlsPlayer(
            playingSnapshot().copy(tracks = tracks, media = MediaItem("stream.m3u8", demux = DemuxPolicy(variant = 1)), speed = 1.5),
        )
        val shown = controlsModel(player).state.value
        assertEquals(
            listOf(PlayerControlsOption("en", false), PlayerControlsOption("Commentary (fr)", true)),
            shown.audioTracks,
        )
        assertEquals(listOf(PlayerControlsOption("Off", true), PlayerControlsOption("en", false)), shown.subtitleTracks)
        assertEquals(
            listOf(PlayerControlsOption("Automatic", false), PlayerControlsOption("360p", false), PlayerControlsOption("2.5 Mbps", true)),
            shown.qualities,
        )
        assertEquals(listOf("0.5x", "0.75x", "Normal", "1.25x", "1.5x", "2x"), shown.speeds.map { it.label })
        assertEquals(listOf(false, false, false, false, true, false), shown.speeds.map { it.selected })
        PlayerControlsMenu.entries.forEach { assertTrue(shown.offers(it), "$it") }

        // One audio track, no subtitles and one variant leave only the speed to choose.
        val plain = Tracks(
            all = listOf(TrackInfo(TrackId(1), TrackKind.Audio, "aac")),
            variants = listOf(StreamVariant(index = 0, bitrate = 800_000)),
        )
        val little = controlsModel(ScriptedControlsPlayer(playingSnapshot().copy(tracks = plain))).state.value
        assertEquals(listOf(PlayerControlsMenu.Speed), PlayerControlsMenu.entries.filter { little.offers(it) })
        assertEquals(emptyList(), little.subtitleTracks)
    }

    @Test
    fun everyWordComesFromTheStrings() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot().copy(tracks = tracks), Progress(position = 61.seconds))
        val french = PlayerControlsStrings(
            subtitlesOff = "Aucun",
            automaticQuality = "Auto",
            time = { "${it.inWholeSeconds} s" },
            positionOf = { at, total -> "${at.inWholeSeconds} sur ${total?.inWholeSeconds}" },
            volumeLevel = { "${(it * 10).toInt()} sur 10" },
            trackName = { "piste ${it.id.value}" },
            variantName = { "variante ${it.index}" },
            speedName = { "fois $it" },
        )
        val model = controlsModel(player, french)
        val shown = model.state.value
        assertEquals("61 s", shown.positionText)
        assertEquals("100 s", shown.durationText)
        assertEquals("61 sur 100", shown.seekBarValueText)
        assertEquals("10 sur 10", shown.volumeText)
        assertEquals(listOf("piste 1", "piste 2"), shown.audioTracks.map { it.label })
        assertEquals(listOf("Aucun", "piste 3"), shown.subtitleTracks.map { it.label })
        assertEquals(listOf("Auto", "variante 0", "variante 1"), shown.qualities.map { it.label })
        assertEquals("fois 0.5", shown.speeds.first().label)

        // New words rebuild what shows.
        model.strings = PlayerControlsStrings.Default
        assertEquals("1:01", model.state.value.positionText)
        assertEquals("Off", model.state.value.subtitleTracks.first().label)
    }

    @Test
    fun theVolumeSliderFollowsACube() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val model = controlsModel(player)
        assertEquals(1f, model.state.value.volumeLevel)

        model.setVolumeLevel(0.5f)
        runCurrent()
        assertEquals("volume 125", player.calls.last())
        // The slider reads back the value it was given, through the cube root.
        assertTrue(abs(model.state.value.volumeLevel - 0.5f) < 1e-6f)
        assertEquals("50 percent", model.state.value.volumeText)

        // A volume above unity shows as a full slider.
        player.state.value = player.state.value.copy(volume = 2f)
        runCurrent()
        assertEquals(1f, model.state.value.volumeLevel)

        // Moving the slider above 0 takes the mute off, and 0 leaves it.
        player.state.value = player.state.value.copy(muted = true)
        model.setVolumeLevel(0f)
        assertEquals("volume 0", player.calls.last())
        model.setVolumeLevel(1.4f)
        assertEquals(listOf("volume 1000", "muted false"), player.calls.takeLast(2))
    }

    @Test
    fun subtitlesStandAboveTheControlsWhileTheyShow() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val model = controlsModel(player)
        model.barShare = 0.25f
        assertEquals(listOf("subtitles 750"), player.calls)
        // The same share again moves nothing.
        model.poke()
        assertEquals(1, player.calls.size)

        advanceTimeBy(3_001.milliseconds)
        assertEquals(listOf("subtitles 750", "subtitles 1000"), player.calls)

        // Subtitles the application already placed higher stay where they are.
        player.state.value = player.state.value.copy(subtitlePosition = 0.5f)
        model.poke()
        assertEquals(2, player.calls.size)

        // A position the application set during the lift is not undone when the controls hide.
        player.state.value = player.state.value.copy(subtitlePosition = 1f)
        model.poke()
        assertEquals("subtitles 750", player.calls.last())
        player.state.value = player.state.value.copy(subtitlePosition = 0.9f)
        model.hide()
        assertEquals(3, player.calls.size)
    }

    @Test
    fun closingPutsSubtitlesBackAndStopsFollowing() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot())
        val model = controlsModel(player)
        model.barShare = 0.25f
        model.close()
        assertEquals(listOf("subtitles 750", "subtitles 1000"), player.calls)

        player.progress.value = Progress(position = 50.seconds)
        runCurrent()
        advanceTimeBy(10.seconds)
        assertEquals(Duration.ZERO, model.state.value.position)
        assertTrue(model.state.value.visible)
    }

    @Test
    fun aCommandThePlayerRefusesIsDroppedRatherThanThrown() = runTest {
        val player = ScriptedControlsPlayer(playingSnapshot().copy(tracks = tracks)).apply { refuseEverything = true }
        val model = controlsModel(player)
        model.togglePlay()
        model.next()
        model.beginScrub(0.5f)
        model.setVolumeLevel(0.5f)
        model.select(PlayerControlsMenu.Audio, 0)
        model.select(PlayerControlsMenu.Speed, 0)
        model.barShare = 0.25f
        assertEquals(emptyList(), player.calls)
    }
}
