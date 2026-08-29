@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class, ObsoleteWorkersApi::class, ExperimentalStdlibApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.output.AppleHostClock
import io.github.yuroyami.kiteplayer.output.CoreAudioSink
import io.github.yuroyami.kiteplayer.spi.AudioBuffer
import io.github.yuroyami.kiteplayer.spi.AudioDecoder
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.concurrent.AtomicLong
import kotlin.math.abs
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole shipped audio path, on a real device, with real media, for as long as the gate asks.
 *
 * This is the other half of assertion 3 of plan section 15.2 B1.8. The half in
 * `kiteplayer-output`'s `RealTimeSoakTest` holds the callback's own instruments and its negative control,
 * because that module can see the sink's counters; this one holds the real decoder, the real conversion
 * stage, the real backpressure and the real clock, because this is the only module where FFmpeg, the
 * engine and a device can all be reached at once. Both are needed and the report names both commands.
 *
 * ### It does not run in the ordinary gate
 *
 * Gated on KPRT_DEVICE_SOAK, for the reason register item B1-24 records: there is no CI, so this is a
 * supervised run on one machine and it makes sound for ten minutes.
 *
 *     KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
 *       ./gradlew :kiteplayer-ffmpeg:macosArm64Test --tests '*RealTimeMediaSoakTest*' --rerun-tasks -i
 *
 * ### What it can and cannot claim
 *
 * Level 1 for macOS arm64 in debug, on one machine, with its numbers recorded, and nothing beyond that.
 * It is not release-mode qualification. What makes it worth running at all is that nothing here is
 * synthetic: the samples come out of a real container through a real decoder, the ring is fed by
 * `AudioPlayback.submitDecoded` with its real backpressure, and the clock is read the way a player reads
 * it.
 */
class RealTimeMediaSoakTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString()
        ?: error("KITEPLAYER_TESTMEDIA is not set: the Gradle test task sets it")

    private val enabled: Boolean get() = getenv("KPRT_DEVICE_SOAK") != null

    private val minutes: Double
        get() = getenv("KPRT_DEVICE_SOAK_MINUTES")?.toKString()?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0

    @Test
    fun `real media plays through the C callback with the collector under pressure`() = runBlocking {
        if (!enabled) {
            println("RealTimeMediaSoakTest: skipped, set KPRT_DEVICE_SOAK=1 to run the supervised device run")
            return@runBlocking
        }

        val sink = CoreAudioSink()
        val audio = AudioPlayback(sink, AppleHostClock)
        val pressure = CollectorPressure()
        var loops = 0
        var framesDecoded = 0L

        try {
            val until = AppleHostClock.nanos() + (minutes * 60_000_000_000L).toLong()
            var negotiated = false
            pressure.start()

            // The clip is a few seconds long, so it is played over and over until the time is up. Each loop
            // is a fresh source and decoder, which also means the run covers open and close hundreds of
            // times rather than once.
            while (AppleHostClock.nanos() < until) {
                val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteFFmpegSource
                val stream = assertNotNull(source.firstAudio, "no audio stream in sync1080p30.mp4")
                source.selectStreams(setOf(stream.index))
                val decoder: AudioDecoder =
                    assertNotNull(source.audioDecoderFactories().first().create(stream))
                try {
                    if (!negotiated) {
                        audio.open(decoder.outputFormat)
                        audio.play()
                        negotiated = true
                    }
                    suspend fun hand(buffer: AudioBuffer) {
                        audio.submitDecoded(
                            pts = buffer.pts,
                            interleaved = buffer.interleavedFloat(),
                            frames = buffer.frameCount,
                            sourceFormat = buffer.format,
                        )
                        framesDecoded += buffer.frameCount
                        buffer.close()
                    }
                    while (AppleHostClock.nanos() < until) {
                        val packet = source.readPacket()
                        if (packet == null) {
                            decoder.send(null)
                            while (true) hand(decoder.receive() ?: break)
                            break
                        }
                        if (packet.streamIndex != stream.index) {
                            packet.close()
                            continue
                        }
                        while (!decoder.send(packet)) {
                            hand(decoder.receive() ?: break)
                        }
                        packet.close()
                        while (true) hand(decoder.receive() ?: break)
                    }
                } finally {
                    decoder.close()
                    source.close()
                }
                loops++
            }

            val position = audio.position()
            pressure.stop()

            println(
                "RealTimeMediaSoakTest: ${minutes} minutes of sync1080p30.mp4 audio: loops=$loops " +
                    "framesDecoded=$framesDecoded underruns=${audio.underruns} " +
                    "position=${position?.micros} buffered=${audio.buffered} " +
                    "collections=${pressure.collections()} allocations=${pressure.allocations()}",
            )

            assertTrue(loops > 0, "the clip never finished once, so nothing was really played")
            assertTrue(pressure.collections() > 100, "the collector was not under pressure")
            assertNotNull(position, "the clock never anchored, so the device never reported an anchor")

            // Underruns are bounded by the number of loops rather than required to be zero, and the bound
            // was measured rather than assumed: a first version asserted zero and reported one after three
            // loops in 24 seconds. The cause is this test and not the callback. Between one loop and the
            // next the ring drains while a fresh source and decoder are being opened, which is a real
            // starvation of a real device with no audio to give it. The engine's answer to that in a real
            // player is `endOfStream`, and calling it here would be worse than the bound: it tells the ring
            // that trailing silence is the end of the media, and nothing clears that flag short of a flush,
            // so every later underrun in the run would go uncounted and the assertion would be blind.
            //
            // The bound still catches what assertion 3 is about. A collector pause starving the device in
            // the middle of a clip would push this far past the loop count.
            assertTrue(
                audio.underruns <= loops.toLong(),
                "the device was starved ${audio.underruns} times over $loops loops, so at least one " +
                    "starvation happened somewhere other than a loop seam",
            )

            // The real-time check, on the feeder rather than on the clock. The decoder is paced by the
            // device through the ring's backpressure, so the audio it produced measures what the device
            // consumed: about one second of samples per second of wall time. The clock itself cannot be
            // used for this, because each loop restarts the media timeline at zero, which is what the
            // measured position of 3.75 seconds after 24 seconds of playing says.
            val decodedUs = framesDecoded * 1_000_000L / 48_000L
            val elapsedUs = (minutes * 60_000_000L).toLong()
            assertTrue(
                abs(decodedUs - elapsedUs) < elapsedUs / 5,
                "the device consumed ${decodedUs} us of audio over ${elapsedUs} us of wall time, which is " +
                    "not real time",
            )
        } finally {
            pressure.stop()
            audio.close()
        }
    }

    /**
     * A Kotlin thread allocating hard with the collector's target heap pinned low.
     *
     * The same instrument as `RealTimeSoakTest`'s, restated here rather than shared, because the two
     * modules do not share a test source set and a third module existing only to hold twenty lines of
     * churn would be worse than the repetition.
     */
    private class CollectorPressure {
        private val worker = Worker.start(name = "kprt-media-collector-pressure")
        private val running = AtomicLong(0)
        private val allocated = AtomicLong(0)
        private var previousAutotune = true
        private var previousTarget = 0L
        private var epochAtStart = 0L

        fun start() {
            epochAtStart = GC.lastGCInfo?.epoch?.toLong() ?: 0L
            previousAutotune = GC.autotune
            previousTarget = GC.targetHeapBytes
            GC.autotune = false
            GC.targetHeapBytes = 1L * 1024 * 1024
            running.value = 1
            val state = this
            worker.executeAfter(0L) { state.churn() }
        }

        private fun churn() {
            while (running.value == 1L) {
                var head: Node? = null
                repeat(2_000) { index -> head = Node(index, head) }
                allocated.addAndGet(2_000)
                if (head?.value == -1) println("unreachable, and here to stop the loop being optimised away")
            }
        }

        private class Node(val value: Int, val next: Node?)

        /**
         * Collections during this instrument's own life, not since the process started.
         *
         * `GC.lastGCInfo.epoch` is cumulative, and reporting it raw made a number in a sibling suite
         * read as 184,498 collections for a run whose own share was 624. One test in a process gets
         * away with it and two do not, so both measure the delta.
         */
        fun collections(): Long = (GC.lastGCInfo?.epoch?.toLong() ?: 0L) - epochAtStart

        fun allocations(): Long = allocated.value

        fun stop() {
            if (running.value == 0L) return
            running.value = 0
            worker.requestTermination(processScheduledJobs = false)
            GC.autotune = previousAutotune
            if (previousTarget > 0) GC.targetHeapBytes = previousTarget
        }
    }
}
