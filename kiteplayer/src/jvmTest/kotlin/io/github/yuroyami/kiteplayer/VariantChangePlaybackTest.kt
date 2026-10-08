package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIoResolver
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A quality change while the whole player plays, with no new open (#464): a forced step up and a
 * forced step down, through real HTTP, FFmpeg and the engine, onto a screen and a sound device
 * paced on the real clock. At a change no picture shows twice, at most three in a row are dropped
 * for coming late on a slow machine, and the sound has no gap.
 */
class VariantChangePlaybackTest {

    @Test
    fun anMp4LadderStepsUpAndDownWithNoHeldPictureAndNoGapInSound() =
        // From 1280x720 up to 1920x1080 and down again.
        stepsUpAndDown("hls", "ladder.m3u8", startHeight = 720, up = 2, down = 1, master = "ladder.m3u8", segments = Regex("ladder-(\\d)-(\\d+)\\.m4s"))

    @Test
    fun anEncryptedMp4LadderStepsUpAndDownWithNoHeldPictureAndNoGapInSound() =
        // The same two variants under AES-128, each with a key of its own (#565).
        stepsUpAndDown("hls", "ladder-aes.m3u8", startHeight = 720, up = 2, down = 1, master = "ladder-aes.m3u8", segments = Regex("ladder-aes-(\\d)-(\\d+)\\.m4s"))

    @Test
    fun anMpegTsStreamStepsUpAndDownWithNoHeldPictureAndNoGapInSound() =
        stepsUpAndDown("hls", "ts.m3u8", startHeight = 180, up = 1, down = 0, master = "ts.m3u8", segments = Regex("ts-(\\d)-(\\d+)\\.ts"))

    @Test
    fun aDashManifestStepsUpAndDownWithNoHeldPictureAndNoGapInSound() =
        stepsUpAndDown("dash", "separate.mpd", startHeight = 180, up = 1, down = 0, master = "separate.mpd", segments = Regex("separate-([01])-(\\d+)\\.m4s"))

    /**
     * Plays [name] of the fixture folder [folder] from the variant of [startHeight], moves to the
     * variant [up] once pictures show, and to [down] once a picture of [up] showed. [segments]
     * reads a segment request's variant and number.
     */
    private fun stepsUpAndDown(folder: String, name: String, startHeight: Int, up: Int, down: Int, master: String, segments: Regex) = runBlocking {
        val media = requireTestMedia(
            sequenceOf(
                System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, folder) },
                File("testmedia/$folder"),
                File("../testmedia/$folder"),
            ).filterNotNull().firstOrNull { File(it, name).isFile },
            "testmedia/$folder/$name is missing; run scripts/testmedia.sh",
        )
        FixtureServer(media).use { server ->
            val client = HttpClient()
            val resolver = KtorMediaIoResolver(
                client, policy = HttpReaderPolicy(connectTimeout = 5.seconds, readTimeout = 5.seconds, maxReconnects = 0),
            )
            val output = CapturingOutput(silenceAfter = START_FRAMES)
            val screen = CapturingScreen()
            val warnings = CopyOnWriteArrayList<PlaybackWarning>()
            val player = KitePlayer.create(
                PlayerConfig(
                    backends = Backends(ObservedBackend(), output),
                    network = NetworkConfig(ioResolver = resolver),
                    hardwareDecode = HwdecPolicy.Off,
                    // A short read-ahead, so a change shows within the twelve seconds of the fixtures.
                    buffer = BufferPolicy(totalDuration = 2.seconds, stallTimeout = 10.seconds),
                ),
            )
            val watching = CoroutineScope(Dispatchers.Default)
            try {
                watching.launch {
                    player.events.collect {
                        if (it is PlayerEvent.Warning) warnings += it.warning
                    }
                }
                withTimeout(15.seconds) {
                    player.attachRendererAndAwait(screen)
                    player.open(MediaItem("${server.root}/$name", demux = DemuxPolicy(maxVideoHeight = startHeight)))
                }
                val variants = player.state.value.tracks.variants
                val first = checkNotNull(player.state.value.tracks.selectedVariant) { "no variant plays: $variants" }
                val sizes = listOf(first, up, down).map { index -> variants.first { it.index == index }.let { VideoSize(it.width!!, it.height!!) } }
                assertEquals(startHeight, sizes[0].height, "the stream did not open on the variant asked for")
                player.play()

                suspend fun awaitPictureOf(size: VideoSize, after: Int, what: String): Int {
                    val reached = withTimeoutOrNull(15.seconds) {
                        while (true) {
                            check(player.state.value.error == null) { "the player failed: ${player.state.value.error}" }
                            val at = screen.pictures.withIndex().indexOfFirst { it.index >= after && it.value.size == size }
                            if (at >= 0) return@withTimeoutOrNull at
                            delay(10)
                        }
                        @Suppress("UNREACHABLE_CODE") -1
                    }
                    return checkNotNull(reached) { "no picture of $size showed $what: ${screen.pictures.map { it.size }.distinct()}, ${player.state.value}" }
                }

                val started = awaitPictureOf(sizes[0], 0, "at the start")
                withTimeout(15.seconds) { player.selectVariant(up) }
                val raised = awaitPictureOf(sizes[1], started, "after the step up")
                withTimeout(15.seconds) { player.selectVariant(down) }
                val lowered = awaitPictureOf(sizes[2], raised, "after the step down")
                // A second of the last variant, so the change is not the end of what was measured.
                withTimeoutOrNull(10.seconds) {
                    while (screen.pictures.size < lowered + 30 && player.state.value.status != PlaybackStatus.Ended) delay(10)
                }
                player.pause()

                val pictures = screen.pictures.toList()
                val shown = pictures.map { it.ptsMicros }
                val steps = shown.zipWithNext { a, b -> b - a }
                fun told(at: List<IndexedValue<Long>>) = at.map { "after ${shown[it.index]} us at ${pictures[it.index].size.height}: ${it.value} us to ${pictures[it.index + 1].size.height}" }
                assertTrue(steps.all { it >= 30_000 }, "a picture is shown twice: ${told(steps.withIndex().filter { it.value < 30_000 })}")
                // The second of pictures around each change. A slow machine drops a picture that came
                // late, which is the engine's own rule: CI's Mac dropped one or two in a row, at a
                // change and away from one. A new open loses far more than that.
                val around = listOf(raised, lowered).flatMap { (it - 15 until it + 15).toList() }.filter { it in steps.indices }
                assertTrue(around.all { steps[it] <= MAX_STEP_MICROS }, "pictures are missing at a change: ${told(around.map { IndexedValue(it, steps[it]) }.filter { it.value > MAX_STEP_MICROS })}")
                val missing = steps.sumOf { (it - 30_000) / 33_333 }
                assertTrue(missing <= MAX_LATE_PICTURES, "$missing pictures are missing: ${told(steps.withIndex().filter { it.value > 37_000 })}")
                assertEquals(1, pictures.map { it.generation }.distinct().size, "the stream opened again, or sought")
                assertEquals(listOf(sizes[0], sizes[1], sizes[2]), pictures.map { it.size }.fold(listOf<VideoSize>()) { all, size -> if (all.lastOrNull() == size) all else all + size })
                assertEquals(1, server.asked.count { it == master }, "the stream opened again: ${server.asked}")
                val read = server.asked.mapNotNull { segments.matchEntire(it) }.map { it.groupValues[2] to it.groupValues[1] }
                assertEquals(read.distinctBy { it.first }, read.distinct(), "a segment was read from two variants")

                // Each picture of those seconds against the one before it, on the clock of the screen it reached.
                val held = pictures.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000.0 }
                val longest = around.maxOf { held[it] }
                println("variant change $name: ${pictures.size} pictures, $missing missing, longest time between two at a change ${"%.1f".format(longest)} ms, " +
                    "longest silence ${output.longestSilence.get()} frames")
                assertTrue(longest <= MAX_HELD_MILLIS, "a picture was held for $longest ms at a change, and its own time is ${"%.1f".format(FRAME_MILLIS)} ms")
                assertTrue(output.longestSilence.get() <= MAX_SILENT_FRAMES, "the sound stopped for ${output.longestSilence.get()} frames")
                assertEquals(emptyList(), warnings.toList(), "a change that was asked for warns of nothing")
                assertEquals(null, output.failure.get())
            } finally {
                watching.cancel()
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

    private companion object {
        /** One picture's time at the fixtures' 30 pictures a second. */
        const val FRAME_MILLIS = 1000.0 / 30

        /**
         * A sine passes zero, and the fixtures' passes through a sample of exactly zero now and
         * then, so a frame or two of silence is the sound itself. A gap of one packet is 1024.
         */
        const val MAX_SILENT_FRAMES = 8L

        /** Half a second of pictures over the whole run, which a slow machine may drop for coming late. */
        const val MAX_LATE_PICTURES = 15L

        /** Three pictures dropped in a row at a change, and the time the one before them then stays. */
        const val MAX_STEP_MICROS = 135_000L
        const val MAX_HELD_MILLIS = 5 * FRAME_MILLIS

        /** A tenth of a second of sound at 48 kHz, past the fade in of the encoder's first packets. */
        const val START_FRAMES = 4800L
    }
}
