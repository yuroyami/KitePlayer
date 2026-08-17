package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Typed filter attachment on open (S4.e): a chain set on the source runs every decoded frame
 * through KiteCodec's graph, built lazily from the first frame's own geometry; hardware stands
 * down for it with a warning under Auto. Real media, so this runs where the matrix runs.
 */
class FilterAttachmentTest {

    @Test
    fun attachedFilterScalesEveryDecodedFrame() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteCodecSource
        source.videoFilterDescription = "scale=160:90"
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = assertNotNull(
                source.videoDecoderFactories().first().create(video, HwdecPolicy.Off),
                "the software decoder must open with a filter attached",
            )
            try {
                var seen = 0
                while (seen < 5) {
                    val packet = source.readPacket() ?: break
                    if (packet.streamIndex != video.index) {
                        packet.close()
                        continue
                    }
                    try {
                        while (!decoder.send(packet)) {
                            val frame = decoder.receive() ?: continue
                            assertEquals(160, frame.size.width, "the filter must scale the width")
                            assertEquals(90, frame.size.height, "the filter must scale the height")
                            seen++
                            frame.close()
                        }
                    } finally {
                        packet.close()
                    }
                    while (true) {
                        val frame = decoder.receive() ?: break
                        assertEquals(160, frame.size.width, "the filter must scale the width")
                        assertEquals(90, frame.size.height, "the filter must scale the height")
                        seen++
                        frame.close()
                    }
                }
                assertTrue(seen >= 5, "at least five filtered frames must come out, saw $seen")
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun hardwareStandsDownWithAWarningWhenAFilterIsAttached() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteCodecSource
        source.videoFilterDescription = "eq=brightness=0.1"
        val warnings = mutableListOf<PlaybackWarning>()
        source.onWarning = { warnings += it }
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = source.videoDecoderFactories().first().create(video, HwdecPolicy.Auto)
            if (decoder != null) {
                assertEquals(
                    HwdecStatus.Software,
                    decoder.hardware,
                    "with a filter attached the Auto route must be software",
                )
                decoder.close()
            }
            // On a host whose selection offers hardware for this codec, the stand-down says why.
            if (platformDecoderSelection(video.codec, HwdecPolicy.Auto).hardware != null) {
                assertTrue(
                    warnings.filterIsInstance<PlaybackWarning.HardwareDecodeUnavailable>()
                        .any { "filter" in it.reason },
                    "the stand-down must warn typed, got $warnings",
                )
            }
        } finally {
            source.close()
        }
    }
    // Audit F-FLT1: a filtered decoder that never decoded a frame has no graph to flush, and
    // isDrained must say so instead of holding the whole end of stream off for ever.
    @Test
    fun aFilteredDecoderThatNeverDecodedStillDrains() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteCodecSource
        source.videoFilterDescription = "scale=160:90"
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = assertNotNull(source.videoDecoderFactories().first().create(video, HwdecPolicy.Off))
            try {
                decoder.send(null)
                while (decoder.receive() != null) { /* drain whatever the flush yields */ }
                assertTrue(
                    decoder.isDrained,
                    "no frame ever entered the graph, so there is nothing left to flush",
                )
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }
}
