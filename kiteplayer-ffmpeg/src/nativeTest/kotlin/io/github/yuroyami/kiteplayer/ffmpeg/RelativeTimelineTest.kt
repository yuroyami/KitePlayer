@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timeline the engine sees, checked on media whose container timeline is nowhere near zero.
 *
 * `tsoffset1400.ts` is `sync1080p30.mp4` remuxed to MPEG-TS with its timestamps pushed 1400 seconds
 * forward, so its container starts at about 1401.4 seconds. Every assertion about a point on the
 * timeline below fails by roughly that amount if the origin is subtracted in the wrong place, or not
 * at all, or twice. The assertion about an interval fails the other way, if an origin is subtracted
 * from something that has none.
 */
class RelativeTimelineTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    /** One frame at 30 fps, which is the tolerance the first frame gets. */
    private val frameDurationUs = 33_333L

    @Test
    fun `the first video frame of an offset transport stream lands at the timeline origin`() = runBlocking {
        val source = open("tsoffset1400.ts")
        try {
            val stream = assertNotNull(source.firstVideo, "the fixture has a video stream")
            source.selectStreams(setOf(stream.index))
            val decoder = videoDecoder(source, stream.index)
            val micros = try {
                val frame = assertNotNull(firstFrame(source, stream.index, decoder), "no frame decoded")
                frame.pts.micros.also { frame.close() }
            } finally {
                decoder.close()
            }

            // Absolute, this frame sits at 1 401 400 000 us. The engine's timeline starts at zero, so
            // it must arrive within one frame of that: the clip's audio starts 21 ms before its video
            // and the container start is the earlier of the two, so the first picture is not exactly 0.
            assertTrue(
                abs(micros) <= frameDurationUs,
                "the first frame of an offset transport stream reported $micros us. The engine " +
                    "timeline starts at zero, so this must be within one frame duration " +
                    "($frameDurationUs us) of it. A value near 1 401 400 000 means the container " +
                    "start offset was never subtracted.",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `packet durations are the same with and without the offset`() = runBlocking {
        // The differential that names the bug. The two files are the same pictures in two containers,
        // one shifted 1400 seconds and one not, so any difference here is the mapping and not the
        // media.
        val shifted = videoPacketDurations("tsoffset1400.ts", count = 30)
        val plain = videoPacketDurations("sync1080p30.mp4", count = 30)

        assertEquals(
            plain,
            shifted,
            "the same packets came out with different durations once the container was shifted. A " +
                "duration is an interval and has no origin, so nothing about the container start " +
                "applies to it",
        )
        // And the value itself is right, not merely equal on both sides.
        assertEquals(
            listOf(frameDurationUs),
            plain.distinct(),
            "expected every packet of a 30 fps stream to last $frameDurationUs us",
        )
    }

    @Test
    fun `decode timestamps are relative too`() = runBlocking {
        val source = open("tsoffset1400.ts")
        try {
            val stream = assertNotNull(source.firstVideo)
            source.selectStreams(setOf(stream.index))
            val packet = assertNotNull(nextPacket(source, stream.index), "no video packet")
            val dts = assertNotNull(packet.dts, "MPEG-TS carries a decode timestamp on every packet")
            val pts = assertNotNull(packet.pts)
            packet.close()

            // This used to be the packet's raw tick count wrapped in a microsecond type. At 1/90000
            // that reads as 126 126 000 us, which is 126 seconds: wrong by a scale factor and by an
            // origin at once, and small enough to look plausible.
            assertTrue(
                abs(dts.micros) <= frameDurationUs,
                "the first decode timestamp reported ${dts.micros} us, which is neither relative " +
                    "nor in microseconds. Raw ticks here would read as 126 126 000.",
            )
            // The clip has no B-frames, so nothing is reordered and the two agree exactly.
            assertEquals(pts.micros, dts.micros, "with no reordering the two timestamps are the same")
        } finally {
            source.close()
        }
    }

    @Test
    fun `a stream with no timestamps is counted forward from its own frame durations`() = runBlocking {
        // The synthesis half of the relative timeline, on real media rather than a contrived frame.
        // An Annex B elementary stream carries no container timestamps at all, so every frame reaches
        // the wrapper with none and the running counter is the only thing that can date it.
        val source = open("novts.h264")
        try {
            val stream = assertNotNull(source.firstVideo)
            assertEquals(25.0, stream.frameRate, "the fixture is one second of 25 fps video")
            source.selectStreams(setOf(stream.index))
            val decoder = videoDecoder(source, stream.index)

            val stamps = mutableListOf<Long>()
            var withoutTimestamp = 0
            fun take(frame: VideoFrame) {
                stamps += frame.pts.micros
                if (!(frame as KiteCodecVideoFrame).hasPts) withoutTimestamp++
                frame.close()
            }

            try {
                // The canonical loop: a refused packet is offered again after a drain, never dropped.
                while (true) {
                    val packet = nextPacket(source, stream.index)
                    if (packet == null) {
                        decoder.send(null)
                        while (true) take(decoder.receive() ?: break)
                        break
                    }
                    while (!decoder.send(packet)) take(decoder.receive() ?: refusedNothing())
                    packet.close()
                    while (true) take(decoder.receive() ?: break)
                }
            } finally {
                decoder.close()
            }

            assertEquals(25, stamps.size, "the fixture is one second at 25 fps")
            assertEquals(
                25,
                withoutTimestamp,
                "every frame of an elementary stream should have arrived without a timestamp",
            )
            assertEquals(
                List(25) { it * 40_000L },
                stamps,
                "a timestampless stream starts at the origin and steps by the decoder's own frame " +
                    "duration of 40 000 us. Twenty five zeroes would mean the fabricated timestamp " +
                    "this replaced is back, and a repeated value anywhere would mean the counter is " +
                    "not advancing",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `buffered frames keep their own generation until a flush moves the epoch`() = runBlocking {
        // D22. The decoder wrappers used to stamp the generation on every send, before knowing whether
        // the packet was even accepted, so frames decoded in the old epoch surfaced wearing the new
        // one. Now send cannot touch it and flush is the only boundary.
        val source = open("sync1080p30.mp4")
        try {
            val stream = assertNotNull(source.firstVideo)
            source.selectStreams(setOf(stream.index))
            val decoder = videoDecoder(source, stream.index)
            try {
                // Feed without draining until the decoder refuses. Output is pending from then on,
                // which is the state the defect needed.
                var refused: PlayerPacket? = null
                var offered = 0
                while (refused == null && offered < PACKET_FEED_LIMIT) {
                    val packet = assertNotNull(nextPacket(source, stream.index), "ran out of packets")
                    offered++
                    if (decoder.send(packet)) packet.close() else refused = packet
                }
                assertNotNull(
                    refused,
                    "the decoder took $offered packets without ever asking to be drained, so this " +
                        "test never reached the state it is about",
                )
                // The refused packet was not consumed. This test abandons the old epoch here, so it is
                // released rather than offered again, which is what a seek does to a queue.
                refused.close()

                val buffered = assertNotNull(decoder.receive(), "a refusal means output is pending")
                assertEquals(
                    Generation.Initial,
                    buffered.generation,
                    "a frame decoded in the initial epoch belongs to it, however many sends followed",
                )
                buffered.close()

                val next = Generation.Initial.next()
                decoder.flush(next)
                assertNull(
                    decoder.receive(),
                    "a flush drops what was buffered, so no frame of the old epoch survives it",
                )

                val fresh = assertNotNull(
                    firstFrame(source, stream.index, decoder),
                    "the decoder produced nothing after the flush",
                )
                assertEquals(next, fresh.generation, "frames after the flush carry the epoch it set")
                fresh.close()
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }

    private suspend fun open(file: String): KiteCodecSource =
        KiteCodecSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteCodecSource

    private suspend fun videoDecoder(source: KiteCodecSource, index: Int): VideoDecoder =
        assertNotNull(
            source.videoDecoderFactories().first()
                .create(source.streams.first { it.index == index }, HwdecPolicy.Auto),
            "no video decoder for stream $index",
        )

    /** The next packet of [index], skipping and closing everything else. */
    private suspend fun nextPacket(source: KiteCodecSource, index: Int): PlayerPacket? {
        while (true) {
            val packet = source.readPacket() ?: return null
            if (packet.streamIndex == index) return packet
            packet.close()
        }
    }

    /**
     * The next frame out of [decoder], reading as many packets as that takes.
     *
     * A refused packet is offered again after the drain rather than dropped, and anything decoded
     * beyond the one frame wanted is closed here.
     */
    private suspend fun firstFrame(source: KiteCodecSource, index: Int, decoder: VideoDecoder): VideoFrame? {
        var first: VideoFrame? = null
        while (first == null) {
            val packet = nextPacket(source, index)
            if (packet == null) {
                decoder.send(null)
                return decoder.receive()
            }
            while (!decoder.send(packet)) {
                val frame = decoder.receive() ?: refusedNothing()
                if (first == null) first = frame else frame.close()
            }
            packet.close()
            if (first == null) first = decoder.receive()
        }
        return first
    }

    private suspend fun videoPacketDurations(file: String, count: Int): List<Long> {
        val source = open(file)
        return try {
            val stream = assertNotNull(source.firstVideo, "no video stream in $file")
            source.selectStreams(setOf(stream.index))
            val out = mutableListOf<Long>()
            while (out.size < count) {
                val packet = assertNotNull(nextPacket(source, stream.index), "$file ran out of packets")
                out += assertNotNull(packet.duration, "$file gave a packet no duration").micros
                packet.close()
            }
            out
        } finally {
            source.close()
        }
    }

    private fun refusedNothing(): Nothing =
        error("the decoder refused a packet and produced nothing; this violates the codec contract")

    private companion object {
        /** Enough packets that a decoder which never asks to be drained fails instead of looping. */
        const val PACKET_FEED_LIMIT = 200
    }
}
