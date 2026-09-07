@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Resume where I was", handed out as one value. Every application rebuilt it by hand from the
 * queue, the index, the position, the speed, the volume and the track choices; the engine knows
 * all of it.
 */
class MementoTest {

    private fun script() = MediaScript(
        durationUs = 20_000_000,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 3, marker = 0.25f, language = "jpn", title = "audio-B"),
        ),
    )

    @Test
    fun `restore on a fresh player reaches the memento's own state`() = runTest {
        val first = CoreHarness(this, script = script())
        val original = KitePlayer(first.core)
        first.attachRenderer()
        original.openQueue(listOf(MediaItem("scripted://one"), MediaItem("scripted://two")), startIndex = 1)
        original.seek(12.seconds)
        original.setSpeed(1.5)
        original.setVolume(0.4f)
        original.setMuted(true)
        original.setLoop(LoopMode.All)
        first.run(100.milliseconds)
        val japanese = original.state.value.tracks.audio.first { it.language == "jpn" }.id
        original.selectTrack(TrackKind.Audio, japanese)
        first.run(100.milliseconds)

        val memento = original.memento()
        assertEquals(1, memento.queueIndex)
        assertEquals(12.seconds, memento.position)
        assertEquals("jpn", memento.audioLanguage, "tracks are remembered by language, not by id")

        val second = CoreHarness(this, script = script())
        val restored = KitePlayer(second.core)
        second.attachRenderer()
        restored.restore(memento)
        second.run(100.milliseconds)

        val snapshot = restored.state.value
        assertEquals(PlaybackStatus.Paused, snapshot.status, "a restore ends paused, like every open")
        assertEquals(listOf("scripted://one", "scripted://two"), snapshot.queue.map { it.uri })
        assertEquals(1, snapshot.queueIndex)
        val landed = restored.position()
        assertTrue(
            (landed - 12.seconds).absoluteValue <= 40.milliseconds,
            "the position must come back within one frame, got $landed",
        )
        assertEquals(1.5, snapshot.speed)
        assertEquals(0.4f, snapshot.volume)
        assertTrue(snapshot.muted)
        assertEquals(LoopMode.All, snapshot.loop)
        val selected = snapshot.tracks.selectedAudio
        assertEquals("jpn", selected?.let { snapshot.tracks.find(it) }?.language, "the audio track was picked by language")
        first.close()
        second.close()
    }

    @Test
    fun `properties round trip for items that carry only strings`() {
        val memento = PlayerMemento(
            queue = listOf(
                MediaItem(
                    uri = "https://example.test/a.mp4",
                    headers = mapOf("Authorization" to "Bearer x", "X-Trace" to "1"),
                    formatHint = "mp4",
                    startPosition = 3.seconds,
                    openOptions = mapOf("probesize" to "1000000"),
                ),
                MediaItem("b.mkv"),
            ),
            queueIndex = 1,
            position = 12.seconds,
            speed = 1.5,
            preservePitch = false,
            volume = 0.4f,
            muted = true,
            loop = LoopMode.All,
            shuffle = true,
            subtitleDelay = 250.milliseconds,
            audioDelay = (-40).milliseconds,
            audioLanguage = "jpn",
            subtitleLanguage = null,
            subtitlesOff = true,
        )

        val properties = memento.asProperties()
        assertEquals(PlayerMemento.FORMAT_VERSION.toString(), properties["version"])
        assertEquals("2", properties["queue.size"])
        assertEquals("Bearer x", properties["queue.0.header.Authorization"])
        assertEquals("1000000", properties["queue.0.option.probesize"])
        assertEquals(memento, PlayerMemento.fromProperties(properties))
    }

    @Test
    fun `a properties map from a newer format is refused`() {
        // Otherwise complete, so the only thing wrong with it is the version: a missing key would
        // trip the same exception and prove nothing about the check.
        val complete = PlayerMemento(
            queue = listOf(MediaItem("a.mp4")),
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
        ).asProperties()
        val failure = assertFailsWith<IllegalArgumentException> {
            PlayerMemento.fromProperties(complete + ("version" to "3"))
        }
        assertTrue("version" in failure.message.orEmpty(), "the refusal names the version: ${failure.message}")
    }

    @Test
    fun `what cannot be written as text is dropped rather than half stored`() {
        val item = MediaItem(
            uri = "label",
            io = { error("never opened here") },
            externalSubtitles = listOf(SubtitleSource("/tmp/x.srt")),
        )
        val memento = PlayerMemento(
            queue = listOf(item),
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
        val back = PlayerMemento.fromProperties(memento.asProperties()).queue.single()
        assertEquals("label", back.uri)
        assertNull(back.io, "a factory cannot be stored, so it must not come back as anything")
        assertEquals(emptyList(), back.externalSubtitles)
    }

    @Test
    fun `restore brings back the balance and the equaliser`() = runTest {
        val first = CoreHarness(this, script = script())
        val original = KitePlayer(first.core)
        first.attachRenderer()
        original.open(MediaItem("scripted://one"))
        original.setBalance(-1f)
        original.setEqualizer(EqualizerSettings(gainsDb = List(10) { 3f }, preampDb = -2f))
        first.run(100.milliseconds)

        val memento = original.memento()
        assertEquals(-1f, memento.balance, "the memento did not even record the balance")

        val second = CoreHarness(this, script = script())
        val restored = KitePlayer(second.core)
        second.attachRenderer()
        restored.restore(memento)
        second.run(100.milliseconds)

        val snapshot = restored.state.value
        assertEquals(-1f, snapshot.balance, "the restored player was back at centre")
        assertEquals(-2f, snapshot.equalizer.preampDb)
        assertEquals(List(10) { 3f }, snapshot.equalizer.gainsDb)
        first.close()
        second.close()
    }

    @Test
    fun `restore brings back every picture and subtitle setting`() = runTest {
        val first = CoreHarness(this, script = script())
        val original = KitePlayer(first.core)
        first.attachRenderer()
        original.open(MediaItem("scripted://one"))
        original.setVideoScale(VideoScale.Fill)
        original.setVideoTransform(VideoTransform(aspectOverride = 1.85f, zoom = 1.2f, panX = 0.1f, panY = -0.1f))
        original.setVideoAdjustments(VideoAdjustments(brightness = 0.2f, contrast = 1.1f))
        original.setRenderQuality(RenderQuality(dither = true, deband = true))
        original.setSubtitleScale(1.4f)
        original.setSubtitlePosition(0.8f)
        original.setSubtitleStyle(SubtitleStyleOverride(fontSizePx = 40f, bold = true))
        original.setVideoEnabled(false)
        first.run(100.milliseconds)

        val second = CoreHarness(this, script = script())
        val restored = KitePlayer(second.core)
        second.attachRenderer()
        restored.restore(original.memento())
        second.run(100.milliseconds)

        val snapshot = restored.state.value
        assertEquals(VideoScale.Fill, snapshot.videoScale)
        assertEquals(1.85f, snapshot.videoTransform.aspectOverride)
        assertEquals(1.2f, snapshot.videoTransform.zoom)
        assertEquals(0.2f, snapshot.videoAdjustments.brightness)
        assertTrue(snapshot.renderQuality.dither && snapshot.renderQuality.deband)
        assertEquals(1.4f, snapshot.subtitleScale)
        assertEquals(0.8f, snapshot.subtitlePosition)
        assertEquals(40f, snapshot.subtitleStyle?.fontSizePx)
        assertEquals(false, snapshot.videoEnabled)
        first.close()
        second.close()
    }

    @Test
    fun `the text form carries every new setting`() {
        val memento = PlayerMemento(
            queue = listOf(MediaItem("a.mp4")),
            queueIndex = 0,
            position = 3.seconds,
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
            secondarySubtitleLanguage = "fra",
            balance = -0.5f,
            equalizer = EqualizerSettings(gainsDb = List(10) { it.toFloat() }, preampDb = 1.5f),
            subtitleScale = 1.3f,
            subtitlePosition = 0.7f,
            subtitleStyle = SubtitleStyleOverride(fontFamily = "Serif", bold = true, primaryColor = -1),
            videoScale = VideoScale.Stretch,
            videoTransform = VideoTransform(aspectOverride = 2.35f, zoom = 1.1f),
            videoAdjustments = VideoAdjustments(saturation = 0.5f),
            renderQuality = RenderQuality(dither = true, scaler = VideoScaler.CatmullRom),
            videoEnabled = false,
        )
        assertEquals(memento, PlayerMemento.fromProperties(memento.asProperties()))
    }

    @Test
    fun `a memento with no subtitle style keeps having none`() {
        val memento = PlayerMemento(
            queue = listOf(MediaItem("a.mp4")),
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
        assertEquals(null, PlayerMemento.fromProperties(memento.asProperties()).subtitleStyle)
    }

    @Test
    fun `an older memento reads back with the new settings at their defaults`() {
        // What an application stored before any of this existed. It must still open, not throw.
        val old = mapOf(
            "version" to "1",
            "queue.size" to "1",
            "queue.0.uri" to "a.mp4",
            "queueIndex" to "0",
            "position" to "0",
            "speed" to "1.0",
            "preservePitch" to "true",
            "volume" to "1.0",
            "muted" to "false",
            "loop" to "Off",
            "shuffle" to "false",
            "subtitleDelay" to "0",
            "audioDelay" to "0",
            "subtitlesOff" to "false",
        )
        val memento = PlayerMemento.fromProperties(old)
        assertEquals(0f, memento.balance)
        assertEquals(EqualizerSettings.Flat, memento.equalizer)
        assertEquals(VideoScale.Fit, memento.videoScale)
        assertEquals(RenderQuality.Off, memento.renderQuality)
        assertEquals(1f, memento.subtitleScale)
        assertTrue(memento.videoEnabled)
    }
}
