@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * One cue of short Lithuanian in windows-1257 whose only accent is ė, which windows-1252 has as
 * Albanian ë. Nothing in the bytes says which of the two the file is, so the guess shows the Western
 * reading and says it guessed.
 */
private val LITHUANIAN_SRT: ByteArray =
    "1\n00:00:00,000 --> 00:00:05,000\n".encodeToByteArray() +
        hex("54eb74eb206e75eb6a6f206e616d6f2e") +
        "\n\n".encodeToByteArray()

private const val LITHUANIAN = "Tėtė nuėjo namo."

/** The same bytes read as windows-1252, where ė is ë. */
private const val AS_WESTERN = "Tëtë nuëjo namo."

/** A subtitle file's bytes, which wait for [gate] before the first read when there is one. */
private class FileIo(private val bytes: ByteArray, private val gate: CompletableDeferred<Unit>? = null) : MediaIo {
    override val size: Long = bytes.size.toLong()
    override val seekable: Boolean = true
    private var at = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        gate?.await()
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

/**
 * An application chooses the encoding of an external subtitle file (#515).
 *
 * The guess cannot tell a short Lithuanian file in windows-1257 from Albanian in windows-1252, so it
 * says it guessed and shows ë for ė. An application can name the encoding with the source, read a
 * loaded file again in another one, or set one for every file that is not Unicode, as VLC and mpv
 * let a viewer do.
 */
class SubtitleEncodingChoiceTest {

    private val script = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(SubtitleCue.Text(0, 5_000_000, listOf(StyledSpan("from the container")))),
    )

    private fun CoreHarness.texts(): List<String> =
        core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    private fun CoreHarness.guesses(): List<PlaybackWarning.SubtitleCharsetGuessed> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.SubtitleCharsetGuessed>()

    private suspend fun CoreHarness.playing() {
        openWithRenderer()
        core.play()
        run(300.milliseconds)
    }

    private fun lithuanian(encoding: String? = null, io: () -> MediaIo = { FileIo(LITHUANIAN_SRT) }) =
        SubtitleSource(uri = "memory://episode.srt", encoding = encoding, io = { io() })

