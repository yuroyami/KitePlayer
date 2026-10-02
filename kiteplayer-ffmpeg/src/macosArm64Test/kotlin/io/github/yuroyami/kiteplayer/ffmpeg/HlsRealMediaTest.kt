@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The whole player on the real CoreAudio device, playing the `testmedia/hls` streams through a
 * reader that serves them as a server would (#209): it starts, keeps time, seeks, and ends as
 * complete rather than failed.
 */
class HlsRealMediaTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val hlsDir: String = (platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia") + "/hls"

    /** A reader for one file of [hlsDir] at [location], which opens the other files by address. */
    private inner class FileReader(override val location: String, private val bytes: ByteArray) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }

        override suspend fun openRelated(uri: String): MediaIo? = reader(uri)

        override fun close() = Unit
    }

    private fun reader(address: String): MediaIo? =
        readTestFile("$hlsDir/${address.substringAfterLast('/')}")?.let { FileReader(address, it) }

    private fun item(name: String): MediaItem =
        MediaItem("https://cdn.test/hls/$name", io = { checkNotNull(reader("https://cdn.test/hls/$name")) })

    private suspend fun KitePlayer.awaitStatus(status: PlaybackStatus, within: Duration) {
        withTimeout(within) { while (state.value.status != status) delay(20) }
    }

    @Test
    fun aMasterPlaylistPlaysSeeksAndEndsComplete() = runBlocking {
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = AppleOutputBackend),
                progressInterval = 50.milliseconds,
            ),
        )
        try {
            withTimeout(15.seconds) { player.open(item("ts.m3u8")) }
            val duration = assertNotNull(player.state.value.duration)
            assertTrue(duration in 11.5.seconds..12.5.seconds, "the stream lasts $duration")
            player.play()
            delay(2.seconds)
            val early = player.position()
            assertTrue(early in 1.seconds..3.seconds, "two seconds of play reached $early")
            assertEquals(PlaybackStatus.Playing, player.state.value.status)

            player.seek(8.seconds)
            val landed = player.position()
            assertTrue(landed in 7.9.seconds..8.3.seconds, "the seek to 8 s landed at $landed")
            player.awaitStatus(PlaybackStatus.Ended, within = 10.seconds)

            val warnings = player.warningHistory().map { it.warning }
            assertTrue(warnings.none { it is PlaybackWarning.SegmentSkipped }, "a segment was skipped: $warnings")
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun aSeparateAudioRenditionKeepsTimeWithThePicture() = runBlocking {
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = AppleOutputBackend),
                progressInterval = 50.milliseconds,
            ),
        )
        try {
            withTimeout(15.seconds) { player.open(item("alt.m3u8")) }
            assertNotNull(player.state.value.tracks.selectedAudio, "no sound track was chosen from the rendition")
            player.play()
            delay(3.seconds)
            val position = player.position()
            assertTrue(position in 2.seconds..4.seconds, "three seconds of play reached $position")
            assertEquals(PlaybackStatus.Playing, player.state.value.status)
            val drift = player.stats.value.avDrift
            assertTrue(drift.absoluteValue < 100.milliseconds, "the picture and the sound drifted $drift apart")
        } finally {
            player.closeAndAwait()
        }
    }
}
