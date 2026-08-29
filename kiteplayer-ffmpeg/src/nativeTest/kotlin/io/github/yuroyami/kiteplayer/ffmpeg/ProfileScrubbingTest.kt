@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackProfile
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * KD-6's real-media proof: the Scrubbing profile's decoder options, threaded through the
 * backend's constructor exactly as a consumer threads them, measurably change what the decoder
 * delivers on the conformance clip (keyframes only, so far fewer frames than the full decode).
 */
class ProfileScrubbingTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private suspend fun decodeCount(decoderOptions: Map<String, String>): Int {
        val backend = KiteFFmpegMediaBackend(decoderOptions = decoderOptions)
        val session = backend.open(MediaItem("$mediaDir/sync1080p30.mp4"))
        var received = 0
        try {
            val source = session.source
            val video = source.streams.first { it.kind == io.github.yuroyami.kiteplayer.TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = session.videoDecoders.first().create(video, HwdecPolicy.Off)
                ?: error("no video decoder")
            try {
                while (true) {
                    val packet = source.readPacket() ?: break
                    if (packet.streamIndex != video.index) {
                        packet.close()
                        continue
                    }
                    while (!decoder.send(packet)) {
                        decoder.receive()?.close() ?: break
                        received++
                    }
                    packet.close()
                    while (true) {
                        val frame = decoder.receive() ?: break
                        frame.close()
                        received++
                    }
                }
                decoder.send(null)
                while (true) {
                    val frame = decoder.receive() ?: break
                    frame.close()
                    received++
                }
            } finally {
                decoder.close()
            }
        } finally {
            session.close()
        }
        return received
    }

    @Test
    fun theScrubbingProfileDecodesOnlyKeyframesOnRealMedia() = runBlocking {
        val full = decodeCount(emptyMap())
        val scrubbed = decodeCount(PlaybackProfile.Scrubbing.decoderOptions)
        assertTrue(full >= 250, "the sync clip holds 300 frames; full decode got $full")
        assertTrue(
            scrubbed in 1..(full / 5),
            "scrubbing delivered $scrubbed of $full; the profile's options did not reach FFmpeg",
        )
    }
}