    @Test
    fun aFileGivenItsEncodingReadsRightAndRaisesNoWarning() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.playing()
        harness.core.addExternalSubtitle(lithuanian(encoding = "windows-1257"))
        harness.run(200.milliseconds)
        assertEquals(listOf(LITHUANIAN), harness.texts())
        assertEquals(emptyList(), harness.guesses(), "a named encoding is not a guess")
        harness.close()
    }

    @Test
    fun aFileGivenAnEncodingItIsNotInStillLoadsReadAsTold() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.playing()
        harness.core.addExternalSubtitle(lithuanian(encoding = "windows-1252"))
        harness.run(200.milliseconds)
        assertEquals(listOf(AS_WESTERN), harness.texts())
        assertEquals(emptyList(), harness.guesses())
        harness.close()
    }

    @Test
    fun aReloadInTheRightEncodingKeepsTheTrackInItsPlaceAndPlaying() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.playing()
        val id = harness.core.addExternalSubtitle(lithuanian())
        harness.run(200.milliseconds)
        assertEquals(listOf(AS_WESTERN), harness.texts(), "the premise: the guess reads it as Western")
        assertEquals("windows-1252", harness.guesses().single().charset)

        val before = harness.core.snapshots.value.tracks
        val position = harness.core.progress.value.position
        harness.core.reloadExternalSubtitle(id, "windows-1257")
        harness.run(200.milliseconds)

        assertEquals(listOf(LITHUANIAN), harness.texts())
        val after = harness.core.snapshots.value
        assertEquals(before.all.map { it.id }, after.tracks.all.map { it.id }, "the same tracks in the same order")
        assertEquals(id, after.tracks.selectedSubtitle)
        assertEquals(PlaybackStatus.Playing, after.status)
        assertTrue(harness.core.progress.value.position >= position, "playback went back to ${harness.core.progress.value.position}")

        // No encoding decides from the bytes again.
        harness.core.reloadExternalSubtitle(id, null)
        harness.run(200.milliseconds)
        assertEquals(listOf(AS_WESTERN), harness.texts())
        harness.close()
    }

    @Test
    fun theSecondarySlotTakesTheNewReadingToo() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.playing()
        val id = harness.core.addExternalSubtitle(lithuanian())
        val container = harness.core.snapshots.value.tracks.all.first { it.kind == TrackKind.Subtitle && it.id != id }.id
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Subtitle, container))
        assertIs<TrackChange.Applied>(harness.core.selectSecondarySubtitle(id))
        harness.run(200.milliseconds)
        assertEquals(listOf("from the container", AS_WESTERN), harness.texts())

        harness.core.reloadExternalSubtitle(id, "cp1257")
        harness.run(200.milliseconds)
        assertEquals(listOf("from the container", LITHUANIAN), harness.texts())
        assertEquals(id, harness.core.snapshots.value.tracks.selectedSecondarySubtitle)
        harness.close()
    }

    @Test
    fun aReloadThatCannotBeReadLeavesTheTrackAsItWas() = runTest {
        var gone = false
        val harness = CoreHarness(this, script = script)
        harness.playing()
        val id = harness.core.addExternalSubtitle(
            lithuanian { if (gone) throw IllegalStateException("the file was deleted") else FileIo(LITHUANIAN_SRT) },
        )
        harness.run(200.milliseconds)

        // The scripted parser has no East Asian tables unless a test gives it some.
        val noTable = assertFailsWith<IllegalArgumentException> { harness.core.reloadExternalSubtitle(id, "Shift_JIS") }
        assertTrue("Shift_JIS" in noTable.message.orEmpty(), noTable.message)
        gone = true
        assertFailsWith<IllegalArgumentException> { harness.core.reloadExternalSubtitle(id, "windows-1257") }

        harness.run(200.milliseconds)
        assertEquals(listOf(AS_WESTERN), harness.texts())
        assertEquals(id, harness.core.snapshots.value.tracks.selectedSubtitle)
        harness.close()
    }

    @Test
    fun onlyAnExternalTrackOfTheOpenMediaCanBeReloaded() = runTest {
        val harness = CoreHarness(this, script = script)
        assertFailsWith<IllegalStateException> { harness.core.reloadExternalSubtitle(TrackId(-1), null) }
        harness.playing()
        val container = harness.core.snapshots.value.tracks.all.first { it.kind == TrackKind.Subtitle }.id
        assertFailsWith<IllegalArgumentException> { harness.core.reloadExternalSubtitle(container, "windows-1257") }
        assertFailsWith<IllegalArgumentException> { harness.core.reloadExternalSubtitle(TrackId(-7), null) }
        harness.close()
    }

    @Test
    fun aLaterReloadReplacesOneStillReading() = runTest {
        var opens = 0
        val gate = CompletableDeferred<Unit>()
        val harness = CoreHarness(this, script = script)
        harness.playing()
        // The first reload is the second open of the file, and it waits.
        val id = harness.core.addExternalSubtitle(lithuanian { opens++; FileIo(LITHUANIAN_SRT, gate.takeIf { opens == 2 }) })
        val first = async { runCatching { harness.core.reloadExternalSubtitle(id, "windows-1251") } }
        harness.run(50.milliseconds)
        val second = async { runCatching { harness.core.reloadExternalSubtitle(id, "windows-1257") } }
        harness.run(200.milliseconds)

        assertIs<IllegalStateException>(first.await().exceptionOrNull())
        assertTrue(second.await().isSuccess, "${second.await()}")
        assertEquals(listOf(LITHUANIAN), harness.texts())
        gate.complete(Unit)
        harness.run(200.milliseconds)
        assertEquals(listOf(LITHUANIAN), harness.texts(), "the replaced reading landed after all")
        harness.close()
    }

    @Test
    fun theFallbackEncodingReadsEveryFileThatIsNotUnicode() = runTest {
        val config = PlayerConfig { subtitles { fallbackEncoding = "windows-1257" } }
        val harness = CoreHarness(this, script = script, config = config)
        harness.playing()
        harness.core.addExternalSubtitle(lithuanian())
        harness.run(200.milliseconds)
        assertEquals(listOf(LITHUANIAN), harness.texts())
        assertEquals(emptyList(), harness.guesses(), "the application's own choice is not a guess")

        // A UTF-8 file is still read as UTF-8.
        val utf8 = "1\n00:00:00,000 --> 00:00:05,000\nZażółć gęślą jaźń\n\n"
        harness.core.addExternalSubtitle(SubtitleSource(uri = "memory://utf8.srt", io = { FileIo(utf8.encodeToByteArray()) }))
        harness.run(200.milliseconds)
        assertEquals(listOf("Zażółć gęślą jaźń"), harness.texts())
        harness.close()
    }
}
