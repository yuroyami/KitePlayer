package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test

/** What a screen reader reads from the Compose canvas path: the label and the player's state (#307). */
@OptIn(ExperimentalTestApi::class)
class CanvasVideoAccessibilityTest {

    private val players = mutableListOf<KitePlayer>()

    @AfterTest
    fun closePlayers() {
        players.forEach { it.close() }
        players.clear()
    }

    @Test
    fun `the canvas says it is the video and follows the player's status`() = runComposeUiTest {
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(backend = RefusingMediaBackend, output = SilentOutputBackend)),
        ).also { players += it }
        setContent {
            KitePlayerVideo(player, Modifier.size(64.dp, 36.dp), path = KiteRenderPath.ComposeCanvas, keepDisplayAwake = false)
        }
        onNodeWithContentDescription("Video").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No media"))

        // The backend refuses every open, so the status moves on to Failed with no media to play.
        runBlocking {
            runCatching { player.open(MediaItem("refused.mp4")) }
            withTimeout(5_000) { player.state.first { it.status == PlaybackStatus.Failed } }
        }
        waitForIdle()
        onNodeWithContentDescription("Video").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed"))
    }

    /** An app that is not in English passes its own words, as the views allow (#309). */
    @Test
    fun `the canvas says what the application passed`() = runComposeUiTest {
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(backend = RefusingMediaBackend, output = SilentOutputBackend)),
        ).also { players += it }
        setContent {
            KitePlayerVideo(
                player,
                Modifier.size(64.dp, 36.dp),
                path = KiteRenderPath.ComposeCanvas,
                keepDisplayAwake = false,
                accessibilityVideoLabel = "Vídeo",
                accessibilityStateFormat = { status, _, _ -> if (status == PlaybackStatus.Idle) "Sin contenido" else "Otro" },
            )
        }
        onNodeWithContentDescription("Vídeo").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sin contenido"))
    }
}

private object RefusingMediaBackend : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("this backend opens nothing")
}

private object SilentOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "silent"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
