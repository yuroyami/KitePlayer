@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SRT = "1\n00:00:01,000 --> 00:00:03,000\nFrom a reader\n\n"

private fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Two cues of Japanese in Shift_JIS, as a Japanese subtitle file from Windows arrives. */
private val SHIFT_JIS_SRT: ByteArray =
    "1\n00:00:01,000 --> 00:00:03,000\n".encodeToByteArray() + hex("82a882cd82e682a482b282b482a282dc82b78142") +
        "\n\n2\n00:00:04,000 --> 00:00:06,000\n".encodeToByteArray() + hex("82a082e882aa82c682a48142") +
        "\n\n".encodeToByteArray()

/** A reader over a fixed block of bytes, which is what an application supplying its own is. */
private class BytesIo(private val bytes: ByteArray) : MediaIo {
    override val size: Long = bytes.size.toLong()
    override val seekable: Boolean = true
    private var at = 0
    var closes = 0
        private set

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

    override fun close() {
        closes++
    }
}

/**
 * Serves [parts] in order, where a null part answers 0: "nothing yet, more may come". Torrent,
 * cache and custom network readers answer that way while they wait.
 */
private class StutteringIo(private val parts: List<ByteArray?>, private val zerosForever: Boolean = false) : MediaIo {
    override val size: Long? = null
    override val seekable: Boolean = false
    private var next = 0
    var zeros = 0
        private set

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (zerosForever) {
            zeros++
            return 0
        }
        if (next >= parts.size) return -1
        val part = parts[next++] ?: return 0.also { zeros++ }
        part.copyInto(into, offset)
        return part.size
    }

    override suspend fun seek(position: Long) = Unit

    override fun close() = Unit
}

private const val TWO_CUE_SRT_HEAD = "1\n00:00:01,000 --> 00:00:02,000\nFirst line\n\n"
private const val TWO_CUE_SRT_TAIL = "2\n00:00:03,000 --> 00:00:04,000\nSecond line\n\n"

/**
 * Where an external subtitle's bytes come from.
 *
 * A subtitle at an address used to fall through the local file read, come back with nothing, and
 * reach the application as a deselection with a sentence in it. There was no reader field either,
 * although the item's own documentation referred to one.
 */
class ExternalSubtitleSourceTest {

    @Test
    fun `a subtitle read through its own reader becomes a track`() = runTest {
        val io = BytesIo(SRT.encodeToByteArray())
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(SubtitleSource(uri = "memory://subs.srt", io = { io })),
            ),
        )
        harness.run(100.milliseconds)
        val subtitles = harness.core.snapshots.value.tracks.all.filter { it.kind == TrackKind.Subtitle }
        assertEquals(1, subtitles.size, "the reader's cues never became a track")
        assertEquals(1, io.closes, "the reader must be closed exactly once")
        harness.close()
    }

    @Test
    fun `a subtitle that cannot be read is skipped with a typed warning`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(SubtitleSource(uri = "https://host.test/subs.srt")),
            ),
        )
        harness.run(100.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }
        assertTrue(
            warnings.any { it is PlaybackWarning.SubtitleSourceUnreadable },
            "the failure arrived as something other than the typed warning: $warnings",
        )
        harness.close()
    }

    @Test
    fun `an empty reader is refused rather than parsed`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "memory://empty.srt", io = { BytesIo(ByteArray(0)) }),
                ),
            ),
        )
        harness.run(100.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }
        val unreadable = warnings.filterIsInstance<PlaybackWarning.SubtitleSourceUnreadable>()
        assertTrue(unreadable.any { "empty" in it.reason }, "expected an empty refusal, got $warnings")
        harness.close()
    }


    // A 0 before the first byte and a 0 between two parts are waits, not the end (#215).
    @Test
    fun aReaderThatAnswersNothingYetIsWaitedFor() = runTest {
        val io = StutteringIo(
            listOf(null, TWO_CUE_SRT_HEAD.encodeToByteArray(), null, TWO_CUE_SRT_TAIL.encodeToByteArray()),
        )
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.core.open(
            MediaItem("scripted://one", externalSubtitles = listOf(SubtitleSource(uri = "memory://late.srt", io = { io }))),
        )
        harness.run(100.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }
        assertTrue(
            warnings.none { it is PlaybackWarning.SubtitleSourceUnreadable },
            "a reader that was only waiting was refused: $warnings",
        )
        assertEquals(2, io.zeros, "the reader never said \"nothing yet\" twice, so this proves nothing")
        val track = harness.core.snapshots.value.tracks.all.single { it.kind == TrackKind.Subtitle }
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, track.id))
        harness.core.play()
        harness.run(3500.milliseconds)
        val shown = harness.core.subtitleCues.value.filterIsInstance<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text>()
        assertEquals(listOf("Second line"), shown.map { it.plainText }, "the cues after the middle wait were dropped")
        harness.close()
    }

    @Test
    fun aReaderThatNeverDeliversIsRefusedAtTheStallLimit() = runTest {
        val io = StutteringIo(emptyList(), zerosForever = true)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 10_000_000),
            config = PlayerConfig(buffer = BufferPolicy(stallTimeout = 2.seconds)),
        )
        harness.core.open(
            MediaItem("scripted://one", externalSubtitles = listOf(SubtitleSource(uri = "memory://never.srt", io = { io }))),
        )
        harness.run(100.milliseconds)
        val warnings = harness.core.warningHistory().map { it.warning }
        val unreadable = warnings.filterIsInstance<PlaybackWarning.SubtitleSourceUnreadable>()
        assertTrue(unreadable.any { "no bytes arrived" in it.reason }, "expected a stall refusal, got $warnings")
        assertTrue(io.zeros > 1, "the loader stopped at the first 0 instead of waiting")
        harness.close()
    }

    @Test
    fun anEastAsianSubtitleIsReadWithTheTableTheParserCarries() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        val asked = mutableListOf<String>()
        harness.backend.textDecoder = { _, encoding ->
            asked += encoding
            if (encoding == "Shift_JIS") "1\n00:00:01,000 --> 00:00:03,000\n\u304A\u306F\u3088\u3046\n\n" else null
        }
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(SubtitleSource(uri = "memory://ja.srt", io = { BytesIo(SHIFT_JIS_SRT) })),
            ),
        )
        harness.run(100.milliseconds)
        assertEquals(listOf("Shift_JIS"), asked, "the engine never handed the bytes to the parser's table")
        val guessed = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.SubtitleCharsetGuessed>()
        assertTrue(guessed.isEmpty(), "a file the table read cleanly is not a guess: $guessed")
        val subtitles = harness.core.snapshots.value.tracks.all.filter { it.kind == TrackKind.Subtitle }
        assertEquals(1, subtitles.size, "the decoded file never became a track")
        harness.close()
    }

    @Test
    fun anEastAsianSubtitleWithNoTableIsNamedInItsWarning() = runTest {
        // The scripted parser carries no tables unless a test gives it one, as a custom backend.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000))
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(SubtitleSource(uri = "memory://ja.srt", io = { BytesIo(SHIFT_JIS_SRT) })),
            ),
        )
        harness.run(100.milliseconds)
        val guessed = harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.SubtitleCharsetGuessed>()
        assertEquals(listOf("Shift_JIS"), guessed.map { it.detected })
        assertEquals("windows-1252", guessed.single().charset)
        harness.close()
    }
}
