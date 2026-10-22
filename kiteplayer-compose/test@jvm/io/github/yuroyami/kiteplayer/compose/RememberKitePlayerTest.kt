package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The composition owns the player: one player across recompositions, closed when the call leaves. */
class RememberKitePlayerTest {

    private val stubs = PlayerConfig(backends = Backends(backend = StubMediaBackend, output = StubOutputBackend))

    @Test
    fun theSamePlayerSurvivesRecomposition() {
        val tick = mutableStateOf(0)
        val seen = mutableListOf<KitePlayer>()
        val scene = ImageComposeScene(20, 20, Density(1f), content = {
            tick.value
            seen += rememberKitePlayer(stubs)
        })
        try {
            repeat(3) {
                tick.value = it + 1
                scene.render(it * 16_666_667L)
            }
        } finally {
            scene.close()
        }
        assertEquals(1, seen.distinct().size, "one player for every composition")
    }

    @Test
    fun thePlayerClosesWhenTheCallLeaves() {
        val shown = mutableStateOf(true)
        var built: KitePlayer? = null
        val scene = ImageComposeScene(20, 20, Density(1f), content = {
            if (shown.value) built = rememberKitePlayer(stubs)
        })
        try {
            scene.render(0L)
            val player = checkNotNull(built)
            player.play()
            shown.value = false
            scene.render(16_666_667L)
            assertSame(player, built)
            // A closed player refuses every command.
            assertFailsWith<IllegalStateException> { player.play() }
        } finally {
            scene.close()
        }
    }
}

/** Never opened: these tests only build and close players. */
private object StubMediaBackend : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("no media in this test")
}

private object StubOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "stub"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
