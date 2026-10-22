package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * An FFmpeg audio buffer decodes straight into the engine's interleaved array (#246). The engine
 * used to read it channel by channel through a planar scratch, which made the buffer decode an
 * interleaved copy of its own first and then copied every sample twice more.
 */
class AudioInterleaveTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBy(block: () -> Unit): Long {
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    private suspend fun buffers(decoder: AudioDecoder, source: KiteFFmpegSource, count: Int): List<AudioBuffer> {
        val out = ArrayList<AudioBuffer>()
        while (out.size < count) {
            val packet = source.readPacket() ?: break
            try {
                while (!decoder.send(packet)) out += decoder.receive() ?: break
            } finally {
                packet.close()
            }
            while (true) out += decoder.receive() ?: break
        }
        return out
    }

    /** The engine's read before this change: one channel at a time through a planar scratch. */
    private fun planarRead(buffer: AudioBuffer, planar: FloatArray, interleaved: FloatArray) {
        val channels = buffer.format.channels
        for (channel in 0 until channels) {
            buffer.copyChannel(channel, planar)
            for (frame in 0 until buffer.frameCount) interleaved[frame * channels + channel] = planar[frame]
        }
    }

    @Test
    fun copyInterleavedGivesTheSameSamplesAndAllocatesOnlyThePlaneCopy() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh")
        for (file in listOf("audio-aac.m4a", "audio-flac.flac", "audio-mp3.mp3")) {
            val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteFFmpegSource
            try {
                val stream = source.streams.first { it.kind == TrackKind.Audio }
                source.selectStreams(setOf(stream.index))
                val decoder = source.newAudioDecoder(stream)
                val decoded = buffers(decoder, source, count = 4)
                try {
                    check(decoded.size >= 4) { "$file decoded ${decoded.size} buffers" }
                    val values = decoded.maxOf { it.frameCount * it.format.channels }
                    val reused = FloatArray(values + 3)
                    val planar = FloatArray(decoded.maxOf { it.frameCount })
                    val old = FloatArray(values)

                    // The same samples as the old read, at the offset asked for.
                    val first = decoded[0]
                    val count = first.frameCount * first.format.channels
                    first.copyInterleaved(reused, offset = 3)
                    planarRead(first, planar, old)
                    assertContentEquals(old.copyOf(count), reused.copyOfRange(3, 3 + count), "$file samples")

                    // Warm both paths on a buffer of their own, so class loading is not counted.
                    decoded[1].copyInterleaved(reused)
                    planarRead(decoded[1], planar, old)

                    val direct = allocatedBy { decoded[2].copyInterleaved(reused) }
                    val throughPlanar = allocatedBy { planarRead(decoded[3], planar, old) }
                    val third = decoded[2]
                    val planeBytes = third.frameCount.toLong() * third.format.channels * 4
                    println(
                        "$file: ${third.frameCount} frames of ${third.format.channels} channels, " +
                            "copyInterleaved allocated $direct bytes, the planar read $throughPlanar",
                    )
                    // What is left is KiteFFmpeg's own copy of the planes, until its copy into a
                    // caller's array can be used here. The float array of its own is gone.
                    assertTrue(
                        direct < throughPlanar,
                        "$file: copyInterleaved allocated $direct bytes, not less than the planar read's $throughPlanar",
                    )
                    assertTrue(direct <= planeBytes * 2 + 1024, "$file: copyInterleaved allocated $direct bytes")
                } finally {
                    decoded.forEach { it.close() }
                    decoder.close()
                }
            } finally {
                source.close()
            }
        }
    }
}
