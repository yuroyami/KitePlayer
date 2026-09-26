package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native view path reports an attachment once for each attachment, not once per recomposition.
 *
 * The desktop native view is a Swing panel, which a headless Compose scene cannot host, so a plain
 * box stands in for it. Everything else is the path itself.
 */
class RendererAttachedReportTest {

    private val players = mutableListOf<KitePlayer>()

    @AfterTest
    fun closePlayers() {
        players.forEach { it.close() }
        players.clear()
    }

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(backends = Backends(backend = StubMediaBackend, output = StubOutputBackend)),
    ).also { players += it }

    @Test
    fun `a recomposition does not report the attachment again`() {
        val player = player()
        val padding = mutableStateOf(0)
        val reported = mutableListOf<KitePlayer>()
        val scene = ImageComposeScene(40, 40, Density(1f), content = {
            // A new modifier on each change, as a call site whose padding moves passes one.
            NativeViewVideo(player, Modifier.padding(padding.value.dp), surface = { _, placed -> Box(placed) }) {
                reported += it
            }
        })
        try {
            var nanos = 0L
            repeat(4) {
                padding.value = it
                scene.render(nanos)
                nanos += 16_666_667L
            }
        } finally {
            scene.close()
        }
        assertEquals(listOf(player), reported, "four compositions of one attachment")
    }

    @Test
    fun `a different player is reported as a new attachment`() {
        val first = player()
        val second = player()
        val current = mutableStateOf(first)
        val reported = mutableListOf<KitePlayer>()
        val scene = ImageComposeScene(40, 40, Density(1f), content = {
            NativeViewVideo(current.value, Modifier, surface = { _, placed -> Box(placed) }) { reported += it }
        })
        try {
            scene.render(0L)
            current.value = second
            scene.render(16_666_667L)
            scene.render(33_333_334L)
        } finally {
            scene.close()
        }
        assertEquals(listOf(first, second), reported)
    }
}

/** Never opened: these tests attach a view, they never play anything. */
private object StubMediaBackend : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("no media in this test")
}

private object StubOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "stub"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
    override val videoRenderer: VideoRendererFactory? = null
}
