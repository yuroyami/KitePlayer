package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The controls option of [KitePlayerVideo], and the video without it left as it was (#469). */
@OptIn(ExperimentalTestApi::class)
class VideoWithControlsTest {

    private val players = mutableListOf<KitePlayer>()

    @AfterTest
    fun closePlayers() {
        players.forEach { it.close() }
        players.clear()
    }

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = Backends(backend = NothingOpens, output = NoSound)),
    ).also { players += it }

    @Test
    fun `the controls draw over the video`() = runComposeUiTest {
        val player = player()
        setContent {
            KitePlayerVideo(player, Modifier.size(320.dp, 180.dp), path = KiteRenderPath.ComposeCanvas, keepDisplayAwake = false) {
                KitePlayerControls(player)
            }
        }
        val video = onNodeWithContentDescription("Video").fetchSemanticsNode().boundsInRoot
        val play = onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
        assertTrue(play.left >= video.left && play.right <= video.right && play.top >= video.top && play.bottom <= video.bottom, "play $play outside the video $video")
        onNodeWithContentDescription("Hide controls").assertExists()
    }

    @Test
    fun `the video without controls draws what it drew before`() {
        // The same video once through the overload that has no controls, and once with empty ones.
        val without = render { player -> KitePlayerVideo(player, Modifier.size(64.dp, 36.dp), path = KiteRenderPath.ComposeCanvas, keepDisplayAwake = false) }
        val empty = render { player ->
            KitePlayerVideo(player, Modifier.size(64.dp, 36.dp), path = KiteRenderPath.ComposeCanvas, keepDisplayAwake = false) {}
        }
        assertEquals(without, empty)
    }

    /** The bounds of the video's node and every pixel drawn, for one composition of [content]. */
    private fun render(content: @androidx.compose.runtime.Composable (KitePlayer) -> Unit): Pair<Rect, List<Int>> {
        var result: Pair<Rect, List<Int>>? = null
        runComposeUiTest {
            val player = player()
            setContent { content(player) }
            val bounds = onNodeWithContentDescription("Video").fetchSemanticsNode().boundsInRoot
            val pixels = onRoot().captureToImage().toPixelMap()
            result = bounds to List(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].hashCode() }
        }
        return checkNotNull(result)
    }
}

private object NothingOpens : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("this backend opens nothing")
}

private object NoSound : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "silent"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
