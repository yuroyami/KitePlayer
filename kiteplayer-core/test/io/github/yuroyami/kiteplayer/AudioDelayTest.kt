package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Which way the audio delay moves the picture, and which values it takes. */
class AudioDelayTest {

    /** The newest frame shown after [playFor] of play under [delay], in microseconds. */
    private suspend fun shownAfter(scope: TestScope, delay: Duration, playFor: Duration = 3.seconds): Long {
        val harness = CoreHarness(scope, script = MediaScript(durationUs = 30_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.setAudioDelay(delay)
        player.play()
        harness.run(playFor)
        val shown = harness.renderer!!.timestamps.last().micros
        harness.close()
        return shown
    }

    @Test
    fun `a positive audio delay shows the picture ahead of the sound`() = runTest {
        val none = shownAfter(this, Duration.ZERO)
        val ahead = shownAfter(this, 200.milliseconds)
        val behind = shownAfter(this, (-200).milliseconds)
        assertTrue(abs(ahead - none - 200_000) <= 50_000, "a positive delay moved the picture by ${ahead - none} us")
        assertTrue(abs(none - behind - 200_000) <= 50_000, "a negative delay moved the picture by ${behind - none} us")
    }

    @Test
    fun `both delays refuse a value that is not finite or past the bound`() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://delays"))
        for (bad in listOf(Duration.INFINITE, -Duration.INFINITE, 2.hours, (-2).hours)) {
            assertFailsWith<IllegalArgumentException> { player.setAudioDelay(bad) }
            assertFailsWith<IllegalArgumentException> { player.setSubtitleDelay(bad) }
            assertFailsWith<IllegalArgumentException> { SubtitleConfig(delay = bad) }
        }
        player.setAudioDelay(-KitePlayer.DELAY_MAX)
        player.setSubtitleDelay(KitePlayer.DELAY_MAX)
        harness.run(100.milliseconds)
        assertEquals(-KitePlayer.DELAY_MAX, player.state.value.audioDelay)
        assertEquals(KitePlayer.DELAY_MAX, player.state.value.subtitleDelay)
        harness.close()
    }

    @Test
    fun `a memento with a delay past the bound is refused before anything changes`() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        harness.attachRenderer()
        val memento = PlayerMemento(
            queue = listOf(MediaItem("scripted://one")),
            queueIndex = 0,
            position = Duration.ZERO,
            speed = 1.0,
            preservePitch = true,
            volume = 1f,
            muted = false,
            loop = LoopMode.Off,
            shuffle = false,
            subtitleDelay = Duration.ZERO,
            audioDelay = Long.MAX_VALUE.milliseconds,
            audioLanguage = null,
            subtitleLanguage = null,
            subtitlesOff = false,
        )
        assertFailsWith<IllegalArgumentException> { player.restore(memento) }
        harness.run(100.milliseconds)
        assertEquals(0, harness.backend.openCalls, "a refused restore reached the backend")
        harness.close()
    }
}
