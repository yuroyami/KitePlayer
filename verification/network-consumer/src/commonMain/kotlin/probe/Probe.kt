package probe

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.IoCachePolicy
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.NetworkConfig
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.withTimeout

/** Only core symbols: dependency presence must activate transport even after release optimization. */
suspend fun runProbe(args: Array<String>) = withTimeout(20_000) {
    val url = args.getOrNull(0)?.takeIf(String::isNotBlank) ?: "http://127.0.0.1:8765/media"
    val expectedIo = args.getOrNull(1)?.takeIf(String::isNotBlank)?.toBooleanStrict() ?: true
    var opened = false
    var observedIo: MediaIo? = null
    var received = 0
    val backend = object : MediaBackend {
        override suspend fun open(media: MediaItem): BackendSession {
            opened = true
            println("NETWORK_PROBE_STAGE backend")
            val reader = media.io?.invoke()
            observedIo = reader
            if (reader != null) {
                println("NETWORK_PROBE_STAGE read")
                received = reader.read(ByteArray(32), 0, 32)
                println("NETWORK_PROBE_STAGE bytes=$received")
                check(received > 0) { "automatic reader returned no response bytes" }
            }
            // This fixture owns no decoder. The failed open also exercises reader cleanup.
            error("probe deliberately stops after observing transport")
        }
    }
    val output = object : OutputBackend {
        override val clock: MonotonicClock = MonotonicClock.System
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "unused probe output"
            override suspend fun create(): AudioSink = error("probe must not create audio")
        }
        override val videoRenderer: VideoRendererFactory? = null
    }
    val player = KitePlayer.create(PlayerConfig(
        backends = Backends(backend, output),
        network = NetworkConfig(ioCache = IoCachePolicy(enabled = false)),
    ))
    try {
        println("NETWORK_PROBE_STAGE open")
        val failure = runCatching { player.open(MediaItem(url)) }.exceptionOrNull()
        check(failure != null) { "the decoder-free probe unexpectedly opened media" }
        check(opened) { "transport failed before the backend could inspect it: $failure" }
        check((observedIo != null) == expectedIo) {
            "expected automatic IO=$expectedIo but observed IO=${observedIo != null}"
        }
        // A failed body read is also an open failure; it must not satisfy the deliberate-stop check.
        check(!expectedIo || received > 0) { "automatic reader delivered no response bytes: $failure" }
        observedIo?.let { reader ->
            check(runCatching { reader.read(ByteArray(1), 0, 1) }.isFailure) {
                "the failed open left its automatic reader alive"
            }
        }
        println("NETWORK_PROBE_OK expectedIo=$expectedIo observedIo=${observedIo != null} bytes=$received")
    } finally {
        println("NETWORK_PROBE_STAGE close")
        player.closeAndAwait()
    }
}
