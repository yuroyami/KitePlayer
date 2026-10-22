package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.EXTERNAL_AUDIO_STREAM_BASE
import io.github.yuroyami.kiteplayer.internal.EXTERNAL_AUDIO_STREAM_SPAN
import io.github.yuroyami.kiteplayer.internal.inspectMedia
import io.github.yuroyami.kiteplayer.internal.isCaptionTrack
import io.github.yuroyami.kiteplayer.internal.openWithExternalAudio
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An item that plays audio files beside its media (#392). Each file is an input with a reader of
 * its own. Its audio tracks are listed after the media's, only the sound heard is read, and a
 * track of an input plays in step with the picture, through a seek and past the input's own end.
 */
class ExternalAudioTest {

    private val dubUri = "scripted://media/dub.m4a"
    private val commentaryUri = "scripted://media/commentary.opus"

    /** The track of the first input, and of the second. */
    private val dub = TrackId(EXTERNAL_AUDIO_STREAM_BASE)
    private val commentary = TrackId(EXTERNAL_AUDIO_STREAM_BASE + EXTERNAL_AUDIO_STREAM_SPAN)

    private fun film(durationUs: Long = 30_000_000, hasAudio: Boolean = true) = MediaScript(durationUs = durationUs, hasAudio = hasAudio)

    /** An audio file: one sound at stream 0, heard as [marker]. */
    private fun sound(marker: Float, durationUs: Long = 30_000_000) =
        MediaScript(durationUs = durationUs, hasVideo = false, audioMarker = marker)

    private fun item(vararg inputs: AudioSource) = MediaItem("scripted://media", externalAudio = inputs.toList())

    /** A harness whose backend answers [dubUri] and [commentaryUri] with audio files of their own. */
    private fun harness(scope: kotlinx.coroutines.test.TestScope, film: MediaScript, dubScript: MediaScript = sound(0.5f)): CoreHarness =
        CoreHarness(scope, script = film).also { harness ->
            harness.backend.scriptFor = { opened ->
                when (opened.uri) {
                    dubUri -> dubScript
                    commentaryUri -> sound(0.25f)
                    else -> null
                }
            }
        }

    private suspend fun CoreHarness.openItem(item: MediaItem) {
        attachRenderer()
        core.open(item)
    }

    /** The sources of the last open, the media's first. A rebuild opens them all again. */
    private fun CoreHarness.sourcesOf(inputs: Int): List<ScriptedSource> =
        backend.sessions.takeLast(inputs + 1).map { it.scriptedSource }

    private fun CoreHarness.leftOut(): List<PlaybackWarning.AudioSourceUnreadable> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.AudioSourceUnreadable>()

    private fun CoreHarness.cuts(): List<PlaybackWarning> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.PathologicalInterleaving>()

    /** A reader with nothing to say, which counts its reads and notes its close. */
    private class CountingReader : MediaIo {
        var reads = 0
        var closed = false
        override val size: Long? get() = null
        override val seekable: Boolean get() = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            return length
        }
        override suspend fun seek(position: Long) = Unit
        override fun close() {
            closed = true
        }
    }

    private fun heardOnly(values: Set<Float>, marker: Float): Boolean =
        values.any { abs(it - marker) < 0.0001f } && values.all { abs(it) < 0.0001f || abs(it - marker) < 0.0001f }

    @Test
    fun theTracksOfEachInputAreListedAfterTheMediasOwn() = runTest {
        val harness = harness(this, film())
        harness.openItem(item(AudioSource(dubUri, title = "Dub", language = "fre"), AudioSource(commentaryUri)))
        val tracks = harness.core.snapshots.value.tracks
        assertEquals(listOf(TrackId(1), dub, commentary), tracks.audio.map { it.id })
        assertEquals(TrackId(1), tracks.selectedAudio, "the media's own sound is the one an open chooses")
        assertTrue(tracks.audio.none { it.id != TrackId(1) && it.isDefault }, "an input's track is listed as the default sound")
        val listed = tracks.audio.first { it.id == dub }
        assertEquals("Dub", listed.title)
        assertEquals("fre", listed.language)
        assertEquals("scripted audio A", tracks.audio.first { it.id == commentary }.title, "an input with no title keeps its track's own")
        assertTrue(tracks.all.none { isCaptionTrack(it.id.value) }, "an input's track was taken for a caption track")
        assertEquals(listOf(0), tracks.video.map { it.id.value }, "only the sound of an input is the item's")
        harness.core.play()
        harness.run(2.seconds)
        val (media, first, second) = harness.sourcesOf(2)
        assertTrue(media.reads > 0)
        assertEquals(0, first.reads + second.reads, "an input nobody hears was read")
        assertEquals(0, first.selectCalls + second.selectCalls)
        harness.close()
    }

    @Test
    fun aTrackOfAnInputPlaysWithThePictureGoingOn() = runTest {
        val media = film()
        val harness = harness(this, media)
        harness.openItem(item(AudioSource(dubUri), AudioSource(commentaryUri)))
        harness.core.play()
        harness.run(3.seconds)
        val statuses = harness.core.statusHistory.size
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, commentary))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.25f), "heard ${harness.sink.audibleValues} after the switch")
        assertEquals(commentary, harness.core.snapshots.value.tracks.selectedAudio)
        val (film, first, second) = harness.sourcesOf(2)
        assertTrue(1 !in film.selectionHistory.last(), "the media's sound is still read: ${film.selectionHistory.last()}")
        assertEquals(0, first.reads, "the input nobody chose was read")
        assertTrue(second.reads > 0)
        assertEquals(emptyList(), harness.core.statusHistory.drop(statuses), "the switch changed the status")
        // To the other input, and back to the media's own sound.
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, dub))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.5f), "heard ${harness.sink.audibleValues} after the second switch")
        val readOfTheInputLeft = second.reads
        harness.run(2.seconds)
        assertEquals(readOfTheInputLeft, second.reads, "the input left behind is still read")
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, TrackId(1)))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 1f), "heard ${harness.sink.audibleValues} back on the media's sound")
        harness.run(30.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        val shown = harness.renderer!!.timestamps.map { it.micros }
        assertEquals((0 until 750).map { it * 40_000L }, shown, "a picture was lost, or shown twice")
        val decoded = media.videoProbe.decodedUs
        assertEquals(decoded.sorted().distinct(), decoded, "a picture read again was decoded again")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        assertEquals(0, harness.ledger.liveCount, "a packet read ahead was never closed")
        harness.close()
    }

    @Test
    fun aPictureWithNoSoundOfItsOwnPlaysTheInputsSound() = runTest {
        val harness = harness(this, film(hasAudio = false))
        harness.openItem(item(AudioSource(dubUri)))
        assertEquals(dub, harness.core.snapshots.value.tracks.selectedAudio)
        harness.core.play()
        harness.run(2.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.5f), "heard ${harness.sink.audibleValues}")
        harness.run(30.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(750, harness.renderer!!.timestamps.size, "pictures were lost")
        val heard = harness.sink.framesPlayed
        assertTrue(heard >= 30L * 48_000 - 4_800, "sound was lost: heard $heard of ${30 * 48_000} frames")
        assertTrue(PlaybackStatus.Buffering !in harness.core.statusHistory, "${harness.core.statusHistory}")
        assertTrue(harness.cuts().isEmpty(), "the two readers were not read in step: ${harness.cuts()}")
        harness.close()
    }

    @Test
    fun aSeekMovesTheInputToWhereTheMediaLanded() = runTest {
        val harness = harness(this, film(hasAudio = false))
        harness.openItem(item(AudioSource(dubUri)))
        harness.core.play()
        harness.run(2.seconds)
        harness.core.seek(Pts(10_300_000), SeekMode.Precise)
        val (media, input) = harness.sourcesOf(1)
        assertEquals(1, media.seeks)
        assertEquals(1, input.seeks, "the input was not moved with the media")
        // The media lands on the keyframe before the target, and the input is sent there.
        assertEquals(listOf(media.seekTargets.single() / 400_000 * 400_000), input.seekTargets)
        val shownBefore = harness.renderer!!.timestamps.size
        val heardBefore = harness.sink.framesPlayed
        harness.sink.audibleValues.clear()
        harness.run(2.seconds)
        val position = harness.core.position().inWholeMicroseconds
        assertTrue(abs(position - 12_300_000) < 300_000, "2 s after the seek to 10.3 s, the position is $position")
        // A seek changes the sign and the scale of the scripted samples, so any sound counts.
        assertTrue(harness.sink.audibleValues.any { it != 0f }, "no sound after the seek: ${harness.sink.audibleValues}")
        val heard = harness.sink.framesPlayed - heardBefore
        assertTrue(abs(heard - 2L * 48_000) < 9_600, "heard $heard frames in the 2 s after the seek")
        val shown = harness.renderer!!.timestamps.drop(shownBefore).map { it.micros }
        assertTrue(shown.size >= 45 && shown.first() >= 10_260_000, "the picture after the seek: $shown")
        harness.run(20.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "a packet read ahead before the seek was never closed")
    }

    @Test
    fun aSeekBackBringsAnInputThatEndedBackIntoTheSound() = runTest {
        // Ten minutes of picture, so the reads of the media never reach its end here.
        val harness = harness(this, film(durationUs = 600_000_000, hasAudio = false), dubScript = sound(0.5f, durationUs = 3_000_000))
        harness.openItem(item(AudioSource(dubUri)))
        harness.core.play()
        harness.run(6.seconds)
        harness.core.seek(Pts(1_000_000), SeekMode.Precise)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.sink.audibleValues.any { it != 0f }, "the input stayed silent after the seek back")
        harness.close()
    }

    @Test
    fun aSeekDropsThePacketsReadAheadBeforeIt() = runTest {
        val ledger = LeakLedger()
        val backend = ScriptedBackend(film(hasAudio = false), ledger)
        backend.scriptFor = { opened -> if (opened.uri == dubUri) sound(0.5f) else null }
        val session = openWithExternalAudio(backend, item(AudioSource(dubUri)))
        val source = session.source
        source.selectStreams(setOf(0, dub.value))
        repeat(10) { assertNotNull(source.readPacket()).close() }
        source.seekToKeyframe(Pts(10_000_000))
        val after = List(10) { assertNotNull(source.readPacket()).also { it.close() }.pts?.micros ?: 0L }
        assertTrue(after.all { it >= 9_500_000 }, "a packet from before the seek came out after it: $after")
        session.close()
        assertEquals(0, ledger.liveCount, "a packet read ahead before the seek was never closed")
    }

    @Test
    fun anInputReadAgainStartsWhereTheReadsAre() = runTest {
        val backend = ScriptedBackend(film(hasAudio = false))
        backend.scriptFor = { opened -> if (opened.uri == dubUri) sound(0.5f) else null }
        val session = openWithExternalAudio(backend, item(AudioSource(dubUri)))
        val source = session.source
        source.selectStreams(setOf(0))
        var readTo = 0L
        while (readTo < 5_000_000) {
            val packet = assertNotNull(source.readPacket())
            readTo = packet.pts?.micros ?: readTo
            packet.close()
        }
        source.selectStreams(setOf(0, dub.value))
        var firstOfTheInput: Long? = null
        while (firstOfTheInput == null) {
            val packet = assertNotNull(source.readPacket())
            if (packet.streamIndex == dub.value) firstOfTheInput = packet.pts?.micros
            packet.close()
        }
        assertTrue(abs(firstOfTheInput - 5_000_000) < 500_000, "the input came in at $firstOfTheInput, the reads were at $readTo")
        session.close()
    }

    @Test
    fun anInputThatEndsEarlyLeavesThePicturePlaying() = runTest {
        val harness = harness(this, film(durationUs = 40_000_000, hasAudio = false), dubScript = sound(0.5f, durationUs = 3_000_000))
        harness.openItem(item(AudioSource(dubUri)))
        harness.core.play()
        harness.run(20.seconds)
        val midway = harness.core.position().inWholeMicroseconds
        assertTrue(abs(midway - 20_000_000) < 500_000, "20 s in, the position is $midway")
        harness.run(25.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(1000, harness.renderer!!.timestamps.size, "pictures were lost")
        val heard = harness.sink.framesPlayed
        assertTrue(abs(heard - 3L * 48_000) < 4_800, "heard $heard frames of a 3 s input")
        assertTrue(PlaybackStatus.Buffering !in harness.core.statusHistory, "${harness.core.statusHistory}")
        assertTrue(harness.cuts().isEmpty(), "${harness.cuts()}")
        assertEquals(dub, harness.core.snapshots.value.tracks.selectedAudio, "the sound stays the track chosen")
        harness.close()
    }

    @Test
    fun anInputThatCannotOpenIsLeftOutWithOneWarning() = runTest {
        val harness = harness(this, film())
        harness.backend.openFailureFor = { opened -> if (opened.uri == dubUri) IllegalStateException("no such file") else null }
        harness.openItem(item(AudioSource(dubUri), AudioSource(commentaryUri)))
        val warning = harness.leftOut().single()
        assertEquals(dubUri, warning.uri)
        assertTrue("no such file" in warning.reason, warning.reason)
        // The input that opened keeps the index its place in the item gives it.
        assertEquals(listOf(TrackId(1), commentary), harness.core.snapshots.value.tracks.audio.map { it.id })
        harness.core.play()
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertTrue(heardOnly(harness.sink.audibleValues, 1f), "heard ${harness.sink.audibleValues}")
        assertEquals(1, harness.leftOut().size)
        harness.close()
    }

    @Test
    fun anItemWhoseOnlyInputCannotOpenPlaysAsIfItNamedNone() = runTest {
        val harness = harness(this, film())
        harness.backend.openFailureFor = { opened -> if (opened.uri == dubUri) IllegalStateException("no such file") else null }
        harness.openItem(item(AudioSource(dubUri)))
        assertEquals(1, harness.leftOut().size)
        assertEquals(listOf(TrackId(1)), harness.core.snapshots.value.tracks.audio.map { it.id })
        harness.core.play()
        harness.run(31.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(750, harness.renderer!!.timestamps.size)
        harness.close()
    }

    @Test
    fun anInputWithNoSoundIsLeftOut() = runTest {
        val harness = harness(this, film(), dubScript = MediaScript(hasAudio = false))
        harness.openItem(item(AudioSource(dubUri)))
        assertTrue("no audio track" in harness.leftOut().single().reason)
        assertEquals(listOf(0), harness.core.snapshots.value.tracks.video.map { it.id.value })
        // The session of the input left out was closed at the open.
        assertEquals(1, harness.backend.sessions[1].closeCount)
        harness.close()
    }

    @Test
    fun aStreamTheMediaAnnouncesLaterKeepsTheTracksOfTheInputs() = runTest {
        val late = MediaScript(
            durationUs = 30_000_000,
            additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 0.75f, appearsAtUs = 3_000_000)),
            // A live sender, so the open cannot read ahead to the late stream.
            live = true,
        )
        val harness = harness(this, late)
        harness.openItem(item(AudioSource(dubUri)))
        assertEquals(listOf(TrackId(1), dub), harness.core.snapshots.value.tracks.audio.map { it.id })
        harness.core.play()
        harness.run(6.seconds)
        // The media's new list names its own streams only. The input's track stays listed.
        assertEquals(setOf(TrackId(1), TrackId(2), dub), harness.core.snapshots.value.tracks.audio.map { it.id }.toSet())
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, dub))
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(heardOnly(harness.sink.audibleValues, 0.5f), "heard ${harness.sink.audibleValues}")
        harness.close()
    }

    @Test
    fun anInputIsReadThroughTheItemsResolverWithItsHeadersOnlyOnTheItemsOwnServer() = runTest {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val readers = mutableListOf<CountingReader>()
        val resolver = object : MediaIoResolver {
            override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())
            override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
                asked += uri to headers
                return CountingReader().also { readers += it }
            }
        }
        val harness = CoreHarness(
            this,
            script = film(hasAudio = false),
            config = PlayerConfig(network = NetworkConfig(ioResolver = resolver)),
        )
        harness.backend.scriptFor = { opened -> if (opened.uri.endsWith(".m4a")) sound(0.5f) else null }
        val headers = mapOf("Authorization" to "Bearer token")
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "https://cdn.example/film.mp4",
                headers = headers,
                externalAudio = listOf(
                    AudioSource("https://cdn.example/dub.m4a"),
                    AudioSource("https://other.example/commentary.m4a"),
                ),
            ),
        )
        assertEquals(
            listOf(
                "https://cdn.example/film.mp4" to headers,
                "https://cdn.example/dub.m4a" to headers,
                "https://other.example/commentary.m4a" to emptyMap(),
            ),
            asked,
        )
        harness.core.play()
        harness.run(2.seconds)
        // The first input is the sound heard, and the scripted backend read it through its reader.
        assertTrue(readers[1].reads > 0, "the input's reader was never read")
        assertEquals(0, readers[2].reads, "the reader of an input nobody hears was read")
        harness.close()
        assertEquals(listOf(true, true), readers.drop(1).map { it.closed }, "the reader of an input was left open")
    }

    @Test
    fun anInputThatCannotOpenClosesTheReaderMadeForIt() = runTest {
        val readers = mutableListOf<CountingReader>()
        val harness = CoreHarness(
            this,
            script = film(),
            config = PlayerConfig(network = NetworkConfig(ioResolver = MediaIoResolver { CountingReader().also { readers += it } })),
        )
        harness.backend.openFailureFor = { opened -> if (opened.uri == dubUri) IllegalStateException("no such file") else null }
        harness.openItem(item(AudioSource(dubUri)))
        assertEquals(1, harness.leftOut().size)
        assertTrue(readers[1].closed, "the reader of the input left out stayed open")
        harness.close()
    }

    @Test
    fun closingTheItemClosesEveryInput() = runTest {
        // Ten minutes, so the reads are far from the end and one packet is read ahead at the close.
        val harness = harness(this, film(durationUs = 600_000_000), dubScript = sound(0.5f, durationUs = 600_000_000))
        harness.openItem(item(AudioSource(dubUri), AudioSource(commentaryUri)))
        harness.core.play()
        harness.run(1.seconds)
        assertIs<TrackChange.Applied>(harness.core.selectTrack(TrackKind.Audio, dub))
        harness.run(1.seconds)
        harness.close()
        assertEquals(listOf(1, 1, 1), harness.backend.sessions.map { it.closeCount })
        assertEquals(0, harness.ledger.liveCount, "a packet read ahead was never closed")
    }

    @Test
    fun anInspectionListsTheTracksOfEachInput() = runTest {
        val harness = harness(this, film())
        val inspection = inspectMedia(harness.backend, item(AudioSource(dubUri, language = "fre")))
        assertEquals(listOf(TrackId(1), dub), inspection.tracks.audio.map { it.id })
        assertEquals(listOf(1, 1), harness.backend.sessions.map { it.closeCount })
        harness.close()
    }

    @Test
    fun theInputsOfAnItemAreItsOwnInTextAndInAMemento() {
        val media = mediaItem("film.mkv") {
            externalAudio(AudioSource("https://cdn.example/dub.m4a?token=secret", title = "Dub", language = "fre"))
            externalAudio(listOf(AudioSource("commentary.opus")))
        }
        assertEquals(
            listOf(AudioSource("https://cdn.example/dub.m4a?token=secret", "Dub", "fre"), AudioSource("commentary.opus")),
            media.externalAudio,
        )
        assertTrue("externalAudio=2" in media.toString(), media.toString())
        assertTrue("secret" !in media.toString(), "a printed item gave away an input's address: $media")
        val memento = PlayerMemento(
            queue = listOf(media, MediaItem("plain.mkv"), media.copy(externalAudio = listOf(AudioSource("x.flac", io = { error("never opened") })))),
            queueIndex = 0,
            position = 0.seconds,
            speed = 1.0,
            preservePitch = true,
            volume = 1f,
            muted = false,
            loop = LoopMode.Off,
            shuffle = false,
            subtitleDelay = 0.seconds,
            audioDelay = 0.seconds,
            audioLanguage = null,
            subtitleLanguage = null,
            subtitlesOff = false,
        )
        val back = PlayerMemento.fromProperties(memento.asProperties()).queue
        assertEquals(media.externalAudio, back[0].externalAudio)
        assertEquals(emptyList(), back[1].externalAudio)
        assertEquals("x.flac", back[2].externalAudio.single().uri)
        assertNull(back[2].externalAudio.single().io, "a reader cannot be stored")
    }
}
