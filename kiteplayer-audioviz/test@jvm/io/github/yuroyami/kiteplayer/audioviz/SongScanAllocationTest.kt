package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The song scan reads four values of each analysis, and builds no display frame for them (#657).
 * It built one for every 10 ms hop, about 1.2 MB of garbage for each second of audio.
 */
class SongScanAllocationTest {

    @Test
    fun aScanAllocatesFarLessThanADisplayFramePerHop() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val format = AudioFormat(48_000, 2, SampleFormat.F32)
        val mono = SyntheticSong.drumLoop(60f)
        val block = FloatArray(1024 * 2)
        fun scan() {
            val builder = SongMapBuilder(TrackId(1))
            var start = 0
            while (start < mono.size) {
                val frames = minOf(1024, mono.size - start)
                for (i in 0 until frames) {
                    block[2 * i] = mono[start + i]
                    block[2 * i + 1] = mono[start + i]
                }
                builder.feed(Pts(start * 1_000_000L / 48_000), block, frames, format)
                start += frames
            }
            builder.build(complete = true)
        }
        scan() // Warm, so class loading and the JIT are not counted.
        val thread = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(thread)
        scan()
        val perSecond = (threads.getThreadAllocatedBytes(thread) - before) / 60
        assertTrue(perSecond < 300_000, "the scan allocated $perSecond bytes for each second of audio")
    }
}
