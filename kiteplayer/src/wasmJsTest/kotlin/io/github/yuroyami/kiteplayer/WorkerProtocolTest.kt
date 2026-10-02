package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The messages between the page and a worker player (#100) come back as they went. Each message
 * is encoded to the JS object `postMessage` would copy and decoded from it, so a field that one
 * side writes under one name and the other reads under another fails here rather than in a browser.
 */
class WorkerProtocolTest {

    private val item = ItemRecord(
        uri = "https://example.com/a b.mkv",
        headers = mapOf("Authorization" to "Bearer x", "X-Empty" to ""),
        formatHint = "matroska",
        openOptions = mapOf("probesize" to "32"),
        startMicros = 90_000_000L,
        title = "Title",
        artist = "Artist",
        album = "Album",
    )

    private val error = ErrorRecord(
        kind = "SourceStalled",
        message = "no data",
        uri = "https://example.com/x",
        detail = "detail",
        codec = "h264",
        stalledMicros = 30_000_000L,
    )

    private val state = StateRecord(
        status = PlaybackStatus.Playing,
        hasMedia = true,
        durationMicros = 600_000_000_000L,
        seekable = true,
        videoWidth = 1920,
        videoHeight = 1080,
        speed = 1.5,
        volume = 0.25f,
        muted = true,
        metadata = mapOf("title" to "Ünïcødé ✓"),
    )

    private fun PageMessage.roundTrip(): PageMessage? = decodePageMessage(encode())

    private fun WorkerMessage.roundTrip(): WorkerMessage? = decodeWorkerMessage(encode())

    @Test
    fun everyPageMessageComesBackAsItWent() {
        val messages = listOf(
            PageMessage.Init("https://example.com/kite.mjs", 48_000, 2, 0.02),
            PageMessage.Init("./kite.mjs", 0, 0, null),
            PageMessage.Open(1, item),
            PageMessage.Open(2, ItemRecord("blob:https://example.com/0")),
            PageMessage.Seek(3, 9_007_199_254_740_991L),
            PageMessage.Stop(4),
            PageMessage.Close(5),
            PageMessage.Play,
            PageMessage.Pause,
            PageMessage.Viewport(640, 360, 2f),
        )
        for (message in messages) assertEquals(message, message.roundTrip())
    }

    @Test
    fun everyWorkerMessageComesBackAsItWent() {
        val messages = listOf(
            WorkerMessage.Hello,
            WorkerMessage.Ready,
            WorkerMessage.InitFailed("the codec module did not load"),
            WorkerMessage.Reply(1, null),
            WorkerMessage.Reply(2, error),
            WorkerMessage.State(state),
            WorkerMessage.State(state.copy(durationMicros = null, videoWidth = null, videoHeight = null, metadata = emptyMap())),
            WorkerMessage.Progress(12_345_678L, 2_000_000L),
            WorkerMessage.Event(EventRecord.Ended),
            WorkerMessage.Event(EventRecord.Failed(error)),
            WorkerMessage.Event(EventRecord.FirstFrameRendered(41_000L)),
            WorkerMessage.Event(EventRecord.VideoSizeChanged(1280, 720)),
            WorkerMessage.Event(EventRecord.AudioFormatChanged(44_100, 6)),
            WorkerMessage.Audio(true),
            WorkerMessage.Audio(false),
        )
        for (message in messages) assertEquals(message, message.roundTrip())
    }

    @Test
    fun aMessageOfAnotherKindIsIgnored() {
        assertNull(decodePageMessage(null))
        assertNull(decodeWorkerMessage(null))
        assertNull(decodePageMessage(WorkerMessage.Ready.encode()))
        assertNull(decodeWorkerMessage(PageMessage.Play.encode()))
        assertNull(decodeWorkerMessage(jsObject()))
    }

    @Test
    fun anItemCrossesWithItsAddressTitlesAndStart() {
        val media = MediaItem(
            uri = "https://example.com/movie.mp4",
            headers = mapOf("Cookie" to "a=b"),
            startPosition = 90.seconds,
            title = "Movie",
        )
        val crossed = ItemRecord.of(media).getOrThrow().toItem()
        assertEquals(media, crossed)
    }

    @Test
    fun anItemWithAPartThatCannotCrossIsRefused() {
        val refused = listOf(
            MediaItem.from(MediaIo.ofBytes(ByteArray(1)), "bytes.mp4"),
            MediaItem("https://example.com/a.mp4", externalSubtitles = listOf(SubtitleSource("https://example.com/a.srt"))),
            MediaItem("https://example.com/a.mp4", videoFilter = "hflip"),
            MediaItem("https://example.com/a.mp4", audioFilter = "volume=0.5"),
            MediaItem("https://example.com/a.mp4", demux = DemuxPolicy(corruptPackets = CorruptPackets.Drop)),
        )
        for (media in refused) {
            val failure = ItemRecord.of(media).exceptionOrNull()
            assertIs<PlaybackException>(failure, "an item with ${media.label} must be refused")
            assertIs<PlaybackError.ConfigurationInvalid>(failure.error)
        }
    }

    @Test
    fun anErrorComesBackOfTheSameKind() {
        val errors = listOf(
            PlaybackError.SourceUnavailable("https://example.com/x", null, "HTTP 404"),
            PlaybackError.SourceStalled("https://example.com/x", 30.seconds),
            PlaybackError.NotMedia("https://example.com/x", "no stream"),
            PlaybackError.DecoderFailed("hevc", "no decoder"),
            PlaybackError.ConfigurationInvalid("a refused part"),
            PlaybackError.Internal("the worker stopped"),
        )
        for (error in errors) {
            val crossed = decodeWorkerMessage(WorkerMessage.Reply(1, ErrorRecord.of(error)).encode())
            assertEquals(error, assertIs<WorkerMessage.Reply>(crossed).error?.toError())
        }
    }

    @Test
    fun aFailureThatIsNotAPlaybackErrorCrossesAsInternalWithItsMessage() {
        val crossed = ErrorRecord.of(IllegalStateException("the player is closed")).toError()
        assertEquals(PlaybackError.Internal("the player is closed"), crossed)
        assertTrue(crossed.message.endsWith(": the player is closed"))
    }

    @Test
    fun aSnapshotKeepsWhatCrosses() {
        val media = MediaItem("https://example.com/a.mp4")
        val snapshot = state.toSnapshot(media)
        assertEquals(media, snapshot.media)
        assertEquals(VideoSize(1920, 1080), snapshot.videoSize)
        assertEquals(state, StateRecord.of(snapshot))
        assertNull(state.copy(hasMedia = false).toSnapshot(media).media)
    }
}
