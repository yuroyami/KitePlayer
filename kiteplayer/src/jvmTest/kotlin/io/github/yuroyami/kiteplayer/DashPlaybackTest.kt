package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player, not just its packet reader, plays separate DASH audio and video sets (#295).
 * Real HTTP, DASH translation, FFmpeg codecs and engine feed a silent clock-paced sound device
 * and a screen that reads the decoded pixels. Both continue at 60 s after a precise seek.
 */
class DashPlaybackTest {

    @Test
    fun separateSetsReachTheSoundDeviceAndScreenBeforeAndAfterASeek() = runBlocking {
        val media = requireTestMedia(
            sequenceOf(
                System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, "dash") },
                File("testmedia/dash"),
                File("../testmedia/dash"),
            ).filterNotNull().firstOrNull { File(it, "separate.mpd").isFile },
            "testmedia/dash/separate.mpd is missing; run scripts/testmedia.sh",
        )
        // This fixture is never optional in testmedia.sh. A partial fixture must fail, not skip.
        val required = listOf("separate.mpd") + (0..2).flatMap { representation ->
            listOf("separate-$representation-init.m4s") + (1..35).map { segment ->
                "separate-$representation-${segment.toString().padStart(5, '0')}.m4s"
            }
        }
        assertEquals(emptyList(), required.filterNot { File(media, it).isFile }, "the separate DASH fixture is incomplete")

        FixtureServer(media).use { server ->
            val client = HttpClient()
            val resolver = KtorMediaIoResolver(
                client, policy = HttpReaderPolicy(connectTimeout = 5.seconds, readTimeout = 5.seconds, maxReconnects = 0),
            )
            val backend = ObservedBackend()
            val output = CapturingOutput()
            val screen = CapturingScreen()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(backend, output),
                    network = NetworkConfig(ioResolver = resolver),
                    hardwareDecode = HwdecPolicy.Off,
                    buffer = BufferPolicy(totalDuration = 4.seconds, stallTimeout = 10.seconds),
                    progressInterval = 20.milliseconds,
                ),
            )
            try {
                withTimeout(15.seconds) {
                    player.attachRendererAndAwait(screen)
                    player.open(MediaItem("${server.root}/separate.mpd"))
                }
                assertNotNull(player.state.value.tracks.selectedAudio, "the separate audio set was not selected")
                assertTrue(player.state.value.tracks.all.any { it.kind == TrackKind.Video }, "no video track")
                player.play()
                awaitOutput("at the start", player, output) {
                    val pictures = screen.pictures.filter { it.ptsMicros in 0L..3_000_000L }
                    player.position() >= 1.seconds && output.nonSilentFrames.get() >= 24_000 &&
                        pictures.size >= 15 && pictures.last().ptsMicros - pictures.first().ptsMicros >= 500_000L
                }
                val firstPictures = screen.pictures.toList()
                assertTrue(firstPictures.map { it.checksum }.distinct().size >= 2, "the renderer saw no changing decoded picture")
                assertTrue(backend.audio.any { it.ptsMicros in 0L..2_000_000L }, "no audio decoded near the beginning")
                val firstGeneration = firstPictures.last().generation
                val stoppedBeforeSeek = output.stops.get()

                withTimeout(15.seconds) { player.seek(60.seconds, SeekMode.Precise) }
                assertTrue(output.stops.get() > stoppedBeforeSeek, "the seek never stopped and discarded the old sound")
                val heardAfterSeekReturned = output.nonSilentFrames.get()
                // A progress flow can still contain its old value after a seek. Require a new
                // decoded generation at the target and fresh non-silent PCM after seek returned.
                awaitOutput("after seeking to 60 s", player, output) {
                    val pictures = screen.pictures.filter { it.generation != firstGeneration && it.ptsMicros in 60_000_000L..64_000_000L }
                    val sound = backend.audio.filter { it.generation != firstGeneration && it.ptsMicros in 59_900_000L..64_000_000L }
                    player.position() >= 61.seconds &&
                        output.nonSilentFrames.get() - heardAfterSeekReturned >= 24_000 &&
                        pictures.size >= 15 && pictures.last().ptsMicros - pictures.first().ptsMicros >= 500_000L &&
                        sound.size >= 20 && sound.last().ptsMicros - sound.first().ptsMicros >= 500_000L
                }
                val after = screen.pictures.filter { it.generation != firstGeneration }
                assertTrue(after.first().ptsMicros in 60_000_000L..60_100_000L, "the first picture after the precise seek was at ${after.first().ptsMicros} us")
                assertTrue(after.map { it.checksum }.distinct().size >= 2, "video stopped changing after the seek")
                assertEquals(PlaybackStatus.Playing, player.state.value.status)
                assertTrue(server.asked.any { it.startsWith("separate-1-0003") }, "no video segment near 60 s was requested: ${server.asked}")
                assertTrue(server.asked.any { it.startsWith("separate-2-0003") }, "no audio segment near 60 s was requested: ${server.asked}")
                println("DASH played non-silent PCM and decoded video, sought to 60 s, and advanced to ${player.position()}")
            } finally {
                // Interrupt a native read as well as cancelling a coroutine, including on timeout.
                backend.source.get()?.interrupt()
                try {
                    withContext(NonCancellable) { withTimeout(10.seconds) { player.closeAndAwait() } }
                } finally {
                    try {
                        output.close()
                    } finally {
                        try {
                            screen.close()
                        } finally {
                            try {
                                resolver.close()
                            } finally {
                                client.close()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitOutput(
        phase: String,
        player: KitePlayer,
        output: CapturingOutput,
        ready: () -> Boolean,
    ) {
        val reached = withTimeoutOrNull(15.seconds) {
            while (true) {
                output.failure.get()?.let { throw AssertionError("the sound device failed $phase", it) }
                check(player.state.value.error == null) { "player failed $phase: ${player.state.value.error}" }
                if (ready()) break
                delay(10)
            }
            true
        }
        assertTrue(reached == true, "no progressing picture and non-silent sound $phase: ${player.state.value}, ${player.progress.value}; nonSilentFrames=${output.nonSilentFrames.get()}")
    }
}
